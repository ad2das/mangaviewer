package ml.melun.mangaview.source.wfwf

import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Test

class WfwfEpisodeTitleTest {
    @Test
    fun liveEpisodeRowsKeepTheirTitlesAndExcludeTheFirstEpisodeShortcut() {
        // Structure from the device-captured /cl?toon=10001 document on 2026-09-05.
        val document = Jsoup.parse("""
            <div class="title-btns">
              <a class="tbtn tbtn-first" href="/cv?toon=10001&num=1">첫화 보기</a>
            </div>
            <a class="ep-item" href="/cv?toon=10001&num=2" data-num="2">
              <div class="ep-num">2</div>
              <div class="ep-content"><span class="ep-title">마왕의 딸은 너무 착해!! 2화</span></div>
              <span class="ep-date">2026.09.05</span>
            </a>
            <a class="ep-item" href="/cv?toon=10001&num=1" data-num="1">
              <div class="ep-num">1</div>
              <div class="ep-content"><span class="ep-title">마왕의 딸은 너무 착해!! 1화</span></div>
            </a>
        """.trimIndent())
        val episodes = WfwfHtmlParser().episodes(document, SeriesId(SourceId("wfwf"), "comic:10001"),
            WfwfSeriesKey(WfwfKind.COMIC, 10001))
        assertEquals(listOf("2", "1"), episodes.map { it.id.remoteKey })
        assertEquals(listOf("마왕의 딸은 너무 착해!! 2화", "마왕의 딸은 너무 착해!! 1화"), episodes.map { it.title })
    }
}
