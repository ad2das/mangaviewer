package ml.melun.mangaview.viewer.runtime

import ml.melun.mangaview.core.ReadingPosition
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.viewer.session.SceneSnapshot

internal data class OwnedFrameMetadata(
    val position: ReadingPosition,
    val pageOrdinal: Int,
    val viewportHeightUnits: Long,
    val readableActualContent: Boolean,
    val fullVisualCoverage: Boolean,
    val presentedPageId: PageId?,
    val geometryRevision: Long = 0L,
    val userInputRevision: Long = 0L,
    val scrollCause: ml.melun.mangaview.viewer.ScrollMutationCause? = null,
    val viewportWidthUnits: Long = 0L,
)

internal data class OwnedPresentation(
    val token: Long,
    val presentedAtNanos: Long,
    val submittedAtNanos: Long,
    val renderLatencyNanos: Long,
    val frameTimelineVsyncId: Long,
    val expectedPresentationTimeNanos: Long,
    val scene: SceneSnapshot,
    val metadata: OwnedFrameMetadata?,
    val timestampKind: PresentationTimestampKind,
    val bufferFrameId: Long = 0L,
    val verifiedTextureIdentities: Map<Long, UploadedTextureIdentity> = emptyMap(),
    val rendererEpoch: Long = 1L,
)

internal enum class PresentationTimestampKind(val nativeToken: Int, val flagCode: Long) {
    UNAVAILABLE(-1, 0),
    DISPLAY_PRESENT(0x343A, 1),
    COMPOSITION_LATCH(0x3436, 2),
    RENDERING_COMPLETE(0x3435, 3),
    SWAP_RETURN(0, 4),
    CANCELLED(-2, 5),
    DROPPED(-3, 6),
    CONTEXT_LOST(-4, 7);

    companion object {
        fun fromNative(token: Int): PresentationTimestampKind =
            entries.firstOrNull { it.nativeToken == token } ?: UNAVAILABLE

        fun fromFlags(flags: Long): PresentationTimestampKind =
            entries.firstOrNull { it.flagCode == ((flags ushr 3) and 7L) } ?: UNAVAILABLE
    }
}
