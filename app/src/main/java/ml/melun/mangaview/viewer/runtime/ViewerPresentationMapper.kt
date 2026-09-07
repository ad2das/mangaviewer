package ml.melun.mangaview.viewer.runtime

import ml.melun.mangaview.core.ReadingPosition
import ml.melun.mangaview.viewer.FixedPx
import ml.melun.mangaview.viewer.session.SceneSnapshot
import ml.melun.mangaview.viewer.session.ViewerSessionState

/** Pure conversion between canonical session state and renderer presentation records. */
internal class ViewerPresentationMapper {
    fun frameMetadata(
        state: ViewerSessionState,
        scene: SceneSnapshot,
    ): OwnedFrameMetadata? {
        val position = positionForScene(state, scene) ?: return null
        val ordinal = state.timeline.pageIndex(position.pageId) ?: return null
        val coverage = visibleCoverage(scene, state.viewport.height)
        return OwnedFrameMetadata(
            position,
            ordinal,
            state.viewport.height.units,
            coverage.readable,
            coverage.full,
            coverage.presentedPageId,
            state.geometryRevision,
            state.userInputRevision,
            state.scrollCause,
            state.viewport.width.units,
        )
    }

    fun evidence(
        rendererIdentity: Long,
        value: OwnedPresentation,
    ): NativePresentationEvidence? {
        val metadata = value.metadata ?: return null
        return NativePresentationEvidence(
            rendererIdentity = rendererIdentity,
            token = value.token,
            generation = value.scene.generation,
            presentedNanos = value.presentedAtNanos,
            submittedAtNanos = value.submittedAtNanos,
            renderLatencyNanos = value.renderLatencyNanos,
            scrollOffsetUnits = value.scene.localOrigin.units + value.scene.scrollOffset.units,
            viewportHeightUnits = metadata.viewportHeightUnits,
            anchorOrdinal = metadata.pageOrdinal,
            anchorOffsetUnits = metadata.position.offsetInPageUnits,
            frameTimelineVsyncId = value.frameTimelineVsyncId,
            expectedPresentationTimeNanos = value.expectedPresentationTimeNanos,
            readableActualContent = metadata.readableActualContent,
            fullVisualCoverage = metadata.fullVisualCoverage,
            fullActualCoverage = metadata.fullVisualCoverage,
            timestampKind = value.timestampKind,
            bufferFrameId = value.bufferFrameId,
            geometryRevision = metadata.geometryRevision,
            userInputRevision = metadata.userInputRevision,
            scrollCause = metadata.scrollCause,
        )
    }

    private fun positionForScene(
        state: ViewerSessionState,
        scene: SceneSnapshot,
    ): ReadingPosition? {
        val layout = state.layout ?: return null
        val absolute = scene.localOrigin + scene.scrollOffset
        val pageId = layout.pageAt(absolute) ?: return null
        val pageTop = layout.topOf(pageId) ?: return null
        return ReadingPosition(pageId, (absolute.units - pageTop.units).coerceAtLeast(0L))
    }

    private fun visibleCoverage(scene: SceneSnapshot, viewport: FixedPx): VisualCoverage {
        val start = scene.scrollOffset.units
        val end = start + viewport.units
        val visible = scene.quads.filter { quad ->
            quad.top.units < end && quad.top.units + quad.height.units > start
        }
        val actual = visible.filter { it.visualKey != null }
        var covered = 0L
        var priorEnd = start
        actual.sortedBy { it.top.units }.forEach { quad ->
            val top = maxOf(start, quad.top.units)
            val bottom = minOf(end, quad.top.units + quad.height.units)
            covered += (bottom - maxOf(top, priorEnd)).coerceAtLeast(0L)
            priorEnd = maxOf(priorEnd, bottom)
        }
        val center = start + viewport.units / 2L
        val presentedPageId = actual.minByOrNull { quad -> distanceFrom(center, quad) }?.pageId
        return VisualCoverage(presentedPageId != null, covered >= viewport.units, presentedPageId)
    }

    private fun distanceFrom(
        center: Long,
        quad: ml.melun.mangaview.viewer.session.SceneQuad,
    ): Long = when {
        center < quad.top.units -> quad.top.units - center
        center >= quad.top.units + quad.height.units ->
            center - (quad.top.units + quad.height.units) + 1L
        else -> 0L
    }

    private data class VisualCoverage(
        val readable: Boolean,
        val full: Boolean,
        val presentedPageId: ml.melun.mangaview.core.PageId?,
    )
}
