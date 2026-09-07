package ml.melun.mangaview.content

import java.io.Closeable
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.PageSpec
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.source.PageFetchPriority
import ml.melun.mangaview.viewer.session.DemandClass

data class EncodedPageRef(
    val pageId: PageId,
    val path: String,
    val byteCount: Long,
    val fingerprint: String,
    val dimensions: PageDimensions,
) {
    init {
        require(path.isNotBlank())
        require(byteCount > 0L)
        require(fingerprint.isNotBlank())
    }
}

fun interface EpisodeManifestPort {
    /** Preparation and loading are one actor-owned, physically joined operation. */
    suspend fun load(episodeId: EpisodeId): EpisodeManifest
}

interface RawPagePort {
    suspend fun find(pageId: PageId): EncodedPageRef?

    /** One invocation is one physical fetch. The pipeline is the only single-flight owner. */
    suspend fun fetch(
        pageId: PageId,
        priority: PageFetchPriority,
        responseStarted: () -> Unit = {},
    ): EncodedPageRef
}

/** Exact logical pixel ownership, independent of fractional viewport demand. */
data class SourceRowRange(val top: Int, val bottomExclusive: Int) {
    init {
        require(top >= 0 && bottomExclusive > top)
    }
}

data class DecodeRequest(
    val generation: Long,
    val page: PageSpec,
    val encoded: EncodedPageRef,
    val dimensions: PageDimensions,
    val displayWidthPx: Int,
    val sourceRange: SourceRowRange,
    val demandClass: DemandClass,
) {
    init {
        require(displayWidthPx > 0)
        require(encoded.pageId == page.id)
        require(sourceRange.bottomExclusive <= dimensions.heightPx)
    }
}

interface CpuTileLease : Closeable {
    val pageId: PageId
    val sourceTopPx: Int
    val sourceBottomPx: Int
    val sourceHeightPx: Int
    val byteCount: Long
}

fun interface ImageDecodePort {
    suspend fun decode(request: DecodeRequest): CpuTileLease
}

data class TextureRef(
    val pageId: PageId,
    val rendererEpoch: Long,
    val key: Long,
    val sourceTopPx: Int,
    val sourceBottomPx: Int,
    val sourceHeightPx: Int,
    val byteCount: Long,
) {
    init {
        require(rendererEpoch > 0L)
        require(key > 0L)
        require(sourceTopPx >= 0 && sourceBottomPx > sourceTopPx)
        require(sourceBottomPx <= sourceHeightPx)
        require(byteCount > 0L)
    }
}

interface TextureUploadPort {
    /** Closes [pixels] exactly once; even cancellation must await the end of native reads/uploads. */
    suspend fun upload(rendererEpoch: Long, pixels: CpuTileLease): TextureRef

    fun release(texture: TextureRef)
}

sealed interface ContentPipelineEvent {
    data class ManifestReady(val generation: Long, val manifest: EpisodeManifest) : ContentPipelineEvent
    data class ManifestFailed(val generation: Long, val episodeId: EpisodeId,
        val cause: Throwable) : ContentPipelineEvent
    data class ResponseStarted(val generation: Long, val pageId: PageId) : ContentPipelineEvent
    data class RawVerified(val generation: Long, val encoded: EncodedPageRef) : ContentPipelineEvent
    data class TextureReady(val generation: Long, val texture: TextureRef) : ContentPipelineEvent
    data class TextureEvicted(val generation: Long, val texture: TextureRef) : ContentPipelineEvent
    data class PageFailed(
        val generation: Long,
        val pageId: PageId,
        val phase: PipelineFailurePhase,
        val demandClass: DemandClass,
        val cause: Throwable,
    ) : ContentPipelineEvent
}

enum class PipelineFailurePhase {
    FETCH,
    DECODE,
    UPLOAD,
}

fun interface ContentPipelineSink {
    fun emit(event: ContentPipelineEvent)
}
