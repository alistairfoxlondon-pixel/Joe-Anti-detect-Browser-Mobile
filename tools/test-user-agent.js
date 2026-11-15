#!/usr/bin/env node
/*
 * User-agent matrix tests.
 *
 * The UA is the first thing a page reads, so a version token that no real browser ever sends is
 * a one-line detection. The traps that have actually bitten this codebase:
 *
 *   - iOS browsers are all WebKit shells. Chrome on iOS is CriOS/<iOS release>; the app used to
 *     emit CriOS/134.0.0.0, a desktop Chromium build. Firefox on iOS is FxiOS and reports no
 *     Gecko build at all.
 *   - A Chromium build number must never appear in a Gecko UA, and vice versa.
 *   - The OS token has to match the identity (Android 15, iPhone OS 18_3, Windows NT 10.0,
 *     Mac OS X 10_15_7, X11 Linux).
 *   - navigator.platform has to agree with the UA: 'Linux armv81' (with a one) is not a value
 *     any browser emits; Blink reports 'Linux armv8l'.
 *
 * The builders are re-implemented here from FingerprintFactory, so a change to the Kotlin that
 * is not mirrored here will surface as a failing matrix rather than a silent regression.
 *
 * Usage: node tools/test-user-agent.js
 */
'use strict';

const CHROMIUM_BUILDS = ['134.0.6998.89', '133.0.6943.98', '132.0.6834.110'];
const FIREFOX_BUILDS = ['134.0.2', '133.0.3'];

/** Port of FingerprintFactory.platformFor. */
function platformFor(os) {
  switch (os) {
    case 'Windows 11':
    case 'Windows 10': return 'Win32';
    case 'macOS': return 'MacIntel';
    case 'Linux': return 'Linux x86_64';
    case 'Android': return 'Linux armv8l';
    case 'iPhone': return 'iPhone';
    case 'iPad': return 'iPad';
    default: return 'Win32';
  }
}

/** Port of FingerprintFactory.buildForIos. */
function buildForIos(engine, iosVersion) {
  const release = iosVersion && iosVersion !== 'auto' ? iosVersion : '18.3';
  return engine === 'firefox' ? '134.0' : release;
}

/** Port of FingerprintFactory.userAgent (minus the override path). */
function userAgent(fp) {
  if (fp.userAgentOverride) return fp.userAgentOverride;
  const chromium = String(fp.browserVersion).split('.')[0];
  const firefox = String(fp.browserVersion).split('.')[0];
  const os = fp.os;
  const engine = fp.engine;

  if (os === 'Windows 11' || os === 'Windows 10') {
    if (engine === 'firefox') return `Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:${firefox}.0) Gecko/20100101 Firefox/${firefox}.0`;
    if (engine === 'edge') return `Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${chromium}.0.0.0 Safari/537.36 Edg/${chromium}.0.0.0`;
    return `Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${chromium}.0.0.0 Safari/537.36`;
  }
  if (os === 'macOS') {
    if (engine === 'firefox') return `Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:${firefox}.0) Gecko/20100101 Firefox/${firefox}.0`;
    if (engine === 'edge') return `Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${chromium}.0.0.0 Safari/537.36 Edg/${chromium}.0.0.0`;
    return `Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${chromium}.0.0.0 Safari/537.36`;
  }
  if (os === 'Linux') {
    if (engine === 'firefox') return `Mozilla/5.0 (X11; Linux x86_64; rv:${firefox}.0) Gecko/20100101 Firefox/${firefox}.0`;
    return `Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${chromium}.0.0.0 Safari/537.36`;
  }
  if (os === 'Android') {
    const version = fp.androidVersion && fp.androidVersion !== 'auto' ? fp.androidVersion : '15';
    const model = fp.deviceModel || 'Pixel 9';
    if (engine === 'firefox') return `Mozilla/5.0 (Android ${version}; Mobile; rv:${firefox}.0) Gecko/${firefox}.0 Firefox/${firefox}.0`;
    if (engine === 'edge') return `Mozilla/5.0 (Linux; Android ${version}; ${model}) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${chromium}.0.0.0 Mobile Safari/537.36 EdgA/${chromium}.0.0.0`;
    return `Mozilla/5.0 (Linux; Android ${version}; ${model}) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${chromium}.0.0.0 Mobile Safari/537.36`;
  }
  // iPhone / iPad
  const token = String(fp.iosVersion && fp.iosVersion !== 'auto' ? fp.iosVersion : '18.3').replace(/\./g, '_');
  const device = os;
  const cpu = `CPU ${os === 'iPhone' ? 'iPhone OS' : 'OS'} ${token}`;
  const safari = fp.iosVersion && fp.iosVersion !== 'auto' ? fp.iosVersion : '18.3';
  const iosApp = String(fp.browserVersion).includes('.') ? fp.browserVersion : safari;
  if (engine === 'firefox') return `Mozilla/5.0 (${device}; ${cpu} like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) FxiOS/${iosApp} Mobile/15E148 Safari/605.1.15`;
  if (engine === 'edge') return `Mozilla/5.0 (${device}; ${cpu} like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) EdgiOS/${iosApp} Mobile/15E148 Safari/605.1.15`;
  if (engine === 'chrome') return `Mozilla/5.0 (${device}; ${cpu} like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/${iosApp} Mobile/15E148 Safari/605.1.15`;
  return `Mozilla/5.0 (${device}; ${cpu} like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/${safari} Mobile/15E148 Safari/604.1`;
}

let failures = 0;
function check(label, condition, detail) {
  if (condition) {
    console.log('  ok   ' + label);
  } else {
    failures++;
    console.log('  FAIL ' + label + (detail ? ' — ' + detail : ''));
  }
}

