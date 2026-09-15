package ml.melun.mangaview.source.goodtoon

import java.io.IOException
import java.net.URI
import kotlinx.coroutines.test.runTest
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.source.CatalogOrder
import ml.melun.mangaview.source.CatalogQuery
import ml.melun.mangaview.source.SeriesKind
import ml.melun.mangaview.source.SeriesStatus
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.SourceResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoodtoonContentSourceTest {
    private val ongoing = GoodtoonFixtures.text("catalog-ongoing.html")
    private val end = GoodtoonFixtures.text("catalog-end.html")
    private val series = GoodtoonFixtures.text("series.html")
    private val chapters = GoodtoonFixtures.text("chapters.html")
    private val search = GoodtoonFixtures.text("search.html")
    private val viewer = GoodtoonFixtures.text("viewer.html")
    private val seriesId = goodtoonSeriesId("gt-21840")
    private val episodeId = goodtoonEpisodeId("gt-21840", "51")
    private val firstImage = "https://img.goodtoon9001.top/gt-21840/ch-1789310077807/001.jpg"

    private fun source(handler: suspend (SourceRequest) -> SourceResponse): GoodtoonContentSource =
        GoodtoonContentSource(
            GoodtoonConfig("https://www.goodtoon004.com", "test-agent"),
            RecordingTransport(handler = handler),
        )

    private fun defaultSource(): GoodtoonContentSource = source { request ->
        if (URI(request.url).host != "www.goodtoon004.com") return@source binaryResponse(request.url)
        when (URI(request.url).path) {
            "/" -> htmlResponse(request.url, search)
            "/ongoing/" -> htmlResponse(request.url, ongoing)
            "/end/" -> htmlResponse(request.url, end)
            "/manga/gt-21840/" -> htmlResponse(request.url, series)
            "/manga/gt-21840/ajax/chapters/" -> htmlResponse(request.url, chapters)
            "/manga/gt-21840/51/" -> htmlResponse(request.url, viewer)
            else -> throw IOException("unexpected ${request.url}")
        }
    }

    @Test
    fun `catalog returns every card in provider order with route status`() = runTest {
        val page = defaultSource().catalog(CatalogQuery(SeriesKind.WEBTOON, CatalogOrder.LATEST))
        assertEquals(63, page.items.size)
        assertEquals("2", page.nextCursor)
        assertTrue(page.items.all { it.status == SeriesStatus.ONGOING })
        assertEquals("gt-22476", page.items.first().id.remoteKey)
    }

    @Test
    fun `completed filter uses the end route and marks cards completed`() = runTest {
        val query = CatalogQuery(SeriesKind.WEBTOON, CatalogOrder.POPULAR, statusFilter = SeriesStatus.COMPLETED)
        val page = defaultSource().catalog(query)
        assertEquals(63, page.items.size)
        assertTrue(page.items.all { it.status == SeriesStatus.COMPLETED })
    }

    @Test
    fun `search parses cards without pagination`() = runTest {
        val page = defaultSource().search("소녀")
        assertEquals(60, page.items.size)
        assertNull(page.nextCursor)
        assertTrue(page.items.all { it.status == null })
    }

    @Test
    fun `genres expose the live chip list`() = runTest {
        val genres = defaultSource().genres(SeriesKind.WEBTOON)
        assertEquals(34, genres.size)
        assertEquals("genre:school", genres.first().key)
        assertTrue(genres.any { it.key == "genre:fantasy" && it.label == "판타지" })
    }

    @Test
    fun `episodes come from the ajax chapter fragment and ignore cursors`() = runTest {
        val source = defaultSource()
        val page = source.episodes(seriesId)
        assertEquals(51, page.items.size)
        assertEquals("51", page.items.first().id.remoteKey)
        assertEquals("1", page.items.last().id.remoteKey)
        assertEquals(1.0, page.items.last().sequenceNumber!!, 0.0)
        assertTrue(source.episodes(seriesId, cursor = "2").items.isEmpty())
    }

    @Test
    fun `episodes walk every chapter page in provider order until a page adds nothing new`() = runTest {
        val requested = mutableListOf<String?>()
        val source = source { request ->
            val uri = URI(request.url)
            if (uri.path != "/manga/gt-21840/ajax/chapters/") throw IOException("unexpected ${request.url}")
            requested += uri.query
            val body = if (uri.query == "t=1") {
                GoodtoonFixtures.text("chapters-page1.html")
            } else {
                GoodtoonFixtures.text("chapters-page2.html")
            }
            htmlResponse(request.url, body)
        }
        val page = source.episodes(seriesId)
        assertEquals(listOf("51", "50", "49", "48", "47", "46", "1"), page.items.map { it.id.remoteKey })
        assertEquals("1", page.items.last().id.remoteKey)
        assertEquals(listOf("t=1", "t=2", "t=3"), requested)
    }

    @Test
    fun `a provider that serves the whole list for any page terminates the walk`() = runTest {
        var requests = 0
        val source = source { request ->
            val uri = URI(request.url)
            if (uri.path != "/manga/gt-21840/ajax/chapters/") throw IOException("unexpected ${request.url}")
            requests += 1
            htmlResponse(request.url, chapters)
        }
        val page = source.episodes(seriesId)
        assertEquals(51, page.items.size)
        assertEquals("1", page.items.last().id.remoteKey)
        assertEquals(2, requests)
    }

    @Test
    fun `adjacent maps previous to the earlier chapter and next to the later chapter`() = runTest {
        val source = defaultSource()
        val middle = source.adjacent(goodtoonEpisodeId("gt-21840", "10"))
        assertEquals("9", middle.previous?.remoteKey)
        assertEquals("11", middle.next?.remoteKey)

        val oldest = source.adjacent(goodtoonEpisodeId("gt-21840", "1"))
        assertNull(oldest.previous)
        assertEquals("2", oldest.next?.remoteKey)

        val newest = source.adjacent(goodtoonEpisodeId("gt-21840", "51"))
        assertEquals("50", newest.previous?.remoteKey)
        assertNull(newest.next)
    }

    @Test
    fun `manifest maps every provider image to a contiguous page`() = runTest {
        val manifest = defaultSource().manifest(episodeId)
        assertEquals(43, manifest.pages.size)
        assertEquals("51화", manifest.title)
        assertEquals("50", manifest.previousEpisodeId?.remoteKey)
        assertNull(manifest.nextEpisodeId)
    }

    @Test
    fun `open page returns the provider image`() = runTest {
        val opened = defaultSource().openPage(PageId.at(episodeId, 0), null)
        try {
            assertEquals("image/jpeg", opened.contentType)
            assertEquals(8L, opened.contentLength)
        } finally {
            opened.close()
        }
    }

    @Test
    fun `expired page refreshes the manifest and retries the new address`() = runTest {
        val refreshedViewer = viewer.replace("ch-1789310077807", "ch-999")
        val imageUrls = mutableListOf<String>()
        var viewerFetches = 0
        val source = source { request ->
            val uri = URI(request.url)
            when {
                uri.host == "img.goodtoon9001.top" -> {
                    imageUrls += request.url
                    if (uri.path.contains("ch-1789310077807")) binaryResponse(request.url, 403)
                    else binaryResponse(request.url)
                }
                uri.path == "/manga/gt-21840/51/" -> {
                    viewerFetches += 1
                    htmlResponse(request.url, if (viewerFetches == 1) viewer else refreshedViewer)
                }
                uri.path.contains("/ajax/chapters/") -> htmlResponse(request.url, chapters)
                else -> throw IOException("unexpected ${request.url}")
            }
        }
        val opened = source.openPage(PageId.at(episodeId, 0), null)
        try {
            assertEquals("image/jpeg", opened.contentType)
        } finally {
            opened.close()
        }
        assertEquals(2, viewerFetches)
        assertEquals(1, imageUrls.count { it.contains("ch-1789310077807") })
        assertEquals(1, imageUrls.count { it.contains("ch-999") })
    }

    @Test
    fun `series details parse the summary block`() = runTest {
        val details = defaultSource().seriesDetails(seriesId)
        assertNotNull(details)
        assertEquals(SeriesStatus.ONGOING, details?.status)
        assertEquals("이잉간", details?.authors)
        assertTrue(details?.description.orEmpty().contains("마왕은 용사와의 결전"))
    }

    @Test
    fun `series url and artwork resolve against the provider origin`() = runTest {
        val source = defaultSource()
        assertEquals("https://www.goodtoon004.com/manga/gt-21840/", source.seriesUrl(seriesId))
        val card = source.catalog(CatalogQuery(SeriesKind.WEBTOON, CatalogOrder.LATEST)).items.first()
        val artwork = source.openArtwork(card)
        assertNotNull(artwork)
        artwork?.close()
        assertTrue(card.thumbnailKey.orEmpty().contains("img.goodtoon9001.top"))
        assertFalse(card.title.isBlank())
    }

    @Test
    fun `foreign series ids are rejected`() {
        val source = defaultSource()
        val foreign = ml.melun.mangaview.core.SeriesId(ml.melun.mangaview.core.SourceId("wfwf"), "gt-1")
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { source.episodes(foreign) }
        }
    }
}
