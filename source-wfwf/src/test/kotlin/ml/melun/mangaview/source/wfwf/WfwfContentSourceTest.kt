package ml.melun.mangaview.source.wfwf

import java.util.ArrayDeque
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.source.PageFetchPriority
import ml.melun.mangaview.source.PageByteStream
import ml.melun.mangaview.source.CatalogOrder
import ml.melun.mangaview.source.CatalogQuery
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.SourceResponse
import ml.melun.mangaview.source.SourcePageUnavailableException
import ml.melun.mangaview.source.SourceGenre
import ml.melun.mangaview.source.SourceTransport
import ml.melun.mangaview.source.SeriesKind
import ml.melun.mangaview.source.SourceSearchQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WfwfContentSourceTest {
    @Test
    fun oneImageElementContributesOnePageDespiteMultipleValidSources() {
        val document = org.jsoup.Jsoup.parse("""
            <div class="viewer-wrap">
              <img data-original="/pages/p1.jpg" data-src="/pages/p1-alt.jpg" src="/pages/p1-small.jpg">
              <img data-src="/assets/loading.png" src="/pages/p2.jpg">
              <img data-lazy-src="/pages/p3.jpg" src="/assets/blank.png">
            </div>
        """, "https://wfwf.test/view?toon=1&num=2")
        assertEquals(listOf(1, 2, 3).map { "https://wfwf.test/pages/p$it.jpg" },
            WfwfHtmlParser().pageImages(document))
    }

    @Test
    fun refreshedProvider404IsReportedAsUnavailableInsteadOfRetriedForever() = runTest {
        val transport = MissingPageTransport()
        val source = WfwfContentSource(WfwfConfig("https://wfwf.test", "agent"), transport)
        val series = SeriesId(SourceId("wfwf"), WfwfSeriesKey(WfwfKind.COMIC, 10).encode())
        val episode = EpisodeId(series, "1")
        val page = source.manifest(episode).pages.single().id

        val failure = runCatching { source.openPage(page).close() }.exceptionOrNull()

        assertTrue(failure is SourcePageUnavailableException)
        assertEquals(2, transport.documentRequests)
        assertEquals(1, transport.pageRequests)
    }

    @Test
    fun deadReusableImageRouteFallsThroughToTheFreshHedge() = runTest {
        val transport = RouteRecoveryTransport()
        val source = WfwfContentSource(WfwfConfig("https://wfwf.test", "agent"), transport)
        val series = SeriesId(SourceId("wfwf"), WfwfSeriesKey(WfwfKind.COMIC, 10).encode())
        val episode = EpisodeId(series, "1")
        source.manifest(episode)

        source.openPage(PageId.at(episode, 0), null, PageFetchPriority.VISIBLE).close()

        assertEquals(2, transport.pageAttempts)
        assertEquals(0, transport.retiredRoutes)
        assertEquals(1, transport.freshRouteAttempts)
    }

    @Test
    fun silentVisibleImageRouteLosesToTheFreshParallelHedge() = runTest {
        val transport = RouteRecoveryTransport(hangFirstPage = true)
        val source = WfwfContentSource(WfwfConfig("https://wfwf.test", "agent"), transport)
        val series = SeriesId(SourceId("wfwf"), WfwfSeriesKey(WfwfKind.COMIC, 10).encode())
        val episode = EpisodeId(series, "1")
        source.manifest(episode)

        source.openPage(PageId.at(episode, 0), null, PageFetchPriority.VISIBLE).close()

        assertEquals(2, transport.pageAttempts)
        assertEquals(0, transport.retiredRoutes)
        assertEquals(1, transport.freshRouteAttempts)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun focusImageUsesAllIndependentRoutesWithoutAStagger() = runTest {
        val transport = IndependentVisibleRoutesTransport()
        val source = WfwfContentSource(WfwfConfig("https://wfwf.test", "agent"), transport)
        val series = SeriesId(SourceId("wfwf"), WfwfSeriesKey(WfwfKind.COMIC, 10).encode())
        val episode = EpisodeId(series, "1")
        source.manifest(episode)

        source.openPage(PageId.at(episode, 0), null, PageFetchPriority.FOCUS).close()

        assertEquals(listOf("primary", "fresh", "alternate"), transport.pageRoutes.sortedBy {
            listOf("primary", "fresh", "alternate").indexOf(it)
        })
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun focusImageRacesIndependentProtocolPoolsWithoutAStagger() = runTest {
        val transport = IndependentProtocolRoutesTransport()
        val source = WfwfContentSource(WfwfConfig("https://wfwf.test", "agent"), transport)
        val series = SeriesId(SourceId("wfwf"), WfwfSeriesKey(WfwfKind.COMIC, 10).encode())
        val episode = EpisodeId(series, "1")
        source.manifest(episode)

        source.openPage(PageId.at(episode, 0), null, PageFetchPriority.FOCUS).close()

        assertEquals(listOf("primary", "fresh"), transport.pageRoutes.sortedBy {
            listOf("primary", "fresh").indexOf(it)
        })
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun visibleNeighborDoesNotDuplicateAHealthyPrimaryRoute() = runTest {
        val transport = HealthyIndependentRoutesTransport()
        val source = WfwfContentSource(WfwfConfig("https://wfwf.test", "agent"), transport)
        val series = SeriesId(SourceId("wfwf"), WfwfSeriesKey(WfwfKind.COMIC, 10).encode())
        val episode = EpisodeId(series, "1")
        source.manifest(episode)

        source.openPage(PageId.at(episode, 0), null, PageFetchPriority.VISIBLE).close()

        assertEquals(listOf("primary"), transport.pageRoutes)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun searchKindFilterNeverLeaksTheOtherContentType() = runTest {
        val html = """
            <a href="/cl?toon=11"><h3>만화 결과</h3></a>
            <a href="/list?toon=22"><h3>웹툰 결과</h3></a>
        """.trimIndent()
        val source = WfwfContentSource(WfwfConfig("https://wfwf.test", "agent"), QueueTransport(html))

        val result = source.search(SourceSearchQuery("결과", SeriesKind.COMIC)).items

        assertEquals(listOf("comic:11"), result.map { it.id.remoteKey })
    }

    @Test
    fun quickReadActionsNeverReplaceRealEpisodeRows() {
        val document = org.jsoup.Jsoup.parse(
            """
            <div class="quick-read"><a href="/view?toon=77&num=1191">▶최신화 보기|1191화</a></div>
            <div class="quick-read"><a href="/view?toon=77&num=1">📖첫화부터 정주행</a></div>
            <div class="episode-list">
              <a href="/view?toon=77&num=1191"><span class="subject">1191화</span></a>
              <a href="/view?toon=77&num=1190"><span class="subject">1190화</span></a>
              <a href="/view?toon=77&num=1"><span class="subject">1화</span></a>
            </div>
            """.trimIndent(),
        )
        val series = SeriesId(SourceId("wfwf"), "webtoon:77")

        val episodes = WfwfHtmlParser().episodes(
            document,
            series,
            WfwfSeriesKey(WfwfKind.WEBTOON, 77),
        )

        assertEquals(listOf("1191화", "1190화", "1화"), episodes.map { it.title })
    }

    @Test
    fun exposesVerifiedProviderGenresWithoutANetworkRoundTrip() = runTest {
        val source = WfwfContentSource(WfwfConfig("https://wfwf.test", "agent"), QueueTransport())

        assertEquals(17, source.genres(SeriesKind.WEBTOON).size)
        assertEquals(35, source.genres(SeriesKind.COMIC).size)
        assertTrue(source.genres(SeriesKind.WEBTOON).any { it.key == "t2:3" && it.label == "성인" })
        assertTrue(source.genres(SeriesKind.COMIC).any { it.key == "t3:백합" && it.label == "백합" })
        assertTrue(source.genres(SeriesKind.COMIC).any { it.key == "t3:sf" && it.label == "SF" })
        assertTrue(source.genres(SeriesKind.COMIC).any { it.key == "t3:bl" && it.label == "BL" })
        assertTrue(source.genres(SeriesKind.COMIC).any { it.key == "t3:ts" && it.label == "TS" })
        assertTrue(source.genres(SeriesKind.COMIC).none { it.label == "일상+치유" })
        assertTrue(source.genres(SeriesKind.COMIC).any { it.label == "무협" })
    }

    @Test
    fun catalogUsesTheCurrentProviderGenreAndOrderParameters() = runTest {
        val adultTransport = QueueTransport("<a href='/list?toon=1'><h3>성인 작품</h3></a>")
        val adultSource = WfwfContentSource(
            WfwfConfig("https://wfwf.test", "agent"),
            adultTransport,
        )
        adultSource.catalog(
            CatalogQuery(
                SeriesKind.WEBTOON,
                CatalogOrder.LATEST,
                SourceGenre("t2:3", "성인"),
            ),
        )
        assertEquals("/ing?o=n&pg=1&t1=&t2=3&t3=", adultTransport.requestPaths().single())

        val yuriTransport = QueueTransport("<a href='/cl?toon=2'><h3>백합 작품</h3></a>")
        val yuriSource = WfwfContentSource(
            WfwfConfig("https://wfwf.test", "agent"),
            yuriTransport,
        )
        yuriSource.catalog(
            CatalogQuery(
                SeriesKind.COMIC,
                CatalogOrder.NEW,
                SourceGenre("t3:백합", "백합"),
            ),
        )
        assertEquals("/cm?o=r&pg=1&t3=%B9%E9%C7%D5", yuriTransport.requestPaths().single())
    }

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
    fun currentProviderCardKeepsItsOwnTitleAndGenreEvidence() {
        val document = org.jsoup.Jsoup.parse(
            """
            <div class="thumb-grid">
              <a class="t-card" href="/list?toon=75698">
                <div class="t-img"><img alt="솔스티스" src="https://cdn.example/75698.jpg"></div>
                <div class="t-title">솔스티스</div>
                <div class="t-genre">성인/로맨스</div>
                <div class="t-ep">51화</div>
              </a>
              <a class="t-card" href="/list?toon=76044">
                <div class="t-title">다른 작품</div><div class="t-genre">스포츠</div>
              </a>
            </div>
            """.trimIndent(),
        )

        val item = WfwfHtmlParser().search(document) { key ->
            SeriesId(SourceId("wfwf"), key.encode())
        }.first { it.id.remoteKey == "webtoon:75698" }

        assertEquals("솔스티스", item.title)
        assertEquals("성인/로맨스", item.subtitle)
    }

    @Test
    fun catalogNeverBorrowsAnotherSeriesTitleFromABroadContainer() {
        val document = org.jsoup.Jsoup.parse(
            """
            <div class="grid">
              <a href="/list?toon=11"><img src="https://cdn.example/11.jpg"></a>
              <article><a href="/list?toon=22"><h3 class="item-title">22번 작품</h3></a></article>
            </div>
            """.trimIndent(),
        )

        val items = WfwfHtmlParser().search(document) { key ->
            SeriesId(SourceId("wfwf"), key.encode())
        }

        assertEquals(listOf("webtoon:22"), items.map { it.id.remoteKey })
        assertEquals(listOf("22번 작품"), items.map { it.title })
    }

    @Test
    fun genericUpdateLinksAreNeverExposedAsSeries() {
        val document = org.jsoup.Jsoup.parse(
            """<a href="/list?toon=77"><span class="title">업데이트</span></a>""",
        )

        val items = WfwfHtmlParser().search(document) { key ->
            SeriesId(SourceId("wfwf"), key.encode())
        }

        assertTrue(items.isEmpty())
    }

    @Test
    fun liveGenreLinksDefineTheExposedGenreSet() {
        val document = org.jsoup.Jsoup.parse(
            """
            <a href="/ing?o=n&amp;pg=1&amp;t1=&amp;t2=3&amp;t3=">성인</a>
            <a href="/ing?o=n&amp;pg=1&amp;t1=&amp;t2=&amp;t3=%B5%E5%B6%F3%B8%B6">드라마</a>
            <a href="/ing?o=n&amp;pg=1&amp;t1=mon&amp;t2=&amp;t3=">월</a>
            """.trimIndent(),
        )

        assertEquals(listOf("성인", "드라마"), WfwfHtmlParser().genres(document).map { it.label })
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

private class MissingPageTransport : SourceTransport {
    var documentRequests = 0
    var pageRequests = 0

    override suspend fun execute(request: SourceRequest): SourceResponse {
        return if (java.net.URI(request.url).host == "cdn.example") {
            pageRequests += 1
            SourceResponse(
                statusCode = 404,
                finalUrl = request.url,
                headers = emptyMap(),
                body = BytesStream(ByteArray(0)),
                contentLength = 0L,
                contentType = null,
            )
        } else {
            documentRequests += 1
            val bytes = """
                <div class="viewer-wrap">
                  <img data-src="https://cdn.example/missing.jpg">
                </div>
                <div class="vnav-row"></div>
            """.trimIndent().toByteArray()
            SourceResponse(
                statusCode = 200,
                finalUrl = request.url,
                headers = mapOf("Content-Type" to listOf("text/html; charset=utf-8")),
                body = BytesStream(bytes),
                contentLength = bytes.size.toLong(),
                contentType = "text/html; charset=utf-8",
            )
        }
    }
}

private class RouteRecoveryTransport(
    private val hangFirstPage: Boolean = false,
) : SourceTransport {
    var pageAttempts = 0
    var retiredRoutes = 0
    var freshRouteAttempts = 0

    override fun retireIdleConnections() {
        retiredRoutes += 1
    }

    override suspend fun executeOnFreshRoute(request: SourceRequest): SourceResponse {
        freshRouteAttempts += 1
        return execute(request)
    }

    override suspend fun execute(request: SourceRequest): SourceResponse {
        val uri = java.net.URI(request.url)
        if (uri.host == "cdn.example") {
            pageAttempts += 1
            if (pageAttempts == 1) {
                if (hangFirstPage) awaitCancellation()
                throw IOException("stale route")
            }
            return response(request.url, byteArrayOf(1, 2, 3), "image/jpeg")
        }
        val document = """
            <div class="viewer-wrap"><img data-src="https://cdn.example/page.jpg"></div>
            <div class="vnav-row"></div>
        """.trimIndent().toByteArray()
        return response(request.url, document, "text/html; charset=utf-8")
    }

    private fun response(url: String, bytes: ByteArray, type: String) = SourceResponse(
        statusCode = 200,
        finalUrl = url,
        headers = mapOf("Content-Type" to listOf(type)),
        body = BytesStream(bytes),
        contentLength = bytes.size.toLong(),
        contentType = type,
    )
}

private class IndependentVisibleRoutesTransport : SourceTransport {
    val pageRoutes = mutableListOf<String>()

    override fun routeParallelism(): Int = 3

    override suspend fun execute(request: SourceRequest): SourceResponse = page(request, "primary")

    override suspend fun executeOnFreshRoute(request: SourceRequest): SourceResponse = page(request, "fresh")

    override suspend fun executeOnAlternateRoute(request: SourceRequest): SourceResponse = page(request, "alternate")

    private suspend fun page(request: SourceRequest, route: String): SourceResponse {
        if (java.net.URI(request.url).host != "cdn.example") {
            val document = """
                <div class="viewer-wrap"><img data-src="https://cdn.example/page.jpg"></div>
                <div class="vnav-row"></div>
            """.trimIndent().toByteArray()
            return response(request.url, document, "text/html; charset=utf-8")
        }
        pageRoutes += route
        if (route != "alternate") awaitCancellation()
        return response(request.url, byteArrayOf(1, 2, 3), "image/jpeg")
    }

    private fun response(url: String, bytes: ByteArray, type: String) = SourceResponse(
        statusCode = 200,
        finalUrl = url,
        headers = mapOf("Content-Type" to listOf(type)),
        body = BytesStream(bytes),
        contentLength = bytes.size.toLong(),
        contentType = type,
    )
}

private class IndependentProtocolRoutesTransport : SourceTransport {
    val pageRoutes = mutableListOf<String>()

    override fun routeParallelism(): Int = 2

    override fun supportsProtocolSelection(): Boolean = true

    override suspend fun execute(request: SourceRequest): SourceResponse = page(request, "primary")

    override suspend fun executeOnFreshRoute(request: SourceRequest): SourceResponse = page(request, "fresh")

    override suspend fun executeOnAlternateRoute(request: SourceRequest): SourceResponse =
        page(request, "alternate")

    private suspend fun page(request: SourceRequest, route: String): SourceResponse {
        if (java.net.URI(request.url).host != "cdn.example") {
            val document = """
                <div class="viewer-wrap"><img data-src="https://cdn.example/page.jpg"></div>
                <div class="vnav-row"></div>
            """.trimIndent().toByteArray()
            return response(request.url, document, "text/html; charset=utf-8")
        }
        pageRoutes += route
        if (route != "fresh") awaitCancellation()
        return response(request.url, byteArrayOf(1, 2, 3), "image/jpeg")
    }

    private fun response(url: String, bytes: ByteArray, type: String) = SourceResponse(
        statusCode = 200,
        finalUrl = url,
        headers = mapOf("Content-Type" to listOf(type)),
        body = BytesStream(bytes),
        contentLength = bytes.size.toLong(),
        contentType = type,
    )
}

private class HealthyIndependentRoutesTransport : SourceTransport {
    val pageRoutes = mutableListOf<String>()

    override fun routeParallelism(): Int = 3

    override suspend fun execute(request: SourceRequest): SourceResponse = page(request, "primary")

    override suspend fun executeOnFreshRoute(request: SourceRequest): SourceResponse = page(request, "fresh")

    override suspend fun executeOnAlternateRoute(request: SourceRequest): SourceResponse = page(request, "alternate")

    private fun page(request: SourceRequest, route: String): SourceResponse {
        if (java.net.URI(request.url).host != "cdn.example") {
            val document = """
                <div class="viewer-wrap"><img data-src="https://cdn.example/page.jpg"></div>
                <div class="vnav-row"></div>
            """.trimIndent().toByteArray()
            return response(request.url, document, "text/html; charset=utf-8")
        }
        pageRoutes += route
        return response(request.url, byteArrayOf(1, 2, 3), "image/jpeg")
    }

    private fun response(url: String, bytes: ByteArray, type: String) = SourceResponse(
        statusCode = 200,
        finalUrl = url,
        headers = mapOf("Content-Type" to listOf(type)),
        body = BytesStream(bytes),
        contentLength = bytes.size.toLong(),
        contentType = type,
    )
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

    fun requestPaths(): List<String> = requests.map { request ->
        val uri = java.net.URI(request.url)
        uri.rawPath + uri.rawQuery?.let { "?$it" }.orEmpty()
    }

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
