package ml.melun.mangaview.reader

import android.graphics.Bitmap
import android.view.Surface

/** Demand-bound rolling SurfaceControl renderer. Created only by an opened reader surface. */
internal object NtkRollingNativeBridge {
    init {
        System.loadLibrary("ntk_strip_renderer")
    }

    external fun nativeCreate(callback: ReaderSurfaceView, creationGeneration: Long): Long

    /** Returns one owned native exact CPU tile pointer, or zero on failure. */
    external fun nativeAllocateExactHardwareBuffer(width: Int, height: Int): Long

    /** Releases exactly the owner reference returned by [nativeAllocateExactHardwareBuffer]. */
    external fun nativeReleaseExactHardwareBuffer(nativeHandle: Long)

    /** Copies one exact private software scratch into the top-left of reusable RGBA storage. */
    external fun nativeCopyExactBitmapToHardwareTile(
        bitmap: Bitmap,
        nativeHandle: Long,
        sourceWidth: Int,
        sourceTop: Int,
        sourceHeight: Int,
        displayWidth: Int,
    ): Boolean

    /** Decodes one sealed source file directly into the already-reserved exact tile buffers. */
    external fun nativeDecodeExactFileToHardwareTiles(
        encodedPath: String,
        nativeHandles: LongArray,
        sourceWidth: Int,
        sourceHeight: Int,
        sourceLeft: Int,
        sourceRegionWidth: Int,
        tileCapacityHeight: Int,
        displayWidth: Int,
    ): Boolean

    /**
     * Allocates only EGL/AHardwareBuffer render targets for the already-opened reader. It has no
     * Surface, frame token, geometry, Bitmap, or presentation capability.
     */
    external fun nativePrepare(handle: Long, width: Int, height: Int): Boolean

    external fun nativeAttach(
        handle: Long,
        surface: Surface,
        childSurfaceControl: android.view.SurfaceControl?,
        geometrySurfaceControl: android.view.SurfaceControl?,
        width: Int,
        height: Int,
        surfaceEpoch: Long,
        refreshPeriodNanos: Long
    ): Boolean

    /** True only after the native owner completed this exact queued attachment. */
    external fun nativeIsSurfaceAttached(handle: Long, surfaceEpoch: Long): Boolean

    /**
     * True when the renderer's one-command display mailbox can accept a new immutable viewport.
     * This is admission backpressure, not presentation evidence.
     */
    external fun nativeHasFrameMailboxCapacity(handle: Long): Boolean

    external fun nativeDetach(handle: Long, surfaceEpoch: Long)

    /**
     * [tileData] is ten integers per resource: page, slot, sourceTop, sourceBottom,
     * sourceWidth, sourceHeight, the JVM token identity, a native-resource kind (0=Bitmap,
     * 1=AHardwareBuffer, 2=exact CPU tile), then the low/high halves of its native pointer.
     * Exact frames still pass the logical Bitmap in [bitmaps], so JNI retains the collision-safe
     * token identity while native storage never enters ART's NativeAllocationRegistry.
     * [geometryData] is pageTop and
     * renderedPageHeight for the same entry. Only the first [bitmapCount] entries are consumed;
     * the arrays are renderer-thread scratch and deliberately retain spare capacity. Returns -1
     * when structurally rejected, -2 when the bounded mailbox is temporarily full, zero when
     * accepted without replacing queued work, or the exact positive token synchronously replaced
     * by an older compatible renderer implementation.
     */
    external fun nativeSubmit(
        handle: Long,
        token: Long,
        structureEpoch: Long,
        width: Int,
        height: Int,
        viewportSourceTop: Int,
        viewportSourceHeight: Int,
        frameTimelineVsyncId: Long,
        expectedPresentationTimeNanos: Long,
        requiresGpuCompletionProof: Boolean,
        producerSceneId: Long,
        bitmapCount: Int,
        tileData: IntArray,
        geometryData: FloatArray,
        bitmaps: Array<Any?>,
    ): Long

    /**
     * Reuses one exact, previously accepted CPU-tile scene. [producerSceneTranslationY] moves
     * every retained page by the same source-space delta without rebuilding its resource graph.
     */
    external fun nativeSubmitProducerGeometry(
        handle: Long,
        producerSceneId: Long,
        token: Long,
        structureEpoch: Long,
        width: Int,
        height: Int,
        viewportSourceTop: Int,
        viewportSourceHeight: Int,
        producerSceneTranslationY: Float,
        frameTimelineVsyncId: Long,
        expectedPresentationTimeNanos: Long,
        requiresGpuCompletionProof: Boolean,
    ): Long

    /**
     * Uploads already-decoded, demand-admitted pixels into the renderer's bounded texture cache.
     * This API has no Surface geometry, frame token, or presentation path and can never draw.
     */
    external fun nativePrewarm(
        handle: Long,
        structureEpoch: Long,
        tileData: IntArray,
        bitmaps: Array<Any>,
        completeSceneSnapshot: Boolean,
    ): Boolean

    /** Prevents non-presenting texture uploads from sharing the EGL lane with physical input. */
    external fun nativeSetPrewarmPaused(handle: Long, paused: Boolean)

    /** Selects exact-current texture safety and the narrower host-emulator queue profile. */
    external fun nativeSetDirectWifiTextureProfile(
        handle: Long,
        enabled: Boolean,
        hostGpuEmulator: Boolean,
    )

    /** O(1) renderer-owner snapshot; false while any native queue/evidence/upload is active. */
    external fun nativeIsQuiescent(handle: Long): Boolean

    /**
     * Exact JNI-global-reference ownership for each JVM identity in [bitmapIdentities]. A true bit
     * means Bitmap.recycle() must remain fenced even when unrelated renderer work is still active.
     */
    external fun nativeBitmapReferenceMask(
        handle: Long,
        bitmapIdentities: IntArray,
    ): BooleanArray?

    /**
     * Drops only not-yet-executing texture-prewarm references for retired Bitmap objects.
     * Visible frame commands and a prewarm tile already owned by the renderer thread are left
     * intact and remain reported by [nativeBitmapReferenceMask].
     */
    external fun nativeDiscardQueuedPrewarmBitmaps(
        handle: Long,
        bitmaps: Array<Any>,
        bitmapIdentities: IntArray,
    ): Int

    /**
     * Drops only not-yet-executing frame commands that still mention a retired Bitmap identity.
     * The applied buffer, an executing command, and a prepared transaction are never touched.
     * Returned tokens let the producer retire the matching pending-commit proofs atomically.
     */
    external fun nativeDiscardQueuedFramesWithRetiredBitmaps(
        handle: Long,
        bitmaps: Array<Any>,
        bitmapIdentities: IntArray,
        protectedToken: Long,
    ): LongArray?

    /** Atomic install fence for a renderer whose worker may fail during asynchronous creation. */
    external fun nativeHasFailed(handle: Long): Boolean

    external fun nativeDestroy(handle: Long)
}
