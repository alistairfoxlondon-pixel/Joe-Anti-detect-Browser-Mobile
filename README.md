# Joe Browser — Anti-detect Browser for Android

**Joe Browser** is a local-first Android browser that gives every profile its own
isolated identity — cookies, storage, proxy and fingerprint — so multiple accounts can
be managed from one device without cross-contamination.

Local account only. No cloud. No telemetry. Everything lives on the device.

## New in 1.6.0

**Every engine now looks like the engine it claims to be.** Chrome, Chromium, Brave, Edge and
Firefox each get their own shell — a tab strip, an active-tab pill or a tab counter depending on
the product, a capsule or a field URL bar, an omnibox-only toolbar or a back/forward row, Brave's
shield inside the URL bar, Firefox's and Edge's home button, Chrome's share action. The engine is
expressed through geometry and affordances only: the app still has exactly one colour identity, so
there is no second brand palette competing with it.

**Canvas fingerprints are now internally consistent.** This is the fix behind the low BrowserScan
score. The previous layer perturbed every pixel of every canvas by ±2 in all three channels, and
re-encoded `toDataURL`/`toBlob` through a scratch canvas — so the three read surfaces could never
agree with each other, which is precisely what "canvas fingerprint inconsistency" measures. One
per-profile transform now lives in one place and is reused by all three: coordinate-seeded and
deterministic, confined to the anti-aliased alpha edge band (fully opaque and fully transparent
pixels are untouched), clamped to one unit in one channel. Repeat visits are identical, cross-API
checks pass, and text rendering is no longer smeared. `tools/verify-fingerprint.js` asserts all
three properties.

**Inter replaces Plus Jakarta Sans.** Five pinned static instances (400–800), subset to Latin +
Latin-Ext + punctuation (~1.2k glyphs each), behind one `@font/app_font` family. Pinned weights
rather than one variable file because Android's variable-font support is API 28+ and varies by OEM
type stack; the store art now reads its typeface straight from `res/font`, so artwork and UI can
never drift apart.

**Tighter geometry.** Corners came down a step across the whole system — hero 22→14, cards 18→12,
insets and tiles 12→8, chips 8→6, buttons 14→8 — while the rounded URL capsule and hero pill keep
their form. The app reads like a tool now, not a toy.

**Authentic brand marks.** The official Google Chrome, Brave, Mozilla Firefox and Huawei vector
marks ship in `brand_*`, reproduced in each owner's colour. The fingerprint services have no
redistributable mark, so their tiles carry the service's own wordmark initial on a plate in its
brand colour — recognisable, and never a lookalike.

**One source of truth for the test services.** `SiteBrand` declares each service's name, purpose,
canonical test URL and colour once; the home verify chips, the in-browser Fingerprint-check menu
and the lab grid all read from it, so they can no longer disagree about which page to open.

**Honest test history.** Opening a detector now records *when* that profile was taken there, and
the lab tile shows it ("Fingerprint report · tested 12 min ago"). The app never reproduces or
guesses a detector's verdict — the number on screen is always the service's own. The lab grid is
also one full-width row per service instead of eight identical glyphs.

**Home opens your profile.** The hero carries an Open pill beside the profile name, so the most
common task is one tap from launch.

**A neutral startup page.** New profiles opened to a detector's homepage, announcing the product to
that vendor before the user had done anything; they now open to a neutral page, with the detectors
one tap away in Home and the browser menu.

## New in 1.5.1

**Signed, correctly-named APKs — and an install guide.** 1.5.1 asked `apksigner` whether a build
output is actually signed and withholds the release APK when it is not; it also ships
`SHA256SUMS.txt` on every release. If Windows SmartScreen or Defender blocks or deletes the
download, read [`INSTALL.md`](INSTALL.md): it explains why an unknown, unsigned, generically-named
APK is heuristic-bad and shows how to verify the checksum before installing.

**New brand mark and icon set.** A shield-and-keyhole mark on deep green replaces the old logo;
the full icon set was redrawn on a single 24dp grid with one stroke weight, and the launcher
foreground/background layers were rebuilt at the correct safe-zone scale.

