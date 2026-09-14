package ml.melun.mangaview.source.goodtoon

import java.util.Collections
import ml.melun.mangaview.source.PageByteStream
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.SourceResponse
import ml.melun.mangaview.source.SourceTransport
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

internal object GoodtoonFixtures {
    const val BASE_URL = "https://www.goodtoon004.com"

    fun text(name: String): String = requireNotNull(
        GoodtoonFixtures::class.java.getResourceAsStream("/fixtures/$name"),
    ) { "Missing fixture $name" }.use { it.readBytes().toString(Charsets.UTF_8) }

    fun document(name: String): Document = Jsoup.parse(text(name), BASE_URL)
}

internal class ByteStream(private val bytes: ByteArray) : PageByteStream {
    private var offset = 0

    override suspend fun readAtMost(destination: ByteArray, offset: Int, byteCount: Int): Int {
        if (this.offset >= bytes.size) return -1
        val count = minOf(byteCount, bytes.size - this.offset)
        System.arraycopy(bytes, this.offset, destination, offset, count)
        this.offset += count
        return count
    }

    override fun close() = Unit
}

internal fun htmlResponse(
    url: String,
    body: String,
    status: Int = 200,
    finalUrl: String = url,
): SourceResponse {
    val bytes = body.toByteArray(Charsets.UTF_8)
    return SourceResponse(
        statusCode = status,
        finalUrl = finalUrl,
        headers = emptyMap(),
        body = ByteStream(bytes),
        contentLength = bytes.size.toLong(),
        contentType = "text/html; charset=UTF-8",
    )
}

internal fun binaryResponse(url: String, status: Int = 200): SourceResponse =
    SourceResponse(
        statusCode = status,
        finalUrl = url,
        headers = emptyMap(),
        body = ByteStream(ByteArray(8) { 1 }),
        contentLength = 8,
        contentType = "image/jpeg",
    )

internal class RecordingTransport(
    private val parallelism: Int = 1,
    private val handler: suspend (SourceRequest) -> SourceResponse,
) : SourceTransport {
    private val recorded = Collections.synchronizedList(mutableListOf<SourceRequest>())
    val requests: List<SourceRequest> get() = recorded.toList()

    override suspend fun execute(request: SourceRequest): SourceResponse {
        recorded += request
        return handler(request)
    }

    override suspend fun executeOnFreshRoute(request: SourceRequest): SourceResponse = execute(request)

    override suspend fun executeOnAlternateRoute(request: SourceRequest): SourceResponse = execute(request)

    override fun routeParallelism(): Int = parallelism

    fun urls(): List<String> = requests.map { it.url }
}

internal fun goodtoonSeriesId(slug: String): ml.melun.mangaview.core.SeriesId =
    ml.melun.mangaview.core.SeriesId(goodtoonSourceId(), slug)

internal fun goodtoonEpisodeId(slug: String, episode: String): ml.melun.mangaview.core.EpisodeId =
    ml.melun.mangaview.core.EpisodeId(goodtoonSeriesId(slug), episode)
