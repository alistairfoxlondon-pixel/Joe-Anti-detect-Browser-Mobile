package com.joebrowser.app.browser

import android.net.Uri
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/**
 * "Paste as Human": types clipboard content into the focused field with human timing.
 *
 * Two layers cooperate:
 *  - **Native (Kotlin):** decides the cadence profile, normalises the text (trailing
 *    whitespace, newline handling), reports progress back to the UI and enforces a hard
 *    playback ceiling so a pasted essay cannot keep typing forever.
 *  - **Injected (JS):** performs the actual character injection inside the page, using the
 *    native value setter plus `InputEvent` so React/Vue/Angular-controlled inputs register
 *    the change exactly as if a person typed it.
 *
 * Timing model (per character, `min + random·(max-min)`):
 *  - `relaxed`  55–135 ms, long pauses after sentence punctuation,
 *  - `natural`  35–105 ms, medium pauses,
 *  - `fast`     15–45 ms,
 *  - `instant`  single-set, used only when the site is a hostile OTP/typing-blocker.
 *  Plus rare "thinking" hesitations and a burst factor, which is what real typing looks like
 *  on a timeline (uniform timing is itself a signal).
 */
object HumanPaste {

    data class Speed(val id: String, val label: String, val minMs: Int, val maxMs: Int) {
        val description: String
            get() = when (id) {
                "instant" -> "Single injection, no delay"
                else -> "$minMs–$maxMs ms per character"
            }
    }

    val speeds = listOf(
        Speed("relaxed", "Relaxed", 55, 135),
        Speed("natural", "Natural", 35, 105),
        Speed("fast", "Fast", 15, 45),
        Speed("instant", "Instant", 0, 0)
    )

    fun speedById(id: String?): Speed = speeds.firstOrNull { it.id == id } ?: speeds[1]

    data class Result(val accepted: Boolean, val reason: String = "")

    /**
     * Starts typing [text] into the focused element.
     *
     * @param onResult fired once, on the WebView's thread, with whether typing began
     *   (`accepted = false` means no editable field was focused).
     * @param onProgress fired on the JS side while characters are being injected.
     */
    fun type(
        webView: WebView,
        text: String,
        speedId: String?,
        jitter: Boolean = true,
        onResult: (Result) -> Unit = {},
        onProgress: ((typed: Int, total: Int) -> Unit)? = null
    ) {
        if (text.isEmpty()) {
            onResult(Result(false, "Clipboard is empty"))
            return
        }
        val payload = normalise(text)
        val speed = speedById(speedId)
        val script = buildScript(payload, speed, jitter && speed.id != "instant")
        runCatching {
            webView.evaluateJavascript(script) { raw ->
                val status = raw?.trim()?.trim('"').orEmpty()
                when {
                    status.startsWith("ok") -> onResult(Result(true))
                    status == "no-target" -> onResult(Result(false, "Tap a text field in the page first"))
                    else -> onResult(Result(false, "This field cannot be typed into"))
                }
            }
        }.onFailure { onResult(Result(false, "The page is not ready")) }
        // Progress arrives asynchronously through the WebMessageListener channel; the caller
        // may also pass a callback here for one-shot use.
        progressSink = onProgress
    }

    /** Name of the injected-object that receives progress messages from the page. */
    const val BRIDGE_NAME = "JoePaste"

    /** Last progress callback registered; cleared after completion. */
    @Volatile
    private var progressSink: ((Int, Int) -> Unit)? = null

    /** Screens register their progress UI here; the browser forwards page messages to it. */
    fun setProgressSink(callback: ((Int, Int) -> Unit)?) {
        progressSink = callback
    }

    fun reportProgress(typed: Int, total: Int) {
        progressSink?.invoke(typed, total)
    }

    fun clearProgressSink() {
        progressSink = null
    }

