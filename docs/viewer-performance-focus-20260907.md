# Active user priority: viewer loading and scrolling

The user's latest direct instruction limits current work to first-image latency, scrolling stutter/stalls, visible blanks and image replacement delay. Enter known episodes directly. Stop catalog/navigation and test-pipeline optimization. Root only, no workers. This supersedes the pipeline speed goal and stale automatic 200-episode/worker text. Existing corpus and raw evidence remain preserved; current work is a performance diagnostic, not corpus credit.

Preserve original image quality, input processing/order, reading-position behavior, device/RAM/security and wired networking. Measure app behavior separately from readback/memory instrumentation. Do not label native submission or composition-latch times as physical display latency.

The user further requested minimizing total time to the viewer improvement. Use short direct-launch comparisons first, reuse valid evidence, run only affected incremental builds/regressions, and reserve image/position validation for candidates with measured benefit. Repeat a comparison when variability makes the decision unclear; do not run the old corpus loop or optimize unrelated navigation. The fixed 24-gesture diagnostic currently completes in about 38 seconds including collection.

## Baseline

`direct-baseline-01/` under `.artifacts/viewer-focus-20260907/` uses normal direct viewer launch, original app `d11b302545e8c9c8b8dc7b8a88fd643bd895dd41cff1e8e1d57c49ec269a985a`, 24 fixed real touchscreen gestures, trace enabled, readback and memory sampling disabled. Existing caches were left alone. Total collection took 37.869 seconds. Native submission p95 using the established verifier's nearest-rank definition was 186.667 ms (the initial rough lower-index estimate was 169.986 ms), maximum 566.484 ms across 211 submissions, including 30 empty-source scenes. This is not an image-correctness qualification or exact physical presentation measurement.

Trace aggregation: mean native frame submission 108.76 ms, mean swap 106.70 ms. The GL thread spent 14.0 seconds in dequeueBuffer and 8.5 seconds in emulator rcCreateSyncKHR encode across this run. These overlapping/nested costs identify where time is spent, not proof that all delay is unavoidable. Earlier raw trace clock limitations remain relevant to cross-clock analysis; these are local slice durations only.

## First app change under evaluation

Repeated state/input updates with no drawable image submit identical empty pixels. An experimental `EngineRenderRuntime` change suppressed consecutive identical empty scenes while retaining reducer/input processing and work reconciliation. The build, engine-v2 tests (including five renderer tests), and architecture checks passed. This experiment was subsequently rejected and its production/test edits reverted because the short comparison did not show a user-facing benefit.

## Short comparison result and current state

`direct-empty-dedup-01` took 38.936 seconds, reduced empty submissions 30 to 3, but native submission p95 was 195.7533 ms versus 186.667 ms baseline. First-source submission return measured from the first submission was 7.433 versus 4.536 seconds. These are not equal-cache controlled trials: the candidate run fetched source images while the baseline reused existing cached bytes, so they do not establish a causal startup regression. They also do not establish a benefit; no longer image/corpus qualification was spent on this candidate.

The original archived app/test pair was reinstalled without rebuilding. A WFWF direct baseline completed in 38.052 seconds with native p95 162.0587 ms, maximum 747.7305 ms and 36 empty-source scenes. Therefore large submission delays occur beyond NTK's authorization path. `initial-comparison.json` contains the established verifier metrics. No net production change from this experiment remains; no viewer speed improvement is claimed. No collector/build is running at this checkpoint.

Next focus: the common renderer's dequeue/sync bottleneck and independently observed first-image request-to-ready intervals. Native frame duration is predominantly swap/dequeue; do not spend further full-corpus runs or navigation work on this question. Emulator remains actual `-gpu host`, 8 CPUs, 8192 MiB, configured 60 Hz, unchanged.

## Further short controls: no release improvement established

