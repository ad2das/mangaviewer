# Library source chip mismatch — 2026-09-18

## Symptom (Kovak)

Opening a work from 보관함 (library) changed the top-right source chip to that work's
provider, while the home/인기 tab lists still showed the previously selected provider.
The chip and the catalog below it disagreed.

## Root cause

Opening a saved series ran `switchSourceForSeries()` → `selectSource()`, which starts
`LibraryCatalogLoader.loadHome()` for the new provider. The very next call,
`episodes()`, runs `catalogs.cancelHome()` before the reload can publish anything:
`loadHome()` first suspends on `HomeCatalogSnapshotStore.load()`, so the cancellation
left the previous provider's `HomeContent.Ready` in state under the new chip. On return,
`resumeDiscovery()` only reloads when home is not `Ready`, so the stale list stayed.

`LibraryDetailLoader.open()` also reassigned `selectedSourceId` for offline-only opens,
the same implicit switch without a reload.

## Fix

- `LibraryViewModel`: opening a saved/offline series no longer switches the global
  catalog source. The chip stays the source the user chose; the series opens with its
  own provider. `switchSourceForSeries()` removed.
- `LibraryDetailLoader`: offline-only opens no longer reassign `selectedSourceId`.
- `LibraryCatalogLoader`: tracks which `(source, kind)` the published home belongs to
  (`shownSource`/`shownKind`). `loadHome()` clears to `Loading` synchronously whenever
  the displayed home is not for the requested source/kind, so a cancelled reload can
  never strand another provider's cards under the current chip. A same-source refresh
  keeps the loaded home visible if it is cancelled.

## Verification

- `:app:testDebugUnitTest` — 8/8 `LibraryCatalogLoaderRegressionTest` pass, including
  new `cancelledSourceSwitchLeavesNoStaleHomeFromThePreviousProvider` and
  `refreshingTheSameSourceKeepsTheLoadedHomeVisibleWhenCancelled`.
- `verifyArchitectureQuality` — gate passed (409 files).
- `:app:assembleDebug` — BUILD SUCCESSFUL.
- Device (emulator-5558, MangaViewerApi35): chip switched to NTK via the picker, opened
  the 뉴엑스툰 saved work 외모지상주의 from 보관함, backed out → chip still NTK; home tab
  loaded NTK content. Previously the chip would have flipped to 뉴엑스툰.
