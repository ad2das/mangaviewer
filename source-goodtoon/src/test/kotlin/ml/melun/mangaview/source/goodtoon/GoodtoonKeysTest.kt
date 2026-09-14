package ml.melun.mangaview.source.goodtoon

import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GoodtoonKeysTest {
    @Test
    fun `ascii slug round trips through series and chapter paths`() {
        val key = GoodtoonSeriesKey("gt-21840")
        assertEquals("gt-21840", key.encode())
        assertEquals("/manga/gt-21840/", key.path())
        assertEquals("/manga/gt-21840/51/", key.chapterPath("51"))
        assertEquals("/manga/gt-21840/ajax/chapters/?t=1", key.chaptersPath())
    }

    @Test
    fun `korean slug is percent encoded with utf-8 and never a plus`() {
        val key = GoodtoonSeriesKey("사랑")
        assertEquals("/manga/%EC%82%AC%EB%9E%91/", key.path())
    }

    @Test
    fun `decode accepts only its own source`() {
        assertEquals("gt-1", GoodtoonSeriesKey.decode(SeriesId(goodtoonSourceId(), "gt-1")).slug)
        assertThrows(IllegalArgumentException::class.java) {
            GoodtoonSeriesKey.decode(SeriesId(SourceId("ntk"), "gt-1"))
        }
    }

    @Test
    fun `episode keys allow non numeric slugs`() {
        val id = goodtoonEpisodeId("gt-21840", "chapter-48")
        assertEquals("chapter-48", GoodtoonEpisodeKey.decode(id).encode())
    }

    @Test
    fun `keys reject multi segment slugs`() {
        assertThrows(IllegalArgumentException::class.java) { GoodtoonSeriesKey("a/b") }
        assertThrows(IllegalArgumentException::class.java) { GoodtoonSeriesKey("..") }
        assertThrows(IllegalArgumentException::class.java) { GoodtoonEpisodeKey("a/b") }
    }
}
