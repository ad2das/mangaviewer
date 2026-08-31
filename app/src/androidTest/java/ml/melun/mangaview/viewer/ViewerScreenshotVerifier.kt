package ml.melun.mangaview.viewer

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import java.io.File

internal class ViewerScreenshotVerifier(
    private val violations: MutableList<String>,
) {
    fun inspect(name: String, file: File, frame: Rect): ScreenshotInspection {
        val bitmap = requireNotNull(BitmapFactory.decodeFile(file.absolutePath)) {
            "Could not decode screenshot ${file.absolutePath}"
        }
        try {
            check(frame.left >= 0 && frame.top >= 0 &&
                frame.right <= bitmap.width && frame.bottom <= bitmap.height
            ) { "Frame $frame is outside screenshot ${bitmap.width}x${bitmap.height}" }
            val colorCounts = HashMap<Int, Int>()
            var samples = 0
            var nonBlack = 0
            val stepX = (frame.width() / SAMPLE_COLUMNS).coerceAtLeast(1)
            val stepY = (frame.height() / SAMPLE_ROWS).coerceAtLeast(1)
            var y = frame.top
            while (y < frame.bottom) {
                var x = frame.left
                while (x < frame.right) {
                    val color = bitmap.getPixel(x, y)
                    val bucket = quantizedColor(color)
                    colorCounts[bucket] = (colorCounts[bucket] ?: 0) + 1
                    if (maxOf(Color.red(color), Color.green(color), Color.blue(color)) > BLACK_LIMIT) {
                        nonBlack += 1
                    }
                    samples += 1
                    x += stepX
                }
                y += stepY
            }
            val dominant = colorCounts.values.maxOrNull() ?: 0
            val dominantRatio = if (samples == 0) 1.0 else dominant.toDouble() / samples
            val nonBlackRatio = if (samples == 0) 0.0 else nonBlack.toDouble() / samples
            if (samples == 0 || colorCounts.size < MINIMUM_COLOR_BUCKETS ||
                dominantRatio >= MAXIMUM_DOMINANT_COLOR_RATIO
            ) {
                violations += "Screenshot $name is visually blank/uniform: " +
                    "samples=$samples colors=${colorCounts.size} dominant=$dominantRatio"
            }
            return ScreenshotInspection(
                name = name,
                sampleCount = samples,
                distinctColorBuckets = colorCounts.size,
                dominantColorRatio = dominantRatio,
                nonBlackRatio = nonBlackRatio,
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun quantizedColor(color: Int): Int =
        (Color.red(color) shr COLOR_SHIFT shl 8) or
            (Color.green(color) shr COLOR_SHIFT shl 4) or
            (Color.blue(color) shr COLOR_SHIFT)

    private companion object {
        const val SAMPLE_COLUMNS = 96
        const val SAMPLE_ROWS = 128
        const val COLOR_SHIFT = 4
        const val BLACK_LIMIT = 8
        const val MINIMUM_COLOR_BUCKETS = 2
        const val MAXIMUM_DOMINANT_COLOR_RATIO = 0.999
    }
}
