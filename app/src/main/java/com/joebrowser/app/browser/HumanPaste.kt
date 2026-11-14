package com.joebrowser.app.browser

import android.webkit.WebView
import org.json.JSONObject

/**
 * Built-in "Paste as Human": types clipboard text into the focused field one character at
 * a time with human timing instead of dropping it in at once. Native to the app — no
 * extension, no store, no second install.
 */
object HumanPaste {

    data class Speed(val id: String, val label: String, val minMs: Int, val maxMs: Int)

    val speeds = listOf(
        Speed("relaxed", "Relaxed", 55, 135),
        Speed("natural", "Natural", 35, 105),
        Speed("fast", "Fast", 15, 45),
        Speed("instant", "Instant", 0, 0)
    )

    fun speedById(id: String): Speed = speeds.firstOrNull { it.id == id } ?: speeds[1]

    /**
     * Returns "ok" when a target field was found and typing started, "no-target" otherwise.
     * Completion is asynchronous; watch for the field filling.
     */
    fun type(webView: WebView, text: String, speedId: String, callback: ((String) -> Unit)? = null) {
        val speed = speedById(speedId)
        val instant = speed.id == "instant"
        val cfg = JSONObject().apply {
            put("text", text)
            put("min", speed.minMs)
            put("max", speed.maxMs)
            put("instant", instant)
        }
        val js = """
(function () {
  var CFG = $cfg;
  var el = document.activeElement;
  function isEditable(node) {
    if (!node) return false;
    if (node.isContentEditable) return true;
    var tag = node.tagName;
    if (tag === 'TEXTAREA') return !node.disabled && !node.readOnly;
    if (tag === 'INPUT') {
      var t = (node.type || 'text').toLowerCase();
      var editable = ['text','search','url','email','password','number','tel'];
      return editable.indexOf(t) >= 0 && !node.disabled && !node.readOnly;
    }
    return false;
  }
  if (!isEditable(el)) return 'no-target';
  try { el.focus(); } catch (e) {}
  var text = CFG.text;
  var instant = !!CFG.instant;
  function setNativeValue(node, value) {
    var proto = node.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
    var desc = Object.getOwnPropertyDescriptor(proto, 'value');
    if (desc && desc.set) { try { desc.set.call(node, value); return; } catch (e) {} }
    node.value = value;
  }
  function typeAt(rangeOffset) {
    if (el.isContentEditable) {
      try { document.execCommand('insertText', false, rangeOffset); return true; } catch (e) { return false; }
    }
    var ch = rangeOffset;
    var before = el.value != null ? el.value : '';
    var start = el.selectionStart != null ? el.selectionStart : before.length;
    var end = el.selectionEnd != null ? el.selectionEnd : before.length;
    var next = before.slice(0, start) + ch + before.slice(end);
    setNativeValue(el, next);
    try { el.selectionStart = el.selectionEnd = start + ch.length; } catch (e) {}
    var evt;
    try { evt = new InputEvent('input', { bubbles: true, cancelable: false, inputType: 'insertText', data: ch }); }
    catch (e) { evt = document.createEvent('Event'); evt.initEvent('input', true, false); }
    el.dispatchEvent(evt);
    return true;
  }
  if (instant) {
    if (el.isContentEditable) { try { document.execCommand('insertText', false, text); } catch (e) {} }
    else {
      var beforeAll = el.value != null ? el.value : '';
      var startAll = el.selectionStart != null ? el.selectionStart : beforeAll.length;
      var endAll = el.selectionEnd != null ? el.selectionEnd : beforeAll.length;
      setNativeValue(el, beforeAll.slice(0, startAll) + text + beforeAll.slice(endAll));
      try { el.selectionStart = el.selectionEnd = startAll + text.length; } catch (e) {}
      el.dispatchEvent(new Event('input', { bubbles: true }));
      el.dispatchEvent(new Event('change', { bubbles: true }));
    }
    return 'ok';
  }
  var idx = 0;
  function step() {
    if (idx >= text.length) {
      try { el.dispatchEvent(new Event('change', { bubbles: true })); } catch (e) {}
      return;
    }
    var ch = text.charAt(idx);
    if (ch === '\n' && el.tagName !== 'TEXTAREA') ch = ' ';
    if (!el.isContentEditable && ch === '\n') {
      // insert a literal newline for multi-line fields
      typeAt('\n');
      idx++;
      setTimeout(step, 40 + Math.random() * 80);
      return;
    }
    typeAt(ch);
    idx++;
    var delay = CFG.min + Math.random() * Math.max(1, CFG.max - CFG.min);
    // Tiny extra pause after sentence punctuation feels human.
    if (ch === '.' || ch === ',' || ch === '!' || ch === '?') delay += 120 + Math.random() * 180;
    setTimeout(step, delay);
  }
  step();
  return 'ok';
})();
""".trimIndent()
        webView.evaluateJavascript(js) { raw -> callback?.invoke(raw?.trim('"') ?: "error") }
    }
}
