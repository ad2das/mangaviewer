# Active priority: faster, equally accurate verification

The user's 2026-09-07 instruction to measure and dramatically accelerate testing supersedes continuation of the slow serial corpus run. Root only; no workers. Preserve the fixed 20-episode sample and existing accepted evidence (4/20 when this priority was set). Episode 5's already running collector may close normally; do not launch episode 6 automatically.

## Objective and success criteria

- Measure total host wall time and monotonic stage durations: setup, catalog search, episode navigation, viewer traversal/capture, closure and memory, archive/export, and independent review. Existing Android runs take about 280 seconds; this is a preliminary baseline, not a complete host total.
- Target at least **70% lower median end-to-end verification time** on at least three identical fixed representative episodes, with before/after durations and variation reported. Rough target: about 90 seconds for cases currently taking roughly five minutes. Treat this as a target to demonstrate, not an achieved promise.
- Preserve original pixels, real UI entry/gestures, complete selected page/discrete source-row verification, independent episode/document ordering, two final compositor checks, input/renderer history, sampled memory and zero closed-session ownership. Keep physical-display/continuous-memory limitations explicit.
- Prove that existing successful captures retain their verdict and malformed/missing/wrong-image or identity evidence still fails. Do not gain speed by skipping checks, shrinking the fixed sample, suppressing input, reducing quality, shortening timeouts alone, prewarming content or manipulating caches.
- Profile UI automation idle synchronization, repeated hierarchy reads/row searches, capture transfer and pixel analysis. Change the measured bottleneck, then compare the same case. Avoid repeated full builds and unaffected regressions.
- Keep emulator-5554/MangaViewerApi35, wired networking, RAM and security settings. Keep heavy analysis/builds separate from device performance measurements.
- After the pipeline improvement, resume the remaining fixed corpus and final checks, commit/push and CI work. Do not discard unaffected passing evidence or claim the old 200-episode contract is complete.

## Initial findings and execution state

`CorpusUiEntry.findTextInList` performs repeated UI hierarchy reads and 55-step real swipes, and `prepare` and `open` each search for the selected episode. UI automation idle synchronization is a hypothesis requiring stage measurements. Current collector identity was verified; only serial launcher PID 8352 was stopped, leaving episode 5 collector PID 4004 to archive and restore trace state normally. No new worker was started.

This document records the active user-directed objective. The goal API rejected replacement because the existing objective is unfinished. Do not falsely complete it to work around that limitation.

## Measured baseline and first experiment

Existing monotonic memory boundary timestamps establish the following durations (seconds). These are boundary-to-boundary device durations, not full host totals:

| Fixed episode | Before catalog to before viewer | Viewer through post-close sample |
| --- | ---: | ---: |
| 1 | 241.374 | 42.088 |
| 2 | 239.013 | 41.194 |
| 3 | 245.777 | 29.848 |
| 4 | 227.044 | 40.954 |
| 5 | 235.540 | 31.354 |

Navigation consumes approximately 85–89% of these measured intervals. The first experiment changes only test UI navigation idle synchronization to zero while retaining explicit state/identity checks and real swipes. The original UI automation timeout is restored before viewer opening and on navigation closure. Default behavior remains unchanged. The test APK adds per-stage and per-hierarchy/swipe monotonic timing; the host collector adds setup, instrumentation, transfer/archive and verification phase timings. Instrumentation APK build and architecture checks passed; Gradle was stopped before measurement.

App SHA remains `d11b302545e8c9c8b8dc7b8a88fd643bd895dd41cff1e8e1d57c49ec269a985a`. Experimental test SHA is `84e7db7ecda7ab431eccaa4b782dd5da4c2cfeb9bd0f4821848df57158c4d24e`. First fixed comparison: `speed-episode-03-idle0/`; no speed or accuracy result is yet claimed.
