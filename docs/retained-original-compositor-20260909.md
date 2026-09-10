> Superseded experimental backend. Per-original compositor buffers were removed after the allocation and fractional-position failures below. Current work is recorded in `buffered-viewport-compositor-20260909.md`. The user now prioritizes immediate smooth scrolling and permits reasonable first-image overruns; 4 seconds is no longer an absolute first-image gate.

# Retained original compositor experiment — not qualified

The live performance goal remains unmet. These changes are local development work; the published APK and the original database/settings are restored after device experiments. None of the measurements below constitutes full fixed-12 qualification or physical scanout proof.

## Implementation

API 31+ uses one native owner with a retained compositor. Ordinary scrolling submits one atomic transaction changing viewport-slot transforms. Each verified original has a bounded immutable RGBA buffer; pages do not each own a SurfaceControl. The viewport slot pool starts at four and grows only to the peak visible count, capped at 128. Hidden slots replace their original buffer reference with one shared, never-visible 1x1 infrastructure buffer. Closing the owner removes the root and all slots.

The same owner owns the sole GLES context for original uploads. Two IO preparation lanes allocate hardware buffers under a separate 64 MiB reservation bound, while Kotlin retains the source CPU borrow. Upload adopts a prepared handle once, imports its hardware buffer as an EGLImage, copies original pixels, and publishes an acquire fence. Cancellation releases an unadopted handle; original texture retirement waits for native scene-reference completion. Existing renderer byte limits remain in force. Older API levels and isolated existing GL tests retain the GL backend.

Texture preparation occurs before acquiring the serialized UPLOAD permit. Opening viewport originals keep network priority; spare background body slots can start the next episode as soon as opening originals arrive. The next-episode expansion accounts for current queued background bodies, preventing its sliding-window tail from being stranded. Total network/body limits are unchanged. Verified identities survive two document boundaries for budgeted resident textures. Preparation covers twelve viewport heights within the existing byte budget; speculative entries are never declared visible placements.

The architecture scanner permits native hardware-buffer/composition calls only in `retained_page_compositor.cpp`. Other native-composition locations, legacy ownership paths, and the existing file/class/function/complexity limits remain forbidden. This is an explicit single-owner backend replacement, not an exemption for per-page surface ownership.

No input distance, event sequence, expected reading position, source resolution rule, or performance threshold was relaxed. Legacy observation field `swapSucceeded` means the native transaction was accepted on this backend. Its timestamp kind is `COMPOSITION_LATCH`, and `eglFrameId` remains zero. It is not an EGL swap or physical display proof. Native GL readback is explicitly unavailable for retained composition.

## Case 3 timing controls

All controls used the fixed traversal protocol, original AVD configuration and boot, and no display tracing unless identified below. Values describe complete native submissions, not scanout.

| Candidate | First complete ms | Prepared native P95 ms | Missed submission slots | >=100 ms cadence gaps |
| --- | ---: | ---: | ---: | ---: |
| Original-scale GL `1510a7a8` | 3966.89 | 28.07 | 19.91% | 0 |
| Retained CPU-copy pipeline `68c0ad95` | 3867.46 | 1.73 | 36.36% | 7 |
| Larger horizon `ebb81c49` | 4259.54 | 1.55 | 25.90% | 7 |
| Direct GPU original upload `783c76bc` | 3585.98 | 1.67 | 28.62% | 7 |
| Upload-completion / in-flight experiment `8b864767` | 6913.58 | 5.76 | 26.87% | 13 |
| Earlier bounded next bodies / slots `7189cd84` | 5634.51 | 2.74 | 15.79% | 5 |

These controls are not causal first-load comparisons: host and server timings vary. The upload-completion/in-flight variant was rejected; both the synchronous `glFinish` path and retention of all pending tile demands were removed from normal production behavior. A debug-only timing probe currently compares completion APIs on the same immutable input. Its default remains native-fence completion, and the probe resets its mode in `finally`.

Display traces isolated significant per-original costs. CPU hardware-buffer preparation took approximately 60–230 ms; the longest observed call spent 79 ms allocating, 58 ms locking and 83 ms unlocking/uploading. Direct GPU transfer removed locking/unlocking, but an observed native-fence creation cost 62 ms, while `glTexSubImage2D` took 1 ms. This motivates the isolated completion comparison. It does not justify skipping synchronization.

## Verification and outstanding work

The latest engine suite has 149 passing tests; app debug unit tests, app/test builds and architecture checks passed. Added regression coverage exercises opening-body priority, spare next-body capacity while the current tail is slow, metadata preservation through two document boundaries, and preparation outside the serialized upload permit. All recorded case-3 input and renderer histories verified with zero cancelled accepted inputs.

The existing 18 native GL/decoder tests apply to the prior GL backend. They do not establish retained-compositor correctness. Actual retained-compositor screenshots were independently compared with original source bodies. The first source region is close to its reference, but the second region fails the unchanged per-row RGB comparison (overall MAE 1.431; maximum row MAE 41.043 against limit 4). GPU and CPU buffer upload versions produced exactly the same comparison results. Fractional compositor placement requires correction and retained native lifecycle tests remain necessary. Do not publish this backend as original-quality qualified.

One traced GPU-upload run encountered a transient database-WAL restoration chmod failure. The saved original database was restored again and independently verified byte-for-byte, with matching metadata, APK hashes, settings and unchanged boot. The wrapper now reports a database restoration failure accurately rather than printing an unconditional all-restored message.

The long-named upload-completion run hit Windows adb destination-path limits during artifact transfer. Its original collection remains marked failed. A second read-only pull from the same closed device capture into a short directory recovered all 56 files; each copied file was hash-verified and recorded in `capture-recovery.json`. Its timing rows are diagnostic only. Later runs use shorter names.

Source export now accepts either a successfully stopped trace or an explicitly disabled trace from a successful, closed capture. The zero-ownership, exact cache identity and SHA-256 requirements are unchanged. Five exporter regression tests passed. The helper performs no network fetch.

Remaining: reduce loading/buffer-completion stalls and cadence misses, restore exact source-to-screen comparison, exercise retained resource lifetime/cancellation, then verify all fixed 12 cases on one final APK. Google-account testing remains deferred as requested.
