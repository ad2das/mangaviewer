package ml.melun.mangaview.reader

data class NtkTileCycleIdentity(
    val key: NtkStripTileKey,
    val admissionId: Long,
    val resourceRevision: Long,
    val installLease: Long = 0L,
    val admissionSurfaceEpoch: Long = 1L
) {
    init {
        require(admissionId > 0L && resourceRevision > 0L && installLease >= 0L)
        require(admissionSurfaceEpoch > 0L)
    }
}

data class NtkTileRetireIdentity(
    val cycle: NtkTileCycleIdentity,
    val retireLease: Long
) {
    init {
        require(cycle.installLease > 0L && retireLease > 0L)
    }
}

/** Immutable actor-snapshot record. No worker or surface adapter may mutate it in place. */
data class NtkTileRecord(
    val key: NtkStripTileKey,
    val state: NtkTileLifecycleState,
    val admissionId: Long,
    val demandEpochAtAdmission: Long,
    val admissionSurfaceEpoch: Long,
    val resourceRevision: Long,
    val installLease: Long,
    val retireLease: Long,
    val rgbaBytes: Long,
    val sceneVersion: Long,
    val retireFenceSerial: Long,
    val lastPresentedFrame: Long = Long.MIN_VALUE,
    val shortageBeforeRetire: Long = 0L,
    val retireSurfaceEpoch: Long = 0L,
    val retireDemandEpoch: Long = 0L,
    val retireProtectedDigest: String = ""
) {
    init {
        require(resourceRevision >= 0L)
        require(installLease >= 0L && retireLease >= 0L)
        require(rgbaBytes >= 0L && sceneVersion >= 0L && retireFenceSerial >= 0L)
        require(shortageBeforeRetire >= 0L)
        require(retireSurfaceEpoch >= 0L && retireDemandEpoch >= 0L)
        if (state == NtkTileLifecycleState.ABSENT) {
            require(admissionId == 0L && installLease == 0L && retireLease == 0L)
            require(admissionSurfaceEpoch == 0L)
            require(rgbaBytes == 0L && sceneVersion == 0L && retireFenceSerial == 0L)
            require(retireSurfaceEpoch == 0L && retireDemandEpoch == 0L && retireProtectedDigest.isEmpty())
        } else {
            require(admissionId > 0L && demandEpochAtAdmission >= 0L)
            require(admissionSurfaceEpoch > 0L)
            require(resourceRevision > 0L && rgbaBytes > 0L)
        }
        if (state in setOf(
                NtkTileLifecycleState.UPLOADING,
                NtkTileLifecycleState.RESIDENT,
                NtkTileLifecycleState.RETIRE_PENDING,
                NtkTileLifecycleState.DETACHED_FENCE_PENDING
            )
        ) require(installLease > 0L)
        if (state in setOf(NtkTileLifecycleState.RESIDENT, NtkTileLifecycleState.RETIRE_PENDING,
                NtkTileLifecycleState.DETACHED_FENCE_PENDING)
        ) require(sceneVersion > 0L)
        if (state in setOf(NtkTileLifecycleState.RETIRE_PENDING,
                NtkTileLifecycleState.DETACHED_FENCE_PENDING)
        ) {
            require(retireLease > 0L && shortageBeforeRetire > 0L)
            require(retireSurfaceEpoch > 0L && NtkStripDigests.isSha256(retireProtectedDigest))
        }
        if (state == NtkTileLifecycleState.DETACHED_FENCE_PENDING) require(retireFenceSerial > 0L)
    }

    val cycle: NtkTileCycleIdentity?
        get() = admissionId.takeIf { it > 0L }?.let {
            NtkTileCycleIdentity(
                key,
                admissionId,
                resourceRevision,
                installLease,
                admissionSurfaceEpoch
            )
        }

    companion object {
        fun absent(key: NtkStripTileKey, lastResourceRevision: Long = 0L): NtkTileRecord =
            NtkTileRecord(
                key = key,
                state = NtkTileLifecycleState.ABSENT,
                admissionId = 0L,
                demandEpochAtAdmission = 0L,
                admissionSurfaceEpoch = 0L,
                resourceRevision = lastResourceRevision,
                installLease = 0L,
                retireLease = 0L,
                rgbaBytes = 0L,
                sceneVersion = 0L,
                retireFenceSerial = 0L
            )
    }
}

