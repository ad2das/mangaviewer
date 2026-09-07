# Practical 20-episode execution — 2026-09-07

The acceptance scope is in `practical-completion-plan-20260907.md`. This report records actual progress; catalog selection is not episode completion.

## Fixed selection

Seed: `4761981576324692694`. Population: first live LATEST catalog page in each source/kind group. Selection completed in 6.265 seconds without viewer entry or image preparation. Original corpus and selection events: `.artifacts/practical-20-20260907/selection/`. All twenty identities are frozen in `.artifacts/practical-20-20260907/episodes.json`.

| Source | Kind | Series | Selected episode labels |
| --- | --- | --- | --- |
| NTK | Comic | 약사의 혼잣말~마오마오의 수수께끼 풀이 수첩~ | 13-1, 13-2, 14-1, 14-2, 15-1 |
| NTK | Webtoon | 우주천마 3077 | 28–32 |
| WFWF | Comic | 다크 개더링 | 32–36 |
| WFWF | Webtoon | 비비안의 사정 | 8–12 |

Independent source episode-order verification remains required; labels alone are not proof.

## Candidate reconciliation

The 71f2eafd app cited in the scope-change document is an archived earlier diagnostic candidate. Current on-device inspection found a later debug-only surface-transaction probe candidate, also preserved in `.artifacts/engine-rewrite-20260906/surface-transaction-capability-root/`. Its app hash is `d11b302545e8c9c8b8dc7b8a88fd643bd895dd41cff1e8e1d57c49ec269a985a`.

After adding metadata-only practical discovery, `:app:assembleDebugAndroidTest verifyArchitectureQuality --max-workers=2` passed. The app hash remained unchanged; the new installed test APK hash is `10ca423d36ef9df087fef5d003486d6aa94b0f36094d665bb623e6d08fd17f70`. Both APKs are archived under `.artifacts/practical-20-20260907/`, and installed hashes were checked before discovery. Earlier full-suite results are historical checks; final candidate coverage will account for subsequent debug/test-only changes.

The later historical `ntk-active-gpu-state-root/measurement.json` reports native submission p95 131.7298 ms, max 810.796 ms, 186 submissions at least 100 ms, eight zero-source scenes, and 108 incomplete viewport scenes. These are diagnostic submission/scene metrics, not physical presentation times or episode acceptance. Transient blanks must be distinguished from persistent missing images.

## Runtime verification

Accepted episodes: **0/20**. First selected episode collection started in `episode-01-attempt-01/`, with real catalog UI entry, actual traversal gestures, readback, final stopped screenshots, and memory sampling. Collection results require review before any episode receives credit.

### First selected episode result

`episode-01-attempt-01/` finished with an instrumentation failure after 281.899 seconds including catalog navigation. The viewer traversed the selected document endpoints using 27 gestures and captured 53 full viewport frames. The final submitted token 233 was captured; two stopped-screen PNGs were identical. Visual inspection showed comic content in the stopped screen, but complete source-row/image correctness has not yet been independently verified.

The failure was `PSS unavailable for owned PID 14748` in the asynchronous memory sampler, surfaced by `QualificationMemory.finish`. It does not by itself prove a leak or normal process termination. Work counters, file leases, prepared pages, and pending publications were zero; decode workers terminated. The episode remains failed/incomplete in `episodes.json`, with the sample unchanged and accepted count 0/20.

During extended navigation, the root inspected activity/log state, took an extra screenshot, and requested an app thread dump via SIGQUIT. These interventions are recorded in `diagnostic-interventions.json`; this attempt is not an undisturbed timing measurement. The trace stopped normally and was archived with SHA-256 `f245f1042fb7255d0960696a46be1d7aa17d3a312f56cce586436498ee902fa3`. The source exporter correctly refused this unsuccessful collection; its guard was not bypassed. Next action is to repair/verify process-lifetime handling in memory measurement and rerun the same selected episode.

### Memory measurement fix and regression

The sampler now compares UID, ProcessRecord token, start sequence, and process name after missing PSS. An exact `No process found for: <expected PID>` response is recorded as process exit separately from numeric PSS; no zero-valued process is invented. PID reuse and unverified/live missing-PSS responses remain failures. Raw before/after ownership and churn records are retained, and all-owned continuous peak remains unproven.

`:app:assembleDebugAndroidTest verifyArchitectureQuality --max-workers=2` passed, followed by **14 Android memory regression tests**. The app remains `d11b302545e8c9c8b8dc7b8a88fd643bd895dd41cff1e8e1d57c49ec269a985a`; the new test APK is `5dd9137acb65382db45b6e193cd821a88c753bf699165c23d9912c63b7db85e1`. Installed hashes match archived `candidate-memory-churn/` APKs. Earlier failed evidence and original test APKs remain intact.

The NTK catalog verifier now supports explicit sequence attributes in the actual HTML catalog, including parent list rows. Split labels such as 13-1 are not misread as episode 1. Complete API catalogs retain priority, identical repeated HTML pages are allowed, and conflicting pages fail. Fourteen Python tests passed. The root verified both captured episode plans and the fixed five-episode chain against all **177** actual source catalog entries; `episode-01-attempt-01/selected-chain-order.json` records this independent order evidence. It does not turn the failed runtime collection into a passing episode.

