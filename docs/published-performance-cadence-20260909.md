# Published APK cadence investigation — 2026-09-09

The performance objective is **not qualified**. Fast native submission calls do
not establish a missed-frame rate below 1% or exclude pauses in visible motion.

## Published APK control

The fixed 12 cases were attempted with published version 2147000006, SHA-256
`8e63aa56ad9b366cbbfe72ec15608f5370a934ac8b4055a0b535d225e1ac4d9f`.
The original emulator boot, AVD settings and network were retained. Each case
used real catalog navigation and the whole-preparation traversal protocol, with
no trace or pixel readback. Original database files and settings were verified
byte-for-byte, including ownership and modes, after every case.

Artifact root: `.artifacts/account-restore-20260908/published-performance-20260909`.
The original cohort is `fresh12-summary.json` and `trace-ui-fresh12-caseNN-01`.

| Case | First complete native submission from tap, ms | Prepared native call P95, ms | Collection |
| --- | ---: | ---: | --- |
| 1 | 2646.27 | 3.04 | Complete |
| 2 | 3101.69 | 3.07 | Complete |
| 3 | 2701.43 | 3.15 | Complete |
| 4 | 2652.73 | 3.96 | Complete |
| 5 | — | — | Episode tap did not open viewer |
| 6 | 2061.97 | 4.44 | Complete |
| 7 | 1003.21 | 3.07 | Complete; restoration command error below |
| 8 | 1856.95 | 7.15 | Complete |
| 9 | 1267.12 | 5.91 | Complete |
| 10 | 1124.16 | 7.14 | Complete |
| 11 | — | — | Episode tap did not open viewer |
| 12 | 1274.93 | 11.10 | Complete |

The ten captures contain 17,571 prepared complete-viewport submissions; the
largest native call was 43.20 ms. These are submission measurements, not physical
presentation timestamps. Complete renderer and input histories were verified.
Case 7's wrapper failed while renaming the restored settings file; the independent
subsequent comparison nevertheless verified the exact original data and settings.
The wrapper error remains in the cohort.

## Motion evidence and navigation

`EngineCapturedMotion` drains the existing production motion and closed-gesture
rings during traversal, retaining their complete history for export after closure.
It rejects overwritten history, missing ordinals, or loss of an earlier gesture
window from the bounded ring. The app's scheduling and ring capacities are unchanged.

`verify_engine_motion_observations.py` reports VSYNC, motion callback, and native
submission intervals separately. A motion callback may acknowledge deferred input;
it does not prove visible movement. VSYNC samples are assigned to the gesture in
which their callbacks ran because VSYNC timestamps can precede gesture starts.
Both window edges and empty windows remain visible in diagnostics.

Saved failure screens show the intended episode rows for cases 5 and 11. They
do not establish why the initial taps failed. The test now observes a stationary
row for 250 ms before its single measured tap and records its bounds. It does not
retry that tap or wait for image preparation. This is a changed navigation
procedure, so these reruns are not replacements for the original cohort failures.

Both reruns with the same published app and test APK
`63df6d46097517356cc8c81e12463f78d3a47f7595dda57b0a80e3158737ac8d`
completed navigation, whole traversal, renderer/input history verification and
original-state restoration. Case 5 measured first submission 3313.73 ms and
prepared native P95 8.74 ms; case 11 measured 1584.75 ms and 9.27 ms. Prepared
cadence remained above the required missed-frame ratio. The raw motion gaps over
100 ms overlapped verified clamped inputs at document boundaries; the corresponding
prepared native submission history had no gap that long. Boundary holds must not
be described as visible motion without checking the input and scene evidence.

## Release-frame defect

The diagnostic trace `trace-ui-motion-case11-trace-01` confirms an empty release
frame. For example, motion ordinals 133/134 use VSYNC timestamps
4797467058332/4797500391664 ns. `ACTION_UP` was handled at 4797475566900 ns.
The intervening frame runs the animation callback but performs no graphics update;
it only starts the fling. The next frame finally advances it. Trace capture adds
substantial overhead and reports three negative-timestamp drops, so its aggregate
timings are not used as an untraced performance result.

The candidate starts fling integration at the actual release event timestamp and
advances it on the current frame when that frame follows release. An event later
than the current VSYNC retains its origin until a future frame. Pointer deltas
are still drained first, and the existing decay law, image quality, input ledger,
renderer and saved-position path remain in use. Tests verify the initial partial
step, cumulative distance without duplicate elapsed time, future-origin handling,
and cancellation at a boundary. The full app unit tests and debug/test APK builds
pass. Untraced matched runs retained complete input and renderer histories. Case 11's
prepared native submission missed ratio changed from 2.70% to 1.60%; case 5 still
measured 3.39%. The identified release transition gap disappeared in case 11's
motion sequence. This fixes that defect but does not meet the full performance goal.

## Unbuffered input experiment and remaining bottleneck

