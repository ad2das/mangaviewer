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

    @Test fun paginationKeepsProviderOrderWhenAnyRowLacksProviderSequence() {
        val first = SourceDocument(URI("https://ntk.test/webtoon/work"), """
            <a href="/webtoon/work/special"><strong>외유특별외전-1화</strong></a>
            <a href="/webtoon/work/ep-10"><strong>10화</strong></a>
            <script>{"episodes":[{"sourceEpisodeId":"ep-10","epNo":10}]}</script>
        """.toByteArray())
        val second = SourceDocument(URI("https://ntk.test/webtoon/work?epage=2"), """
            <a href="/webtoon/work/ep-2"><strong>2화</strong></a>
            <a href="/webtoon/work/ep-1"><strong>1화</strong></a>
            <script>{"episodes":[{"sourceEpisodeId":"ep-2","epNo":2},
              {"sourceEpisodeId":"ep-1","epNo":1}]}</script>
        """.toByteArray())

        val merged = planner.merge(
            listOf(planner.parseDocument(series, first), planner.parseDocument(series, second)),
        )

        assertEquals(
            listOf("/webtoon/work/special", "/webtoon/work/ep-10", "/webtoon/work/ep-2", "/webtoon/work/ep-1"),
            merged.map { it.id.remoteKey },
        )
        assertEquals("/webtoon/work/ep-1", merged.last().id.remoteKey)
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
