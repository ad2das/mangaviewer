package ml.melun.mangaview.viewer.session

import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.viewer.FixedPx
import ml.melun.mangaview.viewer.multiplyDivideFloorExact
import ml.melun.mangaview.viewer.saturatingMultiplyNonNegative

data class SceneQuad(
    val pageId: PageId,
    val top: FixedPx,
    val height: FixedPx,
    val sourceTopPx: Int,
    val sourceBottomPx: Int,
    val sourceHeightPx: Int,
    val visualKey: VisualKey?,
)

data class SceneSnapshot(
    val generation: Long,
    val lifecycleEpoch: Long,
    val sceneRevision: Long,
    val geometryRevision: Long,
    val viewportRevision: Long,
    val windowId: Long,
    val localOrigin: FixedPx,
    val scrollOffset: FixedPx,
    val contentHeight: FixedPx,
    val quads: List<SceneQuad>,
)

class SceneBuilder(
    private val overscanScreens: Int = 1,
) {
    init {
        require(overscanScreens >= 0)
    }

    fun build(state: ViewerSessionState): SceneSnapshot {
        val layout = state.layout ?: return SceneSnapshot(
            state.generation,
            state.lifecycleEpoch,
            state.sceneRevision,
            state.geometryRevision,
            state.viewportRevision,
            0L,
            state.scroll.contentOffset,
            FixedPx.ZERO,
            state.viewport.height,
            emptyList(),
        )
        val overscan = FixedPx(saturatingMultiplyNonNegative(
            state.viewport.height.units,
            overscanScreens,
        ))
        val chunk = saturatingMultiplyNonNegative(state.viewport.height.units, WINDOW_SCREENS)
        val windowId = if (chunk == 0L) 0L else state.scroll.contentOffset.units / chunk
        val chunkStart = FixedPx(windowId * chunk)
        val start = FixedPx((chunkStart.units - overscan.units).coerceAtLeast(0L))
        val end = (chunkStart + FixedPx(saturatingMultiplyNonNegative(
            state.viewport.height.units,
            WINDOW_SCREENS + overscanScreens + 1,
        )))
            .coerceIn(start, state.totalContentHeight)
        val quads = buildQuads(state, layout, start, end)
        return SceneSnapshot(
            state.generation,
            state.lifecycleEpoch,
            state.sceneRevision,
            state.geometryRevision,
            state.viewportRevision,
            windowId,
            start,
            state.scroll.contentOffset - start,
            end - start,
            quads,
        )
    }

    private fun buildQuads(
        state: ViewerSessionState,
        layout: ml.melun.mangaview.viewer.LayoutLedger,
        start: FixedPx,
        end: FixedPx,
    ): List<SceneQuad> = layout.indicesIntersecting(start, end).flatMap { index ->
            val entry = layout.entries[index]
            val pageTop = layout.topAt(index) - start
            val sourceHeight = entry.resolvedDimensions?.heightPx ?: entry.spec.dimensions?.heightPx
            val bands = state.visuals[entry.spec.id].orEmpty()
            if (sourceHeight == null || bands.isEmpty()) {
                listOf(SceneQuad(entry.spec.id, pageTop, entry.height, 0, 1, 1, null))
            } else {
                buildList {
                    var cursor = 0
                    bands.sortedBy(VisualBand::sourceTopPx).forEach { band ->
                        if (band.sourceTopPx > cursor) {
                            add(quad(entry.spec.id, pageTop, entry.height, cursor, band.sourceTopPx,
                                sourceHeight, null))
                        }
                        add(quad(entry.spec.id, pageTop, entry.height, band.sourceTopPx,
                            band.sourceBottomPx, sourceHeight, band.key))
                        cursor = maxOf(cursor, band.sourceBottomPx)
                    }
                    if (cursor < sourceHeight) {
                        add(quad(entry.spec.id, pageTop, entry.height, cursor, sourceHeight,
                            sourceHeight, null))
                    }
                }
            }
        }

    private companion object {
        const val WINDOW_SCREENS = 4
    }

    private fun quad(
        pageId: PageId,
        pageTop: FixedPx,
        pageHeight: FixedPx,
        sourceTop: Int,
        sourceBottom: Int,
        sourceHeight: Int,
        key: VisualKey?,
    ): SceneQuad {
        val localTop = multiplyDivideFloorExact(pageHeight.units, sourceTop, sourceHeight)
        val localBottom = multiplyDivideFloorExact(pageHeight.units, sourceBottom, sourceHeight)
        return SceneQuad(
            pageId,
            FixedPx(pageTop.units + localTop),
            FixedPx((localBottom - localTop).coerceAtLeast(1L)),
            sourceTop,
            sourceBottom,
            sourceHeight,
            key,
        )
    }
}
