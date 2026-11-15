package com.joebrowser.app.fingerprint

import com.joebrowser.app.data.BrowserEngine
import com.joebrowser.app.data.FingerprintConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the runtime spoofing layer injected before any page script runs.
 *
 * Injection strategy (see `BrowserKernel`):
 *  1. `WebViewCompat.addDocumentStartJavaScript` — the earliest hook Android exposes, runs
 *     before the page's own scripts evaluate.
 *  2. If the installed WebView cannot do that, the script is re-injected from
 *     `onPageStarted` and again on `onPageCommitVisible`, and `BrowserKernel` reports a
 *     degraded-kernel warning so the user knows the guarantee is weaker.
 *
 * Design rules inside the JavaScript:
 *  - Everything is deterministic from the profile seed. The same profile answers every
 *    canvas/WebGL/audio probe identically on every visit, so a detection service that
 *    probes twice sees one stable machine, not noise.
 *  - Patched functions keep their original `name`, `length` and `toString()` so the
 *    prototype cannot be fingerprinted by comparing native code strings.
 *  - Every patch is individually wrapped in try/catch: one hostile page freezing a
 *    prototype cannot prevent the rest of the layer from installing.
 */
object FingerprintScript {

    private const val PATCH_HELPERS = """
  function nativeToString(fn, original) {
    try {
      var text = Function.prototype.toString.call(original);
      Object.defineProperty(fn, 'toString', {
        value: function () { return text; }, configurable: true, writable: true
      });
    } catch (e) {}
    return fn;
  }
  function patch(parent, prop, value) {
    if (!parent) return;
    try {
      var descriptor = Object.getOwnPropertyDescriptor(parent, prop);
      var original = parent[prop];
      var wrapper = (typeof value === 'function') ? value : function () { return value; };
      if (original && typeof original === 'function') nativeToString(wrapper, original);
      Object.defineProperty(parent, prop, {
        get: wrapper,
        set: descriptor && descriptor.set ? descriptor.set : undefined,
        configurable: true,
        enumerable: descriptor ? descriptor.enumerable : true
      });
    } catch (e) {}
  }
  function patchValue(parent, prop, value) {
    if (!parent) return;
    try {
      Object.defineProperty(parent, prop, { value: value, configurable: true, writable: true });
    } catch (e) {}
  }
  function patchMethod(parent, prop, factory) {
    if (!parent) return;
    try {
      var original = parent[prop];
      if (typeof original !== 'function') return;
      var wrapper = factory(original);
      nativeToString(wrapper, original);
      try { wrapper.name = original.name; } catch (e) {}
      Object.defineProperty(parent, prop, { value: wrapper, configurable: true, writable: true, enumerable: false });
    } catch (e) {}
  }
  function patchGetter(proto, prop, getter) {
    if (!proto) return;
    try { Object.defineProperty(proto, prop, { get: getter, configurable: true, enumerable: true }); } catch (e) {}
  }
  /*
   * Some properties are own, non-enumerable descriptors on the instance rather than the
   * prototype (navigator.webdriver is the famous one). The prototype patch above cannot
   * reach those, so this walks the object first and only falls back to the prototype.
   */
  /*
   * A prototype getter is shadowed by an own property, and vice versa: whichever one the
   * engine consults first wins. These two helpers therefore always act as a pair — install on
   * the instance AND on the prototype, and only declare success when reading the property back
   * returns what we intended.
   */
  function installOwn(target, prop, getter) {
    if (!target) return false;
    try {
      var own = Object.getOwnPropertyDescriptor(target, prop);
      if (own && own.configurable === false) return false;
      Object.defineProperty(target, prop, { get: getter, configurable: true, enumerable: false });
      return true;
    } catch (e) { return false; }
  }
  function patchOwn(target, proto, prop, getter) {
    var onInstance = installOwn(target, prop, getter);
    patchGetter(proto, prop, getter);
    // If the instance descriptor is locked, undo the prototype patch so the object does not
    // end up with two disagreeing definitions.
    if (!onInstance && proto) {
      try {
        var locked = Object.getOwnPropertyDescriptor(target, prop);
        if (locked && locked.configurable === false) {
          Object.defineProperty(proto, prop, { get: function () { return target[prop]; }, configurable: true, enumerable: true });
        }
      } catch (e) {}
    }
  }
"""

