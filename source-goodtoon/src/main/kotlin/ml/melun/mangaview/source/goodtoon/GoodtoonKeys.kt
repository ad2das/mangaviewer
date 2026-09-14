package ml.melun.mangaview.source.goodtoon

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId

/** Source id shared by the catalog adapter, the engine planner and the app graph. */
const val GOODTOON_SOURCE_ID = "goodtoon"

/**
 * Series identity is the decoded provider slug from `/manga/<slug>/`. The provider uses ASCII
 * slugs (`gt-21840`) and percent-encoded Korean slugs for the same route shape.
 */
data class GoodtoonSeriesKey(val slug: String) {
    init {
        require(slug.isNotBlank()) { "GoodToon series slug must not be blank" }
        require('/' !in slug && '\\' !in slug && slug != "." && slug != ".." && !slug.startsWith('.')) {
            "GoodToon series slug must be a single path segment"
        }
    }

    fun encode(): String = slug

    fun path(): String = "/manga/${urlEncode(slug)}/"

    fun chapterPath(episodeKey: String): String = path() + urlEncode(episodeKey) + "/"

    fun chaptersPath(page: Int = 1): String {
        require(page > 0) { "GoodToon chapter list page must be positive" }
        return "${path()}ajax/chapters/?t=$page"
    }

    companion object {
        fun decode(seriesId: SeriesId): GoodtoonSeriesKey {
            require(seriesId.sourceId == goodtoonSourceId()) { "Series belongs to another source" }
            return GoodtoonSeriesKey(seriesId.remoteKey)
        }
    }
}

/** Episode identity is the chapter slug (`51`, `chapter-48`); it is not always numeric. */
data class GoodtoonEpisodeKey(val slug: String) {
    init {
        require(slug.isNotBlank()) { "GoodToon episode slug must not be blank" }
        require('/' !in slug && '\\' !in slug && slug != "." && slug != "..") {
            "GoodToon episode slug must be a single path segment"
        }
    }

    fun encode(): String = slug

    companion object {
        fun decode(episodeId: EpisodeId): GoodtoonEpisodeKey {
            require(episodeId.seriesId.sourceId == goodtoonSourceId()) { "Episode belongs to another source" }
            return GoodtoonEpisodeKey(episodeId.remoteKey)
        }
    }
}

internal fun goodtoonSourceId(): SourceId = SourceId(GOODTOON_SOURCE_ID)

internal fun urlEncode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

internal fun urlDecodeOrNull(value: String): String? =
    runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrNull()
