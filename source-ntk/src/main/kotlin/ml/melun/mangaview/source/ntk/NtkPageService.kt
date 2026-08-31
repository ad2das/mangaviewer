package ml.melun.mangaview.source.ntk

import java.io.IOException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
    private val manifestLanes = Semaphore(MANIFEST_LANES)
    private val preparationIntents = ConcurrentHashMap<EpisodeId, PreparationIntent>()
    private val replicaRacer = NtkReplicaRacer(
        transport,
        replicas,
        // Only an unproven origin set is raced. As soon as one replica verifies, the selector
        // sends every later page through that single winner. Starting the initial candidates
        // together removes an artificial 75 ms from HARD-page first byte without multiplying
        // steady-state traffic or decode work.
        hedgeDelayMillis = 0L,
        preferQuic = true,
    )

    suspend fun prepare(episodeId: EpisodeId, intent: PreparationIntent) {
        if (preparedEpisodes.contains(episodeId)) return
        preparationIntents.merge(episodeId, intent, ::strongerIntent)
    }

    suspend fun resolve(episodeId: EpisodeId): NtkPreparedEpisode =
        preparedEpisodes.resolve(episodeId) { load(episodeId) }

    private suspend fun load(episodeId: EpisodeId): NtkPreparedEpisode = manifestLanes.withPermit {
        val path = episodeId.remoteKey
        // ContentSource callers are allowed to request a manifest without a separate prepare call.
        // Start the browser's authorized ACK flight before the duplicate HTML transport request so
        // browser startup, challenge work and document parsing overlap on that cold path too.
        val origin = documents.currentOrigin()
        val intent = preparationIntents.remove(episodeId) ?: PreparationIntent.INITIAL_VIEW
        gateway.prepare(origin, path, intent)
        val document = documents.episodeDocument(path)
        validateDocumentIdentity(document, path)
        val parsed = parser.manifest(document)
        val viewer = parsed.viewer
        val nextEpisodeId = viewer?.nextEpisodePath?.let { EpisodeId(episodeId.seriesId, it) }
        parsed.descriptor?.let { descriptor ->
            gateway.documentAvailable(document, descriptor)
        }
        val resolved = resolveRequests(document, parsed)
        // HttpEngine construction is origin-scoped and can otherwise sit directly on the
        // visible page's critical path. Build only the selector's current best origin here;
        // this opens no image request and preserves PageId single-flight ownership.
        transport.warmConnections(resolved.first().candidates, preferQuic = true)
        // The ACK browser must remain alive until an image header and signature prove that the
        // protected replica is usable. Quiescing here races the first page on a cold install.
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

    private fun validateDocumentIdentity(document: NtkEpisodeDocument, expectedPath: String) {
        val final = URI(document.finalUrl)
        val origin = URI(document.origin)
        require(final.scheme == origin.scheme && final.authority == origin.authority) {
            "NTK episode document changed origin after resolution"
        }
        require(final.path == expectedPath) {
            "NTK episode document redirected to another episode"
        }
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
        val referer = documents.url(pageId.episodeId.remoteKey)
        val headers = documents.requestHeaders(referer).toMutableMap()
        headers.putAll(request.headers)
        headers["Accept"] = "image/avif,image/webp,image/apng,image/*,*/*;q=0.8"
        headers["Accept-Language"] = "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7"
        headers["Sec-Fetch-Dest"] = "image"
        headers["Sec-Fetch-Mode"] = "no-cors"
        headers["Sec-Fetch-Site"] = fetchSite(referer, request.url)
        validation?.entityTag?.let { headers["If-None-Match"] = it }
        validation?.lastModified?.let { headers["If-Modified-Since"] = it }
        return openCandidate(pageId, request, headers, priority)
    }

    private suspend fun resolveRequests(
        document: NtkEpisodeDocument,
        parsed: NtkManifestDocument,
    ): List<NtkPageRequest> {
        val expected = parsed.descriptor?.expectedPageCount
        val directComplete = parsed.directPages.isNotEmpty() &&
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
                url = resolvedUrl(base, request.url),
                alternateUrls = request.alternateUrls.map { resolvedUrl(base, it) },
            )
        }
    }

    private fun resolvedUrl(base: URI, value: String): String =
        if (value.startsWith("https://") || value.startsWith("http://")) value
        else base.resolve(value).toString()

    private fun fetchSite(referer: String, target: String): String = runCatching {
        val from = URI(referer)
        val to = URI(target)
        if (from.scheme == to.scheme && from.authority == to.authority) "same-origin" else "cross-site"
    }.getOrDefault("cross-site")

    private suspend fun openCandidate(
        pageId: PageId,
        request: NtkPageRequest,
        headers: Map<String, String>,
        priority: PageFetchPriority,
    ): OpenedPage {
        val origin = documents.currentOrigin()
        // Wait only inside this page worker; ViewerActivity and touch input remain live.
        val authorizationReady = gateway.awaitAuthorization(
            origin,
            pageId.episodeId.remoteKey,
        )
        if (!authorizationReady) {
            LOGGER.warning("ACK confirmation unavailable; using transport fallback id=${pageId.remoteKey}")
        }
        val winner = replicaRacer.open(
            request.candidates,
            headers,
            pageId.remoteKey,
            priority,
            ::validateImageResponse,
        )
        val elapsed = elapsedMillis(winner.startedAtNanos)
        replicas.accepted(winner.lease, elapsed)
        gateway.pageAccessEstablished(origin, pageId.episodeId.remoteKey)
        return winner.opened.copy(
            stream = ReplicaTrackedPageByteStream(
                upstream = winner.opened.stream,
                expectedLength = winner.opened.contentLength,
                succeeded = {
                    replicas.completed(winner.lease, elapsedMillis(winner.startedAtNanos))
                },
                failed = { replicas.failedAndReleased(winner.lease) },
                abandoned = { replicas.abandoned(winner.lease) },
            ),
        )
    }

    private suspend fun validateImageResponse(
        response: ml.melun.mangaview.source.SourceResponse,
    ): OpenedPage {
        try {
            val prefix = ByteArray(IMAGE_SIGNATURE_BYTES)
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
        } catch (failure: Throwable) {
            response.close()
            throw failure
        }
    }

    private fun elapsedMillis(startedNanos: Long): Long =
        ((System.nanoTime() - startedNanos).coerceAtLeast(0L)) / 1_000_000L

    private companion object {
        const val IMAGE_SIGNATURE_BYTES = 12
        const val MANIFEST_LANES = 2
        val LOGGER: Logger = Logger.getLogger(NtkPageService::class.java.simpleName)
    }

}

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

private class PrefixedPageByteStream(
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
        if (copied == byteCount) return copied
        val following = upstream.readAtMost(destination, offset + copied, byteCount - copied)
        return if (following < 0) copied else copied + following
    }

    override fun close() = upstream.close()
}

internal class ReplicaTrackedPageByteStream(
    private val upstream: PageByteStream,
    private val expectedLength: Long?,
    private val succeeded: () -> Unit,
    private val failed: () -> Unit,
    private val abandoned: () -> Unit,
) : PageByteStream {
    private val terminal = AtomicBoolean(false)
    private var received = 0L

    override suspend fun readAtMost(destination: ByteArray, offset: Int, byteCount: Int): Int {
        val count = try {
            upstream.readAtMost(destination, offset, byteCount)
        } catch (failure: Throwable) {
            finish(failed)
            throw failure
        }
        if (count > 0) {
            received = Math.addExact(received, count.toLong())
            if (expectedLength != null && received > expectedLength) {
                finish(failed)
                throw IOException("NTK replica exceeded its declared body length")
            }
        } else if (count < 0) {
            if (expectedLength != null && received != expectedLength) {
                finish(failed)
                throw IOException("NTK replica body length did not match its declaration")
            }
            finish(succeeded)
        }
        return count
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
