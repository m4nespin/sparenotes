# TrailSafe

TrailSafe is a small, native Android app for automatic, one-way backup from selected Supernote Nomad folders to Proton Drive.

## What it does

- Lets you select exact internal-storage or microSD folders with Android's folder picker.
- Recursively includes every file below each selected folder.
- Runs only on Wi-Fi. Android schedules a run when Wi-Fi reconnects and once a day while it stays connected; **Back up now** starts a foreground backup immediately.
- Uploads new files and replaces changed cloud copies under `/my-files/TrailSafe`.
- Never deletes a local file or a Proton Drive file. Removing a source or deleting a local file leaves its existing cloud copy untouched.
- Stores a SHA-256 content fingerprint after each bounded upload batch, so interrupted backups resume and unchanged files are skipped.

Android jobs are intentionally inexact and may be deferred while the Nomad sleeps. Active backups use a foreground service and wake lock so large transfers can finish after the screen sleeps. Android can miss a Wi-Fi disconnect while TrailSafe's process is stopped; the daily job remains the fallback.

## Nomad setup

1. Enable **Settings → Security & Privacy → Sideloading**.
2. Install the TrailSafe APK.
3. Open TrailSafe and tap **Connect Proton Drive**.
4. Scan the one-time QR with a phone, sign in directly on Proton's page, and approve access.
5. Tap **Add folder**, choose `disk` for the microSD card, open the desired folder, then tap **Use this folder**. Repeat for more folders.

The official Proton Drive Android app is not required by TrailSafe.

## Security model

- TrailSafe never receives or stores the Proton password. Authentication happens on Proton's HTTPS page.
- It uses Proton's official Drive CLI/SDK for authentication, encryption, and uploads.
- The CLI session is encrypted at rest with an Android Keystore AES-256-GCM key. Plaintext exists only in TrailSafe's private app directory while a Proton command is active.
- Android backups are disabled and cleartext network traffic is blocked.
- A tiny compatibility wrapper converts syscalls rejected by Android's app sandbox to `ENOSYS`; it does not grant or bypass Android permissions.
- The bundled CLI has one source-level byte patch: its desktop-only `xdg-open` call returns immediately because TrailSafe itself renders the one-time URL and QR. See `tools/patch-proton-cli.ps1`.

## Build

Requirements: JDK 17+, Android SDK 35, and Android NDK 27 only when rebuilding the compatibility wrapper.

The first build downloads Proton Drive CLI 0.6.0 from Proton, verifies its official SHA-512 checksum, applies the Android browser-launch patch, and verifies the patched SHA-256 checksum. The 110 MB generated binary is intentionally excluded from Git.

```powershell
.\gradlew.bat lintDebug testDebugUnitTest assembleDebug
```

The debug APK is for development only. It is debuggable and must not be installed on a device holding Proton data.

For deployment, use **Android Studio → Build → Generate Signed Bundle / APK → APK → release**. Install only the resulting signed, non-debuggable release APK.

To rebuild the ARM64 compatibility wrapper with NDK 27:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\ndk\27.0.12077973\toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android30-clang.cmd" `
  -O2 -fPIE -pie app\src\main\cpp\compatwrap.c `
  -o app\src\main\jniLibs\arm64-v8a\libcompatwrap.so
```

## Bundled runtime

The app currently targets the Nomad's ARM64 Android 11 environment. It bundles Proton Drive CLI 0.6.0, a patched musl loader/runtime, GCC runtime libraries, Alpine's CA bundle, and ZXing QR support. Exact provenance and licenses are recorded in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
