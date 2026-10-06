package ml.melun.mangaview.engine.session

import java.math.BigInteger
import java.util.Random
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.PageSpec
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.engine.api.EngineViewport
import ml.melun.mangaview.engine.api.SpreadPages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Randomized equivalence proof for the capped backward distance walk and the identity-keyed page
 * index: every case runs the production move() and the frozen pre-fix [ReferenceGeometryMove] from
 * the same starting anchor over the same geometry and demands an identical MoveResult -- cursor,
 * consumed, remaining, blocker, boundary -- and an identical resulting anchor. The case mix covers
 * multi-episode windows, varied page geometry, missing dimensions, pruned episodes, split reading,
 * anchors on a target first page, and deltas landing exactly on page boundaries and on the start
 * limit.
 */
class DocumentGeometryWalkEquivalenceTest {
    @Test
    fun productionMoveMatchesTheFrozenReferenceOverTwoThousandCases() {
        val rng = Random(0x9E0C_5EED_2026L)
        var cases = 0
        repeat(FIXTURES) { fixtureIndex ->
            val fixture = fixture(rng)
            val geometry = fixture.geometry
            deltas(fixture, rng).forEach { delta ->
                val before = geometry.anchor
                val actual = geometry.move(delta)
                val actualAnchor = geometry.anchor
                geometry.anchor = before
                val expected = fixture.reference.move(delta)
                assertEquals("fixture $fixtureIndex delta=$delta result", expected, actual)
                assertEquals("fixture $fixtureIndex delta=$delta anchor", geometry.anchor, actualAnchor)
                geometry.anchor = before
                cases++
            }
        }
        assertTrue("checked $cases cases", cases >= 2_000)
    }

    @Test
    fun duplicatedPageIdUsesTheFirstOccurrenceScanLikeTheReference() {
        val series = SeriesId(SourceId("test"), "walk-equivalence")
        val episode = EpisodeId(series, "dup")
        val nextEpisode = EpisodeId(series, "dup-next")
        val pages = mutableListOf(
            PageSpec(PageId.at(episode, 0), 0, PageDimensions(720, 4000)),
            PageSpec(PageId.at(episode, 1), 1, PageDimensions(720, 4000)),
        )
        val manifest = EpisodeManifest(episode, "duplicate", pages, nextEpisodeId = nextEpisode)
        val geometry = DocumentGeometry(episode, EngineViewport(1080, 1920))
        geometry.addManifest(manifest, known = true)
        geometry.addManifest(
            EpisodeManifest(
                nextEpisode, "duplicate-next",
                listOf(PageSpec(PageId.at(nextEpisode, 0), 0, PageDimensions(720, 4000))),
            ),
            known = true,
        )
        // The manifest API forbids repeated ids, so repeat the first PageId in its backing list
        // after construction: the cache must answer exactly like indexOfFirst, first index wins.
        pages[1] = PageSpec(pages[0].id, 1)
        val duplicated = pages[0].id
        val reference = ReferenceGeometryMove(geometry)

        assertEquals(0, geometry.pageIndices.indexOf(manifest, duplicated))
        assertEquals(PageStep.End, geometry.previousPage(duplicated))
        assertEquals(PageStep.Known(duplicated), geometry.nextPage(duplicated))
        assertEquals(reference.nextPage(duplicated), geometry.nextPage(duplicated))
        assertEquals(reference.previousPage(duplicated), geometry.previousPage(duplicated))
    }

    private class Fixture(val geometry: DocumentGeometry, val reference: ReferenceGeometryMove)

