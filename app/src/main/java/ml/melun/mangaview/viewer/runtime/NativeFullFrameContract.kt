package ml.melun.mangaview.viewer.runtime

import ml.melun.mangaview.viewer.FramePlan
import ml.melun.mangaview.viewer.FixedPx

internal data class NativeScrollSubmission(
    val structureEpoch: Long,
    val viewportTop: Int,
    val readableActualContent: Boolean,
    val fullVisualCoverage: Boolean,
    val fullActualCoverage: Boolean,
    val changesViewport: Boolean,
) {
    val hasContent: Boolean get() = readableActualContent
}

/** The exact immutable state installed by the last full native frame submission. */
internal data class NativeFullFrameContract(
    val structureEpoch: Long,
    val plan: FramePlan,
    val renderWidth: Int,
    val renderHeight: Int,
    val bandHeight: Int,
    val coordinateOrigin: FixedPx,
    val localTileRanges: IntArray,
    val localVisualRanges: IntArray = localTileRanges,
    val sceneSignature: Long = 0L,
) {
    init {
        require(structureEpoch > 0L)
        require(renderWidth > 0 && renderHeight > 0)
        require(bandHeight >= renderHeight)
    }

    fun scrollSubmission(next: FramePlan): NativeScrollSubmission? {
        if (next.scrollOffset == plan.scrollOffset ||
            next.generation != plan.generation ||
            next.viewport != plan.viewport ||
            next.contentHeight != plan.contentHeight ||
            next.pages != plan.pages ||
            next.loading != plan.loading
        ) return null
        val maximumOffset = (next.contentHeight.units - next.viewport.height.units).coerceAtLeast(0L)
        if (next.scrollOffset.units !in 0L..maximumOffset) return null
        val scale = renderWidth / next.viewport.width.toPixels()
        val localOffsetUnits = next.scrollOffset.units - coordinateOrigin.units
        if (localOffsetUnits < 0L) return null
        val scaledTop = localOffsetUnits.toDouble() / FixedPx.UNITS_PER_PIXEL * scale
        if (!scaledTop.isFinite()) return null
        val viewportTop = scaledTop.toInt()
        if (viewportTop !in 0..(bandHeight - renderHeight)) return null
        val installedLocalOffsetUnits = plan.scrollOffset.units - coordinateOrigin.units
        if (installedLocalOffsetUnits < 0L) return null
        val installedScaledTop = installedLocalOffsetUnits.toDouble() /
            FixedPx.UNITS_PER_PIXEL * scale
        if (!installedScaledTop.isFinite()) return null
        return NativeScrollSubmission(
            structureEpoch = structureEpoch,
            viewportTop = viewportTop,
            readableActualContent = localTileRanges.containsPoint(
                viewportTop.toLong() + renderHeight.toLong() / 2L,
            ),
            fullVisualCoverage = localVisualRanges.covers(viewportTop, renderHeight),
            fullActualCoverage = localTileRanges.covers(viewportTop, renderHeight),
            changesViewport = viewportTop != installedScaledTop.toInt(),
        )
    }

    fun advancedTo(next: FramePlan): NativeFullFrameContract = copy(plan = next)
}