The same selected first episode is being retried under `episode-01-attempt-02/`. No content preparation or cache manipulation was performed. Accepted count remains 0/20 pending runtime and image verification.

### Restart recovery

The interrupted second attempt had no final collector receipt. After permissions were restored, host process inspection found no collector, while its exact Perfetto PID 15145 remained alive. Device logs recorded rejected library gesture injection followed by UiAutomation disconnection/DeadObjectException at 01:05:28 UTC. No viewer baseline or complete runtime evidence was produced, and the attempt receives no credit.

The root verified the Perfetto command line, stopped that process gracefully, pulled the remaining capture and 101,264,930-byte trace, and restored tracefs to the previously durably verified baseline (`boot`, 7 KiB). The interrupted collector's own pre-state was not persisted, so recovery does not claim to have recovered that missing record. `episode-01-attempt-02/recovery.json` documents the distinction and trace hash `fddecd11897e076355d77ee59edadfa0864d0997d32d31bf127013ed6bcafbed`.

The third attempt uses the same episode identity and app/test APKs, under `episode-01-attempt-03/`. Wired Ethernet and MangaViewerApi35/emulator-5554 were rechecked; no build/collector conflict was present. The fixed selection and accepted count remain unchanged.

### Solo execution

The user's direct instruction “워커 사용 ㄴㄴ” supersedes the earlier worker orchestration preference. The running worker was interrupted; subsequent execution, analysis, and verification are performed by the root alone. No new worker tasks will be assigned.

The third attempt completed successfully: 27 actual gestures, 53 captured frames, endpoint observations, zero retained work/storage ownership, and terminated decode workers. Sampled PSS policy passed with no violations. Active sampled peak was 345,756 KiB, before-catalog 70,909 KiB, before-viewer 254,064 KiB, and after-viewer 279,657 KiB. These remain sampled values, not continuous peak or all-GL proof.

Both final compositor screenshots matched the original image reference and retained the same reported frame token. Native source comparison initially matched 52/53 frames; frame 35 requires examination of its boundary rasterization. The fixed, previously recorded 54-case edge model is being validated on the exact current APK pair. No mismatch is being silently ignored and accepted episode count remains zero pending the image review.

### Practical functional acceptance — episode 1

The exact-current-APK calibration matched all 54 frozen edge cases. Applying that unchanged predictive edge reference made all **53/53 native captures** match the originals; the earlier frame-35 disagreement was a boundary reference issue. The independently parsed episode contains **22 pages**, all bound to source response bytes observed in this run. Every discrete original row contributes to the matching reference pixels. Both actual compositor screenshots match and show an unchanged scene across a 1.373-second interval.

This is **1/20 under the practical functional scope**, recorded in `episode-01-attempt-03/practical-verdict.json`. It is not a pass of the former 200-episode proof contract. Continuous geometric coverage has fractional pixel-edge gaps; the original continuous-area flags remain false. Exact per-frame SurfaceFlinger presentation, continuous memory peak/all GL allocations, and exact cache equivalence are not claimed. Native submission p95 remains 215.376 ms, maximum 723.5923 ms, with 114 submissions at least 100 ms; readback and memory instrumentation are included.

The second fixed episode, 13-2 (`/manhwa/22158/211494`), is running under `episode-02-attempt-01/` with the same app/test hashes. No worker is used.

### Practical functional acceptance — episode 2

Accepted count is **2/20**. Episode 2 completed 30 gestures and all 58 native captures match 25 original pages with complete discrete reference-row support. Both final viewport crops are identical and match the original pixels. A submission during the first screenshot changed only bookkeeping (token/frame/timestamps/geometry revision), not placements, content, input identity or observed geometry. The practical verifier explicitly allows identical-content resubmission; its legacy default still requires the same submission. Six screenshot regression tests passed, including rejection of geometry/content/input changes and wrong pixels. Episode 1 was rechecked with the same practical verifier and still passes.

Three initial cached pages were matched by URL, SHA-256 and byte length to separately sealed earlier HTTP responses; the remaining 22 were observed in the current run. This is byte provenance only, not current-run network timing or freshness. Native submission p95 was 178.3866 ms and maximum 629.7923 ms, including instrumentation. Sampled memory, closed ownership, input history and renderer history passed. Physical presentation and continuous memory/geometry proof remain unclaimed. Canonical review: `episode-02-attempt-01/practical-review.json`.

### Practical functional acceptance — episode 3

Episode 3 passed all practical gates: 39 native captures, 15 source pages, final identical compositor crops, source row support, source byte provenance, input/renderer histories, sampled memory and closed ownership. Submission p95 was 163.9812 ms and maximum 595.2067 ms. Accepted count is **3/20**. A disconnected host output session did not interrupt the collector; its success receipt and exported originals were recovered and reviewed without a duplicate UI run. Root-only sequential execution now logs progress durably to `solo-progress.log` and errors to `solo-runner-errors.txt`. Episode 4 is running. Per-episode review receipts and `episodes.json` are the authoritative subsequent progress records.
