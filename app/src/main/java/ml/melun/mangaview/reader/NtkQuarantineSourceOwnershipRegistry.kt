package ml.melun.mangaview.reader

import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Resource ownership for pre-exact source calls. It never grants metadata, body, decode or native
 * authority; it only proves one physical un-ranged GET per plan-bound page.
 */
object NtkQuarantineSourceOwnershipRegistry {
    enum class AdoptionMark {
        ACTIVE_MARKED,
        ALREADY_PHYSICALLY_COMPLETED,
        STALE
    }

    data class Snapshot(
        val episodePath: String,
        val discoveryGeneration: Long,
        val planBindingDigest: String,
        val sessionId: Long,
        val activeCalls: Int,
        val physicalCallCount: Int,
        val duplicatePhysicalCallCount: Int,
        val adoptedActiveCalls: Int,
        val admissionsClosed: Boolean
    )

    private data class Operation(
        val identity: NtkQuarantineSourceCallIdentity,
        var callAdmitted: Boolean = false,
        var adopted: Boolean = false
    )

    private class Record(
        val episodePath: String,
        val discoveryGeneration: Long,
        val planBindingDigest: String,
        val sessionId: Long
    ) {
        val operations = LinkedHashMap<Long, Operation>()
        val startedPages = LinkedHashSet<Int>()
        val activeLanes = BooleanArray(NtkSourceLanePolicy.MAX_NETWORK_OPERATIONS)
        var physicalCallCount = 0
        var duplicatePhysicalCallCount = 0
        var admissionsClosed = false
    }

    internal data class RecordKey(
        val episodePath: String,
        val discoveryGeneration: Long,
        val sessionId: Long,
    )

    class OperationLease internal constructor(
        private val key: RecordKey,
        val identity: NtkQuarantineSourceCallIdentity
    ) : Closeable {
        private val completed = AtomicBoolean(false)
        private val adoptedByNonce = AtomicLong(0L)

        fun markAdopted(token: NtkPromotionToken): AdoptionMark {
            if (token.episodePath != key.episodePath ||
                token.discoveryGeneration != identity.discoveryGeneration ||
                token.sessionId != identity.sessionId ||
                token.planBindingDigest != identity.planBindingDigest
            ) return AdoptionMark.STALE
            val priorNonce = adoptedByNonce.get()
            if (priorNonce != 0L && priorNonce != token.nonce) {
                return AdoptionMark.STALE
            }
            val record = records[key]
            if (record == null) return markCompletedAdoption(token)
            return synchronized(record) {
                if (records[key] !== record) return@synchronized markCompletedAdoption(token)
                val operation = record.operations[identity.operationId]
                    ?: return@synchronized markCompletedAdoption(token)
                if (operation.identity != identity) return@synchronized AdoptionMark.STALE
                if (!adoptedByNonce.compareAndSet(0L, token.nonce) &&
                    adoptedByNonce.get() != token.nonce
                ) return@synchronized AdoptionMark.STALE
                operation.adopted = true
                AdoptionMark.ACTIVE_MARKED
            }
        }

        private fun markCompletedAdoption(token: NtkPromotionToken): AdoptionMark =
            if (completed.get() &&
                (adoptedByNonce.compareAndSet(0L, token.nonce) ||
                    adoptedByNonce.get() == token.nonce)
            ) AdoptionMark.ALREADY_PHYSICALLY_COMPLETED
            else AdoptionMark.STALE

        override fun close() {
            if (!completed.compareAndSet(false, true)) return
            val record = records[key] ?: return
            synchronized(record) {
                if (records[key] !== record) return@synchronized
                val operation = record.operations[identity.operationId] ?: return@synchronized
                if (operation.identity == identity) {
                    record.operations.remove(identity.operationId)
                    record.activeLanes[identity.laneIndex] = false
                }
            }
        }
    }

    private val records = ConcurrentHashMap<RecordKey, Record>()
    @JvmStatic
    fun beginSession(binding: NtkQuarantinePlanBinding, sessionId: Long) {
        require(sessionId > 0L)
        val key = RecordKey(binding.episodePath, binding.discoveryGeneration, sessionId)
        val created = Record(
            binding.episodePath,
            binding.discoveryGeneration,
            binding.bindingDigest,
            sessionId
        )
        val existing = records.putIfAbsent(key, created)
        check(existing == null || existing.planBindingDigest == binding.bindingDigest) {
            "Conflicting quarantine source session identity"
        }
    }

