package ml.melun.mangaview.viewer.runtime

import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.viewer.FixedPx
import ml.melun.mangaview.viewer.session.SceneQuad

/** Image identity means exact page/row ownership of a successfully uploaded texture. */
internal data class PresentedImageRegion(
    val rendererIdentity: Long,
    val token: Long,
    val generation: Long,
    val pageId: PageId,
    val sourceTopRow: Int,
    val sourceBottomRowExclusive: Int,
    val sourceHeightRows: Int,
    val presentedNanos: Long,
    val timestampKind: PresentationTimestampKind,
    val imageIdentityVerified: Boolean,
    val bufferFrameId: Long = 0L,
    val submittedAtNanos: Long = 0L,
    val renderLatencyNanos: Long = 0L,
    val screenTopPx: Int = 0,
    val screenBottomPx: Int = 0,
    val geometryRevision: Long = 0L,
    val userInputRevision: Long = 0L,
    val viewportHeightPx: Int = 0,
    val viewportWidthPx: Int = 0,
)

internal data class PresentedImageRegionBatch(
    val nextSequence: Long,
    val regions: List<PresentedImageRegion>,
    val dropped: Boolean,
)

internal object PresentedRegionMapper {
    fun from(rendererIdentity: Long, value: OwnedPresentation): List<PresentedImageRegion> {
        val viewport = value.metadata?.viewportHeightUnits ?: return emptyList()
        if (viewport <= 0L) return emptyList()
        val top = pixels(value.scene.scrollOffset.units)
        val bottom = top + pixels(viewport)
        return value.scene.quads.mapNotNull { quad ->
            val key = quad.visualKey?.value ?: return@mapNotNull null
            val rows = clippedRows(quad, top, bottom) ?: return@mapNotNull null
            val quadTop = pixels(quad.top.units)
            val quadBottom = maxOf(quadTop + 1L, pixels((quad.top + quad.height).units))
            PresentedImageRegion(
                rendererIdentity, value.token, value.scene.generation, quad.pageId,
                rows.first, rows.second, quad.sourceHeightPx, value.presentedAtNanos,
                value.timestampKind, value.verifiedTextureIdentities[key]?.matches(quad) == true, value.bufferFrameId,
                value.submittedAtNanos, value.renderLatencyNanos,
                Math.toIntExact(maxOf(top, quadTop) - top),
                Math.toIntExact(minOf(bottom, quadBottom) - top),
                value.metadata.geometryRevision, value.metadata.userInputRevision,
                Math.toIntExact(pixels(viewport)), Math.toIntExact(pixels(value.metadata.viewportWidthUnits)),
            )
        }
    }

    private fun clippedRows(quad: SceneQuad, start: Long, end: Long): Pair<Int, Int>? {
        val top = pixels(quad.top.units)
        val bottom = maxOf(top + 1L, pixels((quad.top + quad.height).units))
        val visibleTop = maxOf(start, top)
        val visibleBottom = minOf(end, bottom)
        val rows = (quad.sourceBottomPx - quad.sourceTopPx).toLong()
        if (visibleBottom <= visibleTop || rows <= 0L) return null
        val height = bottom - top
        // Only full source rows within the exact integer geometry sent to GLES earn coverage.
        val first = quad.sourceTopPx + ((visibleTop - top) * rows + height - 1L) / height
        val last = quad.sourceTopPx + (visibleBottom - top) * rows / height
        if (last <= first) return null
        return Math.toIntExact(first) to Math.toIntExact(last)
    }

    private fun pixels(units: Long): Long = Math.floorDiv(units, FixedPx.UNITS_PER_PIXEL)
}
