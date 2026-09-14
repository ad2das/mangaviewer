package ml.melun.mangaview.source.goodtoon

import java.net.URI
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.engine.api.SourceDocument
import ml.melun.mangaview.engine.api.WorkPriority
import ml.melun.mangaview.source.PageValidation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GoodtoonAccessPlannerTest {
    private val planner = GoodtoonAccessPlanner("test-agent")
    private val finalUrl = URI("https://www.goodtoon004.com/manga/gt-21840/51/")
    private val episodeId = goodtoonEpisodeId("gt-21840", "51")
    private val document = SourceDocument(finalUrl, GoodtoonFixtures.text("viewer.html").toByteArray())
    private val firstImage = "https://img.goodtoon9001.top/gt-21840/ch-1789310077807/001.jpg"

    @Test
    fun `document request targets the canonical chapter path`() {
        val request = planner.documentRequest(
            episodeId,
            URI("https://www.goodtoon004.com"),
            WorkPriority.VISIBLE,
        )
        assertEquals(finalUrl.toString(), request.url)
        assertEquals("test-agent", request.headers["User-Agent"])
    }

    @Test
    fun `plan builds contiguous pages and dropdown navigation`() {
        val plan = planner.parseEpisode(episodeId, document, authEpoch = 7)
        assertEquals(43, plan.manifest.pages.size)
        assertEquals("51화", plan.manifest.title)
        assertEquals(EpisodeId(goodtoonSeriesId("gt-21840"), "50"), plan.manifest.previousEpisodeId)
        assertNull(plan.manifest.nextEpisodeId)
        assertTrue(plan.navigationKnown)
        assertEquals(43, plan.pages.size)
        assertEquals("img:0", plan.pages.first().sourceRecord)
        assertEquals(firstImage, plan.pages.first().candidates.first().toString())
        assertEquals(7L, plan.authEpoch)
    }

    @Test
    fun `older episode navigation crosses non numeric slugs`() {
        val plan = planner.parseEpisode(goodtoonEpisodeId("gt-21840", "49"), document, authEpoch = 0)
        assertEquals("chapter-48", plan.manifest.previousEpisodeId?.remoteKey)
        assertEquals("50", plan.manifest.nextEpisodeId?.remoteKey)
    }

    @Test
    fun `page request carries referer and validation headers`() {
        val plan = planner.parseEpisode(episodeId, document, authEpoch = 0)
        val request = planner.pageRequest(
            plan,
            PageId.at(episodeId, 0),
            0,
            WorkPriority.INTERACTIVE,
            PageValidation("etag-1", "last-modified-1"),
        )
        assertEquals(firstImage, request.url)
        assertEquals(finalUrl.toString(), request.headers["Referer"])
        assertEquals("etag-1", request.headers["If-None-Match"])
        assertEquals("last-modified-1", request.headers["If-Modified-Since"])
    }

    @Test
    fun `a chapter document without images is rejected`() {
        val empty = SourceDocument(finalUrl, "<html><body></body></html>".toByteArray())
        assertThrows(IllegalArgumentException::class.java) {
            planner.parseEpisode(episodeId, empty, authEpoch = 0)
        }
    }
}