- Disabling EGL frame timestamps (`direct-no-timestamps-01`) gave p95 160.8252 ms versus WFWF baseline 162.0587 ms; insignificant single-run difference. Reverted.
- Asynchronous buffer queue (`direct-async-buffer-01`) gave p95 101.4899 ms, but 259/438 submissions had unavailable EGL observations and only 178 reported latches. Lower call duration did not establish smoother physical display. Reverted; do not repeat this rejected path.
- Added export of existing `viewerStartupTimingSnapshot()` to `startup-timing.json`, with physical verification explicitly false. This adds no waits and changes no production behavior. The 12-gesture startup diagnostic completed in 29.836 seconds.
- NTK startup baseline: runtime-open to first HTTP request **3253 ms**, manifest ready **6965.389 ms**, first source submission **7435.512 ms**. Trace shows the first UI traversal took 3166.2 ms, RenderThread surface setup 2303.3 ms and EGL context creation 1548.2 ms. These identify overlapping startup costs; they do not yet prove the exact dependency blocking request dispatch.
- Moving runtime.open before setContentView (`direct-early-content-ntk-01`) did not resolve it: request start 3082.999 ms, manifest 6950.323 ms, first source submission 7462.174 ms. Reverted. No further validation spent on this ineffective ordering change.

Current next action: identify the work-subscription/admission/start boundary delaying the first request by about three seconds. Session manifest and saved-position demands are already independent; do not add redundant parallel fetching based on an assumed position gate. Preserve saved-position and input semantics. All trial production changes above have been reverted. Startup export remains in the instrumentation source. Archived baseline app should be restored after the early-content trial before the next control.


## Work wait lock convoy: retained candidate (2026-09-07)

Two temporary boundary builds completed: `direct-start-boundary-ntk-01` and `-02`. In the second, initial subscription completed promptly but `ntk.episode` execution waited about 3.2 seconds; document-key construction was 3.7 ms and dependency registration 1.7 ms. The source thread was predominantly sleeping, not CPU-starved. A queued coroutine mutex waiter owns the lock before its dispatcher resumes it, allowing a paused UI consumer to block the coordinator.

`WorkCoordinator.awaitSubscription` now runs its lock/await/delivery section on the coordinator dispatcher, preserving the caller Job and the outer cancellation cleanup. New `pausedConsumerDoesNotHoldTheRegistryLockWhileAwaitingWork` fails on the old implementation and passes on the candidate; the first red command failed to invoke Gradle and was rerun correctly with cmd /d. Final engine-v2 result: 84 tests, zero failures/errors/skips. Debug build passed. All 12 temporary START_BOUNDARY statements were removed by restoring the five original files; no temporary logging remains.

- Instrumented control `direct-start-boundary-ntk-02`: HTTP start 3299.4 ms, body complete 4040.7 ms, manifest 7253.9 ms, first source submission 7801.7 ms.
- Instrumented candidate `direct-await-dispatch-ntk-01`: HTTP start 380.1 ms, body complete 2784.6 ms, manifest 6565.9 ms, first source submission 7039.8 ms.
- Clean candidate `direct-await-clean-ntk-01`: HTTP start 50.5 ms, body complete 2516.7 ms, manifest 6038.9 ms, first source submission 6547.3 ms. Zero remaining work subscriptions, file leases, prepared pages or pending publications; decode workers terminated.

These short observations support retaining the lock fix; they do not establish physical presentation time or full image acceptance. Network latency varied and caches were untouched. Installed app is `candidate-await-dispatch-clean/app.apk`; test remains `candidate-startup-export/test.apk`. Both collectors and builds finished. Next experiment targets separate Activity RenderThread surface initialization while preserving native SurfaceView GL rendering.


### Clean candidate validation and rejected Activity rendering control

The final additional cancellation regression (`cancelledReturnToPausedConsumerReleasesTheDeliveredResult`) checks the new dispatch-return boundary: the result is ready while the consumer queue is paused, then cancellation must dispose it exactly once and leave no subscribers or retained results. All **85 engine-v2 tests passed** (`await-dispatch-cancellation-tests.txt`). `git diff --check` passed (repository line-ending warnings only).

