package ml.melun.mangaview.activity

import android.app.Instrumentation
import android.content.Intent
import android.util.Base64
import androidx.test.core.app.ActivityScenario
import java.io.File
import ml.melun.mangaview.ViewerApplication
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.source.SeriesKind
import ml.melun.mangaview.source.SourceSeries
import ml.melun.mangaview.source.SourceEpisode
import androidx.test.platform.app.InstrumentationRegistry
import ml.melun.mangaview.viewer.CorpusUiEntry
import ml.melun.mangaview.viewer.QualificationCorpus
import ml.melun.mangaview.viewer.runtime.ViewerLaunchSpec
import org.json.JSONObject

internal suspend fun withEngineCaptureViewer(
    instrumentation: Instrumentation,
    output: File,
    episode: EpisodeId,
    kind: SeriesKind,
    catalogUi: Boolean,
    beforeViewerOpen: () -> Unit = {},
    afterViewerClosed: suspend (ViewerActivity) -> Unit = {},
    block: suspend (ViewerActivity) -> Unit,
) {
    val context = instrumentation.targetContext
    if (catalogUi) {
        val idleOverride = InstrumentationRegistry.getArguments().getString("captureNavigationIdleMillis")
            ?.toLong()
        val asynchronousMoves = InstrumentationRegistry.getArguments().getString("captureNavigationAsyncMoves") == "true"
        CorpusUiEntry(instrumentation, File(output, "navigation-timing.json"), idleOverride, asynchronousMoves).use { ui ->
            val catalog = File(output, "catalog").apply { check(mkdir()) }
            val sources = (context.applicationContext as ViewerApplication).graph.sources
            val supplied = InstrumentationRegistry.getArguments().getString("captureCatalogMetadataBase64")
            val (series, item) = if (supplied == null) {
                QualificationCorpus(sources, catalog).resolveCatalogEpisode(episode, kind)
            } else {
                // Fixed sample metadata only. The real search and episode row below still
                // verify the current catalog identity; this cannot bypass navigation.
                val raw = Base64.decode(supplied, Base64.NO_WRAP).toString(Charsets.UTF_8)
                val metadata = JSONObject(raw)
                check(metadata.getString("sourceId") == episode.seriesId.sourceId.value &&
                    metadata.getString("seriesKey") == episode.seriesId.remoteKey &&
                    metadata.getString("episodeKey") == episode.remoteKey && metadata.getString("kind") == kind.name)
                val title = metadata.getString("seriesTitle").also { require(it.isNotBlank()) }
                val episodeTitle = metadata.getString("episodeTitle").also { require(it.isNotBlank()) }
                File(catalog, "supplied-entry.json").writeText(raw)
                SourceSeries(episode.seriesId, title) to SourceEpisode(episode, episodeTitle)
            }
            ui.prepare(kind, series, item)
            beforeViewerOpen()
            val launch = ui.open(series, item)
            File(output, "ui-launch.json").writeText(JSONObject().apply {
                put("entry", "CATALOG_EPISODE_ROW_TAP"); put("episodeId", episode.toString())
                put("seriesTitle", series.title); put("episodeTitle", item.title)
                put("tapStartedMonotonicNs", launch.startedNanos); put("tapStartedElapsedMillis", launch.startedMillis)
                put("independentCatalogOrderVerified", false); put("corpusCredit", 0)
            }.toString(2))
            AutoCloseable { ui.closeViewer() }.use { block(launch.activity) }
            ui.assertSeriesContext(series)
            File(output, "ui-return.json").writeText(JSONObject().apply {
                put("sameCatalogSeriesContextVerified", true)
                put("seriesId", series.id.toString()); put("cacheStateEquivalenceVerified", false)
            }.toString(2))
            afterViewerClosed(launch.activity)
        }
    } else {
        beforeViewerOpen()
        var activity: ViewerActivity? = null
        ActivityScenario.launch<ViewerActivity>(Intent(context, ViewerActivity::class.java).apply {
            putExtra(ViewerLaunchSpec.EXTRA_SOURCE_ID, episode.seriesId.sourceId.value)
            putExtra(ViewerLaunchSpec.EXTRA_SERIES_KEY, episode.seriesId.remoteKey)
            putExtra(ViewerLaunchSpec.EXTRA_EPISODE_KEY, episode.remoteKey)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }).use { scenario ->
            scenario.onActivity { activity = it }
            File(output, "ui-launch.json").writeText(JSONObject().put("entry", "DIRECT_VIEWER_INTENT")
                .put("episodeId", episode.toString()).put("corpusCredit", 0).toString(2))
            block(requireNotNull(activity))
        }
        afterViewerClosed(requireNotNull(activity))
    }
}