sealed interface NtkTileLifecycleEvent {
    data class Admit(
        val key: NtkStripTileKey,
        val admissionId: Long,
        val demandEpoch: Long,
        val admissionSurfaceEpoch: Long,
        val resourceRevision: Long,
        val rgbaBytes: Long
    ) : NtkTileLifecycleEvent

    data class LeaseReady(val cycle: NtkTileCycleIdentity) : NtkTileLifecycleEvent
    data class DecodeStarted(val cycle: NtkTileCycleIdentity) : NtkTileLifecycleEvent
    data class DecodeAttemptReleased(val cycle: NtkTileCycleIdentity) : NtkTileLifecycleEvent
    data class DecodeRetryStarted(val cycle: NtkTileCycleIdentity) : NtkTileLifecycleEvent
    data class Decoded(val cycle: NtkTileCycleIdentity) : NtkTileLifecycleEvent
    data class UploadStarted(
        val cycle: NtkTileCycleIdentity,
        val installLease: Long
    ) : NtkTileLifecycleEvent
    data class Resident(
        val cycle: NtkTileCycleIdentity,
        val sceneVersion: Long
    ) : NtkTileLifecycleEvent
    data class RetireRequested(
        val cycle: NtkTileCycleIdentity,
        val retireLease: Long,
        val shortageBeforeRetire: Long,
        val surfaceEpoch: Long,
        val demandEpoch: Long,
        val protectedDigest: String
    ) : NtkTileLifecycleEvent
    data class RetireVetoed(val identity: NtkTileRetireIdentity) : NtkTileLifecycleEvent
    data class Detached(
        val identity: NtkTileRetireIdentity,
        val sceneVersion: Long,
        val retireFenceSerial: Long
    ) : NtkTileLifecycleEvent
    data class Freed(val identity: NtkTileRetireIdentity) : NtkTileLifecycleEvent
    data class Presented(val cycle: NtkTileCycleIdentity, val frameSequence: Long) : NtkTileLifecycleEvent
    data class TerminalFailure(val cycle: NtkTileCycleIdentity, val reason: String) : NtkTileLifecycleEvent
}

data class NtkTileTransitionResult(
    val record: NtkTileRecord,
    val applied: Boolean,
    val stale: Boolean,
    val violation: String? = null
) {
    init {
        require(!(applied && stale))
        require(violation == null || !applied)
    }
}

/** Pure lifecycle transition table. Stale identities are observational and never mutate current state. */
object NtkTileLifecycle {
    fun transition(
        current: NtkTileRecord?,
        event: NtkTileLifecycleEvent
    ): NtkTileTransitionResult = when (event) {
        is NtkTileLifecycleEvent.Admit -> admit(current, event)
        is NtkTileLifecycleEvent.LeaseReady -> advance(
            current, event.cycle, NtkTileLifecycleState.ADMITTED, NtkTileLifecycleState.LEASED
        )
        is NtkTileLifecycleEvent.DecodeStarted -> advance(
            current, event.cycle, NtkTileLifecycleState.LEASED, NtkTileLifecycleState.DECODING
        )
        is NtkTileLifecycleEvent.DecodeAttemptReleased -> hold(
            current, event.cycle, NtkTileLifecycleState.DECODING
        )
        is NtkTileLifecycleEvent.DecodeRetryStarted -> hold(
            current, event.cycle, NtkTileLifecycleState.DECODING
        )
        is NtkTileLifecycleEvent.Decoded -> advance(
            current, event.cycle, NtkTileLifecycleState.DECODING, NtkTileLifecycleState.CPU_READY
        )
        is NtkTileLifecycleEvent.UploadStarted -> uploadStarted(current, event)
        is NtkTileLifecycleEvent.Resident -> resident(current, event)
        is NtkTileLifecycleEvent.RetireRequested -> retire(current, event)
        is NtkTileLifecycleEvent.RetireVetoed -> retireVetoed(current, event.identity)
        is NtkTileLifecycleEvent.Detached -> detached(current, event)
        is NtkTileLifecycleEvent.Freed -> freed(current, event.identity)
        is NtkTileLifecycleEvent.Presented -> presented(current, event)
        is NtkTileLifecycleEvent.TerminalFailure -> terminalFailure(current, event)
    }