`direct-await-clean-wfwf-01` completed with HTTP start **52.3 ms**, body complete **3112.8 ms**, manifest **3382.5 ms**, first source submission **3928.3 ms**. Cleanup ownership counts are zero and decode workers terminated. `await-dispatch-history-checks.json` verifies complete adapter-input and renderer journals for both clean NTK/WFWF controls (562/548 accepted inputs, 104/103 submissions, zero failed swaps). This is not raw MotionEvent correspondence or pixel/physical-display acceptance.

Turning off hardware acceleration only for ViewerActivity (`direct-software-chrome-ntk-01`) worsened first source submission to **7593.5 ms** and native p95 to **347.5358 ms**, versus **6547.3 ms / 202.126 ms** in the clean retained candidate. It was rejected immediately; AndroidManifest.xml was restored byte-for-byte from its saved pre-trial copy. Native GL rendering was never changed by this trial.

Current installed app is the retained clean lock fix: `candidate-await-dispatch-clean/app.apk`, SHA-256 **4951ad650e66f81b8a2e9acb3fb8b00a16ff1c723a791468c26e2b6b8c9553ca**. Installed test remains `candidate-startup-export/test.apk`. Note app/build/outputs may still contain the rejected software-chrome build; use the archived clean APK, or rebuild after the restored manifest. No build/collector remains running at this checkpoint. No START_BOUNDARY logging remains. Net production change from this investigation is only WorkCoordinator.awaitSubscription; two regression tests accompany it.

Request dispatch is markedly faster, but scrolling is **not qualified as improved**: native submission p95 remains 202.126 ms (NTK) / 223.0701 ms (WFWF), with 55/51 submissions at least 100 ms. The next focus remains native swap/dequeue latency and actual image/scroll behavior, without repeating rejected timestamp, async queue, empty-dedup, or software-Activity trials. Full image comparison and physical presentation remain unproven; do not mark the goal complete.


## Scroll compositor investigation: unchanged retained candidate

Read-only inspection confirmed actual GLES uses Android Emulator OpenGL ES Translator on NVIDIA GeForce GTX 1060 3GB (NVIDIA 582.53), not a software renderer. During the subsequent viewer trial, one nvidia-smi sample reported 14% GPU, 6% memory utilization, 2000 MiB used, 139/405 MHz graphics/memory, P8, 8.72 W. This single observation does not prove a hardware limit or justify changing host settings.

Existing clean WFWF trace local-duration aggregation: engine GL swap 11.767 s across 103 calls, dequeue 7.919 s, rcCreateSyncKHR encode 3.989 s. SurfaceFlinger present 16.409 s across 125 calls; RegionSampling captureSample 8.177 s across 53 calls and rcReadColorBufferDMA 3.436 s. These are overlapping durations, not additive or proof of physical display latency. Imported trace still reports out-of-order clock warnings.

AOSP Android 15 NavigationBar.onBarTransition stops region sampling for MODE_OPAQUE (https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android15-release/packages/SystemUI/src/com/android/systemui/navigationbar/NavigationBar.java). Tested a Viewer-only theme opting out of edge-to-edge enforcement so its existing black navigation-bar color could apply. `direct-opaque-navbar-wfwf-01` preserved 1080x2138 viewport, first source submission 4566.72 ms, native p95 159.8911 ms. But region sampling persisted (56 calls / 9.367 s) and SF present remained 16.862 s / 130 calls. The proposed mechanism did not take effect; this single lower p95 is not causal evidence of improvement. Reverted themes.xml and AndroidManifest.xml byte-for-byte from opaque-navbar-originals; reinstalled the retained clean app (4951ad...). No net production change this turn and no live collector/build remains.

Do not repeat this opaque-navbar trial or infer a fix from the transient p95 change. Current next investigation should distinguish emulator/HWC blocking from removable current-renderer work using existing trace evidence; the old OwnedRendererCadenceTest uses an older renderer and is not a direct acceptance test for the current engine. No device/GPU/security/RAM settings were changed.


## Short original-image regression for retained lock fix