**Bulk profile creation.** Profiles → layers icon: name prefix, count (up to 50), engine, OS and
one shared proxy. Every profile in the batch is de-duplicated against all existing signatures, so
twenty profiles are twenty fingerprints, not twenty copies.

**Fingerprint Lab.** Profiles → ⋮ → *Test fingerprint* opens an on-device coherence score with the
exact warnings the editor uses, then one-tap detectors that open **inside the selected profile's
session**, so what you test is what that profile will show the world.

**1.5.1 fixed a release-pipeline bug in 1.5.0.** 1.5.0's release job renamed every build output and
then filtered on the old filename, so an *unsigned* `joe-browser-1.5.0-release.apk` was published and
described as signed.

## New in 1.4.0

**Local account gate** — first launch creates a device-local account (email + password,
PBKDF2-HmacSHA256 hash in private, unbacked-up storage). Sign in on every cold start;
**Tools → Lock app** re-locks instantly. The account is local-only — no server, no email
verification, no sync; resetting it erases only the account, never your profiles.

**One-tap identity templates** — new profiles can start from coherent presets
(US/UK/DE Windows + Chrome/Edge/Firefox, US iPhone Chrome, US/BD Android Chrome/Brave).
Engine, OS, GPU, screen, timezone and language are generated as one consistent identity.

**Proxy failover** — opt-in in Tools (*Auto-switch on proxy failure*): when a watched
profile proxy fails twice in a row, the profile is transparently re-pointed at the
healthiest watched library proxy and you get an alert. Never on the first blip, never
silently on another device.

**Engine catalog with real brand marks** — Chrome, Chromium, Brave, Edge and Firefox
cards now carry the official logos, single-line pinned builds, and a compact create
action. No paragraphs.

**Interface copy diet** — user-facing static copy cut by ~42% against 1.3.0 while
removing dead strings (41 unused keys swept, reference-checked).

## New in 1.3.0

**Premium emerald design** — the whole app was reworked around a deeper jade palette
(light and dark), roundier cards/inputs/sheets, a consistent Material icon set instead of
mixed line weights, and redrawn launcher/store assets. The browser chrome was rebuilt:
one compact identity row (avatar with an isolation-health dot + profile name + engine
caption, ellipsed), icon-only proxy and tracker actions, and a floating paste-progress
pill — eliminating the tiny overlapping chips and clipped labels.

**Fingerprint layer hardening** — the signals detector sites read first are now coherent:

- `navigator.webdriver` reads `false` (it never lies as `undefined`)
- UA-CH brand lists are derived from the pinned build with the rotating GREASE token
  (`Not?A_Brand;v=99` for Chrome 131+) including a `Chromium` entry and a correct
  `fullVersionList` (`99.0.0.0` / full build), per engine
- `plugins`/`mimeTypes` carry real `PluginArray`/`MimeTypeArray`/`Plugin`/`MimeType`
  `Symbol.toStringTag`s with cross-linked references, exactly as Blink exposes them
- `permissions.query({name:'notifications'})` agrees with `Notification.permission`
- `Intl.DateTimeFormat().resolvedOptions()` reports the profile locale **and** timezone
- the HTTP `Accept-Language` header is aligned with `navigator.languages` where the
  installed WebView exposes that switch
- the node verifier grew from 66 to **94 assertions** covering all of the above

**Trust & stability** — cleartext traffic is denied app-wide (loopback excepted) with an
explicit `network_security_config`, the last uncaught stack trace is persisted to a private `crash-last.txt` (no analytics, never
leaves the device), launcher long-press shortcuts (New profile / Profiles / Proxies),
and a batch of overflow/crash fixes across the UI.

## Get the app

- **GitHub Releases** (recommended): each release attaches
  `joe-browser-<version>-release.apk` (minified, signed), `joe-browser-<version>-debug.apk`
  (debug key), and `SHA256SUMS.txt`.
- **Verify before installing** — the download is a plain `.apk` from an unknown publisher, which
  is exactly the shape OS heuristics distrust. Compare the SHA-256:

  ```powershell
  Get-FileHash .\joe-browser-1.6.0-release.apk -Algorithm SHA256
  ```

  ```bash
  shasum -a 256 joe-browser-1.6.0-release.apk
  ```

  The digest must match the line for that file in `SHA256SUMS.txt`.
