# Preparing a continuation and reusing image-transfer storage

The user asked to prioritize the last-read episode before opening it and separately
make image display and scrolling substantially faster. Existing prediction only
considered the home screen's selected source and retained six encoded originals.
Decoding still began after opening the viewer.

The library now predicts the latest reading entry across sources and destinations.
An explicitly selected episode catalog takes precedence. It prepares the same six
originals and up to two viewports of decoded image tiles around the saved source
position. Retained pixels are limited to the smaller of 32 MiB and one quarter of
the existing texture budget. Memory-pressure cancellation releases prediction work.
Native decoding uses one background worker. Pausing the library preserves a matching
prediction because Android pauses it before creating the viewer that claims it.

`EnginePixelWork` gives preparation and rendering the same coordinator-owned decode
identity, including immutable content revision/hash, dimensions, source crop and
display width. `EngineTileBands` shares the exact visible renderer's crop calculation.
The viewer borrows prepared pixels instead of decoding them again. A different width
or content identity cannot reuse them. Cancelling prediction cannot dispose pixels
still borrowed by an upload. The existing source anchor and full-quality raster path
are unchanged.

The native uploader also reuses its pixel-unpack buffer's allocated storage. It
allocates on first use or growth, then uses ordinary ordered `glBufferSubData`
updates. A failed upload invalidates the recorded capacity; closing the context
clears it. No unsynchronized mapping or fence bypass is used. The public gfxstream
[encoder source](https://github.com/google/gfxstream/blob/main/guest/GLESv2_enc/GL2Encoder.cpp)
provides a plausible explanation for the measured improvement: its buffer-allocation
path can request a host response on every call. This does not establish the exact
source revision of the installed emulator driver.

## Measured opening behavior

The first candidate debug APK was
`46a2b0db6a4d2e57f4626f9b9acbfc3a6b943e7ce79137ebabebb3f589a83dab`.
`EngineContinuationOpeningTest` performed four counterbalanced openings of frozen
NTK case 6. Prepared arms launched the real library and waited for its automatic
prediction, then opened the normal viewer by intent. Existing on-disk originals
were retained in both arms. These are intent-to-complete-submission measurements,
not physical display timestamps or catalog-row tap measurements.

| Arm | Preparation before opening | First complete submission |
| --- | ---: | ---: |
| Control 1 | None | 3,936.21 ms |
| Prepared 1 | 3,233.66 ms | 976.18 ms |
| Prepared 2 | 2,579.34 ms | 966.20 ms |
| Control 2 | None | 2,060.34 ms |

Each prepared opening retained six originals and four decoded tiles totaling
22,714,560 bytes. All four openings used the identical saved source anchor and
1080-by-2138 viewport. Screenshot image content was pixel-identical; only the
Android navigation indicator differed in the prepared arms. Preparation time is
reported separately and is not eliminated by moving it before the opening action.

## Measured transfer behavior

The same frozen case 6 was traversed forward, backward and into its next episode
using candidate, old baseline, then candidate again, without restarting the emulator
or changing its configuration. All three collected all 33 originals, preserved input
records and restored the library database with verification.

| Prepared-phase measurement | Candidate 1 | Old baseline | Candidate 2 |
| --- | ---: | ---: | ---: |
| Image uploads | 85 | 85 | 84 |
| Upload P95 | 4.571 ms | 25.880 ms | 4.466 ms |
| Upload maximum | 12.652 ms | 32.509 ms | 29.469 ms |
| Buffer-allocation synchronization calls | 1 | 85 | 1 |
| Native frame submission P95 | 26.772 ms | 26.542 ms | 25.515 ms |
| Applied-input completion maximum | 71.898 ms | 124.411 ms | 58.377 ms |
| Applied-input completions at least 100 ms | 0 | 2 | 0 |

Transfer P95 fell by about 83%. Whole-frame submission still exceeds 16 ms. Driver
completion-fence creation remains the dominant frame cost in these traces, so these
results do not establish zero-stutter scrolling. During loading after the first full
viewport, candidate 1 recorded 20 input completions of at least 100 ms, maximum
864.496 ms; candidate 2 recorded one, maximum 136.004 ms. These failures are retained.

## Nonzero-position and fixed-corpus checks

The follow-up candidate `e9555e3a06e0b6e60f659b2d821a868a4120d4219cfad0d532edbd7bf11e927c`
corrects the preparation horizon to count rows after the saved position, including
when it is near a tile's bottom. It also uses the existing API-30-compatible exact
integer conversion and preserves cleanup of later resources after an earlier close
failure. Test APK: `ff250b6d7c40c1c1c20b5fa6d31a58a5c079aa308807e3f5d58b5a137c8a46bc`.

Two further four-arm comparisons saved the position at 90% of page 16 before opening.
Each complete opening was checked against that exact seeded source anchor, as well as
against the other arms. All eight matched. Both prepared arms in each case used the
real library's automatic prediction. Their image content was pixel-identical to the
first control; only the Android navigation indicator changed. The second controls
also differed in small system status-bar areas.

| Case | Control 1 | Prepared 1 | Prepared 2 | Control 2 |
| --- | ---: | ---: | ---: | ---: |
| NTK 6, page 16 | 2,961.48 ms | 998.07 ms | 1,017.65 ms | 2,059.67 ms |
| WFWF 10, page 16 | 1,705.05 ms | 1,002.97 ms | 1,130.52 ms | 1,134.44 ms |

Preparation before opening took 3,398.64/2,336.02 ms for NTK and 1,999.64/1,398.11 ms
for WFWF. Each retained six originals and five raster tiles, 27,600,480 and 30,071,520
bytes respectively. WFWF's second control was already almost as fast as its prepared
arms; these observations do not support a universal opening-speed multiplier.

The frozen twelve-case traversal used the earlier `46a2...` candidate. All cases
collected their complete original sets, reached the required forward/reverse endpoints
and next-episode boundaries, and retained their input histories. Case 1 exceeded the
15-second preparation deadline and remains a failed collector verdict, despite later
finishing all 202 originals. The other eleven collector verdicts succeeded.

Across 25,610 native submissions, 23,591 were in the prepared phase. Per-case prepared
native P95 ranged from 26.994 to 28.609 ms. Two native submissions exceeded 100 ms.
Among 24,549 prepared input completions, four exceeded 100 ms, all in case 5, with a
120.149 ms maximum. Keeping loading intervals after the first complete viewport adds
many more failures: per-case counts of at least 100 ms were 58, 51, 88, 27, 38, 4, 35,
10, 37, 1, 1 and 2. The worst was 1,618.049 ms. This batch did not collect physical
presentation traces and does not qualify missing-frame percentage or physical cadence.

Unit tests cover shared decode ownership, cancellation while an upload borrows pixels,
bounded preparation, the near-bottom anchor horizon, deferred prediction cancellation
and continuation selection. Five native instrumentation tests passed for raster
identity, every-pixel rendering comparisons, reverse source rows, surface recreation
and ownership cleanup. The initial and both mid-page automatic-opening comparisons
passed. Engine and app unit tests, architecture verification, debug/test/release builds
and release lint passed for the follow-up candidate. This is not full performance
qualification, and the follow-up APK was not the APK used for the twelve-case batch.

Evidence is under `.artifacts/account-restore-20260908/`: `continuation-buffer-reuse`,
`continuation-buffer-scroll`, `buffer-reuse-baseline-control-installed` and
`buffer-reuse-candidate-confirmation`, `continuation-buffer-full12`, and
`continuation-midpage`. The earlier `buffer-reuse-baseline-control`
attempt failed its installed-APK identity check before a traversal started; it is
not a performance sample.

## Rejected graphics API substitution

A separate debug-only prototype compared the same 1080-by-2138 white surface with
OpenGL ES and Vulkan. Both used a native owner thread and the same 16.667 ms target
period. It did not load real pages or qualify source rendering. Vulkan used two
in-flight frame fences and one presentation semaphore per acquired swapchain image,
following the [Khronos semaphore ownership guidance](https://docs.vulkan.org/guide/latest/swapchain_semaphore_reuse.html).
No GPU fence was bypassed. These timings include waiting for reusable resources and
acquiring the next image; looking only at `vkQueuePresentKHR` would hide that cost.

An eight-second-per-arm OpenGL/Vulkan-FIFO/OpenGL comparison first found Vulkan slower.
A second comparison added MAILBOX mode. Its native P95 after one second was about
11.03 ms, versus 21.30/21.54 ms for its flanking OpenGL controls. The first second and
its slow initialization frames remain in the raw observations, not discarded from
the evidence.

A third four-arm comparison collected a simultaneous SurfaceFlinger trace. It had
no reported trace errors. Native P95 after one second was 21.93/38.83/10.21/22.48 ms
for OpenGL/FIFO/MAILBOX/OpenGL. However, exact buffer-number matching showed:

| Arm | Buffers submitted | Unique buffers latched by SurfaceFlinger | Not latched |
| --- | ---: | ---: | ---: |
| OpenGL 1 | 427 | 199 | 53.40% |
| Vulkan FIFO | 285 | 285 | 0.00% |
| Vulkan MAILBOX | 471 | 204 | 56.69% |
| OpenGL 2 | 407 | 193 | 52.58% |

MAILBOX's post-startup latch-gap P95 was 52.36 ms, essentially equal to the flanking
OpenGL controls' 52.35/52.38 ms. FIFO retained all its slower submissions, but its
post-startup latch-gap P95 was still 44.30 ms and maximum 152.83 ms. Latches are not
physical display timestamps. The device did not expose `VK_GOOGLE_display_timing`,
so that extension supplied no actual-presentation observations. FrameTimeline only
identified the probe's setup transactions, not an actual timeline for each native
buffer; the buffer-latch analysis uses the separately recorded numbered latch events.

The lower MAILBOX call duration therefore does not establish better visible cadence
or satisfy the missing-frame goal. No Vulkan renderer substitution was retained.
The diagnostic source, exact APKs and results are archived in `graphics-api-probe`,
`graphics-api-probe-modes`, and `graphics-api-probe-timing`. The prototype was removed
from app sources and its debug-only Vulkan dependency was removed with it.
