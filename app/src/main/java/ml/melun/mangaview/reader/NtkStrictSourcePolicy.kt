package ml.melun.mangaview.reader

import java.io.IOException

/** Physical network lanes are identical; these labels only order never-started pages. */
enum class NtkSourceOperationLane {
    METADATA,
    STAGE,
    URGENT,
    TARGET,
    BACKGROUND_PROOF;

    val isForegroundDemand: Boolean
        get() = this == STAGE || this == URGENT || this == TARGET
}

object NtkSourceLanePolicy {
    /**
     * A finite episode cardinality bound is not a concurrency limit. Production catalogs contain
     * 121-270 page volumes, so retain their complete authoritative table while the executor below
     * rolls a smaller fixed active window over it.
     */
    const val MAX_EPISODE_PAGES = 384

    /**
     * The demanded exact bodies share one HttpEngine/HTTP3 connection per CDN origin. Keep a
     * bounded full forward wave so a long strip does not serialize behind completed pages; the
     * engine multiplexes these requests without opening one TCP/TLS connection per worker.
     */
    const val MAX_NETWORK_OPERATIONS = 120

    /**
     * Webtoon work is striped over three origins and stays bounded at 40 bodies per origin.
     */
    const val MAX_NETWORK_OPERATIONS_PER_ROUTE = 40

    /**
     * Manhwa uses six click-owned H2 sessions to one signed route. A normal 20-30 page book still
     * remains one bounded wave.
     */
    const val MAX_MANHWA_NETWORK_OPERATIONS_PER_ROUTE = 40
}

enum class NtkPreGeometryPhase { STAGE_RUNWAY, METADATA_DRAIN }

object NtkPreGeometryAdmissionPolicy {
    @JvmStatic
    fun phase(stageRunwayClosed: Boolean): NtkPreGeometryPhase =
        if (stageRunwayClosed) NtkPreGeometryPhase.METADATA_DRAIN
        else NtkPreGeometryPhase.STAGE_RUNWAY

    @JvmStatic
    fun canCloseStageRunway(
        geometrySealed: Boolean,
        stageBodyQueueDepth: Int,
        activeStageBodies: Int,
        unsettledStagePages: Int
    ): Boolean = !geometrySealed && stageBodyQueueDepth == 0 &&
        activeStageBodies == 0 && unsettledStagePages == 0
}

data class NtkPreGeometrySourcePlan(
    val revision: Long,
    val priorities: Map<Int, Int>,
    val lanes: Map<Int, NtkSourceOperationLane>
)

object NtkPreGeometrySourcePlanner {
    @JvmStatic
    @JvmOverloads
    fun create(initialPageIndex: Int, pageCount: Int, revision: Long = 0L): NtkPreGeometrySourcePlan {
        require(pageCount > 0 && initialPageIndex in 0 until pageCount && revision >= 0L)
        val order = buildList(pageCount) {
            // Reading is a forward traversal. Fill the entire downward path before assigning any
            // priority to pages behind a resumed bookmark; alternating forward/backward wasted
            // cold lanes on content the user is unlikely to revisit.
            addAll(initialPageIndex until pageCount)
            addAll(0 until initialPageIndex)
        }
        val priorities = LinkedHashMap<Int, Int>(pageCount)
        val lanes = LinkedHashMap<Int, NtkSourceOperationLane>(pageCount)
        order.forEachIndexed { index, page ->
            priorities[page] = pageCount - index
            lanes[page] = if (index < NtkSourceLanePolicy.MAX_NETWORK_OPERATIONS) {
                NtkSourceOperationLane.STAGE
            } else {
                NtkSourceOperationLane.METADATA
            }
        }
        return NtkPreGeometrySourcePlan(revision, priorities, lanes)
    }
}

enum class NtkSourceDemandClass { HARD, SOFT, BACKGROUND }

class NtkSourceDemandSnapshot(
    val authority: Long,
    val demandEpoch: Long,
    hardPages: IntArray,
    softPages: IntArray,
    backgroundPages: IntArray
) {
    private val hardStorage = hardPages.copyOf()
    private val softStorage = softPages.copyOf()
    private val backgroundStorage = backgroundPages.copyOf()
    private val hardOrder = hardStorage.withIndex().associate { it.value to it.index }
    private val softOrder = softStorage.withIndex().associate { it.value to it.index }
    private val backgroundOrder = backgroundStorage.withIndex().associate { it.value to it.index }

    init {
        require(authority > 0L && demandEpoch >= 0L)
        val all = orderedPages()
        require(all.all { it >= 0 } && all.toSet().size == all.size)
    }

    val hardPages: IntArray get() = hardStorage.copyOf()
    val softPages: IntArray get() = softStorage.copyOf()
    val backgroundPages: IntArray get() = backgroundStorage.copyOf()

    fun demandClass(pageIndex: Int): NtkSourceDemandClass = when {
        hardOrder.containsKey(pageIndex) -> NtkSourceDemandClass.HARD
        softOrder.containsKey(pageIndex) -> NtkSourceDemandClass.SOFT
        backgroundOrder.containsKey(pageIndex) -> NtkSourceDemandClass.BACKGROUND
        else -> throw IllegalArgumentException("Page is absent from strict source demand")
    }

    fun priorityIndex(pageIndex: Int): Int = hardOrder[pageIndex]
        ?: softOrder[pageIndex]
        ?: backgroundOrder[pageIndex]
        ?: Int.MAX_VALUE

    fun orderedPages(): IntArray = hardStorage + softStorage + backgroundStorage

    override fun equals(other: Any?): Boolean = other is NtkSourceDemandSnapshot &&
        authority == other.authority && demandEpoch == other.demandEpoch &&
        hardStorage.contentEquals(other.hardStorage) &&
        softStorage.contentEquals(other.softStorage) &&
        backgroundStorage.contentEquals(other.backgroundStorage)

    override fun hashCode(): Int {
        var result = authority.hashCode()
        result = 31 * result + demandEpoch.hashCode()
        result = 31 * result + hardStorage.contentHashCode()
        result = 31 * result + softStorage.contentHashCode()
        return 31 * result + backgroundStorage.contentHashCode()
    }
}

