# Device raster reference and live regression

The optional `--raster-calibration` argument in the capture-bundle verifier selects a predictive reference for page-edge pixel ownership. It does not increase the RGB row error threshold or select whichever neighboring page happens to match the captured pixels.

## Calibration contract

`tools/engine_raster_edge_model.py` predicts the float32 clip/viewport transformation, nearest-even 8-bit subpixel snapping, and upper-page ownership of a shared edge. The predictions were frozen before the device control was measured. The original prediction SHA-256 is `39cdf9ccb2c9f2394fcddda46e04ea2bd4617533b6f726cd7eab85b475a695cc`.

The fixed control has 54 edge positions: rows 2, 65, 155, 1069, 1712, and 2135, each at offsets 508 through 516 in 1/1024 pixel units. Each position checks three complete 1080-pixel rows against solid source images. All 54 positions matched on the recorded calibration device, including the repeated control with capture metadata enabled.

`tools/engine_raster_profile.py` requires the complete grid, every predicted pixel count, the frozen model hash, the calibration APK hashes, and the capture collection's matching app/test APK hashes. Each real capture must report a 1080×2138 viewport, 1024 coordinate units per pixel, 8 subpixel bits, and zero sample buffers/samples. Missing metadata fails; this profile cannot qualify older captures that lack the metadata. Device identity is constrained to the recorded `MangaViewerApi35` AVD; the result is not a general rasterization rule for other devices.

The reference changes edge ownership only. Texture interpolation still uses the submitted logical coordinates, and source-row coverage remains clipped to actual source bounds. The RGB row MAE limit remains 4.0. A negative test uses the wrong page at an edge: it fails even when the calibrated reference is enabled.

## Evidence locations

All paths below are relative to `.artifacts/engine-rewrite-20260906/`:

- `raster-edge-grid-root/frozen-prediction.json`: predictions recorded before the control.
- `raster-metadata-regression-root/`: repeated 54-position control, runtime metadata, 11 passing Android tests, and exact APKs.
- `ntk-raster-profile-live-root/`: subsequent real UI/gesture collection, original trace, native packets, source exports, and verifier results.

The live collection used app SHA-256 `04c3923b8bd82f095d41b9f7175fb46a63344b49fd5639de7cd7b0ad32ebdbdc` and test SHA-256 `6a762d6988b20272212d24482171756a9a36f121d11fa7267d98d267821cd631`. It produced 458 captures and 2,292 submitted frames. The exact producer/Binder/SurfaceFlinger buffer linkage passed for all captures. This establishes observable composition linkage, not a physical scanout timestamp.

The live run failed the performance targets: submission p95 154.163 ms, maximum 495.348 ms, and 940 submissions at least 100 ms. `submission-decomposition.json` separates the interval before the native swap marker from the marker-to-submission-return interval using verified native timestamps. The latter includes native return work and must not be labeled pure EGL swap duration. Noncaptured frames also have substantial delay; the cause is not yet established.

All 458 captured frames passed the calibrated pixel reference; the largest row RGB MAE was 1.187460 against the unchanged 4.0 limit. The final stop also passed: two compositor screenshots matched the sources, and the raw SurfaceFlinger interval retained the same viewer buffer. The actual physical scanout timestamp remains unmeasured.

The integrated bundle exited with failure. Its current-run HTTP binding stage has no observed downloads for cached originals. Source-row coverage found 19 pages with a partly unobserved first or last row, despite all 132 originals being present. Pixel agreement for observed samples cannot fill these missing source intervals. Preserve this failure and investigate the boundary coverage calculation and actual sampling before changing the result.

`verified-archived-origins.json` subsequently revalidated three actual sealed HTTP ledgers and matched all 132 current originals by authorized document candidate URL, HTTP 200 GET response, byte count, body SHA, and independently recomputed current cache page/revision filename. The earlier feasibility index supplied locations only. Its previously missing p0107 is present as request 20 in the original `ntk-long-traversal-root` ledger, with SHA `86f143b057390cb2b07ed49def6a5a4cc6362347c70be7ea2ac6def38fc9f6c3`. That older source export had no p0107 cache binding. No new network request or app-cache change was needed.

