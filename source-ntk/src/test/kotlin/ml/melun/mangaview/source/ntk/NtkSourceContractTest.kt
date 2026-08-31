package ml.melun.mangaview.source.ntk

import kotlinx.coroutines.test.runTest
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.PageSpec
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.source.PageByteStream
import ml.melun.mangaview.source.PageValidation
import ml.melun.mangaview.source.PreparationIntent
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.SourceResponse
import ml.melun.mangaview.source.SourceTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkSourceContractTest {
    @Test
    fun numericAndSlugEpisodesFromManhwaAndWebtoonShareOneCanonicalManifestContract() = runTest {
        val cases = listOf(
            NtkContractCase(NtkKind.MANHWA, "3540", "135917", "135918", "chapter-final"),
            NtkContractCase(NtkKind.WEBTOON, "work-slug", "episode-11", "episode-12", "episode-finale"),
        )

        cases.forEach { fixture ->
            val first = fixture.source(replicaTag = "a")
            val second = fixture.source(replicaTag = "b")
            val episode = fixture.episodeId()

            first.source.episodes(episode.seriesId)
            second.source.episodes(episode.seriesId)
            val firstManifest = first.source.manifest(episode)
            val repeatedManifest = first.source.manifest(episode)
            val differentReplicaManifest = second.source.manifest(episode)

            assertEquals(firstManifest.pages, repeatedManifest.pages)
            assertEquals(firstManifest.pages, differentReplicaManifest.pages)
            assertEquals(canonicalPages(episode, 3), firstManifest.pages)
            assertEquals(3, firstManifest.pages.map(PageSpec::id).toSet().size)
            assertEquals(fixture.episodeId(fixture.older), firstManifest.previousEpisodeId)
            assertEquals(fixture.episodeId(fixture.newer), firstManifest.nextEpisodeId)
            assertEquals(1, first.gateway.resolveCount)
            assertTrue(firstManifest.pages.all {
                it.dimensions == null && it.encodedLength == null && it.fingerprint == null
            })
        }
    }

    @Test
    fun replicaUrlsAndRequestHeadersStayBehindCanonicalPageIdentity() = runTest {
        val fixture = NtkContractCase(
            NtkKind.WEBTOON,
            "reader-series",
            "episode-11",
            "episode-12",
            "episode-13",
        )
        val runtime = fixture.source(replicaTag = "signed-token")
        val episode = fixture.episodeId()
        runtime.source.episodes(episode.seriesId)
        val manifest = runtime.source.manifest(episode)

        runtime.source.openPage(
            manifest.pages.first().id,
            PageValidation(entityTag = "old-etag", lastModified = "yesterday"),
        ).close()

        val attempts = runtime.transport.requests.filter { "page-cdn" in it.url }
        assertEquals(2, attempts.size)
        assertTrue(attempts.first().url.startsWith("https://primary-page-cdn"))
        assertTrue(attempts.last().url.startsWith("https://alternate-page-cdn"))
        attempts.forEach { request ->
            assertEquals("agent", request.headers["User-Agent"])
            assertEquals("signed-token", request.headers["X-Page-Token"])
            assertEquals("old-etag", request.headers["If-None-Match"])
            assertEquals("yesterday", request.headers["If-Modified-Since"])
            assertEquals("https://ntk.test${episode.remoteKey}", request.headers["Referer"])
        }
        assertEquals(PageId.at(episode, 0), manifest.pages.first().id)
        assertNull(manifest.pages.first().fingerprint)
    }

    private fun canonicalPages(episodeId: EpisodeId, count: Int): List<PageSpec> =
        List(count) { ordinal -> PageSpec(PageId.at(episodeId, ordinal), ordinal) }
}

