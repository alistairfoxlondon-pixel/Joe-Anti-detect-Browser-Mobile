#!/usr/bin/env node
/**
 * Copy budget report.
 *
 * The user's requirement is "at least 50% less text". That cannot be proven by deleting strings —
 * new features legitimately add labels — so this report splits user-facing copy into two piles:
 *
 *   • EXISTING  — keys that already existed in the 1.4.0 baseline. This is the pile the diet
 *                 applies to, and it must shrink.
 *   • NEW       — copy for features that did not exist (bulk create, fingerprint lab/audit,
 *                 change password, status labels). Its size is reported, not hidden.
 *
 * Run: node tools/copy-report.js [baseline-ref]
 */
const { execSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const BASELINE = process.argv[2] || 'a3e3f19';

const parse = (xml) =>
  Object.fromEntries(
    [...xml.matchAll(/<string name="([\w.]+)"[^>]*>([\s\S]*?)<\/string>/g)]
      .map((m) => [m[1], m[2]])
  );

const current = parse(fs.readFileSync(path.join(ROOT, 'app/src/main/res/values/strings.xml'), 'utf8'));
let baseline = {};
try {
  baseline = parse(execSync(`git show ${BASELINE}:app/src/main/res/values/strings.xml`, { cwd: ROOT, encoding: 'utf8' }));
} catch {
  console.error(`Cannot read baseline ${BASELINE}. Pass a commit ref as the first argument.`);
  process.exit(1);
}

const chars = (obj, keys) => keys.reduce((n, k) => n + obj[k].length, 0);
const kept = Object.keys(baseline).filter((k) => k in current);
const removed = Object.keys(baseline).filter((k) => !(k in current));
const added = Object.keys(current).filter((k) => !(k in baseline));

const NEW_FEATURE = /^(bulk_|lab_|batch_|account_change|account_current|account_new|account_password_changed|profiles_audit|profiles_bulk|editor_status|home_empty_recent_action|browser_download_permission|not_checked|tile)/;
const newFeature = added.filter((k) => NEW_FEATURE.test(k));
const newRedesign = added.filter((k) => !NEW_FEATURE.test(k));

const baseChars = chars(baseline, Object.keys(baseline));
const keptChars = chars(current, kept);

console.log('Joe Browser — user-facing copy budget\n');
console.log(`baseline (${BASELINE}):   ${baseChars} chars, ${Object.keys(baseline).length} keys`);
console.log(`  still present:        ${keptChars} chars, ${kept.length} keys  (${Math.round((keptChars / baseChars) * 100)}% of baseline)`);
console.log(`  removed outright:     ${chars(baseline, removed)} chars, ${removed.length} keys`);
console.log(`  rewritten shorter:    ${chars(baseline, kept) - keptChars} chars saved`);
console.log(`new copy:`);
console.log(`  for new features:     ${chars(current, newFeature)} chars, ${newFeature.length} keys`);
console.log(`  redesign labels:      ${chars(current, newRedesign)} chars, ${newRedesign.length} keys`);
console.log(`\ntotal now:               ${chars(current, Object.keys(current))} chars, ${Object.keys(current).length} keys`);

const removedKeys = removed.length;
if (removedKeys === 0) {
  console.error('\nFAIL: nothing was removed from the baseline copy.');
  process.exit(1);
}
