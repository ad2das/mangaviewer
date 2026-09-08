package ml.melun.mangaview.source.ntk

import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.SourceTransport
import ml.melun.mangaview.source.readBytes

/** User-supplied stable entry points. A landing page alone cannot establish a usable origin. */
class NtkOriginResolver(private val transport: SourceTransport, private val userAgent: String) {
    suspend fun resolve(current: String): String? {
        for (candidate in (listOf(current) + ENTRY_POINTS).distinct()) {
            probe(candidate)?.let { return it }
        }
        return null
    }

    private suspend fun probe(candidate: String): String? = try {
        withTimeoutOrNull(2500) {
            val response = transport.execute(SourceRequest(
                "$candidate/api/works?page=1&pageSize=1&withTotal=1",
                headers = mapOf("User-Agent" to userAgent, "Accept" to "application/json"),
                totalTimeoutMillis = 2500,
            ))
            response.use {
                if (it.statusCode != 200) return@withTimeoutOrNull null
                val uri = URI(it.finalUrl)
                if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) return@withTimeoutOrNull null
                val parsed = NtkDocumentParser().searchApi(it.readBytes(512 * 1024).toString(Charsets.UTF_8), SourceId("ntk"), NtkKind.WEBTOON)
                if (!parsed.recognized || parsed.series.isEmpty()) return@withTimeoutOrNull null
                URI(uri.scheme, null, uri.host, uri.port, null, null, null).toString()
            }
        }
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { null }

    companion object {
        val ENTRY_POINTS = listOf("https://sbxh9.com", "https://newtoki1.org")
        const val DEFAULT_ORIGIN = "https://sbxh9.com"
    }
}
