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
| **HTTP Tracker** | Native request counter + known-tracker-host heuristic; badge + per-session report, copyable as text. |
| **History & trash** | Per-profile history (capped), soft-delete "trash" for profiles with empty-trash in settings. |
| **Backups** | JSON export (full, or shared without credentials) via the system share sheet, e.g. into a password manager. Proxy passwords are AES-256-GCM encrypted at rest and decrypt only on this device. |

## Honest limitations (please read)

- **Android WebView is not Chromium with a pluggable profile engine.** Isolation relies on
  the WebView's `MULTI_PROFILE` support (current Android System WebView). On very old WebViews
  the app falls back to shared storage and says so on screen — it never pretends otherwise.
- **Sites can still correlate you** across profiles via IP address, account logins, behavioral
  signals, TLS fingerprint, and any signal the WebView genuinely leaks. Joe Browser reduces
  surface; it does not and cannot make you anonymous.
- **Firefox identities are UA-level only.** Rendering is always the WebView (Blink); Gecko
  internals are not emulated.
- **Extensions**: WebView cannot execute Chrome extensions. The Extensions tab is a library of
  store links you open in a profile, not an installer.
- **In-app purchases, sync, account restore**: none. This is a single-device, offline-first tool.
- **Fingerprint spoofing is a cat-and-mouse game.** The consistency engine avoids the obvious
  contradictions (desktop OS + mobile touch points, Direct3D renderer on Android, …) and the
  editor surfaces warnings, but detector services change constantly.
- **Proxy override is process-wide** in AndroidX WebKit. If two Joe Browser windows use different
  proxies at the same time, the most recent one wins for new requests; the app warns in-toast
  when that happens. One shared relay per profile is the safe way to use multiple proxies.
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

- ✅ **Compiles** — `assembleDebug` and `assembleRelease` (R8 + resource shrinking) pass in CI.
- ✅ **Resource linking** — all layouts, drawables, themes, and view-binding references verified.
- ✅ **Static audit** — binding ids, menu ids, `R.*` references, and manifest declarations cross-checked.
- ❌ **Not run on a device/emulator yet** — no instrumented UI test has been executed; first-run
  flows (WebView isolation path, proxy CONNECT path, tracker badge, downloads, file chooser) are
  implemented but untested at runtime. Please file issues with logs if something misbehaves.
- ❌ **Fingerprint coherence vs. live detector services** (BrowserScan, IPHey, PixelScan, …) —
  not validated end-to-end; the editor shows the consistency warnings it computes locally.

## Privacy

Joe Browser ships with `allowBackup=false`, `usesCleartextTraffic=false` and a
`data_extraction_rules` that excludes all app data from cloud backup/device transfer.
Proxy and profile credentials never leave the device except as authenticated proxy traffic.
The only network calls the app itself makes are: the page you open, the proxy health check
(configurable), the IP-echo/geo lookup for "this device" status and proxy checks
(api.ipify.org / ip-api.com), and the sites you explicitly open (BrowserScan, etc.).

## License

Source-available for your own profiles and devices. Third-party licenses: see the in-app
*Open source licenses* screen (Settings → … → Licenses) and `NOTICE` files where present.
