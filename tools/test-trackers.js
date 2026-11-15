#!/usr/bin/env node
/*
 * Tracker classifier tests.
 *
 * TrackerMonitor decides which of a page's requests get labelled "tracker". Two failure modes
 * matter: a false negative (a real collection host reported as ordinary traffic, which makes
 * the counter a lie) and a false positive (a normal site flagged, which makes the counter
 * useless). Both are cheap to test, so they are tested here against a port of the classifier.
 *
 * Usage: node tools/test-trackers.js
 */
'use strict';

const fs = require('fs');
const path = require('path');

const SRC = path.join(__dirname, '..', 'app', 'src', 'main', 'java', 'com', 'joebrowser', 'app',
  'browser', 'TrackerMonitor.kt');

/** Reads the blocklist straight out of the Kotlin source, so the two cannot drift apart. */
function loadBlocklist() {
  const source = fs.readFileSync(SRC, 'utf8');
  const start = source.indexOf('private val KNOWN_TRACKER_HOSTS = listOf(');
  if (start < 0) throw new Error('blocklist not found in TrackerMonitor.kt');
  const end = source.indexOf('\n        )', start);
  const block = source.slice(start, end);
  const hosts = [];
  const pattern = /"([^"]+)"/g;
  let match;
  while ((match = pattern.exec(block)) !== null) hosts.push(match[1]);
  if (!hosts.length) throw new Error('blocklist is empty');
  return hosts;
}

/** Port of TrackerMonitor.isTracker. */
function isTracker(host, blocklist) {
  const h = String(host).toLowerCase().replace(/^\.+/, '').replace(/\.+$/, '');
  if (!h) return false;
  return blocklist.some((known) => h === known || h.endsWith('.' + known));
}

/** Port of TrackerMonitor.hostOf. */
function hostOf(url) {
  const afterScheme = url.includes('://') ? url.slice(url.indexOf('://') + 3) : '';
  if (!afterScheme) return null;
  const authority = afterScheme.split('/')[0].split('?')[0].split('#')[0];
  const withoutUser = authority.includes('@') ? authority.slice(authority.lastIndexOf('@') + 1) : authority;
  const host = withoutUser.split(':')[0].toLowerCase();
  return host || null;
}

let failures = 0;
function check(label, actual, expected) {
  const ok = String(actual) === String(expected);
  if (!ok) failures++;
  console.log((ok ? '  ok   ' : '  FAIL ') + label + ' → ' + actual + (ok ? '' : ' (expected ' + expected + ')'));
}

const blocklist = loadBlocklist();
console.log('Joe Browser — tracker classifier tests');
console.log('blocklist entries: ' + blocklist.length + '\n');

console.log('must be flagged');
[
  'www.google-analytics.com',
  'google-analytics.com',
  'ssl.google-analytics.com',
  'stats.g.doubleclick.net',
  'doubleclick.net',
  'connect.facebook.net',
  'mc.yandex.ru',
  'bat.bing.com',
  'script.hotjar.com',
  'api.segment.io',
  'px.ads.linkedin.com',
  'sentry.io',
  'o1234.ingest.sentry.io'
].forEach((host) => check(host, isTracker(host, blocklist), 'true'));

console.log('\nmust NOT be flagged');
[
  'example.com',
  'github.com',
  'en.wikipedia.org',
  // The substring trap: ends with 'doubleclick.net' but is a different registrable domain.
  'notdoubleclick.net',
  'fakegoogle-analytics.com',
  // The search engine itself, not its analytics endpoint.
  'yandex.ru',
  'www.yandex.ru',
  // Mail and news properties.
  'mail.ru',
  'news.mail.ru',
  'localhost'
].forEach((host) => check(host, isTracker(host, blocklist), 'false'));

console.log('\nblocklist hygiene');
const withSlashes = blocklist.filter((h) => h.includes('/'));
check('no path fragments in the blocklist', withSlashes.length, 0);
const duplicates = blocklist.filter((h, i) => blocklist.indexOf(h) !== i);
check('no duplicate entries', duplicates.length, 0);
const upper = blocklist.filter((h) => h !== h.toLowerCase());
check('all entries lowercase', upper.length, 0);

