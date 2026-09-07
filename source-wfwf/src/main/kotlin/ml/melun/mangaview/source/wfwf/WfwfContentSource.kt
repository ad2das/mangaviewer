package ml.melun.mangaview.source.wfwf

import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URLEncoder
import java.net.URI
import java.nio.charset.Charset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.PageSpec
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.source.AdjacentEpisodes
import ml.melun.mangaview.source.CatalogOrder
import ml.melun.mangaview.source.CatalogQuery
import ml.melun.mangaview.source.ContentSource
import ml.melun.mangaview.source.OpenedPage
import ml.melun.mangaview.source.PageValidation
import ml.melun.mangaview.source.PageFetchPriority
import ml.melun.mangaview.source.PreparationIntent
import ml.melun.mangaview.source.SourceEpisode
import ml.melun.mangaview.source.SourceGenre
import ml.melun.mangaview.source.SourceHttpMethod
import ml.melun.mangaview.source.SourcePage
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.SourceResponse
import ml.melun.mangaview.source.SourceSeries
import ml.melun.mangaview.source.SourceSearchQuery
import ml.melun.mangaview.source.SearchField
import ml.melun.mangaview.source.SeriesKind
import ml.melun.mangaview.source.SourceTransport
import ml.melun.mangaview.source.readBytes
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

data class WfwfConfig(
    val initialOrigin: String,
    val userAgent: String,
    val imageOriginHints: List<String> = emptyList(),
    val manifestCacheEpisodes: Int = 12,
) {
    init {
        require(manifestCacheEpisodes > 0) { "WFWF manifest cache capacity must be positive" }
    }
}

