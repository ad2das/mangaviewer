# Qualification implementation and evidence ledger

Status: **not qualified; 0/200**. The approved acceptance policy is accurate completion plus empirically demonstrated best feasible performance on emulator-5554 / MangaViewerApi35 using wired Ethernet. No commit or push is authorized by a partial diagnostic result.

## Implemented contracts

- Page alternatives belong to one page; viewer identity, page order, document origin, cookie batches and ACK callbacks retain request ownership. The document parser decodes Flight chunks without treating unrelated DOM images as a complete protected manifest.
- The session preserves deep saved positions and ordered input reversals. One content actor owns forward manifest demand, retries, cancellation and resource reservations. Memory budgets depend on physical RAM, with distant resources reclaimed first.
- Renderer context recovery invalidates texture epochs. Late old-epoch textures cannot reenter the active scene. Each terminal frame retains its submission epoch and is recorded even when callbacks resolve out of order.
- Displayed rows carry exact page/range upload provenance. A shared texture key cannot establish another page's identity. EGL latch or rendering completion timestamps never become physical display timestamps.
- The corpus harness uses real library UI entry and gestures, a fresh fixed random sample per attempt, complete source-row coverage and independently recorded episode order. It stops on the first failure. Raw activity diagnostics explicitly award zero corpus credit.
- The memory verifier aggregates ActivityManager-owned processes, including attributed WebView processes. It applies the approved RAM-dependent active increase and like-for-like post-close residual policy. Android's ICU regex implementation exposed a brace escaping error in the ownership parser; the correction passed the subsequent device regression.
- Complete cached episodes retain an ordered, checksummed manifest and every normally verified body's SHA256, length and dimensions. Reopening validates all bodies and pins immutable inodes through read-only file descriptors until pipeline close. Partial, changed or unavailable cache entries fall back to current source resolution before presentation; a live pinned episode cannot silently fetch replacement positional pages.

## Evidence obtained

All paths below are workspace-relative. Raw evidence is retained under `.artifacts`; no final sample caches were injected or cleared.

