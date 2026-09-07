package ml.melun.mangaview.source.wfwf

import java.net.URI
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.source.CatalogOrder
import ml.melun.mangaview.source.CatalogQuery
import ml.melun.mangaview.source.SourceHttpMethod
import ml.melun.mangaview.source.PageByteStream
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.SourceResponse
import ml.melun.mangaview.source.SourceSearchQuery
import ml.melun.mangaview.source.SourceSeries
import ml.melun.mangaview.source.SourceTransport
import ml.melun.mangaview.source.SeriesKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WfwfComicSearchTest {
    @Test
    fun advertisedPagesOverlapButEarlierMatchingPageWins() = runTest {
        val thirdStarted = CompletableDeferred<Unit>()
        val requests = mutableListOf<Int>()
        val search = WfwfComicSearch(fetchCatalogPage = { pageNumber ->
            requests += pageNumber
            when (pageNumber) {
                1 -> WfwfComicCatalogPage(1, emptyList(), "2", listOf(2, 3))
                2 -> {
                    thirdStarted.await()
                    catalogPage(2, listOf(series(12, "Target earlier")), "3")
                }
                3 -> {
                    thirdStarted.complete(Unit)
                    catalogPage(3, listOf(series(13, "Target later")), null)
                }
                else -> error("Unadvertised page requested")
            }
        })
        val result = search.search(SourceSearchQuery("target", SeriesKind.COMIC))
        assertEquals(listOf("comic:12"), result.items.map { it.id.remoteKey })
        assertEquals(listOf(1, 2, 3), requests)
        assertEquals("comic:3", result.nextCursor)
    }

    @Test
    fun unusedLaterPageFailureDoesNotDiscardEarlierMatch() = runTest {
        val laterStarted = CompletableDeferred<Unit>()
        val search = WfwfComicSearch(fetchCatalogPage = { pageNumber ->
            when (pageNumber) {
                1 -> WfwfComicCatalogPage(1, emptyList(), "2", listOf(2, 3))
                2 -> { laterStarted.await(); catalogPage(2, listOf(series(12, "Target")), "3") }
                else -> { laterStarted.complete(Unit); error("Future page failed") }
            }
        })
        assertEquals(listOf("comic:12"), search.search(SourceSearchQuery("target", SeriesKind.COMIC)).items.map { it.id.remoteKey })
    }
    @Test
    fun findsTargetOnlyOnALaterCatalogPage() = runTest {
        val requests = mutableListOf<Int>()
        val search = WfwfComicSearch(fetchCatalogPage = { pageNumber ->
            requests += pageNumber
            when (pageNumber) {
                1 -> catalogPage(1, listOf(series(11, "Unrelated")), "2")
                2 -> catalogPage(2, listOf(series(12, "Target title")), "3")
                else -> catalogPage(pageNumber, emptyList(), null)
            }
        })

        val result = search.search(SourceSearchQuery(" target ", SeriesKind.COMIC))

        assertEquals(listOf("comic:12"), result.items.map { it.id.remoteKey })
        assertEquals("comic:3", result.nextCursor)
        assertEquals(listOf(1, 2), requests)
    }

    @Test
    fun retainsOpaqueCursorForFurtherMatchingPages() = runTest {
        val search = WfwfComicSearch(fetchCatalogPage = { pageNumber ->
            when (pageNumber) {
                1 -> catalogPage(1, listOf(series(11, "Target one")), "2")
                2 -> catalogPage(2, listOf(series(12, "Target two")), "3")
                else -> catalogPage(pageNumber, emptyList(), null)
            }
        })
        val query = SourceSearchQuery("target", SeriesKind.COMIC)

        val first = search.search(query)
        val second = search.search(query.copy(cursor = first.nextCursor))

        assertEquals(listOf("comic:11"), first.items.map { it.id.remoteKey })
        assertEquals("comic:2", first.nextCursor)
        assertEquals(listOf("comic:12"), second.items.map { it.id.remoteKey })
        assertEquals("comic:3", second.nextCursor)
    }

    @Test
    fun unrelatedTitlesAreExcludedFromTitleMatches() = runTest {
        val search = WfwfComicSearch(fetchCatalogPage = { pageNumber ->
            catalogPage(pageNumber, listOf(series(11, "Different title", subtitle = "Target author")), null)
        })

        val result = search.search(SourceSearchQuery("target", SeriesKind.COMIC))

        assertTrue(result.items.isEmpty())
        assertNull(result.nextCursor)
    }

    @Test
    fun ordinaryLatestComicCatalogRefreshesCacheButSearchReusesIt() = runTest {
        val path = "/cm?o=n&pg=1&t3="
        val transport = ComicCatalogTransport(mapOf(
            path to """
                <a href="/cl?toon=10001"><h3>Target comic</h3></a>
            """.trimIndent(),
        ))
        val source = WfwfContentSource(WfwfConfig("https://wfwf.test", "agent"), transport)
        val catalogQuery = CatalogQuery(SeriesKind.COMIC, CatalogOrder.LATEST)

        source.catalog(catalogQuery)
        val found = source.search(SourceSearchQuery("target", SeriesKind.COMIC))
        source.catalog(catalogQuery)

        assertEquals(listOf("comic:10001"), found.items.map { it.id.remoteKey })
        assertEquals(listOf(path, path), transport.requestPaths)
    }

    @Test
    fun expiredMetadataIsFetchedAgain() = runTest {
        var now = 0L
        var requests = 0
        val search = WfwfComicSearch(
            fetchCatalogPage = {
                requests += 1
                catalogPage(1, listOf(series(11, "Target")), null)
            },
            nowNanos = { now },
        )
        val query = SourceSearchQuery("target", SeriesKind.COMIC)

        search.search(query)
        now = 5L * 60L * 1_000_000_000L
        search.search(query)

        assertEquals(2, requests)
    }

    @Test
    fun malformedCursorAndCursorCyclesFailClosed() = runTest {
        val search = WfwfComicSearch(fetchCatalogPage = { pageNumber ->
            when (pageNumber) {
                1 -> catalogPage(1, emptyList(), "2")
                2 -> catalogPage(2, emptyList(), "1")
                else -> error("Unexpected page $pageNumber")
            }
        })

        val malformed = runCatching {
            search.search(SourceSearchQuery("target", SeriesKind.COMIC, cursor = "comic:02"))
        }.exceptionOrNull()
        val cycle = runCatching {
            search.search(SourceSearchQuery("target", SeriesKind.COMIC))
        }.exceptionOrNull()

        assertTrue(malformed is IllegalArgumentException)
        assertTrue(cycle is IllegalArgumentException)
    }

    private fun catalogPage(page: Int, items: List<SourceSeries>, nextCursor: String?) =
        WfwfComicCatalogPage(page, items, nextCursor)

    private fun series(id: Int, title: String, subtitle: String? = null) = SourceSeries(
        id = SeriesId(SourceId("wfwf"), "comic:$id"),
        title = title,
        subtitle = subtitle,
    )
}

