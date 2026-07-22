package ml.melun.mangaview.reader

/** Explicit reason for ending a physical resource cycle. */
enum class NtkResourceCycleReleaseReason {
    MEMORY_PRESSURE,
    CONTEXT_LOSS,
    AUTHORITY_RESTART
}

data class NtkResourceCycleKey(
    val surfaceEpoch: Long,
    val key: NtkStripTileKey,
    val resourceRevision: Long
) {
    init {
        require(surfaceEpoch > 0L && resourceRevision > 0L)
    }
}

data class NtkResourceCycleAdmissionProof(
    val cycleKey: NtkResourceCycleKey,
    val admissionId: Long,
    val installLease: Long = 0L
) {
    init {
        require(admissionId > 0L)
        require(installLease >= 0L)
    }
}

data class NtkResourceCycleReleaseProof(
    val cycleKey: NtkResourceCycleKey,
    val admissionId: Long,
    val installLease: Long,
    val retireLease: Long,
    val reason: NtkResourceCycleReleaseReason,
    val shortageBeforeRetire: Long,
    val freed: Boolean
) {
    init {
        require(admissionId > 0L && installLease >= 0L)
        require(retireLease >= 0L && shortageBeforeRetire >= 0L)
        when (reason) {
            NtkResourceCycleReleaseReason.MEMORY_PRESSURE -> {
                require(freed)
                require(installLease > 0L)
                require(retireLease > 0L)
                require(shortageBeforeRetire > 0L)
            }
            NtkResourceCycleReleaseReason.CONTEXT_LOSS,
            NtkResourceCycleReleaseReason.AUTHORITY_RESTART -> {
                require(!freed)
                require(shortageBeforeRetire == 0L)
            }
        }
    }

    val authorizesNextRevision: Boolean
        get() = when (reason) {
            NtkResourceCycleReleaseReason.MEMORY_PRESSURE ->
                freed && retireLease > 0L && shortageBeforeRetire > 0L
            NtkResourceCycleReleaseReason.CONTEXT_LOSS,
            NtkResourceCycleReleaseReason.AUTHORITY_RESTART -> false
        }
}

data class NtkResourceCycleLedgerUpdate(
    val ledger: NtkResourceCycleLedger,
    val applied: Boolean,
    val violation: String? = null
) {
    init {
        require(applied == (violation == null))
    }
}

enum class NtkResourceCycleAdmissionStatus {
    ELIGIBLE,
    AWAITING_PREVIOUS_FREED,
    VIOLATION
}

data class NtkResourceCycleAdmissionEligibility(
    val status: NtkResourceCycleAdmissionStatus,
    val violation: String? = null
) {
    init {
        require((status == NtkResourceCycleAdmissionStatus.VIOLATION) == (violation != null))
    }
}

/**
 * Immutable E5 proof ledger. Admission Surface epoch is immutable resource identity; a preserved
 * window reattach may advance policy epoch while the exact revision chain crosses Surface epochs.
 */
