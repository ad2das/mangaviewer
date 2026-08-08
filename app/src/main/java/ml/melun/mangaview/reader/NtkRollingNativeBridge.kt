package ml.melun.mangaview.reader

import android.graphics.Bitmap
import android.view.Surface

/** Demand-bound rolling SurfaceControl renderer. Created only by an opened reader surface. */
internal object NtkRollingNativeBridge {
    init {
        System.loadLibrary("ntk_strip_renderer")
    }

    external fun nativeCreate(callback: ReaderSurfaceView): Long

    /**
     * Allocates only EGL/AHardwareBuffer render targets for the already-opened reader. It has no
     * Surface, frame token, geometry, Bitmap, or presentation capability.
     */
    external fun nativePrepare(handle: Long, width: Int, height: Int): Boolean

    external fun nativeAttach(
        handle: Long,
        surface: Surface,
        width: Int,
        height: Int,
        surfaceEpoch: Long,
        refreshPeriodNanos: Long
    ): Boolean

    external fun nativeDetach(handle: Long, surfaceEpoch: Long)

    /**
     * [tileData] is seven integers per bitmap: page, slot, sourceTop, sourceBottom,
     * sourceWidth, sourceHeight, and the JVM bitmap identity. [geometryData] is pageTop and
     * renderedPageHeight for the same entry.
     */
    external fun nativeSubmit(
        handle: Long,
        token: Long,
        structureEpoch: Long,
        width: Int,
        height: Int,
        tileData: IntArray,
        geometryData: FloatArray,
        bitmaps: Array<Bitmap>
    ): Boolean

    /**
     * Uploads already-decoded, demand-admitted pixels into the renderer's bounded texture cache.
     * This API has no Surface geometry, frame token, or presentation path and can never draw.
     */
    external fun nativePrewarm(
        handle: Long,
        structureEpoch: Long,
        tileData: IntArray,
        bitmaps: Array<Bitmap>,
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

    external fun nativeDestroy(handle: Long)
}