`direct-await-pixels-wfwf-01` completed on the retained clean app/test with the same 12 real gestures, readback enabled, 64 maximum captures, original cache untouched. Read-only export fetched the exact referenced cache bodies without network requests. All **23 captured strips** matched independently decoded original images with the existing per-row RGB MAE <= 4 tolerance; no calibrated edge override was needed. Native packet checks passed. Comparison took **7.313 seconds**. Evidence: `direct-await-pixels-wfwf-01/pixel-regression.json` and its original-sources manifest, packets and frame metadata.

The run's complete adapter receipt journal verified **630 accepted inputs**, zero cancelled/clamped inputs, and resolved deferred receipts. All **106 renderer submissions** were accounted for with zero failed swaps. After close, all work counts, file leases, prepared pages and pending publications were zero and decode workers had terminated. This qualifies these captured pixels and adapter receipts only; it does not prove every source row, raw MotionEvent correspondence, persisted resume position or physical display. Native p95 163.814 ms with 48 calls >=100 ms confirms scrolling remains unresolved; readback runs are not matched no-readback performance controls. No code changes or processes remain in flight from this turn.


## Page-boundary texture preparation: promising retained candidate

Correction to the previous conversational report: the only zero-source frame after first image in `direct-await-pixels-wfwf-01` is token 106, submitted during close about five seconds after the last reading frame. It is expected scene clearing, not demonstrated mid-scroll full blanking. Exclude close frames from reading coverage analysis.

EngineTilePlanner previously prepared adjacent bands only within a visible page, even when the session had already verified the next/previous page bytes. The new candidate prepares the first band of the next page when the last current band is visible, and the last band of the previous page when the first band is visible. It uses manifest order and existing verified PageContentIdentity only; does not issue speculative requests for unknown bytes, does not place offscreen tiles, and retains visible-first memory budgeting with NEXT_IMAGE priority. Three planner regressions cover both directions, absent bytes, spare budget, and no cross-page preparation while in the middle of a page. All **88 engine-v2 tests passed** and debug build passed (`page-edge-build.txt`).

`direct-page-edge-pixels-wfwf-01` used the same 12 real gestures/readback settings as `direct-await-pixels-wfwf-01`. Excluding startup and the close clearing frame, incomplete-scene submissions fell **24 to 1**, and their submission-to-next-submission durations totaled **2739.6 to 203.0 ms** (observed reading submission spans 11201.3 / 10846.2 ms). This is scene-readiness evidence, NOT measured physical blank-screen time. Native p95 was **180.8952 ms vs 163.814 ms**, so no improvement in general frame pacing is established.

All **22 captured strips matched original pixels**, all **566 accepted adapter inputs** verified, zero failed swaps, and closure left zero subscriptions/file leases/prepared pages/publications with decode workers terminated. Pixel comparison took 7.468 seconds. No network/caches/RAM/security settings were manipulated. Evidence in the candidate collection's `pixel-regression.json` and exact original-source export.

Current installed app is `candidate-page-edge/app.apk`, SHA-256 **01b19f0a73828822e61930cdc3c694daa3b8e39696776f74da0e6df67103cfaa**, with existing `candidate-startup-export/test.apk`. Source includes the previous WorkCoordinator fix plus the page-edge planner change and tests. The earlier GPU/software-chrome/opaque-navbar experiments remain reverted. No collector/build is running. Next: confirm this promising coverage improvement with one further short matched control before claiming it stable; no full corpus run yet.


### Second short comparison confirms page-edge readiness improvement

Ran baseline archive followed by candidate archive once more, same 12 gestures and readback settings. Both second-pair initial anchors are page p0000/sourceYQ32=0. Existing saved positions were honored, never manipulated. The first pair had different initial anchors (0 versus 144081294070 Q32, about 33.5 source pixels); do not describe that first pair as identical-position controls.

Second pair (`direct-await-pixels-wfwf-02` -> `direct-page-edge-pixels-wfwf-02`): incomplete reading scenes **27 -> 2**; incomplete-scene submission spans **3297.9 -> 348.7 ms**. Native p95 **162.9255 -> 166.9505 ms**, confirming the fix addresses preparation gaps, not general compositor stalls. Complete adapter-input checks passed in both runs; candidate accepted **579 inputs**. Candidate closure again left zero owned work/file/page/publication counts and decode workers terminated. Exact summaries, initial anchors, and scope limitations are in `page-edge-comparison.json`. First candidate run's independent 22-strip original-pixel comparison remains valid for the same app/test APKs. Second run's native packets were checked by collection; no additional pixel comparison was claimed.

