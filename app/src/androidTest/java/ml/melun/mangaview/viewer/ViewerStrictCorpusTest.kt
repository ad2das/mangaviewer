package ml.melun.mangaview.viewer

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ViewerStrictCorpusTest {
    @Test
    fun tenDistinctEpisodesPassAsOneUnbrokenRecord() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val evidence = requireNotNull(context.getExternalFilesDir("ux-evidence"))
            .resolve("strict-corpus-${SystemClock.elapsedRealtime()}")
            .apply { check(mkdirs() || isDirectory) }
        val entries = JSONArray()
        val corpus = strictCorpus()
        verifyCorpusShape(corpus)

        var failure: Throwable? = null
        corpus.forEachIndexed { index, candidate ->
            if (failure != null) return@forEachIndexed
            val profile = when {
                index == 0 -> COLD_ENTRY_PROFILE
                candidate.source == "ntk" -> NTK_DETAIL_PROFILE
                else -> NORMAL_PROFILE
            }
            val harness = ViewerUxTestHarness(
                instrumentation = instrumentation,
                artifactPrefix = "strict-corpus-${index + 1}-${candidate.source}-${candidate.kind}",
            )
            try {
                val result = if (candidate.source == "ntk") {
                    withProductionDetailWarmup(instrumentation, candidate.episode) {
                        harness.run(candidate.episode)
                    }
                } else {
                    harness.run(candidate.episode)
                }
                entries.put(candidate.toJson(index, profile, harness.evidenceDirectory, result, null))
            } catch (caught: Throwable) {
                failure = caught
                entries.put(candidate.toJson(index, profile, harness.evidenceDirectory, null, caught))
            }
        }

        val passed = failure == null && entries.length() == corpus.size
        val report = File(evidence, "summary.json").apply {
            writeText(JSONObject()
                .put("schema", 1)
                .put("passed", passed)
                .put("requiredConsecutiveEpisodes", corpus.size)
                .put("completedEpisodes", entries.length())
                .put("consecutivePassed", if (passed) corpus.size else 0)
                .put("failurePolicy", "ANY_FAILURE_RESETS_RECORD_TO_ZERO")
                .put("cacheManipulation", "NONE")
                .put("cacheObservation", "NOT_READ")
                .put("gestureInjection", "UiDevice.swipe only")
                .put("thresholds", JSONObject()
                    .put("safeBounds", "EXACT")
                    .put(
                        "firstFrame",
                        "4000ms maximum; if external source/network exceeds it, " +
                            "decode <350ms, present <150ms, combined app tail <500ms",
                    )
                    .put("fullEpisodeVerified", "measured; 30000ms no-progress timeout only")
                    .put("gestureDisplacement", "NONZERO_AND_AT_MOST_8_VIEWPORTS")
                    .put("idleScrollDrift", 0)
                    .put("nativeRenderP95NanosExclusive", 16_000_000)
                    .put("motionMissedFrameRatioExclusive", 0.01)
                    .put("freezeCount", 0)
                    .put("jumpBlankOverlapWrongPageCount", 0)
                    .put("pssIncreaseKibMaximum", 192 * 1_024)
                    .put("pssCheckpointRangeKibMaximum", 64 * 1_024))
                .put("entries", entries)
                .toString(2))
        }
        check(passed) {
            "Strict corpus record reset after ${entries.length()}/${corpus.size}: " +
                "${failure?.message}; evidence=${report.absolutePath}"
        }
    }

    private fun verifyCorpusShape(corpus: List<StrictCorpusEpisode>) {
        check(corpus.size == REQUIRED_EPISODES)
        check(corpus.map { it.episode }.distinct().size == REQUIRED_EPISODES)
        check(corpus.map { it.episode.seriesKey }.distinct().size == REQUIRED_EPISODES)
        check(corpus.map { it.source }.toSet() == setOf("ntk", "wfwf"))
        check(corpus.map { it.kind }.toSet() == setOf("manhwa", "webtoon"))
    }

    private fun StrictCorpusEpisode.toJson(
        index: Int,
        profile: String,
        evidence: File,
        result: ViewerUxResult?,
        failure: Throwable?,
    ): JSONObject = JSONObject()
        .put("ordinal", index + 1)
        .put("source", source)
        .put("kind", kind)
        .put("seriesKey", episode.seriesKey)
        .put("episodeKey", episode.episodeKey)
        .put("launchProfile", profile)
        .put("episodeReuseWithinSuite", false)
        .put("provenance", provenance)
        .put("passed", result != null && failure == null)
        .put("firstFrameMillis", result?.firstFrameMillis)
        .put("fullEpisodeVerifiedMillis", result?.fullEpisodeVerifiedMillis)
        .put("observedSourceId", result?.observedEpisode?.sourceId)
        .put("observedSeriesKey", result?.observedEpisode?.seriesKey)
        .put("observedEpisodeKey", result?.observedEpisode?.episodeKey)
        .put("evidence", evidence.absolutePath)
        .put("failure", failure?.message)

    private companion object {
        const val REQUIRED_EPISODES = 10
        const val COLD_ENTRY_PROFILE = "COLD_SUITE_ENTRY_UNMODIFIED_APP_STATE"
        const val NORMAL_PROFILE = "NORMAL_CONTINUATION_UNMODIFIED_APP_STATE"
        const val NTK_DETAIL_PROFILE =
            "PRODUCTION_DETAIL_FIXED_2500MS_NO_READINESS_WAIT_UNMODIFIED_APP_STATE"
    }
}

private data class StrictCorpusEpisode(
    val source: String,
    val kind: String,
    val episode: LiveEpisode,
    val provenance: String,
)

private fun strictCorpus(): List<StrictCorpusEpisode> = listOf(
    corpus("wfwf", "comic:10007", "28", "current strict UX evidence and live document audit"),
    corpus("wfwf", "comic:10017", "74", "legacy live regression and live document audit"),
    corpus("wfwf", "comic:10001", "1", "legacy live regression and live document audit"),
    corpus("wfwf", "webtoon:859", "320", "current origin catalog and live document audit"),
    corpus("wfwf", "webtoon:3941", "299", "current origin catalog and live document audit"),
    corpus("ntk", "/webtoon/57451201", "/webtoon/57451201/jjaptoon-1341148", "current NTK smoke"),
    corpus("ntk", "/webtoon/16972", "/webtoon/16972/1516431", "durable heavy replay corpus"),
    corpus("ntk", "/webtoon/851869", "/webtoon/851869/nv-851869-7", "durable slug replay corpus"),
    corpus("ntk", "/manhwa/2778", "/manhwa/2778/10972", "durable fixed heavy regression"),
    corpus("ntk", "/manhwa/9298", "/manhwa/9298/84734", "durable image format replay corpus"),
)

private fun corpus(source: String, series: String, episode: String, provenance: String) =
    StrictCorpusEpisode(
        source = source,
        kind = if (series.startsWith("comic:") || series.startsWith("/manhwa/")) "manhwa" else "webtoon",
        episode = LiveEpisode(source, series, episode),
        provenance = provenance,
    )