| Evidence | Result and limit |
| --- | --- |
| `.artifacts/qualification-integration-20260905/full-gate-3.log` | Earlier integration candidate passed lint, release build, app unit tests and debug/instrumentation builds. Later fixes require a new final gate. |
| `.artifacts/qualification-integration-20260905/native-lifecycle.txt` | Native pixels, context recreation, HOME/close and CPU decode format checks passed (4 tests). |
| `.artifacts/qualification-integration-20260905/source-cookies.txt` | Source browser document/callback ownership checks passed (10 tests). |
| `.artifacts/qualification-integration-20260905/qualification-regressions-v1.txt` | 25/26 device regressions passed, including injected upload/draw GL context loss recovery and replacement pixels. The one memory-parser ICU failure is retained. |
| `.artifacts/qualification-integration-20260905/diagnostic-build-7.log` | App unit tests, debug APKs and unchanged architecture quality gate passed after provenance, terminal ordering and GL recovery fixes. |
| `.artifacts/qualification-integration-20260905/full-gate-4.log` | All module tests, lint, debug/release builds, instrumentation APK and architecture gate passed for the candidate containing the optional static geometry experiment. |
| `.artifacts/qualification-integration-20260905/static-pixels-memory-v1.txt` | Seven device tests passed, including static source rows, context recreation, injected GL loss and corrected memory parsing. |
| `.artifacts/qualification-integration-20260905/static-sentinel-pixels.txt` | Full-viewport static/streaming pixel equality passed. A verified different sentinel image between modes prevents stale-frame false positives. |
| `.artifacts/qualification-integration-20260905/full-gate-6.log` | All module tests, lint, debug/release builds, app/source instrumentation APKs and architecture gate passed. Subsequent timing-anchor and cache-resume changes require a new final gate. |
| `.artifacts/qualification-integration-20260905/promoted-static-regressions.txt` | 31 device regressions passed after static geometry became the production default, including pixels, context recovery, motion timing and evidence parsers. |
| `.artifacts/qualification-integration-20260905/source-cookies-v2.txt` | Rebuilt source document/callback ownership checks passed again (10 tests). |
| `.artifacts/qualification-integration-20260905/session-memory-195423798235100` | Actual session memory-pressure regression passed. Three trim cycles retained exact visible pixels and saved offset 137 while reducing resident textures from 10 to 3. All terminal fetch/decode/upload/manifest/retry/retiring/resident counts were zero. Comparable post-close PSS rose from 81,184 to 81,934 KiB (750 KiB), below 64 MiB; this fixture does not replace live-corpus memory checks. |
| `.artifacts/qualification-integration-20260905/full-gate-7.log` | Whole-module tests, lint, debug/release and architecture passed in 87 seconds. The initial hard-link implementation subsequently failed the actual Android cache-resume regression because SELinux denied link creation; that design was replaced without changing device security. |
| `.artifacts/qualification-integration-20260905/cache-resume-device-v2.txt` and `complete-resume-197093771440700` | Four device tests passed with read-only descriptor pins: exact saved pixels/offset, no extra source prepare/manifest/fetch calls, zero remaining body descriptors and leases after two closes; three screenshot-binding regressions also passed. This is functional evidence, not a paired live startup timing claim. |
| `.artifacts/qualification-integration-20260905/full-gate-8.log` | Descriptor-based candidate passed all module tests, lint, debug/release and instrumentation builds, and the architecture gate in 76 seconds. `git diff --check` also passed. No final 200-episode qualification or push is claimed. |
| `.artifacts/qualification-live-20260905/ntk-diagnostic-v1` | Harness thread-access defect; original diagnostic failed and received no credit. State reads moved to main with original exception evidence retained. |
| `.artifacts/qualification-live-20260905/ntk-diagnostic-v2` | Exact episode `/webtoon/57451201/jjaptoon-1341148`: all 132 raw pages verified; the 60-second diagnostic reached page 96 and did not complete. Native render p95 35.9935 ms; motion gap 83.33333 ms and missed ratio 0.00913075. These are not independent physical display results. |
| `.artifacts/qualification-live-20260905/wfwf-diagnostic-v1` | Exact `comic:10007` / `28` failed before a manifest arrived, with connection reset/DNS/TLS errors. During-run screenshot contains the failure. No credit. |
| `.artifacts/qualification-live-20260905/wfwf-diagnostic-v2` | Updated origin loaded the exact 40-page episode and its next manifest without starting the NTK browser. Full-row traversal remained incomplete at the 180-second diagnostic limit. Native render maximum 1328.9799 ms and motion gap 1108.872304 ms remain under investigation. |
| `.artifacts/qualification-live-20260905/wfwf-diagnostic-v3` | Diagnostic passed in 52.965 seconds, with all 40 requested page rows in the navigation recorder. Native render p95 32.4233 ms, maximum 424.401 ms; motion maximum 551.4406 ms and missed ratio 0.0196616. External display linkage still lacks the first page's top 101 rows. No corpus credit. |
| `.artifacts/qualification-live-20260905/ntk-diagnostic-v4` | Diagnostic passed in 120.231 seconds, with all 132 requested page rows in the navigation recorder. Native render p95 31.4675 ms, maximum 86.7113 ms; motion maximum 163.3442 ms and missed ratio 0.00756287. External display linkage still lacks the first page's top 198 rows. No corpus credit. |
| `.artifacts/qualification-live-20260905/ntk-diagnostic-v5` | Diagnostic failed its 150-second traversal limit: page 4 rows 0–423 remained unvisited in this attempt. No substitution or credit. The separate metadata buffer produced no global continuity findings, and native/trace clock brackets intersected at [-4.9, +4.8] microseconds without missing submission bridges. Native render p95 31.5462 ms; display-linked row coverage remained 127/132 pages. |

## Reproduced rendering improvement

The native trace identified repeated emulator `glBufferDataSyncAEMU` work during geometry submission. The candidate replaces per-frame geometry buffer transfers with one immutable unit quad and the same calculated float endpoints passed as uniforms. Original texture content and source sampling remain unchanged.

