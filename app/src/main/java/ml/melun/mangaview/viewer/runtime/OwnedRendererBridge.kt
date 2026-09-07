package ml.melun.mangaview.viewer.runtime

import android.view.Surface

internal fun interface OwnedRendererCallback {
    fun onFramePresented(token: Long, atNanos: Long, timestampKind: Int, bufferFrameId: Long)
}

internal object OwnedRendererBridge {
    init {
        System.loadLibrary("viewer_native")
    }

    external fun nativeCreate(callback: OwnedRendererCallback): Long
    external fun nativeAttach(renderer: Long, surface: Surface): Boolean
    external fun nativeDetach(renderer: Long)
    external fun nativeContextLost(renderer: Long): Boolean
    external fun nativeRecreateContext(renderer: Long): Boolean
    external fun nativeInjectGlContextLossForVerification(renderer: Long)
    external fun nativeSetStaticQuadForVerification(renderer: Long, enabled: Boolean): Boolean
    external fun nativeSetDirectTextureUploadForVerification(renderer: Long, enabled: Boolean): Boolean
    external fun nativeSetSwapIntervalForVerification(renderer: Long, interval: Int): Boolean
    external fun nativeRasterizationInfoForVerification(renderer: Long): IntArray?

    external fun nativeUpload(
        renderer: Long,
        cpuTile: Long,
        width: Int,
        height: Int,
        sourceTop: Int,
        sourceBottom: Int,
        sourceHeight: Int,
    ): Long

    external fun nativeReleaseTexture(renderer: Long, textureKey: Long)
    external fun nativeSetTextureBudget(renderer: Long, bytes: Long): Boolean
    external fun nativeClearScene(renderer: Long): Boolean
    external fun nativeHasTexture(renderer: Long, textureKey: Long): Boolean
    external fun nativeTextureCounts(renderer: Long): LongArray

    external fun nativeSubmit(
        renderer: Long,
        token: Long,
        surfaceWidth: Int,
        surfaceHeight: Int,
        contentHeight: Int,
        viewportTop: Int,
        frameTimelineVsyncId: Long,
        frameRate: Float,
        sceneKey: Long,
        count: Int,
        packedScene: IntArray?,
    ): Int

    external fun nativePollPresentations(renderer: Long)

    external fun nativeSubmitEngine(renderer: Long, token: Long, width: Int, height: Int,
        frameRate: Float, sceneKey: Long, count: Int, packedScene: IntArray, coordinateUnitsPerPixel: Int): Int

    external fun nativeRequestReadback(
        renderer: Long,
        token: Long,
        sessionId: Long,
        rendererEpoch: Long,
        surfaceEpoch: Long,
        top: Int,
        bottom: Int,
    ): Boolean

    external fun nativeTakeReadback(renderer: Long, token: Long): ByteArray?
    external fun nativeReadbackCounts(renderer: Long): LongArray

    external fun nativeDestroy(renderer: Long)
}
