package com.joebrowser.app.browser

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.webkit.Profile as WebKitProfile
import androidx.webkit.ProfileStore
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

    data class KernelStatus(
        val isolation: IsolationMode,
        val injection: InjectionMode,
        val profileToken: String,
        /** Set when the kernel had to degrade; shown once in the browser UI. */
        val warning: String? = null
    )

    private var script: String = ""
    private var installed = false
    private var token: String = ""

    /**
     * Handle to the document-start rule currently attached to the WebView.
     *
     * Kept so a refresh can `removeDocumentStartJavaScript` it: the WebKit API has no
     * replace, and stacking a second rule would leave the old identity's script running
     * first, which defeats the point of the update.
     */
    private var startHandle: String? = null

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
     * Binds the WebView to the profile's isolated bucket and attaches the document-start
     * script. Call after `prepare()` and before `loadUrl`.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun install(webView: WebView) {
        if (installed) return
        installed = true

        // ---- isolation
        isolationMode = if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            try {
                val store = store() ?: throw IllegalStateException("ProfileStore unavailable")
                // getOrCreateProfile is cheap after the first call and returns the same bucket.
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

        // ---- per-profile cookie policy
        //
        // When a WebKit Profile is bound, cookies live in that bucket's own jar and must be
        // driven through it: CookieManager.getInstance() is the *default* (global) jar, so using
        // it here would accept, read and clear the wrong profile's cookies.
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
                startHandle = WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*"))
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
    fun refreshScript(webView: WebView, updated: Profile): Boolean {
        val rebuilt = runCatching {
            FingerprintScript.build(updated.fp, updated.engine, updated.tools.unlockSelection)
        }.getOrNull()
        if (rebuilt.isNullOrEmpty() || rebuilt == script) return false
        script = rebuilt

        if (injectionMode == InjectionMode.DOCUMENT_START &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        ) {
            startHandle?.let { handle ->
                runCatching { WebViewCompat.removeDocumentStartJavaScript(webView, handle) }
                startHandle = null
            }
            runCatching {
                startHandle = WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*"))
            }.onFailure {
                Log.w(Async.TAG, "Could not re-attach document-start script", it)
                injectionMode = InjectionMode.EARLY_FALLBACK
            }
        }
        // The fallback path injects into the live document too, so a reload is not strictly
        // required for it to take effect — but the page's cached globals still are, so the
        // caller reloads identically in both modes.
        return true
    }

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

    fun status(): KernelStatus {
        val warning = when {
            isolationMode == IsolationMode.SHARED && injectionMode == InjectionMode.EARLY_FALLBACK ->
                "This WebView cannot isolate storage or inject before load. Update Android System WebView."
            isolationMode == IsolationMode.SHARED ->
                "This WebView cannot isolate profile storage. Update Android System WebView."
            injectionMode == InjectionMode.EARLY_FALLBACK ->
                "Fingerprint layer injected at page start (document-start hook unavailable)."
            else -> null
        }
        return KernelStatus(isolationMode, injectionMode, token, warning)
    }

    /**
     * Wipes this profile's browser data: the isolated bucket, cookies, cache and the
     * materialised directories. Runs synchronously; call from a background thread.
     */
    fun wipeData(webView: WebView?) {
        // Scoped to this profile's bucket. The previous version called the global
        // CookieManager.removeAllCookies(), which signed the user out of *every* profile in the
        // app — a direct violation of the isolation the product promises — and, because a
        // WebKit Profile's cookies live in a separate jar, did not reliably clear this one.
        clearCookies()
        runCatching { webView?.clearCache(true) }
        runCatching { webView?.clearFormData() }
        runCatching { webView?.clearHistory() }

        if (isolationMode == IsolationMode.ISOLATED || WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            runCatching {
                val store = store() ?: throw IllegalStateException("ProfileStore unavailable")
                store.deleteProfile(token)
                // Re-create immediately so the live WebView keeps a valid bucket.
                val recreated = store.getOrCreateProfile(ProfileStorage.rotateKernelToken(context, profile.id))
                token = recreated.name
            }.onFailure { Log.w(Async.TAG, "Could not delete WebKit profile bucket", it) }
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
