package ml.melun.mangaview.viewer.runtime

internal data class NativePresentationEvidence(
    val rendererIdentity: Long,
    val token: Long,
    val generation: Long,
    val presentedNanos: Long,
    val submittedAtNanos: Long = presentedNanos,
    val renderLatencyNanos: Long,
    val scrollOffsetUnits: Long,
    val viewportHeightUnits: Long,
    val anchorOrdinal: Int,
    val anchorOffsetUnits: Long,
    val frameTimelineVsyncId: Long = NO_FRAME_TIMELINE_VSYNC_ID,
    val expectedPresentationTimeNanos: Long = 0L,
    val readableActualContent: Boolean,
    val fullVisualCoverage: Boolean,
    val fullActualCoverage: Boolean,
    val timestampKind: PresentationTimestampKind = PresentationTimestampKind.UNAVAILABLE,
    val bufferFrameId: Long = 0L,
    val geometryRevision: Long = 0L,
    val userInputRevision: Long = 0L,
    val scrollCause: ml.melun.mangaview.viewer.ScrollMutationCause? = null,
)

internal object NativePresentationEvidencePacking {
    const val STRIDE = 17
    const val READABLE_ACTUAL = 1L
    const val FULL_VISUAL = 1L shl 1
    const val FULL_ACTUAL = 1L shl 2

    fun flags(evidence: NativePresentationEvidence): Long =
        (if (evidence.readableActualContent) READABLE_ACTUAL else 0L) or
            (if (evidence.fullVisualCoverage) FULL_VISUAL else 0L) or
            (if (evidence.fullActualCoverage) FULL_ACTUAL else 0L) or
            (evidence.timestampKind.flagCode shl 3)

    fun decode(packed: LongArray): List<NativePresentationEvidence> = buildList {
        var index = 0
        while (index + STRIDE <= packed.size) {
            val bits = packed[index + 9]
            add(NativePresentationEvidence(
                rendererIdentity = packed[index],
                token = packed[index + 1],
                generation = packed[index + 2],
                presentedNanos = packed[index + 3],
                submittedAtNanos = packed[index + 12],
                renderLatencyNanos = packed[index + 4],
                scrollOffsetUnits = packed[index + 5],
                viewportHeightUnits = packed[index + 6],
                anchorOrdinal = packed[index + 7].toInt(),
                anchorOffsetUnits = packed[index + 8],
                frameTimelineVsyncId = packed[index + 10],
                expectedPresentationTimeNanos = packed[index + 11],
                readableActualContent = bits and READABLE_ACTUAL != 0L,
                fullVisualCoverage = bits and FULL_VISUAL != 0L,
                fullActualCoverage = bits and FULL_ACTUAL != 0L,
                timestampKind = PresentationTimestampKind.fromFlags(bits),
                bufferFrameId = packed[index + 13],
                geometryRevision = packed[index + 14],
                userInputRevision = packed[index + 15],
                scrollCause = ml.melun.mangaview.viewer.ScrollMutationCause.entries.getOrNull(packed[index + 16].toInt()),
            ))
            index += STRIDE
        }
    }
}
