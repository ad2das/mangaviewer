package ml.melun.mangaview.source.ntk

import java.io.Closeable
import ml.melun.mangaview.source.PreparationIntent

data class NtkPageRequest(
    val url: String,
    val alternateUrls: List<String> = emptyList(),
    val headers: Map<String, String> = emptyMap(),
) {
    val candidates: List<String> = (listOf(url) + alternateUrls).distinct()

    init {
        require(url.isNotBlank()) { "NTK page URL must not be blank" }
        require(candidates.none(String::isBlank)) { "NTK alternate page URL must not be blank" }
    }
}

data class NtkViewerDescriptor(
    val workId: String,
    val episodeId: String,
    val token: String,
    val apiPath: String,
    val expectedPageCount: Int?,
)

data class NtkEpisodeDocument(
    val origin: String,
    val path: String,
    val html: String,
    val responseHeaders: Map<String, List<String>> = emptyMap(),
    val contentType: String? = null,
    val finalUrl: String = origin + path,
)

interface NtkAccessGateway : Closeable {
    suspend fun prepare(origin: String, episodePath: String, intent: PreparationIntent)

    suspend fun resolve(
        document: NtkEpisodeDocument,
        descriptor: NtkViewerDescriptor,
    ): List<NtkPageRequest>

    suspend fun documentAvailable(
        document: NtkEpisodeDocument,
        descriptor: NtkViewerDescriptor,
    ) = Unit

    /** Releases provider browser work once the direct page path has proved usable. */
    fun pageAccessEstablished(origin: String, episodePath: String) = Unit

    override fun close() = Unit
}
