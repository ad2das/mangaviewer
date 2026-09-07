package ml.melun.mangaview.source.ntk

import java.io.IOException
import java.net.URI
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.PageSpec
import ml.melun.mangaview.source.OpenedPage
import ml.melun.mangaview.source.PageByteStream
import ml.melun.mangaview.source.PageValidation
import ml.melun.mangaview.source.PageFetchPriority
import ml.melun.mangaview.source.PreparationIntent
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.SourceTransport

internal class NtkPageService(
    private val transport: SourceTransport,
    private val documents: NtkDocumentClient,
    private val gateway: NtkAccessGateway,
    private val parser: NtkDocumentParser,
    private val replicas: NtkReplicaSelector = NtkReplicaSelector(),
    private val preparedEpisodes: NtkPreparedEpisodeStore = NtkPreparedEpisodeStore(),
) {
    private val manifestLanes = Semaphore(gateway.parallelPreparationCapacity.coerceAtLeast(1))
    private val adjacentManifestLanes = Semaphore(
        (gateway.parallelPreparationCapacity - INITIAL_MANIFEST_RESERVE).coerceAtLeast(1),
    )
    private val preparationIntents = ConcurrentHashMap<EpisodeId, PreparationIntent>()
    @Volatile private var latestManifestPath: String? = null
    private val replicaRacer = NtkReplicaRacer(
        transport,
        replicas,
        // Candidate validation reaches the first progressive-preview checkpoint. Selecting a
        // route after only the image signature allowed a fast header followed by a stalled body
        // to own a page while the user scrolled into it with no locally decodable bytes.
        hedgeDelayMillis = 150L,
        maxConcurrentAttempts = 24,
        visibleReservedAttempts = 6,
        preferQuic = true,
    )

    suspend fun prepare(episodeId: EpisodeId, intent: PreparationIntent) {
        if (preparedEpisodes.contains(episodeId)) return
        preparationIntents.merge(episodeId, intent, ::strongerIntent)
        val origin = documents.currentOrigin()
        if (intent != PreparationIntent.INITIAL_VIEW && gateway.parallelPreparationCapacity == 1) {
            val from = latestManifestPath
            if (from != null && from != episodeId.remoteKey) {
                gateway.preflightAdjacentChallenge(origin, from, episodeId.remoteKey)
                return
            }
        }
        // Preparing an adjacent episode is the command to start its ACK state machine, not just
        // to cache a native challenge. The browser and native challenge can run concurrently;
        // resolve() reuses this exact assigned lane and does not create a second request.
        gateway.prepare(origin, episodeId.remoteKey, intent)
    }

    suspend fun resolve(
        episodeId: EpisodeId,
        onAdjacencyFallbackRequired: () -> Unit = {},
    ): NtkPreparedEpisode = preparedEpisodes.resolve(episodeId) {
        load(episodeId, onAdjacencyFallbackRequired)
    }

    private suspend fun load(
        episodeId: EpisodeId,
        onAdjacencyFallbackRequired: () -> Unit,
    ): NtkPreparedEpisode {
        val path = episodeId.remoteKey
        // ContentSource callers are allowed to request a manifest without a separate prepare call.
        // Start the browser's authorized ACK flight before the duplicate HTML transport request so
        // browser startup, challenge work and document parsing overlap on that cold path too.
        val origin = documents.currentOrigin()
        val intent = preparationIntents.remove(episodeId) ?: PreparationIntent.INITIAL_VIEW
        return withManifestLane(intent) {
            loadOnManifestLane(
                episodeId,
                path,
                origin,
                intent,
                onAdjacencyFallbackRequired,
            )
        }
    }

    private suspend fun loadOnManifestLane(
        episodeId: EpisodeId,
        path: String,
        origin: String,
        intent: PreparationIntent,
        onAdjacencyFallbackRequired: () -> Unit,
    ): NtkPreparedEpisode = coroutineScope {
        gateway.prepare(origin, path, intent)
        val traceContext = (gateway as? NtkWebViewAccessGateway)?.ntkTraceContext(origin, path)
            ?: NtkTraceContext(
                sourceEpisodeId = episodeId.remoteKey,
                episodePath = path,
            )
        var documentOrigin = origin
        try {
            val document = documents.episodeDocument(path, documentPriority(intent), traceContext)
            validateNtkDocumentIdentity(document, path)
            documentOrigin = document.origin
            val parsed = parseDocument(document, traceContext)
            require(parsed.descriptor != null || parsed.directPages.isNotEmpty()) {
                "NTK document contains no episode manifest"
            }
            documents.acceptDocument(document)
            NtkTrace.emit(
                "native-document-accepted",
                traceContext.copy(
                    providerEpisodeId = parsed.descriptor?.episodeId ?: traceContext.providerEpisodeId,
                ),
                role = "main",
                outcome = "success",
            )
            val viewer = parsed.viewer
            if (viewer?.previousKnown != true || viewer.nextKnown != true) {
                // The native document is available before the protected image API finishes its
                // minimum-seen ACK. Let the source overlap only the metadata that this document
                // proved to be missing.
                onAdjacencyFallbackRequired()
            }
            val nextEpisodeId = viewer?.nextEpisodePath?.let { EpisodeId(episodeId.seriesId, it) }
            latestManifestPath = path
            parsed.descriptor?.let { descriptor ->
                gateway.documentAvailable(document, descriptor)
            }
            val resolved = resolveRequests(document, parsed)
            // The cold visible race can use at most three origins. Warming every advertised
            // replica would create and immediately evict more engines than the transport keeps,
            // competing with the first real image and leaving its chosen engine cold again.
            val warmCandidates = replicas.order(resolved.first().candidates)
                .take(NtkReplicaRacePolicy.IMMEDIATE_WINDOW)
            warmCandidates.forEach { candidate ->
                transport.warmConnections(listOf(candidate), preferQuic = true)
            }
            // Keep one H2 fallback ready for a route whose QUIC handshake does not progress.
            transport.warmConnections(warmCandidates.take(1), preferQuic = false)
            // The ACK browser must remain alive until an image header and signature prove that the
            // protected replica is usable. Releasing the pool lease does not quiesce that browser.
            val ids = resolved.indices.map { PageId.at(episodeId, it) }
            NtkPreparedEpisode(
                pages = ids.mapIndexed { index, id -> PageSpec(id, index) },
                requests = ids.zip(resolved).toMap(),
                title = viewer?.title,
                previousEpisodeId = viewer?.previousEpisodePath?.let {
                    EpisodeId(episodeId.seriesId, it)
                },
                nextEpisodeId = nextEpisodeId,
                previousKnown = viewer?.previousKnown == true,
                nextKnown = viewer?.nextKnown == true,
            )
        } finally {
            gateway.manifestResolutionFinished(origin, path)
            if (documentOrigin != origin) gateway.manifestResolutionFinished(documentOrigin, path)
        }
    }

    private suspend fun <T> withManifestLane(
        intent: PreparationIntent,
        block: suspend () -> T,
    ): T = if (intent == PreparationIntent.INITIAL_VIEW) {
        manifestLanes.withPermit { block() }
    } else {
        adjacentManifestLanes.withPermit {
            manifestLanes.withPermit { block() }
        }
    }

    private fun parseDocument(
        document: NtkEpisodeDocument,
        traceContext: NtkTraceContext,
    ): NtkManifestDocument {
        val started = System.nanoTime()
        NtkTrace.emit("native-document-parse-start", traceContext, role = "main")
        return parser.manifest(document).also { parsed ->
            NtkTrace.emit(
                "native-document-parse-end",
                traceContext.copy(
                    providerEpisodeId = parsed.descriptor?.episodeId ?: traceContext.providerEpisodeId,
                ),
                role = "main",
                outcome = "success",
            )
            runCatching { android.util.Log.d(
                "NtkNative", "phase=document-parsed elapsedMs=${(System.nanoTime() - started) / 1_000_000L}",
            ) }
        }
    }

    private fun strongerIntent(
        existing: PreparationIntent,
        incoming: PreparationIntent,
    ): PreparationIntent = if (
        existing == PreparationIntent.INITIAL_VIEW || incoming == PreparationIntent.INITIAL_VIEW
    ) {
        PreparationIntent.INITIAL_VIEW
    } else {
        incoming
    }

    private fun documentPriority(intent: PreparationIntent): PageFetchPriority = when (intent) {
        PreparationIntent.INITIAL_VIEW -> PageFetchPriority.VISIBLE
        PreparationIntent.ADJACENT_FORWARD,
        PreparationIntent.ADJACENT_REVERSE,
        -> PageFetchPriority.BACKGROUND
    }

    suspend fun open(
        pageId: PageId,
        validation: PageValidation?,
        priority: PageFetchPriority = PageFetchPriority.NORMAL,
    ): OpenedPage {
        val request = preparedEpisodes.request(pageId) ?: run {
            resolve(pageId.episodeId)
            preparedEpisodes.request(pageId)
        }
            ?: throw IllegalStateException("NTK page was not registered by a manifest")
        val episodeOrigin = requireNotNull(request.episodeOrigin) { "NTK page has no bound document origin" }
        val referer = episodeOrigin +
            pageId.episodeId.remoteKey
        val headers = documents.requestHeaders(referer).toMutableMap()
        headers.putAll(request.headers)
        headers["Accept"] = "image/avif,image/webp,image/apng,image/*,*/*;q=0.8"
        headers["Accept-Language"] = "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7"
        headers["Sec-Fetch-Dest"] = "image"
        headers["Sec-Fetch-Mode"] = "no-cors"
        headers["Sec-Fetch-Site"] = ntkFetchSite(referer, request.url)
        validation?.entityTag?.let { headers["If-None-Match"] = it }
        validation?.lastModified?.let { headers["If-Modified-Since"] = it }
        val traceContext = ((gateway as? NtkWebViewAccessGateway)?.ntkTraceContext(
            episodeOrigin,
            pageId.episodeId.remoteKey,
        ) ?: NtkTraceContext(
            sourceEpisodeId = pageId.episodeId.remoteKey,
            episodePath = pageId.episodeId.remoteKey,
        )).copy(page = pageId.remoteKey)
        NtkTrace.emit("protected-image-open-requested", traceContext, role = "main")
        return openCandidate(pageId, request, headers, priority, traceContext)
    }

    private suspend fun resolveRequests(
        document: NtkEpisodeDocument,
        parsed: NtkManifestDocument,
    ): List<NtkPageRequest> {
        val expected = parsed.descriptor?.expectedPageCount
        val directComplete = parsed.directPages.isNotEmpty() &&
            (parsed.descriptor == null || parsed.directPagesOwnedByViewer) &&
            (expected == null || parsed.directPages.size == expected)
        val resolved = if (directComplete) parsed.directPages else {
            val descriptor = requireNotNull(parsed.descriptor) {
                "NTK protected manifest descriptor is missing"
            }
            gateway.resolve(document, descriptor)
        }
        require(resolved.isNotEmpty()) { "NTK manifest contains no pages" }
        require(expected == null || resolved.size == expected) {
            "NTK manifest page count is incomplete"
        }
        val base = URI(document.origin + document.path)
        return resolved.map { request ->
            request.copy(
                url = resolveNtkPageUrl(base, request.url),
                alternateUrls = request.alternateUrls.map { resolveNtkPageUrl(base, it) },
                episodeOrigin = document.origin,
            )
        }
    }

    private suspend fun openCandidate(
        pageId: PageId,
        request: NtkPageRequest,
        headers: Map<String, String>,
        priority: PageFetchPriority,
        traceContext: NtkTraceContext,
    ): OpenedPage = coroutineScope {
        val origin = requireNotNull(request.episodeOrigin) { "NTK page has no bound document origin" }
        val winner = raceAuthorizationAndPage(origin, pageId, request, headers, priority, traceContext)
        val elapsed = elapsedMillis(winner.startedAtNanos)
        replicas.accepted(winner.lease, elapsed)
        gateway.pageAccessEstablished(origin, pageId.episodeId.remoteKey)
        winner.opened.copy(
            stream = ReplicaTrackedPageByteStream(
                upstream = winner.opened.stream,
                expectedLength = winner.opened.contentLength,
                initialPriority = priority,
                succeeded = {
                    replicaRacer.completed(winner)
                    replicas.completed(winner.lease, elapsedMillis(winner.startedAtNanos))
                },
                failed = {
                    replicaRacer.failed(winner)
                    replicas.failedAndReleased(winner.lease)
                },
                abandoned = {
                    replicaRacer.abandoned(winner)
                    replicas.abandoned(winner.lease)
                },
            ),
        )
    }

    private suspend fun raceAuthorizationAndPage(
        origin: String,
        pageId: PageId,
        request: NtkPageRequest,
        headers: Map<String, String>,
        priority: PageFetchPriority,
        traceContext: NtkTraceContext,
    ): NtkReplicaWinner = coroutineScope {
        if (gateway.isAuthorizationReady(origin, pageId.episodeId.remoteKey)) {
            return@coroutineScope attemptPage(request, headers, pageId, priority, traceContext).getOrThrow()
        }
        raceNtkAuthorizationAndPage(
            awaitAuthorization = { gateway.awaitAuthorization(origin, pageId.episodeId.remoteKey) },
            attempt = { attemptPage(request, headers, pageId, priority, traceContext) },
            release = { winner ->
                winner.opened.close()
                replicaRacer.abandoned(winner)
                replicas.abandoned(winner.lease)
            },
        )
    }

    private suspend fun attemptPage(
        request: NtkPageRequest,
        headers: Map<String, String>,
        pageId: PageId,
        priority: PageFetchPriority,
        traceContext: NtkTraceContext,
    ): Result<NtkReplicaWinner> = try {
        Result.success(replicaRacer.open(
            request.candidates,
            headers,
            pageId.remoteKey,
            priority,
            { response -> validateImageResponse(response, routeProbeBytes(priority)) },
            traceContext,
        ))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        Result.failure(failure)
    }

    private suspend fun validateImageResponse(
        response: ml.melun.mangaview.source.SourceResponse,
        probeBytes: Int,
    ): OpenedPage {
        // The replica race owns the response until this function successfully returns.
        val prefix = ByteArray(probeBytes)
        var count = 0
        while (count < prefix.size) {
            val read = response.body.readAtMost(prefix, count, prefix.size - count)
            if (read < 0) break
            require(read > 0) { "NTK page stream returned zero bytes" }
            count += read
        }
        NtkImageSignature.detect(prefix, count)
            ?: throw IOException("HTTP payload is not a supported image")
        return OpenedPage(
            stream = PrefixedPageByteStream(prefix, count, response.body),
            contentLength = response.contentLength,
            contentType = response.contentType,
            entityTag = response.header("ETag"),
            lastModified = response.header("Last-Modified"),
        )
    }

    private fun elapsedMillis(startedNanos: Long): Long =
        ((System.nanoTime() - startedNanos).coerceAtLeast(0L)) / 1_000_000L

    private companion object {
        const val INITIAL_MANIFEST_RESERVE = 1
    }

}

