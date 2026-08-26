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

/**
 * O(1) eligibility for a forward-resume scene whose immutable suffix is already on Surface.
 * This is deliberately narrower than the full-scene fast path: any reverse-floor expansion or
 * source-demand change must still request the historical prefix.
 */
object NtkStrictForwardSuffixFastPathPolicy {
    /** Source-count seals may use more display refs after auto-cut; only identity mapping is safe. */
    @JvmStatic
    fun hasCanonicalLaunchDisplayShape(
        displayIndexes: IntArray,
        sourceIndexes: IntArray,
        manifestPageCount: Int,
    ): Boolean {
        if (manifestPageCount <= 0 || displayIndexes.size != manifestPageCount ||
            sourceIndexes.size != manifestPageCount
        ) return false
        return displayIndexes.indices.all { ordinal ->
            displayIndexes[ordinal] == ordinal && sourceIndexes[ordinal] == ordinal
        }
    }

    @JvmStatic
    fun canQuery(
        rollingPixelResidency: Boolean,
        physicalDrawPresented: Boolean,
        sourceDemandChanged: Boolean,
        pageCount: Int,
        launchPageCount: Int,
        forwardSourceFloor: Int,
        activeSourceFloorBeforeProof: Int,
        allowedFirstSource: Int,
        allowedLastSource: Int,
    ): Boolean = !rollingPixelResidency &&
        physicalDrawPresented &&
        !sourceDemandChanged &&
        pageCount >= launchPageCount &&
        forwardSourceFloor in 1 until launchPageCount &&
        activeSourceFloorBeforeProof == forwardSourceFloor &&
        allowedFirstSource == forwardSourceFloor &&
        allowedLastSource == launchPageCount - 1

    @JvmStatic
    fun canCommit(
        rollingPixelResidency: Boolean,
        physicalDrawPresented: Boolean,
        sourceDemandChanged: Boolean,
        pageCount: Int,
        launchPageCount: Int,
        forwardSourceFloor: Int,
        activeSourceFloorBeforeProof: Int,
        activeSourceFloorAfterProof: Int,
        allowedFirstSource: Int,
        allowedLastSource: Int,
        suffixInstalled: Boolean,
    ): Boolean = suffixInstalled &&
        activeSourceFloorAfterProof == forwardSourceFloor &&
        canQuery(
            rollingPixelResidency,
            physicalDrawPresented,
            sourceDemandChanged,
            pageCount,
            launchPageCount,
            forwardSourceFloor,
            activeSourceFloorBeforeProof,
            allowedFirstSource,
            allowedLastSource,
        )

    /** Final lock-linearized proof check; a clear/reinstall must never ABA-match an old query. */
    @JvmStatic
    fun isCommitProofCurrent(
        capturedProofRevision: Long,
        currentProofRevision: Long,
        forwardSourceFloor: Int,
        activeSourceFloor: Int,
        launchShapeValid: Boolean,
        rollingPixelResidency: Boolean,
    ): Boolean = capturedProofRevision > 0L &&
        currentProofRevision == capturedProofRevision &&
        activeSourceFloor == forwardSourceFloor &&
        launchShapeValid &&
        !rollingPixelResidency
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
     * Keeps the physically visible sources hard and orders soft work nearest to the active
     * traversal direction. Once an explicit reverse gesture has widened [allowedFirstSource], a
     * later forward gesture must not let that historical prefix outrank the forward runway.
     */
    fun orderedSoftSources(hardSources: Collection<Int>): List<Int> {
        val hard = hardSources.filter(::admitsSource).toHashSet()
        val visibleFirst = hard.minOrNull() ?: allowedFirstSource
        val visibleLast = hard.maxOrNull() ?: visibleFirst
        val predecessors = (allowedFirstSource until visibleFirst)
            .filterNot(hard::contains)
            .sortedDescending()
        val successors = ((visibleLast + 1)..allowedLastSource)
            .filterNot(hard::contains)
        return if (direction < 0) predecessors + successors else successors + predecessors
    }

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

