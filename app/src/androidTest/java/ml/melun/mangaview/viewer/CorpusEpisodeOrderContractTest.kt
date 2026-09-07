package ml.melun.mangaview.viewer

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.source.SeriesKind
import ml.melun.mangaview.source.SourceEpisode
import ml.melun.mangaview.source.SourceSeries
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CorpusEpisodeOrderContractTest {
    private val expected = (1..5).map { EpisodeId(SERIES, "episode-$it") }

    @Test
    fun finalManifestIdsCannotReplaceMissingObservedTransitions() {
        val violations = CorpusEpisodeOrderContract.violations(
            observed = expected.take(2),
            expected = expected,
            requiredEpisodes = expected.size,
        )

        assertTrue(violations.any { it.contains("Observed 2/5") })
        assertTrue(violations.any { it.contains("order differs") })
    }

    @Test
    fun aCompleteIndependentlyExpectedTransitionOrderPasses() {
        assertFalse(CorpusEpisodeOrderContract.violations(expected, expected, expected.size).isNotEmpty())
    }

    @Test
    fun sampleIdentityIncludesTheCompleteChain() {
        val first = sample(listOf("episode-1", "episode-2", "episode-3", "episode-4", "episode-5"))
        val second = sample(listOf("episode-1", "episode-2", "episode-3", "episode-4", "replacement"))

        assertNotEquals(first.key, second.key)
    }

    @Test(expected = IllegalArgumentException::class)
    fun importedSampleWithFourEpisodesIsRejectedBeforeRefresh() {
        CorpusSeriesSample.fromJson(JSONObject()
            .put("source", "fixture").put("kind", SeriesKind.COMIC.name)
            .put("seriesKey", "series").put("title", "work")
            .put("episodes", JSONArray().apply {
                repeat(4) { put(JSONObject().put("key", "episode-$it").put("title", "episode-$it")) }
            }))
    }

    @Test(expected = IllegalArgumentException::class)
    fun singleRegressionCannotUseFiveEpisodePadding() {
        SingleEpisodeRegression.fromJson(JSONObject()
            .put("source", "wfwf").put("kind", SeriesKind.COMIC.name)
            .put("seriesKey", "comic:10007").put("episodeKey", "28")
            .put("role", SingleEpisodeRegression.ROLE)
            .put("classification", SingleEpisodeRegression.CLASSIFICATION)
            .put("provenance", JSONArray().put(JSONObject()
                .put("artifact", "fixture").put("classification", "SINGLE_EPISODE_DEVICE_FAILURE")
                .put("reason", "failure")))
            .put("episodes", JSONArray()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun singleRegressionRequiresFailureProvenance() {
        SingleEpisodeRegression.fromJson(JSONObject()
            .put("source", "wfwf").put("kind", SeriesKind.COMIC.name)
            .put("seriesKey", "comic:10007").put("episodeKey", "28")
            .put("role", SingleEpisodeRegression.ROLE)
            .put("classification", SingleEpisodeRegression.CLASSIFICATION)
            .put("provenance", JSONArray()))
    }

    @Test
    fun cleanupContinuesEvidenceActionsAndRetainsPrimaryFailure() {
        val primary = IllegalStateException("viewer failure")
        val cleanupFailure = IllegalStateException("close failure")
        val actions = mutableListOf<String>()

        val result = CorpusCleanupContract.finish(primary, listOf(
            { actions += "close"; throw cleanupFailure },
            { actions += "return" },
            { actions += "memory" },
        ))

        assertSame(primary, result)
        assertTrue(actions == listOf("close", "return", "memory"))
        assertTrue(primary.suppressed.contains(cleanupFailure))
    }

    @Test
    fun cleanupFailureIsReturnedOnlyWhenThereIsNoPrimaryFailure() {
        val cleanupFailure = IllegalStateException("memory failure")
        val result = CorpusCleanupContract.finish(null, listOf { throw cleanupFailure })

        assertSame(cleanupFailure, result)
    }

    private fun sample(keys: List<String>) = CorpusSeriesSample(
        SeriesKind.COMIC,
        SourceSeries(SERIES, "work"),
        keys.map { SourceEpisode(EpisodeId(SERIES, it), it) },
    )

    private companion object {
        val SERIES = SeriesId(SourceId("fixture"), "series")
    }
}
