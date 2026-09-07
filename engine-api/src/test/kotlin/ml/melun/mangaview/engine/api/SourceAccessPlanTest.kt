package ml.melun.mangaview.engine.api

import java.net.URI
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.PageSpec
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class SourceAccessPlanTest {
    private val episode = EpisodeId(SeriesId(SourceId("source"), "series"), "episode")
    private val first = PageId.at(episode, 0)
    private val second = PageId.at(episode, 1)
    private val address = URI("https://example.test/image?token=private-token")

    @Test
    fun alternativeAddressesStayOnTheirRecordAndEqualAddressDoesNotCollapsePages() {
        val alternative = URI("https://replica.test/image")
        val firstPage = PageAccessPlan(first, "dom:0", listOf(address, alternative, address))
        val plan = plan(listOf(firstPage, PageAccessPlan(second, "dom:1", listOf(address))))
        assertEquals(listOf(address, alternative), plan.page(first).candidates)
        assertEquals(listOf(address), plan.page(second).candidates)
        assertEquals(2, plan.manifest.pages.size)
        assertFalse(plan.toString().contains("private-token"))
        assertFalse(firstPage.toString().contains("private-token"))
    }

    @Test
    fun reorderedMissingAndForeignRecordsCannotBePublished() {
        val a = PageAccessPlan(first, "dom:0", listOf(address))
        val b = PageAccessPlan(second, "dom:1", listOf(address))
        assertThrows(IllegalArgumentException::class.java) { plan(listOf(b, a)) }
        assertThrows(IllegalArgumentException::class.java) { plan(listOf(a)) }
        val foreign = PageId.at(episode.copy(remoteKey = "other"), 1)
        assertThrows(IllegalArgumentException::class.java) {
            plan(listOf(a, PageAccessPlan(foreign, "dom:1", listOf(address))))
        }
    }

    @Test
    fun duplicateSourceRecordAndUnownedBrowserAuthorizationAreRejected() {
        val pages = listOf(PageAccessPlan(first, "same", listOf(address)),
            PageAccessPlan(second, "same", listOf(address)))
        assertThrows(IllegalArgumentException::class.java) { plan(pages) }
        assertThrows(IllegalArgumentException::class.java) {
            plan(records(), listOf(AccessPrerequisite.BrowserAuthorization(episode.copy(remoteKey = "other"))))
        }
    }

    @Test
    fun callerMutationCannotChangePublishedManifestOrCandidateOwnership() {
        val candidates = mutableListOf(address)
        val pages = mutableListOf(PageSpec(first, 0), PageSpec(second, 1))
        val records = mutableListOf(PageAccessPlan(first, "dom:0", candidates),
            PageAccessPlan(second, "dom:1", candidates))
        val plan = EpisodeAccessPlan(EpisodeManifest(episode, "chapter", pages), "revision", "a".repeat(64),
            URI("https://example.test/chapter"), 0, records)
        candidates.clear()
        pages.clear()
        records.clear()
        assertEquals(2, plan.manifest.pages.size)
        assertEquals(listOf(address), plan.page(first).candidates)
        assertThrows(UnsupportedOperationException::class.java) {
            (plan.pages as MutableList<PageAccessPlan>).clear()
        }
    }

    private fun records() = listOf(PageAccessPlan(first, "dom:0", listOf(address)),
        PageAccessPlan(second, "dom:1", listOf(address)))

    private fun plan(pages: List<PageAccessPlan>, prerequisites: List<AccessPrerequisite> = emptyList()) =
        EpisodeAccessPlan(EpisodeManifest(episode, "chapter", listOf(PageSpec(first, 0), PageSpec(second, 1))),
            "revision", "a".repeat(64), URI("https://example.test/chapter"), 0, pages, prerequisites)
}