    fun build(fp: FingerprintConfig, engine: BrowserEngine, unlockSelection: Boolean): String {
        val brand = engine.id
        val isIos = fp.os == "iPhone" || fp.os == "iPad"
        val chromeLike = engine != BrowserEngine.FIREFOX && !isIos &&
            (fp.os == "Android" || fp.os == "Windows 11" || fp.os == "Windows 10" ||
                fp.os == "macOS" || fp.os == "Linux")
        // An iOS identity means Safari's engine (WebKit), never Chrome/Firefox: iOS browsers are
        // all WebKit shells, and WebKit exposes neither navigator.userAgentData nor
        // navigator.deviceMemory. Treating iOS as Chrome-like was a self-contradiction that any
        // half-decent probe could read straight off the page.
        val safariLike = isIos
        
        val vendor = when {
            engine == BrowserEngine.FIREFOX -> ""
            isIos -> "Apple Computer, Inc."
            else -> "Google Inc."
        }
        val languages = JSONArray().apply {
            put(fp.language)
            val base = fp.language.substringBefore('-')
            if (base != fp.language) put(base)
            if (fp.language != "en" && base != "en") put("en")
        }
        val brands = JSONArray().apply {
            when (engine) {
                BrowserEngine.CHROME -> {
                    put(brandEntry("\"Not(A:Brand\";v=\"24\"", "\"Chromium\""))
                    put(brandEntry("\"Google Chrome\";v=\"" + major(fp.browserVersion) + "\"", "\"Google Chrome\""))
                }
                BrowserEngine.CHROMIUM -> {
                    put(brandEntry("\"Not(A:Brand\";v=\"24\"", "\"Chromium\""))
                    put(brandEntry("\"Chromium\";v=\"" + major(fp.browserVersion) + "\"", "\"Chromium\""))
                }
                BrowserEngine.BRAVE -> {
                    put(brandEntry("\"Not(A:Brand\";v=\"24\"", "\"Brave\""))
                    put(brandEntry("\"Brave\";v=\"" + major(fp.browserVersion) + "\"", "\"Brave\""))
                }
                BrowserEngine.EDGE -> {
                    put(brandEntry("\"Not(A:Brand\";v=\"24\"", "\"Microsoft Edge\""))
                    put(brandEntry("\"Microsoft Edge\";v=\"" + major(fp.browserVersion) + "\"", "\"Microsoft Edge\""))
                }
                BrowserEngine.FIREFOX -> Unit
            }
        }

        val cfg = JSONObject().apply {
            put("seed", fp.seed)
            put("ua", FingerprintFactory.userAgent(fp, engine))
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
        // A stable per-profile offset (in 1/1000 px) applied to text measurements when font
        // noise is on: measurements and getBoundingClientRect answers must agree with each other,
        // which is why one shared value is used rather than independent jitter per call.
        put("fontEpsilon", if (fp.fontNoise) 40 + Math.floorMod(fp.seed, 37) else 0)
            put("fontNoise", fp.fontNoise)
            put("mediaNoise", fp.mediaNoise)
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
            put("isFirefox", engine == BrowserEngine.FIREFOX)
            put("isIos", isIos)
            put("safariLike", safariLike)
            put("pdfViewer", chromeLike)
            put("unlockSelection", unlockSelection)
            put("brands", brands)
            // Model/platform-version tokens for getHighEntropyValues: they must describe the
            // identity's platform, not this device. An Android identity reporting 'Pixel 9' on a
            // Mac profile (or a fixed 15.0.0 on macOS) is a contradiction a probe can read.
            put("androidModel", if (fp.os == "Android") fp.deviceModel else "")
            put("platformVersion", when (fp.os) {
                "Windows 11" -> "15.0.0"
                "Windows 10" -> "10.0.0"
                "macOS" -> "14.5.0"
                "Linux" -> ""
                "Android" -> fp.androidVersion.takeIf { it.isNotBlank() && it != "auto" } ?: ""
                else -> ""
            })
            put("isMobile", fp.deviceType == "mobile")
        }

        return TEMPLATE.replace("__PATCH_HELPERS__", PATCH_HELPERS).replace("__CFG__", cfg.toString())
    }

    private val VERSION_PATTERN = Regex("v=\"?([0-9]+)")

    private fun major(build: String): String = build.substringBefore('.')

    private fun brandEntry(brand: String, out: String): JSONObject = JSONObject().apply {
        put("brand", brand.trim('"').replace("\"", ""))
        put("version", VERSION_PATTERN.find(brand)?.groupValues?.getOrNull(1).orEmpty())
        put("out", out.trim('"').replace("\"", ""))
        put("raw", brand)
    }

