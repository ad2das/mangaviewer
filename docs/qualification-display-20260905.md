# Display evidence ledger

Status: **diagnostic only; 0/200 corpus credit**. This record documents the
strongest display observation available from the NTK v6 run. It does not turn
an Android screenshot, EGL latch, or FrameTracer fallback event into a physical
scanout timestamp.

## NTK v6 screenshot corroboration

The verifier was run against the normal-use complete-cache snapshot, the
independently exported `p0000` body, and the screenshot captured before the
diagnostic stopped:

```text
python tools/verify_viewer_screenshot.py \
  --capture .artifacts/qualification-live-20260905/ntk-diagnostic-v6/diagnostic-ntk-v6/diagnostic-ntk-v6-197343407/ten-episode-before-stop.capture.json \
  --snapshot .artifacts/qualification-live-20260905/ntk-diagnostic-v6/independent-source-body/complete-resume.snapshot \
  --body .artifacts/qualification-live-20260905/ntk-diagnostic-v6/independent-source-body/p0000-original.page \
  --page-key p0000 \
  --output .artifacts/qualification-live-20260905/ntk-diagnostic-v6/ntk-v6-viewer-screenshot-verification.json
```

The output is retained at
`.artifacts/qualification-live-20260905/ntk-diagnostic-v6/ntk-v6-viewer-screenshot-verification.json`.
It independently confirms the complete `p0000` source image (720 x 1098)
against 5,336,280 captured RGB components. Zero components fell outside the
exact rational pixel-center bilinear result's nearest-integer envelope; the
maximum quantization error was 0.5. The bound body SHA-256 is
`259927985297bd50b26575ebb14a447b3a5e021d0b75914b28ff0a6ac39d76ba`.

The capture interval is `[197490931411600, 197491042712300]` in the app's
monotonic clock, or 111.3007 ms. Both snapshots report window focus, no
potential occluders, unchanged surface bounds `(0,136)-(1080,2274)`, unchanged
episode/page identity, unchanged input/scroll state, and identical complete
page geometry. These facts establish one observed, unoccluded source-page
image during the screenshot interval.

The result intentionally records `exactPhysicalPresentationTimeVerified=false`,
`nativeBufferIdentityVerified=false`, and `corpusCredit=0`. A screenshot API
does not return the native buffer token, and its receive interval includes
capture, transport, and observer scheduling.

## Negative controls

`tools/test_verify_viewer_screenshot.py` covers the valid fixture and rejects a
changed image even with a refreshed image hash, an incomplete source-row range,
a wrong screenshot hash, an occluded capture, and a checksum-corrupted cache
snapshot. The six tests pass with:

```text
cd tools
python -m unittest test_verify_viewer_screenshot.py
```

The test fixture only exercises verifier behavior. It does not award display or
corpus credit.

## Remaining display gates

| Gate | Current result | What would close it |
| --- | --- | --- |
| Source body and page identity | One page passes: normal-use checksummed snapshot, body length/hash/dimensions, episode identity, and complete source rows | Repeat for every requested page in every accepted episode |
| Unoccluded screenshot geometry | `p0000` passes before/after focus, occlusion, surface, session, and geometry checks | Retain equivalent evidence for each page whose pixels are claimed |
| Native buffer to display-event correlation | Not complete: v6 host verifier reports 3,076 matched native frames out of 6,883 and 3,812 display-correlation violations, including missing or ambiguous BLAST Queue evidence | A lossless trace with one owned native swap, Queue, Latch, and display event for every displayed buffer, spanning entry through final harvest |
| Physical presentation timestamp | Blocked on this emulator: `PresentFences=false`; `PresentFenceSignaled` is explicitly `PRESENT_FENCE_OR_HWC_VSYNC_FALLBACK`, while EGL reports latch/unavailable | A device/renderer exposing real present fences, or an external display-source instrument that reports frame identity and scanout time with an independently bracketed clock bridge |
| Full source-row coverage | Not complete: v6 expected 132 pages and host report fully covers 131; the screenshot sidecar covers only `p0000` | Prove each source-row union through matched displayed buffers; no navigation or screenshot observation can fill an unobserved row |
| Timing and motion gates | Diagnostic values are retained but not qualified; native render p95 31.5653 ms, native maximum 84.6628 ms, motion maximum 318.8019 ms, and surface maximum 149.4763 ms | Obtain exact sample attribution from complete physical display evidence under the frozen policy |
| Corpus completion | No claim: this is one diagnostic episode and the collection metadata requires no corpus credit | Fresh fixed random 200-episode run covering the four source categories (10 works x 5 episodes), with all prior gates passing under unchanged device/RAM/network conditions |

The v6 host report is
`.artifacts/qualification-live-20260905/ntk-diagnostic-v6/host-display-verification.json`;
its series has `expectedPages=132`, `fullyCoveredPages=131`,
`nativeFrames=6883`, `matchedFrames=3076`, and a directly nested
native/trace clock-offset intersection of `[0,4800]` ns. That narrow trace
clock bracket only relates app monotonic samples to trace timestamps. It does
not establish scanout time or resolve the fallback provenance.

## Feasible next measurement

The current emulator cannot close the physical timestamp gate through more
host-side analysis: the missing fact is hardware/renderer provenance. The
bounded next experiment is to repeat the same app run on a target whose
SurfaceFlinger path exposes real present fences, retaining the app PID/package
ownership, native `viewer_clock` brackets, Queue/Latch/present events, and the
independent source-pixel capture. If that capability is unavailable, an
external display-source capture with per-frame IDs and a bracketing clock is
required. Without one of those sources, the exact physical-display and
all-frame gates remain externally blocked and must stay unqualified.
