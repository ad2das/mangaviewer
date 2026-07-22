package ml.melun.mangaview.reader

/**
 * Immutable hand-off from the exact authority gate to the rolling reader.  The reader must not
 * reconstruct this contract from Manga fields: those fields are mutable compatibility mirrors.
 */
class StrictExactLaunchSeal private constructor(
    val manifestSeal: NtkEpisodeManifestSeal,
    val normalizedEpisodePath: String,
    val manifestDigest: String,
    val discoveryGeneration: Long,
    val proofDigestSha256: String,
    canonicalAssets: List<String>
) {
    val canonicalAssets: List<String> = canonicalAssets
        .map(NtkStripDigests::canonicalAsset)
        .toList()
    val pageCount: Int = this.canonicalAssets.size

    init {
        require(manifestSeal.isStructurallyComplete)
        require(normalizedEpisodePath == manifestSeal.normalizedEpisodePath)
        require(manifestDigest == manifestSeal.digestSha256)
        require(discoveryGeneration > 0L)
        require(NtkStripDigests.isSha256(proofDigestSha256))
        require(pageCount == manifestSeal.pageCount)
        require(this.canonicalAssets == manifestSeal.normalizedCanonicalAssets)
    }

    fun matchesEpisodePath(path: String?): Boolean =
        NtkStripDigests.normalizeEpisodePath(path.orEmpty()) == normalizedEpisodePath

    fun matchesSource(sourceIndex: Int, asset: String?): Boolean =
        sourceIndex in canonicalAssets.indices &&
            NtkStripDigests.canonicalAsset(asset.orEmpty()) == canonicalAssets[sourceIndex]

    fun hasSameAuthority(authority: NtkAuthoritativeManifest?): Boolean =
        authority?.isProductionClaimable == true &&
            authority.seal.normalizedEpisodePath == normalizedEpisodePath &&
            authority.seal.digestSha256 == manifestDigest &&
            authority.proof.discoveryGeneration == discoveryGeneration &&
            authority.proof.proofDigestSha256 == proofDigestSha256 &&
            authority.seal.normalizedCanonicalAssets == canonicalAssets

    companion object {
        @JvmStatic
        fun from(authority: NtkAuthoritativeManifest): StrictExactLaunchSeal {
            require(authority.isProductionClaimable)
            val seal = authority.seal
            return StrictExactLaunchSeal(
                manifestSeal = seal,
                normalizedEpisodePath = seal.normalizedEpisodePath,
                manifestDigest = seal.digestSha256,
                discoveryGeneration = authority.proof.discoveryGeneration,
                proofDigestSha256 = authority.proof.proofDigestSha256,
                canonicalAssets = seal.normalizedCanonicalAssets
            )
        }
    }
}

