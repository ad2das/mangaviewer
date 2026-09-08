# Viewer scroll verification, 2026-09-08

The zero-stutter result for prepared images is not yet established. The user explicitly
allows waiting for originals to arrive, including at a next-episode boundary. Those
waits are reported separately and are not treated as prepared-image scroll failures.
The same emulator, original images and fixed twelve documents are used. Personal
Google-account verification remains deferred by the user.

## Changes under verification

- Apply each input in order as soon as its source geometry is known. The production
  viewer no longer puts every input behind a pixel-readiness acknowledgement. The
  optional reducer barrier remains available to fixtures; movement revisions advance
  independently of it. No input-distance scaling, dropping or synthetic pacing was added.
- Keep the last submitted complete viewport until its replacement has all required
  textures. Autosave and bookmarks use the actually submitted source anchor, including
  when input has advanced beyond an original that has not arrived.
- Give the opening original and two nearby originals the first download window, then
  begin bulk original preparation as soon as the anchor bytes are verified. Authorize
  one known next episode while current originals download and queue its first two
  originals as soon as its authorization plan is available.
- Prepare up to four viewport lengths of tiles within the existing texture-memory
  budget. Align OkHttp's host admission with the engine's existing network admission.
  The engine still permits 16 network operations, 14 bodies and 12 background bodies.

Google sign-in and synchronization of recent/favorite series, bookmarks and exact
reading positions remain in this candidate, including offline merging, deletion
records and account-owner protection. No personal cloud data was used for tests.

## Completed measurements

`scroll-validation-full12` measured the candidate before the final change that queues
the adjacent episode's first two originals earlier. It contains 24,917 native frame
submissions and 24,848 applied-input measurements after the first complete viewport.

- All twelve traversed the current document in both directions. Eleven crossed a real
  next-episode boundary. Case 2 has no next episode; its unconditional boundary assertion
  failed and that failure is preserved.
- All twelve prepared the current originals within 15 seconds. Native submission P95
  was 8.06–9.97 ms, with no native submission taking 100 ms. These are native-call costs,
  not input latency or physical display cadence.
- Input acceptance to the first native completion covering the resolved movement
  revision still exceeded 100 ms for 61 input samples in six cases. The largest was
  570.5 ms. Most occurred during initial original preparation; case 3 also waited at the
  next episode boundary after the current episode's originals were ready.
- Apart from case 3's next-episode wait, the after-current-preparation populations had
  no measured input delay of 100 ms or more. All adapter input histories were conserved;
  no incomplete viewport followed the first complete viewport. Frame evidence was not
  lost and all work/storage ownership counters reached zero on closure.

The composition-latch analysis corroborates those waits where a latch timestamp exists.
756 input measurements lacked a latch timestamp for their first covering submission;
they remain explicitly unavailable. Neither these records nor native submission timing
prove physical scanout or a physical missed-frame rate below 1%.

The subsequent `next-original-pair` proves the adjacent-image request now begins as
soon as its plan is available: in case 3 it started 15 ms after plan observation,
424 ms before the first viewport. Its new image host still took about 1.1 seconds to
deliver the first body. The input reached that boundary before the body arrived and
waited up to 529.5 ms. Case 6 in the same batch had no input delay above 30.3 ms.
These two observations do not establish a general latency improvement.

## Current validation

The application, data and engine suites passed 105, 97 and 129 tests respectively.
Architecture validation, release lint and the release build passed. The native
readiness-position fixture previously verified that closing with the next original
blocked saves the last submitted position while ordered forward/reverse input has
already updated the logical geometry.

Current immutable binaries are in
`.artifacts/account-restore-20260908/same-avd-restart/next-original-candidate/`:

- Debug: `5c8146a831671b8a37a54a55317a81e2cde1e258ff962c6f9b416ca5cfbd78d0`
- Release: `22625d38c8849036565a2e601237aaf1d13cee1900a870760d4f88f76dd3ae91`
- Initial test APK: `9a30a441e1d59162462a1ae4a3623774708d4be120c173526a1308f541c00a65`

`scroll-final-full12` completed, with the database restored and verified. Case 1 exceeded
the 15-second preparation bound (183/202 originals) and the existing harness stopped
its traversal; this is a failed, incomplete case. The other eleven traversed both
document endpoints. Case 2 correctly ran without a next-episode crossing because its
verified manifest has no next episode; the other ten crossed that boundary.

The final batch did not reproduce the earlier rendering result. From case 4 onward,
native submission P95 increased to 28.26–35.36 ms, and native calls exceeded 100 ms.
Input delay also exceeded 100 ms in several already-prepared populations. In one such
case, input geometry resolved in 0.12 ms while its first covering native completion
took 123.6 ms. These results remain failures, not qualified smooth-scrolling results.

