#!/usr/bin/env node
/*
 * Fingerprint layer verifier.
 *
 * The spoofing layer is the one part of Joe Browser that cannot be exercised by the Android
 * build: it is JavaScript built in Kotlin and injected into a WebView, so a compile error in
 * the Kotlin says nothing about whether the script actually rewrites `navigator` at runtime.
 *
 * This harness does three things, in order:
 *   1. pulls PATCH_HELPERS + TEMPLATE straight out of FingerprintScript.kt (no copies to drift),
 *   2. substitutes a real config object the way FingerprintScript.build() does,
 *   3. evaluates it against a mock environment whose values live as prototype getters —
 *      which is how Blink exposes navigator/screen — and asserts the observable result.
 *
 * Usage: node tools/verify-fingerprint.js
 * Exit code 0 = every assertion held.
 */
'use strict';

const fs = require('fs');
const path = require('path');

const SRC = path.join(__dirname, '..', 'app', 'src', 'main', 'java', 'com', 'joebrowser', 'app',
  'fingerprint', 'FingerprintScript.kt');

/** Extracts a Kotlin raw string (`""" ... """`) that starts at the given marker. */
function rawString(source, marker) {
  const at = source.indexOf(marker);
  if (at < 0) throw new Error(`marker not found: ${marker}`);
  const start = source.indexOf('"""', at) + 3;
  const end = source.indexOf('"""', start);
  if (end < 0) throw new Error(`unterminated raw string after ${marker}`);
  return source.slice(start, end);
}

/** The config FingerprintScript.build() would emit for this profile. */
function sampleConfig(overrides) {
  const base = {
    seed: 0x5eed,
    ua: 'Mozilla/5.0 (Linux; Android 14; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) ' +
        'Chrome/134.0.6998.89 Mobile Safari/537.36',
    platform: 'Linux armv8l',
    vendor: 'Google Inc.',
    language: 'de-DE',
    languages: ['de-DE', 'de', 'en'],
    timezone: 'Europe/Berlin',
    screenW: 411, screenH: 915, availW: 411, availH: 915,
    colorDepth: 24, dpr: 2.625,
    cores: 8, memory: 8, touch: 5,
    canvasNoise: true, audioNoise: true, hwNoise: true,
    fontNoise: true, mediaNoise: true,
    doNotTrack: false, spoofBattery: true,
    webrtcMode: 'proxy', geoMode: 'proxy',
    geoLat: 52.52, geoLng: 13.4,
    webglVendor: 'Google Inc. (ARM)',
    webglRenderer: 'ANGLE (ARM, Mali-G720, OpenGL ES 3.2)',
    isDesktop: false, chromeLike: true, isFirefox: false,
    pdfViewer: true, unlockSelection: true,
    brands: [
      { brand: 'Not(A:Brand', version: '24', out: 'Not(A:Brand', raw: '"Not(A:Brand";v="24"' },
      { brand: 'Google Chrome', version: '134', out: 'Google Chrome', raw: '"Google Chrome";v="134"' }
    ],
    isMobile: true
  };
  return Object.assign(base, overrides || {});
}

/** Blink-style object: values are own getters on a prototype, as they are in the real engine. */
function protoGetters(proto, values) {
  Object.keys(values).forEach(function (key) {
    Object.defineProperty(proto, key, {
      get: function () { return values[key]; },
      configurable: true,
      enumerable: true
    });
  });
  return proto;
}

