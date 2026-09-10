# Buffered viewport compositor — not performance qualified

Current measurements and outstanding qualification are summarized in [the performance checkpoint](viewer-performance-status-20260910.md). Original image quality, input ordering and distance, exact saved position, the fixed emulator and 12-case corpus remain unchanged.

The per-original retained compositor was rejected. Its isolated native upload probe found native-fence completion faster than `glFinish` and client-wait alternatives; repeated original hardware-buffer allocation instead took about 50 ms with outliers above 600 ms. Changing synchronization APIs did not solve this. Its final screenshots also failed fractional-position source comparison. The complete rejected source and diff are preserved in `rejected-per-page-originals.zip` under the repeated-host-restart evidence directory.

The current experiment uploads originals into existing GL textures and renders the scene into three reusable viewport buffers. One SurfaceControl displays these buffers with a vertical flip and no scaling or fractional translation. GL computes the existing 1/1024-pixel geometry before composition. There is no per-original hardware-buffer preparation or transfer, and no full-frame EGL swap in this backend. API 30 retains the existing GL window backend.

The same renderer thread owns GL, textures, scene, and viewport buffers. Immutable completed transactions transfer exclusively to one FIFO submission thread for `apply` and deletion; it owns no GL context or textures. Hide and detach drain that FIFO before structural transactions. Queue acceptance is not physical presentation. A submitted viewport remains busy until the completion callback for its replacement supplies the previous-buffer release fence and that fence signals. Callbacks enqueue records and post a coalesced owner wake; the owner polls release fences without blocking. Choreographer polling remains available for outstanding fences. All-busy backpressure retains the latest scene before assigning a submission identity. Detach invalidates the callback queue so old releases cannot free buffers in a new attachment. Late completion wakes after closing are ignored. Native callback threads detach from the JVM if this call attached them. The framework retains submitted buffers after local references are released. Synchronization follows the [NDK previous-buffer release contract](https://developer.android.com/ndk/reference/group/native-activity#asurfacetransactionstats_getpreviousreleasefencefd).

Viewport storage replaces the EGL window's implicit buffer queue and is separately bounded to three RGBA buffers of at most 16 MiB each. Actual allocation follows the observed surface dimensions. Original texture accounting and its existing budget are unchanged. Capture PBOs remain separately bounded. Readback verifies both framebuffer bindings against the specific owned draw framebuffer; it reports EGL frame ID zero and never claims physical presentation. Compositor callbacks report actual composition latch timestamps.

## Original preparation and ownership

The existing incremental header parser can publish exact dimensions from the same original response before decoding finishes. Metadata does not mark a page prepared, publish a file lease, or establish verified content identity. Final body publication still verifies length and hash. Subscribers validate generation, plan, page and revision, and conflicting or retired-attempt updates are rejected.

The runtime retains at most 14 header-observed unfinished transfers, matching the existing physical body-read limit. This prevents scrolling and reversals from cancelling and restarting originals already in flight. Navigation, background suspension and close clear retention; completion and failure retire entries. It adds no extra physical permits.

Repeated geometry lookup and snapshot creation are avoided where immutable state is unchanged. Duplicate verified-content notifications do not repeat content reduction. Original input samples remain ordered and preserve their full distance.

## Validation boundaries

Native lifecycle and pixel diagnostics exercise detach/reattach, release fences, zero outstanding ownership and original-to-framebuffer comparison. Stopped screenshots from successful selected cases passed source comparison. These checks do not prove every moving frame reached physical presentation, and the full fixed-12 performance target remains unmet.

Rejected experiments included per-original compositor buffers, pending-input projection, Java frame-timeline IDs passed into the native Choreographer path, deferred EGL-sync destruction, and synchronous GPU completion. Their absence from the final implementation is intentional. The prior per-original experiment is recorded in [the historical comparison](retained-original-compositor-20260909.md).