class NtkResourceCycleLedger private constructor(
    admissions: Map<NtkResourceCycleKey, NtkResourceCycleAdmissionProof>,
    releases: Map<NtkResourceCycleKey, NtkResourceCycleReleaseProof>
) {
    private val admissions = LinkedHashMap(admissions)
    private val releases = LinkedHashMap(releases)

    val admissionCount: Int get() = admissions.size
    val releaseCount: Int get() = releases.size
    val reentryCount: Int get() = admissions.keys.count { it.resourceRevision > 1L }
    val pendingReentryCount: Int
        get() = pendingReentryKeys().size
    val memoryPressureReleaseCount: Int
        get() = releases.values.count { it.reason == NtkResourceCycleReleaseReason.MEMORY_PRESSURE }
    val contextLossReleaseCount: Int
        get() = releases.values.count { it.reason == NtkResourceCycleReleaseReason.CONTEXT_LOSS }
    val authorityRestartReleaseCount: Int
        get() = releases.values.count { it.reason == NtkResourceCycleReleaseReason.AUTHORITY_RESTART }

    fun admissionEligibility(
        surfaceEpoch: Long,
        key: NtkStripTileKey,
        resourceRevision: Long
    ): NtkResourceCycleAdmissionEligibility {
        val cycleKey = NtkResourceCycleKey(surfaceEpoch, key, resourceRevision)
        if (cycleKey in admissions) return ineligible("Duplicate resource-cycle admission $cycleKey")
        if (resourceRevision == 1L) {
            val priorAdmissions = admissions.keys.any { it.key == key }
            return if (priorAdmissions) {
                ineligible("Revision one would restart an existing authority cycle $cycleKey")
            } else {
                eligible()
            }
        }
        val previousKeys = admissions.keys.filter {
            it.key == key && it.resourceRevision == resourceRevision - 1L
        }
        if (previousKeys.size != 1) {
            return ineligible("Resource reentry lacks one exact previous admission $cycleKey")
        }
        val previousKey = previousKeys.single()
        val previousAdmission = admissions[previousKey]
            ?: return ineligible("Resource reentry lacks previous admission $previousKey")
        val previousRelease = releases[previousKey]
            ?: return NtkResourceCycleAdmissionEligibility(
                NtkResourceCycleAdmissionStatus.AWAITING_PREVIOUS_FREED
            )
        if (previousRelease.admissionId != previousAdmission.admissionId ||
            previousRelease.installLease != previousAdmission.installLease ||
            !previousRelease.authorizesNextRevision
        ) return ineligible("Resource reentry has invalid release proof $previousKey")
        return eligible()
    }

    fun admit(
        surfaceEpoch: Long,
        cycle: NtkTileCycleIdentity,
        allowPendingPreviousFreed: Boolean = false
    ): NtkResourceCycleLedgerUpdate {
        require(surfaceEpoch == cycle.admissionSurfaceEpoch)
        if (cycle.installLease != 0L) {
            return violation("Admission already carried install lease ${cycle.installLease}")
        }
        val cycleKey = NtkResourceCycleKey(surfaceEpoch, cycle.key, cycle.resourceRevision)
        val eligibility = admissionEligibility(surfaceEpoch, cycle.key, cycle.resourceRevision)
        if (eligibility.status == NtkResourceCycleAdmissionStatus.VIOLATION ||
            eligibility.status == NtkResourceCycleAdmissionStatus.AWAITING_PREVIOUS_FREED &&
                !allowPendingPreviousFreed
        ) {
            return violation(eligibility.violation
                ?: "Resource reentry lacks previous FREED proof $cycleKey")
        }
        val next = LinkedHashMap(admissions)
        next[cycleKey] = NtkResourceCycleAdmissionProof(
            cycleKey = cycleKey,
            admissionId = cycle.admissionId
        )
        return NtkResourceCycleLedgerUpdate(NtkResourceCycleLedger(next, releases), true)
    }

    /**
     * Decode work may be admitted after DETACHED, but a new GPU publication cannot start until
     * the immediately preceding authority resource cycle has an exact memory-pressure FREED.
     */
    fun publicationEligibility(
        surfaceEpoch: Long,
        cycle: NtkTileCycleIdentity
    ): NtkResourceCycleAdmissionEligibility {
        require(surfaceEpoch == cycle.admissionSurfaceEpoch)
        val cycleKey = NtkResourceCycleKey(surfaceEpoch, cycle.key, cycle.resourceRevision)
        val admission = admissions[cycleKey]
            ?: return ineligible("Publication lacks resource-cycle admission $cycleKey")
        if (admission.admissionId != cycle.admissionId) {
            return ineligible("Publication admission mismatch $cycleKey")
        }
        if (admission.installLease != 0L) {
            return ineligible("Publication cycle already has install binding $cycleKey")
        }
        if (cycle.resourceRevision == 1L) return eligible()
        val previousKeys = admissions.keys.filter {
            it.key == cycle.key && it.resourceRevision == cycle.resourceRevision - 1L
        }
        if (previousKeys.size != 1) {
            return ineligible("Publication lacks one exact previous admission $cycleKey")
        }
        val previousKey = previousKeys.single()
        val previousAdmission = admissions[previousKey]
            ?: return ineligible("Publication lacks previous admission $previousKey")
        val previousRelease = releases[previousKey]
            ?: return NtkResourceCycleAdmissionEligibility(
                NtkResourceCycleAdmissionStatus.AWAITING_PREVIOUS_FREED
            )
        return if (previousRelease.admissionId == previousAdmission.admissionId &&
            previousRelease.installLease == previousAdmission.installLease &&
            previousRelease.authorizesNextRevision
        ) eligible() else ineligible("Publication has invalid previous FREED proof $previousKey")
    }

    fun bindInstall(surfaceEpoch: Long, cycle: NtkTileCycleIdentity): NtkResourceCycleLedgerUpdate {
        require(surfaceEpoch == cycle.admissionSurfaceEpoch)
        if (cycle.installLease <= 0L) return violation("Install binding lacks an install lease")
        val cycleKey = NtkResourceCycleKey(surfaceEpoch, cycle.key, cycle.resourceRevision)
        val admission = admissions[cycleKey]
            ?: return violation("Install binding lacks admission $cycleKey")
        if (admission.admissionId != cycle.admissionId) {
            return violation("Install binding admission mismatch $cycleKey")
        }
        if (admission.installLease != 0L) {
            return violation("Duplicate install binding $cycleKey")
        }
        val next = LinkedHashMap(admissions)
        next[cycleKey] = admission.copy(installLease = cycle.installLease)
        return NtkResourceCycleLedgerUpdate(NtkResourceCycleLedger(next, releases), true)
    }

    fun release(proof: NtkResourceCycleReleaseProof): NtkResourceCycleLedgerUpdate {
        val admission = admissions[proof.cycleKey]
            ?: return violation("Resource release lacks admission ${proof.cycleKey}")
        if (proof.cycleKey in releases) {
            return violation("Duplicate resource-cycle release ${proof.cycleKey}")
        }
        if (admission.admissionId != proof.admissionId) {
            return violation("Resource release admission mismatch ${proof.cycleKey}")
        }
        if (admission.installLease != proof.installLease) {
            return violation("Resource release install mismatch ${proof.cycleKey}")
        }
        val next = LinkedHashMap(releases)
        next[proof.cycleKey] = proof
        return NtkResourceCycleLedgerUpdate(NtkResourceCycleLedger(admissions, next), true)
    }

    fun releaseFor(
        surfaceEpoch: Long,
        record: NtkTileRecord,
        reason: NtkResourceCycleReleaseReason,
        freed: Boolean
    ): NtkResourceCycleLedgerUpdate {
        require(surfaceEpoch == record.admissionSurfaceEpoch)
        val cycle = record.cycle
            ?: return violation("Absent record cannot release a resource cycle ${record.key}")
        return release(NtkResourceCycleReleaseProof(
            cycleKey = NtkResourceCycleKey(surfaceEpoch, record.key, record.resourceRevision),
            admissionId = cycle.admissionId,
            installLease = cycle.installLease,
            retireLease = record.retireLease,
            reason = reason,
            shortageBeforeRetire = if (
                reason == NtkResourceCycleReleaseReason.MEMORY_PRESSURE
            ) record.shortageBeforeRetire else 0L,
            freed = freed
        ))
    }

    /**
     * Closes one cycle after the controller has proved that the whole native authority is
     * physically gone. A cycle which had already crossed the memory-pressure retire boundary
     * keeps that exact reason/epoch/lease and receives its terminal FREED proof; all other
     * current ownership ends at the authority barrier.
     */
    fun releaseForTerminalPhysicalBarrier(record: NtkTileRecord): NtkResourceCycleLedgerUpdate {
        val retiring = record.state == NtkTileLifecycleState.RETIRE_PENDING ||
            record.state == NtkTileLifecycleState.DETACHED_FENCE_PENDING
        return if (retiring) {
            releaseFor(
                surfaceEpoch = record.admissionSurfaceEpoch,
                record = record,
                reason = NtkResourceCycleReleaseReason.MEMORY_PRESSURE,
                freed = true
            )
        } else {
            releaseFor(
                surfaceEpoch = record.admissionSurfaceEpoch,
                record = record,
                reason = NtkResourceCycleReleaseReason.AUTHORITY_RESTART,
                freed = false
            )
        }
    }

    fun releaseProofs(): List<NtkResourceCycleReleaseProof> = releases.values.toList()

    fun pendingReentryKeys(): List<NtkResourceCycleKey> = admissions.keys.filter { key ->
        if (key.resourceRevision <= 1L) return@filter false
        val previous = admissions.keys.singleOrNull {
            it.key == key.key && it.resourceRevision == key.resourceRevision - 1L
        }
        previous == null || releases[previous]?.authorizesNextRevision != true
    }

    fun hasAdmission(surfaceEpoch: Long, cycle: NtkTileCycleIdentity): Boolean {
        require(surfaceEpoch == cycle.admissionSurfaceEpoch)
        val admission = admissions[NtkResourceCycleKey(
            surfaceEpoch,
            cycle.key,
            cycle.resourceRevision
        )] ?: return false
        return admission.admissionId == cycle.admissionId &&
            admission.installLease == cycle.installLease
    }

    val digest: String
        get() = NtkStripDigests.sha256Tokens(buildList {
            add("ntk-resource-cycle-ledger-v2")
            admissions.values.sortedWith(compareBy(
                { it.cycleKey.surfaceEpoch },
                { it.cycleKey.key.pageIndex },
                { it.cycleKey.key.slotIndex },
                { it.cycleKey.resourceRevision }
            )).forEach { proof ->
                add("A")
                add(proof.cycleKey.surfaceEpoch.toString())
                add(proof.cycleKey.key.pageIndex.toString())
                add(proof.cycleKey.key.slotIndex.toString())
                add(proof.cycleKey.resourceRevision.toString())
                add(proof.admissionId.toString())
                add(proof.installLease.toString())
            }
            releases.values.sortedWith(compareBy(
                { it.cycleKey.surfaceEpoch },
                { it.cycleKey.key.pageIndex },
                { it.cycleKey.key.slotIndex },
                { it.cycleKey.resourceRevision }
            )).forEach { proof ->
                add("R")
                add(proof.cycleKey.surfaceEpoch.toString())
                add(proof.cycleKey.key.pageIndex.toString())
                add(proof.cycleKey.key.slotIndex.toString())
                add(proof.cycleKey.resourceRevision.toString())
                add(proof.admissionId.toString())
                add(proof.installLease.toString())
                add(proof.retireLease.toString())
                add(proof.reason.name)
                add(proof.shortageBeforeRetire.toString())
                add(proof.freed.toString())
            }
        })

    val isStructurallyValid: Boolean
        get() = admissions.values.all { admission ->
            val key = admission.cycleKey
            if (key.resourceRevision == 1L) true else {
                val previous = admissions.keys.singleOrNull {
                    it.key == key.key && it.resourceRevision == key.resourceRevision - 1L
                } ?: return@all false
                val previousAdmission = admissions[previous]
                val release = releases[previous]
                previousAdmission != null && (release == null ||
                    release.admissionId == previousAdmission.admissionId &&
                    release.installLease == previousAdmission.installLease &&
                    release.authorizesNextRevision)
            }
        } && releases.all { (key, release) ->
            val admission = admissions[key]
            admission != null && release.admissionId == admission.admissionId &&
                release.installLease == admission.installLease
        }

    /** Terminal E5 relation: every revision > 1 has the exact prior authority-cycle FREED proof. */
    val isValid: Boolean
        get() = isStructurallyValid && admissions.keys.all { key ->
            if (key.resourceRevision == 1L) true else {
                val previous = admissions.keys.singleOrNull {
                    it.key == key.key && it.resourceRevision == key.resourceRevision - 1L
                } ?: return@all false
                releases[previous]?.authorizesNextRevision == true
            }
        }

    override fun equals(other: Any?): Boolean = other is NtkResourceCycleLedger &&
        admissions == other.admissions && releases == other.releases
    override fun hashCode(): Int = 31 * admissions.hashCode() + releases.hashCode()

    private fun violation(message: String) = NtkResourceCycleLedgerUpdate(this, false, message)

    private fun eligible() = NtkResourceCycleAdmissionEligibility(
        NtkResourceCycleAdmissionStatus.ELIGIBLE
    )

    private fun ineligible(message: String) = NtkResourceCycleAdmissionEligibility(
        NtkResourceCycleAdmissionStatus.VIOLATION,
        message
    )

    companion object {
        fun empty(): NtkResourceCycleLedger = NtkResourceCycleLedger(emptyMap(), emptyMap())
    }
}
