package ml.melun.mangaview.content

import kotlinx.coroutines.Job
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.PageSpec
import ml.melun.mangaview.viewer.session.DemandClass
import ml.melun.mangaview.viewer.session.SourceRangeFraction

internal data class PageRecord(
    val page: PageSpec,
    var raw: RawState = RawState.Absent,
    var decode: DecodeState = DecodeState.Idle,
    var demand: DemandTarget? = null,
    var fetchFailures: Int = 0,
    var decodeFailures: Int = 0,
    var residents: List<TextureRef> = emptyList(),
)

internal data class DemandTarget(
    val demandClass: DemandClass,
    val sourceRange: SourceRangeFraction?,
    val rank: Int,
)

internal sealed interface RawState {
    data object Absent : RawState
    data class Fetching(val token: Long, val job: Job, val cancelRequested: Boolean = false) : RawState
    data class Verified(val encoded: EncodedPageRef) : RawState
    data class WaitingRetry(val retryAtMillis: Long) : RawState
    data object Failed : RawState
}

internal sealed interface DecodeState {
    data object Idle : DecodeState
    data class Decoding(
        val token: Long,
        val range: SourceRowRange,
        val hardLane: Boolean,
        val job: Job,
        val cancelRequested: Boolean = false,
        val reservedByteCount: Long = 0L,
    ) : DecodeState
    data class Uploading(
        val token: Long,
        val range: SourceRowRange,
        val hardLane: Boolean,
        val job: Job,
        val cancelRequested: Boolean = false,
        val reservedByteCount: Long = 0L,
    ) : DecodeState
    data class Resident(val texture: TextureRef) : DecodeState
    data class WaitingRetry(val retryAtMillis: Long) : DecodeState
    data object Failed : DecodeState
}

data class PagePipelineSnapshot(
    val pageId: PageId,
    val rawState: String,
    val decodeState: String,
    val residentTextureCount: Int,
)

data class ContentPipelineSnapshot(
    val generation: Long,
    val rendererEpoch: Long,
    val activeFetches: Int,
    val activeDecodes: Int,
    val activeUploads: Int,
    val retryWakeups: Int,
    val pages: List<PagePipelineSnapshot>,
    val retiringPages: List<PagePipelineSnapshot> = emptyList(),
    val activeManifests: Int = 0,
)