/**
 * Reads only the fixed prefix needed to reject a non-image route. The verified bytes are replayed
 * to the sole page owner, while full length and digest validation remain in the cache writer.
 */
internal fun routeProbeBytes(priority: PageFetchPriority): Int = when (priority) {
    PageFetchPriority.FOCUS,
        PageFetchPriority.VISIBLE,
        -> INTERACTIVE_ROUTE_PROBE_BYTES
    PageFetchPriority.IMMINENT_FORWARD,
        PageFetchPriority.FORWARD,
        PageFetchPriority.DISTANT_FORWARD,
        PageFetchPriority.ADJACENT_FORWARD,
        -> FORWARD_ROUTE_PROBE_BYTES
    else -> BACKGROUND_ROUTE_PROBE_BYTES
}

// One progressive chunk proves the route and immediately exposes useful full-quality rows.
// Full length and digest validation continue in the sole cache writer after handoff.
private const val INTERACTIVE_ROUTE_PROBE_BYTES = 128 * 1_024
private const val FORWARD_ROUTE_PROBE_BYTES = 32 * 1_024
private const val BACKGROUND_ROUTE_PROBE_BYTES = 16 * 1_024

internal object NtkImageSignature {
    fun detect(bytes: ByteArray, count: Int): String? = when {
        jpeg(bytes, count) -> "jpeg"
        png(bytes, count) -> "png"
        webp(bytes, count) -> "webp"
        else -> null
    }