/** Builds a mock window that is hostile-but-realistic: stale native values everywhere. */
function makeEnvironment(cfg) {
  const navProto = protoGetters({}, {
    userAgent: 'Mozilla/5.0 (Linux; Android 10; SM-G950F) Chrome/100.0 Mobile Safari/537.36',
    appVersion: '5.0 (Linux; Android 10; SM-G950F) Chrome/100.0 Mobile Safari/537.36',
    platform: 'Linux armv8l',
    vendor: 'Google Inc.',
    language: 'en-US',
    languages: ['en-US'],
    hardwareConcurrency: 4,
    deviceMemory: 4,
    maxTouchPoints: 0,
    doNotTrack: null,
    pdfViewerEnabled: false,
    productSub: '20030107',
    appCodeName: 'Mozilla',
    appName: 'Netscape',
    product: 'Gecko',
    cookieEnabled: true
  });

  const navigator = Object.create(navProto);
  // Blink exposes webdriver as an own property; an automated WebView reports true.
  Object.defineProperty(navigator, 'webdriver', {
    get: function () { return true; }, configurable: true, enumerable: true
  });

  const screenProto = protoGetters({}, {
    width: 360, height: 640, availWidth: 360, availHeight: 640, colorDepth: 24, pixelDepth: 24
  });

  function rawPixels(count) {
    const data = new Uint8ClampedArray(count);
    for (let i = 0; i < count; i++) data[i] = (i * 7) % 251;
    return data;
  }
  function CanvasRenderingContext2D() {}
  CanvasRenderingContext2D.prototype.getImageData = function (x, y, w, h) {
    return { data: rawPixels(w * h * 4), width: w, height: h };
  };
  function HTMLCanvasElement() {}
  HTMLCanvasElement.prototype.getContext = function () { return new CanvasRenderingContext2D(); };

  const env = {};
  env.window = env;
  env.self = env;
  env.globalThis = env;
  env.top = env;
  env.parent = env;
  env.Navigator = function () {};
  env.Navigator.prototype = navProto;
  env.Screen = function () {};
  env.Screen.prototype = screenProto;
  env.navigator = navigator;
  env.screen = Object.create(screenProto);
  env.location = { href: 'https://example.com/', protocol: 'https:' };
  env.document = {
    documentElement: { style: {} },
    body: { style: {} },
    referrer: '',
    hidden: false,
    visibilityState: 'visible',
    hasFocus: function () { return true; },
    createEvent: function () { return { initEvent: function () {} }; },
    createElement: function () {
      return {
        getContext: function () { return null; },
        style: {}, setAttribute: function () {}, appendChild: function () {}
      };
    },
    addEventListener: function () {}
  };
  env.addEventListener = function () {};
  env.setInterval = function () { return 0; };
  env.clearInterval = function () {};
  env.setTimeout = function () { return 0; };
  env.Intl = Intl;
  env.Math = Math;
  env.JSON = JSON;
  env.Object = Object;
  env.Array = Array;
  env.Error = Error;
  env.Date = Date;
  env.Promise = Promise;
  env.Uint8ClampedArray = Uint8ClampedArray;
  env.performance = { now: function () { return 0; }, timing: {} };
  env.devicePixelRatio = 1;
  env.CanvasRenderingContext2D = CanvasRenderingContext2D;
  env.HTMLCanvasElement = HTMLCanvasElement;
  env.__cfg = cfg;
  return env;
}

function build(cfg) {
  const source = fs.readFileSync(SRC, 'utf8');
  const helpers = rawString(source, 'PATCH_HELPERS');
  const template = rawString(source, 'TEMPLATE');
  if (template.indexOf('__PATCH_HELPERS__') < 0 || template.indexOf('__CFG__') < 0) {
    throw new Error('TEMPLATE lost its placeholders');
  }
  return template.replace('__PATCH_HELPERS__', helpers).replace('__CFG__', JSON.stringify(cfg));
}

function run(script, env) {
  const args = [
    'window', 'self', 'navigator', 'screen', 'document', 'location', 'devicePixelRatio',
    'performance', 'Intl', 'globalThis', 'Navigator', 'Screen', 'top',
    'CanvasRenderingContext2D', 'HTMLCanvasElement'
  ];
  const values = [
    env, env, env.navigator, env.screen, env.document, env.location, env.devicePixelRatio,
    env.performance, env.Intl, env, env.Navigator, env.Screen, env.top,
    env.CanvasRenderingContext2D, env.HTMLCanvasElement
  ];
  const Factory = Function.bind.apply(Function, [null].concat(args, [script]));
  const factory = new Factory();
  factory.apply(null, values);
  return env;
}

