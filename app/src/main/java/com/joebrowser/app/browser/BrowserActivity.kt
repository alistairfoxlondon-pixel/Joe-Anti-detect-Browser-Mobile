package com.joebrowser.app.browser

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.joebrowser.app.R
import com.joebrowser.app.core.Async
import com.joebrowser.app.data.Prefs
import com.joebrowser.app.data.ProfileStorage
import com.joebrowser.app.data.Profile
import com.joebrowser.app.data.ProfileRepository
import com.joebrowser.app.databinding.ActivityBrowserBinding
import com.joebrowser.app.databinding.BottomSheetTrackerBinding
import com.joebrowser.app.proxy.LocalProxyRelay
import com.joebrowser.app.proxy.ProxyMonitorEngine
import com.joebrowser.app.proxy.ProxyTestResult
import com.joebrowser.app.ui.ProfileEditorActivity
import com.joebrowser.app.ui.UiKit

/**
 * The browsing window for a single profile.
 *
 * Order of operations in [onCreate] is load-bearing:
 *
 *  1. resolve the profile row (a deleted profile closes the activity with a toast),
 *  2. run [BrowserKernel.prepare] **before** `WebView()` is instantiated — `setProfile` is
 *     only legal pre-create, so the identity and storage bucket must be decided first,
 *  3. create the WebView, restore its saved state if any, apply settings,
 *  4. [BrowserKernel.install] binds the bucket and registers the document-start script,
 *  5. optionally verify the proxy off-thread, then navigate.
 *
 * Every user-visible control drives real state: back/forward track WebView history, the
 * address pill is a real editor, the progress bar is the real load progress, the proxy badge
 * reflects an actual relay probe, the tracker sheet lists actual intercepted requests, and
 * "paste as human" types through [HumanPaste].
 */
class BrowserActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBrowserBinding
    private var profile: Profile? = null

    /** Returns the badge to the live proxy state once a transient message has been read. */
    private val badgeReset = Runnable {
        if (!isFinishing && !isDestroyed && ::binding.isInitialized) bindProxyBadge()
    }
    private var kernel: BrowserKernel? = null
    private var webView: WebView? = null

    private var tracker: TrackerMonitor? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var pendingUrl: String = ""
    private var lastErrorUrl: String = ""
    private var trackerSheet: BottomSheetDialog? = null

    /** Loopback relay that injects proxy credentials the WebView cannot send. */
    private var relay: LocalProxyRelay? = null
    private var proxyApplied = false
    private var sessionClosed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val profileId = intent.getStringExtra(EXTRA_PROFILE_ID)?.takeIf { it.isNotBlank() }
            ?: return finishWith(R.string.browser_profile_missing)

        ProfileRepository.profile(this, profileId) { loaded ->
            if (isFinishing || isDestroyed) return@profile
            if (loaded == null) return@profile finishWith(R.string.browser_profile_missing)
            profile = loaded
            startSession(loaded, savedInstanceState)
        }
    }

    private fun finishWith(messageRes: Int) {
        Async.toast(this, getString(messageRes))
        finish()
    }

    // ----------------------------------------------------------------- session

    private fun startSession(loaded: Profile, savedState: Bundle?) {
        if (!::binding.isInitialized) return

        // ---- identity bar
        binding.browserAvatar.text = loaded.initials()
        binding.browserAvatar.background = UiKit.avatarBackground(loaded.id)
        binding.browserProfileName.text = loaded.displayName()
        binding.browserEngineChip.text = loaded.engine.title
        binding.browserKernelChip.text = getString(R.string.browser_kernel_ok)
        binding.browserKernelChip.setTextColor(ContextCompat.getColor(this, R.color.text_muted))

        // ---- 1. kernel FIRST: prepare() must run before the WebView exists
        // prepare() builds the spoofing payload and resolves the profile's storage token.
        val prepared = BrowserKernel.preflight(this, loaded)
        kernel = BrowserKernel(this, loaded, ::onKernelStatus)
        kernel?.prepare()
        if (!prepared) {
            // Missing WebView provider or unusable identity: say so on the chip, never crash.
            binding.browserKernelChip.text = getString(R.string.browser_kernel_shared)
            binding.browserKernelChip.setTextColor(ContextCompat.getColor(this, R.color.warning))
        }

        // Chrome is wired before the WebView exists so the error state is still navigable
        // (back arrow, retry button) on a device with no usable provider.
        wireToolbar()
        wireFindBar()
        wireBackHandling()

        val web = createWebView()
        if (web == null) {
            binding.browserStateAction.visibility = View.GONE
            showState(R.drawable.ic_warning, R.string.browser_error_title, R.string.browser_no_webview)
            binding.browserUrlField.isEnabled = false
            binding.btnNavReload.isEnabled = false
            binding.btnNavBack.isEnabled = false
            binding.btnNavForward.isEnabled = false
            binding.btnBrowserMenu.visibility = View.GONE
            return
        }
        webView = web
        binding.browserWebViewFrame.addView(
            web, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        kernel?.install(web)
        HumanPaste.attachBridge(web) { message -> onPasteMessage(message) }

        tracker = TrackerMonitor { _, trackers ->
            // Counts feed the tracker sheet; the chip only nudges once, never per request.
            if (trackers > 0 && ::binding.isInitialized) showTrackerHint()
        }

        // Session bookkeeping happens at launch, not at exit: a window the user swipes away
        // must still count as opened, and Home sorts by last-opened.
        Prefs.setLastProfileId(this, loaded.id)
        if (savedState == null) {
            ProfileRepository.updateProfile(this, loaded.id) {
                it.lastOpened = System.currentTimeMillis()
                it.openCount += 1
                it.everLaunched = true
            }
        }

        pendingUrl = intent.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() }
            ?: savedState?.getString(STATE_URL)
            ?: loaded.startUrl.ifBlank { Prefs.defaultStartUrl(this) }

        if (savedState != null) web.restoreState(savedState)
        verifyProxyThenLoad(loaded)
    }

    private fun onKernelStatus(status: BrowserKernel.KernelStatus) {
        if (!::binding.isInitialized) return
        val isolated = status.isolation == BrowserKernel.IsolationMode.ISOLATED
        binding.browserKernelChip.text = getString(
            if (isolated) R.string.browser_kernel_ok else R.string.browser_kernel_shared
        )
        binding.browserKernelChip.setTextColor(
            ContextCompat.getColor(this, if (isolated) R.color.success else R.color.warning)
        )
        val warning = status.warning
        if (warning != null && status.injection == BrowserKernel.InjectionMode.EARLY_FALLBACK) {
            // One line, dismissible, never a blocking dialog on every page.
            Async.toast(this, getString(R.string.browser_kernel_fallback))
        }
    }

    // ---------------------------------------------------------------- web view

    @SuppressLint("SetJavaScriptEnabled")
    /**
     * Builds the session WebView. Returns null when this device has no usable WebView
     * provider — the caller degrades to an error card instead of crashing on first touch.
     */
    private fun createWebView(): WebView? {
        val web = try {
            WebView(this)
        } catch (t: Throwable) {
            android.util.Log.e(Async.TAG, "WebView provider unavailable", t)
            return null
        }
        web.setBackgroundColor(ContextCompat.getColor(this, R.color.bg))

        val settings: WebSettings = web.settings
        runCatching {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadsImagesAutomatically = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.mediaPlaybackRequiresUserGesture = false
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.setSupportMultipleWindows(false)
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            profile?.let { loaded ->
                settings.userAgentString =
                    com.joebrowser.app.fingerprint.FingerprintFactory.userAgent(loaded.fp, loaded.engine)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) settings.safeBrowsingEnabled = true
        }.onFailure { android.util.Log.w(Async.TAG, "WebSettings partially applied", it) }

        web.webViewClient = SessionClient()
        web.webChromeClient = SessionChromeClient()
        web.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            download(url, userAgent, contentDisposition, mimeType)
        }
        return web
    }

    /**
     * Applies the profile's proxy to the WebView, then navigates.
     *
     * WebView cannot send proxy credentials, so a loopback [LocalProxyRelay] is started and the
     * WebView is pointed at it through `ProxyController`. The health check runs in parallel with
     * the navigation and only updates the badge — a failed probe never blocks the page, because
     * a proxy that cannot answer our probe may still serve the site.
     */
    private fun verifyProxyThenLoad(loaded: Profile) {
        if (!loaded.proxy.enabled) {
            hideProxyBadge()
            clearProxyOverride { load(pendingUrl) }
            return
        }
        binding.browserProxyBadge.visibility = View.VISIBLE
        binding.browserProxyBadge.text = getString(R.string.browser_proxy_checking)
        binding.browserProxyBadge.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
        // 1. route the WebView through the relay
        applyProxyOverride(loaded) {
            if (isFinishing || isDestroyed) return@applyProxyOverride
            load(pendingUrl)
            // 2. probe the exit node in the background and settle the badge when it answers
            ProxyMonitorEngine.verifyBeforeLaunch(this, loaded) { result ->
                if (isFinishing || isDestroyed) return@verifyBeforeLaunch
                bindProxyResult(result)
            }
        }
    }

    private fun applyProxyOverride(loaded: Profile, onReady: () -> Unit) {
        val config = loaded.proxy
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            binding.browserProxyBadge.visibility = View.VISIBLE
            binding.browserProxyBadge.text = getString(R.string.browser_proxy_unsupported)
            binding.browserProxyBadge.setTextColor(ContextCompat.getColor(this, R.color.warning))
            Async.toast(this, getString(R.string.browser_proxy_unsupported))
            onReady()
            return
        }
        val started = runCatching { LocalProxyRelay.start(config) }.getOrNull()
        if (started == null) {
            binding.browserProxyBadge.visibility = View.VISIBLE
            binding.browserProxyBadge.text = getString(R.string.browser_proxy_failed)
            binding.browserProxyBadge.setTextColor(ContextCompat.getColor(this, R.color.danger))
            onReady()
            return
        }
        relay = started
        runCatching {
            // No addDirect() fallback on purpose: if the relay cannot serve a request the page
            // must fail visibly instead of silently leaking the device IP.
            val rule = ProxyConfig.Builder()
                .addProxyRule(started.loopbackHost() + ":" + started.port)
                .build()
            ProxyController.getInstance().setProxyOverride(rule, { it.run() }) {
                proxyApplied = true
                Async.onMain(onReady)
            }
        }.onFailure {
            binding.browserProxyBadge.visibility = View.VISIBLE
            binding.browserProxyBadge.text = getString(R.string.browser_proxy_failed)
            onReady()
        }
    }

    /** Removes a previous override so a "direct" profile never inherits another one. */
    private fun clearProxyOverride(onDone: () -> Unit) {
        if (!proxyApplied || !WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            onDone()
            return
        }
        runCatching {
            ProxyController.getInstance().clearProxyOverride({ it.run() }) {
                proxyApplied = false
                Async.onMain(onDone)
            }
        }.onFailure { onDone() }
    }

    private fun bindProxyResult(result: ProxyTestResult) {
        binding.browserProxyBadge.visibility = View.VISIBLE
        // The badge is only actionable when the probe failed; a healthy badge must not look
        // tappable, or a tap that does nothing reads as a broken control.
        binding.browserProxyBadge.setOnClickListener(null)
        binding.browserProxyBadge.isClickable = false
        if (result.ok) {
            binding.browserProxyBadge.text =
                getString(R.string.browser_proxy_ok, result.country.ifBlank { result.ip }, result.latencyMs)
            binding.browserProxyBadge.setTextColor(ContextCompat.getColor(this, R.color.success))
        } else {
            binding.browserProxyBadge.text = getString(R.string.browser_proxy_failed)
            binding.browserProxyBadge.setTextColor(ContextCompat.getColor(this, R.color.danger))
            binding.browserProxyBadge.isClickable = true
            binding.browserProxyBadge.setOnClickListener {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.browser_proxy_failed)
                    .setMessage(result.error.ifBlank { getString(R.string.browser_error_body) })
                    .setPositiveButton(R.string.action_retry) { _, _ -> retryProxy() }
                    .setNegativeButton(R.string.action_cancel, null)
                    .show()
            }
        }
    }

    private fun retryProxy() {
        val loaded = profile ?: return
        binding.browserProxyBadge.text = getString(R.string.browser_proxy_checking)
        binding.browserProxyBadge.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
        ProxyMonitorEngine.verifyBeforeLaunch(this, loaded) { result ->
            if (isFinishing || isDestroyed) return@verifyBeforeLaunch
            bindProxyResult(result)
        }
    }

    /**
     * Puts the badge back to whatever the live session state is: hidden for a direct profile,
     * the last measurement for a routed one, or "checking" while a fresh probe is in flight.
     */
    private fun bindProxyBadge() {
        val loaded = profile
        val config = loaded?.proxy
        if (config == null || !config.enabled) {
            hideProxyBadge()
            return
        }
        binding.browserProxyBadge.visibility = View.VISIBLE
        binding.browserProxyBadge.setOnClickListener(null)
        binding.browserProxyBadge.isClickable = false
        when {
            config.lastIp.isNotBlank() && config.latencyMs >= 0 -> {
                binding.browserProxyBadge.text =
                    getString(R.string.browser_proxy_ok, config.lastCountry.ifBlank { config.lastIp }, config.latencyMs)
                binding.browserProxyBadge.setTextColor(ContextCompat.getColor(this, R.color.success))
            }
            config.lastError.isNotBlank() -> {
                binding.browserProxyBadge.text = getString(R.string.browser_proxy_failed)
                binding.browserProxyBadge.setTextColor(ContextCompat.getColor(this, R.color.danger))
            }
            else -> {
                binding.browserProxyBadge.text = getString(R.string.browser_proxy_checking)
                binding.browserProxyBadge.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
            }
        }
    }

    private fun hideProxyBadge() {
        binding.browserProxyBadge.visibility = View.GONE
    }

    // ---------------------------------------------------------------- toolbar

    private fun wireToolbar() {
        binding.btnBrowserBack.setOnClickListener { handleBackOrFinish() }
        binding.btnNavBack.setOnClickListener { goBack() }
        binding.btnNavForward.setOnClickListener { goForward() }
        binding.btnNavReload.setOnClickListener { reloadOrStop() }
        binding.btnBrowserMenu.setOnClickListener { showMenu(it) }
        binding.browserUrlField.setOnEditorActionListener { _, actionId, event ->
            val isGo = actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)
            if (isGo) {
                loadUrlFromBar()
                UiKit.hideKeyboard(binding.browserUrlField)
                true
            } else {
                false
            }
        }
        binding.browserUrlField.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.browserUrlField.selectAll()
                binding.browserUrlIcon.setImageResource(R.drawable.ic_search)
            }
        }
        binding.browserStateAction.setOnClickListener {
            binding.browserStateCard.visibility = View.GONE
            load(lastErrorUrl.ifBlank { pendingUrl })
        }
    }

    private fun goBack() {
        val web = webView ?: return
        if (web.canGoBack()) web.goBack() else handleBackOrFinish()
    }

    private fun goForward() {
        val web = webView ?: return
        if (web.canGoForward()) web.goForward()
    }

    private fun reloadOrStop() {
        val web = webView ?: return
        val stopping = binding.browserLoading.visibility == View.VISIBLE
        if (stopping) {
            web.stopLoading()
            binding.browserLoading.visibility = View.GONE
            binding.browserProgress.progress = 0
        } else {
            load(web.url ?: pendingUrl)
        }
    }

    private fun loadUrlFromBar() {
        val raw = binding.browserUrlField.text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) return
        binding.browserUrlField.clearFocus()
        load(normalizeUrl(raw))
    }

    private fun load(rawUrl: String) {
        val url = normalizeUrl(rawUrl)
        pendingUrl = url
        if (url.isBlank()) return
        binding.browserStateCard.visibility = View.GONE
        binding.browserLoading.visibility = View.VISIBLE
        binding.browserProgress.progress = 0
        binding.browserUrlField.setText(url)
        binding.browserUrlField.setSelection(0)
        runCatching { webView?.loadUrl(url) }.onFailure {
            showState(R.drawable.ic_wifi_off, R.string.browser_error_title, R.string.browser_error_body)
        }
    }

    /** Turns a typed string into a URL, defaulting bare words to a search. */
    private fun normalizeUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val lower = trimmed.lowercase()
        return when {
            lower.startsWith("http://") || lower.startsWith("https://") ||
                lower.startsWith("about:") || lower.startsWith("file:") ||
                lower.startsWith("data:") -> trimmed
            trimmed.contains(' ') || !trimmed.contains('.') ->
                "https://duckduckgo.com/?q=" + Uri.encode(trimmed)
            else -> "https://$trimmed"
        }
    }

    // ------------------------------------------------------------------ menu

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.browser_menu, popup.menu)
        runCatching {
            val method = PopupMenu::class.java.getDeclaredMethod("setForceShowIcon", Boolean::class.javaPrimitiveType)
            method.isAccessible = true
            method.invoke(popup, true)
        }
        popup.menu.findItem(R.id.speedRelaxed).isChecked = false
        popup.menu.findItem(R.id.speedNatural).isChecked = false
        popup.menu.findItem(R.id.speedFast).isChecked = false
        popup.menu.findItem(R.id.speedInstant).isChecked = false
        val currentSpeed = profile?.tools?.humanPasteSpeed?.takeIf { it.isNotBlank() }
            ?: Prefs.humanPasteSpeed(this)
        when (currentSpeed) {
            "relaxed" -> popup.menu.findItem(R.id.speedRelaxed).isChecked = true
            "fast" -> popup.menu.findItem(R.id.speedFast).isChecked = true
            "instant" -> popup.menu.findItem(R.id.speedInstant).isChecked = true
            else -> popup.menu.findItem(R.id.speedNatural).isChecked = true
        }
        // The profile decides; the global preference is only the default for profiles that have
        // never been edited. 'Either is on' would let a profile with the feature switched off
        // still paste as human because the app-wide default happened to be on.
        popup.menu.findItem(R.id.menuPasteHuman).isEnabled =
            profile?.tools?.humanPaste ?: Prefs.humanPasteEnabled(this)

        popup.setOnMenuItemClickListener { item -> onMenuItem(item) }
        popup.show()
    }

    private fun onMenuItem(item: android.view.MenuItem): Boolean {
        val web = webView ?: return false
        return when (item.itemId) {
            R.id.menuPasteHuman -> { pasteAsHuman(); true }
            R.id.speedRelaxed -> { setSpeed("relaxed", item); true }
            R.id.speedNatural -> { setSpeed("natural", item); true }
            R.id.speedFast -> { setSpeed("fast", item); true }
            R.id.speedInstant -> { setSpeed("instant", item); true }
            R.id.menuHttpTracker -> { showTrackerSheet(); true }
            R.id.menuClearData -> { confirmClearData(); true }
            R.id.menuFindInPage -> { toggleFindBar(true); true }
            R.id.menuCopyAddress -> {
                Async.copyToClipboard(this, web.url.orEmpty())
                true
            }
            R.id.menuOpenExternal -> { openExternal(web.url); true }
            R.id.menuEditProfile -> {
                Intent(this, ProfileEditorActivity::class.java)
                    .putExtra(ProfileEditorActivity.EXTRA_PROFILE_ID, profile?.id)
                    .let { runCatching { startActivity(it) } }
                true
            }
            R.id.fpBrowserScan -> { load("https://browserscan.net/"); true }
            R.id.fpIpHey -> { load("https://iphey.com/"); true }
            R.id.fpPixelScan -> { load("https://pixelscan.net/"); true }
            R.id.fpIpFighter -> { load("https://ipfighter.com/"); true }
            R.id.fpWhoer -> { load("https://whoer.net/"); true }
            else -> false
        }
    }

    private fun setSpeed(speed: String, item: android.view.MenuItem) {
        item.isChecked = true
        Prefs.setHumanPasteSpeed(this, speed)
        val id = profile?.id ?: return
        ProfileRepository.updateProfile(this, id) { it.tools.humanPasteSpeed = speed }
    }

    /** Types the clipboard into the focused field using the real timing engine. */
    /**
     * Receives `progress|typed|total` and `done|typed|total` from the typing loop.
     * The badge doubles as the progress label so no extra chrome is needed.
     */
    private fun onPasteMessage(raw: String) {
        if (!::binding.isInitialized) return
        val parts = raw.split('|')
        if (parts.size < 3) return
        val typed = parts[1].toIntOrNull() ?: return
        val total = parts[2].toIntOrNull() ?: return
        if (total <= 0) return
        when (parts[0]) {
            "progress" -> {
                binding.browserProxyBadge.visibility = View.VISIBLE
                binding.browserProxyBadge.text = getString(R.string.browser_paste_progress, typed, total)
                binding.browserProxyBadge.setTextColor(ContextCompat.getColor(this, R.color.accent))
            }
            "done" -> {
                binding.browserProxyBadge.text = getString(R.string.browser_paste_done, typed)
                binding.browserProxyBadge.setTextColor(ContextCompat.getColor(this, R.color.success))
                // Let the proxy badge reclaim the slot shortly after typing finishes.
                binding.browserProxyBadge.removeCallbacks(badgeReset)
                binding.browserProxyBadge.postDelayed(badgeReset, 2500L)
            }
        }
    }

    private fun pasteAsHuman() {
        val web = webView ?: return
        val clipboard = runCatching {
            val manager = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            manager.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        }.getOrNull().orEmpty()

        if (clipboard.isBlank()) {
            Async.toast(this, getString(R.string.browser_paste_empty))
            return
        }
        // Profile is the source of truth: the editor lets each profile choose its own cadence,
        // and the in-session menu writes straight back to it (see setSpeed). Reading the global
        // preference here would ignore every per-profile choice the user made.
        val configured = profile?.tools?.humanPasteSpeed?.takeIf { it.isNotBlank() }
        val speed = configured ?: Prefs.humanPasteSpeed(this)
        val jitter = profile?.tools?.humanPasteJitter ?: Prefs.humanPasteJitter(this)
        Async.toast(this, getString(R.string.browser_paste_started, HumanPaste.describe(clipboard, speed)))
        HumanPaste.setProgressSink { typed, total ->
            if (!isFinishing && !isDestroyed && ::binding.isInitialized) {
                binding.browserProxyBadge.visibility = View.VISIBLE
                binding.browserProxyBadge.text = getString(R.string.browser_paste_progress, typed, total)
            }
        }
        HumanPaste.type(
            webView = web,
            text = clipboard,
            speedId = speed,
            jitter = jitter,
            onResult = { result ->
                if (!result.accepted) {
                    Async.toast(this, result.reason.ifBlank { getString(R.string.browser_paste_no_field) })
                }
            },
            onProgress = { typed, total ->
                if (typed >= total) HumanPaste.clearProgressSink()
            }
        )
    }

    private fun openExternal(url: String?) {
        if (url.isNullOrBlank()) return
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { Async.toast(this, getString(R.string.browser_external_failed)) }
    }

    // -------------------------------------------------------------- find in page

    private fun toggleFindBar(show: Boolean) {
        binding.browserFindBar.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            UiKit.showKeyboard(binding.browserFindInput)
        } else {
            webView?.clearMatches()
            UiKit.hideKeyboard(binding.browserFindInput)
        }
    }

    private fun wireFindBar() {
        binding.browserFindClose.setOnClickListener { toggleFindBar(false) }
        binding.browserFindNext.setOnClickListener { findNext(true) }
        binding.browserFindPrev.setOnClickListener { findNext(false) }
        binding.browserFindInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) = findAll(s?.toString().orEmpty())
        })
    }

    private fun findAll(query: String) {
        val web = webView ?: return
        if (query.isBlank()) {
            web.clearMatches()
            return
        }
        web.findAllAsync(query)
    }

    private fun findNext(forward: Boolean) {
        val web = webView ?: return
        val query = binding.browserFindInput.text?.toString().orEmpty()
        if (query.isBlank()) return
        if (forward) web.findNext(true) else web.findNext(false)
    }

    // ------------------------------------------------------------------ tracker

    private fun showTrackerHint() {
        // Non-blocking cue only; the sheet opens from the menu on demand.
        if (binding.browserProxyBadge.visibility == View.VISIBLE) return
        binding.browserProxyBadge.visibility = View.VISIBLE
    }

    private fun showTrackerSheet() {
        val monitor = tracker ?: return
        val entries = monitor.snapshot()
        if (entries.isEmpty()) {
            Async.toast(this, getString(R.string.browser_tracker_empty))
            return
        }
        val sheetBinding = BottomSheetTrackerBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this, R.style.ThemeOverlay_Jb_BottomSheet)
        dialog.setContentView(sheetBinding.root)
        sheetBinding.trackerSummary.text = getString(
            R.string.browser_tracker_summary,
            entries.sumOf { it.count },
            entries.count { it.tracker }
        )
        sheetBinding.trackerEmpty.visibility = View.GONE
        sheetBinding.trackerList.layoutManager = LinearLayoutManager(this)
        sheetBinding.trackerList.adapter = TrackerAdapter().apply { submit(entries) }
        sheetBinding.trackerClose.setOnClickListener { dialog.dismiss() }
        sheetBinding.trackerCopy.setOnClickListener {
            Async.copyToClipboard(this, monitor.report(profile?.displayName().orEmpty()))
            Async.toast(this, getString(R.string.action_copy_report))
        }
        dialog.setOnShowListener {
            dialog.behavior.peekHeight = (resources.displayMetrics.heightPixels * 0.62f).toInt()
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
        trackerSheet = dialog
        dialog.setOnDismissListener { trackerSheet = null }
        runCatching { dialog.show() }
    }

    // ------------------------------------------------------------------- data

    private fun confirmClearData() {
        val loaded = profile ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.browser_clear_data_title)
            .setMessage(getString(R.string.browser_clear_data_body, loaded.displayName()))
            .setPositiveButton(R.string.action_clear) { _, _ -> clearBrowsingData(loaded) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun clearBrowsingData(loaded: Profile) {
        val web = webView
        val app = applicationContext
        binding.browserLoading.visibility = View.VISIBLE
        Async.bg {
            // Wipe order matters: the WebKit bucket first (it owns cookies + site data),
            // then the materialised directories, then the history rows.
            runCatching { kernel?.wipeData(web) }
            runCatching { kernel?.flushCookies() ?: CookieManager.getInstance().flush() }
            ProfileStorage.sessionDirs(app, loaded.id)
                .forEach { dir -> runCatching { dir.deleteRecursively() } }
            ProfileRepository.clearHistory(app, loaded.id)
            Async.onMain {
                if (isFinishing || isDestroyed) return@onMain
                binding.browserLoading.visibility = View.GONE
                tracker?.reset()
                Async.toast(this, getString(R.string.browser_cleared))
                load(loaded.startUrl.ifBlank { Prefs.defaultStartUrl(this) })
            }
        }
    }

    private fun download(url: String, userAgent: String?, disposition: String?, mimeType: String?) {
        val request = runCatching {
            DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                addRequestHeader("User-Agent", userAgent)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    android.webkit.URLUtil.guessFileName(url, disposition, mimeType)
                )
            }
        }.getOrNull()
        if (request == null) {
            Async.toast(this, getString(R.string.browser_download_failed))
            return
        }
        val queued = runCatching {
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
        }.isSuccess
        Async.toast(
            this,
            getString(if (queued) R.string.browser_download_started else R.string.browser_download_failed)
        )
    }

    // ------------------------------------------------------------------- state

    private fun syncUrlField(url: String?) {
        if (url.isNullOrBlank()) return
        if (binding.browserUrlField.hasFocus()) return
        pendingUrl = url
        binding.browserUrlField.setText(url)
        binding.browserUrlField.setSelection(0)
        binding.browserUrlIcon.setImageResource(
            if (url.startsWith("https://")) R.drawable.ic_lock else R.drawable.ic_search
        )
    }

    private fun updateNavButtons() {
        val web = webView
        binding.btnNavBack.alpha = if (web?.canGoBack() == true) 1f else 0.35f
        binding.btnNavForward.alpha = if (web?.canGoForward() == true) 1f else 0.35f
    }

    private fun showState(iconRes: Int, titleRes: Int, bodyRes: Int) {
        binding.browserStateIcon.setImageResource(iconRes)
        binding.browserStateTitle.setText(titleRes)
        binding.browserStateBody.setText(bodyRes)
        binding.browserStateCard.visibility = View.VISIBLE
        binding.browserLoading.visibility = View.GONE
        binding.browserProgress.progress = 0
    }

    // --------------------------------------------------------------- back key

    private fun wireBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> hideCustomView()
                    binding.browserFindBar.visibility == View.VISIBLE -> toggleFindBar(false)
                    trackerSheet != null -> trackerSheet?.dismiss()
                    webView?.canGoBack() == true -> webView?.goBack()
                    else -> {
                        closeSession()
                        finish()
                    }
                }
            }
        })
    }

    /** Called by the back button in the identity bar; mirrors the system back gesture. */
    private fun handleBackOrFinish() {
        if (customView != null) {
            hideCustomView()
            return
        }
        val web = webView
        if (web?.canGoBack() == true) web.goBack() else {
            closeSession()
            finish()
        }
    }

    /**
     * End-of-session work: history flush, cookie flush, and the per-profile
     * "clear data on close" contract.
     */
    private fun closeSession() {
        if (sessionClosed) return
        sessionClosed = true
        val loaded = profile ?: return
        val web = webView
        val app = applicationContext
        // Always flush this profile's cookie jar so a killed process cannot lose a login the
        // user just made. Flushing the global jar instead would persist the wrong profile.
        runCatching { kernel?.flushCookies() ?: CookieManager.getInstance().flush() }
        if (!loaded.freshSession) return
        Async.bg {
            runCatching { kernel?.wipeData(web) }
            ProfileStorage.sessionDirs(app, loaded.id)
                .forEach { dir -> runCatching { dir.deleteRecursively() } }
        }
    }

    // ------------------------------------------------------------- WebView glue

    private inner class SessionClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val url = request?.url?.toString() ?: return false
            return routeUrl(url)
        }

        @Deprecated("Deprecated in Java")
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
            val target = url ?: return false
            return routeUrl(target)
        }

        /** Keeps http(s) in-app and hands non-web schemes to the system. */
        private fun routeUrl(url: String): Boolean {
            return when {
                url.startsWith("http://") || url.startsWith("https://") ||
                    url.startsWith("about:") || url.startsWith("data:") -> false
                url.startsWith("intent:") -> {
                    runCatching {
                        val fallback = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                        val resolved = fallback.resolveActivity(packageManager)
                        if (resolved != null) startActivity(fallback) else openExternal(fallback.getStringExtra("browser_fallback_url"))
                    }
                    true
                }
                else -> {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                        .onFailure { Async.toast(this@BrowserActivity, getString(R.string.browser_external_failed)) }
                    true
                }
            }
        }

        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?
        ): WebResourceResponse? {
            val url = request?.url?.toString()
            if (!url.isNullOrBlank() && Prefs.trackerEnabled(this@BrowserActivity)) {
                tracker?.record(url)
            }
            return null
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            syncUrlField(url)
            binding.browserLoading.visibility = View.VISIBLE
            binding.browserStateCard.visibility = View.GONE
            kernel?.injectFallback(view ?: return)
            updateNavButtons()
        }

        override fun onPageCommitVisible(view: WebView?, url: String?) {
            binding.browserLoading.visibility = View.GONE
            kernel?.injectOnCommit(view ?: return)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            binding.browserLoading.visibility = View.GONE
            binding.browserProgress.progress = 0
            syncUrlField(url)
            updateNavButtons()
            val loaded = profile
            if (loaded != null && url != null && !url.startsWith("about:")) {
                ProfileRepository.addHistory(this@BrowserActivity, loaded, url, view?.title.orEmpty())
            }
            val query = binding.browserFindInput.text?.toString().orEmpty()
            if (binding.browserFindBar.visibility == View.VISIBLE && query.isNotBlank()) {
                view?.findAllAsync(query)
            }
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?
        ) {
            if (request?.isForMainFrame != true) return
            lastErrorUrl = request.url?.toString().orEmpty()
            showState(R.drawable.ic_wifi_off, R.string.browser_error_title, R.string.browser_error_body)
        }

        @Deprecated("Deprecated in Java")
        override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) return
            lastErrorUrl = failingUrl.orEmpty()
            showState(R.drawable.ic_wifi_off, R.string.browser_error_title, R.string.browser_error_body)
        }

        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
            if (handler == null) return
            val host = runCatching { Uri.parse(error?.url).host }.getOrNull().orEmpty()
            MaterialAlertDialogBuilder(this@BrowserActivity)
                .setTitle(R.string.browser_ssl_title)
                .setMessage(getString(R.string.browser_ssl_body) + if (host.isNotBlank()) "\n\n$host" else "")
                .setPositiveButton(R.string.browser_ssl_proceed) { _, _ -> runCatching { handler.proceed() } }
                .setNegativeButton(R.string.browser_ssl_go_back) { _, _ ->
                    runCatching { handler.cancel() }
                    goBack()
                }
                .setOnCancelListener { runCatching { handler.cancel() } }
                .show()
        }

        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
            // Recover instead of letting the framework kill the app: drop the dead WebView and
            // rebuild it with the same profile (identity and bucket are re-applied).
            runCatching { binding.browserWebViewFrame.removeView(view) }
            runCatching { view?.destroy() }
            webView = null
            val loaded = profile
            if (loaded == null) {
                finish()
                return true
            }
            Async.toast(this@BrowserActivity, getString(R.string.browser_error_body))
            val replacement = createWebView() ?: run {
                finish()
                return true
            }
            webView = replacement
            binding.browserWebViewFrame.addView(
                replacement,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            kernel?.install(replacement)
            replacement.loadUrl(pendingUrl.ifBlank { loaded.startUrl })
            return true
        }
    }

    private inner class SessionChromeClient : WebChromeClient() {

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            binding.browserProgress.progress = newProgress
            binding.browserLoading.visibility =
                if (newProgress in 1..99) View.VISIBLE else View.GONE
        }

        override fun onReceivedTitle(view: WebView?, title: String?) {
            binding.browserProfileName.text = profile?.displayName() ?: return
        }

        override fun onGeolocationPermissionsShowPrompt(
            origin: String?,
            callback: GeolocationPermissions.Callback?
        ) {
            val mode = profile?.fp?.geoMode ?: "block"
            when (mode) {
                "custom" -> callback?.invoke(origin, true, false)
                "block" -> callback?.invoke(origin, false, false)
                else -> callback?.invoke(origin, true, false)
            }
        }

        override fun onPermissionRequest(request: PermissionRequest?) {
            // Only grant what the identity can honestly back: the page gets nothing for
            // camera/mic because the app never asks the OS for those on the user's behalf.
            runCatching { request?.deny() }
        }

        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: FileChooserParams?
        ): Boolean {
            this@BrowserActivity.fileCallback?.onReceiveValue(null)
            this@BrowserActivity.fileCallback = filePathCallback
            return try {
                val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                }
                startActivityForResult(intent, REQUEST_FILE)
                true
            } catch (t: ActivityNotFoundException) {
                this@BrowserActivity.fileCallback = null
                Async.toast(this@BrowserActivity, getString(R.string.error_no_handler))
                false
            }
        }

        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
            if (view == null) return
            if (customView != null) {
                callback?.onCustomViewHidden()
                return
            }
            customView = view
            customViewCallback = callback
            binding.browserCustomView.addView(
                view,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            binding.browserCustomView.visibility = View.VISIBLE
        }

        override fun onHideCustomView() {
            hideCustomView()
        }

        override fun onCreateWindow(
            view: WebView?,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message?
        ): Boolean {
            // Pop-ups are routed into the same session rather than a second window we cannot
            // fingerprint: this keeps the identity guarantee intact.
            val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
            val popup = createWebView() ?: return false
            popup.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return false
                    load(url)
                    popup.destroy()
                    return true
                }
            }
            transport.webView = popup
            resultMsg.sendToTarget()
            return true
        }
    }

    private fun hideCustomView() {
        binding.browserCustomView.removeAllViews()
        binding.browserCustomView.visibility = View.GONE
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
    }

    // ------------------------------------------------------------- lifecycle

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQUEST_FILE) {
            @Suppress("DEPRECATION")
            super.onActivityResult(requestCode, resultCode, data)
            return
        }
        val callback = fileCallback ?: return
        fileCallback = null
        val results: Array<Uri>? = if (resultCode == RESULT_OK) {
            val clip = data?.clipData
            when {
                clip != null -> Array(clip.itemCount) { clip.getItemAt(it).uri }
                data?.data != null -> arrayOf(data.data as Uri)
                else -> null
            }
        } else {
            null
        }
        runCatching { callback.onReceiveValue(results) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_URL, pendingUrl)
        runCatching { webView?.saveState(outState) }
    }

    override fun onPause() {
        liveWindows = (liveWindows - 1).coerceAtLeast(0)
        runCatching { webView?.onPause() }
        runCatching { webView?.pauseTimers() }
        runCatching { kernel?.flushCookies() ?: CookieManager.getInstance().flush() }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        liveWindows += 1
        runCatching { webView?.onResume() }
        runCatching { webView?.resumeTimers() }
        updateNavButtons()
    }

    override fun onDestroy() {
        if (isFinishing) closeSession()
        runCatching { relay?.close() }
        relay = null
        proxyApplied = false
        trackerSheet?.dismiss()
        trackerSheet = null
        HumanPaste.clearProgressSink()
        binding.browserProxyBadge.removeCallbacks(badgeReset)
        runCatching { binding.browserWebViewFrame.removeAllViews() }
        runCatching { webView?.stopLoading() }
        runCatching { webView?.webChromeClient = null }
        // destroy() detaches the client; assigning null is not allowed by the platform API.
        runCatching { webView?.stopLoading() }
        runCatching { webView?.destroy() }
        webView = null
        kernel = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_PROFILE_ID = "profile_id"
        private const val EXTRA_URL = "url"
        private const val STATE_URL = "browser_url"
        private const val REQUEST_FILE = 4311

        /** Open a profile's window, optionally on a specific URL. */
        fun launch(context: Context, profileId: String, url: String? = null) {
            val intent = Intent(context, BrowserActivity::class.java)
                .putExtra(EXTRA_PROFILE_ID, profileId)
            if (!url.isNullOrBlank()) intent.putExtra(EXTRA_URL, url)
            runCatching { context.startActivity(intent) }
                .onFailure { Toast.makeText(context, R.string.browser_profile_missing, Toast.LENGTH_SHORT).show() }
        }

        /** Number of live browser windows — the Home screen's "running" metric. */
        @Volatile
        private var liveWindows = 0

        fun openWindowCount(): Int = liveWindows

        /** Kept for callers that only need to know the feature exists. */
        fun isSupported(): Boolean = true
    }

}
