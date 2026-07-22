package ml.melun.mangaview.reader

/**
 * Android-free reducer for the strict target lifecycle.
 *
 * A plan may reserve data work before a window target exists. The only legal target sequence is:
 * exact plan + committed episode shell -> detached warm -> SurfaceView install -> exact published
 * surface. Exact manifest ownership permits the surface-independent source claim immediately;
 * the surface join remains a presentation-only transition.
 */
internal class NtkSurfaceDemandProtocol {
    data class Demand(
        val generation: Long,
        val planGeneration: Int,
        val planProofDigest: String
    )

    data class Transition(
        val demand: Demand? = null,
        val startDetachedWarm: Boolean = false,
        val installSurfaceView: Boolean = false,
        val promoteManifestSurfaceJoin: Boolean = false,
        val revokeSurface: Boolean = false,
        val closeEngine: Boolean = false
    )

    data class Snapshot(
        val demandGeneration: Long,
        val planGeneration: Int,
        val planProofDigest: String,
        val planReservedNanos: Long,
        val shellFrameCommitted: Boolean,
        val shellFrameCommitNanos: Long,
        val detachedWarmStarted: Boolean,
        val detachedEngineGeneration: Long,
        val detachedWarmReady: Boolean,
        val surfaceViewConstructed: Boolean,
        val surfaceViewInstalled: Boolean,
        val publishedSurfaceEpoch: Long,
        val manifestOwned: Boolean,
        val sourceClaimed: Boolean,
        val destroyed: Boolean
    )

    private var nextDemandGeneration = 0L
    private var demand: Demand? = null
    private var planReservedNanos = 0L
    private var shellFrameCommitNanos = 0L
    private var warmStarted = false
    private var detachedEngineGeneration = 0L
    private var warmReady = false
    private var surfaceConstructed = false
    private var surfaceInstalled = false
    private var publishedSurfaceEpoch = 0L
    private var manifestOwned = false
    private var sourceClaimed = false
    private var joinPromoted = false
    private var destroyed = false

    fun reservePlan(
        planGeneration: Int,
        planProofDigest: String,
        reservedNanos: Long
    ): Transition {
        require(planGeneration > 0)
        require(planProofDigest.isNotBlank())
        require(reservedNanos > 0L)
        if (destroyed) return Transition()
        val current = demand
        if (current != null &&
            current.planGeneration == planGeneration &&
            current.planProofDigest == planProofDigest
        ) {
            return maybeStartWarm(current)
        }
        nextDemandGeneration++
        demand = Demand(nextDemandGeneration, planGeneration, planProofDigest)
        planReservedNanos = reservedNanos
        warmStarted = false
        detachedEngineGeneration = 0L
        warmReady = false
        surfaceConstructed = false
        surfaceInstalled = false
        publishedSurfaceEpoch = 0L
        manifestOwned = false
        sourceClaimed = false
        joinPromoted = false
        return maybeStartWarm(checkNotNull(demand))
    }

    fun onShellFrameCommitted(commitNanos: Long): Transition {
        require(commitNanos > 0L)
        if (destroyed) return Transition()
        if (shellFrameCommitNanos == 0L) shellFrameCommitNanos = commitNanos
        return demand?.let(::maybeStartWarm) ?: Transition()
    }

    fun bindDetachedEngine(demandGeneration: Long, engineGeneration: Long): Boolean {
        if (destroyed || engineGeneration <= 0L) return false
        val current = demand ?: return false
        if (current.generation != demandGeneration || !warmStarted ||
            detachedEngineGeneration != 0L
        ) return false
        detachedEngineGeneration = engineGeneration
        return true
    }

    fun onDetachedWarmReady(
        demandGeneration: Long,
        engineGeneration: Long
    ): Transition {
        if (destroyed) return Transition()
        val current = demand ?: return Transition()
        if (current.generation != demandGeneration || !warmStarted ||
            engineGeneration <= 0L || detachedEngineGeneration != engineGeneration ||
            warmReady
        ) return Transition()
        warmReady = true
        return Transition(demand = current, installSurfaceView = !surfaceInstalled)
    }

    fun onSurfaceViewConstructed(demandGeneration: Long): Boolean {
        val current = demand ?: return false
        if (destroyed || current.generation != demandGeneration || !warmReady ||
            surfaceConstructed
        ) return false
        surfaceConstructed = true
        return true
    }

