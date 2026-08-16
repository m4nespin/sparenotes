# Releasing SpareNotes

Back up `.release-signing` securely before publishing the first release. Losing the signing key prevents future in-place updates.

## One-time GitHub setup

Add these repository Actions secrets under **Settings → Secrets and variables → Actions**:

- `RELEASE_KEYSTORE_BASE64`: Base64 content of `.release-signing/sparenotes-release.p12`
- `RELEASE_KEYSTORE_PASSWORD`: `storePassword` from `.release-signing/keystore.properties`

Copy and save the keystore value first:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes(".release-signing/sparenotes-release.p12")) | Set-Clipboard
```

Then copy and save the password value:

```powershell
$signing = ConvertFrom-StringData (Get-Content -Raw ".release-signing/keystore.properties")
$signing.storePassword | Set-Clipboard
```

Clear the clipboard after saving both secrets:

```powershell
Set-Clipboard -Value ""
```

## Publish a release

1. Increment `versionCode` and set `versionName` in `app/build.gradle.kts`.
2. Commit and push the change to `main`.
3. Wait for CI to pass.
4. Create and push a matching annotated tag:

   ```powershell
   git tag -a v0.2.0 -m "SpareNotes 0.2.0"
   git push origin v0.2.0
   ```

The release workflow rejects a tag that does not equal `v` plus `versionName`. A valid tag builds and verifies the signed APK, generates its SHA-256 checksum, and publishes both files to GitHub Releases.