    private fun admit(
        current: NtkTileRecord?,
        event: NtkTileLifecycleEvent.Admit
    ): NtkTileTransitionResult {
        require(event.admissionId > 0L && event.demandEpoch >= 0L)
        require(event.admissionSurfaceEpoch > 0L)
        require(event.resourceRevision > 0L && event.rgbaBytes > 0L)
        val before = current ?: NtkTileRecord.absent(event.key)
        if (before.key != event.key) return violation(before, "Admission key mismatch")
        if (before.state != NtkTileLifecycleState.ABSENT) {
            return violation(before, "Duplicate admission for ${event.key} in ${before.state}")
        }
        if (event.resourceRevision <= before.resourceRevision) {
            return stale(before)
        }
        return applied(before.copy(
            state = NtkTileLifecycleState.ADMITTED,
            admissionId = event.admissionId,
            demandEpochAtAdmission = event.demandEpoch,
            admissionSurfaceEpoch = event.admissionSurfaceEpoch,
            resourceRevision = event.resourceRevision,
            rgbaBytes = event.rgbaBytes
        ))
    }

    private fun advance(
        current: NtkTileRecord?,
        cycle: NtkTileCycleIdentity,
        from: NtkTileLifecycleState,
        to: NtkTileLifecycleState
    ): NtkTileTransitionResult {
        val checked = checked(current, cycle) ?: return stale(current, cycle.key)
        if (checked.state != from) return violation(checked, "Expected $from, was ${checked.state}")
        return applied(checked.copy(state = to))
    }

    private fun hold(
        current: NtkTileRecord?,
        cycle: NtkTileCycleIdentity,
        state: NtkTileLifecycleState
    ): NtkTileTransitionResult {
        val checked = checked(current, cycle) ?: return stale(current, cycle.key)
        if (checked.state != state) return violation(checked, "Expected $state, was ${checked.state}")
        return applied(checked)
    }

    private fun uploadStarted(
        current: NtkTileRecord?,
        event: NtkTileLifecycleEvent.UploadStarted
    ): NtkTileTransitionResult {
        require(event.installLease > 0L)
        val checked = checked(current, event.cycle, requireInstallLease = false)
            ?: return stale(current, event.cycle.key)
        if (checked.state != NtkTileLifecycleState.CPU_READY || checked.installLease != 0L) {
            return violation(checked, "Upload may start exactly once from CPU_READY")
        }
        return applied(checked.copy(
            state = NtkTileLifecycleState.UPLOADING,
            installLease = event.installLease
        ))
    }

    private fun resident(
        current: NtkTileRecord?,
        event: NtkTileLifecycleEvent.Resident
    ): NtkTileTransitionResult {
        require(event.sceneVersion > 0L && event.cycle.installLease > 0L)
        val checked = checked(current, event.cycle) ?: return stale(current, event.cycle.key)
        if (checked.state != NtkTileLifecycleState.UPLOADING) {
            return violation(checked, "Resident ACK requires UPLOADING")
        }
        return applied(checked.copy(
            state = NtkTileLifecycleState.RESIDENT,
            sceneVersion = event.sceneVersion
        ))
    }

    private fun retire(
        current: NtkTileRecord?,
        event: NtkTileLifecycleEvent.RetireRequested
    ): NtkTileTransitionResult {
        require(event.retireLease > 0L && event.shortageBeforeRetire > 0L)
        require(event.surfaceEpoch > 0L && event.demandEpoch >= 0L)
        require(NtkStripDigests.isSha256(event.protectedDigest))
        val checked = checked(current, event.cycle) ?: return stale(current, event.cycle.key)
        if (checked.state != NtkTileLifecycleState.RESIDENT) {
            return violation(checked, "Retire requires RESIDENT")
        }
        return applied(checked.copy(
            state = NtkTileLifecycleState.RETIRE_PENDING,
            retireLease = event.retireLease,
            shortageBeforeRetire = event.shortageBeforeRetire,
            retireSurfaceEpoch = event.surfaceEpoch,
            retireDemandEpoch = event.demandEpoch,
            retireProtectedDigest = event.protectedDigest
        ))
    }

