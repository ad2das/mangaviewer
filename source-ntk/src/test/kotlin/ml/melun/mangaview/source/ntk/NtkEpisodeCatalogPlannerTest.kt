package ml.melun.mangaview.source.ntk

import java.net.URI
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.engine.api.SourceDocument
import org.junit.Assert.*
import org.junit.Test

class NtkEpisodeCatalogPlannerTest {
    private val planner = NtkEpisodeCatalogPlanner("agent")
    private val series = SeriesId(SourceId("ntk"), "/webtoon/work")

    @Test fun missingAndIncompleteApiTotalsCannotClaimACompleteCatalog() {
        fun api(total: String) = SourceDocument(URI("https://ntk.test/api/webtoon/work/episodes"),
            """{"episodes":[{"episodeId":"ep","title":"1화"}]$total}""".toByteArray())
        assertNull(planner.parseApi(series, api("")))
        assertNull(planner.parseApi(series, api(",\"total\":2")))
        assertEquals(1, planner.merge(listOf(requireNotNull(planner.parseApi(series, api(",\"total\":1"))))).size)
    }

    @Test fun paginationIsBoundToThisSeriesWithoutSilentlyClampingThePageCount() {
        val source = SourceDocument(URI("https://ntk.test/webtoon/work"), """
            <a href="/webtoon/work/ep">1화</a>
            <a href="/webtoon/work?epage=1050">last</a>
            <a href="/webtoon/foreign?epage=9999">foreign</a>
            <a href="https://foreign.test/webtoon/work?epage=9999">other origin</a>
        """.toByteArray())
        val page = planner.parseDocument(series, source)
        assertEquals(1050, page.lastPage)
        assertEquals(listOf("/webtoon/work/ep"), planner.merge(listOf(page)).map { it.id.remoteKey })
    }
}
