package ml.melun.mangaview.viewer

import java.io.File
import ml.melun.mangaview.viewer.runtime.NativePresentationEvidence

internal object ViewerPresentationEvidenceArtifacts {
    fun write(
        directory: File,
        evidence: List<NativePresentationEvidence>,
        gestures: List<PresentationGestureWindow>,
    ): File = directory.resolve("presentation-evidence.tsv").apply {
        writeText(buildString {
            appendLine(
                "kind\tindex\tdirection\twindowStart\twindowEnd\trenderer\ttoken\t" +
                    "generation\tpresentedNanos\tsubmittedAtNanos\tframeTimelineVsyncId\t" +
                    "expectedPresentationTimeNanos\trenderLatencyNanos\tscrollOffsetUnits\t" +
                    "viewportHeightUnits\tanchorOrdinal\tanchorOffsetUnits\treadableActual\t" +
                    "fullVisual\tfullActual\ttimestampKind\tbufferFrameId\tgeometryRevision\tuserInputRevision\tscrollCause",
            )
            gestures.forEachIndexed { index, gesture ->
                append("gesture\t").append(index).append('\t').append(gesture.direction)
                    .append('\t').append(gesture.range.first).append('\t').append(gesture.range.last)
                    .appendLine("\t\t\t\t\t\t\t\t\t\t\t\t\t\t")
            }
            evidence.forEach { sample ->
                val gestureIndex = gestures.indexOfFirst { sample.presentedNanos in it.range }
                val gesture = gestures.getOrNull(gestureIndex)
                append("presentation\t").append(gestureIndex).append('\t')
                    .append(gesture?.direction ?: "NONE").append('\t')
                    .append(gesture?.range?.first ?: 0L).append('\t')
                    .append(gesture?.range?.last ?: 0L).append('\t')
                    .append(sample.rendererIdentity).append('\t').append(sample.token).append('\t')
                    .append(sample.generation).append('\t').append(sample.presentedNanos).append('\t')
                    .append(sample.submittedAtNanos).append('\t')
                    .append(sample.frameTimelineVsyncId).append('\t')
                    .append(sample.expectedPresentationTimeNanos).append('\t')
                    .append(sample.renderLatencyNanos).append('\t').append(sample.scrollOffsetUnits)
                    .append('\t').append(sample.viewportHeightUnits).append('\t')
                    .append(sample.anchorOrdinal).append('\t').append(sample.anchorOffsetUnits)
                    .append('\t').append(sample.readableActualContent).append('\t')
                    .append(sample.fullVisualCoverage).append('\t')
                    .append(sample.fullActualCoverage).append('\t')
                    .append(sample.timestampKind).append('\t').append(sample.bufferFrameId)
                    .append('\t').append(sample.geometryRevision).append('\t').append(sample.userInputRevision)
                    .append('\t').append(sample.scrollCause?.name ?: "UNKNOWN").appendLine()
            }
        })
    }
}