    private fun retireVetoed(
        current: NtkTileRecord?,
        identity: NtkTileRetireIdentity
    ): NtkTileTransitionResult {
        val checked = checked(current, identity) ?: return stale(current, identity.cycle.key)
        if (checked.state != NtkTileLifecycleState.RETIRE_PENDING) {
            return violation(checked, "Retire veto requires RETIRE_PENDING")
        }
        return applied(checked.copy(
            state = NtkTileLifecycleState.RESIDENT,
            retireLease = 0L,
            shortageBeforeRetire = 0L,
            retireSurfaceEpoch = 0L,
            retireDemandEpoch = 0L,
            retireProtectedDigest = ""
        ))
    }

    private fun detached(
        current: NtkTileRecord?,
        event: NtkTileLifecycleEvent.Detached
    ): NtkTileTransitionResult {
        require(event.sceneVersion > 0L && event.retireFenceSerial > 0L)
        val checked = checked(current, event.identity) ?: return stale(current, event.identity.cycle.key)
        if (checked.state != NtkTileLifecycleState.RETIRE_PENDING) {
            return violation(checked, "Detached ACK requires RETIRE_PENDING")
        }
        return applied(checked.copy(
            state = NtkTileLifecycleState.DETACHED_FENCE_PENDING,
            sceneVersion = event.sceneVersion,
            retireFenceSerial = event.retireFenceSerial
        ))
    }

    private fun freed(
        current: NtkTileRecord?,
        identity: NtkTileRetireIdentity
    ): NtkTileTransitionResult {
        val checked = checked(current, identity) ?: return stale(current, identity.cycle.key)
        if (checked.state != NtkTileLifecycleState.DETACHED_FENCE_PENDING) {
            return violation(checked, "FREED ACK requires DETACHED_FENCE_PENDING")
        }
        return applied(NtkTileRecord.absent(checked.key, checked.resourceRevision))
    }

    private fun presented(
        current: NtkTileRecord?,
        event: NtkTileLifecycleEvent.Presented
    ): NtkTileTransitionResult {
        require(event.frameSequence > 0L)
        val checked = checked(current, event.cycle) ?: return stale(current, event.cycle.key)
        if (checked.state !in setOf(NtkTileLifecycleState.RESIDENT,
                NtkTileLifecycleState.RETIRE_PENDING)
        ) return violation(checked, "Only a scene-resident tile can be presented")
        return applied(checked.copy(lastPresentedFrame = maxOf(
            checked.lastPresentedFrame,
            event.frameSequence
        )))
    }

    private fun terminalFailure(
        current: NtkTileRecord?,
        event: NtkTileLifecycleEvent.TerminalFailure
    ): NtkTileTransitionResult {
        require(event.reason.isNotBlank())
        val checked = checked(current, event.cycle, requireInstallLease = false)
            ?: return stale(current, event.cycle.key)
        return applied(checked.copy(state = NtkTileLifecycleState.FAILED))
    }

    private fun checked(
        current: NtkTileRecord?,
        cycle: NtkTileCycleIdentity,
        requireInstallLease: Boolean = cycle.installLease > 0L
    ): NtkTileRecord? = current?.takeIf {
        it.key == cycle.key &&
            it.admissionId == cycle.admissionId &&
            it.resourceRevision == cycle.resourceRevision &&
            (!requireInstallLease || cycle.installLease > 0L && it.installLease == cycle.installLease)
    }

    private fun checked(
        current: NtkTileRecord?,
        identity: NtkTileRetireIdentity
    ): NtkTileRecord? = checked(current, identity.cycle)?.takeIf {
        it.retireLease == identity.retireLease
    }

    private fun applied(record: NtkTileRecord) = NtkTileTransitionResult(record, true, false)
    private fun stale(record: NtkTileRecord) = NtkTileTransitionResult(record, false, true)
    private fun stale(current: NtkTileRecord?, key: NtkStripTileKey) =
        stale(current ?: NtkTileRecord.absent(key))
    private fun violation(record: NtkTileRecord, message: String) =
        NtkTileTransitionResult(record, false, false, message)
}