The comparison policy was written before measurement at `.artifacts/qualification-display-20260905/static-quad-comparison/policy.json`. Five pairs alternated ordering, used the same installed APK (`41da8e4a2dd5e4374380b4d6084dcb6494785afe56050b1cd0b3a9b2dcbef00f`), and retained all submitted terminal frames over the full prescribed experiment. No builds or trace analysis overlapped these measurements.

All five pairs improved native render p95. Streaming trial p95 values ranged from 29.6468 to 37.2577 ms; static values ranged from 25.6098 to 26.0974 ms. The paired mean difference was -8.27698 ms, with a prespecified 95% t interval of [-13.02371, -3.53025] ms. The interval excludes zero, so the policy's extension to ten pairs was not triggered. Raw trials and `five-pair-analysis.json` remain alongside the policy.

This establishes an improvement in this native rendering metric. It does not establish the 16 ms goal, physical presentation timing, absence of maximum stalls, or a physical lower bound. Static geometry is now the production default; final qualification remains outstanding.

## Unresolved display measurement

SurfaceFlinger reports `PresentFences=false` on this emulator. Android 15's `PresentFenceSignaled` trace can therefore be a synthesized HWC/VSYNC time; its label alone is insufficient proof. The EGL timestamp path reports latch/unavailable events.

Independent emulator RGB capture is an observation upper bound. SDK `timestampUs` describes estimated generation, not display. The capture's actual row orientation differs from its SDK declaration, and the analyzer records the explicitly selected decoder model. Host and guest wall clocks also differ: they must be bracketed, not assumed equal.

The low-resolution V2 calibration independently identified 121 of 143 native scenes. Earliest RGB receipt bounds were p95 212.2025 ms and maximum 339.3054 ms, including capture and transport. Missing scenes and incomplete clock correlation prevent qualification. Neither this experiment nor a failed optimization attempt proves a physical limit.

New native traces bracket the sampled monotonic clock between a `viewer_clock` parent begin and its `viewer_swap` child begin. The verifier intersects actual brackets instead of assigning a fixed 100-microsecond allowance. Recorded app PID/package anchor trace ownership even when Perfetto retains the process's pre-specialization zygote name. Old traces lacking these brackets remain incomplete.

V4 corroborated all 116 BLAST/PFS-linked buffers against independent RGB pixels. Its native/trace clock bracket was [-5.1, +5.1] microseconds; host/native uncertainty remained 36.2635 ms. Four additional RGB scenes had no PFS event at the tail. These results do not turn PFS into a physical timestamp.

The NTK V3/V4 traces have unresolved midstream process-tree continuity flags; ftrace and SurfaceFlinger streams have no such flags. Wire-field order identifies flags injected by the tracing service. Perfetto v46 can lose a sparse writer's prior chunk metadata when dense streams overwrite already-read chunks, so the flags alone do not establish payload loss. They still prevent a continuity pass. The separately reported 134 clock conversion errors were unstable, unused emulator TSC snapshot entries, not graphics event timestamps.

With no active trace, only `traced_probes` was restarted to request 8 MiB shared memory; `/proc/<new PID>/maps` confirmed exactly 8,388,608 bytes. This did not eliminate the V4 process-tree flags. The AVD RAM and network were unchanged. `.artifacts/qualification-display-20260905/producer-buffer-control/result.json` records this experiment. The next capture isolates process metadata in a separate 4 MiB buffer so dense graphics events cannot evict its writer history; its effectiveness remains to be measured.

Submission timestamps now use exactly the same pre-JNI clock sample as native latency. Queue and preparation intervals receive separate trace markers. This repairs an interval bookkeeping defect; it is not a measured performance improvement. Existing final screenshots have reader chrome covering the missing top rows and lack capture intervals, so they cannot retroactively close the display-coverage gap.

## Remaining qualification work

Complete-cache resume validation, complete display evidence, remaining same-condition paired performance comparisons, the full 200-episode attempt, final whole-project checks and submission are outstanding. WFWF live access is restored at origin 492, but exact prior failures remain mandatory regressions. No timing exception has been approved by evidence or frozen into the final policy. The active goal remains open.