- **Why Windows warns** — SmartScreen/Defender flag unsigned or low-reputation binaries, and a
  generic `app-release-unsigned.apk` name scores badly on its own. 1.5.1 fixes all three causes:
  builds are signed, filenames are specific and versioned, and checksums are published. Full
  detail, false-positive reporting links and Android/WSL/macOS install steps: [`INSTALL.md`](INSTALL.md).
- **CI**: every push builds debug and release APKs (Actions → *Build* → artifact `joe-browser-apks`),
  and the workflow fails if either APK is unsigned.

## What it does

| Area | What's implemented |
|---|---|
| **Isolated profiles** | Per-profile in-memory jar/database partitioning via AndroidX WebKit (`MULTI_PROFILE`, one `ProfileStore` per profile). Falls back to shared WebView storage with an explicit user-facing warning when the installed WebView lacks support. |
| **Fingerprint engine** | Seeded, internally-consistent identities: OS, screen + DPR, WebGL vendor/renderer, cores, memory, touch points, timezone, locale/accept-language (and the matching HTTP header), browser build pin with rotating GREASE UA-CH brands, tagged `PluginArray`/`MimeTypeArray`, permission states coherent with `Notification.permission`. Canvas/audio/hardware noise injected at document-start. |
| **Proxy support** | HTTP, HTTPS and SOCKS5 with username/password auth. WebView can't carry proxy credentials, so the app runs a loopback CONNECT/SOCKS5 relay (`LocalProxyRelay`) that injects them upstream — the same path the proxy tester uses, so what you test is what you get. |
| **Proxy library** | Paste-import (`host:port`, `scheme://user:pass@host:port`, `host:port:user:pass`, etc.), live checks (exit IP, latency, geo), watchlists with **offline / recovered / exit-IP-rotated** notifications (special-use foreground service, stops itself when nothing is watched). |
| **Paste as Human** | Types clipboard content with human timing (Relaxed/Natural/Fast/Instant) into any focused field — no extension needed. |
| **Session wipe** | Optional per profile: cookies, site data and cache are erased when the session ends, so the next launch starts signed out. History is kept — the History screen has its own per-profile clear. |
| **HTTP Tracker** | Native request counter + known-tracker-host heuristic. The chrome badge shows the live `N req · M trk` total and opens the per-session report sheet (a real `RecyclerView`, so a 3000-request page scrolls). The sheet's summary uses the monitor's true totals, not the 150 rows it can display, and the copied text report says when the list was truncated. |
| **Live identity** | Editing a saved profile's identity refreshes the open tab on return: the kernel swaps the document-start script (the WebView has no replace, so the old rule is removed first) and reloads only when the rebuilt payload actually differs. |
| **Engines tab** | Catalogue of the five identity families (Chrome, Chromium, Brave, Edge, Firefox) with the pinned build list each profile can take, how many profiles already use it, and a one-tap "create profile with this engine" action. |
| **Tools tab** | Every real setting in one place: background monitor on/off + cadence, human-typing defaults and rhythm, default start page, history/tracker/selection switches, theme, trash, storage use, JSON backup (with or without passwords) and a full reset. |
| **History & trash** | Per-profile history (capped), soft-delete "trash" for profiles with empty-trash in the Tools tab. |
| **Backups** | JSON export (full, or shared without credentials) via the system share sheet, e.g. into a password manager, and a matching **restore** from the file picker (tolerant per item, reports skipped rows, restored profiles start with empty cookies). Proxy passwords are stored AES-256-GCM encrypted with a device-bound Keystore key and decrypt only on this device; the *full* export writes them in plain text inside the file, because a Keystore key cannot leave the device — use the no-passwords export if the file leaves your control. |

## Honest limitations (please read)

- **Android WebView is not Chromium with a pluggable profile engine.** Isolation relies on
  the WebView's `MULTI_PROFILE` support (current Android System WebView). On very old WebViews
  the app falls back to shared storage and says so on screen — it never pretends otherwise.
- **Sites can still correlate you** across profiles via IP address, account logins, behavioral
  signals, TLS fingerprint, and any signal the WebView genuinely leaks. Joe Browser reduces
  surface; it does not and cannot make you anonymous.
