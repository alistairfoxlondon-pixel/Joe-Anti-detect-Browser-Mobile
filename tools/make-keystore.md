# Creating the release keystore

Joe Browser signs release builds only when keystore material is present. Without it the release
APK is **unsigned** — which is not installable at all on Android and is the single biggest reason a
download gets quarantined on Windows. Run this once and keep the file safe; every future release
must use the same key or Android will refuse to upgrade an installed copy.

## 1. Generate the keystore (needs a JDK, `keytool` ships with it)

```bash
keytool -genkeypair \
  -keystore joe-release.p12 \
  -storetype PKCS12 \
  -alias joe-browser \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=Joe Browser, OU=Release, O=Joe Browser, L=London, C=GB"
```

Pick a strong password when prompted. This file plus that password is the release identity:
- **commit neither.** `*.p12` and `keystore.properties` are git-ignored.
- losing it means you cannot ship an upgrade to anyone who installed a previous build.

## 2. Put it in GitHub Actions

```bash
base64 -w0 joe-release.p12     # macOS: base64 -i joe-release.p12 | tr -d '\n'
```

Then set four repository secrets (Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `JOE_KEYSTORE_BASE64` | the base64 output above |
| `JOE_KEYSTORE_PASSWORD` | the store password |
| `JOE_KEY_ALIAS` | `joe-browser` |
| `JOE_KEY_PASSWORD` | the key password (usually the same) |

The **Build** workflow decodes it into the runner, signs with v1 + v2 + v3, and the release job
publishes `joe-browser-<version>-release.apk` with a matching `SHA256SUMS.txt`.

## 3. Or build locally

Create `keystore.properties` next to `settings.gradle`:

```properties
storeFile=joe-release.p12
storePassword=…
keyAlias=joe-browser
keyPassword=…
```

```bash
./gradlew :app:assembleRelease
# app/build/outputs/apk/release/joe-browser-<version>-release.apk
apksigner verify --print-certs app/build/outputs/apk/release/joe-browser-*-release.apk
```

`apksigner verify` printing three scheme lines (`v1`, `v2`, `v3`) is the proof the build is
installable and that Windows scanners have a real signature to evaluate.
