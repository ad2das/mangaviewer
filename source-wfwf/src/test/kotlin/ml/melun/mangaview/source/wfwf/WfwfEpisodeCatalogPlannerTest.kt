package ml.melun.mangaview.source.wfwf

import java.net.URI
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.engine.api.SourceDocument
import org.junit.Assert.*
import org.junit.Test

class WfwfEpisodeCatalogPlannerTest {
    private val planner = WfwfEpisodeCatalogPlanner("agent")
    private val series = SeriesId(SourceId("wfwf"), "comic:7")

    @Test fun catalogRowsAndPaginationAreConfinedToTheRequestedSeries() {
        val page = planner.parse(series, document("""
            <a href="/cv?toon=7&num=8">8화</a>
            <a href="/cv?toon=99&num=99">다른 작품</a>
            <a href="/cl?toon=7&pg=3">3</a>
            <a href="/cl?toon=99&pg=20">20</a>
        """))
        assertEquals(listOf("8"), page.episodes.map { it.id.remoteKey })
        assertEquals(3, page.lastPage)
    }

    @Test fun overlappingCatalogPagesProduceOneConsistentEpisodeOrder() {
        val first = planner.parse(series, document("<a href='/cv?toon=7&num=9'>9화</a><a href='/cv?toon=7&num=8'>8화</a>"))
        val second = planner.parse(series, document("<a href='/cv?toon=7&num=8'>8화</a><a href='/cv?toon=7&num=7'>7화</a>"))
        assertEquals(listOf("9", "8", "7"), planner.merge(listOf(first, second)).map { it.id.remoteKey })
    }

    private fun document(html: String) = SourceDocument(URI("https://wfwf.test/cl?toon=7"), html.toByteArray())
}