class WfwfContentSource(
    private val config: WfwfConfig,
    private val transport: SourceTransport,
    preparationScope: CoroutineScope? = null,
    private val parser: WfwfHtmlParser = WfwfHtmlParser(),
    private val originResolver: WfwfOriginResolver = WfwfOriginResolver(transport, config.userAgent),
) : ContentSource {
    override val id = SourceId("wfwf")
    private val origin = WfwfOriginCoordinator(config.initialOrigin, originResolver, preparationScope)
    private val catalogStore = WfwfCatalogStore(::fetchCatalog)
    private val manifestStore = WfwfManifestStore(config.manifestCacheEpisodes, ::fetchManifest)
    private val comicSearch = WfwfComicSearch(::fetchComicCatalogPage)

    /** Resolves and warms only the reusable provider origin; it never fetches user content. */
    fun warm() {
        origin.start()
    }

    override suspend fun search(query: String, cursor: String?): SourcePage<SourceSeries> {
        return search(SourceSearchQuery(query, cursor = cursor))
    }

    override suspend fun search(query: SourceSearchQuery): SourcePage<SourceSeries> {
        if (query.kind == SeriesKind.COMIC) return comicSearch.search(query)
        if (query.cursor != null) return SourcePage(emptyList())
        val encoded = URLEncoder.encode(query.text.trim(), Charset.forName("EUC-KR").name())
        val document = document("/search.html?q=$encoded")
        val parsed = parser.search(document, ::seriesId)
        val requestedKind = query.kind
        val kindFiltered = parsed.filter { item ->
            requestedKind == null || runCatching { WfwfSeriesKey.decode(item.id).kind }.getOrNull().matches(requestedKind)
        }
        val filtered = if (query.field == SearchField.TITLE) kindFiltered else {
            kindFiltered.filter { it.subtitle?.contains(query.text, ignoreCase = true) == true }
        }
        return SourcePage(filtered)
    }

    override suspend fun catalog(query: CatalogQuery): SourcePage<SourceSeries> {
        val page = WfwfCatalogPagination.page(query.cursor)
        if (query.kind == SeriesKind.COMIC && query.order == CatalogOrder.LATEST && query.genre == null) {
            val live = fetchComicCatalogPage(page)
            comicSearch.record(live)
            return SourcePage(live.items, live.nextCursor)
        }
        val firstPagePath = WfwfCatalogPagination.path(query, page = 1)
        val catalogDocument = document(WfwfCatalogPagination.path(query, page))
        val items = parser.search(catalogDocument, ::seriesId).filter { item ->
            runCatching { WfwfSeriesKey.decode(item.id).kind }.getOrNull().matches(query.kind)
        }
        val nextCursor = WfwfCatalogPagination.nextPageCursor(catalogDocument, firstPagePath, page)
        return SourcePage(items, nextCursor)
    }

    override suspend fun genres(kind: SeriesKind): List<SourceGenre> = when (kind) {
        SeriesKind.WEBTOON -> WFWF_WEBTOON_GENRES
        SeriesKind.COMIC -> WFWF_COMIC_GENRES
    }

    override suspend fun episodes(seriesId: SeriesId, cursor: String?): SourcePage<SourceEpisode> {
        require(seriesId.sourceId == id) { "Series belongs to another source" }
        if (cursor != null) return SourcePage(emptyList())
        return SourcePage(catalogStore.load(seriesId, refresh = true))
    }

    override suspend fun manifest(episodeId: EpisodeId): EpisodeManifest {
        require(episodeId.seriesId.sourceId == id) { "Episode belongs to another source" }
        return manifestStore.load(episodeId).payload.manifest
    }

    private suspend fun fetchManifest(episodeId: EpisodeId): WfwfManifestPayload {
        val key = WfwfSeriesKey.decode(episodeId.seriesId)
        val document = document(viewPath(key, episodeId.remoteKey))
        val images = parser.pageImages(document)
        require(images.isNotEmpty()) { "WFWF episode contains no page images" }
        val metadata = parser.viewerMetadata(document, key, episodeId.remoteKey)
        val catalog = if (metadata.navigationKnown) null else {
            catalogStore.load(episodeId.seriesId, refresh = false)
        }
        val adjacent = catalog?.let { adjacentFrom(it, episodeId) } ?: AdjacentEpisodes(
            previous = metadata.previousEpisodeKey?.let { EpisodeId(episodeId.seriesId, it) },
            next = metadata.nextEpisodeKey?.let { EpisodeId(episodeId.seriesId, it) },
        )
        val title = metadata.title
            ?: catalog?.firstOrNull { it.id == episodeId }?.title
            ?: episodeId.remoteKey
        val pageIds = images.mapIndexed { index, _ -> PageId.at(episodeId, index) }
        return WfwfManifestPayload(
            manifest = EpisodeManifest(
                id = episodeId,
                title = title,
                pages = pageIds.mapIndexed { index, pageId -> PageSpec(pageId, index) },
                previousEpisodeId = adjacent.previous,
                nextEpisodeId = adjacent.next,
            ),
            pageUrls = pageIds.zip(images).toMap(),
        )
    }

    override suspend fun adjacent(episodeId: EpisodeId): AdjacentEpisodes {
        require(episodeId.seriesId.sourceId == id) { "Episode belongs to another source" }
        val catalog = catalogStore.load(episodeId.seriesId, refresh = false)
        return adjacentFrom(catalog, episodeId)
    }

    override suspend fun prepare(episodeId: EpisodeId, intent: PreparationIntent) {
        require(episodeId.seriesId.sourceId == id) { "Episode belongs to another source" }
        origin.start()
    }

    override suspend fun openPage(
        pageId: PageId,
        validation: PageValidation?,
    ): OpenedPage = openPage(pageId, validation, PageFetchPriority.NORMAL)

    override suspend fun openPage(
        pageId: PageId,
        validation: PageValidation?,
        priority: PageFetchPriority,
    ): OpenedPage {
        require(pageId.episodeId.seriesId.sourceId == id) { "Page belongs to another source" }
        val registered = resolvePage(pageId)
        val response = executePageWithRouteRecovery(pageId, registered.url, validation, priority)
        if (response.statusCode in 200..299) return response.openedWfwfPage()
        val statusCode = response.statusCode
        response.close()
        if (statusCode !in EXPIRED_PAGE_STATUSES) throw wfwfPageFailure(statusCode)
        val refreshed = manifestStore.refreshIfCurrent(pageId.episodeId, registered.revision)
        val refreshedUrl = refreshed.payload.pageUrls[pageId]
            ?: throw IllegalStateException("WFWF refreshed manifest no longer contains the page")
        if (refreshedUrl == registered.url) {
            throw refreshedWfwfPageFailure(statusCode)
        }
        val retried = executePageWithRouteRecovery(pageId, refreshedUrl, validation, priority)
        if (retried.statusCode in 200..299) return retried.openedWfwfPage()
        val retryStatus = retried.statusCode
        retried.close()
        throw refreshedWfwfPageFailure(retryStatus)
    }

    override suspend fun openArtwork(series: SourceSeries): OpenedPage? {
        require(series.id.sourceId == id) { "Series belongs to another source" }
        val value = series.thumbnailKey?.takeIf(String::isNotBlank) ?: return null
        val url = runCatching { URI("${origin.current()}/").resolve(value).toString() }.getOrNull() ?: return null
        val response = transport.execute(SourceRequest(url, headers = requestHeaders(origin.resolve(listPath(WfwfSeriesKey.decode(series.id))))))
        if (response.statusCode !in 200..299) {
            response.close()
            return null
        }
        return response.openedWfwfPage()
    }

    override suspend fun seriesUrl(seriesId: SeriesId): String? {
        require(seriesId.sourceId == id) { "Series belongs to another source" }
        return origin.resolve(listPath(WfwfSeriesKey.decode(seriesId)))
    }

    private suspend fun resolvePage(pageId: PageId): WfwfPageLookup.Found =
        when (val lookup = manifestStore.page(pageId)) {
            is WfwfPageLookup.Found -> lookup
            WfwfPageLookup.MissingEpisode -> manifestStore.load(pageId.episodeId).registered(pageId)
            is WfwfPageLookup.MissingPage ->
                manifestStore.refreshIfCurrent(pageId.episodeId, lookup.revision).registered(pageId)
        }

    private fun WfwfManifestEntry.registered(pageId: PageId): WfwfPageLookup.Found {
        val url = payload.pageUrls[pageId]
            ?: throw IllegalStateException("WFWF manifest does not contain the requested page")
        return WfwfPageLookup.Found(url, revision)
    }

    private suspend fun executePage(
        pageId: PageId,
        pageUrl: String,
        validation: PageValidation?,
        priority: PageFetchPriority,
    ): SourceResponse {
        val headers = requestHeaders(referer = origin.resolve(viewPathFor(pageId.episodeId))).toMutableMap()
        validation?.entityTag?.let { headers["If-None-Match"] = it }
        validation?.lastModified?.let { headers["If-Modified-Since"] = it }
        return transport.execute(SourceRequest(pageUrl, headers = headers, priority = priority))
    }

    private suspend fun executePageWithRouteRecovery(
        pageId: PageId,
        pageUrl: String,
        validation: PageValidation?,
        priority: PageFetchPriority,
    ): SourceResponse {
        if (priority == PageFetchPriority.FOCUS ||
            priority == PageFetchPriority.VISIBLE ||
            priority == PageFetchPriority.IMMINENT_FORWARD ||
            priority == PageFetchPriority.FORWARD ||
            priority == PageFetchPriority.DISTANT_FORWARD ||
            priority == PageFetchPriority.ADJACENT_FORWARD
        ) {
            return executePageHedged(pageId, pageUrl, validation, priority)
        }
        return try {
            withTimeout(pageHeaderTimeoutMillis(priority)) {
                executePage(pageId, pageUrl, validation, priority)
            }
        } catch (timeout: TimeoutCancellationException) {
            transport.retireIdleConnections()
            executePageOnFreshRoute(pageId, pageUrl, validation, priority)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            transport.retireIdleConnections()
            executePageOnFreshRoute(pageId, pageUrl, validation, priority)
        }
    }

    private suspend fun executePageHedged(
        pageId: PageId,
        pageUrl: String,
        validation: PageValidation?,
        priority: PageFetchPriority,
    ): SourceResponse {
        val focusRouteRace = priority == PageFetchPriority.FOCUS &&
            (transport.routeParallelism() >= WFWF_HEDGED_REQUEST_COUNT ||
                transport.supportsProtocolSelection())
        val (hedgeDelay, alternateDelay) = when {
            focusRouteRace -> 0L to if (
                transport.routeParallelism() >= WFWF_HEDGED_REQUEST_COUNT
            ) 0L else FOCUS_PAGE_ALTERNATE_DELAY_MILLIS
            priority == PageFetchPriority.VISIBLE ->
                VISIBLE_PAGE_HEDGE_DELAY_MILLIS to VISIBLE_PAGE_ALTERNATE_DELAY_MILLIS
            priority == PageFetchPriority.IMMINENT_FORWARD ||
                priority == PageFetchPriority.FORWARD ||
                priority == PageFetchPriority.DISTANT_FORWARD ||
                priority == PageFetchPriority.ADJACENT_FORWARD ->
                FORWARD_PAGE_HEDGE_DELAY_MILLIS to FORWARD_PAGE_ALTERNATE_DELAY_MILLIS
            else -> PAGE_HEDGE_DELAY_MILLIS to PAGE_ALTERNATE_DELAY_MILLIS
        }
        return executeWfwfHedged(
            timeoutMillis = pageHeaderTimeoutMillis(priority),
            hedgeDelayMillis = hedgeDelay,
            alternateDelayMillis = alternateDelay,
            primaryRequest = { executePage(pageId, pageUrl, validation, priority) },
            recoveryRequest = { executePageOnFreshRoute(pageId, pageUrl, validation, priority) },
            alternateRequest = { executePageOnAlternateRoute(pageId, pageUrl, validation, priority) },
        )
    }

    private suspend fun executePageOnFreshRoute(
        pageId: PageId,
        pageUrl: String,
        validation: PageValidation?,
        priority: PageFetchPriority,
    ): SourceResponse {
        val headers = requestHeaders(referer = origin.resolve(viewPathFor(pageId.episodeId))).toMutableMap()
        validation?.entityTag?.let { headers["If-None-Match"] = it }
        validation?.lastModified?.let { headers["If-Modified-Since"] = it }
        return transport.executeOnFreshRoute(
            SourceRequest(pageUrl, headers = headers, priority = priority),
        )
    }

    private suspend fun executePageOnAlternateRoute(
        pageId: PageId,
        pageUrl: String,
        validation: PageValidation?,
        priority: PageFetchPriority,
    ): SourceResponse {
        val headers = requestHeaders(referer = origin.resolve(viewPathFor(pageId.episodeId))).toMutableMap()
        validation?.entityTag?.let { headers["If-None-Match"] = it }
        validation?.lastModified?.let { headers["If-Modified-Since"] = it }
        return transport.executeOnAlternateRoute(
            SourceRequest(pageUrl, headers = headers, priority = priority),
        )
    }

    private fun pageHeaderTimeoutMillis(priority: PageFetchPriority): Long = when (priority) {
        PageFetchPriority.FOCUS -> VISIBLE_HEADER_TIMEOUT_MILLIS
        PageFetchPriority.VISIBLE -> VISIBLE_HEADER_TIMEOUT_MILLIS
        PageFetchPriority.IMMINENT_FORWARD -> FORWARD_HEADER_TIMEOUT_MILLIS
        PageFetchPriority.FORWARD -> FORWARD_HEADER_TIMEOUT_MILLIS
        PageFetchPriority.DISTANT_FORWARD -> FORWARD_HEADER_TIMEOUT_MILLIS
        PageFetchPriority.NORMAL -> NORMAL_HEADER_TIMEOUT_MILLIS
        PageFetchPriority.ADJACENT_FORWARD -> NORMAL_HEADER_TIMEOUT_MILLIS
        PageFetchPriority.BACKGROUND -> BACKGROUND_HEADER_TIMEOUT_MILLIS
    }
    private suspend fun fetchCatalog(seriesId: SeriesId): List<SourceEpisode> {
        val key = WfwfSeriesKey.decode(seriesId)
        val first = document(listPath(key))
        val pageNumbers = parser.catalogPageNumbers(first, key).filter { it != 1 }
        if (pageNumbers.isEmpty()) return parser.episodes(first, seriesId, key)
        return coroutineScope {
            val remaining = pageNumbers.map { page ->
                async { parser.episodes(document(listPagePath(key, page)), seriesId, key) }
            }
            parser.mergeEpisodePages(
                listOf(parser.episodes(first, seriesId, key)) + remaining.map { it.await() },
            )
        }
    }

    private suspend fun fetchComicCatalogPage(page: Int): WfwfComicCatalogPage {
        val query = CatalogQuery(SeriesKind.COMIC, CatalogOrder.LATEST)
        val firstPagePath = WfwfCatalogPagination.path(query, page = 1)
        val catalogDocument = document(WfwfCatalogPagination.path(query, page))
        val items = parser.search(catalogDocument, ::seriesId).filter { item ->
            runCatching { WfwfSeriesKey.decode(item.id).kind }.getOrNull() == WfwfKind.COMIC
        }
        return WfwfComicCatalogPage(
            page = page,
            items = items,
            nextCursor = WfwfCatalogPagination.nextPageCursor(catalogDocument, firstPagePath, page),
            linkedPages = WfwfCatalogPagination.higherPages(catalogDocument, firstPagePath, page),
        )
    }

    private suspend fun document(path: String): Document {
        return origin.execute { requestOrigin -> requestDocument(path, requestOrigin) }
    }

    private suspend fun requestDocument(path: String, requestOrigin: String): Document {
        require(path.startsWith('/')) { "WFWF document path must be absolute" }
        val ticket = origin.beginDocument()
        val request = SourceRequest(
            url = requestOrigin + path,
            method = SourceHttpMethod.GET,
            headers = requestHeaders(),
        )
        val parallelRoutes = transport.routeParallelism() >= WFWF_HEDGED_REQUEST_COUNT
        val response = executeWfwfHedged(
            timeoutMillis = DOCUMENT_HEADER_TIMEOUT_MILLIS,
            hedgeDelayMillis = if (parallelRoutes) 0L else DOCUMENT_HEDGE_DELAY_MILLIS,
            alternateDelayMillis = if (parallelRoutes) 0L else DOCUMENT_ALTERNATE_DELAY_MILLIS,
            primaryRequest = { transport.execute(request) },
            recoveryRequest = { transport.executeOnFreshRoute(request) },
            alternateRequest = { transport.executeOnAlternateRoute(request) },
        )
        if (response.statusCode !in 200..299) {
            response.close()
            throw IOException("WFWF document request failed with ${response.statusCode}")
        }
        val finalUrl = response.finalUrl
        val bytes = response.readBytes(MAX_DOCUMENT_BYTES)
        val requested = URI(requestOrigin + path)
        val received = URI(finalUrl)
        require(requested.path == received.path &&
            requested.rawQuery?.split('&')?.sorted() == received.rawQuery?.split('&')?.sorted()
        ) {
            "WFWF document identity changed"
        }
        val document = Jsoup.parse(ByteArrayInputStream(bytes), null, finalUrl)
        if (requested.path == "/view" || requested.path == "/cv") {
            require(parser.pageImages(document).isNotEmpty()) { "WFWF document contains no episode pages" }
        }
        origin.observe(finalUrl, ticket)
        return document
    }

    private fun adjacentFrom(catalog: List<SourceEpisode>, episodeId: EpisodeId): AdjacentEpisodes {
        val index = catalog.indexOfFirst { it.id == episodeId }
        if (index < 0) return AdjacentEpisodes(null, null)
        return AdjacentEpisodes(
            previous = catalog.getOrNull(index + 1)?.id,
            next = catalog.getOrNull(index - 1)?.id,
        )
    }

    private fun seriesId(key: WfwfSeriesKey): SeriesId = SeriesId(id, key.encode())

    private fun listPath(key: WfwfSeriesKey): String = when (key.kind) {
        WfwfKind.COMIC -> "/cl?toon=${key.titleId}"
        WfwfKind.WEBTOON -> "/list?toon=${key.titleId}"
    }

    private fun listPagePath(key: WfwfSeriesKey, page: Int): String {
        require(page > 1) { "The first catalog page uses the canonical list URL" }
        return "${listPath(key)}&s=n&pg=$page"
    }

    private fun viewPath(key: WfwfSeriesKey, episodeKey: String): String = when (key.kind) {
        WfwfKind.COMIC -> "/cv?toon=${key.titleId}&num=$episodeKey"
        WfwfKind.WEBTOON -> "/view?toon=${key.titleId}&num=$episodeKey"
    }

    private fun viewPathFor(episodeId: EpisodeId): String =
        viewPath(WfwfSeriesKey.decode(episodeId.seriesId), episodeId.remoteKey)

    private fun requestHeaders(referer: String? = null): Map<String, String> = buildMap {
        put("User-Agent", config.userAgent)
        put("Accept", "text/html,application/xhtml+xml,image/avif,image/webp,image/*,*/*;q=0.8")
        referer?.let { put("Referer", it) }
    }

}