    // The script is kept as one template with two placeholders so it stays readable and
    // Kotlin never has to escape JavaScript's own braces or quotes.
    private val TEMPLATE: String = """
(function () {
  'use strict';
  try {
    if (window.__jbKernel) { return; }
    Object.defineProperty(window, '__jbKernel', { value: true, enumerable: false, configurable: false });
  } catch (e) { return; }

  var JB = __CFG__;
  var SEED = (JB.seed | 0) >>> 0;
  if (SEED === 0) { SEED = 1; }
__PATCH_HELPERS__

  /* ------------------------------------------------------------------ noise */
  function ihash(a, b, c) {
    var h = (SEED ^ Math.imul(a | 0, 374761393) ^ Math.imul(b | 0, 668265263) ^ Math.imul(c | 0, 2246822519)) | 0;
    h = Math.imul(h ^ (h >>> 13), 1274126177);
    h ^= h >>> 16;
    return h >>> 0;
  }
  function noiseAt(i, salt) { return (ihash(i | 0, 0, salt) % 5) - 2; }
  function clamp8(v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }

  /* ------------------------------------------------------------------ fonts */
  /*
   * Font-metric defence. Text measurement is one of the cheapest fingerprinting signals: a page
   * renders a hidden string and reads its width. Nothing here changes layout — the page still
   * renders with the device's real fonts — it only shifts the *numbers handed to scripts* by a
   * stable per-profile amount, so two profiles on one device do not report identical metrics.
   *
   * The original accessors are captured before patching; reading them back afterwards would
   * recurse into this layer.
   */
  /*
   * Two numbers, because the two surfaces have different precision:
   *  - canvas measureText returns a float, so a sub-pixel offset is enough to make two profiles
   *    on the same device disagree, with no visible effect at all;
   *  - offsetWidth and friends are integers by definition, so a fractional offset would be
   *    rounded away and every profile would look identical. There the offset is 0 or exactly
   *    one pixel, chosen per profile: never enough to break a layout, enough to differ.
   */
  var FONT_FRACTION = (JB.fontEpsilon | 0) / 1000;
  var FONT_SHIFT_PX = (JB.fontEpsilon | 0) >= 60 ? 1 : 0;
  var FONT_TARGETS = ['offsetWidth', 'offsetHeight', 'scrollWidth', 'scrollHeight',
    'clientWidth', 'clientHeight'];

  function shiftMeasurement(value) {
    if (!FONT_SHIFT_PX || typeof value !== 'number' || !isFinite(value) || value < 0) return value;
    return value + FONT_SHIFT_PX;
  }

  function installFontMetricLayer() {
    if (!FONT_FRACTION && !FONT_SHIFT_PX) return;
    try {
      if (window.HTMLElement) {
        FONT_TARGETS.forEach(function (prop) {
          try {
            var descriptor = Object.getOwnPropertyDescriptor(HTMLElement.prototype, prop);
            var nativeGet = descriptor && descriptor.get;
            if (!nativeGet) return;
            var wrapper = nativeToString(function () {
              return shiftMeasurement(nativeGet.call(this));
            }, nativeGet);
            Object.defineProperty(HTMLElement.prototype, prop, {
              get: wrapper, configurable: true, enumerable: descriptor.enumerable
            });
          } catch (e) {}
        });
      }
    } catch (e) {}

    try {
      if (window.CanvasRenderingContext2D) {
        patchMethod(CanvasRenderingContext2D.prototype, 'measureText', function (original) {
          return function (text) {
            var metrics = original.call(this, text);
            if (!metrics || typeof metrics.width !== 'number') return metrics;
            try {
              // Same direction and magnitude class as the element metrics above, so a page that
              // cross-checks canvas against DOM sees one consistent story, never a contradiction.
              Object.defineProperty(metrics, 'width', {
                value: metrics.width + FONT_FRACTION, configurable: true, enumerable: true
              });
            } catch (e) {}
            return metrics;
          };
        });
      }
    } catch (e) {}
  }
  installFontMetricLayer();

  /* ---------------------------------------------------------------- canvas */
  function addCanvasNoise(data) {
    if (!data || !data.length) return data;
    for (var i = 0; i < data.length; i += 4) {
      var px = i >>> 2;
      data[i] = clamp8(data[i] + noiseAt(px, 11));
      data[i + 1] = clamp8(data[i + 1] + noiseAt(px, 23));
      data[i + 2] = clamp8(data[i + 2] + noiseAt(px, 47));
    }
    return data;
  }
  if (JB.canvasNoise) {
    try {
      if (window.CanvasRenderingContext2D) {
        patchMethod(CanvasRenderingContext2D.prototype, 'getImageData', function (original) {
          return function () {
            var img = original.apply(this, arguments);
            try { if (img && img.data) addCanvasNoise(img.data); } catch (e) {}
            return img;
          };
        });
      }
      if (window.HTMLCanvasElement) {
        patchMethod(HTMLCanvasElement.prototype, 'toDataURL', function (original) {
          return function () {
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
                return original.apply(scratch, arguments);
              }
            } catch (e) {}
            return original.apply(this, arguments);
          };
        });
        patchMethod(HTMLCanvasElement.prototype, 'toBlob', function (original) {
          return function () {
            var self = this, args = arguments;
            try {
              var w = self.width, h = self.height;
              if (w > 0 && h > 0 && w <= 4096 && h <= 4096) {
                var scratch = document.createElement('canvas');
                scratch.width = w; scratch.height = h;
                var ctx = scratch.getContext('2d');
                ctx.drawImage(self, 0, 0);
                var img = ctx.getImageData(0, 0, w, h);
                addCanvasNoise(img.data);
                ctx.putImageData(img, 0, 0);
                return original.apply(scratch, args);
              }
            } catch (e) {}
            return original.apply(self, args);
          };
        });
      }
    } catch (e) {}
  }

  /* ----------------------------------------------------------------- WebGL */
  var GL_VENDOR = JB.webglVendor;
  var GL_RENDERER = JB.webglRenderer;
  var GL_VERSION = 'WebGL 1.0 (OpenGL ES 2.0 Chromium)';
  var GL_SL_VERSION = 'WebGL GLSL ES 1.0 (OpenGL ES GLSL ES 1.0 Chromium)';
  function patchGL(proto) {
    if (!proto) return;
    patchMethod(proto, 'getParameter', function (original) {
      return function (param) {
        try {
          if (param === 37445 || param === 7936) return GL_VENDOR;
          if (param === 37446 || param === 7937) return GL_RENDERER;
          if (param === 7938) return GL_VERSION;
          if (param === 35724) return GL_SL_VERSION;
        } catch (e) {}
        return original.apply(this, arguments);
      };
    });
    patchMethod(proto, 'getExtension', function (original) {
      return function (name) {
        var ext = original.apply(this, arguments);
        try {
          if (String(name).toLowerCase() === 'webgl_debug_renderer_info') {
            return {
              UNMASKED_VENDOR_WEBGL: 37445,
              UNMASKED_RENDERER_WEBGL: 37446
            };
          }
        } catch (e) {}
        return ext;
      };
    });
    patchMethod(proto, 'getSupportedExtensions', function (original) {
      return function () {
        var list = original.apply(this, arguments) || [];
        try {
          if (list.indexOf('WEBGL_debug_renderer_info') < 0) list.push('WEBGL_debug_renderer_info');
        } catch (e) {}
        return list;
      };
    });
    if (JB.hwNoise) {
      patchMethod(proto, 'readPixels', function (original) {
        return function (x, y, w, h, format, type, pixels) {
          var out = original.apply(this, arguments);
          try {
            if (pixels && pixels.length) {
              for (var i = 0; i < pixels.length; i += 4) {
                pixels[i] = clamp8(pixels[i] + noiseAt((i >>> 2) + (x | 0), 101));
                pixels[i + 1] = clamp8(pixels[i + 1] + noiseAt((i >>> 2) + (y | 0), 103));
                pixels[i + 2] = clamp8(pixels[i + 2] + noiseAt(i >>> 2, 107));
              }
            }
          } catch (e) {}
          return out;
        };
      });
    }
  }
  try { patchGL(window.WebGLRenderingContext && WebGLRenderingContext.prototype); } catch (e) {}
  try { patchGL(window.WebGL2RenderingContext && WebGL2RenderingContext.prototype); } catch (e) {}

  /* ----------------------------------------------------------------- audio */
  if (JB.audioNoise) {
    try {
      if (window.AudioBuffer && AudioBuffer.prototype.getChannelData) {
        patchMethod(AudioBuffer.prototype, 'getChannelData', function (original) {
          return function (channel) {
            var data = original.call(this, channel);
            try {
              for (var i = 0; i < data.length; i++) data[i] = data[i] + noiseAt(i, 1009) * 1e-7;
            } catch (e) {}
            return data;
          };
        });
      }
      if (window.AnalyserNode) {
        patchMethod(AnalyserNode.prototype, 'getFloatFrequencyData', function (original) {
          return function (array) {
            var out = original.apply(this, arguments);
            try {
              for (var i = 0; i < array.length; i++) array[i] = array[i] + noiseAt(i, 1201) * 1e-5;
            } catch (e) {}
            return out;
          };
        });
      }
    } catch (e) {}
  }

  /* ------------------------------------------------------------- navigator */
  var nav = window.Navigator && Navigator.prototype;
  if (nav) {
    patchGetter(nav, 'userAgent', function () { return JB.ua; });
    patchGetter(nav, 'appVersion', function () { return JB.ua.replace(/^Mozilla\//, ''); });
    patchGetter(nav, 'platform', function () { return JB.platform; });
    patchGetter(nav, 'vendor', function () { return JB.vendor; });
    patchGetter(nav, 'hardwareConcurrency', function () { return JB.cores; });
    patchGetter(nav, 'deviceMemory', function () { return JB.memory; });
    patchGetter(nav, 'maxTouchPoints', function () { return JB.touch; });
    patchGetter(nav, 'language', function () { return JB.language; });
    patchGetter(nav, 'languages', function () { return Object.freeze(JB.languages.slice()); });
    // navigator.webdriver is an own property in Blink, so the pair helper is required here.
    patchOwn(window.navigator, nav, 'webdriver', function () { return undefined; });
    patchGetter(nav, 'productSub', function () { return '20030107'; });
    // Belt and braces: if some WebView keeps a locked own descriptor, remove it entirely.
    try {
      if (window.navigator && window.navigator.webdriver !== undefined) {
        delete window.navigator.webdriver;
      }
    } catch (e) {}
    patchGetter(nav, 'cookieEnabled', function () { return true; });
    patchGetter(nav, 'doNotTrack', function () { return JB.doNotTrack ? '1' : null; });
    // WebKit (Safari/iOS) does expose this flag and it is true there; Chromium exposes it too.
    // Only Firefox lacks it, which is handled by the hide-it block below.
    patchGetter(nav, 'pdfViewerEnabled', function () {
      if (JB.isFirefox) return undefined;
      if (JB.safariLike) return true;
      return !!JB.pdfViewer;
    });
    patchGetter(nav, 'appCodeName', function () { return 'Mozilla'; });
    patchGetter(nav, 'appName', function () { return 'Netscape'; });
    patchGetter(nav, 'product', function () { return 'Gecko'; });

    /* userAgentData (Chromium only — a Firefox identity must not expose it) */
    if (JB.chromeLike && window.NavigatorUAData) {
      try {
        var uaData = {
          brands: JB.brands.map(function (b) { return { brand: b.brand, version: b.version }; }),
          mobile: !!JB.isMobile,
          platform: JB.platform === 'Win32' ? 'Windows' :
                    JB.platform === 'MacIntel' ? 'macOS' :
                    JB.platform === 'iPhone' || JB.platform === 'iPad' ? 'iOS' :
                    JB.isMobile ? 'Android' : 'Linux',
          getHighEntropyValues: function (hints) {
            return Promise.resolve({
              architecture: JB.platform === 'Linux armv81' ? 'arm' : 'x86',
              bitness: '64',
              brands: this.brands,
              fullVersionList: JB.brands.map(function (b) {
                return { brand: b.brand, version: b.version + '.0.0.0' };
              }),
              mobile: this.mobile,
              model: JB.androidModel || '',
              platform: this.platform,
              platformVersion: JB.platformVersion || '',
              uaFullVersion: JB.ua.indexOf('Firefox') >= 0 ? '' : majorVersion(),
              wow64: false
            });
          }
        };
        function majorVersion() {
          var m = JB.ua.match(/(?:Chrome|CriOS|Edg|EdgA)\/([0-9]+)/);
          return m ? m[1] + '.0.0.0' : '';
        }
        patchGetter(nav, 'userAgentData', function () { return uaData; });
      } catch (e) {}
    }

    /* plugins / mimeTypes: full PDF set on desktop, empty on mobile */
    if (JB.isDesktop) {
      try {
        var pluginSpecs = [
          ['PDF Viewer', 'internal-pdf-viewer'],
          ['Chrome PDF Viewer', 'internal-pdf-viewer'],
          ['Chromium PDF Viewer', 'internal-pdf-viewer'],
          ['Microsoft Edge PDF Viewer', 'internal-pdf-viewer'],
          ['WebKit built-in PDF', 'internal-pdf-viewer']
        ];
        var pluginArray = {};
        for (var pi = 0; pi < pluginSpecs.length; pi++) {
          pluginArray[pi] = {
            name: pluginSpecs[pi][0],
            filename: pluginSpecs[pi][1],
            description: 'Portable Document Format',
            length: 1
          };
        }
        pluginArray.length = pluginSpecs.length;
        pluginArray.item = function (i) { return this[i] || null; };
        pluginArray.namedItem = function (n) {
          for (var i = 0; i < this.length; i++) if (this[i].name === n) return this[i];
          return null;
        };
        pluginArray.refresh = function () {};
        var mimeArray = {
          0: { type: 'application/pdf', suffixes: 'pdf', description: 'Portable Document Format' },
          1: { type: 'text/pdf', suffixes: 'pdf', description: 'Portable Document Format' },
          length: 2
        };
        mimeArray.item = function (i) { return this[i] || null; };
        mimeArray.namedItem = function (n) {
          for (var i = 0; i < this.length; i++) if (this[i].type === n) return this[i];
          return null;
        };
        patchGetter(nav, 'plugins', function () { return pluginArray; });
        patchGetter(nav, 'mimeTypes', function () { return mimeArray; });
      } catch (e) {}
    } else {
      try {
        var emptyPlugins = { length: 0, item: function () { return null; }, namedItem: function () { return null; }, refresh: function () {} };
        patchGetter(nav, 'plugins', function () { return emptyPlugins; });
        patchGetter(nav, 'mimeTypes', function () { return emptyPlugins; });
      } catch (e) {}
    }

    /*
     * Surfaces a WebKit browser does not have.
     *
     * A page can check for these in one line, so leaving them present under an iPhone/iPad
     * identity (or under a Firefox identity) contradicts the user agent the page was served.
     * Hiding them is not "emulating Safari" — the app never claims to render with WebKit — but it
     * does stop the identity from refuting itself.
     */
    if (JB.safariLike || JB.isFirefox) {
      ['userAgentData', 'deviceMemory', 'connection'].forEach(function (prop) {
        try {
          if (prop in navigator || Object.getOwnPropertyDescriptor(window.Navigator.prototype, prop)) {
            Object.defineProperty(window.Navigator.prototype, prop, {
              get: function () { return undefined; }, configurable: true, enumerable: false
            });
            Object.defineProperty(navigator, prop, {
              get: function () { return undefined; }, configurable: true, enumerable: false
            });
          }
        } catch (e) {}
      });
    }
    // Firefox exposes neither the Chromium UA-Data API nor WebKit's pdfViewerEnabled flag.
    if (JB.isFirefox) {
      try {
        Object.defineProperty(navigator, 'pdfViewerEnabled', {
          get: function () { return undefined; }, configurable: true, enumerable: false
        });
      } catch (e) {}
    }

    /* connection */
    try {
      if (window.NetworkInformation && NetworkInformation.prototype) {
        patchGetter(NetworkInformation.prototype, 'effectiveType', function () { return '4g'; });
        patchGetter(NetworkInformation.prototype, 'rtt', function () { return 50; });
        patchGetter(NetworkInformation.prototype, 'downlink', function () { return 10; });
        patchGetter(NetworkInformation.prototype, 'saveData', function () { return false; });
      }
    } catch (e) {}
  }

  /* ---------------------------------------------------------------- screen */
  try {
    if (window.Screen && Screen.prototype) {
      patchGetter(Screen.prototype, 'width', function () { return JB.screenW; });
      patchGetter(Screen.prototype, 'height', function () { return JB.screenH; });
      patchGetter(Screen.prototype, 'availWidth', function () { return JB.availW; });
      patchGetter(Screen.prototype, 'availHeight', function () { return JB.availH; });
      patchGetter(Screen.prototype, 'availLeft', function () { return 0; });
      patchGetter(Screen.prototype, 'availTop', function () { return 0; });
      patchGetter(Screen.prototype, 'colorDepth', function () { return JB.colorDepth; });
      patchGetter(Screen.prototype, 'pixelDepth', function () { return JB.colorDepth; });
    }
    patchGetter(window, 'devicePixelRatio', function () { return JB.dpr; });
    patchMethod(window, 'matchMedia', function (original) {
      return function (query) {
        try {
          var q = String(query);
          var m = q.match(/device-pixel-ratio\s*:\s*([0-9.]+)/);
          if (m) {
            var target = parseFloat(m[1]);
            var matches = Math.abs(target - JB.dpr) < 0.01 || Math.abs(target - 1) < 0.01;
            return {
              matches: matches, media: q,
              addEventListener: function () {}, removeEventListener: function () {},
              addListener: function () {}, removeListener: function () {}, onchange: null
            };
          }
        } catch (e) {}
        return original.apply(this, arguments);
      };
    });
  } catch (e) {}

  /* -------------------------------------------------------------- timezone */
  var JB_TZ = JB.timezone;
  try {
    var tzParts = function (date) {
      try {
        var fmt = new Intl.DateTimeFormat('en-US', {
          timeZone: JB_TZ, hour12: false, year: 'numeric', month: '2-digit',
          day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit'
        });
        var parts = fmt.formatToParts(date);
        var map = {};
        for (var i = 0; i < parts.length; i++) { map[parts[i].type] = parts[i].value; }
        var hour = parseInt(map.hour, 10) % 24;
        return Date.UTC(+map.year, +map.month - 1, +map.day, hour, +map.minute, +map.second);
      } catch (e) {
        return null;
      }
    };
    var originalOffset = Date.prototype.getTimezoneOffset;
    patchMethod(Date.prototype, 'getTimezoneOffset', function () {
      return function () {
        var t = this.getTime();
        if (isNaN(t)) return 0;
        var asUTC = tzParts(this);
        if (asUTC == null) { return originalOffset.apply(this, arguments); }
        var utc = Math.floor(t / 1000) * 1000;
        return Math.round((asUTC - utc) / 60000);
      };
    });
    if (window.Intl && Intl.DateTimeFormat) {
      patchMethod(Intl.DateTimeFormat.prototype, 'resolvedOptions', function (original) {
        return function () {
          var out = original.apply(this, arguments);
          try { out.timeZone = JB_TZ; } catch (e) {}
          return out;
        };
      });
    }
  } catch (e) {}

  /* ---------------------------------------------------------------- WebRTC */
  try {
    var OrigPC = window.RTCPeerConnection || window.webkitRTCPeerConnection;
    if (JB.webrtcMode === 'off') {
      if (OrigPC) {
        window.RTCPeerConnection = undefined;
        window.webkitRTCPeerConnection = undefined;
      }
    } else if (JB.webrtcMode === 'proxy' && OrigPC) {
      var PatchedPC = function (config) {
        var next = config ? JSON.parse(JSON.stringify(config)) : {};
        next.iceTransportPolicy = 'relay';
        return new OrigPC(next);
      };
      PatchedPC.prototype = OrigPC.prototype;
      nativeToString(PatchedPC, OrigPC);
      try { Object.setPrototypeOf(PatchedPC, OrigPC); } catch (e) {}
      window.RTCPeerConnection = PatchedPC;
      window.webkitRTCPeerConnection = PatchedPC;
    }
  } catch (e) {}

  /* ----------------------------------------------------------- geolocation */
  try {
    if (navigator.geolocation) {
      var geo = navigator.geolocation;
      if (JB.geoMode === 'block') {
        var deny = function (success, error) {
          if (typeof error === 'function') {
            error({ code: 1, message: 'User denied geolocation', PERMISSION_DENIED: 1, POSITION_UNAVAILABLE: 2, TIMEOUT: 3 });
          }
        };
        geo.getCurrentPosition = deny;
        geo.watchPosition = function (success, error) { deny(success, error); return 0; };
      } else if (JB.geoMode === 'custom') {
        var fakePosition = function () {
          return {
            coords: {
              latitude: JB.geoLat, longitude: JB.geoLng, accuracy: 25,
              altitude: null, altitudeAccuracy: null, heading: null, speed: null
            },
            timestamp: Date.now()
          };
        };
        geo.getCurrentPosition = function (success) { if (typeof success === 'function') success(fakePosition()); };
        geo.watchPosition = function (success) { if (typeof success === 'function') success(fakePosition()); return 1; };
      }
    }
  } catch (e) {}

  /* --------------------------------------------------------------- battery */
  /*
   * Battery Status API. Two bugs lived here: the property is `navigator.getBattery()` (a method
   * on navigator), not something to overwrite as a plain value, and it was assigned without the
   * nativeToString wrapper — so the patched function announced itself. The level is also derived
   * from the profile seed now, because a constant 0.87 across every profile is its own signal.
   */
  if (JB.spoofBattery && navigator.getBattery) {
    try {
      var originalBattery = navigator.getBattery;
      var seededLevel = 0.35 + (ihash(0, 0, 907) % 60) / 100;
      var batteryWrapper = function () {
        return Promise.resolve({
          charging: true,
          chargingTime: 0,
          dischargingTime: Infinity,
          level: seededLevel,
          onchargingchange: null,
          onchargingtimechange: null,
          ondischargingtimechange: null,
          onlevelchange: null,
          addEventListener: function () {},
          removeEventListener: function () {},
          dispatchEvent: function () { return true; }
        });
      };
      nativeToString(batteryWrapper, originalBattery);
      Object.defineProperty(navigator, 'getBattery', {
        value: batteryWrapper, configurable: true, writable: true, enumerable: false
      });
    } catch (e) {}
  }

  /* ------------------------------------------------------- media devices */
  if (JB.mediaNoise) {
    try {
      if (navigator.mediaDevices && navigator.mediaDevices.enumerateDevices) {
        patchMethod(navigator.mediaDevices, 'enumerateDevices', function (original) {
          return function () {
            return original.apply(this, arguments).then(function (list) {
              try {
                var hasAudio = list.some(function (d) { return d.kind === 'audioinput'; });
                var out = list.slice();
                if (!hasAudio) {
                  out.push({ deviceId: '', kind: 'audioinput', label: '', groupId: '' });
                  out.push({ deviceId: '', kind: 'audiooutput', label: '', groupId: '' });
                  out.push({ deviceId: '', kind: 'videoinput', label: '', groupId: '' });
                }
                return out;
              } catch (e) { return list; }
            });
          };
        });
      }
    } catch (e) {}
  }

  /* ----------------------------------------------------------- window.chrome */
  if (JB.chromeLike) {
    try {
      window.chrome = window.chrome || {};
      if (!window.chrome.app) {
        window.chrome.app = {
          isInstalled: false,
          InstallState: { DISABLED: 'disabled', INSTALLED: 'installed', NOT_INSTALLED: 'not_installed' },
          RunningState: { CANNOT_RUN: 'cannot_run', READY_TO_RUN: 'ready_to_run', RUNNING: 'running' },
          getDetails: function () { return null; },
          getIsInstalled: function () { return false; }
        };
      }
      if (!window.chrome.csi) {
        window.chrome.csi = function () {
          return { startE: Date.now(), onloadT: Date.now(), pageT: Date.now() % 97, tran: 15 };
        };
      }
      if (!window.chrome.loadTimes) {
        window.chrome.loadTimes = function () {
          var now = Date.now() / 1000;
          return {
            commitLoadTime: now, connectionInfo: 'h2', finishDocumentLoadTime: now,
            finishLoadTime: now, firstPaintAfterLoadTime: 0, firstPaintTime: now,
            navigationType: 'Other', wasAlternateProtocolAvailable: false,
            wasFetchedViaSpdy: true, wasNpnNegotiated: true
          };
        };
      }
      if (!window.chrome.runtime) {
        window.chrome.runtime = {
          OnInstalledReason: { CHROME_UPDATE: 'chrome_update', INSTALL: 'install', SHARED_MODULE_UPDATE: 'shared_module_update', UPDATE: 'update' },
          OnRestartRequiredReason: { APP_UPDATE: 'app_update', OS_UPDATE: 'os_update', PERIODIC: 'periodic' },
          PlatformArch: { ARM: 'arm', ARM64: 'arm64', MIPS: 'mips', MIPS64: 'mips64', X86_32: 'x86-32', X86_64: 'x86-64' },
          PlatformOs: { ANDROID: 'android', CROS: 'cros', LINUX: 'linux', MAC: 'mac', OPENBSD: 'openbsd', WIN: 'win' },
          id: undefined
        };
      }
    } catch (e) {}
  } else if (JB.isFirefox) {
    try { delete window.chrome; } catch (e) { window.chrome = undefined; }
  }

  /* ------------------------------------------------ automation fingerprints */
  try {
    var markers = ['cdc_adoQpoasnfa76pfcZLmcfl_', '__webdriver_evaluate', '__selenium_evaluate',
      '__webdriver_script_function', '__webdriver_script_func', '__webdriver_script_fn',
      '__fxdriver_evaluate', '__driver_unwrapped', '__webdriver_unwrapped', '__driver_evaluate',
      '_Selenium_IDE_Recorder', '_selenium', 'calledSelenium', 'webdriver', '__nightmare'];
    for (var mi = 0; mi < markers.length; mi++) {
      try { if (markers[mi] in document) delete document[markers[mi]]; } catch (e) {}
      try { if (markers[mi] in window) delete window[markers[mi]]; } catch (e) {}
    }
    var cdc = Object.keys(window).filter(function (k) { return /^cdc_|^\$?chrome_asyncScriptInfo/.test(k); });
    for (var ci = 0; ci < cdc.length; ci++) { try { delete window[cdc[ci]]; } catch (e) {} }
  } catch (e) {}

  /* --------------------------------------- built-in: unlock selection/copy */
  if (JB.unlockSelection) {
    try {
      var injectStyle = function () {
        try {
          var style = document.createElement('style');
          style.textContent = '*{user-select:text !important;-webkit-user-select:text !important;}';
          (document.head || document.documentElement).appendChild(style);
        } catch (e) {}
      };
      if (document.head) { injectStyle(); } else {
        document.addEventListener('DOMContentLoaded', injectStyle, { once: true });
      }
      ['contextmenu', 'selectstart', 'copy', 'cut', 'dragstart'].forEach(function (type) {
        var stop = function (event) {
          try {
            if (event.__jbAllowed) return;
            event.stopImmediatePropagation();
            event.stopPropagation();
          } catch (e) {}
        };
        window.addEventListener(type, stop, true);
        document.addEventListener(type, stop, true);
      });
    } catch (e) {}
  }
})();
""".trimIndent()
}
