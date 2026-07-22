package ml.melun.mangaview.reader

/**
 * Android-free executable model of the SurfaceView handoff reducer.
 *
 * Production owns the actual native lease and engine calls, while this model fixes the legal
 * ordering and exact-identity decisions used by deterministic JVM tests.
 */
internal class NtkSurfaceHandoffStateMachine<L>(
    initialEngineGeneration: Long,
    private val releaseLease: (L) -> Unit
) {
    data class Candidate<L>(
        val surfaceEpoch: Long,
        val refreshPeriodNanos: Long,
        var width: Int,
        var height: Int,
        var geometryRevision: Long,
        var lease: L?,
        var holderAlive: Boolean
    )

    sealed interface EngineState {
        data class Attaching(
            val key: NtkSurfaceAttachKey,
            var requestedGeometryRevision: Long
        ) : EngineState

        data class Ready(
            val key: NtkSurfaceAttachKey,
            val appliedGeometryRevision: Long,
            val width: Int,
            val height: Int
        ) : EngineState

        data class Published(val identity: NtkPublishedSurfaceIdentity) : EngineState
        data class Detaching(
            val key: NtkSurfaceAttachKey,
            val wasPublished: Boolean
        ) : EngineState
    }

    data class AttachSubmission<L>(
        val lease: L,
        val surfaceEpoch: Long,
        val refreshPeriodNanos: Long,
        val width: Int,
        val height: Int,
        val geometryRevision: Long
    )

    sealed interface ChangeAction {
        data object None : ChangeAction
        data object DriveAttach : ChangeAction
        data class UpdateAttach(
            val key: NtkSurfaceAttachKey,
            val width: Int,
            val height: Int,
            val geometryRevision: Long
        ) : ChangeAction
        data class ResizePublished(
            val identity: NtkPublishedSurfaceIdentity,
            val width: Int,
            val height: Int,
            val geometryRevision: Long
        ) : ChangeAction
    }

    sealed interface ReadyAction {
        data object Stale : ReadyAction
        data class ResizeBarrier(
            val key: NtkSurfaceAttachKey,
            val width: Int,
            val height: Int,
            val geometryRevision: Long
        ) : ReadyAction
        data class Publish(
            val key: NtkSurfaceAttachKey,
            val geometryRevision: Long,
            val width: Int,
            val height: Int
        ) : ReadyAction
    }

    sealed interface DestroyAction {
        data object LeaseOnly : DestroyAction
        data object AlreadyDetaching : DestroyAction
        data class Revoke(
            val key: NtkSurfaceAttachKey,
            val published: NtkPublishedSurfaceIdentity?
        ) : DestroyAction
    }

    var engineGeneration: Long = initialEngineGeneration
        private set
    var candidate: Candidate<L>? = null
        private set
    var engineState: EngineState? = null
        private set
    var published: NtkPublishedSurfaceIdentity? = null
        private set
    var terminalFailure: NtkSurfaceAttachFailure? = null
        private set
    var closing: Boolean = false
        private set

    init {
        require(initialEngineGeneration > 0L)
    }

    fun created(
        surfaceEpoch: Long,
        refreshPeriodNanos: Long,
        width: Int,
        height: Int,
        lease: L
    ): Boolean {
        require(surfaceEpoch > 0L && refreshPeriodNanos > 0L)
        if (closing || terminalFailure != null) {
            releaseLease(lease)
            return false
        }
        candidate?.lease?.let(releaseLease)
        candidate = Candidate(
            surfaceEpoch,
            refreshPeriodNanos,
            width,
            height,
            1L,
            lease,
            true
        )
        return true
    }

    fun changed(width: Int, height: Int): ChangeAction {
        if (width <= 0 || height <= 0 || terminalFailure != null || closing) {
            return ChangeAction.None
        }
        val current = candidate ?: return ChangeAction.None
        if (!current.holderAlive) return ChangeAction.None
        if (current.width == width && current.height == height) {
            return ChangeAction.None
        }
        current.width = width
        current.height = height
        current.geometryRevision++
        return when (val state = engineState) {
            null -> ChangeAction.DriveAttach
            is EngineState.Attaching -> {
                if (state.key.surfaceEpoch != current.surfaceEpoch) ChangeAction.None
                else {
                    state.requestedGeometryRevision = current.geometryRevision
                    ChangeAction.UpdateAttach(
                        state.key,
                        width,
                        height,
                        current.geometryRevision
                    )
                }
            }
            is EngineState.Ready -> {
                if (state.key.surfaceEpoch != current.surfaceEpoch) ChangeAction.None
                else ChangeAction.UpdateAttach(
                    state.key,
                    width,
                    height,
                    current.geometryRevision
                )
            }
            is EngineState.Published -> {
                if (state.identity.surfaceEpoch != current.surfaceEpoch) ChangeAction.None
                else ChangeAction.ResizePublished(
                    state.identity,
                    width,
                    height,
                    current.geometryRevision
                )
            }
            is EngineState.Detaching -> ChangeAction.None
        }
    }

    fun takeAttachSubmission(): AttachSubmission<L>? {
        val current = candidate ?: return null
        if (closing || terminalFailure != null || !current.holderAlive ||
            current.width <= 0 || current.height <= 0 || engineState != null ||
            engineGeneration <= 0L
        ) return null
        val lease = current.lease ?: return null
        current.lease = null
        return AttachSubmission(
            lease,
            current.surfaceEpoch,
            current.refreshPeriodNanos,
            current.width,
            current.height,
            current.geometryRevision
        )
    }

    fun attachSubmitted(key: NtkSurfaceAttachKey): Boolean {
        val current = candidate ?: return false
        if (engineState != null || key.engineGeneration != engineGeneration ||
            key.surfaceEpoch != current.surfaceEpoch
        ) return false
        engineState = EngineState.Attaching(key, current.geometryRevision)
        return true
    }

    fun ready(
        key: NtkSurfaceAttachKey,
        appliedGeometryRevision: Long,
        width: Int,
        height: Int
    ): ReadyAction {
        val state = engineState as? EngineState.Attaching ?: return ReadyAction.Stale
        val current = candidate ?: return ReadyAction.Stale
        if (state.key != key || key.engineGeneration != engineGeneration ||
            key.surfaceEpoch != current.surfaceEpoch || !current.holderAlive
        ) return ReadyAction.Stale
        engineState = EngineState.Ready(
            key,
            appliedGeometryRevision,
            width,
            height
        )
        return if (current.geometryRevision == appliedGeometryRevision) {
            ReadyAction.Publish(
                key,
                appliedGeometryRevision,
                width,
                height
            )
        } else {
            ReadyAction.ResizeBarrier(
                key,
                current.width,
                current.height,
                current.geometryRevision
            )
        }
    }

    fun published(identity: NtkPublishedSurfaceIdentity): Boolean {
        val state = engineState as? EngineState.Ready ?: return false
        val current = candidate ?: return false
        if (state.key.engineGeneration != identity.engineGeneration ||
            state.key.attachGeneration != identity.attachGeneration ||
            state.key.surfaceEpoch != identity.surfaceEpoch ||
            identity.engineGeneration != engineGeneration ||
            identity.surfaceEpoch != current.surfaceEpoch ||
            identity.geometryRevision != current.geometryRevision
        ) return false
        published = identity
        engineState = EngineState.Published(identity)
        return true
    }

    fun destroyed(): DestroyAction {
        val current = candidate
        current?.holderAlive = false
        current?.lease?.let(releaseLease)
        current?.lease = null
        candidate = null
        return when (val state = engineState) {
            null -> DestroyAction.LeaseOnly
            is EngineState.Detaching -> DestroyAction.AlreadyDetaching
            is EngineState.Attaching -> {
                engineState = EngineState.Detaching(state.key, false)
                DestroyAction.Revoke(state.key, null)
            }
            is EngineState.Ready -> {
                engineState = EngineState.Detaching(state.key, false)
                DestroyAction.Revoke(state.key, null)
            }
            is EngineState.Published -> {
                published = null
                val key = NtkSurfaceAttachKey(
                    state.identity.engineGeneration,
                    state.identity.attachGeneration,
                    state.identity.surfaceEpoch
                )
                engineState = EngineState.Detaching(key, true)
                DestroyAction.Revoke(key, state.identity)
            }
        }
    }

    fun cancelledBeforeClaim(key: NtkSurfaceAttachKey): Boolean {
        val state = engineState as? EngineState.Attaching ?: return false
        if (state.key != key) return false
        engineState = null
        return true
    }

    fun detachCompleted(key: NtkSurfaceAttachKey): Boolean {
        val state = engineState as? EngineState.Detaching ?: return false
        if (state.key != key) return false
        engineState = null
        return true
    }

    fun installSuccessor(generation: Long): Boolean {
        if (generation <= engineGeneration || terminalFailure != null || closing) return false
        engineGeneration = generation
        return true
    }

    fun fail(reason: NtkSurfaceAttachFailure) {
        if (terminalFailure != null) return
        terminalFailure = reason
        published = null
        candidate?.lease?.let(releaseLease)
        candidate = null
    }

    fun close() {
        closing = true
        published = null
        candidate?.lease?.let(releaseLease)
        candidate = null
    }
}
