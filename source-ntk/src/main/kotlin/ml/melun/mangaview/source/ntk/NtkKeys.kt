package ml.melun.mangaview.source.ntk

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.SeriesId

enum class NtkKind(val pathSegment: String) {
    MANHWA("manhwa"),
    WEBTOON("webtoon"),
}

data class NtkSeriesKey(
    val kind: NtkKind,
    val workKey: String,
) {
    init {
        require(workKey.matches(Regex("[A-Za-z0-9_-]{1,160}"))) { "Invalid NTK work key" }
    }

    fun path(): String = "/${kind.pathSegment}/$workKey"

    fun episodePath(episodeKey: String): String {
        require(episodeKey.matches(Regex("[A-Za-z0-9_.-]{1,200}"))) { "Invalid NTK episode key" }
        return "${path()}/$episodeKey"
    }

    companion object {
        fun decode(seriesId: SeriesId): NtkSeriesKey {
            val match = SERIES_PATH.matchEntire(seriesId.remoteKey)
            requireNotNull(match) { "Invalid NTK series key" }
            val kind = NtkKind.entries.first { it.pathSegment == match.groupValues[1] }
            return NtkSeriesKey(kind, match.groupValues[2])
        }

        fun episodeKey(episodeId: EpisodeId): String {
            val seriesKey = decode(episodeId.seriesId)
            val prefix = "${seriesKey.path()}/"
            require(episodeId.remoteKey.startsWith(prefix)) { "Episode path does not match series" }
            return episodeId.remoteKey.removePrefix(prefix).also { seriesKey.episodePath(it) }
        }

        private val SERIES_PATH = Regex("^/(manhwa|webtoon)/([A-Za-z0-9_-]{1,160})$")
    }
}