internal enum class NtkSourceDemandOfferDecision { ACCEPT, IDEMPOTENT, STALE, CONFLICT }

/** Fail-closed epoch admission used before a source demand reaches the session actor mailbox. */
internal class NtkSourceDemandEpochGate {
    private var latestEpisode: NtkEpisodeToken? = null
    private var latestDemand: NtkSourceDemandSnapshot? = null

    @Synchronized
    fun offer(
        episode: NtkEpisodeToken,
        candidate: NtkSourceDemandSnapshot
    ): NtkSourceDemandOfferDecision {
        val latest = latestDemand
        if (latest == null || candidate.demandEpoch > latest.demandEpoch) {
            latestEpisode = episode
            latestDemand = candidate
            return NtkSourceDemandOfferDecision.ACCEPT
        }
        if (candidate.demandEpoch < latest.demandEpoch) {
            return NtkSourceDemandOfferDecision.STALE
        }
        return if (latestEpisode == episode && latest == candidate) {
            NtkSourceDemandOfferDecision.IDEMPOTENT
        } else {
            NtkSourceDemandOfferDecision.CONFLICT
        }
    }
}

open class NtkTerminalSourceException(
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)

class NtkSourceIdentityException(message: String) : NtkTerminalSourceException(message)
class NtkEncodedShaMismatchException(message: String) : NtkTerminalSourceException(message)

/**
 * A transport interruption is scoped to one body operation. It must not revoke the immutable
 * episode authority: that would make an ordinary timeout or demand cancellation permanently
 * poison reverse scrolling. Integrity/identity failures remain terminal and are never retried.
 */
object NtkStrictSourceFailurePolicy {
    // One logical attempt already cycles every immutable replica, but a temporary CDN reset storm
    // must not permanently poison a page. This remains finite for ownership/accounting invariants
    // while giving a live viewer enough recovery opportunities to finish after network pressure
    // subsides. Identity, integrity and terminal HTTP failures are still rejected immediately.
    const val MAX_PHYSICAL_ATTEMPTS = 32
    // Never recycle an exhausted operation ledger indefinitely. Thirty-two complete logical
    // attempts already cover transient DNS/socket pressure; opening unbounded new ledgers leaves
    // a permanently dead single-origin page spinning forever and prevents an honest terminal
    // image error from reaching the viewer.
    const val MAX_PHYSICAL_RECOVERY_CYCLES = 0

    @JvmStatic
    fun isRecoverablePhysicalFailure(failure: Throwable, attemptOrdinal: Int): Boolean {
        require(attemptOrdinal > 0)
        if (attemptOrdinal >= MAX_PHYSICAL_ATTEMPTS) return false
        return isRetryableTransportFailure(failure)
    }

    /**
     * Identity/integrity errors remain terminal. Transport errors may retry inside the finite
     * attempt ledger; whether another ledger may open is decided separately and remains bounded.
     */
    @JvmStatic
    fun isRetryableTransportFailure(failure: Throwable): Boolean {
        var cursor: Throwable? = failure
        var sawIo = false
        val seen = HashSet<Throwable>()
        while (cursor != null && seen.add(cursor)) {
            if (cursor is NtkTerminalSourceException) return false
            if (cursor is IOException) sawIo = true
            cursor = cursor.cause
        }
        return sawIo
    }

    @JvmStatic
    fun shouldRetryPhysicalFailure(
        failure: Throwable,
        attemptOrdinal: Int,
        completedRecoveryCycles: Int
    ): Boolean {
        require(attemptOrdinal > 0)
        require(completedRecoveryCycles >= 0)
        if (isRecoverablePhysicalFailure(failure, attemptOrdinal)) return true
        return attemptOrdinal >= MAX_PHYSICAL_ATTEMPTS &&
            completedRecoveryCycles < MAX_PHYSICAL_RECOVERY_CYCLES &&
            isRetryableTransportFailure(failure)
    }

    @JvmStatic
    fun retryDelayMs(attemptOrdinal: Int, recoveryCycle: Int): Long {
        require(attemptOrdinal > 0)
        require(recoveryCycle >= 0)
        if (attemptOrdinal <= 3 && recoveryCycle == 0) return 0L
        val attemptDelay = ((attemptOrdinal - 3).coerceAtLeast(1) * 50L)
            .coerceAtMost(1_000L)
        val cycleDelay = (recoveryCycle * 500L).coerceAtMost(3_000L)
        return maxOf(attemptDelay, cycleDelay)
    }
}