private class ComicCatalogTransport(
    private val routes: Map<String, String>,
) : SourceTransport {
    val requestPaths = mutableListOf<String>()

    override suspend fun execute(request: SourceRequest): SourceResponse {
        require(request.method == SourceHttpMethod.GET)
        val uri = URI(request.url)
        val path = uri.rawPath + uri.rawQuery?.let { "?$it" }.orEmpty()
        requestPaths += path
        val bytes = requireNotNull(routes[path]) { "Unexpected WFWF route: $path" }.toByteArray()
        return SourceResponse(
            statusCode = 200,
            finalUrl = request.url,
            headers = mapOf("Content-Type" to listOf("text/html; charset=utf-8")),
            body = ComicCatalogBytes(bytes),
            contentLength = bytes.size.toLong(),
            contentType = "text/html; charset=utf-8",
        )
    }
}

private class ComicCatalogBytes(
    private val bytes: ByteArray,
) : PageByteStream {
    private var position = 0

    override suspend fun readAtMost(destination: ByteArray, offset: Int, byteCount: Int): Int {
        if (position >= bytes.size) return -1
        val count = minOf(byteCount, bytes.size - position)
        bytes.copyInto(destination, offset, position, position + count)
        position += count
        return count
    }

    override fun close() = Unit
}
