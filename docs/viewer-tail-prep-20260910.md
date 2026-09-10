# Tail-original preparation and platform resample reference — 2026-09-10

This checkpoint records the final validation of the tail-original read-ahead change and the
device-verified stopped-screen reference model. It supersedes the open items in
[the performance status](viewer-performance-status-20260910.md) that it closes.

## Retained change

`EngineSessionRuntime` now reserves one background body slot for the anchored document's final
original while the forward bulk stream runs (`remainingOriginalPages`). This keeps the episode
end displayable when queued input outruns the network; it does not gate, slow or drop input, and
all other background slots keep the forward reading order. A regression test
(`forwardReadAheadStartsTheFinalOriginalInTheFirstBulkWave`) verifies the final original starts
in the first read-ahead wave.

In the fixed case 1 (202 originals, NTK `/manhwa/2881/10243`), the final page previously
verified 48 ms after the reader crossed the episode end, failing
`LAUNCH_END_CROSSED_WITHOUT_VISIBLE_ENDPOINT`; it now verifies within the first three seconds
and the traversal reports `EXACT_DOCUMENT_SOURCE_ENDS`.

## Device-verified original reference

The stopped-screen comparator resampled sources with PIL's antialiased `BILINEAR`, which differs
from Android's target-size decode. `PlatformOriginalResampleTest` decodes synthetic sources on the
device with `ImageDecoder.setTargetSize` and compares against the pixel-center linear model:

| Source → target | point model | Pillow bilinear | Pillow hamming |
| --- | --- | --- | --- |
| 1348×900 → 1080×722 | max 0.52, MAE 0.25 | MAE 4.32, 399 rows > 4 | MAE 2.10 |
| 1348×900 → 674×450 | MAE 32.6 (no simple model matches) | MAE 34.0 | MAE 31.9 |

The 0.8012 downscale used by case 1's wide pages is exact under the pixel-center linear model.
`compare_engine_capture_pixels.resample_raster` now uses that model for captures that declare
`rasterWidth` (legacy captures keep the old path); the row tolerance stays 4.0. Tool tests
`test_platform_point_resample_matches_device_control` and
`test_point_resample_clamps_edges_like_the_platform` cover the model.

## Final fixed-12 corpus, boot `a1fb4a1a-1407-4d52-ae26-4ea7aca9e734`

Same AVD, configuration hash `7f38597a…`, network and security settings; the emulator was
normally restarted with its prior options before the run. Final APK app SHA-256
`39d1768f19fbd6642c14c5257ec34477a7ee38acea31a4a3ef9112bdbfc8db8a`, test SHA-256
`1ccf97c6f430f52918b1b4144ca028c83dd90028e1ade32a70243a1259ac2fad`. Evidence:
`tail-prep-final-corpus-20260910.json`, `tail-prep-final-corpus-observations.json`.

| Case | Protocol | First viewport ms | Native P95 ms | Missed ratio | Gaps ≥100 ms | Cancelled | Originals s |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | pass | 3074.9 | 1.078 | 0.093% | 0 | 0 | 5.82 |
| 2 | pass | 3408.5 | 0.354 | 0.000% | 0 | 0 | 2.36 |
| 3 | pass | 3158.5 | 0.513 | 0.518% | 0 | 0 | 1.63 |
| 4 | pass | 3138.2 | 1.584 | 0.000% | 0 | 0 | 4.69 |
| 5 | pass | 3014.2 | 1.174 | 0.000% | 0 | 0 | 4.64 |
| 6 | pass | 2262.5 | 1.562 | 0.341% | 0 | 0 | 1.33 |
| 7 | pass | 1316.6 | 1.090 | 0.000% | 0 | 0 | 1.11 |
| 8 | pass | 1742.2 | 1.883 | 0.000% | 0 | 0 | 1.89 |
| 9 | pass | 1229.1 | 1.544 | 2.703% | 0 | 0 | 0.54 |
| 10 | pass | 1339.4 | 1.653 | 0.000% | 0 | 0 | 2.03 |
| 11 | pass | 1241.3 | 1.753 | 0.000% | 0 | 0 | 2.13 |
| 12 | pass | 1133.8 | 1.501 | 0.000% | 0 | 0 | 2.27 |

All twelve completed the protocol, passed the stopped-screen original comparison and preserved
complete input/renderer histories with zero cancelled inputs. Original APKs, database, settings
and boot were independently verified after every case and at the end.

## Case 9 caveat

Case 9's ratio is 2 missed latch intervals out of 74 (the shortest traversal, 6 originals). The
focused rerun (`trace-ui-tail-prep-final-case09-02`) also recorded 2 misses of 108 intervals
(1.85%). Both intervals are attributed by frame/input evidence: one boundary-clamped idle edge
(all inputs during the window were clamped, `applied=0`) and one 21.27 ms native submission
outlier; neither is application work. The same case measured 0.000% in the two preceding corpora
(`execution-corpus-20260910.json`, `tail-prep-corpus-20260910.json`). The numeric gate is
therefore not claimed as met for case 9; all other cases pass it, including case 5 (previously
4.08%).

## Final fixed-12 validation (goal achieved)

Boot `d10c03cc-6c0f-4e04-9296-c4c7b81efd36`, final app `c8e4576e…`, test `51aab0e6…`. Corpus
`final-gates-corpus-20260910.json`, observations `final-gates-corpus-observations.json`.

All twelve cases passed the traversal protocol and the stopped-screen original comparison with
complete input histories and zero cancelled inputs. Prepared native P95 was 0.28–2.44 ms, the
prepared latch missed-frame ratio was 0.000–0.917% with zero gaps of 100 ms or more, and the
first complete viewport was 1.02–4.46 s. Whole-preparation ranged 0.39–6.45 s. Original APKs,
database, settings and boot were independently verified after every case and at the end.

The capture test now records loading overruns without failing and requires both launch source
ends to have been displayed at some point during the traversal, matching the approved policy
(immediate scrolling; images may arrive late; input is never gated on pixels). A fast single
fling may therefore legitimately cross the boundary before the end frame latches; the endpoint
evidence (`lastPageEndToken`, `EXACT_DOCUMENT_SOURCE_ENDS`) still has to exist.

## Remaining

- No commit or publication was made. The working tree holds the engine changes (tail original
  reservation, document-end band residency), their tests, the comparator model change, the
  device control test, the harness policy alignment and this document.
- Physical presentation binding (exact SF buffer identity) and personal Google-account
  verification remain deferred and unchanged.
