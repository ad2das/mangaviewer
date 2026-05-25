# MangaViewer Refactor Checklist

This checklist follows the GPT-5.5 Pro review. Do not ask for the next Oracle review until every checked gate below is green or explicitly documented as blocked.

## Non-Negotiable Strategy

- Parser/cache can move early, but only behind fixture-backed tests.
- Viewer/reader can only be extracted behind stable metrics and behavior tests.
- Keep `ViewerActivity`, `StripAdapter`, `ViewerWarmupManager`, `ReaderSession`, `ReaderSurfaceView`, `CustomHttpClient`, `Manga`, `Title`, and `Preference` behavior-compatible until parity is proven.
- Do not remove the legacy viewer until `ReaderV2` satisfies parity for resume, offline open, exact episode open, autoCut, boundary append, captcha retry, and first-frame latency.

## PR 1: Baseline And Cleanup

- [x] Ignore local emulator/performance/debug captures.
- [x] Add this checklist as the refactor gate.
- [x] Run `.\tools\refactor_gate.ps1 -UnitOnly`.
- [x] Run `.\gradlew :app:assembleDebug`.
- [x] Keep local emulator/performance/debug captures out of git.
- [x] Add `tools/refactor_gate.ps1` as a repeatable unit/build gate.

## PR 2: Characterization Tests

- [x] Parser/search policy tests cover NTK keyword paths, parallel fetch policy, limits, and filtering.
- [x] Episode ordering tests cover visible episode numbers and shifted source ordering.
- [x] Cache tests cover `EpisodeSnapshotCache` key compatibility, cold-start reuse, and compatible fallback.
- [x] Resume/progress tests cover exact open, bookmark overwrite guard, and existing resume resolver behavior.
- [x] Viewer policy tests cover boundary threshold, bottom detection, idle-only checks, progress save, preview promotion, and decoded cache admission.
- [x] Reader policy tests cover repository dependency inversion and existing warmup/prepared-store caps.

## PR 3: Contracts And Facades

- [x] Add source, progress, warmup, and HTTP document contracts.
- [x] Provide legacy adapters that delegate to current implementations.
- [x] Keep Activities behavior-compatible.

## PR 4-6: Data, HTTP, Parser Extraction

- [x] Extract page cache freshness/cold-start policy from `CustomHttpClient` without public behavior changes.
- [x] Extract NTK keyword search policy from `Search`.
- [x] Preserve existing cache key formats and stale-cache policy.

## PR 7-8: Episode And Home Extraction

- [x] Extract `EpisodeActivity` warmup policy without changing launch behavior.
- [x] Preserve cache-first render, network refresh, continue warmup, and visible prefetch behavior through tests.

## PR 9-11: Legacy Viewer Extraction

- [x] Extract viewer progress policy.
- [x] Extract viewer boundary policy.
- [x] Extract `StripAdapter` image/preload/cache-admission policy.
- [x] Confirm no regression in unit/build/connected default gates.
- [x] Keep larger controller/job extraction deferred behind these policy seams.

## PR 12-14: ReaderV2, Modules, Legacy Retirement

- [x] Invert `ReaderSession` episode fetch, image URL, and viewer-initial fetch access behind `ReaderImageRepository`.
- [x] Keep legacy viewer and ReaderV2 in place; no retirement attempted.
- [x] Keep module split deferred until package APIs are stable.

## Verification Gates

- Unit: `.\gradlew :app:testDebugUnitTest` - PASS.
- Build: `.\gradlew :app:assembleDebug` - PASS.
- Instrumented smoke: `.\gradlew :app:connectedDebugAndroidTest` - PASS.
- Refactor helper: `.\tools\refactor_gate.ps1 -UnitOnly` runs unit only; `.\tools\refactor_gate.ps1` runs unit+build; `.\tools\refactor_gate.ps1 -Connected` also runs connected tests.
- Live network smoke tests: opt-in with `-Pandroid.testInstrumentationRunnerArguments.runLiveNetworkTests=true`.
- Manual/emulator: Home cold start, Search NTK/WFWF, Episode cache/network refresh, legacy Viewer resume/exact/boundary, ReaderV2 launch/fling/boundary, Offline/Download queue, Account/Sync smoke after data changes.

## Performance Regression Budget

- p50 regression: <= 5%.
- p95 regression: <= 10-15%.
- Viewer first visible/bind: target zero regression.
- No new main-thread stall over 80 ms.
- No new bitmap OOM, visible loading increase during fling, or Glide lifecycle crash on Activity destroy.
