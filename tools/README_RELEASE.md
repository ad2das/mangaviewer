# Release Automation

GitHub Actions uses a two-step release flow:

1. `Build APK Artifact` builds the debug APK on pushes to `main` and uploads `mangaviewer-debug-apk`.
2. `Release APK` promotes the newest successful artifact by uploading it to the existing Latest release and committing `version.json` plus `releases.html`.

The fast release job does not start Gradle. It downloads the already-built artifact and should stay much closer to the 30-second target on GitHub-hosted runners.

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
