package ml.melun.mangaview.source.goodtoon

import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.PageSpec
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.source.AdjacentEpisodes
import ml.melun.mangaview.source.CatalogQuery
import ml.melun.mangaview.source.ContentSource
import ml.melun.mangaview.source.OpenedPage
import ml.melun.mangaview.source.PageFetchPriority
import ml.melun.mangaview.source.PageValidation
import ml.melun.mangaview.source.PreparationIntent
import ml.melun.mangaview.source.SeriesKind
import ml.melun.mangaview.source.SeriesStatus
import ml.melun.mangaview.source.SourceEpisode
import ml.melun.mangaview.source.SourceGenre
import ml.melun.mangaview.source.SourceHttpMethod
import ml.melun.mangaview.source.SourcePage
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.SourceResponse
import ml.melun.mangaview.source.SourceSearchQuery
import ml.melun.mangaview.source.SourceSeries
import ml.melun.mangaview.source.SourceSeriesDetails
import ml.melun.mangaview.source.SourceTransport
import ml.melun.mangaview.source.readBytes
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

data class GoodtoonConfig(
    val initialOrigin: String,
    val userAgent: String,
    val manifestCacheEpisodes: Int = 12,
) {
    init {
        require(manifestCacheEpisodes > 0) { "GoodToon manifest cache capacity must be positive" }
    }
}

/** Which document shape a request must produce; the provider serves soft 404 pages. */
internal enum class GoodtoonDocumentKind { CATALOG, SEARCH, SERIES, CHAPTER, CHAPTER_LIST }

