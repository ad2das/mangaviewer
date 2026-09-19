package ml.melun.mangaview.source.newxtoon

import java.net.URI
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.engine.api.SourceDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewxtoonAccessPlannerTest {
    private val planner = NewxtoonAccessPlanner("test-agent")
    private val series = SeriesId(planner.sourceId, "17974")
    private val episode = EpisodeId(series, "1062717")

    private fun fixture(name: String): ByteArray = checkNotNull(
        javaClass.classLoader?.getResourceAsStream("newxtoon/$name"),
    ) { "Missing fixture $name" }.use { it.readBytes() }

    private fun document(body: ByteArray) =
        SourceDocument(URI("https://newxtoon1.com/comics/17974/chapters/1062717"), body)

    @Test fun attachesTheNeighborChaptersFromTheReaderPage() {
        val plan = planner.parseEpisode(episode, document(fixture("chapter.html")), 0, null)
        assertTrue("the reader controls make the adjacency known", plan.navigationKnown)
        assertEquals("1062718", plan.manifest.previousEpisodeId?.remoteKey)
        assertEquals("1062721", plan.manifest.nextEpisodeId?.remoteKey)
        assertEquals(series, plan.manifest.nextEpisodeId?.seriesId)
    }

    @Test fun aReaderPageWithoutControlsStaysUnknownForTheCatalog() {
        val html = """
            <html><body>
              <div class="reader-page-frame">
                <img src="https://cdn.test/1.webp" width="800" height="1200">
              </div>
            </body></html>
        """.trimIndent()
        val plan = planner.parseEpisode(episode, document(html.toByteArray()), 0, null)
        assertFalse("without reader controls the catalog stays the authority", plan.navigationKnown)
        assertNull(plan.manifest.previousEpisodeId)
        assertNull(plan.manifest.nextEpisodeId)
    }

    @Test fun aReaderLinkToAnotherSeriesIsIgnored() {
        val html = """
            <html><body>
              <a href="https://newxtoon1.com/comics/99999/chapters/5" class="reader-side-button reader-side-previous"></a>
              <div class="reader-page-frame">
                <img src="https://cdn.test/1.webp" width="800" height="1200">
              </div>
            </body></html>
        """.trimIndent()
        val plan = planner.parseEpisode(episode, document(html.toByteArray()), 0, null)
        assertFalse(plan.navigationKnown)
        assertNull(plan.manifest.previousEpisodeId)
    }
}