- **Firefox identities are UA-level only.** Rendering is always the WebView (Blink); Gecko
  internals are not emulated.
- **Extensions**: WebView cannot execute Chrome extensions, so the app does not ship an
  extensions installer or an extensions tab at all. Anything a page needs has to be injected by
  the fingerprint layer or the page itself.
- **In-app purchases, sync, account restore**: none. This is a single-device, offline-first tool.
- **Fingerprint spoofing is a cat-and-mouse game.** The consistency engine avoids the obvious
  contradictions (desktop OS + mobile touch points, Direct3D renderer on Android, …) and the
  editor surfaces warnings, but detector services change constantly.
- **Proxy override is process-wide** in AndroidX WebKit. Each browser window starts its own
  loopback relay (`LocalProxyRelay`) around its profile's proxy and installs a process-wide
  override that points at it; if two windows with *different* proxies are open at once, the most
  recently opened one wins for new requests in both. The badge in each window reflects the proxy
  it actually verified, so a mismatch is visible rather than silent. Keep one proxied profile
  open at a time for strict separation.
- **Root certificates / MITM**: `PROCEED ANYWAY` on the SSL dialog is for captive portals and
  broken corporate CAs, not for defeating TLS validation as a feature.

## Build locally

Requirements: JDK 17, Android SDK (platform 34, build-tools), network access to
Google Maven + Maven Central.

```bash
./gradlew :app:assembleDebug      # debug APK
./gradlew :app:assembleRelease    # minified release APK (unsigned without secrets)
```

`gradle/wrapper/gradle-wrapper.jar` is committed; the wrapper downloads Gradle 8.10.2.

The fingerprint layer can be checked without Android at all:

```bash
node tools/verify-fingerprint.js   # fingerprint layer + identity coherence
node tools/test-relay.js           # proxy relay over real sockets
node tools/test-trackers.js        # tracker blocklist, host parsing, capped rows vs true totals
node tools/test-human-paste.js     # typing engine against a DOM mock
node tools/test-user-agent.js      # 98-identity user-agent matrix
```

Both run in CI before Gradle, so a regression fails the build rather than the APK.

## Signing (for maintainers)

Release builds are signed only when keystore material is present:

| CI secret | Meaning |
|---|---|
| `JOE_KEYSTORE_BASE64` | base64 of a PKCS#12 keystore |
| `JOE_KEYSTORE_PASSWORD` | keystore password |
| `JOE_KEY_ALIAS` | key alias (default `joe-browser`) |
| `JOE_KEY_PASSWORD` | key password (defaults to the keystore password) |

Without these, `assembleRelease` produces `app-release-unsigned.apk` and the debug APK is
used for sideloading. Locally, place a `keystore.properties` (git-ignored) with
`storeFile/storePassword/keyAlias/keyPassword` next to `settings.gradle`.

## Verification status

Full detail, including a per-screen trace and the exact copy numbers, lives in [`QA.md`](QA.md).
The short version:

- ✅ **Compiles** — `assembleDebug` and `assembleRelease` (R8 + resource shrinking) pass in CI on
  every push; the workflow is the gate, not a local run.
- ✅ **Resource linking** — every layout, drawable, string, dimen and theme referenced from XML or
  `R.*` resolves (the build fails otherwise).
- ✅ **Fingerprint layer executed, not just compiled** — `node tools/verify-fingerprint.js` runs in
  CI on every push: it extracts the real script from `FingerprintScript.kt`, injects it into a
  Blink-shaped mock (values as prototype getters, `webdriver` as an own property) and asserts
  the resulting `navigator`, `screen`, timezone and canvas output, plus that two profiles never
  collide. 94 assertions, including the timezone-offset sign (Berlin is −60 in winter, −120 in
  summer), the `Intl` zone name and locale, the `webdriver=false` read, UA-CH brand derivation
  and fullVersionList for the pinned build, `PluginArray`/`MimeTypeArray` toStringTags and
  MimeType→Plugin back-links, `permissions.query` vs `Notification.permission` agreement, and
  geolocation permission modes (custom coordinates, the `0,0`/out-of-range rejection, and `block`).
