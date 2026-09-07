# Practical completion scope — 2026-09-07

Historical plan: superseded by the user's explicit [realistic release goal](viewer-release-goal-20260907.md). The 20-episode gate below is no longer a release requirement.

The user's direct correction, “아니 목표를 현실적으로” (make the goal itself realistic), supersedes the earlier 200-episode acceptance plan. Existing evidence remains historical evidence; it does not acquire new passing status from this change.

## Required completion

- Select and freeze one actual catalog series in each NTK/WFWF × COMIC/WEBTOON group, with five consecutive episodes per series: 20 episodes total. Record the selection seed, catalog identities, episode order, and final app/test APK hashes.
- Enter episodes through the real catalog UI and exercise actual gestures through the episode, including the final stopped screen. Verify image completeness/correctness, navigation, input order, and reading-position preservation. Missing evidence is explicitly incomplete.
- Fix release-blocking crashes, wrong or missing images, reading-position damage, input faults, and confirmed resource leaks. Recheck the failed segment and affected regressions; a failure no longer discards unrelated successful episodes. Do not silently substitute failed series.
- Measure and disclose startup time, rendering delays, visible blanks, and memory behavior. The earlier 4-second/16-ms/1-percent/no-100-ms targets are improvement targets, not absolute release gates. Exhaustive external-cost proof and five/ten-pair experiments are no longer required for completion. Do not label submission or synthetic latch timestamps as physical presentation measurements.
- Preserve original image quality, existing uncommitted work, input behavior, and continuation positions. Use only emulator-5554 / MangaViewerApi35 and wired networking; retain AVD RAM and security settings. Do not prepare content or manipulate caches to improve test results.
- Pass module tests, relevant Android regressions, lint, debug/release builds, architecture checks, and git diff --check on the final candidate. Reuse valid completed checks when their inputs have not changed. Verify workflow module checkout coverage.
- After the same final candidate passes all 20 episodes and required checks, commit/push to main and verify CI. Report remaining performance limitations and link evidence. A changed candidate requires affected checks and final-candidate runtime evidence.

## Current evidence at scope change

No episode has yet been accepted under this plan (0/20). Earlier diagnostics are not corpus completion. Current archived debug app SHA-256: `71f2eafd147fd1b20f671a6ccfbcbb9aff89d583d465fdeee8c5decad2115cca`; test APK: `86592e263b419cc38268008f5507b5bb631f060a756b4443cf68d80d56c94aca`.

The latest actual UI diagnostic is `.artifacts/engine-rewrite-20260906/ntk-worker-close-live-root/`. It reported submission p95 157.3402 ms, maximum 613.8362 ms, and 195 submissions at least 100 ms. These values are not physical display latency. Decoder workers terminated and session work/leases/prepared ownership returned to zero. Sampled memory is evidence at sample points, not proof of continuous peak or all GL allocations.

Full module checks already passed after the decoder closure fix: 903 test executions across ten modules (including variant repeats), lint, release build, and structure checks; debug/test APK builds and 23 relevant Android regressions also passed. See `engine-memory-evidence-20260907.md` and its linked artifact names. No commit/push or CI success has been claimed.

## Execution ownership

The root owns ADB, builds, integration, and acceptance. The user's later instruction “워커 사용 ㄴㄴ” supersedes the earlier worker preference: all remaining work is performed by the root alone. Measurement must not overlap heavy builds or analysis. Prior swap-interval/pending-frame caps were rejected for loss/throughput effects and are not release fixes.
