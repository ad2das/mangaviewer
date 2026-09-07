package ml.melun.mangaview.viewer

import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.ReadingPosition

data class ViewerTelemetrySnapshot(
    val capturedAtNanos: Long,
    val manifests: List<EpisodeManifest>,
    val anchor: ReadingPosition,
    val anchorOrdinal: Int,
    val scrollOffsetUnits: Long,
    val scrollRevision: Long,
    val scrollCause: ScrollMutationCause,
    val userInputRevision: Long,
    val viewportHeightUnits: Long,
    val contentHeightUnits: Long,
    val velocityUnitsPerSecond: Long,
    val networkConcurrency: Int,
    val activeFetchCount: Int,
    val activeFetchPageIds: List<PageId>,
    val activeDecodeCount: Int,
    val activeDecodePageIds: List<PageId>,
    val residentPageIds: List<PageId>,
    val currentEpisodeProgress: EpisodeProgressTelemetry?,
    val episodeAppends: List<EpisodeAppendTelemetry>,
    val visiblePages: List<VisiblePageTelemetry>,
    val uncoveredViewportUnits: Long,
    val visuallyUncoveredViewportUnits: Long,
    val overlappingViewportUnits: Long,
)

data class EpisodeProgressTelemetry(
    val episodeId: ml.melun.mangaview.core.EpisodeId,
    val pageCount: Int,
    val verifiedCount: Int,
)

data class EpisodeAppendTelemetry(
    val fromEpisodeId: ml.melun.mangaview.core.EpisodeId,
    val targetEpisodeId: ml.melun.mangaview.core.EpisodeId?,
    val terminal: Boolean,
    val hasBoundary: Boolean,
    val retryReason: String?,
)

data class VisiblePageTelemetry(
    val pageId: PageId,
    val globalOrdinal: Int,
    val visibleUnits: Long,
    val coveredUnits: Long,
    val loadingUnits: Long,
    val visualCoveredUnits: Long,
    val overlappingUnits: Long,
    val presented: Boolean,
    val pageHeightUnits: Long = 0L,
    val visibleOffsetInPageUnits: Long = 0L,
)
