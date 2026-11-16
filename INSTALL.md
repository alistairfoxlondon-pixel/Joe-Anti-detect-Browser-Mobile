# Installing Joe Browser

If Windows or your antivirus blocks the APK, or the browser deletes the file the moment the
download finishes, nothing is wrong with your device — and nothing malicious is in the app.
Here is exactly what causes it and exactly how to install safely.

## Why the file gets blocked or deleted

An APK is an executable installer. Three things make an app like this look suspicious to a
scanner, none of which are malware:

1. **It is not code-signed with a certificate the OS has seen before.** Android requires a
   signature to install, but a self-signed developer certificate has no reputation. SmartScreen
   and Defender treat "signed, but nobody has ever run this" as *unknown*, and unknown
   executables are held or quarantined by default.
2. **It is not distributed through an app store.** Store-delivered apps arrive with a
   reputation that scanners have already computed. A sideloaded APK has zero downloads, so
   zero reputation, regardless of what it does.
3. **It requests sensitive-looking permissions** — a browser with proxy support asks for
   `INTERNET`, `FOREGROUND_SERVICE`, `POST_NOTIFICATIONS` and `RECEIVE_BOOT_COMPLETED`.
   That combination is normal for a browser and also normal for adware, so heuristics flag it
   on sight.

Prior releases made this worse in two concrete ways, and both are fixed:

- The release artefact was named `app-release-unsigned.apk`. An **unsigned** APK cannot be
  installed at all — no signature means no install, so the file fails verification and gets
  deleted by the browser or the scanner. The build now only publishes APKs that carry a
  signature, and names them `joe-browser-<version>-debug.apk` / `-release.apk`.
- Releases shipped without checksums. They are now published with a `SHA256SUMS.txt`, so the
  bytes you downloaded can be verified as the bytes we built.

## What you can verify yourself

Everything the app does is visible in this repository. Nothing is obfuscated in a way that
hides behaviour, and R8 is configured to keep the app's own classes readable.

| Check | How |
|---|---|
| No analytics / ad / crash SDKs | `app/build.gradle` dependency list — eight AndroidX/Material libraries and nothing else |
| No hidden network calls | `grep -rn "http" app/src/main/java` — the only hosts are your page loads, `api.ipify.org` and `ip-api.com` for IP/geo display |
| No background upload | The foreground service is `ProxyMonitorService`; it only performs the health checks you configure and posts local notifications |
| Backup is opt-in | `allowBackup=false`, `dataExtractionRules` excludes everything from cloud backup and device transfer |
| Exact file contents | Compare against `SHA256SUMS.txt` from the release (below) |

### Verify a downloaded APK

```bash
# Windows (PowerShell)
Get-FileHash .\joe-browser-1.5.0-debug.apk -Algorithm SHA256

# macOS / Linux
shasum -a 256 joe-browser-1.5.0-debug.apk
```

Compare the result with the matching line in `SHA256SUMS.txt` on the release page. If they
match, the file is exactly what CI produced.

### Check the signature

Any APK manager (or `apksigner verify --print-certs <file>`) prints the signing certificate.
Joe Browser APKs are signed either by the project's release key or — for the `-debug` artefact
— with the standard Android debug key. Either is fine; what matters is that a signature is
*present*.

## Install steps

### Android phone

1. Download the APK from the release page.
2. Open it. Android will offer to let your file manager install unknown apps — allow it
   **once** for that app, then the installer runs.
3. If Play Protect shows "Unsafe app blocked", tap **More details → Install anyway**. This is
   the expected prompt for any self-signed app; it is not a virus report.
4. Open Joe Browser and create the on-device account.

### Windows / WSL (sideloading to a phone over USB)

```powershell
# Allow the download to stay put if Defender quarantined it
Add-MpPreference -ExclusionPath "$env:USERPROFILE\Downloads\joe-browser-*.apk"

adb install -r .\joe-browser-1.5.0-debug.apk
```

`adb install` is the cleanest route because the file never passes through a browser download
handler. If you do not want a permanent exclusion, download with a different method first
(e.g. `gh release download`) and scan the checksum above instead.

### macOS

Gatekeeper will say the file is "from an unidentified developer" only for apps *launched* on
the Mac — an APK file itself just needs to be copied to the phone. If Safari removed it, check
Download settings or use `gh release download` in a terminal.

## If a scanner still refuses the file

That is a reputation problem, and reputation only improves with distribution. Two things help:

- **Sign with a stable key and never rotate it.** A key that changes between releases resets
  every reputation signal. See *Signing* in the README for the three CI secrets.
- **Report a false positive** with the SHA-256 from `SHA256SUMS.txt`:
  [Microsoft](https://www.microsoft.com/en-us/wdsi/filesubmission),
  [Google Safe Browsing](https://safebrowsing.google.com/safebrowsing/report_error/).
  Include the build log link from the GitHub Actions run so the reviewer can see the APK was
  produced by this open build pipeline and not repackaged.

## What Joe Browser will never do

- It will not ask for your Google account, your phone number, or your contacts.
- It will not install anything else, show ads, or phone home.
- It will not run with root, and it cannot read other apps' data.
- Its "fingerprint" features change what *web pages* see. They do not hide the app from your
  device's security software, and they are not a licence to break a site's terms.


---

## For maintainers: why this build is (or is not) signed

A release APK is only installable when it carries a signature, and only trustworthy to Windows
when that signature is stable across versions. The keystore that produces it is **not** in this
repository. If a release page offers `joe-browser-<version>-release.apk`, signing was configured;
if it only offers `-debug`, the release keystore was missing for that run — see
[`tools/make-keystore.md`](tools/make-keystore.md) for the one-time setup and the four CI secrets.