Immediately afterward, `scroll-final-empty-control` ran the same 1080×2138 native
submission path without images, download or decode work. Its 288 submissions over
five seconds had P95 23.28 ms and maximum 112.30 ms, with one call above 100 ms.
The previous empty control's P95 was 8.49 ms. This establishes that the environment's
empty rendering path also became slower; it does not assign every real-viewer delay
to that cause or exempt the application from the requested outcome. No emulator,
graphics, memory, security or network settings were changed to obtain a passing result.

The harness now retains a 15-second preparation failure while continuing full scrolling
under its separate 150-second bound. Its four existing protocol tests and native
readiness-position test passed together. `scroll-complete-traversal-case1` then
traversed all 202 originals in both directions and crossed the next-episode boundary.
All originals became ready at 18.17 seconds, so the test still failed its 15-second
criterion. Native submission P95 was 28.21 ms, maximum 126.27 ms; five input samples
after the first viewport exceeded 100 ms. No input after all current originals were
ready exceeded 77.28 ms to native completion, but three available composition-latch
measurements exceeded 100 ms. This is not a zero-stutter result.

`scroll-final-release-smoke` installed the exact release APK and opened, scrolled and
closed NTK case 4 and WFWF case 10 twice each. All four iterations kept the same main
process, displayed no viewer error, and closed normally; there was no fatal exception.
All eight screenshots were visually inspected. Each reopened 1080×2138 source viewport
was pixel-for-pixel identical to its previous stopped viewport. The original database
was restored and verified, and the debug APK was restored. This verifies the short
release lifecycle and exact resume position, not sustained frame cadence.

## Upload object allocation and compositor diagnosis

`scroll-work-diagnostic-case5` traced the unchanged candidate through the entire
199-original episode, reverse traversal and next-episode crossing. Matching the exact
frame-token field in each `engine_frame` marker aligned diagnostic and Perfetto clocks
within 0.08 ms. The longest unexplained input-to-render gap overlapped a 66.46 ms upload:
46.54 ms was in `glGenTextures`, 11.54 ms in `glGenBuffers`, and 4.50 ms in the encoded
buffer transfer. The initially generated substring-based join matched input revisions
as well as frame tokens; it was invalid and was replaced by the exact-token analysis.