let failures = 0;
function check(label, actual, expected) {
  const ok = String(actual) === String(expected);
  if (!ok) failures++;
  console.log((ok ? '  ok   ' : '  FAIL ') + label + ' → ' + actual + (ok ? '' : ' (expected ' + expected + ')'));
}

console.log('Joe Browser — fingerprint layer verification\n');

// ---------------------------------------------------------------- syntax / idempotence
const cfg = sampleConfig();
const script = build(cfg);
try {
  new Function(script);
  console.log('  ok   script parses');
} catch (e) {
  console.log('  FAIL script does not parse: ' + e.message);
  process.exit(1);
}

const env = run(script, makeEnvironment(cfg));
const nav = env.navigator;

console.log('\nnavigator');
check('userAgent', nav.userAgent, cfg.ua);
check('appVersion has no Mozilla prefix', nav.appVersion.indexOf('Mozilla/') === 0, false);
check('platform', nav.platform, cfg.platform);
check('vendor', nav.vendor, cfg.vendor);
check('hardwareConcurrency', nav.hardwareConcurrency, cfg.cores);
check('deviceMemory', nav.deviceMemory, cfg.memory);
check('maxTouchPoints', nav.maxTouchPoints, cfg.touch);
check('language', nav.language, cfg.language);
check('languages', JSON.stringify(nav.languages), JSON.stringify(cfg.languages));
check('webdriver removed', nav.webdriver, 'undefined');
check('pdfViewerEnabled', nav.pdfViewerEnabled, 'true');
check('doNotTrack null when off', nav.doNotTrack, 'null');

console.log('\nscreen / viewport');
check('screen.width', env.screen.width, cfg.screenW);
check('screen.height', env.screen.height, cfg.screenH);
check('availWidth', env.screen.availWidth, cfg.availW);
check('availHeight', env.screen.availHeight, cfg.availH);
check('colorDepth', env.screen.colorDepth, cfg.colorDepth);

console.log('\ntimezone / locale');
check('timezone', Intl.DateTimeFormat().resolvedOptions().timeZone, cfg.timezone);

console.log('\ncanvas');
const ctx = new env.CanvasRenderingContext2D();
const first = Array.from(ctx.getImageData(0, 0, 8, 8).data);
const second = Array.from(ctx.getImageData(0, 0, 8, 8).data);
const untouched = Array.from((function () {
  const raw = new Uint8ClampedArray(8 * 8 * 4);
  for (let i = 0; i < raw.length; i++) raw[i] = (i * 7) % 251;
  return raw;
})());
check('stable across probes (same profile = same machine)', first.join() === second.join(), true);
check('noise actually applied', first.join() !== untouched.join(), true);

console.log('\nstability');
check('userAgent stable on repeat read', nav.userAgent === cfg.ua, true);
check('languages array is frozen', Object.isFrozen(nav.languages), true);

// ------------------------------------------------------------ second profile isolation
const otherCfg = sampleConfig({ seed: 0xbeef, language: 'fr-FR', languages: ['fr-FR', 'fr', 'en'], timezone: 'Europe/Paris', cores: 6 });
const otherEnv = run(build(otherCfg), makeEnvironment(otherCfg));
const otherCtx = new otherEnv.CanvasRenderingContext2D();
const otherPixels = Array.from(otherCtx.getImageData(0, 0, 8, 8).data);
console.log('\nper-profile separation');
check('cores differ per profile', otherEnv.navigator.hardwareConcurrency, otherCfg.cores);
check('locale differs per profile', otherEnv.navigator.language, otherCfg.language);
check('canvas differs between profiles', first.join() !== otherPixels.join(), true);

console.log('\n' + (failures === 0
  ? 'all assertions passed'
  : failures + ' assertion(s) failed'));
process.exit(failures === 0 ? 0 : 1);
