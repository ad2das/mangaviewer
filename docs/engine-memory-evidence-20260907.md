# Engine PSS collection

`collect_engine_live_trace.py --memory-sampling` enables the existing bounded, asynchronous owned-process PSS recorder in the normal-viewer capture test. Active requests run on its worker, not the gesture caller. Raw ActivityManager ownership records and per-process `dumpsys meminfo` output are retained. The recorder marks samples crossing viewer closure separately and terminates before instrumentation completes.

There are two baselines. `before-catalog` precedes real catalog navigation and remains the baseline for the sampled workload PSS increase. `before-viewer` occurs after normal catalog navigation, immediately before the actual episode-row tap. The viewer is then closed while the same catalog series remains open; `after-viewer` supplies the residual comparison. This does not introduce extra content loading, cache resets, or a content-readiness wait. The catalog identity is checked on return. Full cache-state equivalence remains unverified.

The policy regression test verifies that introducing the catalog baseline cannot hide startup-to-workload PSS growth. Eleven memory tests passed on the designated emulator. The application APK stayed at SHA-256 `04c3923b8bd82f095d41b9f7175fb46a63344b49fd5639de7cd7b0ad32ebdbdc`; the new instrumentation APK is `12877ad5d1006873e62713a16a22ab709deb29edf3b770407a4c09aec7feaf34`. Both are archived with the live collection.

## Actual results

Paths are relative to `.artifacts/engine-rewrite-20260906/`.

The initial diagnostic, `ntk-owned-pss-live-root/`, compared the pre-catalog process group with the post-viewer group. It failed the 64 MiB residual check by reporting 225,529 KiB of growth. Its raw records show one process at the initial baseline and three owned processes after catalog/episode work. The failure is preserved; it is not sufficient evidence of a viewer leak under equal initial and final resource conditions.

The corrected diagnostic, `ntk-owned-pss-catalog-boundary-root/`, retained both baselines and returned to the same catalog series. It collected 54 measured samples: three boundary samples, 50 eligible active samples, and one sample overlapping viewer closure. The overlapping sample was not credited as active.

| Measurement | KiB |
|---|---:|
| Before catalog | 71,066 |
| Before viewer, in catalog | 226,749 |
| Maximum eligible active sample | 307,283 |
| After viewer, in catalog | 279,957 |
| Startup-to-active increase | 236,217 |
| Adaptive increase limit | 508,505 |
| Catalog-to-catalog residual | 53,208 |
| Residual limit | 65,536 |

`owned-pss-audit.json` independently checks every ownership record, PID set, raw PSS value and sum, timestamp ordering, closure overlap, and sampler termination. It records hashes of the raw evidence. The diagnostic auditor deliberately rejects sparse/unmeasured sequences rather than guessing their raw-file ordinals.

These sampled PSS checks passed. Continuous peak coverage, full native GL allocation accounting, and cache-state equivalence are not established; `memoryQualified` and corpus credit remain false. Rendering also failed: submission p95 was 160.1993 ms and maximum was 497.6287 ms. This run does not establish an unavoidable-cost exception.

## Worker termination correction

The viewer previously completed `engineClosed` before shutting down its decode dispatcher. `AndroidWorkDispatcher.closeAndAwait()` now shuts down the executor and waits for termination on `Dispatchers.IO`, with a bounded failure if work remains. `ViewerActivity` attempts both engine and worker cleanup, preserves cleanup exceptions, and completes its close signal only afterward. The capture test independently records and asserts the executor's `isTerminated` state.

Two Android tests verify that closure waits for blocked work while the main thread remains usable, and that a timeout cannot claim termination. The existing GL-owner and PSS regressions also passed: 23 Android tests in total. The raster-capability cache was separated from `EngineSurfaceOwner`, and pending presentation delivery moved into its private state object to satisfy the existing 400-line class limit. Two JVM tests verify lazy capability queries, epoch invalidation, and recovery after a failed query.

The subsequent actual UI run in `ntk-worker-close-live-root/` confirmed `decodeWorkersTerminated=true`. Sampled PSS increase was 239,232 KiB and catalog-to-catalog residual was 56,645 KiB, within their limits. Submission p95 was 157.3402 ms, maximum 613.8362 ms, with 195 submissions at least 100 ms: performance still fails. The archived app/test hashes are `71f2eafd147fd1b20f671a6ccfbcbb9aff89d583d465fdeee8c5decad2115cca` and `86592e263b419cc38268008f5507b5bb631f060a756b4443cf68d80d56c94aca`.

## Full module checks

`gradlew test lint :app:assembleRelease verifyArchitectureQuality --max-workers=2` passed after the live run ended. The summary `full-module-checks-worker-close-summary.json` records 903 unit-test executions across all ten modules, including repeated Android debug/release cases, with zero failures, errors, or skips. Debug and instrumentation APK builds also passed. The release APK SHA-256 is `a003d2422866cfd44f09e13788f3a728c5a7d8cecbca655adac36dbfb5a0b1ea`.

`ci-module-checkout-audit.json` confirms that both APK workflows include all ten declared module directories in sparse checkout, including `viewer-content`, `engine-api`, and `engine-v2`. This is a static check; no CI run, commit, or push has occurred. Runtime qualification and the 200-episode corpus remain incomplete.
