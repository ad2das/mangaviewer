# Release Automation

GitHub Actions uses this release flow:

1. `Release APK` runs on pushes to `main`, builds the debug APK, then uploads the APK plus `version.json` to `main-latest`.
   The `main` branch reuses the `main-latest` release tag so only the newest main APK is kept there.
   Classification SQLite release assets are published by `Update classification DB`, not by the APK release path.
   Each release uses one greater than the larger of the Gradle baseline and the newest published APK version.
   Publishing is serialized so concurrent Actions runs cannot allocate the same version. The cached APK's manifest
   is patched before signing; rebuilding an unchanged `versionName` still produces an Android-installable upgrade.
   `version.json` describes the signed APK's version, download URL, SHA-256 and size. The app checks this release
   asset, with the release API as fallback; the old branch `version.json` is not an update source.
2. `Build APK Artifact` remains available for manual artifact-only builds on other branches.

`Build APK Artifact` and `Release APK` default to a read-only Gradle cache so builds skip the slower cache-save post step. The workflows exclude Gradle's local task-output cache because it is large enough to make cache restore slower than the work it saves. Turn on `refresh_gradle_cache` only when dependencies or Gradle inputs changed and the cache needs to be warmed again.

The app's account sheet checks and downloads updates inside the app. A silent check runs after ten seconds
with the library Activity resumed and is cancelled when leaving it. It prompts only when a newer release exists.
After the user accepts the download, the verified APK opens Android's installer once the library Activity resumes.
Downloaded APKs must match the advertised size/hash (when present), application ID, version and installed
signing certificate. Android's install permission and package installer remain the final installation steps.
`AppUpdateRepositoryTest` accepts an `updateFixture` instrumentation argument pointing to a locally signed
APK with the installed app's version plus one; that fixture check is skipped if the argument is absent.


Manual local full release is still available from the repository root:

```powershell
.\tools\release_latest.ps1
```

Promote an already downloaded artifact directory without committing or uploading:

```powershell
.\tools\promote_release_artifact.ps1 -ArtifactDir .\release-artifact -NoCommit -NoUpload
```

Promote the latest successful GitHub artifact manually:

```powershell
.\tools\promote_release_artifact.ps1
```
