package ml.melun.mangaview.source.ntk

import java.util.ArrayDeque
import kotlinx.coroutines.test.runTest
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.source.PageByteStream
import ml.melun.mangaview.source.PreparationIntent
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.SourceResponse
import ml.melun.mangaview.source.SourceTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkContentSourceTest {
    @Test
    fun manifestUsesViewerMetadataWithoutAnyCatalogRequest() = runTest {
        val viewer = """
            <script>{"sourceWorkId":"work-slug","episodeId":"current","imagesToken":"token",
              "imageApiPath":"/api/webtoon-images","imageCount":1,"epTitle":"현재 화",
              "prevEpId":"older","nextEpId":"newer"}</script>
        """.trimIndent()
        val gateway = RecordingGateway(
            listOf(NtkPageRequest("https://cdn.example/001.jpg")),
        )
        val source = NtkContentSource(
            NtkConfig("https://ntk.test", "agent"),
            NtkQueueTransport(viewer),
            gateway,
        )
        val series = SeriesId(SourceId("ntk"), "/webtoon/work-slug")

        val manifest = source.manifest(
            EpisodeId(series, "/webtoon/work-slug/current"),
        )

        assertEquals("현재 화", manifest.title)
        assertEquals("/webtoon/work-slug/older", manifest.previousEpisodeId?.remoteKey)
        assertEquals("/webtoon/work-slug/newer", manifest.nextEpisodeId?.remoteKey)
        assertEquals(1, manifest.pages.size)
    }

    @Test
    fun protectedEpisodeIsConvertedToTheSamePageManifestAsDirectContent() = runTest {
        val episodeApi = """
            {"total":2,"episodes":[
              {"sourceEpisodeId":"ep-12","epNo":12,"title":"12화","imageCount":2},
              {"sourceEpisodeId":"ep-11","epNo":11,"title":"11화","imageCount":2}
            ]}
        """.trimIndent()
        val viewer = """
            <script>{"sourceWorkId":"work-slug","episodeId":"ep-11","imagesToken":"token",
              "imageApiPath":"/api/webtoon-images","imageCount":2}</script>
        """.trimIndent()
        val transport = NtkQueueTransport(episodeApi, viewer)
        val gateway = RecordingGateway(
            listOf(
                NtkPageRequest("https://cdn.example/001.jpg"),
                NtkPageRequest("https://cdn.example/002.jpg"),
            ),
        )
        val source = NtkContentSource(NtkConfig("https://ntk.test", "agent"), transport, gateway)
        val series = SeriesId(SourceId("ntk"), "/webtoon/work-slug")

        val episodes = source.episodes(series).items
        val manifest = source.manifest(EpisodeId(series, "/webtoon/work-slug/ep-11"))

        assertEquals(listOf("12화", "11화"), episodes.map { it.title })
        assertEquals(2, manifest.pages.size)
        assertEquals(listOf("p0000", "p0001"), manifest.pages.map { it.id.remoteKey })
        assertEquals("/webtoon/work-slug/ep-12", manifest.nextEpisodeId?.remoteKey)
        assertEquals("/webtoon/work-slug/ep-11", gateway.preparedPath)
        assertTrue(gateway.resolved)
        assertTrue(manifest.pages.map { it.id }.toSet().size == 2)
    }

    @Test
    fun providerSequenceControlsAdjacencyWhenDisplayTitleNumbersConflict() = runTest {
        val episodeApi = """
            {"total":3,"episodes":[
              {"sourceEpisodeId":"newer","epNo":225,"title":"특별편 1","imageCount":1},
              {"sourceEpisodeId":"current","epNo":224,"title":"시즌 999","imageCount":1},
              {"sourceEpisodeId":"older","epNo":223,"title":"외전 5000","imageCount":1}
            ]}
        """.trimIndent()
        val viewer = """
            <script>{"sourceWorkId":"work-slug","episodeId":"current","imagesToken":"token",
              "imageApiPath":"/api/webtoon-images","imageCount":1}</script>
        """.trimIndent()
        val source = NtkContentSource(
            NtkConfig("https://ntk.test", "agent"),
            NtkQueueTransport(episodeApi, viewer),
            RecordingGateway(listOf(NtkPageRequest("https://cdn.example/001.jpg"))),
        )
        val series = SeriesId(SourceId("ntk"), "/webtoon/work-slug")
        val current = EpisodeId(series, "/webtoon/work-slug/current")

        assertEquals(
            listOf("특별편 1", "시즌 999", "외전 5000"),
            source.episodes(series).items.map { it.title },
        )
        val manifest = source.manifest(current)
        assertEquals("/webtoon/work-slug/newer", manifest.nextEpisodeId?.remoteKey)
        assertEquals("/webtoon/work-slug/older", manifest.previousEpisodeId?.remoteKey)
    }

    @Test
    fun prepareDefersNavigationToTheSerializedManifestLane() = runTest {
        val gateway = RecordingGateway(emptyList())
        val source = NtkContentSource(
            NtkConfig("https://ntk.test", "agent"),
            NtkQueueTransport(),
            gateway,
        )
        val series = SeriesId(SourceId("ntk"), "/manhwa/2")
        val episode = EpisodeId(series, "/manhwa/2/1181")

        source.prepare(episode, PreparationIntent.ADJACENT_FORWARD)

        assertEquals(null, gateway.preparedPath)
    }
}

private class RecordingGateway(
    private val pages: List<NtkPageRequest>,
) : NtkAccessGateway {
    var resolved = false
    var preparedPath: String? = null

    override suspend fun prepare(origin: String, episodePath: String, intent: PreparationIntent) {
        preparedPath = episodePath
    }

    override suspend fun resolve(
        document: NtkEpisodeDocument,
        descriptor: NtkViewerDescriptor,
    ): List<NtkPageRequest> {
        resolved = true
        return pages
    }
}

private class NtkQueueTransport(vararg bodies: String) : SourceTransport {
    private val bodies = ArrayDeque(bodies.toList())

    override suspend fun execute(request: SourceRequest): SourceResponse {
        val bytes = bodies.removeFirst().toByteArray()
        return SourceResponse(
            statusCode = 200,
            finalUrl = request.url,
            headers = emptyMap(),
            body = NtkBytesStream(bytes),
            contentLength = bytes.size.toLong(),
            contentType = "text/html; charset=utf-8",
        )
    }
}

private class NtkBytesStream(private val bytes: ByteArray) : PageByteStream {
    private var position = 0

    override suspend fun readAtMost(destination: ByteArray, offset: Int, byteCount: Int): Int {
        if (position == bytes.size) return -1
        val count = minOf(byteCount, bytes.size - position)
        bytes.copyInto(destination, offset, position, position + count)
        position += count
        return count
    }

    override fun close() = Unit
}