private data class NtkContractCase(
    val kind: NtkKind,
    val work: String,
    val older: String,
    val current: String,
    val newer: String,
) {
    private val key = NtkSeriesKey(kind, work)

    fun episodeId(remoteEpisodeKey: String = current): EpisodeId = EpisodeId(
        SeriesId(SourceId("ntk"), key.path()),
        key.episodePath(remoteEpisodeKey),
    )

    fun source(replicaTag: String): NtkContractRuntime {
        val catalog = """
            {"total":3,"episodes":[
              {"sourceEpisodeId":"$newer","epNo":13,"title":"13화","imageCount":3},
              {"sourceEpisodeId":"$current","epNo":12,"title":"12화","imageCount":3},
              {"sourceEpisodeId":"$older","epNo":11,"title":"11화","imageCount":3}
            ]}
        """.trimIndent()
        val viewer = """
            <script>{"sourceWorkId":"$work","episodeId":"$current","imagesToken":"token",
              "imageApiPath":"/api/${kind.pathSegment}-images","imageCount":3}</script>
        """.trimIndent()
        val transport = NtkContractTransport(key, episodeId(), catalog, viewer)
        val gateway = NtkContractGateway(replicaTag)
        return NtkContractRuntime(
            source = NtkContentSource(
                NtkConfig("https://ntk.test", "agent"),
                transport,
                gateway,
            ),
            transport = transport,
            gateway = gateway,
        )
    }
}

private data class NtkContractRuntime(
    val source: NtkContentSource,
    val transport: NtkContractTransport,
    val gateway: NtkContractGateway,
)

private class NtkContractGateway(
    private val replicaTag: String,
) : NtkAccessGateway {
    var resolveCount = 0
        private set

    override suspend fun prepare(origin: String, episodePath: String, intent: PreparationIntent) = Unit

    override suspend fun resolve(
        document: NtkEpisodeDocument,
        descriptor: NtkViewerDescriptor,
    ): List<NtkPageRequest> {
        resolveCount += 1
        return List(3) { ordinal ->
            val page = ordinal.toString().padStart(3, '0')
            NtkPageRequest(
                url = "https://primary-page-cdn.example/$replicaTag/$page.jpg",
                alternateUrls = listOf(
                    "https://alternate-page-cdn.example/$replicaTag/$page.jpg",
                    "https://primary-page-cdn.example/$replicaTag/$page.jpg",
                ),
                headers = mapOf("X-Page-Token" to replicaTag),
            )
        }
    }
}

private class NtkContractTransport(
    private val key: NtkSeriesKey,
    private val episodeId: EpisodeId,
    private val catalog: String,
    private val viewer: String,
) : SourceTransport {
    val requests = mutableListOf<SourceRequest>()

    override suspend fun execute(request: SourceRequest): SourceResponse {
        requests += request
        val response = when {
            request.url.endsWith("/api/${key.kind.pathSegment}/${key.workKey}/episodes") -> {
                ContractPayload(200, catalog.toByteArray(), "application/json")
            }
            request.url == "https://ntk.test${episodeId.remoteKey}" -> {
                ContractPayload(200, viewer.toByteArray(), "text/html")
            }
            request.url.startsWith("https://primary-page-cdn") -> {
                ContractPayload(503, ByteArray(0), "text/plain")
            }
            request.url.startsWith("https://alternate-page-cdn") -> {
                ContractPayload(
                    200,
                    byteArrayOf(
                        0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte(),
                        0x00, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                    ),
                    "image/jpeg",
                )
            }
            else -> error("Unexpected NTK fixture request: ${request.url}")
        }
        return SourceResponse(
            statusCode = response.status,
            finalUrl = request.url,
            headers = mapOf(
                "ETag" to listOf("new-etag"),
                "Last-Modified" to listOf("today"),
            ),
            body = NtkContractBytes(response.bytes),
            contentLength = response.bytes.size.toLong(),
            contentType = response.contentType,
        )
    }
}

private data class ContractPayload(
    val status: Int,
    val bytes: ByteArray,
    val contentType: String,
)

private class NtkContractBytes(
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
