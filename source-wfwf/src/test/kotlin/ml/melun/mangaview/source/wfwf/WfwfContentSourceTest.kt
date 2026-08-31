package ml.melun.mangaview.source.wfwf

import java.util.ArrayDeque
import kotlinx.coroutines.test.runTest
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.source.PageByteStream
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.SourceResponse
import ml.melun.mangaview.source.SourceTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WfwfContentSourceTest {
    @Test
    fun catalogCombinesSplitCoverAndTitleLinksWithoutPlaceholderNames() {
        val document = org.jsoup.Jsoup.parse(
            """
            <article class="item">
              <a href="/list?toon=72442"><img data-src="https://cdn.example/72442.jpg"></a>
              <a href="/list?toon=72442"><h3 class="item-title">정확한 작품명</h3></a>
            </article>
            """.trimIndent(),
        )

        val items = WfwfHtmlParser().search(document) { key ->
            SeriesId(SourceId("wfwf"), key.encode())
        }

        assertEquals("정확한 작품명", items.single().title)
        assertEquals("https://cdn.example/72442.jpg", items.single().thumbnailKey)
    }

    @Test
    fun oldEpisodeAdjacencyLoadsEveryDeclaredCatalogPage() = runTest {
        val firstCatalog = """
            <a href="/cv?toon=10007&num=183"><span class="subject">183화</span></a>
            <a href="/cv?toon=10007&num=182"><span class="subject">182화</span></a>
            <a href="/cl?toon=10007&s=n&pg=2">2</a>
        """.trimIndent()
        val viewer = """
            <div class="viewer-wrap"><img src="https://cdn.example/pages/001.jpg"></div>
        """.trimIndent()
        val secondCatalog = """
            <a href="/cv?toon=10007&num=29"><span class="subject">29화</span></a>
            <a href="/cv?toon=10007&num=28"><span class="subject">28화</span></a>
            <a href="/cv?toon=10007&num=27"><span class="subject">27화</span></a>
        """.trimIndent()
        val transport = RoutingTransport(mapOf(
            "/cl?toon=10007" to firstCatalog,
            "/cv?toon=10007&num=28" to viewer,
            "/cl?toon=10007&s=n&pg=2" to secondCatalog,
        ))
        val source = WfwfContentSource(WfwfConfig("https://wfwf.test", "agent"), transport)
        val series = SeriesId(SourceId("wfwf"), WfwfSeriesKey(WfwfKind.COMIC, 10007).encode())

        val manifest = source.manifest(EpisodeId(series, "28"))

        assertEquals("27", manifest.previousEpisodeId?.remoteKey)
        assertEquals("29", manifest.nextEpisodeId?.remoteKey)
        assertEquals(3, transport.requests.size)
    }

    @Test
    fun comicAndWebtoonDocumentsBecomeTheSameManifestShape() = runTest {
        val catalog = """
            <a href="/cl?toon=10007&num=12"><span class="subject">12화</span></a>
            <a href="/cl?toon=10007&num=11"><span class="subject">11화</span></a>
        """.trimIndent()
        val viewer = """
            <div class="image-view">
              <img data-src="https://cdn.example/pages/001.jpg">
              <img src="https://cdn.example/pages/002.webp">
              <img class="banner" src="https://cdn.example/banner-ad.jpg">
            </div>
        """.trimIndent()
        val transport = QueueTransport(catalog, viewer)
        val source = WfwfContentSource(WfwfConfig("https://wfwf.test", "agent"), transport)
        val series = SeriesId(SourceId("wfwf"), WfwfSeriesKey(WfwfKind.COMIC, 10007).encode())

        val episodes = source.episodes(series).items
        val manifest = source.manifest(EpisodeId(series, "11"))

        assertEquals(listOf("12", "11"), episodes.map { it.id.remoteKey })
        assertEquals(2, manifest.pages.size)
        assertEquals(listOf("p0000", "p0001"), manifest.pages.map { it.id.remoteKey })
        assertEquals("12", manifest.nextEpisodeId?.remoteKey)
        assertEquals(null, manifest.previousEpisodeId)
        assertTrue(transport.requests.any { "/cv?toon=10007&num=11" in it.url })
    }

    @Test
    fun slugLikeTitlesInMarkupDoNotAffectNumericEpisodeIdentity() = runTest {
        val catalog = """
            <a class="card" href="/view?toon=88&num=901">
              <span class="subject">외전 10.5화</span>
            </a>
        """.trimIndent()
        val transport = QueueTransport(catalog)
        val source = WfwfContentSource(WfwfConfig("https://wfwf.test", "agent"), transport)
        val series = SeriesId(SourceId("wfwf"), WfwfSeriesKey(WfwfKind.WEBTOON, 88).encode())

        val episode = source.episodes(series).items.single()

        assertEquals("901", episode.id.remoteKey)
        assertEquals("외전 10.5화", episode.title)
    }

    @Test
    fun advertisementImagesOutsideTheViewerAreNeverManifestPages() = runTest {
        val document = org.jsoup.Jsoup.parse(
            """
            <main>
              <img src="https://ads.example/904-strip.jpg" alt="광고">
              <div class="viewer-wrap">
                <img data-src="https://cdn.example/series/001.jpg" src="/assets/img/sprite.png">
                <img data-src="https://cdn.example/series/002.jpg" src="/assets/img/sprite.png">
              </div>
            </main>
            """.trimIndent(),
            "https://wfwf.test/cv?toon=1&num=2",
        )

        val images = WfwfHtmlParser().pageImages(document)

        assertEquals(
            listOf(
                "https://cdn.example/series/001.jpg",
                "https://cdn.example/series/002.jpg",
            ),
            images,
        )
    }

    @Test
    fun viewerNavigationAvoidsTheCatalogOnTheColdManifestPath() = runTest {
        val viewer = """
            <div class="vbar-title"><div class="vt-name">작품 28화</div></div>
            <div class="viewer-wrap"><img data-src="https://cdn.example/pages/001.jpg"></div>
            <div class="vnav-row">
              <a class="vnav-btn" href="/cv?toon=10007&amp;num=27">previous</a>
              <button>menu</button>
              <a class="vnav-btn" href="/cv?toon=10007&amp;num=29">next</a>
            </div>
        """.trimIndent()
        val transport = RoutingTransport(mapOf("/cv?toon=10007&num=28" to viewer))
        val source = WfwfContentSource(WfwfConfig("https://wfwf.test", "agent"), transport)
        val series = SeriesId(SourceId("wfwf"), WfwfSeriesKey(WfwfKind.COMIC, 10007).encode())

        val manifest = source.manifest(EpisodeId(series, "28"))

        assertEquals("작품 28화", manifest.title)
        assertEquals("27", manifest.previousEpisodeId?.remoteKey)
        assertEquals("29", manifest.nextEpisodeId?.remoteKey)
        assertEquals(listOf("/cv?toon=10007&num=28"), transport.requestPaths())
    }
}