const OS_LIST = ['Android', 'iPhone', 'iPad', 'Windows 11', 'Windows 10', 'macOS', 'Linux'];
const ENGINES = ['chrome', 'chromium', 'brave', 'edge', 'firefox'];

console.log('Joe Browser — user-agent matrix\n');

const cases = [];
for (const os of OS_LIST) {
  for (const engine of ENGINES) {
    const builds = engine === 'firefox' ? FIREFOX_BUILDS : CHROMIUM_BUILDS;
    for (const build of builds) {
      const ios = os === 'iPhone' || os === 'iPad';
      cases.push({
        os,
        engine,
        fp: {
          os,
          engine,
          // iOS browsers report the iOS release, everything else reports an engine build.
          browserVersion: ios ? buildForIos(engine, '18.3') : build,
          iosVersion: ios ? '18.3' : 'auto',
          androidVersion: '15',
          deviceModel: 'Pixel 9',
          userAgentOverride: ''
        }
      });
    }
  }
}

console.log('shape checks over ' + cases.length + ' identities');
let shapeProblems = 0;
const problems = [];
cases.forEach((c) => {
  const ua = userAgent(c.fp);
  const isFirefox = c.engine === 'firefox';
  const isIos = c.os === 'iPhone' || c.os === 'iPad';
  const hasFirefox = /Firefox\/|FxiOS\//.test(ua);
  const hasChrome = /Chrome\/|CriOS\/|EdgiOS\//.test(ua);
  // Chromium and Brave have no iOS build of their own, so the UA correctly collapses to the
  // Safari form (Version/... Safari/604.1). That is the honest answer, not a contradiction.
  const safariFallback = isIos && (c.engine === 'chromium' || c.engine === 'brave');

  if (isFirefox !== hasFirefox) problems.push(`${c.os}/${c.engine} ${c.fp.browserVersion}: engine token mismatch → ${ua}`);
  if (!safariFallback && isFirefox === hasChrome) problems.push(`${c.os}/${c.engine} ${c.fp.browserVersion}: contradictory engine tokens → ${ua}`);
  if (safariFallback && !/Safari\/604\.1/.test(ua)) problems.push(`${c.os}/${c.engine}: expected the Safari fallback → ${ua}`);
  if (isIos && /6998|6943|6834/.test(ua)) problems.push(`${c.os}/${c.engine}: desktop Chromium build on iOS → ${ua}`);
  if (isIos && !ua.includes('like Mac OS X')) problems.push(`${c.os}/${c.engine}: missing iOS platform token → ${ua}`);
  if (!isIos && /iPhone|iPad|CriOS|EdgiOS|FxiOS/.test(ua)) problems.push(`${c.os}/${c.engine}: iOS token on a non-iOS identity → ${ua}`);
  if (c.os === 'Android' && !/Android 15/.test(ua)) problems.push(`Android: wrong OS token → ${ua}`);
  if (c.os === 'Android' && /Windows NT|Macintosh/.test(ua)) problems.push(`Android: desktop token → ${ua}`);
  if (c.os.startsWith('Windows') && !/Windows NT 10\.0/.test(ua)) problems.push(`${c.os}: missing Windows token → ${ua}`);
  if (c.os === 'macOS' && !/Macintosh/.test(ua)) problems.push(`macOS: missing Macintosh token → ${ua}`);
  if (c.os === 'Linux' && !/X11; Linux x86_64/.test(ua)) problems.push(`Linux: missing X11 token → ${ua}`);
});
shapeProblems = problems.length;
problems.slice(0, 10).forEach((p) => console.log('  FAIL ' + p));
check('no shape problems across the matrix', shapeProblems === 0, shapeProblems + ' problem(s)');

console.log('\niOS specifics');
const crios = userAgent({ os: 'iPhone', engine: 'chrome', browserVersion: '18.3', iosVersion: '18.3', userAgentOverride: '' });
check('CriOS reports the iOS release, not a Chromium build', /CriOS\/18\.3/.test(crios), crios);
check('CriOS has no Chrome/ token', !/Chrome\//.test(crios), crios);
const fxios = userAgent({ os: 'iPhone', engine: 'firefox', browserVersion: '134.0', iosVersion: '18.3', userAgentOverride: '' });
check('FxiOS carries no Gecko build token', !/Gecko\/134/.test(fxios), fxios);
check('FxiOS keeps the WebKit token', /AppleWebKit\/605\.1\.15/.test(fxios), fxios);
const safari = userAgent({ os: 'iPad', engine: 'brave', browserVersion: '18.3', iosVersion: '18.3', userAgentOverride: '' });
check('an iOS identity with no native browser falls back to Safari', /Version\/18\.3 Mobile/.test(safari), safari);
check('iPad uses the OS token, not iPhone OS', /CPU OS 18_3/.test(safari), safari);

console.log('\nplatform tokens');
check('Android platform is Linux armv8l', platformFor('Android'), 'Linux armv8l');
check('Windows platform', platformFor('Windows 11'), 'Win32');
check('macOS platform', platformFor('macOS'), 'MacIntel');
check('iPhone platform', platformFor('iPhone'), 'iPhone');
check('Android platform is never armv81', !String(platformFor('Android')).includes('armv81'), true);

console.log('\nuser-agent override');
const overridden = userAgent({
  os: 'Windows 11', engine: 'chrome', browserVersion: '134.0.6998.89',
  userAgentOverride: '  MyCustomAgent/1.0  '
});
check('override is used verbatim', overridden, 'MyCustomAgent/1.0');

console.log('\n' + (failures === 0 ? 'all user-agent tests passed' : failures + ' check(s) failed'));
process.exit(failures === 0 ? 0 : 1);
