package com.joebrowser.app.browser

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.os.Message
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.ProxyConfig as JBProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.joebrowser.app.data.BROWSERSCAN_URL
import com.joebrowser.app.JoeBrowserApp
import com.joebrowser.app.R
import com.joebrowser.app.data.Database
import com.joebrowser.app.data.Prefs
import com.joebrowser.app.data.Profile
import com.joebrowser.app.databinding.ActivityBrowserBinding
import com.joebrowser.app.databinding.BottomSheetTrackerBinding
import com.joebrowser.app.fingerprint.FingerprintScript
import com.joebrowser.app.proxy.LocalProxyRelay
import java.lang.ref.WeakReference
import java.net.URI

class BrowserActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBrowserBinding
    private lateinit var profile: Profile
    private lateinit var webView: WebView
    private var relay: LocalProxyRelay? = null
    private var proxyApplied = false
    private lateinit var trackerMonitor: TrackerMonitor
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var customView: android.view.View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var isolationWarned = false

    private val fileChooser = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        filePathCallback?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
        filePathCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val profileId = intent.getStringExtra(EXTRA_PROFILE_ID)
        val db = Database.get(this)
        val loaded = profileId?.let { db.getProfile(it) }
        if (loaded == null) {
            Toast.makeText(this, "Profile not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        profile = loaded
        Prefs.setLastProfileId(this, profile.id)

        // First-ever launch opens BrowserScan so the identity can be inspected immediately.
        val firstLaunch = !profile.everLaunched
        profile.everLaunched = true
        profile.openCount += 1
        profile.lastOpened = System.currentTimeMillis()
        db.saveProfile(profile)

        trackerMonitor = TrackerMonitor { total, trackers ->
            runOnUiThread {
                if (total <= 0) binding.trackerBadge.visibility = View.GONE
                else {
                    binding.trackerBadge.visibility = View.VISIBLE
                    binding.trackerBadge.text = if (total > 99) "99" else total.toString()
                }
            }
        }

        setupWebView()
        setupToolbar()
        registerOpen()

        val target = intent.getStringExtra(EXTRA_OPEN_URL)
            ?: if (firstLaunch) BROWSERSCAN_URL else normalizeUrl(profile.startUrl)
        applyProxyAndLoad(target)
    }

    private fun normalizeUrl(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return BROWSERSCAN_URL
        if (t.startsWith("about:")) return t
        if (!t.contains("://")) return "https://$t"
        return t
    }

    private fun setupWebView() {
        webView = binding.webView
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadsImagesAutomatically = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.textZoom = 100
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.javaScriptCanOpenWindowsAutomatically = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.safeBrowsingEnabled = true
        }
        // The profile's identity — every string a page reads.
        settings.userAgentString = com.joebrowser.app.fingerprint.FingerprintFactory.userAgent(profile.fp, profile.browserKind)
        try { settings.setGeolocationEnabled(profile.fp.geoMode != "block") } catch (_: Exception) {}

        // Per-profile storage isolation where the WebView supports it.
        val useMultiProfile = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
        if (useMultiProfile) {
            try {
                val store = androidx.webkit.ProfileStore.getInstance()
                store.getOrCreateProfile("jb-${profile.id}")
                WebViewCompat.setProfile(webView, "jb-${profile.id}")
            } catch (t: Throwable) {
                warnIsolationOnce()
            }
        } else {
            warnIsolationOnce()
        }

        // Fingerprint injection at document start (falls back to onPageStarted below).
        val script = FingerprintScript.build(profile.fp, profile.browserKind, profile.tools.unlockSelection)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try {
                WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*"))
            } catch (t: Throwable) {
                // fallback handled in onPageStarted
            }
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = JBWebViewClient(script)
        webView.webChromeClient = JBWebChromeClient()

        webView.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            try {
                val request = DownloadManager.Request(Uri.parse(url)).apply {
                    setMimeType(mimeType)
                    addRequestHeader("User-Agent", userAgent)
                    val name = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)
                    setTitle(name)
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, name)
                }
                (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                Toast.makeText(this, "Downloading…", Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                Toast.makeText(this, "Could not start the download", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun warnIsolationOnce() {
        if (isolationWarned) return
        isolationWarned = true
        Toast.makeText(
            this,
            "This WebView build cannot isolate profile storage — update Android System WebView for full profile isolation.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun applyProxyAndLoad(url: String) {
        val proxy = profile.proxy
        if (proxy.enabled && WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            try {
                relay = LocalProxyRelay.start(proxy)
                val config = JBProxyConfig.Builder().addProxyRule("127.0.0.1:${relay!!.port}").build()
                proxyApplied = true
                ProxyController.getInstance().setProxyOverride(config, { it.run() }, Runnable {
                    runOnUiThread { loadInitial(url) }
                })
                Toast.makeText(this, "Proxy: ${proxy.label()} (${proxy.host}:${proxy.port})", Toast.LENGTH_SHORT).show()
                return
            } catch (t: Throwable) {
                try { relay?.close() } catch (_: Exception) {}
                relay = null
                proxyApplied = false
                Toast.makeText(this, "Proxy could not start — loading directly", Toast.LENGTH_LONG).show()
            }
        } else if (proxy.enabled) {
            Toast.makeText(this, "Per-app proxy requires a newer Android System WebView", Toast.LENGTH_LONG).show()
        }
        // No proxy: ensure a clean state, then load.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            try {
                ProxyController.getInstance().clearProxyOverride({ it.run() }, Runnable {
                    proxyApplied = false
                })
            } catch (_: Exception) {}
        }
        loadInitial(url)
    }

    private fun loadInitial(url: String) {
        binding.webView.loadUrl(url)
    }

    private fun setupToolbar() {
        binding.btnBack.setOnClickListener { if (webView.canGoBack()) webView.goBack() }
        binding.btnForward.setOnClickListener { if (webView.canGoForward()) webView.goForward() }
        binding.btnReload.setOnClickListener { webView.reload() }
        binding.btnRetry.setOnClickListener {
            binding.errorView.visibility = View.GONE
            webView.reload()
        }
        binding.urlField.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                val input = v.text?.toString()?.trim().orEmpty()
                if (input.isNotEmpty()) loadUrlFromBar(input)
                true
            } else false
        }
        binding.urlField.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.urlField.selectAll()
            } else {
                syncUrlField(webView.url)
            }
        }
        binding.btnTracker.setOnClickListener { showTrackerSheet() }
        binding.btnMore.setOnClickListener { view -> binding.btnMore.showPopupMenu(view) }
    }

    private fun android.widget.ImageButton.showPopupMenu(anchor: View) {
        val popup = androidx.appcompat.widget.PopupMenu(this@BrowserActivity, anchor)
        popup.menuInflater.inflate(R.menu.browser_menu, popup.menu)
        // Reflect the profile's current typing speed as the checked radio item.
        val speedItem = when (profile.tools.humanPasteSpeed) {
            "relaxed" -> R.id.speedRelaxed
            "fast" -> R.id.speedFast
            "instant" -> R.id.speedInstant
            else -> R.id.speedNatural
        }
        popup.menu.findItem(speedItem)?.isChecked = true
        popup.setOnMenuItemClickListener { item -> onBrowserMenu(item) }
        popup.show()
    }

    private fun onBrowserMenu(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menuPasteHuman -> pasteAsHuman()
            R.id.speedRelaxed -> setTypingSpeed("relaxed", item)
            R.id.speedNatural -> setTypingSpeed("natural", item)
            R.id.speedFast -> setTypingSpeed("fast", item)
            R.id.speedInstant -> setTypingSpeed("instant", item)
            R.id.fpBrowserScan -> openInProfile("https://www.browserscan.net/")
            R.id.fpIpHey -> openInProfile("https://iphey.com/")
            R.id.fpPixelScan -> openInProfile("https://pixelscan.net/")
            R.id.fpIpFighter -> openInProfile("https://www.ipfighter.com/browser-fingerprint")
            R.id.fpWhoer -> openInProfile("https://whoer.net/")
            R.id.menuHttpTracker -> showTrackerSheet()
            R.id.menuClearData -> confirmClearData()
            else -> return false
        }
        return true
    }

    private fun setTypingSpeed(speed: String, item: MenuItem) {
        profile.tools.humanPasteSpeed = speed
        item.isChecked = true
        Database.get(this).saveProfile(profile)
        Toast.makeText(this, "Typing speed: ${speed.replaceFirstChar { it.uppercase() }}", Toast.LENGTH_SHORT).show()
    }

    private fun pasteAsHuman() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrEmpty()) {
            Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            return
        }
        binding.urlField.clearFocus()
        HumanPaste.type(webView, text, profile.tools.humanPasteSpeed) { status ->
            runOnUiThread {
                if (status == "no-target") {
                    Toast.makeText(this, "Focus a text field first", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun openInProfile(url: String) {
        webView.loadUrl(url)
    }

    private fun loadUrlFromBar(input: String) {
        val trimmed = input.trim()
        val url = when {
            trimmed.contains("://") -> trimmed
            trimmed.contains('.') && !trimmed.contains(' ') -> "https://$trimmed"
            else -> "https://www.google.com/search?q=${Uri.encode(trimmed)}"
        }
        webView.loadUrl(url)
        binding.urlField.clearFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(binding.urlField.windowToken, 0)
    }

    private fun syncUrlField(url: String?) {
        if (binding.urlField.hasFocus()) return
        binding.urlField.setText(url ?: "")
        val secure = url?.startsWith("https://") == true
        binding.urlLock.setImageResource(R.drawable.ic_lock)
        binding.urlLock.imageTintList = android.content.res.ColorStateList.valueOf(
            androidx.core.content.ContextCompat.getColor(this, if (secure) R.color.success else R.color.text_faint)
        )
    }

    private fun updateNavButtons() {
        binding.btnBack.alpha = if (webView.canGoBack()) 1f else 0.35f
        binding.btnForward.alpha = if (webView.canGoForward()) 1f else 0.35f
    }

    private fun recordVisit(url: String?, title: String?) {
        if (!Prefs.historyEnabled(this)) return
        val u = url?.trim().orEmpty()
        if (u.isEmpty() || u == "about:blank" || u.startsWith("data:")) return
        Database.get(this).addHistory(profile.id, profile.name, u, title?.ifBlank { u } ?: u)
    }

    private fun showTrackerSheet() {
        val dialog = BottomSheetDialog(this, R.style.ThemeOverlay_Jb_BottomSheet)
        val sheet = BottomSheetTrackerBinding.inflate(layoutInflater)
        val totals = trackerMonitor.totals()
        sheet.trackerSummary.text = "${totals.first} requests · ${totals.second} tracker${if (totals.second == 1) "" else "s"} this session"
        val adapter = TrackerAdapter()
        sheet.trackerList.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        sheet.trackerList.adapter = adapter
        adapter.submit(trackerMonitor.snapshot())
        sheet.trackerClose.setOnClickListener { dialog.dismiss() }
        sheet.btnClearTracker.setOnClickListener {
            trackerMonitor.clear()
            adapter.submit(emptyList())
            sheet.trackerSummary.text = "0 requests · 0 trackers this session"
        }
        sheet.btnCopyTrackerReport.setOnClickListener {
            val report = buildString {
                append("Joe Browser HTTP Tracker — ${profile.name}\n")
                append("Totals: ${totals.first} requests, ${totals.second} trackers\n\n")
                trackerMonitor.snapshot().forEach { append(if (it.tracker) "[tracker] " else "          ").append(it.host).append("  ×").append(it.count).append('\n') }
            }
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Joe Browser tracker report", report))
            Toast.makeText(this, "Report copied", Toast.LENGTH_SHORT).show()
        }
        dialog.setContentView(sheet.root)
        dialog.show()
    }

    private fun confirmClearData() {
        AlertDialog.Builder(this)
            .setTitle("Clear browsing data?")
            .setMessage("Cookies, site data, cache and history for “${profile.name}” will be erased. The identity (fingerprint, proxy) is kept.")
            .setPositiveButton("Clear") { _, _ ->
                wipeSessionData()
                webView.clearCache(true)
                webView.clearHistory()
                Toast.makeText(this, "Browsing data cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun wipeSessionData() {
        try {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        } catch (_: Exception) {}
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            try {
                androidx.webkit.ProfileStore.getInstance().deleteProfile("jb-${profile.id}")
                // Recreate so the WebView keeps working for the rest of the session.
                androidx.webkit.ProfileStore.getInstance().getOrCreateProfile("jb-${profile.id}")
            } catch (_: Exception) {}
        }
        Database.get(this).clearHistory(profile.id)
    }

    private inner class JBWebViewClient(private val documentStartScript: String) : WebViewClient() {
        private var fingerprintInjected = false

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val url = request?.url?.toString() ?: return false
            if (url.startsWith("intent://")) {
                try {
                    val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                    startActivity(intent)
                    return true
                } catch (e: Exception) {
                    return false
                }
            }
            if (url.startsWith("mailto:") || url.startsWith("tel:")) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    return true
                } catch (e: Exception) {
                    return false
                }
            }
            return false
        }

        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): android.webkit.WebResourceResponse? {
            if (profile.tools.httpTracker && request?.url != null) {
                trackerMonitor.record(request.url.toString())
            }
            return null
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            binding.errorView.visibility = View.GONE
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                // Fallback: inject as early as possible when document-start is unsupported.
                view?.evaluateJavascript(documentStartScript, null)
            }
            syncUrlField(url)
            binding.pageProgress.visibility = View.VISIBLE
        }


        override fun onPageFinished(view: WebView?, url: String?) {
            binding.pageProgress.visibility = View.GONE
            updateNavButtons()
            syncUrlField(url)
            recordVisit(url, view?.title)
            binding.urlField.hint = "Search or type URL"
        }

        @Suppress("OVERRIDE_DEPRECATION")
        @Deprecated("Deprecated in Android but still required for API < 23 paths")
        override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
            showError("Page couldn't be loaded", description ?: "Check the connection or the profile proxy.")
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            if (request?.isForMainFrame == true) {
                showError("Page couldn't be loaded", error?.description?.toString() ?: "Check the connection or the profile proxy.")
            }
        }

        private fun showError(title: String, detail: String) {
            binding.errorTitle.text = title
            binding.errorDetail.text = detail
            binding.errorView.visibility = View.VISIBLE
        }

        override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: android.webkit.WebResourceResponse?) {
            if (request?.isForMainFrame == true && (errorResponse?.statusCode ?: 0) >= 400) {
                showError("HTTP ${errorResponse?.statusCode}", "The site answered with an error. It may be blocking this network or the proxy.")
            }
        }

        override fun onReceivedSslError(view: WebView?, handler: android.webkit.SslErrorHandler?, error: android.net.http.SslError?) {
            AlertDialog.Builder(this@BrowserActivity)
                .setTitle("Certificate error")
                .setMessage("The site's certificate could not be verified (${error?.primaryError ?: "unknown"}). How to proceed?")
                .setPositiveButton("Go back") { _, _ -> handler?.cancel(); webView.goBack() }
                .setNegativeButton("Proceed anyway") { _, _ -> handler?.proceed() }
                .setCancelable(false)
                .show()
        }
    }

    private inner class JBWebChromeClient : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            binding.pageProgress.progress = newProgress
            binding.pageProgress.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
            val allow = profile.fp.geoMode != "block"
            callback?.invoke(origin, allow, false)
        }

        override fun onPermissionRequest(request: PermissionRequest?) {
            // Deny camera/mic by default: anti-detect work rarely needs them and silent grants leak.
            request?.deny()
        }

        override fun onShowFileChooser(webView: WebView?, filePathCallback: ValueCallback<Array<Uri>>?,
                                       fileChooserParams: WebChromeClient.FileChooserParams?): Boolean {
            this@BrowserActivity.filePathCallback?.onReceiveValue(null)
            this@BrowserActivity.filePathCallback = filePathCallback
            try {
                val accept = fileChooserParams?.acceptTypes?.firstOrNull { it.isNotBlank() } ?: "*/*"
                fileChooser.launch(accept)
                return true
            } catch (e: Exception) {
                this@BrowserActivity.filePathCallback = null
                return false
            }
        }

        override fun onShowCustomView(view: android.view.View?, callback: WebChromeClient.CustomViewCallback?) {
            if (view == null) return
            customView = view
            customViewCallback = callback
            binding.customViewContainer.removeAllViews()
            binding.customViewContainer.addView(view)
            binding.customViewContainer.visibility = View.VISIBLE
            binding.browserToolbar.visibility = View.GONE
            binding.pageProgress.visibility = View.GONE
        }

        override fun onHideCustomView() {
            binding.customViewContainer.visibility = View.GONE
            binding.customViewContainer.removeAllViews()
            binding.browserToolbar.visibility = View.VISIBLE
            customView = null
            try { customViewCallback?.onCustomViewHidden() } catch (_: Exception) {}
            customViewCallback = null
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            handleBackOrFinish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onBackPressed() {
        handleBackOrFinish()
    }

    private fun handleBackOrFinish() {
        if (customView != null) {
            binding.customViewContainer.visibility = View.GONE
            binding.customViewContainer.removeAllViews()
            binding.browserToolbar.visibility = View.VISIBLE
            customView = null
            try { customViewCallback?.onCustomViewHidden() } catch (_: Exception) {}
            customViewCallback = null
            return
        }
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
            return
        }
        super.onBackPressed()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean = false // menu is shown as a popup from btnMore

    private fun registerOpen() {
        synchronized(openActivities) { openActivities.add(WeakReference(this)) }
        val fp = proxyFingerprint()
        synchronized(openProxyKeys) {
            if (openProxyKeys.isNotEmpty() && fp !in openProxyKeys) {
                Toast.makeText(this,
                    "Android applies one WebView proxy per process — this window switched it to this profile's proxy.",
                    Toast.LENGTH_LONG).show()
            }
            openProxyKeys.add(fp)
        }
    }

    private fun proxyFingerprint(): String = "${profile.proxy.type}:${profile.proxy.host}:${profile.proxy.port}:${profile.proxy.user}"

    override fun onDestroy() {
        synchronized(openProxyKeys) { openProxyKeys.remove(proxyFingerprint()) }
        synchronized(openActivities) { openActivities.removeAll { it.get() == null || it.get() === this } }
        if (profile.freshSession) {
            wipeSessionData()
        }
        if (proxyApplied && WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            try {
                ProxyController.getInstance().clearProxyOverride({ it.run() }, Runnable { })
            } catch (_: Exception) {}
        }
        try { relay?.close() } catch (_: Exception) {}
        relay = null
        // Detach WebView to avoid leaking the activity through it.
        try {
            binding.browserContent.removeView(webView)
            webView.destroy()
        } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"
        const val EXTRA_OPEN_URL = "open_url"
        private val openActivities = mutableSetOf<WeakReference<BrowserActivity>>()
        private val openProxyKeys = mutableSetOf<String>()

        fun launch(context: Context, profileId: String, openUrl: String? = null) {
            context.startActivity(Intent(context, BrowserActivity::class.java).apply {
                putExtra(EXTRA_PROFILE_ID, profileId)
                if (openUrl != null) putExtra(EXTRA_OPEN_URL, openUrl)
            })
        }
    }
}
