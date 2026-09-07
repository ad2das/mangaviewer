package ml.melun.mangaview.viewer.session

import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.ReadingPosition
import ml.melun.mangaview.viewer.FixedPx
import ml.melun.mangaview.viewer.LayoutLedger
import ml.melun.mangaview.viewer.ScrollMutationCause
import ml.melun.mangaview.viewer.Viewport

data class CanonicalScroll(
    val contentOffset: FixedPx = FixedPx.ZERO,
    val velocityPixelsPerSecond: Float = 0.0F,
)

sealed interface GeometryAnchor {
    val viewportOffset: FixedPx
}

data class SemanticViewportAnchor(
    val pageId: PageId,
    val offsetInPage: FixedPx,
    override val viewportOffset: FixedPx,
    val basisViewportWidth: FixedPx,
) : GeometryAnchor {
    init {
        require(offsetInPage >= FixedPx.ZERO)
        require(basisViewportWidth > FixedPx.ZERO)
    }

    companion object {
        const val Q32_ONE: Long = 1L shl 32
    }
}

data class RunwayViewportAnchor(
    val offsetInRunway: FixedPx,
    override val viewportOffset: FixedPx,
) : GeometryAnchor {
    init {
        require(offsetInRunway >= FixedPx.ZERO)
    }
}

data class TerminalRunway(
    val height: FixedPx,
    val nextEpisodeExpected: Boolean,
) {
    init {
        require(height >= FixedPx.ZERO)
    }
}

data class OpeningBasis(
    val savedPositionResolved: Boolean = false,
    val savedPosition: ReadingPosition? = null,
    val accumulatedInput: FixedPx = FixedPx.ZERO,
    val inputFloor: FixedPx = FixedPx.ZERO,
    val applied: Boolean = false,
)

@JvmInline
value class VisualKey(val value: Long) {
    init {
        require(value > 0L)
    }
}

data class VisualBand(
    val sourceTopPx: Int,
    val sourceBottomPx: Int,
    val sourceHeightPx: Int,
    val key: VisualKey,
) {
    init {
        require(sourceTopPx >= 0)
        require(sourceBottomPx > sourceTopPx)
        require(sourceBottomPx <= sourceHeightPx)
    }
}

data class ViewerSessionState(
    val generation: Long,
    val lifecycleEpoch: Long,
    val viewport: Viewport,
    val timeline: EpisodeTimeline,
    val layout: LayoutLedger?,
    val runway: TerminalRunway,
    val scroll: CanonicalScroll,
    val opening: OpeningBasis,
    val visuals: Map<PageId, List<VisualBand>>,
    val geometryRevision: Long,
    val sceneRevision: Long,
    val viewportRevision: Long,
    val foreground: Boolean,
    val surfaceAttached: Boolean,
    val scrollRevision: Long,
    val scrollCause: ScrollMutationCause,
    val userInputRevision: Long,
) {
    val realContentHeight: FixedPx get() = layout?.totalHeight ?: FixedPx.ZERO
    val totalContentHeight: FixedPx get() = realContentHeight + runway.height

    val maximumScroll: FixedPx
        get() = FixedPx(
            (totalContentHeight.units - viewport.height.units).coerceAtLeast(0L),
        )
}

sealed interface SessionEffect {
    data class DemandChanged(val snapshot: DemandSnapshot) : SessionEffect
    data class SceneChanged(val snapshot: SceneSnapshot) : SessionEffect
    data class PersistPosition(val position: ReadingPosition) : SessionEffect
}

data class SessionChange(
    val state: ViewerSessionState,
    val effects: List<SessionEffect>,
)
