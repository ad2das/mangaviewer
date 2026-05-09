# Release Automation

GitHub Actions uses a two-step release flow:

1. `Build APK Artifact` manually builds the debug APK and uploads `mangaviewer-debug-apk`.
2. `Release APK` runs on pushes to `main` and promotes the newest successful artifact by uploading it to the existing Latest release and committing `version.json` plus `releases.html`.

The fast release job does not start Gradle. It downloads the already-built artifact and should stay much closer to the 30-second target on GitHub-hosted runners.

`Build APK Artifact` defaults to a read-only Gradle cache so the manual build skips the slower cache-save post step. Turn on `refresh_gradle_cache` only when dependencies or Gradle inputs changed and the cache needs to be warmed again.

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
