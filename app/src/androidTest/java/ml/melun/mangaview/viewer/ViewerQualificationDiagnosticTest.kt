package ml.melun.mangaview.viewer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import ml.melun.mangaview.ViewerApplication
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** Bounded raw-activity diagnostics. Never contributes to a random corpus or claims a display pass. */
@RunWith(AndroidJUnit4::class)
class ViewerQualificationDiagnosticTest {
    @Test
    fun collectExactEpisodeWithoutPreparationOrCorpusCredit() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val source = SourceId(requireNotNull(arguments.getString("sourceId")))
        val series = SeriesId(source, requireNotNull(arguments.getString("seriesKey")))
        val start = EpisodeId(series, requireNotNull(arguments.getString("episodeKey")))
        val count = (arguments.getString("episodeCount") ?: "1").toInt()
        require(count in 1..5)
        val timeout = (arguments.getString("diagnosticTimeoutMillis") ?: "60000").toLong()
        require(timeout in 1_000L..300_000L)
        val runId = requireNotNull(arguments.getString("diagnosticRunId"))
        require(runId.matches(Regex("[A-Za-z0-9_-]+")))
        val root = requireNotNull(instrumentation.targetContext.getExternalFilesDir("ux-evidence"))
            .resolve("diagnostic-$runId")
        check(!root.exists() && root.mkdirs()) { "Diagnostic identifiers cannot be reused" }
        val chain = if (count == 1) listOf(start) else runBlocking {
            val app = instrumentation.targetContext.applicationContext as ViewerApplication
            val episodes = app.graph.sources.require(source).episodes(series).items
            val index = episodes.indexOfFirst { it.id == start }
            require(index >= count - 1) { "Exact supplied start lacks the requested consecutive chain" }
            (index downTo index - count + 1).map { episodes[it].id }
        }
        root.resolve("diagnostic.json").writeText(JSONObject()
            .put("mode", "DIAGNOSTIC_NO_CORPUS_CREDIT").put("consecutivePassed", 0)
            .put("entry", "RAW_ACTIVITY_NO_WARM_HELPER_NO_CACHE_MANIPULATION")
            .put("source", source.value).put("seriesKey", series.remoteKey).put("episodeKey", start.remoteKey)
            .put("timeoutMillis", timeout).toString(2))
        ViewerTenEpisodeAutoAppendHarness(instrumentation, "diagnostic-$runId", count, chain,
            externalDisplay = true, artifactParent = root, diagnosticMode = true, runTimeoutMillis = timeout)
            .run(LiveEpisode(source.value, series.remoteKey, start.remoteKey))
    }
}
