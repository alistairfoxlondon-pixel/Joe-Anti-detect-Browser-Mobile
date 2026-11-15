# Joe Browser — Anti-detect Browser for Android

**Joe Browser** is a local-first Android browser that gives every profile its own
isolated identity — cookies, storage, proxy and fingerprint — so multiple accounts can
be managed from one device without cross-contamination.

No account. No cloud. No telemetry. Everything lives on the device.

## Get the app

- **GitHub Releases**: tagged releases attach the APKs (`app-debug.apk` is signed with the
  Android debug key and installable directly; `app-release-unsigned.apk` is the minified
  release build and is only installable after signing — see *Signing* below).
- **CI**: every push builds `debug` and `release` APKs (GitHub Actions → *Build* workflow →
  artifact `joe-browser-apks`).

## What it does

| Area | What's implemented |
|---|---|
| **Isolated profiles** | Per-profile in-memory jar/database partitioning via AndroidX WebKit (`MULTI_PROFILE`, one `ProfileStore` per profile). Falls back to shared WebView storage with an explicit user-facing warning when the installed WebView lacks support. |
| **Fingerprint engine** | Seeded, internally-consistent identities: OS, screen + DPR, WebGL vendor/renderer, cores, memory, touch points, timezone, locale/accept-language, browser build pin. Canvas/audio/hardware noise injected at document-start. |
| **Proxy support** | HTTP, HTTPS and SOCKS5 with username/password auth. WebView can't carry proxy credentials, so the app runs a loopback CONNECT/SOCKS5 relay (`LocalProxyRelay`) that injects them upstream — the same path the proxy tester uses, so what you test is what you get. |
| **Proxy library** | Paste-import (`host:port`, `scheme://user:pass@host:port`, `host:port:user:pass`, etc.), live checks (exit IP, latency, geo), watchlists with **offline / recovered / exit-IP-rotated** notifications (special-use foreground service, stops itself when nothing is watched). |
| **Paste as Human** | Types clipboard content with human timing (Relaxed/Natural/Fast/Instant) into any focused field — no extension needed. |
| **HTTP Tracker** | Native request counter + known-tracker-host heuristic; badge + per-session report sheet (a real `RecyclerView`, so a 3000-request page scrolls), copyable as text. |
| **Engines tab** | Catalogue of the five identity families (Chrome, Chromium, Brave, Edge, Firefox) with the pinned build list each profile can take, how many profiles already use it, and a one-tap "create profile with this engine" action. |
| **Tools tab** | Every real setting in one place: background monitor on/off + cadence, human-typing defaults and rhythm, default start page, history/tracker/selection switches, theme, trash, storage use, JSON backup (with or without passwords) and a full reset. |
| **History & trash** | Per-profile history (capped), soft-delete "trash" for profiles with empty-trash in the Tools tab. |
| **Backups** | JSON export (full, or shared without credentials) via the system share sheet, e.g. into a password manager. Proxy passwords are stored AES-256-GCM encrypted with a device-bound Keystore key and decrypt only on this device; the *full* export writes them in plain text inside the file, because a Keystore key cannot leave the device — use the no-passwords export if the file leaves your control. |

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
node tools/verify-fingerprint.js
```

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

Being straight about what was and wasn't tested:

- ✅ **Compiles** — `assembleDebug` and `assembleRelease` (R8 + resource shrinking) pass in CI on
  every push; the workflow is the gate, not a local run.
- ✅ **Resource linking** — every layout, drawable, string, dimen and theme referenced from XML or
  `R.*` resolves (the build fails otherwise).
- ✅ **Static audit** — view-binding ids cross-checked against their layouts, internal imports and
  cross-class member calls verified, no `TODO`/stub paths left in `app/src/main`.
- ✅ **Fingerprint layer executed, not just compiled** — `node tools/verify-fingerprint.js` runs in
  CI on every push: it extracts the real script from `FingerprintScript.kt`, injects it into a
  Blink-shaped mock (values as prototype getters, `webdriver` as an own property) and asserts
  the resulting `navigator`, `screen`, timezone and canvas output, plus that two profiles never
  collide.
- ❌ **Not run on a device/emulator yet** — no instrumented UI test has been executed. First-run
  flows (WebView `MULTI_PROFILE` bucket binding, document-start injection, proxy relay CONNECT,
  tracker sheet, downloads, file chooser, fullscreen video) are implemented but untested at
  runtime. Please file issues with logs if something misbehaves.
- ❌ **Fingerprint coherence vs. live detector services** (BrowserScan, IPHey, PixelScan, …) —
  not validated end-to-end; the editor shows the consistency warnings it computes locally and
  the in-browser *Fingerprint check* menu opens those services inside the profile.

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