/** Latest-only source admission. Display indexes never substitute for immutable source indexes. */
data class StrictRollingAdmission(
    val epoch: Long,
    val physicalDrawPresented: Boolean,
    val visibleFirstDisplay: Int,
    val visibleLastDisplay: Int,
    val direction: Int,
    val allowedFirstSource: Int,
    val allowedLastSource: Int
) {
    init {
        require(epoch >= 0L)
        require(direction == -1 || direction == 1)
        require(allowedFirstSource >= 0)
        require(allowedLastSource >= allowedFirstSource)
    }

    fun admitsSource(sourceIndex: Int): Boolean =
        sourceIndex in allowedFirstSource..allowedLastSource

    /**
     * A committed HWUI frame opens the cold rolling gate exactly once. After that transition,
     * viewport callbacks are the sole owner of direction and source demand. A later frame commit
     * can describe a narrower, older viewport than the latest scroll callback; allowing it to
     * rewrite demand would advance the epoch and cancel useful in-flight decodes.
     */
    fun shouldOpenPhysicalDrawGate(): Boolean = !physicalDrawPresented

    /**
     * A compositor commit can repeat the same viewport many times.  Such a repeat is evidence,
     * not a new source demand: advancing the epoch would cancel useful in-flight bodies and can
     * turn a permanent content error into an unbounded request/commit loop.
     */
    fun hasSameDemand(other: StrictRollingAdmission): Boolean =
        physicalDrawPresented == other.physicalDrawPresented &&
            visibleFirstDisplay == other.visibleFirstDisplay &&
            visibleLastDisplay == other.visibleLastDisplay &&
            direction == other.direction &&
            allowedFirstSource == other.allowedFirstSource &&
            allowedLastSource == other.allowedLastSource

    companion object {
        @JvmStatic
        fun initial(pageCount: Int): StrictRollingAdmission {
            require(pageCount > 0)
            return StrictRollingAdmission(
                epoch = 0L,
                physicalDrawPresented = false,
                visibleFirstDisplay = 0,
                visibleLastDisplay = 0,
                direction = 1,
                allowedFirstSource = 0,
                allowedLastSource = pageCount - 1
            )
        }

        @JvmStatic
        fun update(
            previous: StrictRollingAdmission,
            pageCount: Int,
            visibleFirstDisplay: Int,
            visibleLastDisplay: Int,
            visibleFirstSource: Int,
            visibleLastSource: Int,
            direction: Int,
            physicalDrawPresented: Boolean
        ): StrictRollingAdmission {
            require(pageCount > 0)
            // The product's dominant reader UX is a single continuous traversal toward later
            // pages. Direction changes never reserve or move capacity behind the viewport.
            val safeDirection = 1
            val candidate = if (!physicalDrawPresented) {
                initial(pageCount).copy(
                    visibleFirstDisplay = 0,
                    visibleLastDisplay = 0
                )
            } else {
                StrictRollingAdmission(
                    epoch = 0L,
                    physicalDrawPresented = true,
                    visibleFirstDisplay = visibleFirstDisplay,
                    visibleLastDisplay = visibleLastDisplay,
                    direction = safeDirection,
                    allowedFirstSource = 0,
                    allowedLastSource = pageCount - 1
                )
            }
            // startStrictExactColdRolling already binds and submits the epoch-zero forward runway.
            // A first layout callback with that same window is evidence only, just like every
            // later repeated compositor commit.
            if (previous.hasSameDemand(candidate)) return previous
            return candidate.copy(epoch = previous.epoch + 1L)
        }
    }
}

/**
 * Event-time ordered mailbox for the rolling reader's two producers: viewport callbacks and
 * committed-frame callbacks.  Sequence allocation and publication happen under the same lock, so
 * a producer cannot reserve an old sequence, stall, and publish it after a newer event.
 *
 * Window traffic is latest-only. Physical evidence is never overwritten by window coalescing;
 * the first pending proof opens the cold gate and the latest pending proof preserves the newest
 * committed viewport when several HWUI callbacks arrive before the control lane drains.
 */
internal class StrictRollingControlMailbox {
    internal sealed interface Event {
        val sequence: Long
    }

    internal data class WindowEvent(
        override val sequence: Long,
        val first: Int,
        val last: Int,
        val anchor: Int,
        val busy: Boolean
    ) : Event

    internal data class PhysicalDrawEvent(
        override val sequence: Long,
        val firstVisibleDisplay: Int,
        val lastVisibleDisplay: Int,
        val direction: Int
    ) : Event

    internal data class Batch(val events: List<Event>)

    private val lock = Any()
    private var nextSequence = 1L
    private var drainScheduled = false
    private var latestWindow: WindowEvent? = null
    private var firstPhysicalDraw: PhysicalDrawEvent? = null
    private var latestPhysicalDraw: PhysicalDrawEvent? = null

    fun offerWindow(first: Int, last: Int, anchor: Int, busy: Boolean): Boolean = synchronized(lock) {
        latestWindow = WindowEvent(allocateSequenceLocked(), first, last, anchor, busy)
        markDrainScheduledLocked()
    }

    fun offerPhysicalDraw(
        firstVisibleDisplay: Int,
        lastVisibleDisplay: Int,
        direction: Int
    ): Boolean = synchronized(lock) {
        val event = PhysicalDrawEvent(
            allocateSequenceLocked(),
            firstVisibleDisplay,
            lastVisibleDisplay,
            direction
        )
        if (firstPhysicalDraw == null) firstPhysicalDraw = event
        latestPhysicalDraw = event
        markDrainScheduledLocked()
    }