private class RoutingTransport(
    private val routes: Map<String, String>,
) : SourceTransport {
    val requests = mutableListOf<SourceRequest>()

    fun requestPaths(): List<String> = requests.map { request ->
        val uri = java.net.URI(request.url)
        uri.rawPath + uri.rawQuery?.let { "?$it" }.orEmpty()
    }

    override suspend fun execute(request: SourceRequest): SourceResponse {
        requests += request
        val path = java.net.URI(request.url).rawPath +
            java.net.URI(request.url).rawQuery?.let { "?$it" }.orEmpty()
        val bytes = requireNotNull(routes[path]) { "Unexpected route: $path" }.toByteArray()
        return SourceResponse(
            statusCode = 200,
            finalUrl = request.url,
            headers = mapOf("Content-Type" to listOf("text/html; charset=utf-8")),
            body = BytesStream(bytes),
            contentLength = bytes.size.toLong(),
            contentType = "text/html; charset=utf-8",
        )
    }
}

private class QueueTransport(vararg bodies: String) : SourceTransport {
    private val bodies = ArrayDeque(bodies.toList())
    val requests = mutableListOf<SourceRequest>()

    override suspend fun execute(request: SourceRequest): SourceResponse {
        requests += request
        val bytes = bodies.removeFirst().toByteArray()
        return SourceResponse(
            statusCode = 200,
            finalUrl = request.url,
            headers = mapOf("Content-Type" to listOf("text/html; charset=utf-8")),
            body = BytesStream(bytes),
            contentLength = bytes.size.toLong(),
            contentType = "text/html; charset=utf-8",
        )
    }
}

private class BytesStream(private val bytes: ByteArray) : PageByteStream {
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
