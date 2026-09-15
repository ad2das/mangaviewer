package ml.melun.mangaview.app

import java.io.File
import java.net.URI
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.PageSpec
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.data.cache.CachedPage
import ml.melun.mangaview.engine.api.EpisodeAccessPlan
import ml.melun.mangaview.engine.api.PageAccessPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineEnginePlansTest {
    private val series = SeriesId(SourceId("wfwf"), "series-under-test")
    private val episode = EpisodeId(series, "ep-1")

    private fun cached(remoteKey: String, ordinal: Int, digest: String): Pair<PageSpec, CachedPage> {
        val file = File.createTempFile("offline-page-$ordinal", ".jpg")
        file.deleteOnExit()
        file.writeBytes("bytes-$remoteKey".toByteArray())
        val id = PageId(episode, remoteKey)
        val dimensions = PageDimensions(800, 1200 + ordinal)
        val spec = PageSpec(id, ordinal, dimensions, file.length(), digest)
        return spec to CachedPage(id, file, file.length(), digest, "image/jpeg", dimensions)
    }

    private fun manifest(pages: List<Pair<PageSpec, CachedPage>>, next: EpisodeId? = null) = EpisodeManifest(
        id = episode,
        title = "episode one",
        pages = pages.map { it.first },
        previousEpisodeId = null,
        nextEpisodeId = next,
    )

    private fun fixture(next: EpisodeId? = null): Pair<EpisodeManifest, List<Pair<PageSpec, CachedPage>>> {
        val pages = listOf(cached("p1", 0, "a".repeat(64)), cached("p2", 1, "b".repeat(64)))
        return manifest(pages, next) to pages
    }

    @Test
    fun planIsLocalOnlyAndOwned() {
        val (episodeManifest, pages) = fixture()
        val plan = OfflineEnginePlans.plan(episodeManifest, pages)

        assertTrue(plan.localOnly)
        assertTrue(OfflineEnginePlans.owns(plan))
        assertTrue(plan.contentRevision.startsWith("offline:"))
        assertEquals(0L, plan.authEpoch)
        assertEquals(64, plan.documentSha256.length)
        assertTrue(plan.documentSha256.all { it in "0123456789abcdef" })
        assertEquals(plan.documentSha256, plan.contentRevision.removePrefix("offline:"))
    }

    @Test
    fun accessPagesMirrorManifestOrderWithLocalRecords() {
        val (episodeManifest, pages) = fixture()
        val plan = OfflineEnginePlans.plan(episodeManifest, pages)

        assertEquals(episodeManifest.pages.map { it.id }, plan.pages.map { it.pageId })
        assertEquals(plan.pages.size, plan.pages.map { it.sourceRecord }.distinct().size)
        for (access in plan.pages) {
            assertEquals(1, access.candidates.size)
            val candidate = access.candidates.single()
            assertEquals("https", candidate.scheme)
            assertEquals("offline.invalid", candidate.host)
        }
        assertEquals("offline.invalid", plan.finalDocumentUrl.host)
        assertTrue(plan.prerequisites.isEmpty())
    }

    @Test
    fun revisionIsDeterministicAndContentBound() {
        val (episodeManifest, pages) = fixture()
        val first = OfflineEnginePlans.plan(episodeManifest, pages)
        val second = OfflineEnginePlans.plan(episodeManifest, pages)
        assertEquals(first.contentRevision, second.contentRevision)

        val changed = listOf(pages[0], pages[1].first.copy(fingerprint = "c".repeat(64)) to
            pages[1].second.copy(sha256 = "c".repeat(64)))
        val distinct = OfflineEnginePlans.plan(manifest(changed), changed)
        assertNotEquals(first.contentRevision, distinct.contentRevision)
    }

    @Test
    fun foreignPlansAreNeverOwned() {
        val (episodeManifest, _) = fixture()
        val remote = EpisodeAccessPlan(
            episodeManifest,
            "revision",
            "d".repeat(64),
            URI("https://example.com/ep"),
            0L,
            episodeManifest.pages.mapIndexed { index, spec ->
                PageAccessPlan(spec.id, "record-$index", listOf(URI("https://example.com/p$index")))
            },
        )
        assertFalse(OfflineEnginePlans.owns(remote))
    }
}
