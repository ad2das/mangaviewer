package ml.melun.mangaview.reader

data class NtkDetachedRetireUpdate(
    val ledger: NtkDetachedRetireLedger,
    val record: NtkTileRecord?,
    val applied: Boolean,
    val violation: String? = null
) {
    init {
        require(applied == (violation == null))
    }
}

/**
 * Fence-owned GPU cycles detached from the scene but not yet allowed to return budget bytes.
 * Keeping this ledger separate lets the current tile slot become ABSENT and accept a new hard
 * cycle while the exact old FREED identity remains immutable.
 */
class NtkDetachedRetireLedger private constructor(
    records: Map<Long, NtkTileRecord>
) : Iterable<NtkTileRecord> {
    private val records = LinkedHashMap(records)

    val size: Int get() = records.size
    val rgbaBytes: Long get() = records.values.fold(0L) { total, record ->
        Math.addExact(total, record.rgbaBytes)
    }

    operator fun get(retireLease: Long): NtkTileRecord? = records[retireLease]

    fun add(record: NtkTileRecord): NtkDetachedRetireUpdate {
        if (record.state != NtkTileLifecycleState.DETACHED_FENCE_PENDING) {
            return violation("Detached ledger requires DETACHED_FENCE_PENDING for ${record.key}")
        }
        if (record.retireLease in records) {
            return violation("Duplicate detached retire lease ${record.retireLease}")
        }
        if (records.values.any { existing ->
                existing.key == record.key &&
                    existing.resourceRevision == record.resourceRevision &&
                    existing.installLease == record.installLease
            }
        ) return violation("Duplicate detached resource cycle ${record.key}")
        val next = LinkedHashMap(records)
        next[record.retireLease] = record
        return NtkDetachedRetireUpdate(NtkDetachedRetireLedger(next), record, true)
    }

    fun remove(identity: NtkTileRetireIdentity): NtkDetachedRetireUpdate {
        val record = records[identity.retireLease]
            ?: return violation("Unknown detached retire lease ${identity.retireLease}")
        if (record.key != identity.cycle.key ||
            record.admissionId != identity.cycle.admissionId ||
            record.resourceRevision != identity.cycle.resourceRevision ||
            record.installLease != identity.cycle.installLease
        ) return violation("Detached FREED identity mismatch ${identity.cycle.key}")
        val next = LinkedHashMap(records)
        next.remove(identity.retireLease)
        return NtkDetachedRetireUpdate(NtkDetachedRetireLedger(next), record, true)
    }

    override fun iterator(): Iterator<NtkTileRecord> = records.values.toList().iterator()
    override fun equals(other: Any?): Boolean = other is NtkDetachedRetireLedger &&
        records == other.records
    override fun hashCode(): Int = records.hashCode()

    private fun violation(message: String) = NtkDetachedRetireUpdate(this, null, false, message)

    companion object {
        fun empty(): NtkDetachedRetireLedger = NtkDetachedRetireLedger(emptyMap())
    }
}