    /**
     * Source transport work changes only when its gate, direction priority, or admitted source
     * range changes. Visible display bounds still update decoded-pixel residency, but must not
     * create a new source epoch: boundary jitter otherwise cancels/requeues the same bodies for
     * every presented frame and makes long reading progressively janky.
     */
    fun hasSameSourceDemand(other: StrictRollingAdmission): Boolean =
        physicalDrawPresented == other.physicalDrawPresented &&
            direction == other.direction &&
            allowedFirstSource == other.allowedFirstSource &&
            allowedLastSource == other.allowedLastSource

    companion object {
        @JvmStatic
        @JvmOverloads
        fun initial(
            pageCount: Int,
            initialDisplay: Int = 0,
            initialSource: Int = 0,
        ): StrictRollingAdmission {
            require(pageCount > 0)
            val anchor = initialDisplay.coerceIn(0, pageCount - 1)
            val sourceFloor = initialSource.coerceIn(0, pageCount - 1)
            return StrictRollingAdmission(
                epoch = 0L,
                physicalDrawPresented = false,
                visibleFirstDisplay = anchor,
                visibleLastDisplay = anchor,
                direction = 1,
                allowedFirstSource = sourceFloor,
                allowedLastSource = pageCount - 1
            )
        }

        @JvmStatic
        @JvmOverloads
        fun update(
            previous: StrictRollingAdmission,
            pageCount: Int,
            visibleFirstDisplay: Int,
            visibleLastDisplay: Int,
            visibleFirstSource: Int,
            visibleLastSource: Int,
            direction: Int,
            physicalDrawPresented: Boolean,
            allowReverseExpansion: Boolean = false,
            reverseSourceFloor: Int = (visibleFirstSource - REVERSE_PREDECESSOR_SOURCE_COUNT)
        ): StrictRollingAdmission {
            require(pageCount > 0)
            val safeDirection = if (direction < 0) -1 else 1
            val previousFloor = previous.allowedFirstSource.coerceIn(0, pageCount - 1)
            // A saved resume source is the immutable launch floor. Only a caller that has already
            // observed a physical busy reverse offset may monotonically widen it downward. Never
            // reclaim the widened prefix on a later forward gesture: doing so can clear a source
            // while its reverse re-decode is still crossing the delivery/Surface hand-off.
            val sourceFloor = if (
                physicalDrawPresented && allowReverseExpansion && safeDirection < 0
            ) {
                minOf(previousFloor, reverseSourceFloor.coerceIn(0, pageCount - 1))
            } else {
                previousFloor
            }
            val candidate = if (!physicalDrawPresented) {
                // Before the first compositor proof, direction is evidence only. Preserve the
                // original resume admission and its forward launch semantics.
                initial(pageCount, previous.visibleFirstDisplay, previousFloor).copy(
                    visibleLastDisplay = previous.visibleLastDisplay
                )
            } else {
                StrictRollingAdmission(
                    epoch = 0L,
                    physicalDrawPresented = true,
                    visibleFirstDisplay = visibleFirstDisplay,
                    visibleLastDisplay = visibleLastDisplay,
                    direction = safeDirection,
                    allowedFirstSource = sourceFloor,
                    allowedLastSource = pageCount - 1
                )
            }
            // startStrictExactColdRolling already binds and submits the epoch-zero forward runway.
            // A first layout callback with that same window is evidence only, just like every
            // later repeated compositor commit.
            if (previous.hasSameDemand(candidate)) return previous
            return candidate.copy(
                epoch = if (previous.hasSameSourceDemand(candidate)) {
                    previous.epoch
                } else {
                    previous.epoch + 1L
                }
            )
        }

        /**
         * Converts an observed busy reverse viewport into a bounded source-floor expansion. Some
         * Android input streams coalesce the explicit direction hint, but the decreasing physical
         * anchor still proves that the user is dragging toward older pixels. The busy bit comes
         * from the Surface's active drag/fling state, so idle/programmatic callbacks can never
         * open the saved resume prefix. Do not require a separate touch level here: ACTION_UP can
         * legitimately reach the control lane before its coalesced final busy viewport.
         */
        @JvmStatic
        fun observedPhysicalReverseFloor(
            currentFloor: Int,
            visibleFirstSource: Int,
            direction: Int,
            windowBusy: Boolean,
        ): Int {
            val boundedCurrent = currentFloor.coerceAtLeast(0)
            if (direction >= 0 || !windowBusy) return boundedCurrent
            return minOf(
                boundedCurrent,
                (visibleFirstSource - REVERSE_PREDECESSOR_SOURCE_COUNT).coerceAtLeast(0),
            )
        }

        const val REVERSE_PREDECESSOR_SOURCE_COUNT = 3
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
        val busy: Boolean,
        val directionHint: Int,
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

    @JvmOverloads
    fun offerWindow(
        first: Int,
        last: Int,
        anchor: Int,
        busy: Boolean,
        directionHint: Int = 0,
    ): Boolean = synchronized(lock) {
        latestWindow = WindowEvent(
            allocateSequenceLocked(),
            first,
            last,
            anchor,
            busy,
            directionHint.coerceIn(-1, 1),
        )
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

/**
 * Suppresses pixel-only viewport repeats before they allocate control events or wake ReaderControl.
 * The structure owner publishes a new immutable index object for every page-table mutation, so
 * reference identity makes an equal numeric window new again whenever its page meaning changes.
 */
internal class PublishedWindowIngressGate<T : Any> {
    private class Stamp<T : Any>(
        val structure: T,
        val first: Int,
        val last: Int,
        val anchor: Int,
        val physicalFirst: Int,
        val physicalLast: Int,
        val busy: Boolean,
        val directionHint: Int,
    ) {
        fun matches(
            candidateStructure: T,
            candidateFirst: Int,
            candidateLast: Int,
            candidateAnchor: Int,
            candidatePhysicalFirst: Int,
            candidatePhysicalLast: Int,
            candidateBusy: Boolean,
            candidateDirectionHint: Int,
        ): Boolean = structure === candidateStructure &&
            first == candidateFirst && last == candidateLast && anchor == candidateAnchor &&
            physicalFirst == candidatePhysicalFirst && physicalLast == candidatePhysicalLast &&
            busy == candidateBusy && directionHint == candidateDirectionHint
    }

    private val latest = java.util.concurrent.atomic.AtomicReference<Stamp<T>?>(null)

    fun reserve(
        structure: T,
        first: Int,
        last: Int,
        anchor: Int,
        physicalFirst: Int,
        physicalLast: Int,
        busy: Boolean,
        directionHint: Int,
    ): Boolean {
        val boundedDirection = directionHint.coerceIn(-1, 1)
        while (true) {
            val current = latest.get()
            if (current?.matches(
                    structure,
                    first,
                    last,
                    anchor,
                    physicalFirst,
                    physicalLast,
                    busy,
                    boundedDirection,
                ) == true
            ) {
                return false
            }
            val replacement = Stamp(
                structure,
                first,
                last,
                anchor,
                physicalFirst,
                physicalLast,
                busy,
                boundedDirection,
            )
            if (latest.compareAndSet(current, replacement)) return true
        }
    }

    fun clear() {
        latest.set(null)
    }
}

object ReaderPipelinePolicy {
    const val FOREGROUND_NETWORK_PARALLELISM = 4
    const val IDLE_DECODE_PARALLELISM = 4
    const val BUSY_DECODE_PARALLELISM = 2
    // Protected numeric pages can be decoded broadly on a physical device. On the host-GPU
    // emulator, however, each large ARGB allocation also drives gfxstream/native-allocation GC.
    // Serializing those decodes keeps the immutable byte runway intact while preventing concurrent
    // large bitmap allocations from repeatedly forcing native-allocation GC on the UI/renderer.
    const val NTK_PROTECTED_NUMERIC_DECODE_PARALLELISM = 12
    const val HOST_GPU_NTK_PROTECTED_NUMERIC_DECODE_PARALLELISM = 1
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
    fun protectedNumericDecodeParallelism(hostGpuEmulatorRuntime: Boolean): Int =
        if (hostGpuEmulatorRuntime) {
            HOST_GPU_NTK_PROTECTED_NUMERIC_DECODE_PARALLELISM
        } else {
            NTK_PROTECTED_NUMERIC_DECODE_PARALLELISM
        }

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

    /**
     * A short terminal image is honest actual content, even though it cannot fill the viewport.
     * Surface ownership and exact source identity are proven separately before this policy is
     * consulted. Keep the shape fail-closed so every unresolved/card/error/width defect remains
     * a strict failure. This exception records when the user really saw the current terminal
     * source; it never substitutes for the separately measured adjacent-image presentation.
     */
    @JvmStatic
    fun isExactForwardOnlyTerminalTailActualFrame(
        surfaceQualified: Boolean,
        physicalViewportPx: Int,
        viewportPx: Int,
        drawablePx: Int,
        defectReasons: String
    ): Boolean = surfaceQualified && physicalViewportPx > 0 &&
        viewportPx > 0 && drawablePx > 0 &&
        kotlin.math.abs(viewportPx.toLong() - drawablePx.toLong()) <= 1L &&
        viewportPx < physicalViewportPx &&
        defectReasons == "viewportShort|drawableShort"

    /**
     * Verifies the immutable source ordinals carried by a physically submitted current-tail
     * frame. Exact URL/manifest/generation ownership is checked by the caller before this pure
     * ordinal policy is consulted. Duplicate source ordinals are allowed for a legitimate
     * auto-cut pair, but gaps, regression, a non-resume floor, and a non-terminal suffix are not.
     */
    @JvmStatic
    fun isExactForwardOnlyTerminalTailSourceSequence(
        hostGpuForwardOnlyResumeProfile: Boolean,
        resumeSourceFloor: Int,
        manifestPageCount: Int,
        sourceIndexes: IntArray,
    ): Boolean {
        if (!hostGpuForwardOnlyResumeProfile || resumeSourceFloor <= 0 ||
            manifestPageCount <= resumeSourceFloor || sourceIndexes.isEmpty()
        ) return false
        var previous = -1
        for (source in sourceIndexes) {
            if (source !in resumeSourceFloor until manifestPageCount ||
                (previous >= 0 && (source < previous || source > previous + 1))
            ) return false
            previous = source
        }
        return previous == manifestPageCount - 1
    }

    /**
     * A transition card is invalid before the first real image, but becomes intentional reader
     * content after image ownership has been established. This keeps cold first-image proof strict
     * while allowing the lightweight between-episode label in continuous forward reading.
     */
    @JvmStatic
    fun strictTransitionCardDefectCount(
        visibleCards: Int,
        actualImagePreviouslyCommitted: Boolean
    ): Int = if (actualImagePreviouslyCommitted) 0 else visibleCards.coerceAtLeast(0)

    /**
     * A source-qualified direct-Wi-Fi terminal resume may first commit the exact current tail and
     * its single forward transition together. That is not the placeholder-before-image case the
     * two-argument policy rejects.
     */
    @JvmStatic
    fun strictTransitionCardDefectCount(
        visibleCards: Int,
        actualImagePreviouslyCommitted: Boolean,
        directWifiForwardOnlyInitialResume: Boolean
    ): Int = if (directWifiForwardOnlyInitialResume) {
        0
    } else {
        strictTransitionCardDefectCount(visibleCards, actualImagePreviouslyCommitted)
    }

    @JvmStatic
    fun isStrictBottomEdgeEligible(
        pageCount: Int,
        lastVisiblePage: Int,
        defectReasons: String
    ): Boolean = pageCount > 0 && lastVisiblePage >= pageCount - 1 && defectReasons.isEmpty()
}
