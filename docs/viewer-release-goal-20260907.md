# Viewer release goal — user-approved replacement, 2026-09-07

This goal replaces the earlier 200-episode and practical-20 acceptance plans at the user's explicit request. Work remains solo. Complete the portable viewer improvements, validate the actual minified release, review and commit/push the implementation, and inspect CI. Preserve original image quality, input semantics, reading positions, existing work, and the designated emulator's RAM/security configuration.

## Acceptance and evidence

- Keep the coordinator wait-dispatch fix, adjacent verified-page edge preparation and release JNI callback keep rule. The engine rewrite and source adapters already in the worktree are required dependencies of this candidate.
- Reuse the recorded 88 engine tests, 95 data tests, original-source pixel comparisons, input receipts and surface recreation/resume evidence. Run final incremental module tests, lint, builds and architecture checks once before publication.
- Validate NTK and WFWF image loading/scrolling on release and short repeated opens/closes in one retained app process. Both release source smokes passed. The four alternating same-process runs also completed with PID 4711 throughout, six swipes per run and all four screenshots showing source content. Main-process closed PSS was 56611, 55825, 55433 and 56520 KiB; thread counts were 75, 77, 77 and 77. This small sample shows no monotonically growing main-process memory trend; it does not prove long-duration stability or whole-process-tree peak usage. Artifacts: `.artifacts/viewer-focus-20260907/release-same-process/`.
- Preserve honest performance distinctions. Fresh-host diagnostic first source submission was 1.647 s and native submission p95 7.953 ms; two later sampled runs were 1.339/1.456 s and 8.103/2.883 ms. These are not physical display timestamps. NTK startup varied with document/auth/network work; older timings remain in the focus report. Page-edge incomplete-scene submission spans fell about 90% in two comparisons, not a directly measured screen-blank duration.
- 200/20 full episodes, catalog navigation, unavailable emulator DISPLAY_PRESENT evidence, physical-phone measurements while no phone is connected, and absolute 4 s/16 ms/1%/100 ms thresholds are not mandatory release gates. Do not claim those unperformed checks passed. No repeated exhaustive comparison or long-duration soak is required for this release.
- Fix any newly reproduced release crash, incorrect image, input/resume corruption or confirmed leak. Do not hide failures by rebooting. Record emulator runtime state when comparing performance.
- Commit only project implementation, tests, build/workflow settings and reviewable reports/tools. Keep local captures, content, credentials and transient experiment outputs out of Git. Push main after the checks pass and report CI's actual result.

## Deliverable

Corrected minified release APK: `.artifacts/viewer-focus-20260907/candidate-release-jni-callback/app.apk`, SHA-256 `d8712bf4564b3f42c24000bdfbe8d69ad5cd495b7224d5c5a4bcd641a284b699`, Android 11+, arm64-v8a/x86_64. The earlier release `8dd777af...` is superseded because its JNI callback was removed by R8 and it crashed on viewer launch. Real-phone performance remains unmeasured.

Detailed retained experiments and their limitations: [viewer performance focus](viewer-performance-focus-20260907.md). Local artifact paths are provenance references, not claims that raw captures are published in the repository.

## Final local validation

`gradlew test lint :app:assembleDebug :app:assembleRelease verifyArchitectureQuality --max-workers=2` passed in 40 seconds, 329 tasks (30 executed, 299 up-to-date). Current XML reports contain 908 unit-test executions across ten modules, including Android variant repeats: zero failures, errors or skips. Architecture gate passed over 291 production files. Lint passed with existing warnings; it is not warning-free. Release APK hash still exactly matches the smoke-tested d8712bf4... binary.

`python -X utf8 -m unittest discover -s tools -p 'test_*.py'` passed 344 tests in 5.814 seconds. Staged diff whitespace check passed. Both APK workflows include all modules referenced by settings.gradle, including viewer-content, engine-api and engine-v2. No captured images, APKs, databases, native binaries or private-key/token pattern matches were found in the staged additions/modifications. Local logs: final-realistic-checks.txt, final-unit-summary.json, final-tool-tests.txt under `.artifacts/viewer-focus-20260907/`.
