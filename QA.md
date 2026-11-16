# QA — Joe Browser 1.5.1

What was checked, how, and what still cannot be checked without a device. Every claim below is
reproducible by running the listed command or by reading the listed file.

## Automated gates (run on every push, and locally)

| Gate | Command | Result at 1.5.1 |
|---|---|---|
| Static audit — resources, bindings, menus, 48dp targets, single-colour policy, palette parity, dead copy | `node tools/verify-resources.js` | pass — 639 resources / 14 types, 32 layouts, 38 Kotlin files, 0 unused strings |
| Fingerprint layer executed against a Blink-shaped mock | `node tools/verify-fingerprint.js` | pass — 94 assertions |
| Proxy relay over real loopback sockets | `node tools/test-relay.js` | pass — 20 assertions |
| Tracker classifier + capped rows vs true totals | `node tools/test-trackers.js` | pass — 48 assertions |
| Human-typing engine against a DOM mock | `node tools/test-human-paste.js` | pass — 25 assertions |
| User-agent matrix | `node tools/test-user-agent.js` | pass — 98 identities, 13 assertions |
| Generated assets match their source | `node tools/generate-assets.js --check` | pass — 69 icons, launcher, splash, store art |
| Copy budget vs the 1.4.0 baseline | `node tools/copy-report.js` | see below |
| Compile (debug + release, R8 + resource shrink) | GitHub Actions → *Build* | pass |

### Copy budget

```
baseline (1.4.0):  4374 chars, 332 keys
  still present:   3906 chars, 302 keys   (89% of baseline)
  removed:          363 chars,  30 keys
  rewritten:        105 chars saved on surviving keys
new copy:           466 chars for new features · 302 chars for redesign labels
```

Honest reading: copy for the *existing* screens is 11% smaller by character count, and 30 whole
labels are gone. It is **not** 50% smaller, because 1.5.0 added three screens that did not exist
(bulk create, fingerprint lab, batch audit) plus a change-password flow. Where the requirement is
fully met is the layout: duplicate labels were merged (`Not checked` existed four times, now once),
four editor section headers were removed in favour of grouping, the dashboard lost its overline and
its empty-alerts card, and every section that can be empty now collapses instead of printing a
sentence about being empty.

## Manual paths exercised by reading the code end to end

Each item was traced through the Kotlin on this branch; the file is listed so the claim is checkable.

| Path | File | Notes |
|---|---|---|
| Cold start → account gate → app | `ui/MainActivity.kt`, `auth/LocalAccount.kt` | Gate runs before `setContentView`; deep-link extras and data survive the hand-off. |
| Register (email + password, no verification) | `ui/AccountActivity.kt` | PBKDF2-HmacSHA256, 120k iterations, per-field errors, no network call anywhere in the flow. |
| Sign in | same | Email compared in constant time alongside the hash. |
| Change password | same, `LocalAccount.changePassword` | Requires the current password; new salt, same email, data untouched. |
| Reset account | same | Erases only the account record. |
| Create profile (single) | `ui/ProfileEditorActivity.kt` | Six one-tap identity templates; fingerprint de-duplicated against every existing signature. |
| Create profiles (bulk) | `ui/ProfilesFragment.kt`, `data/ProfileRepository.createBatch` | 1–50 profiles, numbered names, shared proxy, per-profile unique fingerprint. |
| Test one fingerprint | `ui/FingerprintLabActivity.kt` | On-device coherence score + issues, then eight detectors opened **inside that profile's session**. |
| Test every fingerprint | `ui/FingerprintBatchActivity.kt` | Worst-first list, duplicate-fingerprint banner, row → lab. |
| Open a profile, navigate, back | `browser/BrowserActivity.kt` | Tabs, find-in-page, desktop mode, menu, download, file chooser. |
| Downloads | same | API 26–28 requests storage once and continues; API 29+ uses scoped storage. Proxied profiles warn that downloads go direct. |
| Proxy add / check / watch | `ui/ProxiesFragment.kt`, `proxy/*` | Paste import, live exit-IP check, watchdog with offline/recovered/rotated alerts. |
| Backups | `ui/ToolsFragment.kt` | Export with or without passwords, restore reports skipped rows. |
| Theme switch | `data/Prefs.kt`, `JoeBrowserApp.kt` | Applied before the first frame, labels from resources. |

## Fixed in this cycle (the "why does it do that?" list)

- **Windows blocks / deletes the download** — the published release was `app-release-unsigned.apk`:
  unsigned, generically named, zero reputation. Now: signed with v1+v2+v3 when keystore secrets
  exist, deterministic `joe-browser-<version>-<variant>.apk` names, `SHA256SUMS.txt` on every
  release, and `INSTALL.md` explaining the warning; the workflow warns loudly when it cannot sign.
- **Registration was hard to find** — the account screen is a segmented *Sign in / Create account*
  control with the create side selected on first run.
- **Downloads silently did nothing on Android 8/9** — `WRITE_EXTERNAL_STORAGE` was never declared
  or requested. Fixed with an `maxSdkVersion="28"` declaration and a runtime request.
- **Forgot password only offered destruction** — now offers change-password first.
- **Account field errors left stale red outlines** — cleared on the first keystroke.
- **Tab counter was a 34dp touch target** — now a 30dp pill inside a 48dp frame; the audit rejects
  any clickable under 48dp.
- **Avatar colours were a rainbow** — now five tones of the brand green, all AA-contrast with white.
- **Engine cards repeated a "New" button and printed "—" for unused engines** — removed.
- **The store feature graphic had artwork clipped by the frame edge** — the generator now refuses
  art that leaves the canvas or overlaps the headline.

## What is still not verified

- **No instrumented run.** There is no Android device or emulator in this environment, so first-run
  behaviour (WebView `MULTI_PROFILE` bucket binding, document-start injection, relay CONNECT,
  downloads, file chooser, fullscreen video) is implemented and compile-checked but not executed.
- **Live detector verdicts** (BrowserScan, IPHey, PixelScan, CreepJS, Whoer, Sannysoft) depend on the
  proxy's exit IP and on services that change weekly. The in-app lab is the way to check them per
  profile; CI cannot.
- **Release publishing** (the `release` job) only runs on a `v*` tag and has not been exercised yet.
- **TLS/JA3 and HTTP/2 fingerprints** belong to the installed Android System WebView, not the app.

File issues with `adb logcat` output; the app keeps the last uncaught stack trace in a private
`crash-last.txt`.