private val WFWF_WEBTOON_GENRES = listOf(
    SourceGenre("t2:1", "일반"),
    SourceGenre("t2:2", "BL"),
    SourceGenre("t2:3", "성인"),
) + listOf(
    "드라마", "판타지", "액션", "로맨스", "일상", "개그", "미스터리", "순정", "스포츠",
    "스릴러", "무협", "학원", "공포", "스토리",
).map { SourceGenre("t3:$it", it) }
private val WFWF_COMIC_GENRES = listOf(
    "액션" to "액션", "판타지" to "판타지", "로맨스" to "로맨스", "드라마" to "드라마",
    "이세계" to "이세계", "전생" to "전생", "무협" to "무협", "일상" to "일상",
    "순정" to "순정", "러브코미디" to "러브코미디", "개그" to "개그", "학원" to "학원",
    "스포츠" to "스포츠", "미스터리" to "미스터리", "추리" to "추리", "스릴러" to "스릴러",
    "공포" to "공포", "호러" to "호러", "도박" to "도박", "역사" to "역사", "시대" to "시대",
    "게임" to "게임", "sf" to "SF", "요리" to "요리", "먹방" to "먹방", "음악" to "음악",
    "라노벨" to "라노벨", "애니화" to "애니화", "bl" to "BL", "백합" to "백합",
    "성인" to "성인", "붕탁" to "붕탁", "ts" to "TS", "여장" to "여장", "17" to "17",
).map { (wire, label) -> SourceGenre("t3:$wire", label) }

