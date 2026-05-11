# Release Automation

GitHub Actions uses this release flow:

1. `Release APK` runs on pushes to `main` and `ntk01`, builds and uploads the debug APK to that branch's release tag, then commits `version.json` plus `releases.html` back to the same branch.
   The `main` branch reuses the `main-latest` release tag so only the newest main APK is kept there.
2. `Build APK Artifact` remains available for manual artifact-only builds on other branches.

`Build APK Artifact` defaults to a read-only Gradle cache so manual builds skip the slower cache-save post step. Turn on `refresh_gradle_cache` only when dependencies or Gradle inputs changed and the cache needs to be warmed again.

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