Retain the page-edge planner change alongside the coordinator lock fix. All 88 engine-v2 tests and debug build passed, targeted git diff --check passed. Installed app remains candidate-page-edge (01b19f0a...), same startup-export test. Both new collectors completed successfully, no build/collector left running. Next unresolved focus: compositor/output stalls after drawable scenes are ready; NTK-specific post-document authorization time and broader original-quality/resume validation are still incomplete. Goal is not complete.


## NTK remaining startup phases on page-edge candidate

`direct-page-edge-ntk-01` completed with the same retained app/test, 12 gestures, no readback. HTTP start **278.5 ms**, headers **2555.2 ms**, document body complete **2570.4 ms**, manifest ready **5894.6 ms**, first source submission **6318.5 ms**, all relative to runtime-open System.nanoTime. Complete receipt history verified **662 accepted inputs**, zero cancelled/clamped, 114 deferred observations resolved. Cleanup ownership counts zero and decode workers terminated. No pixel or physical display qualification is claimed for this no-readback run.

Saved `browser-phases.log` from this run's NTK process 28130 only (filtered to exclude cookie/header/meta lines). WebView startup reported **274 ms**. Request-relative ageMillis: document received 227; challenge start 550/end 1368 (**818 ms**); canary ran concurrently; ACK start 1556/end 1867 (**311 ms**); manifest start 1878/end 2190 (**312 ms**). Combined measured request/response phase spans are about 1441 ms; this does not prove they are all irreducible network cost. Earlier clean candidate authorization's manifest-to-retirement delta was about 5.6 ms in the same elapsedRealtime clock, excluding cleanup as a multi-second cause. Do not subtract elapsedRealtime values from System.nanoTime without a valid clock binding.

Code inspection confirms isolated WebView initialization is triggered when its process starts, after document resolution currently leads to browser capture. However 274 ms startup alone does not support a complex early-binding rewrite to address a multi-second gap. No browser/protocol/security changes were made and no prewarming was introduced. Retained production changes remain work-wait dispatch and adjacent-page edge preparation. No commands remain in flight. Next unresolved focus remains output/compositor stalls; normal HTTP creation/response time is not yet separated into engine initialization versus actual remote latency.


## Trace overhead control and isolated output diagnostic

`direct-page-edge-ntk-no-trace-01` ran the same installed app/test and 12 gestures with trace/readback disabled. Traced preceding control vs untraced: native p95 **199.5232 -> 197.6612 ms**, first source submission **6318.5 -> 5540.3 ms**, host collection **28.837 -> 24.550 seconds**. Different startup/network/retained-position conditions prevent a causal startup claim. Trace overhead does not explain the ~200 ms native p95. Exact summaries are in `trace-overhead-comparison.json`; no-trace output is diagnostic only.

Ran existing `OwnedRendererCadenceTest` static mode once as an isolated native-output diagnostic, not a current-engine acceptance substitution. It uses the older ownership adapter but shared native renderer. It **failed** its presentation-count gate (67 intervals versus >=240 required); log reports CANCELLED=2 and COMPOSITION_LATCH=67, no DISPLAY_PRESENT evidence. Native render duration p95 **132.1658 ms**, decode **14.3285 ms**, second upload **148.1443 ms**, UI vsync callback gaps >=25ms **2/443**. These observations support a common output bottleneck even without provider/page work.

Caution: this older test sorts CANCELLED zero timestamps into its presentation interval list, yielding a bogus maximum around 9.25e7 ms. Do not quote that max, mixed-kind intervals, or its 'physical' assertion wording as physical display measurements. The independently recorded native call-duration p95 and callback counts remain diagnostic. Logs: `native-output-control.txt`, `native-output-control-log.txt`. No gates were relaxed, no code changed, no device/RAM/security/GPU settings changed. Probe ActivityScenario completed closure after failure; instrument command finished. Installed app/test remain page-edge/startup-export. No live build/collector remains.