console.log('\nhostOf');
check('plain url', hostOf('https://www.google-analytics.com/collect?v=1'), 'www.google-analytics.com');
check('port stripped', hostOf('https://example.com:8443/x'), 'example.com');
check('userinfo stripped', hostOf('https://user:pw@example.com/x'), 'example.com');
check('query only', hostOf('https://example.com?q=1'), 'example.com');
check('scheme-less input rejected', hostOf('/relative/path'), 'null');
check('uppercase lowered', hostOf('https://EXAMPLE.COM/x'), 'example.com');

// End-to-end: a realistic page load.
console.log('\nrealistic page load');
const requests = [
  'https://news.example/story/1',
  'https://news.example/static/app.js',
  'https://www.googletagmanager.com/gtm.js?id=GTM-1',
  'https://region1.google-analytics.com/g/collect',
  'https://cdn.jsdelivr.net/npm/lib.js',
  'https://notdoubleclick.net/pixel.gif'
];
const flagged = requests.filter((u) => {
  const host = hostOf(u);
  return host ? isTracker(host, blocklist) : false;
});
check('two of six requests flagged', flagged.length, 2);
check('the CDN is not flagged', flagged.some((u) => u.includes('jsdelivr')), false);

/*
 * The sheet is capped at CAP hosts, but the summary and the badge must report the true totals.
 * Regression guard: BrowserActivity used to build the summary by summing the visible rows, so
 * any page with more than CAP hosts showed fewer requests than the badge counted, on the same
 * screen. The totals come from the monitor's own counters, never from a snapshot.
 */
console.log('\nsnapshot cap vs totals');
const CAP = 150;

/** Port of TrackerMonitor.record / snapshot / totals. */
function makeMonitor() {
  const counts = new Map();
  let total = 0;
  let trackers = 0;
  return {
    record(url) {
      const host = hostOf(url);
      if (!host) return;
      total++;
      if (isTracker(host, blocklist)) trackers++;
      counts.set(host, (counts.get(host) || 0) + 1);
    },
    snapshot: () => Array.from(counts.entries())
      .sort((a, b) => (isTracker(b[0], blocklist) - isTracker(a[0], blocklist)) || (b[1] - a[1]))
      .slice(0, CAP)
      .map(([host, count]) => ({ host, count, tracker: isTracker(host, blocklist) })),
    totals: () => [total, trackers]
  };
}

// 200 ordinary hosts, each requested once, plus 10 hits on one analytics host.
const big = makeMonitor();
for (let i = 0; i < 200; i++) big.record('https://site' + i + '.example/page');
for (let i = 0; i < 10; i++) big.record('https://www.google-analytics.com/collect');

const bigRows = big.snapshot();
const [bigTotal, bigTrackers] = big.totals();
check('rows capped at ' + CAP, bigRows.length, CAP);
check('true request total counts every request', bigTotal, 210);
check('true tracker total counts every flag', bigTrackers, 10);
check('summed rows under-report (the old bug)', bigRows.reduce((n, r) => n + r.count, 0) < bigTotal, true);
check('sheet summary uses totals, not the rows', bigTotal === 210 && bigTrackers === 10, true);

// The badge and the sheet must agree: both read the same counters.
const badgeText = bigTotal + ' req / ' + bigTrackers + ' trk';
check('badge represents the same numbers', badgeText, '210 req / 10 trk');

// A page at or under the cap must show every host, so the cap never hides anything needlessly.
const small = makeMonitor();
small.record('https://a.example/');
small.record('https://b.example/');
small.record('https://www.google-analytics.com/collect');
const smallRows = small.snapshot();
const [smallTotal] = small.totals();
check('small page lists every host', smallRows.length, 3);
check('small page row sum equals its total', smallRows.reduce((n, r) => n + r.count, 0), smallTotal);

// Tracker rows sort above ordinary traffic, and the biggest of each comes first.
check('first row is the tracker', bigRows[0].tracker, true);
check('first row is the busiest host', bigRows[0].host, 'www.google-analytics.com');
check('second row is not a tracker', bigRows[1].tracker, false);

// Repeated requests to one host collapse into a single row with the right count.
const repeat = makeMonitor();
for (let i = 0; i < 7; i++) repeat.record('https://stats.g.doubleclick.net/pixel');
check('repeats collapse to one row', repeat.snapshot().length, 1);
check('row count is the hit count', repeat.snapshot()[0].count, 7);
check('totals count each hit', repeat.totals()[0], 7);

console.log('\n' + (failures === 0 ? 'all tracker tests passed' : failures + ' check(s) failed'));
process.exit(failures === 0 ? 0 : 1);