The native uploader now reserves 32 texture names at a time and reuses one context-owned
pixel-unpack buffer. Reserving names allocates no texture image storage. Each transfer
still replaces the buffer data store with the original bytes; it does not overwrite a
mapped store while the GPU reads it. The GL implementation owns retirement of prior
stores. Unused names and the unpack buffer are deleted on context closure/recreation.
Texture image dimensions, scene coverage, texture budget and input handling are unchanged.
The data-store replacement semantics follow the
[Khronos buffer-data reference](https://wikis.khronos.org/opengl/GLAPI/glBufferData).

Debug APK `3c3e703a0a38f8d4a6dacdb91b2cb0a2940ba36c9f1d4795c1eba3dafcfb8493`
passed the architecture check and all 12 existing native owner/pixel/context-loss device
tests. Test APK `e881d6b126303094021c1828f70d100b78ecb17557a195b63f0aa00ce9c95c3b`
is unchanged. The same traced case (`upload-names-case5`) completed with database restore:

| Measurement | Before | After |
| --- | ---: | ---: |
| Upload count | 369 | 368 |
| Texture-name creation calls | 369 | 12 |
| Buffer-name creation calls, including static geometry | 370 | 2 |
| Total upload time | 4,809.20 ms | 3,402.73 ms |
| Upload P95 / maximum | 30.66 / 66.46 ms | 26.01 / 30.52 ms |
| Native frame-call P95 | 25.48 ms | 25.33 ms |

This is one paired traversal, not proof of a general percentage improvement. After
current originals were verified, 3,547 applied inputs had native-completion P95 35.02 ms,
maximum 78.14 ms and no delay of 100 ms. Available composition-latch measurements still
included three delays of 100.72, 133.10 and 100.98 ms; 2,017 input latch measurements
were unavailable across the full post-first-viewport population. These are not physical
display timestamps and do not establish zero visible stutter.

The corresponding inputs reached native completion in 60.30, 31.68 and 53.24 ms.
Perfetto shows overlapping Android compositor/hardware-composer work and system
navigation-region readback after submission. For the 133.10 ms input, consecutive system
composition operations took 56.61 and 34.12 ms, with a 46.95 ms region-readback driver
call. This supports a system-composition contribution to the residual latency; it does
not prove every untraced outlier has that cause. System settings and system-bar geometry
were not modified. The fixed twelve-case verification of this uploader completed.

`upload-names-full12` traversed both endpoints in all twelve cases and crossed the next
episode in all eleven cases that have one. Every input audit passed, no post-first-frame
viewport was incomplete, no frame evidence was lost, and ownership returned to zero.
Case 1 took 17.21 seconds to verify all originals, exceeding its 15-second criterion.
Across 25,263 applied inputs after current originals were verified, 68 native-completion
delays were at least 100 ms, with a maximum of 235.31 ms. Available composition-latch
measurements included 147 delays of at least 100 ms; 14,320 post-first-viewport latch
measurements were unavailable. These remain failed smooth-scrolling results.

The unchanged empty control immediately afterward had native-call P95 23.55 ms and
maximum 179.23 ms across 1,171 submissions. This shows a slow empty rendering path too;
it does not prove the cause of every application delay.

Release `f31ea8f8d8d690922067c2cb230c12b4a9810ca4a16a0ec3118a1da4caf0a7d9`
passed the four-iteration NTK/WFWF release smoke, with eight inspected screenshots,
pixel-identical saved/reopened viewports, no fatal exception, and verified database restore.
It is the uploader baseline release, before the allocation changes below.

## Scroll allocation reduction

Trace-only sections now identify graphics updates, scene offers, decode, uploads,
release and presentation polling. Disabled tracing performs no formatted recording.
In the paired case-6 traversal, the original graphics-update sections accumulated 6.77
seconds of main-thread elapsed time over 1,756 calls. Session snapshots copied all known page metadata repeatedly,
and rendering rebuilt unchanged tile request graphs on each input.

Session metadata now publishes a new immutable map only when metadata changes. Old
snapshots retain their original contents across later input, replacement and closure.
Tile demands reuse requests only while tile identity, access-plan reference, priority,
session generation and renderer epoch remain valid. The cache is bounded by current
planned tiles and captures the generation number rather than old runtime snapshots.
Renewed authentication plans rebuild the request even when image identity is unchanged.

Debug `be7c0ce4323b5a2fb04ea395d2fbd382da30c5f7f85216fea33496fa479d5dcf`
passed engine unit and architecture checks. Test APK remains
`e881d6b126303094021c1828f70d100b78ecb17557a195b63f0aa00ce9c95c3b`.
The same frozen case-6 traversal produced this diagnostic comparison:

| Measurement | Before | After |
| --- | ---: | ---: |
| Graphics-update calls | 1,756 | 1,803 |
| Graphics-update mean | 3.85 ms | 1.37 ms |
| Graphics-update P95 / maximum | 5.81 / 22.13 ms | 2.87 / 12.40 ms |
| Graphics-update running CPU mean / P95 | 3.69 / 5.42 ms | 1.29 / 2.56 ms |
| App-process concurrent GC count | 81 | 46 |
| Input-to-native P95 after current originals | 33.02 ms | 44.75 ms |
| Input-to-native maximum after current originals | 107.30 ms | 137.08 ms |
| Inputs at least 100 ms after current originals | 1 | 8 |

The running CPU measurement intersects each trace section with its thread's actual
scheduler running intervals, excluding sleeping and runnable time. It confirms less
CPU work in this pair, although it is not a general benchmark percentage claim.
The CPU allocation improvement did not produce a passing end-to-end latency result in
this pair. Six of the eight delayed inputs overlapped consecutive long driver submissions
and one upload. The other two covered the same frame: main-thread scene offers continued
in 0.01–0.08 ms, but the GL thread spent 84.42 ms preempted/runnable, with another
36.38 ms labelled sleeping, before a 4.10 ms native submission. A subsequent audit
found zero wake-up relationships in this trace: its configuration used `sched_wakeup`,
and the required wake-up transitions were not represented in the parsed thread states.
The labelled sleeping interval therefore cannot distinguish sleeping from wake-to-run
delay. The switch-derived preemption and CPU-running intervals remain available.
The corrected diagnostic follows the [Perfetto scheduling configuration](https://perfetto.dev/docs/data-sources/cpu-scheduling)
and records `sched_waking`. This is evidence of scheduling contribution, not proof that all latency is
outside the app. No new input gating or system configuration change was introduced.

`scroll-allocation-full12` completed all twelve forward/reverse traversals and the eleven
available next-episode crossings. Input receipt audits passed, no frame evidence was
lost, no viewport after the first complete one was incomplete, all eight ownership
counters returned to zero, and decode workers terminated. The original database was
restored and verified. Case 1 exceeded the 15-second preparation bound at 20.97 seconds;
the other eleven met that preparation bound. Six of twelve met the first-image
4-second bound. These readiness bounds are retained separately from the user's
permission to wait for source originals.

Across 24,505 native frame calls, case-level P95 ranged from 28.24 to 32.38 ms, and 22
calls reached 100 ms. After all current originals were verified, 24,615 applied inputs
included 36 delays of at least 100 ms, maximum 147.80 ms. Restricting this population
to the current episode leaves 24,213 inputs and all 36 delayed samples, so next-episode
download waits do not explain these failures. Input resolution in those samples took
0.05–3.52 ms; the remaining time spans native submission, queued work and scheduling.
Without a trace in this full batch, its non-native gaps cannot all be assigned to a
particular cause.

Available composition-latch measurements after current originals were verified numbered
11,212, including 135 delays of at least 100 ms and a maximum of 181.05 ms. Across the
post-first-viewport population, 14,219 input latch measurements were unavailable.
The native and composition results do not establish physical scanout timing or a
zero-stutter result.

Release `41e433ae456d4cea937455ae505a569fd6ce0e4422ccc48460f7cb20b322d2eb`
built successfully with debug/release lint and architecture checks. App/data/engine
unit results were 105/97/131 tests, with no failure, error or skip. The installed debug
candidate passed 13 native owner, context-loss and readiness-position device tests,
including saving the displayed position while a later original remains blocked.
The exact release's four-iteration NTK/WFWF lifecycle and resume smoke passed in one
main process without a viewer error or fatal exception. All eight screenshots were
inspected. Each reopened 1080×2138 source viewport was pixel-identical to its previous
stopped viewport. The original database was restored and verified, and the tested
debug candidate was restored. These lifecycle results do not qualify scrolling cadence.

No commit or publication has been made for this work.

## Wake-up trace audit and empty-path regression

The unchanged allocation APK was rerun as `scroll-waking-case6` with `sched_waking`
enabled. The parsed trace now contains 196,434 wake-up relationships in 685,710 thread
states. It also reports 47 `clock_sync_failure_no_path` errors, so it is not qualified
as complete cross-domain clock evidence. Exact app frame markers still align with the
recorded monotonic frame timestamps in the selected causal comparisons.

This run completed both endpoints and restored the database, but was much slower:
all 33 originals were verified at 7.17 seconds, while the first complete native return
took 39.48 seconds. The initial upload took 34.08 seconds, including 34.03 seconds in
the driver's `glGenTextures` call. Later native-call P95 was 104.57 ms, with maximum
524.47 ms. Among post-first-viewport inputs, 665 reached 100 ms, maximum 858.39 ms;
663 of those were within the current episode. These results are retained as failures.

For the delayed post-first-viewport inputs, the union of traced frame submission,
upload, release, owner-task and presentation-poll intervals leaves at most 4.06 ms
unaccounted for. For example, the 858.39 ms input overlaps consecutive long submissions;
one takes 346.43 ms, with 193.52 ms in driver synchronization and 151.61 ms in buffer
acquisition. An additional 183.59 ms gap before another frame contains a 181.46 ms
upload, including a 178.26 ms encoded buffer transfer. This run does not reproduce
the earlier unexplained long wait outside owner work.

The follow-up `scroll-waking-empty-no-trace` explicitly disabled Perfetto and performed
20 seconds of the same 1080×2138, swap-interval-zero empty submission path. With no
image downloading, decoding or tile uploads, its 466 submissions had P95 79.13 ms,
maximum 394.20 ms and nine calls at least 100 ms. The poor empty-path result therefore
persists without the additional tracing. It prevents a claim that image-work reduction
alone has met the requested 16 ms / no-100-ms result in the current environment.

Host memory counters near the control showed 2,236 MiB available and 107,436 page-ins
per second. A later idle snapshot showed 2,228 MiB available, no page-ins at that instant,
and a 0.73 GB emulator working set against 4.94 GB private memory. These system-wide
observations do not identify individual emulator hard faults or prove that paging
explains every driver delay. No application rendering behavior, AVD/GPU/RAM settings,
other user processes or security settings were changed for these diagnostics.

## Recurring degradation: third restart and same-boot investigation

The user asked why restarting repeatedly restores performance, not to exclude the
post-restart measurements. All measurements remain in the evidence. Three emulator
process restarts occurred on September 8 (05:29, 07:07 and 10:08 UTC).

Immediately before the third restart, the unchanged no-trace empty control recorded
450 frames, P95 112.78 ms, maximum 450.41 ms and 28 calls at least 100 ms. Restarting
the same AVD with the same command and unchanged APK, AVD configuration, display,
security state, database and cache inventory produced 1,200 frames, P95 0.7337 ms,
maximum 20.35 ms and no 100-ms calls. Preservation receipts are under
`.artifacts/account-restore-20260908/same-avd-refresh-2/`.

The following unchanged case-6 run completed 1,302 native submissions with P95
2.9614 ms and no 100-ms native calls. After all originals were prepared, 1,250 inputs
had P95 4.8667 ms, maximum 11.9506 ms and no 100-ms inputs. First complete viewport
still took 4.7176 seconds, exceeding the four-second requirement. This establishes a
runtime-state contribution but neither identifies its cause nor qualifies the full
12-case or long-duration performance requirement.

Investigation continues on boot `d8e6c21f-09fb-4185-9eda-e16c3e03ebeb`, emulator PID
14488, with no further restart. Resource snapshots are taken between measured
segments. The immutable allocation APK is used for empty-scene and repeated full
case-6 image traversals without Perfetto. Each actual image traversal uses an isolated
copy of the original database and the original database is restored and verified.
The first harness stopped after its first image traversal because a temporary working
directory name was reused; its `finally` restoration succeeded. The continuation uses
distinct working directories, without repeating or discarding the completed results.

The resource collector records Windows working set/private memory, process handles
and threads, system paging counters, Android graphics-service PSS and GPU counters.
Per-process GPU dedicated-memory readings have already exceeded the adapter-wide
reading (3.138 GB versus 1.675 GB at 10:24:07 UTC), so they cannot establish a GPU leak.
Microsoft documents a [GPU process counter issue that can resemble a leak](https://learn.microsoft.com/en-us/troubleshoot/windows-client/performance/gpu-process-memory-counters-report-wrong-value).
Adapter counters are retained separately; no diagnosis sums these incompatible values.

The installed emulator is 36.5.11. Google's [release notes](https://developer.android.com/studio/releases/emulator?hl=en)
list a host-memory leak on activity open/close as fixed in 36.4.9; that older report
does not prove that the present runtime has the same defect. Neither the emulator
version nor GPU driver was changed during this investigation.

### Same-boot results through 10:31 UTC

The completed controlled sequence contains fourteen 20-second empty-scene runs,
four full case-6 traversals and one full 202-original case-1 traversal. Every measured
segment used the same boot and immutable APK. The resource summary is
`.artifacts/account-restore-20260908/runtime-growth-summary.json` (28 boundary snapshots).

| Population | Samples | Observed P95 | Maximum | At least 100 ms |
| --- | ---: | ---: | ---: | ---: |
| Empty native submissions, 14 runs | 16,790 | 0.417–3.5602 ms per run | 68.0672 ms | 0 |
| Actual native submissions, 5 traversals | 10,041 | 2.0284–2.8627 ms per run | See per-run artifact | 0 |
| Inputs after all launch originals, 5 traversals | 9,542 | 3.7202–9.5705 ms per run | 29.4554 ms | 0 |

The first case-6 traversal still had six post-first-viewport inputs of at least 100 ms
before all originals were ready (maximum 233.7712 ms). These remain in its input
analysis. No input was unmatched. These are timing diagnostics without display tracing;
they do not establish physical presentation or the fixed-12 performance goal.

Host private memory increased from 2.60 GB before the sequence to about 3.7 GB during
the later six empty-only runs, but did not increase by a fixed amount at every lifecycle:
those six values were 3.700, 3.714, 3.679, 3.704, 3.661 and 3.786 GB. An idle snapshot
then dropped to 3.518 GB. The large case peaked at 4.189 GB in the sampled interval,
then fell to 3.645 GB after closing and idling. This shows retention and partial
reclamation, not proven unbounded leakage. SurfaceFlinger PSS changed from 24,893 to
26,155 kB; composer from 7,355 to 7,427 kB; allocator from 5,803 to 5,813 kB.

A read-only Windows sampler collected 100 one-second samples from 10:29:31 UTC,
beginning during the large traversal and continuing through its subsequent empty
control and idle period. Its maximum sampling work was 0.061 ms per sample. Emulator
working set stayed between 6.482 and 6.578 GB, available physical memory never fell
below 2.057 GB in these samples, and process memory priority was normal (5).
The sampled page-fault counter includes soft faults and cannot identify disk-backed
hard faults. This sequence did not reproduce the earlier collapse to a 0.73 GB working
set or the 79–113 ms empty-path P95. No additional reboot, build, heavy trace analysis,
memory-priority change, cleaner setting change or GPU setting change was performed.

**Cause remains unproven.** Runtime reset restores performance; the long-lived
emulator's greatly reduced resident memory prompted the controlled experiment below. This
controlled sequence weakens a simple large leak on every viewer lifecycle, but does
not exclude a slower leak, host memory pressure from the wider testing workflow,
graphics-driver state, or another long-duration trigger. The next degraded interval
needs process-specific hard-fault and graphics evidence before any reset; healthy
post-reset results cannot identify that missing cause.

### Controlled host-memory pressure: resident-memory collapse is not sufficient

Two additional same-boot controls explicitly tested the host-analysis/build hypothesis.
Windows tracing used a custom 64×64 KiB buffer profile with process/thread lifecycle,
loader, disk I/O and hard-fault events. The same recording configuration was active
before, during and after each comparison. No working-set purge or priority change was
requested. These controls do not qualify product presentation performance.

| Control | Empty native P95 | Empty native maximum | At least 100 ms |
| --- | ---: | ---: | ---: |
| Before retaining a parsed trace | 3.3081 ms | 27.0079 ms | 0 |
| 116.6 MB trace parsed and resident | 3.4655 ms | 57.8951 ms | 0 |
| After releasing trace processor | 3.3251 ms | 21.1949 ms | 0 |
| Before normal app lint build | 3.3695 ms | 25.4123 ms | 0 |
| Build complete, Gradle still resident | 3.3711 ms | 33.5993 ms | 0 |
| After stopping the owned Gradle daemon | 3.4168 ms | 26.5634 ms | 0 |

The trace processor's private memory was 217 MB. In that 77-second Windows trace,
all 10,850 decoded hard faults were associated with process lifetimes; none belonged
to the emulator. The artifacts are `same-boot-analysis-memory-control/`.

The next control reran the existing `:app:lintDebug` check once with `--rerun-tasks`.
It passed in 82.33 seconds, executing 124 tasks. During that ordinary build, the
emulator's working set **did** collapse: from a sampled maximum of 6.455 GB to
1.185 GB. Available physical memory briefly reached 17.3 MB. Nevertheless the two
post-build empty controls remained fast, including after the owned Gradle daemon was
stopped. Working set was still only 1.300 GB at the end of sampling. Thus low resident
memory alone does not explain the earlier sustained 79–113 ms empty-path P95.

The 156-second build trace recorded 95,327 hard-fault events with zero reported event
or buffer loss. Of these, 188 were unambiguously attributed to emulator threads:
5.69 MB read, P95 fault duration 0.160 ms, maximum 1.058 ms. The sum of their durations
was 19.243 ms across potentially overlapping threads, not a single wall-clock stall.
There were 61 unassigned system hard-fault events near thread-lifetime boundaries;
these remain explicitly unassigned. The artifacts are `same-boot-build-memory-control/`.

The decoder handles Windows thread-rundown records as inventories rather than thread
terminations. `tracerpt` could not decode the newer thread payloads, so their PID/TID
prefix is decoded according to Microsoft's [KernelTraceEventParser](https://raw.githubusercontent.com/microsoft/perfview/main/src/TraceEvent/Parsers/KernelTraceEventParser.cs).
The XML exporter reported a UTC offset one minute short; timestamps are corrected
against the ETL header's raw FILETIME anchor (an observed exact 60-second difference).
Hard-fault start times use their FILETIME payload directly. Integer-microsecond export
precision leaves a few sub-microsecond negative non-emulator duration residues; they
are counted in the analysis metadata. None of these diagnostics is treated as a
complete cross-device display-clock verification.

An additional source check found that Android's [EGL TLS destructor](https://android.googlesource.com/platform/frameworks/native/+/master/opengl/libs/EGL/egl_tls.cpp)
already invokes EGL thread cleanup at thread exit. The application's lack of an
explicit `eglReleaseThread()` call is therefore not sufficient evidence of this
recurring leak. No speculative renderer patch was made. Direct graphics-service FD
inspection was denied to the existing Android shell; the denied listings are not zero
FD counts, and security settings were not changed to obtain them.

### Longer recurrence capture, stopped at the user's request

At 11:02 UTC, `run-same-boot-soak.py` started a maximum-45-minute repetition of the
frozen twelve cases, still on the same boot and installed allocation APK. Each actual
traversal is followed by an empty control and resource snapshot. A one-second native
Windows memory sampler and the small-buffer hard-fault recording remain active.
The runner stops at the first native/current-ready-input 100-ms event, empty-path
P95 of at least 16 ms, empty 100-ms event or functional failure. It then preserves
that state and collects a bounded CPU/Android trace instead of rebooting. The original
database is restored in `finally`, including cancellation. The current journal is
`.artifacts/account-restore-20260908/same-boot-soak/stages.jsonl`; this paragraph is a
running protocol, not a claim that 45 minutes or twelve cases have passed.

The user redirected the task to application optimization at 11:04 UTC. The runner
acknowledged its stop file, stopped WPR, joined its sampler and restored the original
database with verified hashes. The first empty control passed; the interrupted first
traversal is not counted as a completed case. No recurrence investigation remains running.

## Opening download window experiment

`opening-anchor-comparison` compares the prior allocation APK with
`opening-anchor-candidate` (debug SHA-256
`1137316bfaf1ff0ed9d4cfd6afe2cd0076a6f994c4cc8d36db85aec7115166fe`).
Only bulk opening-body admission changed: the first anchor original and two neighbors
start together; bulk bodies begin when the anchor original is verified, before decoding
or the first swap. Next-document preparation, priorities, network limits and image
quality are unchanged. The engine unit suite passed, including a blocked-first-body
test that proves distant bodies wait and resume before any first-submission callback.

Four quick cold-original-cache captures ran on the existing boot, without Perfetto or
readback. Case 1's first complete native return changed from 4.805 to 4.057 seconds;
all 202 originals changed from 11.703 to 13.147 seconds. Case 6 changed from 3.658 to
3.768 seconds for first display and from 5.258 to 5.238 seconds for all 33 originals.
There were no incomplete source viewports, lost frame records or native calls of at
least 100 ms. This is a mixed two-case result, not evidence of a uniform improvement.
Manifest latency also varied between runs, so the entire first-display difference is
not attributed to the admission change. The full twelve-case traversal is being
recorded separately in `opening-anchor-full12`.

That complete run subsequently passed all twelve forward/reverse traversals and the
eleven available next-episode boundaries. All originals were verified within 15 seconds.
It recorded 25,263 native submissions, native P95 of 7.868–10.389 ms and no native call
of 100 ms. After current originals were ready, 23,607 applied-input measurements had
no latency of 100 ms; the maximum was 53.526 ms. Available composition-latch measurements
also had no such delay, with a maximum of 68.050 ms. Across the post-first-viewport
population, 751 first-covering-submission latch timestamps were unavailable and remain
unknown. All input histories, complete viewport coverage and closure checks passed.
First display was within four seconds in seven cases; five NTK cases took 4.134–4.224
seconds. This does not establish an absolute four-second guarantee or a physical
missed-frame rate below one percent.

## Per-tile validation allocation

Every constructed `EngineTileSpec` previously compiled `[0-9a-f]{64}` and allocated a
matcher. Scroll planning constructs these descriptors repeatedly. `isSha256Hex` checks
the same exact length and lowercase ASCII alphabet directly; stored-page and document
identities use the same validator. Constructor tests cover valid digits/letters, short
and long hashes, uppercase, non-hex characters, a non-ASCII digit and a newline.

`sha-validation-allocation` compares the actual old and new `EngineTileSpec`
constructors on the host JVM, with a volatile sink preventing escape elimination.
After warmup, the final 100,000-constructor pass allocated 115,200,000 bytes before
and 4,000,000 bytes after (1,152 versus 40 bytes per tile, 96.5% lower). This is a
constructor allocation measurement, not an Android frame-rate claim. The engine/API,
application, data and both provider test suites, architecture check, debug/test/release
builds and release lint passed. Actual traversal validation of the resulting APK is
recorded separately in `sha-validation-full12`.

That full run completed all twelve traversals and eleven available next-episode
boundaries, with verified restoration of the original database. It recorded 25,331
native submissions and 23,582 applied-input measurements after all current originals
were ready. Neither population had a 100-ms event; the latter maximum was 55.046 ms.
The available first-covering-frame latch maximum was 64.645 ms; 873 post-first-viewport
first-covering latch observations were unavailable, not successful or dropped frames.
First display met four seconds in ten cases; current originals met fifteen seconds in
eleven cases (case 1 took 15.798 seconds). The user subsequently explicitly accepted
loading-time compromise and prioritized elimination of even short scroll stutters.
The 100-ms threshold alone therefore cannot qualify this work as complete.

## Short scroll stalls and composition queues

`scroll-frametimeline-case6` captures the same APK and boot with a 16-MB Perfetto
configuration containing graphics, input, scheduling and SurfaceFlinger FrameTimeline.
It completes the full case-6 traversal, restores the original database and reports no
trace-buffer loss. Android's app FrameTimeline does not cover this SurfaceView path:
the trace contains only six unrelated app-window transactions, against 1,327 owned
native submissions. An app jank count of zero from that table would be invalid.
See the [Perfetto FrameTimeline limitations](https://perfetto.dev/docs/data-sources/frametimeline).

The worst available prepared-input latch latency is 53.869 ms. Another input, sequence
325, finishes native submission in 4.116 ms but latches in 50.173 ms. The trace directly
identifies its buffer (159): queueing finishes at +3.718 ms, but BLAST acquires the
buffer only at +30.823 ms, after the previous buffer is released. SurfaceFlinger
receives its transaction at +30.928 ms and latches it at about +50.2 ms. Buffers 160
and 161 are superseded before acquisition; the next acquired buffer is 162. The
SurfaceFlinger cycle around +34 ms is rescheduled. This establishes queue and
composition delay in this interval, rather than a 50-ms application draw operation.
It does not establish a single cause for every short stall.

`analyze-active-latch-gaps.py` now preserves two scopes: all known successive
movement-changing latches within injected gesture down/up windows, and a secondary
subset whose accepted movement samples remain at most 25 ms apart. The latter omits
some delayed input delivery, so it must not replace the wider gesture scope. The
wider scope also includes injection pacing and does not identify every long interval
as an application fault. Both retain unknown timestamps separately and neither is a
physical panel missed-frame-rate measurement. For the unchanged twelve-case APK,
known latch intervals within gestures reach 60.996 ms, and even the continuous-input
subset reaches 51.753 ms. These observations rule out claiming zero short stutter.

A bounded 512-pixel tile-height experiment is recorded in `small-tiles-comparison`.
It preserves the original raster scale, crop geometry, input handling and four-viewport
preparation distance, while bounding each GL upload more tightly. Its additional draw
calls and decode work did not establish a scrolling improvement, and it was reverted.
The comparison crossed a change in composition cost: SurfaceFlinger presentation
averaged 9.39 ms before and 27.33 ms afterward. It therefore does not isolate tile size
as the cause of the overall slowdown. Main-thread graphics-update P95 increased from
1.17 to 1.84 ms and prepared-phase uploads increased from 83 to 213. The restored
2048-pixel version still recorded a 174.44-ms prepared-input latch latency; its empty
control recorded native P95 23.37 ms and maximum 155.58 ms. Neither result passes.

## Larger tiles and an unchanged-APK control after updater restoration

The restored in-app updater was included in debug APK
`2569f3e46501a01f877bc73af7f0bc9e91ab93cde55c9e263b4e32b1c722d9c4`.
`post-update-frame-trace`, `large-tiles-comparison`, and `large-tiles-restored-control`
run the same frozen case 6 in that order, without rebooting the emulator or changing
its configuration. Each completes both endpoints and the next-episode boundary,
verifies all 33 originals, preserves every input, has no incomplete post-first-full
viewport, and restores the original library database with verification.

The sole experimental production change was target tile height 2048 → 4096. It
preserved raster width, original bytes, crop/placement rules, texture budget and
four-viewport preparation distance. Prepared-phase measurements were:

| Measurement | 2048 before | 4096 | 2048 restored |
| --- | ---: | ---: | ---: |
| Native frame submissions | 1299 | 1337 | 1296 |
| Native submission P95, ms | 25.402 | 25.145 | 25.325 |
| Image uploads | 82 | 44 | 84 |
| Upload P95, ms | 24.945 | 28.519 | 25.993 |
| Graphics-update P95, ms | 1.197 | 0.912 | 1.183 |
| Applied-input completion maximum, ms | 67.819 | 77.890 | 102.529 |

Larger tiles reduced calls and planning work but increased individual upload cost;
native submission remained above 16 ms. The change was reverted. These traversals
do not qualify the full twelve-case goal or the physical missed-frame rate. Known
movement-changing latch intervals within injected gestures still have P95 about
53–54 ms in all three runs. Unknown latch timestamps remain unknown.

The restored control's input 770 resolves in 0.085 ms but reaches its first covering
native completion after 102.529 ms. Frame 595 starts 25.968 ms after input acceptance
and spends 76.214 ms in `eglSwapBuffers`. Its thread sleeps for 74.074 ms after
`rcCreateSyncKHR encode` returns and before `queueBuffer` begins. No image upload
occurs in this interval. This directly rules out image-transfer size as the cause
of this particular 100-ms event; the sleeping operation itself is not yet identified.
Evidence is in `worst-prepared-native-stall.json`, `worst-native-frame-children.json`
and `worst-native-frame-thread-states.json` inside `large-tiles-restored-control/case-06`.

## Bounded driver syscall diagnostics

`empty-native-sync-control` and `empty-native-stream-control` use the existing empty
scene control and attach the system strace to only the app's GL owner through
`run-as`. No root, SELinux, GPU or emulator setting is changed. Raw syscall arguments
are recorded without decoding read/write payloads. The second attachment starts
four seconds after thread discovery and lasts twelve seconds.

These are intrusive diagnostic runs, not performance qualification. In the second,
450 completed retry groups on the observed `/dev/goldfish_pipe_dprctd` descriptor
end in a 24-byte response after EAGAIN reads. Retry-span P95 is 22.036 ms and maximum
29.039 ms; 193 groups exceed 16 ms. Individual reads remain below 1.64 ms. The
observed goldfish-sync ioctls are below 0.44 ms, so that syscall is not established
as the cause of ordinary long submissions. An earlier attachment that includes
startup observes long hwbinder ioctls; it must not be treated as steady scrolling.

The observed retry pattern is consistent with the public gfxstream
[QemuPipeStream response-reading loop](https://github.com/google/gfxstream/blob/main/guest/OpenglSystemCommon/QemuPipeStream.cpp).
The public [EGL implementation](https://github.com/google/gfxstream/blob/main/guest/egl/egl.cpp)
creates its host completion fence before queueing a window buffer. These references
describe implementation paths; they do not establish the exact binary revision in
the installed system image or explain every rare stall. The 74-ms sleep in the real
scroll trace remains distinct from the measured repeated-read waits.
