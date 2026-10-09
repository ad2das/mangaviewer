package ml.melun.mangaview.ui.library

import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import org.junit.Assert.assertEquals
import org.junit.Test

class SeriesKindLabelTest {
    @Test fun comicKeysReadAsComicsWhicheverSourceServesThem() {
        assertEquals("만화", seriesKindLabel(SeriesId(SourceId("ntk"), "/manhwa/123")))
        assertEquals("만화", seriesKindLabel(SeriesId(SourceId("wfwf"), "comic:10017")))
    }

    @Test fun everythingElseIsAWebtoon() {
        assertEquals("웹툰", seriesKindLabel(SeriesId(SourceId("ntk"), "/webtoon/123")))
        assertEquals("웹툰", seriesKindLabel(SeriesId(SourceId("wfwf"), "webtoon:5")))
        assertEquals("웹툰", seriesKindLabel(SeriesId(SourceId("newxtoon"), "1876")))
    }
}
