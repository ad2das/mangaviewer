package ml.melun.mangaview.account

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.source.SourceEpisode

internal interface CloudRemotePort {
    val unresolvedRecords: Int
    fun observe(changed: () -> Unit): () -> Unit
    suspend fun exchange(local: List<CloudLibraryRecord>): List<CloudLibraryRecord>
}

internal class FirebaseLibraryRemote(
    private val auth: FirebaseAuth,
    firestore: FirebaseFirestore,
    private val uid: String,
    private val episodes: suspend (SeriesId) -> List<SourceEpisode>,
    private val scope: CoroutineScope,
) : CloudRemotePort {
    private val document = firestore.collection("users").document(uid).collection("mangaView").document("state")
    private val firestore = firestore
    private val catalogs = ConcurrentHashMap<String, List<SourceEpisode>>()
    @Volatile private var checkedLegacy = false
    @Volatile private var nextLegacyRetryAt = 0L
    @Volatile private var lastWritten: String? = null
    @Volatile private var notify: (() -> Unit)? = null
    @Volatile override var unresolvedRecords = 0
        private set

    override fun observe(changed: () -> Unit): () -> Unit {
        notify = changed
        val migration = scope.launch { runCatching { migrateLegacy() } }
        val registration = document.addSnapshotListener { snapshot, _ ->
            if (snapshot == null || snapshot.metadata.hasPendingWrites()) return@addSnapshotListener
            if (snapshot.getString(CloudLibraryRecords.FIELD) == lastWritten) return@addSnapshotListener
            changed()
        }
        return {
            registration.remove()
            migration.cancel()
            notify = null
        }
    }

    override suspend fun exchange(local: List<CloudLibraryRecord>): List<CloudLibraryRecord> {
        return firestore.runTransaction { transaction ->
            check(auth.currentUser?.uid == uid) { "Account changed during synchronization" }
            val snapshot = transaction.get(document)
            val text = snapshot.getString(CloudLibraryRecords.FIELD)
            val remote = text?.let(CloudLibraryRecords::decode).orEmpty()
            val legacy = LegacyCloudLibrary.decode(snapshot.data.orEmpty(), catalogs)
            unresolvedRecords = legacy.unresolvedRecords
            val present = remote.map { it.identity }.toSet()
            val merged = CloudLibraryRecords.merge(local, remote, legacy.records.filter { it.identity !in present })
            val encoded = CloudLibraryRecords.encode(merged)
            // Leave room for the legacy fields already in this same Firestore document.
            val legacyBytes = snapshot.data.orEmpty().filterKeys { it != CloudLibraryRecords.FIELD }
                .toString().toByteArray(Charsets.UTF_8).size
            require(encoded.toByteArray(Charsets.UTF_8).size + legacyBytes < 950_000) { "Account library is full" }
            check(auth.currentUser?.uid == uid) { "Account changed during synchronization" }
            if (encoded != text) transaction.set(document, mapOf(CloudLibraryRecords.FIELD to encoded),
                com.google.firebase.firestore.SetOptions.merge())
            lastWritten = encoded
            merged
        }.await()
    }

    /** Runs off the exchange path; the next exchange picks up whichever catalogs are ready. */
    private suspend fun migrateLegacy() {
        while (true) {
            if (checkedLegacy && System.currentTimeMillis() < nextLegacyRetryAt) return
            val fields = try {
                document.get(com.google.firebase.firestore.Source.SERVER).await().data.orEmpty()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { delay(LEGACY_RETRY_MS); continue }
            val pending = LegacyCloudLibrary.decode(fields, catalogs).unresolvedSeries
            val resolved = coroutineScope {
                pending.map { series -> async {
                    val known = try { withTimeoutOrNull(10_000L) { episodes(SeriesId(SourceId("ntk"), series)) } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                    if (known.isNullOrEmpty()) false else { catalogs[series] = known; true }
                } }.awaitAll().count { it }
            }
            checkedLegacy = true
            if (resolved > 0) notify?.invoke()
            if (resolved == pending.size) return
            nextLegacyRetryAt = System.currentTimeMillis() + LEGACY_RETRY_MS
            delay(LEGACY_RETRY_MS)
        }
    }

    companion object {
        const val LEGACY_RETRY_MS = 60_000L
    }
}
