package com.joebrowser.app.browser

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.webkit.Profile as WebKitProfile
import androidx.webkit.ProfileStore
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.joebrowser.app.core.Async
import com.joebrowser.app.data.Profile
import com.joebrowser.app.data.ProfileStorage
import com.joebrowser.app.fingerprint.FingerprintScript

/**
 * The browser kernel.
 *
 * Owns the two guarantees the app makes to the user about a profile:
 *
 * ### 1. Storage isolation
 * Each profile is bound to its own AndroidX WebKit `Profile` whose name is an opaque token
 * stored inside the profile's private directory ([ProfileStorage.kernelToken]). Cookies,
 * cache, localStorage and IndexedDB land in that profile's bucket, and no other profile can
 * address them. When the installed WebView lacks `MULTI_PROFILE`, the kernel records
 * [IsolationMode.SHARED] and the UI says so honestly instead of pretending.
 *
 * ### 2. Earliest-possible fingerprint injection
 * The spoofing script is attached with `addDocumentStartJavaScript` before the first
 * navigation, which is the earliest hook Android exposes — it runs ahead of any page script,
 * so `navigator`, `screen`, WebGL and canvas are already answering as the profile when the
 * page's own detection code runs. If that API is unavailable, the kernel re-injects from
 * `onPageStarted` **and** `onPageCommitVisible` and reports [InjectionMode.EARLY_FALLBACK].
 *
 * The kernel is deliberately stateless between sessions: it is constructed per WebView,
 * [install]s once, and reports back through the supplied listener. Editing a profile's
 * identity mid-session is handled by [refreshScript], which replaces the attached rule
 * rather than stacking another one on top of it.
 */