Next: avoid repeating trace-disabled or old synthetic cadence probes. The current renderer's native swap/dequeue and emulator HWC path remain unresolved; further app changes must target an evidenced removable cost rather than re-running rejected controls or claiming the requested physical/frame limits have been achieved.


## Focused lifecycle/integration and release validation

Reused existing real-run frame journals to verify exact source-anchor continuity over three consecutive same-episode launch pairs (`observed-resume-continuity.json`): all three previous final anchors matched next initial anchors, including the nonzero 33.5-source-pixel anchor. Journals are SHA-256 bound in the report. This is observed resume continuity, not an exhaustive persistence/rotation claim; the old ViewerNtkResumeAnchorTest uses a different path with explicit production warmup, so it was not run.

Ran the current native `EngineReadbackInstrumentedTest.exactPixelsSurviveSameSizeSurfaceRecreation` on the installed candidate app/test: **1 test passed in 9.071 seconds**, covering eight exact pixel packets across SurfaceView recreation, surface epoch changes, duplicate ticket handling and owner-thread shutdown. Log: `page-edge-surface-recreation.txt`.

Ran data integration unit tests, release assembly and release lint together with no concurrent emulator measurement: `:data:testDebugUnitTest :app:assembleRelease :app:lintRelease`. **BUILD SUCCESSFUL in 3m38s**, 176 tasks, 29 executed, 147 up-to-date. Data results: **95 tests, zero failures/errors/skips**. Release lint: **33 warnings, no errors**; do not call it warning-free. Existing engine-v2 candidate results remain **88 tests passed** and are reused. Full log: `page-edge-release-validation.txt`.

Archived release APK at `candidate-page-edge-release/app.apk`, SHA-256 **8dd777afa541d5a89230670bd0e0aaf141cefc0a9b6ae5e114e1bbe84e41c729**. This release binary was built/checked, not performance-qualified or installed. Emulator retains the measured debug `candidate-page-edge/app.apk` (01b19f0a...) and startup-export test. No commit, push, publication or CI success is claimed. The standalone cadence/display gate remains failed and physical presentation proof is absent; these successful checks do not override that unresolved performance requirement. All validation commands are terminal; no build/collector is running.


## Corrected cadence evidence guard (test-only)

Fixed OwnedRendererCadenceTest's timestamp classification before it computes display metrics: log raw observation kinds and native-call duration separately, remove terminal CANCELLED observations, then require nonempty DISPLAY_PRESENT observations with positive timestamps not earlier than submission. Only after that gate may it calculate display intervals. This removes the previously bogus zero-to-uptime maximum and stops treating composition-latch intervals as physical display timing. Existing count/stall/missed-frame thresholds remain unchanged.

`assembleDebugAndroidTest` passed in 20s; targeted git diff --check passed. Installed the archived `candidate-cadence-evidence/test.apk` temporarily and ran the diagnostic once: it failed explicitly with **Actual display timestamp unavailable: [COMPOSITION_LATCH]** (68 composition latches, 1 cancellation), as the environment cannot satisfy this test's timestamp requirement. Native-call p95 was 125.6457 ms and no bogus physical interval summary was emitted. This negative check is expected validation of the evidence guard, NOT a passing performance test. Log: `native-output-evidence-guard.txt`.

Restored the previously measured `candidate-startup-export/test.apk` afterward; adb install succeeded. App still page-edge candidate (01b19f0a...). Production/release APKs and their earlier image/input/build evidence are unchanged. Test source retains the corrected guard, so future freshly built test APK hashes will differ from archived startup-export; do not reuse exact-APK calibration across that difference. All commands finished, none running.


## Page-edge memory sampling

