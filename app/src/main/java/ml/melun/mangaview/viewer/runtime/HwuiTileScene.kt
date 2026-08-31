package ml.melun.mangaview.viewer.runtime

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF

/**
 * Immutable geometry for one viewport-local HWUI scene.
 *
 * Pixel commands are recorded into the View's current display list on every draw. Keeping a
 * nested RenderNode across detach/attach would also keep its old GPU sampling resources alive;
 * those resources are not a stable ownership boundary after HOME or a graphics-context reset.
 */
internal class HwuiTileScene(
    val signature: Long,
    private val tiles: HardwareTileStore,
    private val placements: List<Placement>,
) {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { isDither = false }
    private val source = Rect()
    private val destination = RectF()

    fun draw(canvas: Canvas): Boolean {
        val resolved = resolveContent() ?: return false
        canvas.drawColor(Color.BLACK)
        var contentIndex = 0
        placements.forEach { placement ->
            when (placement) {
                is Placement.Content -> drawContent(canvas, placement, resolved[contentIndex++])
                is Placement.Loading -> drawLoading(canvas, placement)
            }
        }
        return true
    }

    private fun resolveContent(): List<Bitmap>? {
        val resolved = ArrayList<Bitmap>()
        placements.forEach { placement ->
            if (placement is Placement.Content) {
                val bitmap = tiles.bitmap(placement.handle, placement.version)
                    ?.takeUnless(Bitmap::isRecycled)
                    ?: return null
                resolved += bitmap
            }
        }
        return resolved
    }

    private fun drawContent(canvas: Canvas, placement: Placement.Content, bitmap: Bitmap) {
        source.set(0, 0, placement.contentWidth, placement.contentHeight)
        destination.set(
            0f,
            placement.top.toFloat(),
            placement.renderWidth.toFloat(),
            placement.bottom.toFloat(),
        )
        paint.color = Color.WHITE
        canvas.drawBitmap(bitmap, source, destination, paint)
    }

    private fun drawLoading(canvas: Canvas, placement: Placement.Loading) {
        paint.color = LOADING_COLOR
        canvas.drawRect(
            0f,
            placement.top.toFloat(),
            placement.renderWidth.toFloat(),
            placement.bottom.toFloat(),
            paint,
        )
    }

    internal sealed interface Placement {
        val top: Int
        val bottom: Int
        val renderWidth: Int

        data class Content(
            val handle: Long,
            val version: Long,
            val contentWidth: Int,
            val contentHeight: Int,
            override val renderWidth: Int,
            override val top: Int,
            override val bottom: Int,
        ) : Placement

        data class Loading(
            override val renderWidth: Int,
            override val top: Int,
            override val bottom: Int,
        ) : Placement
    }

    private companion object {
        const val LOADING_COLOR = 0xFF242424.toInt()
    }
}

/** Builds immutable placement data and verifies that every published content tile still exists. */
internal class HwuiTileSceneBuilder(
    private val tiles: HardwareTileStore,
) {
    fun build(frame: PackedNativeFrame): HwuiTileScene? {
        val placements = ArrayList<HwuiTileScene.Placement>(frame.count)
        repeat(frame.count) { index ->
            val offset = index * TILE_STRIDE
            val top = frame.geometryData[index * 2]
            val bottom = frame.geometryData[index * 2 + 1]
            val placement = if (frame.tileData[offset + RESOURCE_KIND] == CONTENT_RESOURCE) {
                contentPlacement(frame, offset, top, bottom) ?: return null
            } else {
                HwuiTileScene.Placement.Loading(frame.width, top, bottom)
            }
            placements += placement
        }
        return HwuiTileScene(frame.sceneSignature, tiles, placements)
    }

    private fun contentPlacement(
        frame: PackedNativeFrame,
        offset: Int,
        top: Int,
        bottom: Int,
    ): HwuiTileScene.Placement.Content? {
        val handle = unsignedHalves(frame.tileData[offset + HANDLE_LOW], frame.tileData[offset + HANDLE_HIGH])
        val version = unsignedHalves(frame.tileData[offset + VERSION_LOW], frame.tileData[offset + VERSION_HIGH])
        val bitmap = tiles.bitmap(handle, version)?.takeUnless(Bitmap::isRecycled) ?: return null
        val contentWidth = frame.tileData[offset + CONTENT_WIDTH].takeIf { it > 0 }
            ?.coerceAtMost(bitmap.width) ?: bitmap.width
        val sourceSpan = frame.tileData[offset + SOURCE_BOTTOM] - frame.tileData[offset + SOURCE_TOP]
        val sourceWidth = frame.tileData[offset + SOURCE_WIDTH].coerceAtLeast(1)
        val contentHeight = ((sourceSpan.toLong() * contentWidth + sourceWidth - 1L) / sourceWidth)
            .toInt().coerceIn(1, bitmap.height)
        return HwuiTileScene.Placement.Content(
            handle = handle,
            version = version,
            contentWidth = contentWidth,
            contentHeight = contentHeight,
            renderWidth = frame.width,
            top = top,
            bottom = bottom,
        )
    }

    private fun unsignedHalves(low: Int, high: Int): Long =
        low.toUInt().toLong() or (high.toUInt().toLong() shl 32)

    private companion object {
        const val TILE_STRIDE = 12
        const val SOURCE_TOP = 2
        const val SOURCE_BOTTOM = 3
        const val SOURCE_WIDTH = 4
        const val CONTENT_WIDTH = 6
        const val RESOURCE_KIND = 7
        const val HANDLE_LOW = 8
        const val HANDLE_HIGH = 9
        const val VERSION_LOW = 10
        const val VERSION_HIGH = 11
        const val CONTENT_RESOURCE = 2
    }
}
