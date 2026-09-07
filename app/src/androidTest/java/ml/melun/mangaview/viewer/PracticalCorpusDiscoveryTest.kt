package ml.melun.mangaview.viewer

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ml.melun.mangaview.ViewerApplication
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Freezes the practical four-group sample from live catalog metadata only.
 *
 * This test deliberately stops after identity selection. It does not enter the viewer, prepare
 * an episode, open a page, prefetch an image, or clear/manipulate any cache. The resulting corpus
 * is selection evidence and carries no qualifying credit by itself.
 */
@RunWith(AndroidJUnit4::class)
class PracticalCorpusDiscoveryTest {
    @Test
    fun discoverOneWorkPerGroupFromCatalogMetadata() {
        val arguments = InstrumentationRegistry.getArguments()
        val seed = requireNotNull(arguments.getString(SEED_ARGUMENT)) {
            "Missing required instrumentation argument: $SEED_ARGUMENT"
        }.toLongOrNull() ?: error("$SEED_ARGUMENT must be a signed 64-bit integer")
        val runId = requireNotNull(arguments.getString(RUN_ID_ARGUMENT)) {
            "Missing required instrumentation argument: $RUN_ID_ARGUMENT"
        }
        require(SAFE_PATH.matches(runId)) { "$RUN_ID_ARGUMENT contains unsafe path characters" }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = requireNotNull(context.getExternalFilesDir("practical-evidence"))
        check(root.mkdirs() || root.isDirectory) {
            "Could not create practical evidence root: ${root.absolutePath}"
        }
        val directory = root.resolve(runId)
        check(!directory.exists()) {
            "Practical discovery run identifiers cannot be reused: ${directory.absolutePath}"
        }
        check(directory.mkdirs()) {
            "Could not create practical evidence directory: ${directory.absolutePath}"
        }

        val started = SystemClock.elapsedRealtime()
        var selected: List<CorpusSeriesSample>? = null
        var failure: Throwable? = null
        try {
            val application = context.applicationContext as ViewerApplication
            selected = QualificationCorpus(application.graph.sources, directory)
                .discover(seed, worksPerGroup = WORKS_PER_GROUP)
            check(selected!!.size == GROUP_COUNT) {
                "Practical discovery selected ${selected!!.size} works; expected $GROUP_COUNT"
            }
            check(selected!!.sumOf { it.chain.size } == EXPECTED_EPISODES) {
                "Practical discovery selected ${selected!!.sumOf { it.chain.size }} episodes; " +
                    "expected $EXPECTED_EPISODES"
            }
        } catch (caught: Throwable) {
            // Keep the partial corpus written by QualificationCorpus. The failure receipt below
            // records the failed attempt without resetting already selected identities.
            failure = caught
        } finally {
            val corpus = directory.resolve("corpus.json")
            val partial = runCatching {
                if (corpus.isFile) JSONObject(corpus.readText()) else null
            }.getOrNull()
            directory.resolve("receipt.json").writeText(JSONObject()
                .put("schema", RECEIPT_SCHEMA)
                .put("runId", runId)
                .put("seed", seed)
                .put("scope", "PRACTICAL_20_FIXED_SAMPLE")
                .put("worksPerGroup", WORKS_PER_GROUP)
                .put("count", partial?.optInt("count", selected?.size ?: 0) ?: (selected?.size ?: 0))
                .put("episodeCount", partial?.optInt("episodeCount", selected?.sumOf { it.chain.size } ?: 0)
                    ?: (selected?.sumOf { it.chain.size } ?: 0))
                .put("populationScope", "FIRST_LATEST_CATALOG_PAGE_PER_SOURCE_KIND")
                .put("episodePageScope", "FIRST_PAGE_ONLY")
                .put("episodeOrderAssumption", "DESCENDING_ORDER_UNVERIFIED")
                .put("selectionMetadataOnly", true)
                .put("imagePrefetch", false)
                .put("cacheManipulation", false)
                .put("qualifyingCredit", false)
                .put("passed", failure == null)
                .put("status", if (failure == null) "SUCCESS" else "FAILURE")
                .put("elapsedMillis", SystemClock.elapsedRealtime() - started)
                .put("failure", failure?.stackTraceToString() ?: JSONObject.NULL)
                .toString(2))
        }
        failure?.let { throw it }
    }

    private companion object {
        const val SEED_ARGUMENT = "practicalSeed"
        const val RUN_ID_ARGUMENT = "practicalRunId"
        const val WORKS_PER_GROUP = 1
        const val GROUP_COUNT = 4
        const val EXPECTED_EPISODES = GROUP_COUNT * WORKS_PER_GROUP * CorpusSeriesSample.REQUIRED_CHAIN_EPISODES
        const val RECEIPT_SCHEMA = 1
        val SAFE_PATH = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
    }
}
