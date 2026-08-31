package ml.melun.mangaview.viewer.runtime

import android.content.Intent
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.ReadingPosition
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId

internal data class ViewerLaunchSpec(
    val sourceId: SourceId,
    val seriesId: SeriesId,
    val episodeId: EpisodeId,
    val initialPosition: ReadingPosition? = null,
) {
    companion object {
        const val EXTRA_SOURCE_ID = "viewer.v2.source_id"
        const val EXTRA_SERIES_KEY = "viewer.v2.series_key"
        const val EXTRA_EPISODE_KEY = "viewer.v2.episode_key"
        const val EXTRA_PAGE_KEY = "viewer.v2.page_key"
        const val EXTRA_PAGE_OFFSET_UNITS = "viewer.v2.page_offset_units"

        fun from(intent: Intent): ViewerLaunchSpec {
            val source = SourceId(required(intent, EXTRA_SOURCE_ID))
            val series = SeriesId(source, required(intent, EXTRA_SERIES_KEY))
            val episode = EpisodeId(series, required(intent, EXTRA_EPISODE_KEY))
            val pageKey = intent.getStringExtra(EXTRA_PAGE_KEY)?.trim()?.takeIf(String::isNotEmpty)
            val offset = intent.getLongExtra(EXTRA_PAGE_OFFSET_UNITS, 0L).coerceAtLeast(0L)
            val position = pageKey?.let { ReadingPosition(PageId(episode, it), offset) }
            return ViewerLaunchSpec(source, series, episode, position)
        }

        private fun required(intent: Intent, key: String): String =
            requireNotNull(intent.getStringExtra(key)?.trim()?.takeIf(String::isNotEmpty)) {
                "Missing viewer launch field: $key"
            }
    }
}
