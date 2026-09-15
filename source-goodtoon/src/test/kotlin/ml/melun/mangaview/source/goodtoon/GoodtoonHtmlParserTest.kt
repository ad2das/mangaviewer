package ml.melun.mangaview.source.goodtoon

import java.net.URI
import java.text.SimpleDateFormat
import java.util.TimeZone
import ml.melun.mangaview.source.SeriesStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoodtoonHtmlParserTest {
    private val parser = GoodtoonHtmlParser()

    @Test
    fun `catalog cards parse slug title genre thumbnail and route status`() {
        val items = parser.series(
            GoodtoonFixtures.document("catalog-ongoing.html"),
            { goodtoonSeriesId(it.slug) },
            SeriesStatus.ONGOING,
        )
        assertEquals(63, items.size)
        val first = items.first()
        assertEquals("gt-22476", first.id.remoteKey)
        assertEquals("시녀의 유혹", first.title)
        assertEquals("판타지/로맨스", first.subtitle)
        assertEquals("https://img.goodtoon9001.top/gt-22476/cover.jpg", first.thumbnailKey)
        assertEquals(SeriesStatus.ONGOING, first.status)
        assertEquals("goodtoon", first.id.sourceId.value)
    }

    @Test
    fun `completed route marks every card completed`() {
        val items = parser.series(
            GoodtoonFixtures.document("catalog-end.html"),
            { goodtoonSeriesId(it.slug) },
            SeriesStatus.COMPLETED,
        )
        assertEquals(63, items.size)
        assertTrue(items.all { it.status == SeriesStatus.COMPLETED })
    }

    @Test
    fun `search cards carry no status`() {
        val items = parser.series(GoodtoonFixtures.document("search.html"), { goodtoonSeriesId(it.slug) })
        assertEquals(60, items.size)
        assertTrue(items.all { it.status == null })
    }

    @Test
    fun `chapter fragment parses newest first with titles dates and sequence numbers`() {
        val key = GoodtoonSeriesKey("gt-21840")
        val seriesId = goodtoonSeriesId(key.slug)
        val episodes = parser.chapters(GoodtoonFixtures.document("chapters.html"), seriesId, key)
        assertEquals(51, episodes.size)

        val newest = episodes.first()
        assertEquals("51", newest.id.remoteKey)
        assertEquals("51화", newest.title)
        assertEquals(51.0, newest.sequenceNumber!!, 0.0)
        assertNotNull(newest.publishedAtEpochMillis)
        val formatted = SimpleDateFormat("yy.MM.dd").apply {
            timeZone = TimeZone.getTimeZone("Asia/Seoul")
        }.format(java.util.Date(newest.publishedAtEpochMillis!!))
        assertEquals("26.09.13", formatted)
        assertTrue(episodes.all { it.id.seriesId.sourceId.value == "goodtoon" })

        val nonNumeric = episodes.first { it.id.remoteKey == "chapter-48" }
        assertEquals("마왕의 빛나는 별 48화", nonNumeric.title)
        assertEquals(48.0, nonNumeric.sequenceNumber!!, 0.0)

        val oldest = episodes.last()
        assertEquals("1", oldest.id.remoteKey)
        assertEquals("마왕의 빛나는 별 1화", oldest.title)
        assertEquals(1.0, oldest.sequenceNumber!!, 0.0)
    }

    @Test
    fun `side story titles never reorder the delivered chapter list`() {
        val key = GoodtoonSeriesKey("gt-21840")
        val seriesId = goodtoonSeriesId(key.slug)
        val episodes = parser.chapters(GoodtoonFixtures.document("chapters-special.html"), seriesId, key)

        // Provider order: "외전 1화"/"특별편 1" sit above the real first chapter and stay there.
        assertEquals(listOf("side-1", "special-1", "34", "3", "2", "1"), episodes.map { it.id.remoteKey })
        assertEquals(listOf(1.0, null, 34.0, 3.0, 2.0, 1.0), episodes.map { it.sequenceNumber })
        assertEquals(1.0, episodes.first().sequenceNumber!!, 0.0)
        assertEquals("1", episodes.last().id.remoteKey)
        assertEquals("마왕의 빛나는 별 1화", episodes.last().title)
        assertEquals(1.0, episodes.last().sequenceNumber!!, 0.0)
    }

    @Test
    fun `chapter merge keeps first occurrence order`() {
        val key = GoodtoonSeriesKey("gt-21840")
        val seriesId = goodtoonSeriesId(key.slug)
        val first = parser.chapters(GoodtoonFixtures.document("chapters.html"), seriesId, key).take(3)
        val merged = parser.mergeChapters(listOf(first, first))
        assertEquals(listOf("51", "50", "49"), merged.map { it.id.remoteKey })
    }

    @Test
    fun `series details parse status author and description`() {
        val details = parser.details(GoodtoonFixtures.document("series.html"))
        assertEquals(SeriesStatus.ONGOING, details.status)
        assertEquals("이잉간", details.authors)
        assertTrue(details.description.orEmpty().contains("마왕은 용사와의 결전"))
    }

    @Test
    fun `viewer pages parse in order and reuse the chapter dropdown`() {
        val key = GoodtoonSeriesKey("gt-21840")
        val document = GoodtoonFixtures.document("viewer.html")
        val finalUrl = URI("https://www.goodtoon004.com/manga/gt-21840/51/")
        val images = parser.pageImages(document, finalUrl)
        assertEquals(43, images.size)
        assertEquals(
            "https://img.goodtoon9001.top/gt-21840/ch-1789310077807/001.jpg",
            images.first(),
        )

        val chapters = parser.viewerChapters(document, key)
        assertEquals(51, chapters.size)
        assertEquals("51", chapters.first().slug)
        assertEquals("51화", chapters.first().title)
        val selected = parser.viewerChapter(document, key)
        assertEquals("51", selected?.slug)
    }

    @Test
    fun `viewer pages ignore unrelated image urls`() {
        val document = GoodtoonFixtures.document("viewer.html")
        val images = parser.pageImages(document, URI("https://www.goodtoon004.com/manga/gt-21840/51/"))
        assertTrue(images.all { it.startsWith("https://img.goodtoon9001.top/") })
        assertNull(images.firstOrNull { it.contains("logo") })
    }
}