    fun pollBatch(): Batch? = synchronized(lock) {
        val window = latestWindow
        val firstPhysical = firstPhysicalDraw
        val latestPhysical = latestPhysicalDraw
        if (window == null && firstPhysical == null && latestPhysical == null) return@synchronized null
        latestWindow = null
        firstPhysicalDraw = null
        latestPhysicalDraw = null
        val events = ArrayList<Event>(3)
        firstPhysical?.let(events::add)
        if (latestPhysical != null && latestPhysical.sequence != firstPhysical?.sequence) {
            events += latestPhysical
        }
        window?.let(events::add)
        events.sortBy { it.sequence }
        Batch(events)
    }

    /** Returns true only when the current drainer can retire without losing an offered event. */
    fun finishDrainIfEmpty(): Boolean = synchronized(lock) {
        if (latestWindow != null || firstPhysicalDraw != null || latestPhysicalDraw != null) {
            false
        } else {
            drainScheduled = false
            true
        }
    }

    fun clear() = synchronized(lock) {
        latestWindow = null
        firstPhysicalDraw = null
        latestPhysicalDraw = null
        drainScheduled = false
    }

    private fun allocateSequenceLocked(): Long {
        val allocated = nextSequence
        nextSequence = if (allocated == Long.MAX_VALUE) 1L else allocated + 1L
        return allocated
    }

    private fun markDrainScheduledLocked(): Boolean {
        if (drainScheduled) return false
        drainScheduled = true
        return true
    }
}

object ReaderPipelinePolicy {
    const val FOREGROUND_NETWORK_PARALLELISM = 4
    const val IDLE_DECODE_PARALLELISM = 4
    const val BUSY_DECODE_PARALLELISM = 2
    // The verified lane is still source-only I/O, but it may admit only the viewport and the
    // bounded directional runway. More workers merely turn a manifest into hidden full-work.
    const val NTK_VERIFIED_SOURCE_FANOUT_PARALLELISM = 3
    // Far-tail work is opportunistic and must yield to every visible request. A single worker is
    // enough to keep the compressed-byte runway moving without competing with the visible lanes.
    const val NTK_PROGRESSIVE_FAR_TAIL_PARALLELISM = 1
    const val INITIAL_WINDOW_BEFORE = 0
    const val INITIAL_WINDOW_AFTER = 1
    const val BUSY_WINDOW_BEFORE = 1
    const val BUSY_WINDOW_AFTER = 3
    const val IDLE_WINDOW_BEFORE = 1
    const val IDLE_WINDOW_AFTER = 3
    // The strict cold qualification scrolls continuously toward later pages. Keep the current
    // visible span plus a bounded nine-page forward runway; no reverse-only bitmap capacity is
    // reserved. This still cannot admit the remainder of a long episode and cold entry remains
    // the exact anchor remains the first decode even though runway bytes arrive early.
    const val STRICT_ROLLING_WINDOW_BEHIND = 0
    const val STRICT_ROLLING_WINDOW_AHEAD = 9
    const val BUSY_DECODE_WIDTH = 1080

    @JvmStatic
    fun windowBefore(busy: Boolean): Int = if (busy) BUSY_WINDOW_BEFORE else IDLE_WINDOW_BEFORE

    @JvmStatic
    fun windowAfter(busy: Boolean): Int = if (busy) BUSY_WINDOW_AFTER else IDLE_WINDOW_AFTER

    @JvmStatic
    fun decodeParallelism(busy: Boolean): Int = if (busy) BUSY_DECODE_PARALLELISM else IDLE_DECODE_PARALLELISM

    @JvmStatic
    fun isNtkLaunchCriticalSource(pageIndex: Int, requiredLastPage: Int): Boolean {
        return pageIndex >= 0 && pageIndex <= requiredLastPage
    }

