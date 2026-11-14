package com.joebrowser.app.fingerprint

import com.joebrowser.app.data.FingerprintConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the document-start JavaScript that makes the WebView present the profile's
 * identity. The noise functions are deterministic (seeded per profile), so the same
 * profile answers the same way on every canvas/WebGL/audio probe — never contradictory.
 */
object FingerprintScript {

    fun build(fp: FingerprintConfig, browserKind: String, unlockSelection: Boolean): String {
        val brand = browserKind.lowercase()
        val chromeLike = brand in listOf("chrome", "chromium", "brave", "edge") &&
            (fp.os == "Android" || fp.os == "Windows 11" || fp.os == "Windows 10" || fp.os == "macOS" || fp.os == "Linux")
        val isiOS = fp.os == "iPhone" || fp.os == "iPad"
        val vendor = when {
            brand == "firefox" -> ""
            isiOS -> "Apple Computer, Inc."
            else -> "Google Inc."
        }
        val languages = JSONArray().apply {
            put(fp.language)
            val base = fp.language.substringBefore('-')
            if (base != fp.language) put(base)
            if (fp.language != "en" && base != "en") put("en")
        }
        val cfg = JSONObject().apply {
            put("seed", fp.seed)
            put("ua", FingerprintFactory.userAgent(fp, brand))
            put("platform", FingerprintFactory.platformFor(fp.os))
            put("vendor", vendor)
            put("language", fp.language)
            put("languages", languages)
            put("timezone", fp.timezone)
            put("screenW", fp.screenW)
            put("screenH", fp.screenH)
            put("availW", fp.availW)
            put("availH", fp.availH)
            put("colorDepth", fp.colorDepth)
            put("dpr", fp.pixelRatio)
            put("cores", fp.cores)
            put("memory", fp.memory)
            put("touch", fp.touchPoints)
            put("canvasNoise", fp.canvasNoise)
            put("audioNoise", fp.audioNoise)
            put("hwNoise", fp.hardwareNoise)
            put("doNotTrack", fp.doNotTrack)
            put("spoofBattery", fp.spoofBattery)
            put("webrtcMode", fp.webrtcMode)
            put("geoMode", fp.geoMode)
            put("geoLat", fp.geoLat.toDoubleOrNull() ?: 0.0)
            put("geoLng", fp.geoLng.toDoubleOrNull() ?: 0.0)
            put("webglVendor", fp.webglVendor)
            put("webglRenderer", fp.webglRenderer)
            put("isDesktop", fp.deviceType == "desktop")
            put("chromeLike", chromeLike)
            put("pdfViewer", chromeLike)
            put("unlockSelection", unlockSelection)
        }

        return """
(function () {
  'use strict';
  var JB = $cfg;
  var SEED = (JB.seed | 0) >>> 0;
  if (SEED === 0) SEED = 1;

  function ihash(a, b, c) {
    var h = (SEED ^ Math.imul(a | 0, 374761393) ^ Math.imul(b | 0, 668265263) ^ Math.imul(c | 0, 2246822519)) | 0;
    h = Math.imul(h ^ (h >>> 13), 1274126177);
    h ^= h >>> 16;
    return h >>> 0;
  }
  function noiseAt(i, salt) { return (ihash(i | 0, 0, salt) % 5) - 2; }
  function clamp8(v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }
  function def(obj, prop, getter) {
    try {
      Object.defineProperty(obj, prop, { get: getter, configurable: true, enumerable: true });
    } catch (e) { /* non-fatal */ }
  }

  /* ---------- Canvas 2D: deterministic per-profile noise ---------- */
  function addCanvasNoise(d) {
    for (var i = 0; i < d.length; i += 4) {
      var px = i >>> 2;
      d[i] = clamp8(d[i] + noiseAt(px, 11));
      d[i + 1] = clamp8(d[i + 1] + noiseAt(px, 23));
      d[i + 2] = clamp8(d[i + 2] + noiseAt(px, 47));
    }
  }
  if (JB.canvasNoise && window.CanvasRenderingContext2D) {
    var origGetImageData = CanvasRenderingContext2D.prototype.getImageData;
    CanvasRenderingContext2D.prototype.getImageData = function () {
      var img = origGetImageData.apply(this, arguments);
      try { if (img && img.data) addCanvasNoise(img.data); } catch (e) {}
      return img;
    };
    var origToDataURL = HTMLCanvasElement.prototype.toDataURL;
    HTMLCanvasElement.prototype.toDataURL = function () {
      try {
        var w = this.width, h = this.height;
        if (w > 0 && h > 0 && w <= 4096 && h <= 4096) {
          var scratch = document.createElement('canvas');
          scratch.width = w; scratch.height = h;
          var ctx = scratch.getContext('2d');
          ctx.drawImage(this, 0, 0);
          var img = ctx.getImageData(0, 0, w, h);
          addCanvasNoise(img.data);
          ctx.putImageData(img, 0, 0);
          return origToDataURL.apply(scratch, arguments);
        }
      } catch (e) {}
      return origToDataURL.apply(this, arguments);
    };
    var origToBlob = HTMLCanvasElement.prototype.toBlob;
    HTMLCanvasElement.prototype.toBlob = function (callback) {
      try {
        var w = this.width, h = this.height;
        if (w > 0 && h > 0 && w <= 4096 && h <= 4096) {
          var scratch = document.createElement('canvas');
          scratch.width = w; scratch.height = h;
          var ctx = scratch.getContext('2d');
          ctx.drawImage(this, 0, 0);
          var img = ctx.getImageData(0, 0, w, h);
          addCanvasNoise(img.data);
          ctx.putImageData(img, 0, 0);
          return origToBlob.apply(scratch, arguments);
        }
      } catch (e) {}
      return origToBlob.apply(this, arguments);
    };
  }

  /* ---------- WebGL: parameter + readPixels spoofing ---------- */
  var GL_VENDOR = JB.webglVendor;
  var GL_RENDERER = JB.webglRenderer;
  function isWindowsANGLE() { return /Direct3D|D3D11/i.test(GL_RENDERER); }
  var GL_VERSION = isWindowsANGLE() ? 'WebGL 1.0 (OpenGL ES 2.0 Chromium)' : 'WebGL 1.0 (OpenGL ES 2.0 Chromium)';
  var GL_SL_VERSION = 'WebGL GLSL ES 1.0 (OpenGL ES GLSL ES 1.0 Chromium)';
  function patchWebGL(proto) {
    if (!proto) return;
    var origGetParameter = proto.getParameter;
    proto.getParameter = function (param) {
      if (param === 37445 || param === 7936) return GL_VENDOR;   // UNMASKED_VENDOR / VENDOR
      if (param === 37446 || param === 7937) return GL_RENDERER; // UNMASKED_RENDERER / RENDERER
      if (param === 7938) return GL_VERSION;                     // VERSION
      if (param === 35724) return GL_SL_VERSION;                 // SHADING_LANGUAGE_VERSION
      return origGetParameter.apply(this, arguments);
    };
    var origGetExtension = proto.getExtension;
    proto.getExtension = function (name) {
      var ext = origGetExtension.apply(this, arguments);
      if (name === 'WEBGL_debug_renderer_info') {
        return { UNMASKED_VENDOR_WEBGL: 37445, UNMASKED_RENDERER_WEBGL: 37446 };
      }
      return ext;
    };
    var origReadPixels = proto.readPixels;
    proto.readPixels = function (x, y, w, h, format, type, pixels) {
      origReadPixels.apply(this, arguments);
      try {
        if (pixels && pixels.length) {
          for (var i = 0; i < pixels.length; i += 4) {
            pixels[i] = clamp8(pixels[i] + noiseAt((i >>> 2) + (x | 0), 101));
            pixels[i + 1] = clamp8(pixels[i + 1] + noiseAt((i >>> 2) + (y | 0), 103));
            pixels[i + 2] = clamp8(pixels[i + 2] + noiseAt(i >>> 2, 107));
          }
        }
      } catch (e) {}
    };
  }
  if (window.WebGLRenderingContext) patchWebGL(WebGLRenderingContext.prototype);
  if (window.WebGL2RenderingContext) patchWebGL(WebGL2RenderingContext.prototype);

  /* ---------- Audio: deterministic sample noise ---------- */
  if (JB.audioNoise && window.AudioBuffer && AudioBuffer.prototype.getChannelData) {
    var origGetChannelData = AudioBuffer.prototype.getChannelData;
    AudioBuffer.prototype.getChannelData = function (channel) {
      var data = origGetChannelData.call(this, channel);
      try {
        for (var i = 0; i < data.length; i++) {
          data[i] = data[i] + noiseAt(i, 1009) * 1e-7;
        }
      } catch (e) {}
      return data;
    };
  }

  /* ---------- Navigator ---------- */
  var navProto = Navigator.prototype;
  def(navProto, 'userAgent', function () { return JB.ua; });
  def(navProto, 'appVersion', function () { return JB.ua.replace(/^Mozilla\//, ''); });
  def(navProto, 'platform', function () { return JB.platform; });
  def(navProto, 'vendor', function () { return JB.vendor; });
  def(navProto, 'hardwareConcurrency', function () { return JB.cores; });
  def(navProto, 'deviceMemory', function () { return JB.memory; });
  def(navProto, 'maxTouchPoints', function () { return JB.touch; });
  def(navProto, 'language', function () { return JB.language; });
  def(navProto, 'languages', function () { return Object.freeze(JB.languages.slice()); });
  def(navProto, 'webdriver', function () { return undefined; });
  def(navProto, 'productSub', function () { return '20030107'; });
  def(navProto, 'cookieEnabled', function () { return true; });
  def(navProto, 'pdfViewerEnabled', function () { return !!JB.pdfViewer; });
  def(navProto, 'doNotTrack', function () { return JB.doNotTrack ? '1' : null; });
  try {
    Object.defineProperty(navProto, 'webdriver', { get: function () { return undefined; }, configurable: true });
  } catch (e) {}

  /* Plugins / mimeTypes: full PDF set on desktop identities, empty on mobile. */
  function makePlugins() {
    if (typeof PluginArray === 'undefined') return null;
    var pa = Object.create(PluginArray.prototype);
    var pd = 'Portable Document Format';
    var specs = [
      ['PDF Viewer', 'internal-pdf-viewer', pd],
      ['Chrome PDF Viewer', 'internal-pdf-viewer', pd],
      ['Chromium PDF Viewer', 'internal-pdf-viewer', pd],
      ['Microsoft Edge PDF Viewer', 'internal-pdf-viewer', pd],
      ['WebKit built-in PDF', 'internal-pdf-viewer', pd]
    ];
    var count = 0;
    specs.forEach(function (s, i) {
      var p = (typeof Plugin !== 'undefined') ? Object.create(Plugin.prototype) : {};
      try {
        Object.defineProperty(p, 'name', { value: s[0], enumerable: true });
        Object.defineProperty(p, 'filename', { value: s[1], enumerable: true });
        Object.defineProperty(p, 'description', { value: s[2], enumerable: true });
        Object.defineProperty(p, 'length', { value: 1 });
      } catch (e) {}
      Object.defineProperty(pa, i, { value: p, enumerable: true });
      count++;
    });
    Object.defineProperty(pa, 'length', { value: count });
    pa.item = function (i) { return this[i] || null; };
    pa.namedItem = function (n) { for (var i = 0; i < this.length; i++) { if (this[i].name === n) return this[i]; } return null; };
    pa.refresh = function () {};
    return pa;
  }
  function makeMimeTypes() {
    if (typeof MimeTypeArray === 'undefined') return null;
    var ma = Object.create(MimeTypeArray.prototype);
    var specs = [
      ['application/pdf', 'pdf', 'Portable Document Format'],
      ['text/pdf', 'pdf', 'Portable Document Format']
    ];
    specs.forEach(function (s, i) {
      var mt = (typeof MimeType !== 'undefined') ? Object.create(MimeType.prototype) : {};
      try {
        Object.defineProperty(mt, 'type', { value: s[0], enumerable: true });
        Object.defineProperty(mt, 'suffixes', { value: s[1], enumerable: true });
        Object.defineProperty(mt, 'description', { value: s[2], enumerable: true });
      } catch (e) {}
      Object.defineProperty(ma, i, { value: mt, enumerable: true });
    });
    Object.defineProperty(ma, 'length', { value: specs.length });
    ma.item = function (i) { return this[i] || null; };
    ma.namedItem = function (n) { for (var i = 0; i < this.length; i++) { if (this[i].type === n) return this[i]; } return null; };
    return ma;
  }
  if (JB.isDesktop) {
    var plugins = makePlugins(); if (plugins) def(navProto, 'plugins', function () { return plugins; });
    var mimeTypes = makeMimeTypes(); if (mimeTypes) def(navProto, 'mimeTypes', function () { return mimeTypes; });
  } else {
    var emptyPlugins = { length: 0, item: function () { return null; }, namedItem: function () { return null; }, refresh: function () {} };
    var emptyMimes = { length: 0, item: function () { return null; }, namedItem: function () { return null; } };
    def(navProto, 'plugins', function () { return emptyPlugins; });
    def(navProto, 'mimeTypes', function () { return emptyMimes; });
  }

  /* Connection */
  try {
    def(navProto, 'connection', function () {
      return { effectiveType: '4g', downlink: 10, rtt: 50, saveData: false };
    });
  } catch (e) {}

  /* ---------- Screen ---------- */
  var screenProto = Screen.prototype;
  def(screenProto, 'width', function () { return JB.screenW; });
  def(screenProto, 'height', function () { return JB.screenH; });
  def(screenProto, 'availWidth', function () { return JB.availW; });
  def(screenProto, 'availHeight', function () { return JB.availH; });
  def(screenProto, 'availLeft', function () { return 0; });
  def(screenProto, 'availTop', function () { return 0; });
  def(screenProto, 'colorDepth', function () { return JB.colorDepth; });
  def(screenProto, 'pixelDepth', function () { return JB.colorDepth; });
  def(window, 'devicePixelRatio', function () { return JB.dpr; });

  /* ---------- Timezone: Date + Intl agree ---------- */
  var JB_TZ = JB.timezone;
  function tzParts(date) {
    try {
      var fmt = new Intl.DateTimeFormat('en-US', {
        timeZone: JB_TZ, hour12: false, year: 'numeric', month: '2-digit', day: '2-digit',
        hour: '2-digit', minute: '2-digit', second: '2-digit'
      });
      var parts = fmt.formatToParts(date);
      var map = {};
      for (var i = 0; i < parts.length; i++) map[parts[i].type] = parts[i].value;
      var hour = parseInt(map.hour, 10) % 24;
      return Date.UTC(+map.year, +map.month - 1, +map.day, hour, +map.minute, +map.second);
    } catch (e) {
      return null;
    }
  }
  Date.prototype.getTimezoneOffset = function () {
    var t = this.getTime();
    if (isNaN(t)) return 0;
    var asUTC = tzParts(this);
    if (asUTC == null) return 0;
    var utc = Math.floor(t / 1000) * 1000;
    return Math.round((asUTC - utc) / 60000);
  };
  var origResolvedOptions = Intl.DateTimeFormat.prototype.resolvedOptions;
  Intl.DateTimeFormat.prototype.resolvedOptions = function () {
    var out = origResolvedOptions.call(this);
    try { out.timeZone = JB_TZ; } catch (e) {}
    return out;
  };

  /* ---------- WebRTC: relay-only or off ---------- */
  (function () {
    if (JB.webrtcMode === 'off') {
      window.RTCPeerConnection = undefined;
      window.webkitRTCPeerConnection = undefined;
      return;
    }
    if (JB.webrtcMode !== 'proxy') return;
    var Orig = window.RTCPeerConnection || window.webkitRTCPeerConnection;
    if (!Orig) return;
    function Patched(config) {
      config = config || {};
      config.iceTransportPolicy = 'relay';
      return new Orig(config);
    }
    Patched.prototype = Orig.prototype;
    try { Object.setPrototypeOf(Patched, Orig); } catch (e) {}
    window.RTCPeerConnection = Patched;
    window.webkitRTCPeerConnection = Patched;
  })();

  /* ---------- Geolocation ---------- */
  if (window.navigator && navigator.geolocation) {
    var geo = navigator.geolocation;
    if (JB.geoMode === 'block') {
      geo.getCurrentPosition = function (success, error) {
        if (error) error({ code: 1, message: 'User denied geolocation', PERMISSION_DENIED: 1, POSITION_UNAVAILABLE: 2, TIMEOUT: 3 });
      };
      geo.watchPosition = function (success, error) {
        if (error) error({ code: 1, message: 'User denied geolocation', PERMISSION_DENIED: 1, POSITION_UNAVAILABLE: 2, TIMEOUT: 3 });
        return 0;
      };
    } else if (JB.geoMode === 'custom') {
      var fake = {
        coords: {
          latitude: JB.geoLat, longitude: JB.geoLng, accuracy: 25,
          altitude: null, altitudeAccuracy: null, heading: null, speed: null
        },
        timestamp: Date.now()
      };
      geo.getCurrentPosition = function (success) { if (success) success(fake); };
      geo.watchPosition = function (success) { if (success) success(fake); return 1; };
    }
  }

  /* ---------- Battery ---------- */
  if (JB.spoofBattery && navigator.getBattery) {
    navigator.getBattery = function () {
      return Promise.resolve({
        charging: true, chargingTime: 0, dischargingTime: Infinity, level: 0.87,
        addEventListener: function () {}, removeEventListener: function () {}
      });
    };
  }

  /* ---------- window.chrome for Chromium identities ---------- */
  if (JB.chromeLike) {
    window.chrome = window.chrome || {};
    window.chrome.app = window.chrome.app || {
      isInstalled: false,
      InstallState: { DISABLED: 'disabled', INSTALLED: 'installed', NOT_INSTALLED: 'not_installed' },
      RunningState: { CANNOT_RUN: 'cannot_run', READY_TO_RUN: 'ready_to_run', RUNNING: 'running' }
    };
    window.chrome.csi = window.chrome.csi || function () {
      return { startE: Date.now(), onloadT: Date.now(), pageT: Date.now() % 97, tran: 15 };
    };
    window.chrome.loadTimes = window.chrome.loadTimes || function () {
      var now = Date.now() / 1000;
      return {
        commitLoadTime: now, connectionInfo: 'h2', finishDocumentLoadTime: now,
        finishLoadTime: now, firstPaintAfterLoadTime: 0, firstPaintTime: now,
        navigationType: 'Other', wasAlternateProtocolAvailable: false,
        wasFetchedViaSpdy: true, wasNpnNegotiated: true
      };
    };
    window.chrome.runtime = window.chrome.runtime || {};
  }

  /* ---------- Automation markers cleanup ---------- */
  try {
    for (var key in window) {
      if (Object.prototype.hasOwnProperty.call(window, key) &&
          (key.indexOf('cdc_') === 0 || key.indexOf('__cdc_') === 0 || key.indexOf('selenium') === 0)) {
        try { delete window[key]; } catch (e) {}
      }
    }
  } catch (e) {}

  /* ---------- Built-in: unlock selection / context menu on hostile pages ---------- */
  if (JB.unlockSelection) {
    try {
      var style = document.createElement('style');
      style.textContent = '*{user-select:text !important;-webkit-user-select:text !important;}';
      (document.head || document.documentElement).appendChild(style);
      ['contextmenu', 'selectstart', 'copy', 'cut', 'dragstart'].forEach(function (t) {
        window.addEventListener(t, function (e) { e.stopImmediatePropagation(); }, true);
        document.addEventListener(t, function (e) { e.stopImmediatePropagation(); }, true);
      });
    } catch (e) {}
  }
})();
""".trimIndent()
    }
}