- ✅ **Human-typing engine executed** — `node tools/test-human-paste.js` runs the real injected
  script against a DOM mock: caret insertion, selection replacement, keydown/input/keyup per
  character, real timers, the busy guard, `no-target`, and newline handling in inputs vs textareas.
- ✅ **User-agent matrix executed** — `node tools/test-user-agent.js` builds 98 identities
  (7 OSes × 5 engines × their builds) and checks for the tells that have actually shipped here:
  no desktop Chromium build on an iOS identity, no Gecko token in a WebKit UA, no iOS token on a
  Windows profile, and platform strings that match the UA (`Linux armv8l`, not the invented `armv81`).
- ✅ **Tracker classifier and counters executed** — `node tools/test-trackers.js` reads the
  blocklist out of `TrackerMonitor.kt` and checks both directions (12 tracker hosts flagged, 11
  non-trackers left alone, including the `notdoubleclick.net` lookalike). It also ports
  `record`/`snapshot`/`totals`, feeds 210 requests across 200 hosts through them, and asserts the
  row list stays capped at 150 while the summary and badge read the true totals — the bug that
  once made the sheet disagree with the badge on the same screen. 48 checks.
- ✅ **Proxy relay exercised end to end** — `node tools/test-relay.js` runs a faithful port of
  `LocalProxyRelay` against real loopback sockets: plain HTTP must reach the proxy in absolute
  form with credentials, CONNECT tunnels must deliver a 512 KB body byte-exact, bad credentials
  must fail with 502 (never a direct fallback), and host/port parsing must reject nonsense.
- ✅ **Static audit (re-run after every change)** — view-binding ids cross-checked against their
  layouts, internal imports and cross-class member calls verified, every menu item handled, and
  unreferenced members removed rather than left looking like an API. The five harnesses above
  are the executable part; anything they do not cover is listed honestly below.
- ❌ **Not run on a device/emulator yet** — no instrumented UI test has been executed. First-run
  flows (WebView `MULTI_PROFILE` bucket binding, document-start injection, proxy relay CONNECT,
  tracker sheet, downloads, file chooser, fullscreen video) are implemented but untested at
  runtime. Please file issues with logs if something misbehaves.
- ⚠️ **Downloads do not use the profile proxy.** Android's `DownloadManager` cannot be pointed at
  a proxy, so a file downloaded from a proxied profile is fetched over the device's own
  connection. The app says so at the moment you download. Page loads are unaffected.
- ❌ **Fingerprint coherence vs. live detector services** (IPHey, PixelScan, BrowserScan, Whoer, …) —
  not validated end-to-end in CI; there is no device in the build pipeline. What is true: every
  *locally controllable* signal those services read is now internally consistent (UA, UA-CH brands
  + fullVersionList, GREASE, plugins/mimeTypes, permissions, locale, timezone + offset, WebGL,
  canvas/audio noise, automation markers, `webdriver`). What is **not** under app control: the TLS
  (JA3/JA4) and HTTP/2 fingerprint — those belong to the installed Android System WebView and match
  real Chrome on Android, which is the best an app can ship; the exit proxy's IP reputation, which
  detector sites score separately from the browser fingerprint; and WebRTC host candidates, which
  require `webrtcMode=proxy` plus a reachable TURN relay to suppress. A proxy on a blacklisted exit
  node will cost points no matter how clean the browser is — check the proxy's exit with the same
  sites before blaming the fingerprint. Use the in-browser *Fingerprint check* menu (BrowserScan,
  IPHey, PixelScan, IPFighter, Whoer) to verify inside a real session.

## Privacy

Joe Browser ships with `allowBackup=false`, `usesCleartextTraffic=false` and a
`data_extraction_rules` that excludes all app data from cloud backup/device transfer.
Proxy and profile credentials never leave the device except as authenticated proxy traffic.
The only network calls the app itself makes are: the page you open, the proxy health check
(configurable), the IP-echo/geo lookup for "this device" status and proxy checks
(api.ipify.org / ip-api.com), and the sites you explicitly open (BrowserScan, etc.).

## License

Source-available for your own profiles and devices. Third-party licenses: see the in-app
*Open source licenses* screen (Tools tab → About → Open source licenses) and `NOTICE` files where present.
