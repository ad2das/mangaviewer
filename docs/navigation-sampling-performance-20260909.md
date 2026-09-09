# System bar sampling control, 2026-09-09

Removing navigation-bar sampling did not meet the viewer's rendering target.
The experiment used the same application code, emulator boot, frozen case 6,
1080×2138 image viewport and original-image comparison thresholds. Instrumentation
varied only the app window's bar visibility and retained the original 136/66-pixel
top/bottom insets. Rendering, input and saved-position code were unchanged.

The first ABBA run was unsuitable for acceptance: the hidden-bar screenshots
contained Android's fullscreen education overlay. Its failed reports are retained.
A separate ordinary UI test clicked the visible **Got it** button and verified
that the overlay disappeared. The screenshot and action timestamp are under
`navigation-education`. This was not a performance sample. No settings command,
emulator restart or security change was used; the ordinary education acknowledgement
persists.

The subsequent ABBA comparison used exactly the same app/test pair throughout.
All eight compositor screenshots passed, with matching viewport dimensions and
the expected navigation state. The status bar remained visible.

| Condition, in execution order | First source submission | Native P95 | Native frames ≥100 ms | Sampling total |
| --- | ---: | ---: | ---: | ---: |
| Normal bars | 5.173 s | 114.86 ms | 14 | 7,033.62 ms |
| Navigation hidden | 5.270 s | 97.07 ms | 8 | 1.82 ms |
| Navigation hidden | 5.738 s | 97.42 ms | 8 | 4.19 ms |
| Normal bars | 5.189 s | 105.41 ms | 13 | 4,448.32 ms |

`captureSample` still appeared in the hidden-bar trace, but returned almost
immediately. Its work fell sharply while slow native submissions remained.
The measured source interval runs from first source submission to the start of
the first stopped screenshot, excluding that screenshot's acquisition. These
are native submission durations, not physical display presentation proof.

A further control hid both bars while preserving the same viewport. Both
screenshots passed. First source submission was 5.780 s, native P95 92.78 ms,
and two frames still took at least 100 ms. Sampling total was 1.97 ms. The
`rcCreateSyncKHR encode` calls owned by the viewer renderer still had P95 79.89 ms;
the navigation-hidden arms measured 81.31 and 79.71 ms. This query joins the
captured application PID and engine GL thread and requires each call to be fully
inside a real `engine_frame` slice. It finds exactly one such call per source
frame in each of the five runs. These controls do not demonstrate a route to the
required rendering P95 below 16 ms.

The [AOSP Goldfish EGL source](https://android.googlesource.com/device/generic/goldfish-opengl/+/1ed8990955c1b0e96e18301fdc2ca0d7fb12c1a2/system/egl/egl.cpp)
illustrates how native fence creation during buffer submission protects the
consumer from an unfinished host buffer. This is mechanism context, not a claim
that the older source revision matches the installed Android 15 binary. The
reported synchronization duration comes from the actual device trace. Removing
fence protection would not establish correct presentation.

Bar hiding was not adopted. Temporary instrumentation hooks and helper tests
were archived and removed. The instrumentation APK was rebuilt and matched the
original hash; the architecture gate passed. Application SHA
`4ac85f2074551f893909ea90fc04fb07503ded7cc49cff2385121c93032a6f86`
and test SHA `242fb7feb7daf6702591c3aa9dd047333973e02fb96e611f6c0da647a7c0f177`
were restored after this comparison. The later
[progressive opening change](progressive-opening-20260909.md) records the subsequent
installed candidate. Original database restoration and the unchanged boot identifier
were verified after every run. No collector or build remains active.

Evidence under `.artifacts/account-restore-20260908/`:

- `navigation-only-fixed-viewport`: initial overlay-confounded comparison.
- `navigation-education`: separate visible-button acknowledgement.
- `navigation-only-confirmed`: ABBA pixel reports, traces and comparison.
- `all-bars-fixed-viewport`: both-bars control, archived temporary source,
  renderer-owned synchronization comparison, cleanup build and final installed-APK verification.

The full 12-case performance goal remains unmet. These viewport pixel checks do
not qualify whole-episode coverage, exact display timing or missing frames.

## Installed driver inspection

A subsequent read-only inspection copied the three installed vendor libraries,
verified their SHA-256 hashes against the device, and saved their ELF build IDs,
dynamic symbols and relevant disassembly under `installed-egl-driver`.
`verified-installed.json` also freshly verifies the unchanged app/test hashes,
Android 15 fingerprint and emulator boot identifier. No app was rebuilt or run
for this inspection, and no system library was replaced.

This closes part of the source-version uncertainty above. In the actual installed
`libEGL_emulation.so` (build ID `f8d403ca4d56b6982f0f589babfb21e5`):

- Window swap at `0xf0a0` selects synchronization based on encoder capability
  fields. Its Goldfish branch calls `0x152d0` with native-fence type `0x3144`,
  empty attributes, automatic destruction and a request for a new fence FD.
- That helper invokes encoder slot `0xf0` at `0x15351`, then obtains the native
  fence through ioctl `0xc0184000`. The window-buffer queue call follows the
  helper's return at `0xf2b6`; the dequeue call is at `0xf527`.
- In the installed `lib_renderControl_enc.so`, its constructor fills slot `0xf0`
  with `0xbf80`. That function starts the exact `rcCreateSyncKHR encode` trace
  section (string at `0x301b`), encodes opcode `0x272d`, and reads two eight-byte
  results before ending the section. Conditional checksum processing is also
  inside that interval. Thus the trace section is not merely command serialization.
- The installed `QemuPipeStream::commitBufferAndReadFully` contains buffered
  response consumption and a `qemu_pipe_read` retry loop. This agrees with the
  separately retained intrusive syscall observations in `empty-native-stream-control`;
  those earlier observations are not a new performance sample for the current APK.

These addresses are ELF virtual offsets, not process addresses. This static
inspection does not prove the host-side reason for each long response, dynamically
identify every capability branch, or establish an irreducible hardware limit.
The runtime evidence remains the five renderer-owned trace queries above. Both
the installed branch structure and the current
[gfxstream EGL implementation](https://github.com/google/gfxstream/blob/main/guest/egl/egl.cpp)
place synchronization before queueing; the public source is still not asserted
to be a byte-identical build of this system image.

No supported application option that safely removes this response dependency
was identified. Moving only the timing boundary would not reduce the wait or
prove visible cadence. Fence bypass and driver replacement were not attempted.

An existing alternative-output result was also checked before considering a new
renderer rewrite: `window-write-probe/source` uses `ANativeWindow_lock`, full-size
RGBA writes and `ANativeWindow_unlockAndPost` for mode 1. Its two CPU-output arms
have post-first-second native P95 327.48 and 296.76 ms, compared with 79.38 and
78.88 ms in the surrounding GL arms. Every CPU buffer latched, but latch-gap P95
was 357.01 and 352.35 ms. These are historical synthetic controls, not original-page
qualification or a fresh matched comparison against the current APK. They provide
no basis for adopting that substitution, and it was not repeated.

The investigation narrowed the driver mechanism but produced no additional
production performance change. The requested twelve-case criteria remain unmet;
the root cause of the host response delay and a compliant remedy remain unresolved.
