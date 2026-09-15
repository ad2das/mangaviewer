package ml.melun.mangaview.ui.library

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max

private val LIBRARY_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy.MM.dd")

internal fun libraryDate(epochMillis: Long): String =
    LIBRARY_DATE_FORMAT.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

/** Korean relative time used on continuation and bookmark cards instead of a fake progress bar. */
internal fun libraryRelativeTime(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
    val elapsedMinutes = max(0L, now - epochMillis) / 60_000L
    return when {
        elapsedMinutes < 1 -> "방금 전"
        elapsedMinutes < 60 -> "${elapsedMinutes}분 전"
        elapsedMinutes < 60 * 24 -> "${elapsedMinutes / 60}시간 전"
        elapsedMinutes < 60 * 24 * 7 -> "${elapsedMinutes / (60 * 24)}일 전"
        elapsedMinutes < 60 * 24 * 30 -> "${elapsedMinutes / (60 * 24 * 7)}주 전"
        else -> libraryDate(epochMillis)
    }
}
