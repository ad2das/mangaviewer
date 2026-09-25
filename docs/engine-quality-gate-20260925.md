# Engine quality gate status (2026-09-25)

## Scope

Production-source refactors that keep the work coordinator, the GL owner, the viewer screen and
the content pipeline inside the architecture quality gate, the standard project checks, and the
Ps16k device re-validation of the engine viewer.

## Verified

- `verifyArchitectureQuality` passes for 439 files with zero violations.
- `gradlew test lint :app:assembleDebug :app:assembleRelease verifyArchitectureQuality`
  completes successfully.
- The `data` module lint had two `NewApi` errors at `LocalTlsRelay.kt:26`
  (`java.util.Base64` requires API 26 while the module minimum is 24). The relay now builds its
  Basic authorization with `okhttp3.Credentials.basic`; the header is byte-identical for the UUID
  credentials it generates, and the call is available on the module minimum.
- Ps16k device re-validation (emulator-5554, AVD `MangaViewerPs16k`, NVIDIA GLES, no SwiftShader):
  - Full qemu cold-boot four-source frame-timing gate, three consecutive runs: `PASS4` x3
    (ntk/wfwf/newxtoon/goodtoon). Every run reported `renderMiss=0`, `flingMiss=0`,
    `violations=[]`, with render p95 0.29-0.43 ms per source.
  - ntk single gate: `OK (1 test)`, 21.7 s.
  - Scroll qualification capture (wfwf `comic:10001` episode 1, trace flushed and hashed):
    gateable criteria PASS - `moving_stage_coverage`, `ring_integrity`, `next_boundary_observed`,
    `prefirst_viewport_input_attempts`, `display_fence_coverage`, `raw_input_binding_honest`;
    `cache_inventory_recorded` and `cache_state_provenance` also PASS.

## Honest limitations (no local producer)

- `natural_cold_state`: the checker needs `cache-state.json` from the external scoped-cold runner;
  there is no in-repo producer, so the criterion stays UNVERIFIED.
- `analyzer_report`: needs `analyze-presented-motion.py`, which is not present on this host.
- Legacy viewer smoke tests: the pre-engine `ViewerUxTestHarness` device smokes
  (ntk/wfwf/heavy/autoappend/manhwa `UxStressSmoke`, ten-episode autoappend harnesses,
  `ViewerScreenshotEvidence`) read the pre-engine global-offset telemetry that the engine
  migration deliberately stubbed (`EngineViewerScreen.viewerTelemetrySnapshot()` returns null);
  they are incompatible by design and are not run as qualification evidence.

## Receipts

- `.artifacts/qualification/ps16k-scroll-refactor-1790301975755/` and its `.checker.json`.
- `%TEMP%\opencode\cf\speed\summary-*-PS16KF3-*.json` for the cold-boot gate runs.
