package ml.melun.mangaview.source.ntk

import java.io.Closeable
import ml.melun.mangaview.source.PreparationIntent

data class NtkPageRequest(
    val url: String,
    val alternateUrls: List<String> = emptyList(),
    val headers: Map<String, String> = emptyMap(),
    val episodeOrigin: String? = null,
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
    val originTicket: NtkOriginTicket? = null,
)

interface NtkAccessGateway : Closeable {
    /** Number of episode preparations that can coexist without superseding one another. */
    val parallelPreparationCapacity: Int
        get() = 1

    suspend fun prepare(origin: String, episodePath: String, intent: PreparationIntent)

    /**
     * Waits for the provider browser to confirm that protected image requests are authorized.
     * False means that no trustworthy confirmation arrived within the browser flight's absolute
     * deadline; callers must retain their normal transport fallback in that case.
     */
    suspend fun awaitAuthorization(origin: String, episodePath: String): Boolean = true

    /** True only when a new image request can start with an already completed ACK. */
    fun isAuthorizationReady(origin: String, episodePath: String): Boolean = true

    suspend fun resolve(
        document: NtkEpisodeDocument,
        descriptor: NtkViewerDescriptor,
    ): List<NtkPageRequest>

    suspend fun documentAvailable(
        document: NtkEpisodeDocument,
        descriptor: NtkViewerDescriptor,
    ) = Unit

    /**
     * Releases the exclusive manifest-preparation lease while retaining any browser state for
     * [awaitAuthorization]. Direct manifests never call [resolve], so without this boundary they
     * could occupy the pool forever even though document parsing had already finished.
     */
    fun manifestResolutionFinished(origin: String, episodePath: String) = Unit

    /** Ages only the adjacent episode's official one-use challenge after this manifest completes. */
    fun preflightAdjacentChallenge(
        origin: String,
        episodePath: String,
        adjacentEpisodePath: String,
    ) = Unit

    /** Releases provider browser work once the direct page path has proved usable. */
    fun pageAccessEstablished(origin: String, episodePath: String) = Unit

    override fun close() = Unit
}