    private fun fixture(rng: Random): Fixture {
        val series = SeriesId(SourceId("test"), "walk-equivalence")
        val episodeCount = 1 + rng.nextInt(5)
        val episodes = (0 until episodeCount).map { EpisodeId(series, "e$it") }
        val target = episodes[rng.nextInt(episodeCount)]
        val geometry = DocumentGeometry(target, EngineViewport(1080, 1920))
        geometry.applySplitMode(rng.nextBoolean())
        val pageCount = 1 + rng.nextInt(12)
        episodes.forEachIndexed { index, episode ->
            val pages = List(pageCount) { ordinal ->
                PageSpec(PageId.at(episode, ordinal), ordinal, randomDimensions(rng))
            }
            geometry.addManifest(
                EpisodeManifest(
                    episode, "episode-$index", pages,
                    previousEpisodeId = episodes.getOrNull(index - 1),
                    nextEpisodeId = episodes.getOrNull(index + 1),
                ),
                known = rng.nextBoolean(),
            )
        }
        val anchorEpisode = if (rng.nextInt(3) == 0) target else episodes[rng.nextInt(episodeCount)]
        val anchorOrdinal = if (rng.nextInt(4) == 0) 0 else rng.nextInt(pageCount)
        if (rng.nextInt(10) != 0) {
            val pageId = PageId.at(anchorEpisode, anchorOrdinal)
            geometry.anchor = AnchorState(
                pageId,
                randomSource(rng, geometry, pageId),
                randomViewportOffset(rng, geometry),
            )
        }
        if (episodeCount > 1 && rng.nextInt(4) == 0) {
            val anchorEpisodeId = geometry.anchor?.pageId?.episodeId
            val victims = episodes.filter { it != anchorEpisodeId }
            val victim = victims[rng.nextInt(victims.size)]
            geometry.manifests.remove(victim)
            geometry.navigationKnown.remove(victim)
            geometry.actualDimensions.keys.removeAll { it.episodeId == victim }
        }
        return Fixture(geometry, ReferenceGeometryMove(geometry))
    }

    private fun deltas(fixture: Fixture, rng: Random): List<BigRational> {
        val geometry = fixture.geometry
        val pageScreen = anchorPageScreen(geometry)
        val values = mutableListOf(
            BigRational.ZERO,
            BigRational.ONE,
            -BigRational.ONE,
            pageScreen,
            -pageScreen,
            pageScreen + BigRational.ONE,
            -(pageScreen + BigRational.ONE),
        )
        if (pageScreen > BigRational.ONE) {
            values += pageScreen - BigRational.ONE
            values += -(pageScreen - BigRational.ONE)
        }
        val twoThirds = pageScreen * BigRational.of(2L) / BigRational.of(3L)
        values += twoThirds
        values += -twoThirds
        values += BigRational.of(20_000_000L)
        values += BigRational.of(-20_000_000L)
        values += BigRational.of(1L + rng.nextInt(50_000_000))
        values += BigRational.of(-1L - rng.nextInt(50_000_000))
        val anchor = geometry.anchor
        if (anchor != null) {
            val limit = fixture.reference.limitDistance(Cursor(anchor.pageId, anchor.sourceQ32))
            if (limit != null && limit.signum() != 0) {
                values += -limit
                values += -(limit + BigRational.ONE)
                values += -(limit + pageScreen)
                if (limit > BigRational.ONE) values += -(limit - BigRational.ONE)
            }
        }
        return values
    }

    private fun anchorPageScreen(geometry: DocumentGeometry): BigRational {
        val anchor = geometry.anchor ?: return BigRational.of(6_144_000L)
        val dimensions = geometry.page(anchor.pageId)?.dimensions ?: return BigRational.of(6_144_000L)
        val base = pageScreenLength(dimensions.widthPx, dimensions.heightPx, geometry.viewport.widthPx)
        return if (geometry.splitMode && SpreadPages.isSpread(dimensions)) {
            base * BigRational.of(2L)
        } else {
            base
        }
    }

    private fun randomDimensions(rng: Random): PageDimensions? =
        if (rng.nextInt(5) == 0) null else PageDimensions(1 + rng.nextInt(3_000), 1 + rng.nextInt(8_000))

    private fun randomSource(rng: Random, geometry: DocumentGeometry, pageId: PageId): BigRational {
        val dimensions = geometry.page(pageId)?.dimensions
            ?: return BigRational.of(1L + rng.nextInt(10_000_000))
        val splitFactor = if (geometry.splitMode && SpreadPages.isSpread(dimensions)) {
            BigInteger.valueOf(2L)
        } else {
            BigInteger.ONE
        }
        val full = BigRational.of(pageSourceExtent(dimensions.heightPx).multiply(splitFactor))
        return when (rng.nextInt(4)) {
            0 -> BigRational.ZERO
            1 -> full
            2 -> full / BigRational.of(2L)
            else -> full * BigRational.of(BigInteger.valueOf(1L + rng.nextInt(997)), BigInteger.valueOf(1_000L))
        }
    }

    private fun randomViewportOffset(rng: Random, geometry: DocumentGeometry): Long {
        val heightUnits = geometry.viewport.heightPx.toLong() * SCREEN_UNITS_PER_PIXEL_LONG
        return when (rng.nextInt(3)) {
            0 -> 0L
            1 -> heightUnits
            else -> heightUnits / 2L
        }
    }

    private companion object {
        const val FIXTURES = 240
    }
}