private const val MAX_DOCUMENT_BYTES = 16 * 1_024 * 1_024
private const val PAGE_HEDGE_DELAY_MILLIS = 750L
private const val PAGE_ALTERNATE_DELAY_MILLIS = 1_500L
private const val FOCUS_PAGE_ALTERNATE_DELAY_MILLIS = 750L
private const val VISIBLE_PAGE_HEDGE_DELAY_MILLIS = 1_750L
private const val VISIBLE_PAGE_ALTERNATE_DELAY_MILLIS = 3_000L
private const val FORWARD_PAGE_HEDGE_DELAY_MILLIS = 2_500L
private const val FORWARD_PAGE_ALTERNATE_DELAY_MILLIS = 3_500L
private const val DOCUMENT_HEDGE_DELAY_MILLIS = 1_000L
private const val DOCUMENT_ALTERNATE_DELAY_MILLIS = 2_000L
private const val DOCUMENT_HEADER_TIMEOUT_MILLIS = 6_000L
private const val VISIBLE_HEADER_TIMEOUT_MILLIS = 4_000L
private const val FORWARD_HEADER_TIMEOUT_MILLIS = 5_000L
private const val NORMAL_HEADER_TIMEOUT_MILLIS = 6_000L
private const val BACKGROUND_HEADER_TIMEOUT_MILLIS = 8_000L
private val EXPIRED_PAGE_STATUSES = setOf(401, 403, 404, 410)

private fun WfwfKind?.matches(kind: SeriesKind): Boolean =
    (this == WfwfKind.COMIC && kind == SeriesKind.COMIC) ||
        (this == WfwfKind.WEBTOON && kind == SeriesKind.WEBTOON)
