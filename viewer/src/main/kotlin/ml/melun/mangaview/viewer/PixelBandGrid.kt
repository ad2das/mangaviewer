package ml.melun.mangaview.viewer

import ml.melun.mangaview.core.PageDimensions

data class PixelBand(
    val sourceTopPx: Int,
    val sourceBottomPx: Int,
    val displayWidthPx: Int,
) {
    init {
        require(sourceTopPx >= 0 && sourceBottomPx > sourceTopPx) {
            "Pixel band source range is invalid"
        }
        require(displayWidthPx > 0) { "Pixel band display width is invalid" }
    }
}

class PixelBandGrid(
    private val maximumDisplayWidth: Int = 1_440,
    private val maximumDisplayBandHeight: Int = 512,
    private val maximumScratchBytes: Long = 32L * 1_024L * 1_024L,
) {
    init {
        require(maximumDisplayWidth > 0)
        require(maximumDisplayBandHeight > 0)
        require(maximumScratchBytes >= 4L)
    }

    fun displayWidth(dimensions: PageDimensions, requestedWidthPx: Int): Int =
        minOf(maximumDisplayWidth, dimensions.widthPx, requestedWidthPx.coerceAtLeast(1))

    fun sourceRowsPerBand(dimensions: PageDimensions): Int {
        // Boundaries must not change when a window is resized. A stable source grid lets a
        // higher-resolution tile atomically replace the exact same band without creating gaps.
        val outputBound = maximumDisplayBandHeight.toLong() *
            dimensions.widthPx / minOf(maximumDisplayWidth, dimensions.widthPx)
        val scratchRowBytes = dimensions.widthPx.toLong() * BYTES_PER_PIXEL
        // A corrupt or unusually wide header can make even one source row exceed the target
        // scratch budget. Keep the reducer total and let the decoder report allocation failure;
        // geometry and user input must not crash merely because metadata is extreme.
        val scratchBound = (maximumScratchBytes / scratchRowBytes).coerceAtLeast(1L)
        return minOf(dimensions.heightPx.toLong(), outputBound, scratchBound)
            .coerceAtLeast(1L)
            .toInt()
    }

    fun bandsIntersecting(
        dimensions: PageDimensions,
        sourceStartPx: Int,
        sourceEndExclusivePx: Int,
        requestedWidthPx: Int = maximumDisplayWidth,
    ): List<PixelBand> {
        val start = sourceStartPx.coerceIn(0, dimensions.heightPx)
        val end = sourceEndExclusivePx.coerceIn(start, dimensions.heightPx)
        if (start == end) return emptyList()
        val rows = sourceRowsPerBand(dimensions)
        val displayWidth = displayWidth(dimensions, requestedWidthPx)
        val first = start / rows
        val last = (end - 1) / rows
        return (first..last).map { index ->
            val top = index * rows
            PixelBand(top, minOf(dimensions.heightPx, top + rows), displayWidth)
        }
    }

    private companion object {
        const val BYTES_PER_PIXEL = 4L
    }
}
