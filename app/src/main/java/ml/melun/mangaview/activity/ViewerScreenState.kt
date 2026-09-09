package ml.melun.mangaview.activity

import android.os.Bundle
import ml.melun.mangaview.core.*
import ml.melun.mangaview.engine.api.SourceAnchor
import ml.melun.mangaview.viewer.runtime.ViewerLaunchSpec

/** Saved-instance state carries source coordinates without rounding through screen pixels. */
internal object ViewerScreenState {
    fun write(spec: ViewerLaunchSpec) = Bundle().apply {
        putString("source", spec.sourceId.value)
        putString("series", spec.seriesId.remoteKey)
        putString("episode", spec.episodeId.remoteKey)
        spec.initialAnchor?.let {
            putString("anchorPage", it.pageId.remoteKey)
            putLong("sourceYQ32", it.sourceYQ32)
            putLong("viewportOffset", it.viewportOffsetUnits)
        }
        spec.initialPosition?.let {
            putString("legacyPage", it.pageId.remoteKey)
            putLong("legacyOffset", it.offsetInPageUnits)
        }
    }

    fun read(state: Bundle): ViewerLaunchSpec {
        val source = SourceId(requireNotNull(state.getString("source")))
        val series = SeriesId(source, requireNotNull(state.getString("series")))
        val episode = EpisodeId(series, requireNotNull(state.getString("episode")))
        val anchor = state.getString("anchorPage")?.let {
            SourceAnchor(PageId(episode, it), state.getLong("sourceYQ32"), state.getLong("viewportOffset"))
        }
        val position = state.getString("legacyPage")?.let {
            ReadingPosition(PageId(episode, it), state.getLong("legacyOffset"))
        }
        return ViewerLaunchSpec(source, series, episode, position, anchor)
    }
}
