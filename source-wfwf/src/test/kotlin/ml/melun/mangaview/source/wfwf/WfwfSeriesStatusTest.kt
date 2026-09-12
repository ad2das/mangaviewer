package ml.melun.mangaview.source.wfwf

import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.source.SeriesStatus
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WfwfSeriesStatusTest {
    @Test
    fun completedBadgeMarksCompletedCardsAndPlainCardsStayOngoing() {
        val document = Jsoup.parse(
            """
            <a class="t-card" href="/list?toon=10001">
              <div class="t-img"><img src="/1.jpg"><span class="badge-end">완결</span></div>
              <div class="t-title">끝난 작품</div><div class="t-genre">드라마</div>
            </a>
            <a class="t-card" href="/list?toon=10002">
              <div class="t-img"><img src="/2.jpg"><span class="badge-up">UP</span></div>
              <div class="t-title">연재 작품</div><div class="t-genre">액션</div>
            </a>
            <a href="/list?toon=10003"><h3>배지 없는 작품</h3></a>
            """.trimIndent(),
            "https://wfwf.test/list",
        )
        val items = WfwfHtmlParser().search(document) { key -> SeriesId(SourceId("wfwf"), key.encode()) }

        assertEquals(3, items.size)
        assertEquals(SeriesStatus.COMPLETED, items.first { it.title == "끝난 작품" }.status)
        assertEquals(SeriesStatus.ONGOING, items.first { it.title == "연재 작품" }.status)
        assertNull("a card without the list markup must not claim a status",
            items.first { it.title == "배지 없는 작품" }.status)
    }
}