`direct-page-edge-memory-wfwf-01` completed with the retained candidate app/startup-export test, 12 real gestures, no readback, trace and memory sampling enabled. **Sampled PSS policy passed**, no violations, sampler terminated. Across 15 measured boundary/active samples: startup 70728 KiB, pre-viewer 67995 KiB, observed active peak 108905 KiB, observed rise 38177 KiB versus adaptive 508505 KiB budget; after close 97012 KiB, residual 29017 KiB versus 65536 KiB boundary check. Exact summary: `memory-summary.json`; raw ownership/PSS and sampling outcomes are retained under the capture's memory directory.

This is sampled memory evidence only: the capture explicitly leaves allOwnedPeakConfirmed, likeForLikeCacheStateVerified, allNativeGlAllocationsVerified and memoryQualified false. It does not prove the exact peak of every owned process, identical-cache residual, or all native GL allocation accounting. No limits were raised, no cache state was manipulated, and no performance improvement is inferred from this instrumented run. App/test unchanged; command is terminal and no measurement/build remains live.

## GPU control, restoration, and real-phone scope

The user clarified that the final target is a real Android phone after emulator validation. Continue solo, focus on first images and scroll readiness, reuse existing validation, and avoid further emulator-specific tuning or broad corpus loops. The portable retained changes are coordinator wait dispatch and adjacent verified-page edge preparation. Release already includes arm64-v8a and x86_64, with minimum Android API 30 (Android 11); real-phone performance has not been measured.

A temporary command-line `-gpu swiftshader` run on the same AVD produced static native-call p95 23.6421 ms versus previous host diagnostics 125.6457–132.1658 ms. The actual viewer run `direct-swiftshader-wfwf-01` produced first source submission 1595.683 ms, native submission p95 24.4025 ms, maximum 69.1382 ms, 399 frames. These are diagnostic submission metrics, not physical display timing. The legacy synthetic test's mixed cancelled timestamp maximum is invalid and must not be used.

After stopping the temporary emulator, restored the original host GPU with port 5554, unchanged 8192 MiB RAM/configuration and SELinux Enforcing. Verified boot complete and GLES NVIDIA GTX 1060 host translator. AVD config SHA-256 remains 7f38597a323f824d5cb7b6f6508a17b9893d65806df7c7488c9fdc99b1ca7629. Recovery state now records mustRestoreHost=false.

The one fresh-host comparison `direct-host-restored-wfwf-01` succeeded with the same archived app/test and 12 gestures: first source submission **1647.1133 ms**, native submission p95 **7.9534 ms**, maximum **22.3598 ms**, **392 frames**. Exact two-run summary: `.artifacts/viewer-focus-20260907/gpu-backend-comparison.json`. This refutes attributing the earlier improvement specifically to SwiftShader: the restored host is faster on this short run. Restart/runtime state is a plausible confound, not a proven root cause. No cache manipulation or app code change was performed during these comparisons. Do not extrapolate these figures to a real phone or claim physical 60 fps; physicalPresentationVerified and performanceQualified remain false.

Emulator backend experiment is concluded with the original host configuration restored. Existing 88 engine tests, 95 data tests, release build/lint, exact pixel checks, input evidence, lifecycle check and sampled memory results remain applicable and should not be repeated without a relevant change. Both portable fixes remain in the archived release APK; no real phone is connected or tested as part of these runs, and no commit/push is claimed.

## Future measurements: distinguish runtime accumulation from app regressions

User explicitly requested using reboot recovery as a lesson for future investigation. When a long-running emulator shows a large unexplained slowdown, preserve its current short-run evidence and process/memory/device-uptime observations, then compare one matching short run after restarting the same AVD with the original GPU/RAM/security configuration. Record the restart boundary; compare app candidates under comparable runtime conditions. Do not spend repeated code experiments on a slowdown that disappears with environment recovery, and do not reboot before every test or silently discard degraded runs. Reboot recovery is evidence of state sensitivity, not identification of the accumulated resource or proof that the app has no leak. If deterioration recurs during normal repeated viewing, investigate app/session resource release separately; real-phone correctness and sustained use remain relevant.

The interrupted release-smoke preparation performed read-only inspection only; no release install or release runtime test was executed. No collector/build/instrumentation process was found active when resuming. Release runtime smoke remains pending; archived debug evidence must not be relabeled as release evidence.