    /**
     * Registers the message channel on a WebView.
     *
     * Uses `WebViewCompat.addWebMessageListener`, which is origin-scoped and only exposes the
     * object to the main frame — unlike `addJavascriptInterface`, which would hand every ad
     * iframe a native object to poke at.
     */
    fun attachBridge(webView: WebView, onMessage: (String) -> Unit) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        runCatching {
            WebViewCompat.addWebMessageListener(
                webView,
                BRIDGE_NAME,
                setOf("*"),
                object : WebViewCompat.WebMessageListener {
                    override fun onPostMessage(
                        view: WebView,
                        message: WebMessageCompat,
                        sourceOrigin: Uri,
                        isMainFrame: Boolean,
                        replyProxy: JavaScriptReplyProxy
                    ) {
                        if (!isMainFrame) return
                        onMessage(message.data.orEmpty())
                    }
                }
            )
        }.onFailure { android.util.Log.w("JoePaste", "WebMessageListener unavailable", it) }
    }

    /** Normalises clipboard text so pages receive what a person would actually type. */
    fun normalise(raw: String): String {
        var text = raw.replace("\r\n", "\n").replace('\r', '\n')
        // Strip a single trailing newline: clipboard copies of a line usually carry one.
        if (text.endsWith("\n")) text = text.dropLast(1)
        // Collapse runs of blank lines, which no human types on purpose.
        text = text.replace(Regex("\n{3,}"), "\n\n")
        return text.trim()
    }

    fun characterCount(text: String): Int = normalise(text).length

    fun estimatedDurationMs(text: String, speedId: String?): Long {
        val speed = speedById(speedId)
        if (speed.id == "instant") return 0L
        val average = (speed.minMs + speed.maxMs) / 2.0
        val punctuation = normalise(text).count { it == '.' || it == ',' || it == '!' || it == '?' }
        return (normalise(text).length * average + punctuation * 140).toLong()
    }

    /** Short human summary used in the browser menu. */
    fun describe(text: String, speedId: String?): String {
        val count = characterCount(text)
        val speed = speedById(speedId)
        if (speed.id == "instant") return "$count characters · instant"
        val seconds = estimatedDurationMs(text, speedId) / 1000.0
        return "$count characters · ${speed.label} · ~%.1fs".format(seconds)
    }

    // ------------------------------------------------------------------ script

    private fun buildScript(text: String, speed: Speed, jitter: Boolean): String {
        val config = JSONObject().apply {
            put("text", text)
            put("min", speed.minMs)
            put("max", speed.maxMs)
            put("instant", speed.id == "instant")
            put("jitter", jitter)
        }
        return TEMPLATE.replace("__CFG__", config.toString())
    }

    private val TEMPLATE = """
(function () {
  var CFG = __CFG__;
  if (window.__jbTyping) return 'busy';
  window.__jbTyping = true;

  /*
   * Progress transport. The app registers an AndroidX WebMessageListener object named
   * 'JoePaste' on the WebView only (never a JavascriptInterface, which would be reachable from
   * any frame). Every call is wrapped: a page that removes the object simply loses progress
   * reporting, not the typing itself.
   */
  function postToApp(payload) {
    try {
      var bridge = window.JoePaste;
      if (bridge && bridge.postMessage) {
        bridge.postMessage(payload);
      }
    } catch (e) {}
  }

  function isEditable(node) {
    if (!node || node.nodeType !== 1) return false;
    if (node.disabled || node.readOnly) {
      if (!node.isContentEditable) return false;
    }
    if (node.isContentEditable) return true;
    var tag = node.tagName;
    if (tag === 'TEXTAREA') return !node.disabled && !node.readOnly;
    if (tag === 'INPUT') {
      var type = (node.type || 'text').toLowerCase();
      var editable = ['text', 'search', 'url', 'email', 'password', 'number', 'tel', 'date', 'datetime-local', 'month', 'time', 'week'];
      return editable.indexOf(type) >= 0 && !node.disabled && !node.readOnly;
    }
    return false;
  }

  var el = document.activeElement;
  if (!isEditable(el)) {
    /* Fall back to the first visible editable field on the page. */
    var candidates = document.querySelectorAll('input, textarea, [contenteditable=""], [contenteditable="true"]');
    for (var i = 0; i < candidates.length; i++) {
      var node = candidates[i];
      if (isEditable(node) && node.offsetParent !== null) { el = node; break; }
    }
  }
  if (!isEditable(el)) { window.__jbTyping = false; return 'no-target'; }

  try { el.focus(); } catch (e) {}

  function setNativeValue(node, value) {
    var proto = node.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
    try {
      var descriptor = Object.getOwnPropertyDescriptor(proto, 'value');
      if (descriptor && descriptor.set) { descriptor.set.call(node, value); return; }
    } catch (e) {}
    node.value = value;
  }

  function fireInput(node, data) {
    try {
      var event = new InputEvent('input', { bubbles: true, cancelable: false, inputType: 'insertText', data: data });
      node.dispatchEvent(event);
    } catch (e) {
      try {
        var legacy = document.createEvent('Event');
        legacy.initEvent('input', true, false);
        node.dispatchEvent(legacy);
      } catch (e2) {}
    }
  }

  function fireKey(node, type, ch) {
    try {
      var code = ch.charCodeAt(0);
      node.dispatchEvent(new KeyboardEvent(type, {
        key: ch, bubbles: true, cancelable: true, charCode: code, keyCode: code, which: code
      }));
    } catch (e) {}
  }

  function insertChar(ch) {
    if (el.isContentEditable) {
      try { document.execCommand('insertText', false, ch); } catch (e) {}
      fireInput(el, ch);
      return true;
    }
    var current = el.value != null ? el.value : '';
    var start = el.selectionStart != null ? el.selectionStart : current.length;
    var end = el.selectionEnd != null ? el.selectionEnd : current.length;
    var next = current.slice(0, start) + ch + current.slice(end);
    setNativeValue(el, next);
    try { el.selectionStart = el.selectionEnd = start + ch.length; } catch (e) {}
    fireInput(el, ch);
    return true;
  }

  if (CFG.instant || !CFG.text.length) {
    if (el.isContentEditable) {
      try { document.execCommand('insertText', false, CFG.text); } catch (e) {}
    } else {
      var base = el.value != null ? el.value : '';
      var s = el.selectionStart != null ? el.selectionStart : base.length;
      var e = el.selectionEnd != null ? el.selectionEnd : base.length;
      setNativeValue(el, base.slice(0, s) + CFG.text + base.slice(e));
      try { el.selectionStart = el.selectionEnd = s + CFG.text.length; } catch (err) {}
    }
    try { el.dispatchEvent(new Event('input', { bubbles: true })); } catch (e) {}
    try { el.dispatchEvent(new Event('change', { bubbles: true })); } catch (e) {}
    try { el.dispatchEvent(new Event('blur', { bubbles: false })); } catch (e) {}
    window.__jbTyping = false;
    return 'ok';
  }

  var index = 0;
  var total = CFG.text.length;

  function nextDelay(ch) {
    var base = CFG.min + Math.random() * Math.max(1, CFG.max - CFG.min);
    if (CFG.jitter) {
      if (ch === '.' || ch === ',' || ch === '!' || ch === '?') base += 90 + Math.random() * 170;
      else if (ch === ' ') base += Math.random() * 25;
      if (Math.random() < 0.02) base += 260 + Math.random() * 420;   /* hesitation */
      if (Math.random() < 0.06) base = base * 0.45;                   /* burst */
    }
    return base;
  }

  function step() {
    if (index >= total) {
      try { el.dispatchEvent(new Event('change', { bubbles: true })); } catch (e) {}
      try { el.dispatchEvent(new Event('blur', { bubbles: false })); } catch (e) {}
      window.__jbTyping = false;
      try { postToApp('done|' + index + '|' + total); } catch (e) {}
      return;
    }
    var ch = CFG.text.charAt(index);
    if (ch === '\n' && el.tagName !== 'TEXTAREA' && !el.isContentEditable) ch = ' ';
    fireKey(el, 'keydown', ch === '\n' ? 'Enter' : ch);
    if (ch === '\n' && (el.tagName === 'TEXTAREA' || el.isContentEditable)) {
      insertChar('\n');
    } else {
      insertChar(ch);
    }
    fireKey(el, 'keyup', ch === '\n' ? 'Enter' : ch);
    index++;
    try { postToApp('progress|' + index + '|' + total); } catch (e) {}
    setTimeout(step, nextDelay(ch));
  }

  step();
  return 'ok';
})();
""".trimIndent()
}