The candidate additionally requests unbuffered touchscreen delivery for each
gesture. Its own pointer ledger still preserves all samples and drains them on
VSYNC. Initial case 5/11 reruns measured prepared native submission missed ratios
of 1.76%/1.43%. A subsequent fixed-corpus attempt stopped after case 9 at the
user's request to focus on a larger improvement. Every attempted case restored
and independently verified the original database, settings and published APK.

| Case | First complete native submission, ms | Prepared native call P95, ms | Prepared native submission missed ratio |
| --- | ---: | ---: | ---: |
| 1 | 3425.28 | 8.56 | 2.46% |
| 2 | 3669.10 | 9.52 | 1.95% |
| 3 | 3629.04 | 10.06 | 12.28% |
| 4 | 3656.55 | 9.87 | 1.52% |
| 5 | 2893.21 | 8.92 | 2.39% |
| 6 | 3048.67 | 10.17 | 1.59% |
| 7 | 1395.69 | 11.36 | 2.67% |
| 8 | 1870.54 | 9.03 | 0.38% |
| 9 | 1220.08 | 12.37 | 7.38% |

Cases 1 and 4 also failed the existing original-preparation deadline. Cases 10–12
were not attempted in this cohort. These ratios retain window edges and clamped
states; they neither independently prove physical jank nor establish smooth visible
motion. Only individual gaps with matching input and scene evidence can be
classified as boundary holds. The goal remains unmet.

An earlier subset comparison showed markedly longer headers-to-body-complete
times. That subset must not be treated as the median of the full unbuffered
cohort (197.6 ms). In the first opt-in diagnostic, matching 337 original hashes
gave medians of about 39 ms in the earlier cohort and 813 ms in the diagnostic.
This interval includes
consumer scheduling and processing, so it cannot identify remote-server latency
on its own. The opt-in `--http-read-timings` diagnostic partitions body demand,
read admission, dispatch and issued-read-to-callback time. It separately reports
callback queue wait, which overlaps the issued-read interval and must not be added
to that interval. No timing session exists when the observer is absent. Diagnostic
results are exported after closure and do not earn performance qualification.

Two opt-in case 1 diagnostics reproduced the preparation deadline failure. The
first contained 336 completed image streams: 43.45 MB in 19,343 chunks. Summed
parallel-request body time was 9.33% consumer wait, 0.63% admission, 7.48% dispatch,
and 82.56% issued-read-to-callback wait. The four intervals exactly partitioned
each completed body's measured wall interval. The overlapping callback queue
total was reported separately. The second run had 412 completed image streams,
with a similar partition and a different median body time. These records do not
distinguish remote delay from scheduling inside the network engine.

Matching by original SHA-256 instead of URL is necessary: the recorded replica
host changed between runs while the original bytes matched. Therefore these
cohorts cannot attribute all transfer-time variation to touch delivery. The
diagnostic helpers retain normal callback serialization and buffer ownership.

## Bounded input replay

Independent review identified and direct analysis confirmed a large synchronous
replay in `trace-ui-unbuffered12-case03-01`: input revision 790, geometry revision
50, 488 terminal receipts (268 applied and 220 clamped), with 116.3762 ms between
the first and last resolution timestamps. The corresponding submission gap was
120.55 ms. This is an observed wall span, not a measurement of CPU time; it cannot
be dismissed as entirely clamped input.

`EngineSession` now yields ready FIFO replay after at most 32 inputs or after
observing 1 ms of elapsed work. A geometry operation remains indivisible, so this
is not a hard maximum duration for one dispatch. `EngineSessionRuntime` posts a
generation-scoped continuation after a real suspension. Navigation, background
transition and closure cancel the posted job. Content or presentation blockers
do not schedule a polling loop. New input stays behind the existing queue.

Tests compare all 488 mixed-direction receipts and the exact final source anchor
against sequential processing, exercise elapsed-time yielding and stale
generation rejection, and verify interleaved input plus closure. Runtime tests
verify that unrelated owner work runs before replay finishes, that replay resumes
without another input event, and that closure cancels the posted continuation.

The first untraced candidate case 3 run completed with 622 accepted inputs, zero
cancelled inputs, complete input/renderer histories, and verified original-state
restoration. The largest identical-state receipt batch was 27; its longest observed
resolution span was 6.7706 ms. First complete native submission took 3797.78 ms;
prepared native P95 was 11.47 ms. Prepared native submission missed ratio remained
8.47%, and applied-input latency P95 across the whole run was 5.53 seconds. This
removes the demonstrated unbounded replay behavior but does not solve loading
delays or establish the full performance goal.

The contemporaneous control used the same instrumentation APK with HTTP timing
disabled. It produced 633 accepted inputs, zero cancellations, and complete
histories. Its largest state batch contained 353 terminal receipts with a
38.0553 ms observed resolution span; first native submission took 3765.26 ms,
prepared native P95 was 11.28 ms, missed ratio was 6.53%, and applied-input P95
was 6.00 seconds. Thus the first candidate/control pair does not demonstrate
better overall cadence despite the shorter replay batches.

