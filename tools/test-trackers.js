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

console.log('\n' + (failures === 0 ? 'all tracker tests passed' : failures + ' check(s) failed'));
process.exit(failures === 0 ? 0 : 1);