    @JvmStatic
    fun beginOperation(
        episodePath: String,
        identity: NtkQuarantineSourceCallIdentity
    ): OperationLease {
        val key = RecordKey(
            episodePath,
            identity.discoveryGeneration,
            identity.sessionId,
        )
        val record = records[key]
            ?: error("Quarantine operation preceded session reservation")
        synchronized(record) {
            check(records[key] === record) { "Quarantine source session retired" }
            check(!record.admissionsClosed)
            check(record.discoveryGeneration == identity.discoveryGeneration)
            check(record.planBindingDigest == identity.planBindingDigest)
            check(record.sessionId == identity.sessionId)
            check(identity.attemptOrdinal == 1 && identity.method == "GET")
            check(identity.rangeStart == -1L && identity.rangeEnd == -1L)
            check(record.operations.size < NtkSourceLanePolicy.MAX_NETWORK_OPERATIONS)
            check(identity.laneIndex in record.activeLanes.indices)
            check(!record.activeLanes[identity.laneIndex]) {
                "Quarantine physical lane already owns a call"
            }
            check(record.startedPages.add(identity.pageIndex)) {
                "Quarantine page attempted a second physical producer"
            }
            check(!record.operations.containsKey(identity.operationId))
            record.operations[identity.operationId] = Operation(identity)
            record.activeLanes[identity.laneIndex] = true
        }
        return OperationLease(
            RecordKey(episodePath, identity.discoveryGeneration, identity.sessionId),
            identity,
        )
    }

    /** Called at the actual Call.Factory boundary. */
    @JvmStatic
    fun validateCall(
        episodePath: String?,
        identity: NtkQuarantineSourceCallIdentity?
    ): Boolean {
        if (episodePath.isNullOrBlank() || identity?.isValid != true) return false
        val key = RecordKey(episodePath, identity.discoveryGeneration, identity.sessionId)
        val record = records[key] ?: return false
        return synchronized(record) {
            if (records[key] !== record) return@synchronized false
            val operation = record.operations[identity.operationId]
                ?: return@synchronized false
            if (operation.identity != identity) return@synchronized false
            if (operation.callAdmitted) {
                record.duplicatePhysicalCallCount++
                return@synchronized false
            }
            operation.callAdmitted = true
            record.physicalCallCount++
            true
        }
    }

    @JvmStatic
    fun closeAdmissions(
        episodePath: String,
        discoveryGeneration: Long,
        sessionId: Long,
    ): Boolean {
        val key = RecordKey(episodePath, discoveryGeneration, sessionId)
        val record = records[key] ?: return false
        return synchronized(record) {
            if (records[key] !== record) return@synchronized false
            record.admissionsClosed = true
            true
        }
    }

    @JvmStatic
    fun snapshot(
        episodePath: String,
        discoveryGeneration: Long,
        sessionId: Long,
    ): Snapshot? {
        val key = RecordKey(episodePath, discoveryGeneration, sessionId)
        val record = records[key] ?: return null
        return synchronized(record) {
            if (records[key] !== record) return@synchronized null
            Snapshot(
                record.episodePath,
                record.discoveryGeneration,
                record.planBindingDigest,
                record.sessionId,
                record.operations.size,
                record.physicalCallCount,
                record.duplicatePhysicalCallCount,
                record.operations.values.count(Operation::adopted),
                record.admissionsClosed
            )
        }
    }

    @JvmStatic
    fun release(
        episodePath: String,
        discoveryGeneration: Long,
        sessionId: Long,
    ): Boolean {
        val key = RecordKey(episodePath, discoveryGeneration, sessionId)
        val record = records[key] ?: return true
        return synchronized(record) {
            if (records[key] !== record) return@synchronized true
            if (record.operations.isNotEmpty()) return@synchronized false
            records.remove(key, record)
        }
    }

    @JvmStatic
    fun resetForTest() = records.clear()
}
