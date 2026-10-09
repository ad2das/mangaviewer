package ml.melun.mangaview.ui.library

import ml.melun.mangaview.core.SeriesId
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

/**
 * Providers prefix every episode with the series name ("S급 나무로 레벨업 289화"), sometimes with its
 * spaces and punctuation dropped ("엘피스전기더라스트프롤로그" under "엘피스 전기:더 라스트"). Under a
 * header that already names the series the repeat is noise, so lists show only the part that
 * differs ("289화", "프롤로그"). Letters and digits are matched; everything else is ignored.
 */
internal fun shortEpisodeTitle(seriesTitle: String, episodeTitle: String): String {
    val title = episodeTitle.trim()
    var i = 0
    var matched = 0
    for (expected in seriesTitle) {
        if (!expected.isLetterOrDigit()) continue
        while (i < title.length && !title[i].isLetterOrDigit()) i++
        if (i >= title.length || title[i].lowercaseChar() != expected.lowercaseChar()) return title
        i++
        matched++
    }
    if (matched == 0) return title
    return title.substring(i).trimStart { !it.isLetterOrDigit() && it != '(' && it != '[' }.trim().ifEmpty { title }
}

/**
 * Comic or webtoon, read from the series key: NTK files comics under /manhwa/ and WFWF prefixes
 * them with "comic:". Every other source serves webtoons only.
 */
internal fun seriesKindLabel(id: SeriesId): String =
    if (id.remoteKey.startsWith("/manhwa/") || id.remoteKey.startsWith("comic:")) "만화" else "웹툰"
