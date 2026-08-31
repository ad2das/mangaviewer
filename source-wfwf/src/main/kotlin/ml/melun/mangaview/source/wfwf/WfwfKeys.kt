package ml.melun.mangaview.source.wfwf

import ml.melun.mangaview.core.SeriesId

enum class WfwfKind(val wireName: String) {
    COMIC("comic"),
    WEBTOON("webtoon"),
}

data class WfwfSeriesKey(
    val kind: WfwfKind,
    val titleId: Long,
) {
    init {
        require(titleId > 0L) { "WFWF title id must be positive" }
    }

    fun encode(): String = "${kind.wireName}:$titleId"

    companion object {
        fun decode(seriesId: SeriesId): WfwfSeriesKey {
            val parts = seriesId.remoteKey.split(':', limit = 2)
            require(parts.size == 2) { "Invalid WFWF series key" }
            val kind = WfwfKind.entries.firstOrNull { it.wireName == parts[0] }
            return WfwfSeriesKey(requireNotNull(kind) { "Invalid WFWF content kind" }, parts[1].toLong())
        }
    }
}
