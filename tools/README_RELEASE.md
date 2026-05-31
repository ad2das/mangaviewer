# Release Automation

GitHub Actions uses this release flow:

1. `Release APK` runs on pushes to `main`, builds the debug APK, then uploads the APK plus `version.json` to `main-latest`.
   The `main` branch reuses the `main-latest` release tag so only the newest main APK is kept there.
   Classification SQLite release assets are published by `Update classification DB`, not by the APK release path.
2. `Build APK Artifact` remains available for manual artifact-only builds on other branches.

`Build APK Artifact` and `Release APK` default to a read-only Gradle cache so builds skip the slower cache-save post step. The workflows exclude Gradle's local task-output cache because it is large enough to make cache restore slower than the work it saves. Turn on `refresh_gradle_cache` only when dependencies or Gradle inputs changed and the cache needs to be warmed again.

`Experimental Bazel APK` is a manual-only proof of concept for testing whether a Bazel Android build can beat the hosted Gradle path. It uploads `mangaviewer-bazel-debug-apk` and is not connected to the release promotion workflow.

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