The bundle now accepts repeated `--archived-http-directory` arguments and revalidates those raw sealed ledgers itself. Its actual rerun verified all 132 source bindings, while `allSourceResponsesObservedInCurrentRun` remains false. Archive request IDs remain paired with archive paths and hashes; they are not inserted into the current ledger or used as current timing, freshness, or cross-run clock evidence. Ten HTTP tests include wrong URL/cache revision, corrupted body, missing close, invalid seal, and colliding numeric IDs across different ledgers. The original current-run-only bundle and binding report are preserved with `-current-run-only` suffixes.

`boundary-row-influence.json` tests these 19 rows without changing the capture or device content: host temporary source copies replace one boundary row with black and then white. Every row has at least one replacement that fails the unchanged pixel tolerance. Even the weakest of those larger responses has row MAE 124.618293. Thus the rows influence captured pixels, while the existing continuous-area coverage still reports a fractional gap. This diagnostic does not change that gate or grant credit. Its script and input report hashes are archived beside the result.

`tools/engine_source_sampling.py` adds a separate discrete dependency calculation for the independent pixel reference. It uses the pinned Pillow 12.3.0 bilinear filter with quantized 22-bit coefficients, followed by the reference's linear texture sampling and crop bounds. The [Pillow implementation](https://github.com/python-pillow/Pillow/blob/12.3.0/src/libImaging/Resample.c) defines the resampling kernel and coefficient quantization. Model and installed imaging-library hashes are recorded. Impulse-image tests compare the analytic coefficients against actual Pillow output for upsampling, downsampling, equal sizes, and the measured 1098→1647 geometry. Other tests reject zero-weight neighbors, clipped or missing rows, duplicate inflation, changed source identities, and unverified pixels.

`pixels-with-source-sampling.json` revalidated all 458 actual captures and includes the contributing row ranges of each exclusively owned pixel band. `source-row-sampling-diagnostic.json` finds all 143,986 rows of all 132 originals in those reference dependencies. This reports discrete filter participation, not continuous area coverage or lossless recovery after downsampling. The original fractional coverage gate remains unchanged and still fails; the bundle remains diagnostic with zero corpus credit.

`raw-gl-thread-spans.json` decodes original ATRACE events on the previously verified producer thread. EGL swap median/p95 were 78.513/125.4524 ms. Nested `dequeueBuffer` p95 was 119.0464 ms, and `rcCreateSyncKHR encode` p95 was 76.569 ms. Nested totals overlap; these numbers locate delay but do not establish an unavoidable device cost.

Ten alternating readback on/off controls with the same APKs and frozen gesture list completed in `raster-metadata-readback-pairs-root/` and `raster-metadata-readback-pairs-extension-root/`. The small differences in the first block triggered the second five-pair block, starting with the opposite mode to continue alternating order. The aggregate `raster-metadata-readback-ten-pair-summary.json` verifies all 20 collection hashes, matching APK/gesture identities, sealed measurements, and restored trace clocks/buffers. Median run p95 was 158.0622 ms with readback and 158.53045 ms without it. The median paired difference was −0.2069 ms, with differences from −9.2325 to +1.0801 ms. No cache or resume-position reset, content-readiness wait, or APK change occurred between controls. Initial cache/position equivalence remains a separately stated limitation; these controls do not establish the final unavoidable-cost policy.

Read-only host snapshots during the controls reported approximately 502–510 MiB available memory, with page reads still occurring. The Windows paging counters and UTC observation times are archived in `host-paging-samples.jsonl` in the first control directory. They are sparse diagnostic observations, not a continuous per-frame attribution or proof of causality. No host, AVD RAM, or security setting was changed during the controls.

After all 20 controls ended, the task's Gradle daemon was verified idle and stopped; its Kotlin compiler child also terminated. Host committed memory decreased from approximately 39.3 GB to 35.0 GB. A separate unchanged-APK, fixed-gesture, no-readback pilot in `raster-build-daemon-stop-off-root/` still reported p95 156.8086 ms, maximum 514.4716 ms, and 194 of 437 submissions at least 100 ms. Thus this single pilot did not demonstrate that stopping build daemons resolves the delay. It is not pooled into the ten paired controls. Paging remained observable afterward.

Pixel, final-stop, source provenance, complete source-row coverage, physical performance, memory, and the 200-episode corpus remain separate gates. Read their explicit result fields; successful instrumentation or a completed diagnostic stage is not episode credit. This regression carries zero corpus credit.
