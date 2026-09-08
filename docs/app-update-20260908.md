# App updates from GitHub Actions

The rewritten library's update action opened the GitHub releases webpage, while CI reused the
fixed Gradle versionCode. Rebuilding therefore neither notified users in the app nor produced a
higher Android package version.

## Result

- `Release APK` serializes publishing to `main-latest`. Each published build receives one greater
  than the maximum of the Gradle baseline and published APK versions. Rebuilding the same sources
  advances the code; the allocator tests cover 2147000000 → 2147000001 → 2147000002.
- CI patches the built APK manifest, aligns and signs it with the existing certificate, uploads the
  complete APK, then publishes version.json containing its version, URL, SHA-256 and size.
- After the library Activity remains resumed for ten seconds, the app checks the release asset
  automatically. A newer version opens an update prompt; the current version does not. Leaving
  the library cancels the automatic check. The account sheet also supports a manual check.
- Accepting the download validates the APK's size/hash, package name, higher version and signing
  certificate. It requests installation once when the library Activity is resumed. Android's
  unknown-app permission and installation confirmation remain the system installation steps.
- The release API is the fallback if the metadata asset cannot be read. Stale branch metadata
  is not used. Partial, cancelled and invalid downloads are not offered for installation.

## Validation

- Passed `verifyArchitectureQuality`, `:app:testDebugUnitTest`, debug/release APK builds,
  Android test APK build and `:app:lintRelease`.
- Passed five Python release allocator/metadata tests and Bash syntax validation.
- Passed seven Android instrumentation tests on the existing API 35 emulator, including live
  GitHub metadata checking inside the app, signed upgrade download and URI access, invalid and
  cancelled downloads, fallback metadata, automatic notification, and consuming an installation
  request exactly once. Download tests use an intercepted HTTP response containing a locally
  signed version-plus-one APK; they do not publish or install a remote release.
- The native package installer confirmation was not exercised by these tests. The user's library
  database was backed up and restored with verification; the emulator was not restarted.

Evidence: `.artifacts/account-restore-20260908/app-update-auto-final-checks.txt` and
`app-update-validation/instrumentation-auto.txt` under the same artifact directory.

Debug APK SHA-256: `2569f3e46501a01f877bc73af7f0bc9e91ab93cde55c9e263b4e32b1c722d9c4`.
Release APK SHA-256: `9ea138111555a807574c566da85a6a942b4c06a9897820001d76467def6e8762`.

The overall viewer performance goal remains incomplete. Publication of this checkpoint does
not constitute performance qualification; the current limitations remain recorded in
`viewer-scroll-20260908.md`.

## Published checkpoint

Commit `74659235d2b73f636a2fd9fa0410de2d3ff7a33c` was pushed at the user's request.
[Release run 34230337861](https://github.com/ad2das/mangaviewer/actions/runs/34230337861)
succeeded and published versionCode `2147000001`. The 42,638,529-byte APK has SHA-256
`e135a843aad95710bad905a8bbc6a5932024b7449ca67ac6e6d60d77a73ef993`; the downloaded
metadata, APK manifest, APK signature and existing installed certificate matched.

The existing version `2147000000` automatically detected this actual public release
and downloaded it through the app. The app's cached APK had the same SHA-256. Android's
installation-source permission screen opened with permission disabled; that setting
was left unchanged. Native installer confirmation and an installed upgrade were not
claimed. The original library database was restored and verified. Evidence is under
`published-746592` in the account restoration artifact directory.
