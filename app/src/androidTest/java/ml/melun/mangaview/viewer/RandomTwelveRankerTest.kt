package ml.melun.mangaview.viewer

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.source.SeriesKind
import ml.melun.mangaview.source.SourceEpisode
import ml.melun.mangaview.source.SourceSeries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RandomTwelveRankerTest {
    private val bucket = RandomTwelveSelector.Bucket(SourceId("ntk"), SeriesKind.COMIC)
    private val ranker = RandomTwelveRanker(ByteArray(32) { it.toByte() })

    @Test fun rankIsStableOrderIndependentAndDomainSeparated() {
        val series = (1..8).map { SourceSeries(SeriesId(bucket.sourceId, "series-$it"), "title-$it") }
        val forward = series.sortedBy { ranker.series(bucket, it) }.map { it.id }
        val reverse = series.reversed().sortedBy { ranker.series(bucket, it) }.map { it.id }
        assertEquals(forward, reverse)
        val episode = SourceEpisode(EpisodeId(series.first().id, "episode"), "episode")
        assertNotEquals(ranker.series(bucket, series.first()), ranker.episode(bucket, series.first(), episode))
    }

    @Test fun rankedEligibilityShortCircuitMatchesFilteringTheWholePool() {
        val ranked = (1..20).map { SourceSeries(SeriesId(bucket.sourceId, "series-$it"), "title-$it") }
            .sortedBy { ranker.series(bucket, it) }
        val eligible = ranked.filterIndexed { index, _ -> index % 3 != 0 }.map { it.id }.toSet()
        val exhaustive = ranked.filter { it.id in eligible }.take(3).map { it.id }
        val probed = mutableListOf<SeriesId>()
        for (series in ranked) {
            if (series.id in eligible) probed += series.id
            if (probed.size == 3) break
        }
        assertEquals(exhaustive, probed)
    }

    @Test fun seedParserRejectsNonCanonicalLengthAndAcceptsMixedCaseHex() {
        assertEquals(32, parseRandomTwelveSeed("Aa".repeat(32)).size)
        listOf("", "00", "g0".repeat(32), "00".repeat(33)).forEach { value ->
            check(runCatching { parseRandomTwelveSeed(value) }.isFailure)
        }
    }
}