## Release-only JNI callback crash fixed

Actually installed the previously built minified release (8dd777af...) and directly launched WFWF comic:10289 episode 35 using `su 0 am start` on the designated emulator (non-exported activity; no security setting changed). It crashed immediately with `GL owner creation failed`; original crash evidence is `release-smoke/original-crash.txt`. Thus the earlier successful release build/lint did not establish runtime usability and that old release should not be delivered.

The native constructor resolves `onFramePresented` by exact name and `(JJIJ)V` signature, but it has no Java caller. Added one precise keepclassmembers rule for OwnedRendererCallback.onFramePresented in app/proguard-rules.pro. R8's new mapping preserves the method name on the concrete callback. Native source search found this was the only GetMethodID/GetFieldID/FindClass lookup in app/src/main/cpp. No renderer algorithm, image quality, timing thresholds, or emulator settings changed.

`:app:assembleRelease` passed in 35 seconds (11 tasks executed, 133 up-to-date); targeted diff --check passed. New ARM64/x86_64 release: `.artifacts/viewer-focus-20260907/candidate-release-jni-callback/app.apk`, SHA-256 **d8712bf4564b3f42c24000bdfbe8d69ad5cd495b7224d5c5a4bcd641a284b699**. Installed and repeated the same direct launch without reboot. Image content appeared; four real adb swipe gestures changed the visible comic panels; inspected before/after screenshots. PID stayed 3383 and its captured log contained no FATAL EXCEPTION. Evidence: `release-smoke/smoke.json`, before.png, after.png, process-log.txt, activity.txt, memory.txt. This is a release launch/scroll smoke, not exact-pixel, sustained-use or physical-frame qualification. Launch wait is not first-image timing.

Closed the release viewer via Back and restored archived page-edge debug APK successfully for existing diagnostic tooling. The corrected release remains archived for phone installation; the old release is superseded. Existing debug source/behavior evidence remains applicable to the two portable engine changes; it is not relabeled as evidence for the new release binary. No real phone test, commit or push performed.

## NTK release smoke

Installed the same corrected release d8712bf4... and directly launched NTK /manhwa/22158 episode /manhwa/22158/211678, without reboot, cache manipulation or a new build. Inspected screenshots showing loaded source content before and after four actual swipe gestures. PID remained 3668, captured process log had no FATAL EXCEPTION. Artifacts: `.artifacts/viewer-focus-20260907/release-smoke-ntk/` (smoke.json, before.png, after.png, activity.txt, process-log.txt). Together with WFWF this verifies basic viewer loading and scrolling on both sources in the minified release. It does not establish fresh authorization timings, exact pixel equivalence, full-episode coverage, sustained performance or real-phone performance. Closed with Back and successfully restored archived page-edge debug APK for existing diagnostic tooling. No command remains in flight.

## Two later short memory/timing runs, no emulator reboot

Ran two sequential existing 12-gesture WFWF captures with memory sampling on the archived debug app/startup-export test: direct-repeat-memory-wfwf-01 and -02. Both collection and sampled PSS policy passed. First source submission 1338.793 / 1455.9368 ms; native submission p95 8.1029 / 2.8826 ms. These are submission timings with sampling overhead, not physical display performance. Observed active PSS peaks 123469 / 133606 KiB. Post-close PSS 117420 / 117087 KiB versus pre-viewer 93431 / 93842 KiB, residual 23989 / 23245 KiB (about 23 MiB each). All recorded work counts, file leases, prepared pages and pending publications zero; decode workers and memory samplers terminated. No cache-equivalence, exact all-owned peak, full GL memory qualification or corpus credit is claimed.

Important limitation: instrumentation starts a new app process each time (4022, then 4418). These runs show continued good emulator timing without reboot and per-session cleanup, NOT accumulation-free sustained use in a single app process. Do not repeat this collector as a substitute for same-process long-use testing. Exact combined metrics and ownership/policy evidence: `.artifacts/viewer-focus-20260907/repeat-memory-summary.json`. Both commands are terminal; no build or measurement remains running. No source changes in this measurement step.