After extracting the continuation owner and replay budget to satisfy the unchanged
400-line architecture limit, a second candidate run accepted 650 inputs without
cancellation or history loss. Its largest batch had 25 receipts and its longest
observed resolution span was 3.5981 ms. First native submission was 4295.06 ms,
prepared native P95 11.31 ms, submission missed ratio 11.76%, longest edge gap
115.90 ms, and applied-input P95 5.06 seconds. It fails the first-image, missed
frame, and freeze targets. Neither callback nor submission cadence proves physical
presentation. Original app data and settings were independently verified after
restoration. Architecture verification and 139 engine tests passed before this run.

The first candidate's sequence 185 illustrates why input age is not equivalent
to replay CPU cost: accepted at tap +3458.54 ms, it waited behind earlier inputs,
partly advanced at the next episode's page 4, and finished at tap +9245.60 ms.
The next document began at +3480.30 ms, its body completed at +3948.27 ms, and
its authorized plan was observed at +6339.31 ms. Page 5's original completed at
+8608.16 ms. These observations span several dependencies and do not attribute
the whole input delay to any one request. The current episode's authorized plan
was already available at +2242.25 ms, but adjacent authorization waited until
the legacy reading anchor gained its original dimensions. That avoidable
serialization was the next targeted change.

Adjacent document read-ahead now uses the known target episode while a legacy
anchor awaits original dimensions. Nearby original read-ahead also uses the
unresolved legacy page without changing its exact saved position. The regression
test first failed on adjacent authorization, then on neighboring preparation,
and passed after the corresponding changes; it also checks the restored source
anchor and released work ownership. The unchanged architecture gate and all
140 engine tests passed. The app opening-preparation path already downloaded
the first three originals concurrently in these captures, so that behavior is
not credited as a new end-to-end improvement.

`trace-ui-legacy-prefetch-case03-01` confirmed the scheduling change: the opening
plan was observed at tap +2704.99 ms, and the next document started at +2779.59 ms
(74.60 ms later), compared with +1338.80 ms after the opening plan in the prior
refined run. The next authorized plan arrived at +5716.07 ms, versus +6859.83 ms
in that prior run. The run reached a further adjacent episode and accepted 781
inputs without cancellation or history loss. It still failed the performance
targets: first native submission 4415.56 ms, prepared native call P95 12.07 ms,
submission missed ratio 12.27%, longest window edge 148.81 ms, and whole-run
applied-input P95 6.30 seconds. Its longest observed replay span was 12.25 ms.
Thus the removed serialization is demonstrated; overall latency or cadence
improvement is not established by this comparison. Every completed capture
restored the published app, and independent byte/metadata verification of the
original database and settings passed afterward. No AVD settings were changed.

## Rejected bounded transport read-ahead experiment

A separate candidate accumulated small HTTP pulls into two reusable 128 KiB heap
blocks while preserving the existing direct-buffer bridge, callback serialization,
pool size, request limits and original bytes. Five tests covered partial consumption,
the two-block backpressure bound, EOF versus failure, cancellation, and actual bridge
buffer release. Existing HTTP tests and 130 app tests also passed. The experimental
producer used the existing shared callback pool; it added no worker threads.

The fixed case 3 comparison then ran control/candidate/candidate/control with the
same instrumentation APK (`e1810fe0…`), unchanged emulator boot, and restoration
plus independent database/settings verification after every run. All 14 opening
original SHA-256 hashes and byte counts matched. The replica URL changed in the
last control, so the experiment cannot assume one invariant remote route.

| Run | Body median for the same 14 originals, ms | First native submission, ms | All opening originals verified, ms | Native call P95, ms | Submission missed ratio |
| --- | ---: | ---: | ---: | ---: | ---: |
| Control A1 | 1122.45 | 4767.44 | 7104.19 | 26.33 | 26.74% |
| Candidate B1 | 517.38 | 4361.87 | 5964.81 | 29.29 | 30.47% |
| Candidate B2 | 1155.35 | 4757.04 | 7001.90 | 25.64 | 25.84% |
| Control A2 | 526.04 | 4553.55 | 6073.01 | 23.81 | 22.04% |

The initially faster candidate result did not reproduce; the final control was
similarly fast without the extra buffering. No overall improvement or performance
qualification is established. B2 also reached four authorized episodes after the
tap, while the other runs reached three, so whole-run input ages are not a clean
isolated transfer comparison. The experimental implementation was archived under
`read-ahead-rejected-source` and removed from the app. The verified FIFO replay and
legacy adjacent-preparation fixes remain. These captures also show a native-call
regression in both control and candidate compared with earlier runs of the same
control APK; its source remains unresolved and requires native trace attribution.
