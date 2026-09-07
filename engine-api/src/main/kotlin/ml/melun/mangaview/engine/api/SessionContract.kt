package ml.melun.mangaview.engine.api

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.ReadingPosition

data class InputSample(
    val sequence: Long,
    val gestureId: Long,
    val eventTimeNanos: Long,
    val deltaScreenUnits: Long,
) {
    init {
        require(sequence > 0L && gestureId > 0L && eventTimeNanos >= 0L)
    }
}

enum class InputOutcome { APPLIED, CLAMPED, DEFERRED, CANCELLED }
enum class DocumentBoundary { START, END }

data class BoundaryProof(
    val boundary: DocumentBoundary,
    val pageId: PageId,
    val geometryRevision: Long,
)

/** A deferred receipt retains its original acceptance time when subsequently resolved. */
data class InputReceipt(
    val sample: InputSample,
    val acceptedAtNanos: Long,
    val resolvedAtNanos: Long?,
    val appliedScreenUnits: Long,
    val outcome: InputOutcome,
    val geometryRevision: Long,
    val boundary: BoundaryProof? = null,
) {
    init {
        require(acceptedAtNanos >= sample.eventTimeNanos)
        require(resolvedAtNanos == null || resolvedAtNanos >= acceptedAtNanos)
        require((outcome == InputOutcome.DEFERRED) == (resolvedAtNanos == null))
        require((outcome == InputOutcome.CLAMPED) == (boundary != null))
        require(boundary == null || boundary.geometryRevision == geometryRevision)
        require(if (sample.deltaScreenUnits >= 0L) appliedScreenUnits in 0L..sample.deltaScreenUnits
            else appliedScreenUnits in sample.deltaScreenUnits..0L)
    }
}

data class VisiblePageRegion(
    val pageId: PageId,
    val dimensions: PageDimensions,
    val sourceTopQ32: Long,
    val sourceBottomQ32: Long,
    val screenTopUnits: Long,
    val screenBottomUnits: Long,
) {
    init {
        require(sourceTopQ32 >= 0L && sourceBottomQ32 > sourceTopQ32)
        require(sourceBottomQ32 <= dimensions.heightPx.toLong() * SourceAnchor.SOURCE_UNITS_PER_PIXEL)
        require(screenTopUnits >= 0L && screenBottomUnits > screenTopUnits)
    }
}

enum class EngineSessionPhase { OPENING, ACTIVE, CLOSED }

data class EngineSessionSnapshot(
    val sessionId: Long,
    val generation: Long,
    val phase: EngineSessionPhase,
    val viewport: EngineViewport,
    val anchor: SourceAnchor?,
    val geometryRevision: Long,
    val inputRevision: Long,
    val pendingInputCount: Int,
    val visibleRegions: List<VisiblePageRegion>,
    val requiredDimensions: Set<PageId>,
    val requiredEpisodes: Set<EpisodeId>,
    val completeViewport: Boolean,
    val requiredNavigation: Set<EpisodeId> = emptySet(),
    val anchorDimensions: PageDimensions? = null,
)

sealed interface SessionEvent {
    data class PositionResolved(
        val generation: Long,
        val anchor: SourceAnchor?,
        val legacyPosition: ReadingPosition? = null,
    ) : SessionEvent
    data class ManifestResolved(
        val generation: Long,
        val manifest: EpisodeManifest,
        val navigationKnown: Boolean = true,
    ) : SessionEvent
    /** Refines unknown adjacency without replacing page identities or moving the source anchor. */
    data class NavigationResolved(
        val generation: Long,
        val episodeId: EpisodeId,
        val previousEpisodeId: EpisodeId?,
        val nextEpisodeId: EpisodeId?,
    ) : SessionEvent
    data class DimensionsResolved(
        val generation: Long,
        val pageId: PageId,
        val dimensions: PageDimensions,
    ) : SessionEvent
    data class Input(val sample: InputSample) : SessionEvent
    data class Resize(val viewport: EngineViewport) : SessionEvent
    data class Navigate(val episodeId: EpisodeId) : SessionEvent
    data object Close : SessionEvent
}

data class SessionUpdate(
    val snapshot: EngineSessionSnapshot,
    val receipts: List<InputReceipt> = emptyList(),
)

interface EngineSessionPort {
    val snapshot: EngineSessionSnapshot
    /** Called on the creating thread; never performs I/O or waits for content. */
    fun dispatch(event: SessionEvent): SessionUpdate
}
