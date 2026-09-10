package ml.melun.mangaview.viewer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.SecureRandom
import kotlinx.coroutines.runBlocking
import ml.melun.mangaview.ViewerApplication
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.source.CatalogOrder
import ml.melun.mangaview.source.CatalogQuery
import ml.melun.mangaview.source.SeriesKind
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** Bounded random sample of one episode per source and kind; metadata only, no viewer. */
@RunWith(AndroidJUnit4::class)
class RandomSampleSelectionTest {
    @Test fun sampleRandomEpisodePerSourceKind() {
        val arguments = InstrumentationRegistry.getArguments()
        val runId = requireNotNull(arguments.getString("randomSampleRunId")) { "Missing randomSampleRunId" }
        require(Regex("[A-Za-z0-9][A-Za-z0-9._-]*").matches(runId)) { "Unsafe randomSampleRunId" }
        val seedText = requireNotNull(arguments.getString("randomSampleSeed")) { "Missing randomSampleSeed" }
        val random = SecureRandom(parseRandomTwelveSeed(seedText))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = requireNotNull(context.getExternalFilesDir("random-sample-selection"))
        check(root.mkdirs() || root.isDirectory)
        val output = root.resolve(runId)
        check(!output.exists()) { "Random sample run ID cannot be reused: $output" }
        check(output.mkdirs())
        val application = context.applicationContext as ViewerApplication
        val sources = application.graph.sources
        val cases = JSONArray()
        runBlocking {
            for (bucket in BUCKETS) {
                val source = sources.require(bucket.sourceId)
                val catalog = source.catalog(CatalogQuery(bucket.kind, CatalogOrder.LATEST))
                check(catalog.items.isNotEmpty()) { "Empty catalog for ${bucket.sourceId}/${bucket.kind}" }
                val start = random.nextInt(catalog.items.size)
                var chosen = false
                for (offset in catalog.items.indices) {
                    val series = catalog.items[(start + offset) % catalog.items.size]
                    val episodes = source.episodes(series.id)
                    if (episodes.items.isEmpty()) continue
                    val episode = episodes.items[random.nextInt(episodes.items.size)]
                    cases.put(JSONObject()
                        .put("sourceId", bucket.sourceId.value).put("kind", bucket.kind.name)
                        .put("seriesKey", series.id.remoteKey).put("seriesTitle", series.title)
                        .put("episodeKey", episode.id.remoteKey).put("episodeTitle", episode.title)
                        .put("seriesRank", "sample").put("episodeRank", "sample"))
                    chosen = true
                    break
                }
                check(chosen) { "No episode-bearing series sampled for ${bucket.sourceId}/${bucket.kind}" }
            }
        }
        check(cases.length() == BUCKETS.size)
        output.resolve("cases.json").writeText(JSONObject().put("schema", 2).put("seed", seedText.lowercase())
            .put("algorithm", "RANDOM_LATEST_PAGE_SAMPLE_V1").put("cases", cases).toString(2))
        output.resolve("receipt.json").writeText(JSONObject().put("schema", 1).put("runId", runId)
            .put("status", "SUCCESS").put("selectedCases", cases.length()).put("selectionMetadataOnly", true)
            .put("viewerOpened", false).toString(2))
    }

    private data class Bucket(val sourceId: SourceId, val kind: SeriesKind)

    private companion object {
        val BUCKETS = listOf(
            Bucket(SourceId("ntk"), SeriesKind.COMIC), Bucket(SourceId("ntk"), SeriesKind.WEBTOON),
            Bucket(SourceId("wfwf"), SeriesKind.COMIC), Bucket(SourceId("wfwf"), SeriesKind.WEBTOON),
        )
    }
}
