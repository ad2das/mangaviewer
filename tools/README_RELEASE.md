# Release Automation

Run this from the repository root after code changes are ready:

```powershell
.\tools\release_latest.ps1
```

Normal releases are automated by GitHub Actions. After a code change lands on `main`, `.github/workflows/release-apk.yml` runs this script, bumps the APK version, updates `version.json`, commits the release metadata, pushes it back to `main`, and uploads the APK to the existing Latest release.

The script:

- increments `releasePatch` in `app/build.gradle`
- computes the new `versionCode`
- updates `version.json` and `releases.html`
- runs `assembleDebug`
- runs `testDebugUnitTest`
- copies the APK into `apk/`
- commits the release files
- pushes `main`
- uploads the APK to the existing Latest GitHub release

To keep only the newest APK asset in the release:

```powershell
.\tools\release_latest.ps1 -DeleteOldReleaseApks
```

Useful dry-run-ish mode for checking metadata/build without committing or uploading:

```powershell
.\tools\release_latest.ps1 -NoCommit -NoUpload
```