    private fun jpeg(bytes: ByteArray, count: Int): Boolean = count >= 3 &&
        bytes[0].u() == 0xff && bytes[1].u() == 0xd8 && bytes[2].u() == 0xff

    private fun png(bytes: ByteArray, count: Int): Boolean = count >= PNG.size &&
        PNG.indices.all { bytes[it].u() == PNG[it] }

    private fun webp(bytes: ByteArray, count: Int): Boolean = count >= 12 &&
        ascii(bytes, 0, "RIFF") && ascii(bytes, 8, "WEBP")

    private fun ascii(bytes: ByteArray, offset: Int, expected: String): Boolean =
        expected.indices.all { bytes[offset + it].u() == expected[it].code }

    private fun Byte.u(): Int = toInt() and 0xff

    private val PNG = intArrayOf(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
}

internal class PrefixedPageByteStream(
    private val prefix: ByteArray,
    private val prefixCount: Int,
    private val upstream: PageByteStream,
) : PageByteStream {
    private var prefixOffset = 0

    override suspend fun readAtMost(destination: ByteArray, offset: Int, byteCount: Int): Int {
        require(offset >= 0 && byteCount >= 0 && offset + byteCount <= destination.size)
        if (byteCount == 0) return 0
        val remaining = prefixCount - prefixOffset
        if (remaining <= 0) return upstream.readAtMost(destination, offset, byteCount)
        val copied = minOf(remaining, byteCount)
        prefix.copyInto(destination, offset, prefixOffset, prefixOffset + copied)
        prefixOffset += copied
        // Return the already-validated bytes immediately. Waiting here to fill the caller's
        // larger buffer with a fresh network read delayed the first progressive publication by
        // another RTT even though useful image bytes were already in memory.
        return copied
    }

    override fun promote(priority: PageFetchPriority) = upstream.promote(priority)

    override fun close() = upstream.close()
}

internal class ReplicaTrackedPageByteStream(
    private val upstream: PageByteStream,
    private val expectedLength: Long?,
    initialPriority: PageFetchPriority = PageFetchPriority.BACKGROUND,
    private val succeeded: () -> Unit,
    private val failed: () -> Unit,
    private val abandoned: () -> Unit,
) : PageByteStream {
    private val terminal = AtomicBoolean(false)
    private val readProgressTimeoutMillis = AtomicLong(ntkReadProgressTimeoutMillis(initialPriority))
    private val received = AtomicLong(0L)

    override suspend fun readAtMost(destination: ByteArray, offset: Int, byteCount: Int): Int {
        val count = try {
            withTimeoutOrNull(readProgressTimeoutMillis.get()) {
                upstream.readAtMost(destination, offset, byteCount)
            } ?: throw SocketTimeoutException("NTK replica body made no progress for ${readProgressTimeoutMillis.get()}ms")
        } catch (timeout: SocketTimeoutException) {
            runCatching(upstream::close)
            finish(failed)
            throw timeout
        } catch (cancelled: CancellationException) {
            finish(abandoned)
            throw cancelled
        } catch (failure: Throwable) {
            finish(failed)
            throw failure
        }
        if (count > 0) {
            val total = received.updateAndGet { value -> Math.addExact(value, count.toLong()) }
            if (expectedLength != null && total > expectedLength) {
                finish(failed)
                throw IOException("NTK replica exceeded its declared body length")
            }
        } else if (count < 0) {
            if (expectedLength != null && received.get() != expectedLength) {
                finish(failed)
                throw IOException("NTK replica body length did not match its declaration")
            }
            finish(succeeded)
        }
        return count
    }

    override fun promote(priority: PageFetchPriority) {
        val promoted = ntkReadProgressTimeoutMillis(priority)
        readProgressTimeoutMillis.getAndUpdate { current -> minOf(current, promoted) }
        upstream.promote(priority)
    }

    override fun close() {
        try {
            upstream.close()
        } finally {
            finish(abandoned)
        }
    }

    private fun finish(action: () -> Unit) {
        if (terminal.compareAndSet(false, true)) action()
    }
}

private fun ntkReadProgressTimeoutMillis(priority: PageFetchPriority): Long = when (priority) {
    PageFetchPriority.FOCUS -> 1_500L
    PageFetchPriority.VISIBLE -> 1_500L
    PageFetchPriority.IMMINENT_FORWARD -> 2_000L
    PageFetchPriority.FORWARD -> 2_000L
    PageFetchPriority.DISTANT_FORWARD -> 2_000L
    PageFetchPriority.NORMAL -> 4_000L
    PageFetchPriority.ADJACENT_FORWARD -> 4_000L
    PageFetchPriority.BACKGROUND -> 8_000L
}
