package ml.melun.mangaview.content

import java.math.BigInteger
import ml.melun.mangaview.core.toLongExact
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.source.PageFetchPriority
import ml.melun.mangaview.viewer.session.DemandClass
import ml.melun.mangaview.viewer.session.SemanticViewportAnchor
import ml.melun.mangaview.viewer.session.SourceRangeFraction

internal const val MAX_FETCH_RETRIES = 2
internal const val MAX_DECODE_RETRIES = 2

/** Extra background attempts while a failed page is still demanded. */
internal const val MAX_AUTO_RETRIES = 6

/** Suppress warm decode for this long after a system memory-pressure signal. */
internal const val MEMORY_PRESSURE_COOLDOWN_MILLIS = 5_000L

private const val TARGET_BAND_HEIGHT_PX = 2_048L
private const val AUTO_RETRY_MAX_DELAY_MILLIS = 30_000L

internal fun hardLane(demandClass: DemandClass): Boolean =
    demandClass == DemandClass.RESUME_ANCHOR || demandClass == DemandClass.VISIBLE

internal fun fetchPriority(
    demandClass: DemandClass,
    coldFocus: Boolean = false,
): PageFetchPriority = if (coldFocus) PageFetchPriority.FOCUS else when (demandClass) {
    DemandClass.RESUME_ANCHOR -> PageFetchPriority.FOCUS
    DemandClass.VISIBLE -> PageFetchPriority.VISIBLE
    DemandClass.CURRENT_FORWARD_NEAR -> PageFetchPriority.IMMINENT_FORWARD
    DemandClass.ADJACENT_PREFIX -> PageFetchPriority.ADJACENT_FORWARD
    DemandClass.CURRENT_FORWARD_FAR -> PageFetchPriority.DISTANT_FORWARD
    DemandClass.CURRENT_BEHIND_NEAR -> PageFetchPriority.BACKGROUND
    DemandClass.BEHIND -> PageFetchPriority.BACKGROUND
}

internal fun nextDecodeRange(
    requested: SourceRangeFraction,
    dimensions: PageDimensions,
    displayWidth: Int,
    residents: List<TextureRef>,
): SourceRowRange? {
    val scaledHeight = dimensions.heightPx.toLong() * displayWidth / dimensions.widthPx
    val bandCount = ((scaledHeight + TARGET_BAND_HEIGHT_PX - 1L) / TARGET_BAND_HEIGHT_PX)
        .coerceIn(1L, dimensions.heightPx.toLong())
    val height = dimensions.heightPx.toLong()
    val unit = SemanticViewportAnchor.Q32_ONE
    val firstRow = multiplyDivide(requested.startQ32, height, unit)
    val bottomRow = (requested.endQ32 * height + unit - 1L) / unit
    val startBand = ((firstRow + 1L) * bandCount - 1L) / height
    val endBand = (bottomRow * bandCount - 1L) / height
    var band = startBand
    while (band <= endBand) {
        val candidate = SourceRowRange(
            (dimensions.heightPx.toLong() * band / bandCount).toInt(),
            (dimensions.heightPx.toLong() * (band + 1L) / bandCount).toInt(),
        )
        if (residents.none { it.covers(candidate) }) return candidate
        band += 1L
    }
    return null
}

internal fun TextureRef.covers(range: SourceRowRange): Boolean =
    sourceTopPx <= range.top && sourceBottomPx >= range.bottomExclusive

private fun multiplyDivide(value: Long, multiplier: Long, divisor: Long): Long =
    BigInteger.valueOf(value).multiply(BigInteger.valueOf(multiplier))
        .divide(BigInteger.valueOf(divisor)).toLongExact()

internal fun retryDelay(attempt: Int): Long = when {
    attempt <= 1 -> 250L
    attempt <= 3 -> 1_000L
    else -> (1_000L shl (attempt - 3).coerceAtMost(5)).coerceAtMost(AUTO_RETRY_MAX_DELAY_MILLIS)
}

fun adaptiveResidentBudgetBytes(totalPhysicalMemoryBytes: Long?): Long =
    totalPhysicalMemoryBytes?.takeIf { it > 0L }?.let {
        (it / 32L).coerceIn(1L, 384L * 1024L * 1024L)
    } ?: (128L * 1024L * 1024L)