    fun onSurfaceViewInstalled(demandGeneration: Long): Boolean {
        val current = demand ?: return false
        if (destroyed || current.generation != demandGeneration || !surfaceConstructed ||
            surfaceInstalled
        ) return false
        surfaceInstalled = true
        return true
    }

    fun onSurfacePublished(
        demandGeneration: Long,
        engineGeneration: Long,
        surfaceEpoch: Long
    ): Transition {
        val current = demand ?: return Transition()
        if (destroyed || current.generation != demandGeneration || !surfaceInstalled ||
            engineGeneration != detachedEngineGeneration || surfaceEpoch <= 0L
        ) return Transition()
        if (publishedSurfaceEpoch != 0L && publishedSurfaceEpoch != surfaceEpoch) {
            return Transition()
        }
        publishedSurfaceEpoch = surfaceEpoch
        return maybePromote(current)
    }

    fun onManifestOwned(
        planGeneration: Int,
        planProofDigest: String
    ): Transition {
        val current = demand ?: return Transition()
        if (destroyed || current.planGeneration != planGeneration ||
            current.planProofDigest != planProofDigest
        ) return Transition()
        manifestOwned = true
        return maybePromote(current)
    }

    fun markSourceClaimed(demandGeneration: Long): Boolean {
        val current = demand ?: return false
        if (destroyed || current.generation != demandGeneration || !manifestOwned ||
            sourceClaimed
        ) return false
        sourceClaimed = true
        return true
    }

    fun revokeCurrentSurface(demandGeneration: Long): Transition {
        val current = demand ?: return Transition()
        if (destroyed || current.generation != demandGeneration) return Transition()
        val hadSurface = publishedSurfaceEpoch > 0L || surfaceInstalled
        publishedSurfaceEpoch = 0L
        manifestOwned = false
        sourceClaimed = false
        joinPromoted = false
        return Transition(demand = current, revokeSurface = hadSurface)
    }

    fun normalExit(): Transition {
        val current = demand
        manifestOwned = false
        sourceClaimed = false
        joinPromoted = false
        publishedSurfaceEpoch = 0L
        return Transition(demand = current, revokeSurface = current != null && surfaceInstalled)
    }

    fun destroy(): Transition {
        if (destroyed) return Transition()
        destroyed = true
        val close = detachedEngineGeneration > 0L
        demand = null
        planReservedNanos = 0L
        warmStarted = false
        warmReady = false
        surfaceConstructed = false
        surfaceInstalled = false
        publishedSurfaceEpoch = 0L
        manifestOwned = false
        sourceClaimed = false
        joinPromoted = false
        return Transition(revokeSurface = close, closeEngine = close)
    }

    fun currentDemand(): Demand? = demand

    fun snapshot(): Snapshot {
        val current = demand
        return Snapshot(
            demandGeneration = current?.generation ?: 0L,
            planGeneration = current?.planGeneration ?: 0,
            planProofDigest = current?.planProofDigest.orEmpty(),
            planReservedNanos = planReservedNanos,
            shellFrameCommitted = shellFrameCommitNanos > 0L,
            shellFrameCommitNanos = shellFrameCommitNanos,
            detachedWarmStarted = warmStarted,
            detachedEngineGeneration = detachedEngineGeneration,
            detachedWarmReady = warmReady,
            surfaceViewConstructed = surfaceConstructed,
            surfaceViewInstalled = surfaceInstalled,
            publishedSurfaceEpoch = publishedSurfaceEpoch,
            manifestOwned = manifestOwned,
            sourceClaimed = sourceClaimed,
            destroyed = destroyed
        )
    }

    private fun maybeStartWarm(current: Demand): Transition {
        if (shellFrameCommitNanos <= 0L || warmStarted) return Transition(demand = current)
        warmStarted = true
        return Transition(demand = current, startDetachedWarm = true)
    }

    private fun maybePromote(current: Demand): Transition {
        if (joinPromoted || !manifestOwned || publishedSurfaceEpoch <= 0L) {
            return Transition(demand = current)
        }
        joinPromoted = true
        return Transition(demand = current, promoteManifestSurfaceJoin = true)
    }
}