class BrowserKernel(
    private val context: Context,
    private val profile: Profile,
    private val onStatus: (KernelStatus) -> Unit
) {

    enum class IsolationMode { ISOLATED, SHARED }

    enum class InjectionMode { DOCUMENT_START, EARLY_FALLBACK }

    /** What the kernel had to give up, if anything. A type, not a sentence: the UI owns wording. */
    enum class Warning {
        /** No storage isolation: every profile shares one cookie jar and cache. */
        SHARED_STORAGE,
        /** No document-start hook: the spoof runs at page start instead, one beat later. */
        LATE_INJECTION
    }

    data class KernelStatus(
        val isolation: IsolationMode,
        val injection: InjectionMode,
        val profileToken: String,
        /** Set when the kernel had to degrade; shown once in the browser UI. */
        val warning: Warning? = null
    )

    private var script: String = ""
    private var installed = false
    private var token: String = ""

    /**
     * Handle to the document-start rule currently attached to the WebView.
     *
     * Kept so a refresh can remove it: `addDocumentStartJavaScript` has no replace semantics,
     * and stacking a second rule would leave the old identity's script running first, which
     * defeats the point of the update. `ScriptHandler.remove()` is the documented undo.
     */
    private val startHandles = java.util.WeakHashMap<WebView, ScriptHandler>()

    private var injectionMode: InjectionMode = InjectionMode.EARLY_FALLBACK
    private var isolationMode: IsolationMode = IsolationMode.SHARED

    /**
     * Prepares storage and builds the spoofing payload. Must run on the main thread before
     * the WebView is created, because `WebViewCompat.setProfile` is only legal pre-create.
     */
    fun prepare(): KernelStatus {
        script = runCatching {
            FingerprintScript.build(
                profile.fp,
                profile.engine,
                profile.tools.unlockSelection
            )
        }.getOrElse {
            Log.e(Async.TAG, "Fingerprint script build failed", it)
            ""
        }
        token = runCatching { ProfileStorage.kernelToken(context, profile.id) }.getOrDefault(profile.id)
        ProfileStorage.ensure(context, profile.id)
        return status()
    }

    /**
     * Binds a brand-new, untouched WebView to the profile's isolated bucket BEFORE any
     * `getSettings()`, client assignment, or JavascriptInterface call occurs.
     */
    fun bindProfileEarly(webView: WebView) {
        isolationMode = if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            try {
                val store = store() ?: throw IllegalStateException("ProfileStore unavailable")
                val bucket: WebKitProfile = store.getOrCreateProfile(token)
                WebViewCompat.setProfile(webView, bucket.name)
                IsolationMode.ISOLATED
            } catch (t: Throwable) {
                Log.w(Async.TAG, "MULTI_PROFILE binding failed; falling back to shared storage", t)
                IsolationMode.SHARED
            }
        } else {
            IsolationMode.SHARED
        }
    }

    /**
     * Attaches per-profile cookie policy and document-start spoofing script onto a WebView
     * whose profile was bound via [bindProfileEarly]. Call before `loadUrl`.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun install(webView: WebView) {
        installed = true

        if (isolationMode == IsolationMode.SHARED && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            // Fallback if caller didn't call bindProfileEarly first
            runCatching {
                val store = store() ?: throw IllegalStateException("ProfileStore unavailable")
                val bucket: WebKitProfile = store.getOrCreateProfile(token)
                WebViewCompat.setProfile(webView, bucket.name)
                isolationMode = IsolationMode.ISOLATED
            }.onFailure {
                Log.w(Async.TAG, "Late MULTI_PROFILE binding skipped", it)
            }
        }

        // ---- per-profile cookie policy
        runCatching {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) &&
                isolationMode == IsolationMode.ISOLATED
            ) {
                val bucketManager = store()?.getOrCreateProfile(token)?.cookieManager
                if (bucketManager != null) {
                    bucketManager.setAcceptCookie(true)
                    bucketManager.setAcceptThirdPartyCookies(webView, true)
                    return@runCatching
                }
            }
            val cookies = CookieManager.getInstance()
            cookies.setAcceptCookie(true)
            cookies.setAcceptThirdPartyCookies(webView, true)
        }

        // ---- fingerprint injection: earliest possible hook
        injectionMode = if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) && script.isNotEmpty()) {
            try {
                runCatching { startHandles.remove(webView)?.remove() }
                startHandles[webView] = WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*"))
                InjectionMode.DOCUMENT_START
            } catch (t: Throwable) {
                Log.w(Async.TAG, "Document-start injection failed; using early fallback", t)
                InjectionMode.EARLY_FALLBACK
            }
        } else {
            InjectionMode.EARLY_FALLBACK
        }

        onStatus(status())
    }

    /**
     * Re-injects before the page's main-frame scripts run when `DOCUMENT_START` is not
     * available. Called by the WebViewClient from `onPageStarted`.
     */
    fun injectFallback(webView: WebView) {
        if (injectionMode == InjectionMode.DOCUMENT_START || script.isEmpty()) return
        runCatching { webView.evaluateJavascript(script, null) }
    }

    /**
     * Second-chance injection once the document is committed. Cheap (the script self-guards
     * with `__jbKernel`) and catches documents that replaced their scripts early.
     */
    fun injectOnCommit(webView: WebView) {
        if (injectionMode == InjectionMode.DOCUMENT_START || script.isEmpty()) return
        runCatching { webView.evaluateJavascript(script, null) }
    }

    /**
     * Rebuilds the script after the profile's identity changed and re-attaches it.
     *
     * Returns true when the payload actually differed — the caller then reloads, because a
     * document already parsed with the old values keeps them (navigator, screen and WebGL are
     * patched at document start, so only a fresh document picks up new ones).
     *
     * The previous document-start rule is removed first. `addDocumentStartJavaScript` has no
     * replace semantics: leaving the old rule attached would run the old identity's script
     * ahead of the new one on every subsequent document.
     */
    fun refreshScript(webViews: Collection<WebView>, updated: Profile): Boolean {
        val rebuilt = runCatching {
            FingerprintScript.build(updated.fp, updated.engine, updated.tools.unlockSelection)
        }.getOrNull()
        if (rebuilt.isNullOrEmpty() || rebuilt == script) return false
        script = rebuilt

        if (injectionMode == InjectionMode.DOCUMENT_START &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        ) {
            webViews.forEach { webView ->
                runCatching { startHandles.remove(webView)?.remove() }
                runCatching {
                    startHandles[webView] = WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*"))
                }.onFailure {
                    Log.w(Async.TAG, "Could not re-attach document-start script", it)
                    injectionMode = InjectionMode.EARLY_FALLBACK
                }
            }
        }
        return true
    }

    fun refreshScript(webView: WebView, updated: Profile): Boolean =
        refreshScript(listOf(webView), updated)

    /** The bucket's cookie store when isolation is active, otherwise the global one. */
    private fun cookieStore(): CookieManager? = try {
        if (isolationMode == IsolationMode.ISOLATED && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            store()?.getOrCreateProfile(token)?.cookieManager ?: CookieManager.getInstance()
        } else {
            CookieManager.getInstance()
        }
    } catch (t: Throwable) {
        Log.w(Async.TAG, "Cookie store unavailable", t)
        null
    }

    /** Clears cookies for this profile only. Safe on any thread; call from a background one. */
    fun clearCookies() {
        runCatching {
            cookieStore()?.let { manager ->
                manager.removeAllCookies(null)
                manager.flush()
            }
        }.onFailure { Log.w(Async.TAG, "Could not clear profile cookies", it) }
    }

    /** Persists the profile's cookie jar so a killed process does not lose a fresh login. */
    fun flushCookies() {
        runCatching { cookieStore()?.flush() }
    }

    private fun store(): ProfileStore? = runCatching { ProfileStore.getInstance() }.getOrNull()

    fun status(): KernelStatus = KernelStatus(
        isolationMode,
        injectionMode,
        token,
        when {
            isolationMode == IsolationMode.SHARED -> Warning.SHARED_STORAGE
            injectionMode == InjectionMode.EARLY_FALLBACK -> Warning.LATE_INJECTION
            else -> null
        }
    )

    /** The bucket's cookie store when isolation is active, otherwise the global one. */
    fun activeCookieManager(): CookieManager? = cookieStore()

    /** The bucket's WebStorage when isolation is active, otherwise the global one. */
    private fun webStorage(): android.webkit.WebStorage? = try {
        if (isolationMode == IsolationMode.ISOLATED && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            store()?.getOrCreateProfile(token)?.webStorage ?: android.webkit.WebStorage.getInstance()
        } else {
            android.webkit.WebStorage.getInstance()
        }
    } catch (t: Throwable) {
        Log.w(Async.TAG, "WebStorage unavailable", t)
        null
    }

    /**
     * Wipes this profile's browser data: cookies, WebStorage (localStorage/IndexedDB), cache,
     * form data, history, and materialised directories. Must run on the main thread when
     * clearing live WebView state, or safe from background when [webView] is null.
     */
    fun wipeData(webView: WebView?) {
        clearCookies()
        runCatching { webStorage()?.deleteAllData() }
        if (webView != null) {
            Async.onMain {
                runCatching { webView.clearCache(true) }
                runCatching { webView.clearFormData() }
                runCatching { webView.clearHistory() }
                runCatching {
                    webView.evaluateJavascript(
                        "try{localStorage.clear();sessionStorage.clear();}catch(_){}",
                        null
                    )
                }
            }
        }
        // Rotate the kernel token on disk so the NEXT session starts in a brand-new WebKit bucket,
        // without calling deleteProfile() on the currently loaded live profile (which throws
        // IllegalStateException on AndroidX WebKit).
        runCatching {
            ProfileStorage.rotateKernelToken(context, profile.id)
        }
        ProfileStorage.markWiped(context, profile.id)
        listOf("cookies", "cache", "localstorage", "indexdb")
            .forEach { kind -> runCatching { ProfileStorage.sessionDir(context, profile.id, kind).deleteRecursively() } }
    }

    companion object {
        /** True when the device can give each profile a real WebView storage bucket. */
        fun supportsIsolation(): Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

        /** True when the device can inject before the document's own scripts. */
        fun supportsDocumentStart(): Boolean =
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

        /**
         * Cheap pre-flight used by the browser activity *before* it creates a WebView.
         *
         * It answers one question — "can this device run the session as advertised?" — and it
         * must never throw: a missing WebView provider, an unreadable storage directory or a
         * corrupt profile row should degrade into a warning chip, not a crash on first touch.
         */
        fun preflight(context: Context, profile: Profile): Boolean = try {
            ProfileStorage.ensure(context.applicationContext, profile.id)
            android.webkit.WebSettings.getDefaultUserAgent(context).isNotBlank()
        } catch (t: Throwable) {
            Log.w(Async.TAG, "Kernel pre-flight failed", t)
            false
        }
    }
}
