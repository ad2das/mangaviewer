# Search result list fade consistency — 2026-09-18

## Change

`SearchScreen` result cards used `Modifier.animateItem()` with the default
fade-in/fade-out specs. The episode list in `SeriesDetailScreen` had already
been switched to `animateItem(fadeInSpec = null, fadeOutSpec = null)` so cards
do not build fade layers while the list scrolls. The search list now uses the
same spec; placement animation is kept, appearance fades are gone.

## Why

Search results are the second-longest scrolling list in the app. Default item
fades allocate an extra graphics layer per card entering the viewport, which is
exactly the cost the detail list dropped during the perf pass. This keeps the
two lists consistent.

## Verification

- `:app:testDebugUnitTest verifyArchitectureQuality :app:assembleDebug` —
  BUILD SUCCESSFUL (arch gate 410 files).
- Debug APK installed on emulator 5558; search tab opened and closed with no
  crash (`logcat -b crash` empty, app pid alive).
- Fade specs are not observable in a static screenshot; no layout change.
