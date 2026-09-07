package ml.melun.mangaview.source.ntk

import java.io.Closeable
import java.util.Collections
import java.util.IdentityHashMap

/** Owns validated bodies until every candidate job has stopped and one body is returned. */
internal class NtkReplicaRaceOwnership(
    private val releaseLease: (NtkReplicaSelector.ReplicaLease) -> Unit,
) : Closeable {
    private val owned = Collections.newSetFromMap(IdentityHashMap<NtkReplicaWinner, Boolean>())

    fun retain(winner: NtkReplicaWinner) = synchronized(owned) {
        check(owned.add(winner)) { "NTK replica body already belongs to this race" }
    }

    fun take(winner: NtkReplicaWinner): NtkReplicaWinner = synchronized(owned) {
        check(owned.remove(winner)) { "NTK replica body has no race owner" }
        winner
    }

    override fun close() {
        val abandoned = synchronized(owned) { owned.toList().also { owned.clear() } }
        var failure: Throwable? = null
        abandoned.forEach { winner ->
            try {
                try { winner.opened.close() } finally { releaseLease(winner.lease) }
            } catch (error: Throwable) {
                if (failure == null) failure = error else failure?.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }
}