    /** Exact NTK cold demand keeps every forward source admitted; physical work stays bounded. */
    @JvmStatic
    fun strictExactColdDemandBounds(
        pageCount: Int,
        anchorPage: Int,
        direction: Int,
        firstActualPresented: Boolean
    ): IntArray {
        if (pageCount <= 0) return intArrayOf(-1, -1)
        val anchor = anchorPage.coerceIn(0, pageCount - 1)
        return strictExactColdVisibleDemandBounds(
            pageCount,
            anchor,
            anchor,
            direction,
            firstActualPresented
        )
    }

    @JvmStatic
    fun strictExactColdVisibleDemandBounds(
        pageCount: Int,
        firstVisibleSource: Int,
        lastVisibleSource: Int,
        direction: Int,
        firstActualPresented: Boolean
    ): IntArray {
        if (pageCount <= 0) return intArrayOf(-1, -1)
        if (!firstActualPresented) {
            return intArrayOf(0, pageCount - 1)
        }
        return intArrayOf(0, pageCount - 1)
    }

    /** A valid first physical image frame must not be mislabeled as the blank frame before it. */
    @JvmStatic
    fun shouldCountStrictInitialBlankFrame(
        actualPresentedInLifecycle: Boolean,
        blankOrRootCommit: Boolean
    ): Boolean = !actualPresentedInLifecycle && blankOrRootCommit

    /**
     * Fail-closed admission for one exact physical submission path. A legacy View frame requires
     * its HWUI commit callback; the dedicated Surface producer requires a successful hardware
     * buffer queue submission. Epoch zero means no page-table identity was ever established, and
     * drawable coverage alone must never be promoted to an actual-image event.
     */
    @JvmStatic
    @JvmOverloads
    fun isStrictCommittedFrameValid(
        sessionGenerationMatches: Boolean,
        telemetryGenerationMatches: Boolean,
        episodeMatches: Boolean,
        hardwareAccelerated: Boolean,
        registeredHwuiFrameCommitCallbackObserved: Boolean,
        surfaceQueueSubmissionObserved: Boolean,
        frameToken: Long,
        drawnVersion: Long,
        committedVersion: Long,
        proofStructureEpoch: Long,
        currentStructureEpoch: Long,
        hasVisiblePages: Boolean,
        viewportDefect: Boolean,
        surfaceControlLatchObserved: Boolean = false
    ): Boolean = sessionGenerationMatches && telemetryGenerationMatches && episodeMatches &&
        listOf(
            hardwareAccelerated && registeredHwuiFrameCommitCallbackObserved,
            surfaceQueueSubmissionObserved,
            surfaceControlLatchObserved
        ).count { it } == 1 &&
        frameToken > 0L && drawnVersion > 0L &&
        committedVersion >= drawnVersion && proofStructureEpoch > 0L &&
        proofStructureEpoch == currentStructureEpoch && hasVisiblePages && !viewportDefect

    /** Stable, fail-closed classification shared by strict frame admission and edge telemetry. */
    @JvmStatic
    fun strictViewportDefectReasons(
        physicalViewportPx: Int,
        viewportPx: Int,
        drawablePx: Int,
        missingPx: Int,
        placeholderPx: Int,
        visibleLoading: Int,
        visibleErrors: Int,
        visibleCards: Int,
        widthFillFailures: Int,
        lowResolutionItems: Int
    ): String {
        val reasons = ArrayList<String>(10)
        if (physicalViewportPx <= 0) reasons += "physicalViewport"
        if (viewportPx < physicalViewportPx) reasons += "viewportShort"
        if (drawablePx < physicalViewportPx) reasons += "drawableShort"
        if (missingPx != 0) reasons += "missing"
        if (placeholderPx != 0) reasons += "placeholder"
        if (visibleLoading != 0) reasons += "loading"
        if (visibleErrors != 0) reasons += "error"
        if (visibleCards != 0) reasons += "card"
        if (widthFillFailures != 0) reasons += "widthFill"
        if (lowResolutionItems != 0) reasons += "lowResolution"
        return reasons.joinToString("|")
    }

    @JvmStatic
    fun isStrictBottomEdgeEligible(
        pageCount: Int,
        lastVisiblePage: Int,
        defectReasons: String
    ): Boolean = pageCount > 0 && lastVisiblePage >= pageCount - 1 && defectReasons.isEmpty()
}