class GoodtoonContentSource(
    private val config: GoodtoonConfig,
    private val transport: SourceTransport,
    preparationScope: CoroutineScope? = null,
    private val parser: GoodtoonHtmlParser = GoodtoonHtmlParser(),
    originProbeObserver: (String) -> Unit = {},
    originResolver: GoodtoonOriginResolver =
        GoodtoonOriginResolver(transport, config.userAgent, onProbe = originProbeObserver),
    onOriginResolved: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) : ContentSource {
    override val id = goodtoonSourceId()
    private val origin = GoodtoonOriginCoordinator(config.initialOrigin, originResolver, preparationScope, onOriginResolved)
    private val catalogStore = GoodtoonCatalogStore(fetchProgressively = ::fetchCatalog, fetch = { fetchCatalog(it) })
    private val manifestStore = GoodtoonManifestStore(config.manifestCacheEpisodes, ::fetchManifest)
    private val searchService = GoodtoonSearchService(
        fetch = { path -> document(GoodtoonDocumentKind.SEARCH, path) },
        parse = { document -> parser.series(document, ::seriesId) },
    )

    /** All orders share one provider route, so the home fan-out must not fetch it three times. */
    private val catalogLock = Mutex()
    private var recentCatalog: CatalogSnapshot? = null

    /** Resolves and warms only the reusable provider origin; it never fetches user content. */
    fun warm() {
        origin.start()
    }

    override suspend fun search(query: String, cursor: String?): SourcePage<SourceSeries> =
        search(SourceSearchQuery(query, cursor = cursor))

    override suspend fun search(query: SourceSearchQuery): SourcePage<SourceSeries> = searchService.search(query)

    override suspend fun catalog(query: CatalogQuery): SourcePage<SourceSeries> {
        val page = GoodtoonCatalogPagination.page(query.cursor)
        val path = GoodtoonCatalogPagination.path(query, page)
        val status = if (query.statusFilter == SeriesStatus.COMPLETED) {
            SeriesStatus.COMPLETED
        } else {
            SeriesStatus.ONGOING
        }
        return catalogLock.withLock {
            val remembered = recentCatalog
            if (remembered != null && remembered.path == path &&
                clock() - remembered.atMillis <= CATALOG_REUSE_MILLIS
            ) {
                return@withLock remembered.page
            }
            val document = document(GoodtoonDocumentKind.CATALOG, path)
            val items = parser.series(document, ::seriesId, status)
            val next = GoodtoonCatalogPagination.nextPageCursor(
                document,
                GoodtoonCatalogPagination.path(query, page = 1),
                page,
            )
            SourcePage(items, next).also { fresh ->
                recentCatalog = CatalogSnapshot(path, clock(), fresh)
            }
        }
    }

    override suspend fun genres(kind: SeriesKind): List<SourceGenre> = GOODTOON_GENRES

    override suspend fun episodes(seriesId: SeriesId, cursor: String?): SourcePage<SourceEpisode> {
        require(seriesId.sourceId == id) { "Series belongs to another source" }
        if (cursor != null) return SourcePage(emptyList())
        return SourcePage(catalogStore.load(seriesId, refresh = true))
    }

    override suspend fun episodeCatalog(
        seriesId: SeriesId,
        onPartial: suspend (List<SourceEpisode>) -> Unit,
    ): List<SourceEpisode> {
        require(seriesId.sourceId == id) { "Series belongs to another source" }
        return catalogStore.load(seriesId, refresh = true, onPartial = onPartial)
    }

    override suspend fun seriesDetails(seriesId: SeriesId): SourceSeriesDetails? {
        require(seriesId.sourceId == id) { "Series belongs to another source" }
        val key = GoodtoonSeriesKey.decode(seriesId)
        return parser.details(document(GoodtoonDocumentKind.SERIES, key.path()))
    }

    override suspend fun manifest(episodeId: EpisodeId): EpisodeManifest {
        require(episodeId.seriesId.sourceId == id) { "Episode belongs to another source" }
        return manifestStore.load(episodeId).payload.manifest
    }

    override suspend fun adjacent(episodeId: EpisodeId): AdjacentEpisodes {
        require(episodeId.seriesId.sourceId == id) { "Episode belongs to another source" }
        return adjacentFrom(catalogStore.load(episodeId.seriesId, refresh = false), episodeId)
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
        if (response.statusCode in 200..299) return response.openedGoodtoonPage()
        val statusCode = response.statusCode
        response.close()
        if (statusCode !in EXPIRED_PAGE_STATUSES) throw goodtoonPageFailure(statusCode)
        val refreshed = manifestStore.refreshIfCurrent(pageId.episodeId, registered.revision)
        val refreshedUrl = refreshed.payload.pageUrls[pageId]
            ?: throw IllegalStateException("GoodToon refreshed manifest no longer contains the page")
        if (refreshedUrl == registered.url) throw refreshedGoodtoonPageFailure(statusCode)
        val retried = executePageWithRouteRecovery(pageId, refreshedUrl, validation, priority)
        if (retried.statusCode in 200..299) return retried.openedGoodtoonPage()
        val retryStatus = retried.statusCode
        retried.close()
        throw refreshedGoodtoonPageFailure(retryStatus)
    }

    override suspend fun openArtwork(series: SourceSeries): OpenedPage? {
        require(series.id.sourceId == id) { "Series belongs to another source" }
        val value = series.thumbnailKey?.takeIf(String::isNotBlank) ?: return null
        val url = runCatching { URI("${origin.current()}/").resolve(value).toString() }.getOrNull() ?: return null
        val response = transport.execute(SourceRequest(url, headers = requestHeaders()))
        if (response.statusCode !in 200..299) {
            response.close()
            return null
        }
        return response.openedGoodtoonPage()
    }

    override suspend fun seriesUrl(seriesId: SeriesId): String? {
        require(seriesId.sourceId == id) { "Series belongs to another source" }
        return origin.resolve(GoodtoonSeriesKey.decode(seriesId).path())
    }

    private suspend fun resolvePage(pageId: PageId): GoodtoonPageLookup.Found =
        when (val lookup = manifestStore.page(pageId)) {
            is GoodtoonPageLookup.Found -> lookup
            GoodtoonPageLookup.MissingEpisode -> manifestStore.load(pageId.episodeId).registered(pageId)
            is GoodtoonPageLookup.MissingPage ->
                manifestStore.refreshIfCurrent(pageId.episodeId, lookup.revision).registered(pageId)
        }

    private fun GoodtoonManifestEntry.registered(pageId: PageId): GoodtoonPageLookup.Found {
        val url = payload.pageUrls[pageId]
            ?: throw IllegalStateException("GoodToon manifest does not contain the requested page")
        return GoodtoonPageLookup.Found(url, revision)
    }

    private suspend fun executePage(
        pageId: PageId,
        pageUrl: String,
        validation: PageValidation?,
        priority: PageFetchPriority,
    ): SourceResponse = transport.execute(
        SourceRequest(pageUrl, headers = pageHeaders(pageId, validation), priority = priority),
    )

    private suspend fun executePageOnFreshRoute(
        pageId: PageId,
        pageUrl: String,
        validation: PageValidation?,
        priority: PageFetchPriority,
    ): SourceResponse = transport.executeOnFreshRoute(
        SourceRequest(pageUrl, headers = pageHeaders(pageId, validation), priority = priority),
    )

    private suspend fun executePageOnAlternateRoute(
        pageId: PageId,
        pageUrl: String,
        validation: PageValidation?,
        priority: PageFetchPriority,
    ): SourceResponse = transport.executeOnAlternateRoute(
        SourceRequest(pageUrl, headers = pageHeaders(pageId, validation), priority = priority),
    )

    private suspend fun pageHeaders(pageId: PageId, validation: PageValidation?): Map<String, String> {
        val key = GoodtoonSeriesKey.decode(pageId.episodeId.seriesId)
        val headers = requestHeaders(
            referer = origin.resolve(key.chapterPath(pageId.episodeId.remoteKey)),
        ).toMutableMap()
        validation?.entityTag?.let { headers["If-None-Match"] = it }
        validation?.lastModified?.let { headers["If-Modified-Since"] = it }
        return headers
    }

    private suspend fun executePageWithRouteRecovery(
        pageId: PageId,
        pageUrl: String,
        validation: PageValidation?,
        priority: PageFetchPriority,
    ): SourceResponse {
        val parallelRoutes = transport.routeParallelism() >= GOODTOON_HEDGED_REQUEST_COUNT
        val (hedgeDelay, alternateDelay) = when {
            priority == PageFetchPriority.FOCUS -> 0L to if (parallelRoutes) 0L else FOCUS_PAGE_ALTERNATE_DELAY_MILLIS
            priority == PageFetchPriority.VISIBLE ->
                if (parallelRoutes) 0L to 0L
                else VISIBLE_PAGE_HEDGE_DELAY_MILLIS to VISIBLE_PAGE_ALTERNATE_DELAY_MILLIS
            priority == PageFetchPriority.IMMINENT_FORWARD ||
                priority == PageFetchPriority.FORWARD ||
                priority == PageFetchPriority.DISTANT_FORWARD ||
                priority == PageFetchPriority.ADJACENT_FORWARD ->
                if (parallelRoutes) 0L to 0L
                else FORWARD_PAGE_HEDGE_DELAY_MILLIS to FORWARD_PAGE_ALTERNATE_DELAY_MILLIS
            else -> if (parallelRoutes) 0L to 0L else PAGE_HEDGE_DELAY_MILLIS to PAGE_ALTERNATE_DELAY_MILLIS
        }
        return executeGoodtoonHedged(
            timeoutMillis = pageHeaderTimeoutMillis(priority),
            hedgeDelayMillis = hedgeDelay,
            alternateDelayMillis = alternateDelay,
            primaryRequest = { executePage(pageId, pageUrl, validation, priority) },
            recoveryRequest = { executePageOnFreshRoute(pageId, pageUrl, validation, priority) },
            alternateRequest = { executePageOnAlternateRoute(pageId, pageUrl, validation, priority) },
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

    private suspend fun fetchCatalog(
        seriesId: SeriesId,
        onPartial: suspend (List<SourceEpisode>) -> Unit = {},
    ): List<SourceEpisode> {
        val key = GoodtoonSeriesKey.decode(seriesId)
        val accumulator = GoodtoonEpisodeCatalogAccumulator()
        var page = 1
        while (true) {
            val document = document(GoodtoonDocumentKind.CHAPTER_LIST, key.chaptersPath(page))
            val parsed = parser.chapters(document, seriesId, key)
            if (!accumulator.absorb(parsed)) return accumulator.episodes()
            onPartial(accumulator.episodes())
            page += 1
        }
    }

    private suspend fun fetchManifest(episodeId: EpisodeId): GoodtoonManifestPayload {
        val seriesKey = GoodtoonSeriesKey.decode(episodeId.seriesId)
        val episodeKey = GoodtoonEpisodeKey.decode(episodeId)
        val document = document(GoodtoonDocumentKind.CHAPTER, seriesKey.chapterPath(episodeKey.encode()))
        val finalUrl = runCatching { URI(document.location()) }.getOrNull()
        val images = finalUrl?.let { parser.pageImages(document, it) }.orEmpty()
        require(images.isNotEmpty()) { "GoodToon episode contains no page images" }
        val catalog = catalogStore.load(episodeId.seriesId, refresh = false)
        val adjacent = adjacentFrom(catalog, episodeId)
        val title = catalog.firstOrNull { it.id == episodeId }?.title ?: episodeId.remoteKey
        val pageIds = images.mapIndexed { index, _ -> PageId.at(episodeId, index) }
        return GoodtoonManifestPayload(
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

    private suspend fun document(kind: GoodtoonDocumentKind, path: String): Document =
        origin.execute { requestOrigin -> requestDocument(kind, path, requestOrigin) }

    private suspend fun requestDocument(
        kind: GoodtoonDocumentKind,
        path: String,
        requestOrigin: String,
    ): Document {
        require(path.startsWith('/')) { "GoodToon document path must be absolute" }
        val ticket = origin.beginDocument()
        val request = SourceRequest(
            url = requestOrigin + path,
            method = SourceHttpMethod.GET,
            headers = requestHeaders(),
        )
        val parallelRoutes = transport.routeParallelism() >= GOODTOON_HEDGED_REQUEST_COUNT
        val response = executeGoodtoonHedged(
            timeoutMillis = DOCUMENT_HEADER_TIMEOUT_MILLIS,
            hedgeDelayMillis = if (parallelRoutes) 0L else DOCUMENT_HEDGE_DELAY_MILLIS,
            alternateDelayMillis = if (parallelRoutes) 0L else DOCUMENT_ALTERNATE_DELAY_MILLIS,
            primaryRequest = { transport.execute(request) },
            recoveryRequest = { transport.executeOnFreshRoute(request) },
            alternateRequest = { transport.executeOnAlternateRoute(request) },
        )
        if (response.statusCode !in 200..299) {
            response.close()
            throw IOException("GoodToon document request failed with ${response.statusCode}")
        }
        val finalUrl = response.finalUrl
        val bytes = try { response.readBytes(MAX_DOCUMENT_BYTES) } finally { response.close() }
        val requested = URI(requestOrigin + path)
        require(requested.path == URI(finalUrl).path) { "GoodToon document identity changed" }
        val document = Jsoup.parse(ByteArrayInputStream(bytes), null, finalUrl)
        if (!documentIsAvailable(kind, document)) {
            throw IOException("GoodToon document is unavailable")
        }
        origin.observe(finalUrl, ticket)
        return document
    }

    private fun documentIsAvailable(kind: GoodtoonDocumentKind, document: Document): Boolean = when (kind) {
        GoodtoonDocumentKind.CATALOG -> document.select("a.card, .pagination").isNotEmpty()
        GoodtoonDocumentKind.SEARCH -> document.selectFirst(".card-grid, a.card[href]") != null
        GoodtoonDocumentKind.SERIES -> document.selectFirst(".summary-title") != null
        GoodtoonDocumentKind.CHAPTER -> document.select("img.wp-manga-chapter-img").isNotEmpty()
        GoodtoonDocumentKind.CHAPTER_LIST -> true
    }

    private fun adjacentFrom(catalog: List<SourceEpisode>, episodeId: EpisodeId): AdjacentEpisodes {
        val index = catalog.indexOfFirst { it.id == episodeId }
        if (index < 0) return AdjacentEpisodes(null, null)
        return AdjacentEpisodes(
            previous = catalog.getOrNull(index + 1)?.id,
            next = catalog.getOrNull(index - 1)?.id,
        )
    }

    private fun seriesId(key: GoodtoonSeriesKey): SeriesId = SeriesId(id, key.encode())

    private fun requestHeaders(referer: String? = null): Map<String, String> = buildMap {
        put("User-Agent", config.userAgent)
        put("Accept", "text/html,application/xhtml+xml,image/avif,image/webp,image/*,*/*;q=0.8")
        referer?.let { put("Referer", it) }
    }
}

private val GOODTOON_GENRES = listOf(
    "school" to "학원",
    "action" to "액션",
    "sci-fi" to "SF",
    "story" to "스토리",
    "fantasy" to "판타지",
    "bl" to "BL",
    "gag" to "개그",
    "romance-drama" to "연애",
    "drama" to "드라마",
    "romance" to "로맨스",
    "period" to "시대극",
    "sports" to "스포츠",
    "slice-of-life" to "일상",
    "mystery" to "추리",
    "horror" to "공포",
    "adult" to "성인",
    "omnibus" to "옴니버스",
    "episode" to "에피소드",
    "martial-arts" to "무협",
    "shounen" to "소년",
    "etc" to "기타",
    "novelpia" to "노벨피아",
    "married" to "유부녀",
    "hardcore" to "하드코어",
    "training" to "조교",
    "high-level" to "고수위",
    "abuse" to "능욕",
    "harem" to "하렘",
    "forced" to "강제",
    "female-popular" to "여성인기",
    "male-popular" to "남성인기",
    "threesome" to "3P",
    "adult-warning" to "후방주의",
    "yuri" to "백합",
).map { (wire, label) -> SourceGenre("genre:$wire", label) }

private const val MAX_DOCUMENT_BYTES = 16 * 1_024 * 1_024
private const val CATALOG_REUSE_MILLIS = 30_000L
private const val PAGE_HEDGE_DELAY_MILLIS = 750L
private const val PAGE_ALTERNATE_DELAY_MILLIS = 1_500L
private const val FOCUS_PAGE_ALTERNATE_DELAY_MILLIS = 750L
private const val VISIBLE_PAGE_HEDGE_DELAY_MILLIS = 1_750L
private const val VISIBLE_PAGE_ALTERNATE_DELAY_MILLIS = 3_000L
private const val FORWARD_PAGE_HEDGE_DELAY_MILLIS = 2_500L
private const val FORWARD_PAGE_ALTERNATE_DELAY_MILLIS = 3_500L
private const val DOCUMENT_HEDGE_DELAY_MILLIS = 1_000L
private const val DOCUMENT_ALTERNATE_DELAY_MILLIS = 2_000L
private const val DOCUMENT_HEADER_TIMEOUT_MILLIS = 8_000L
private const val VISIBLE_HEADER_TIMEOUT_MILLIS = 5_000L
private const val FORWARD_HEADER_TIMEOUT_MILLIS = 6_000L
private const val NORMAL_HEADER_TIMEOUT_MILLIS = 8_000L
private const val BACKGROUND_HEADER_TIMEOUT_MILLIS = 10_000L
private val EXPIRED_PAGE_STATUSES = setOf(401, 403, 404, 410)

private data class CatalogSnapshot(
    val path: String,
    val atMillis: Long,
    val page: SourcePage<SourceSeries>,
)
