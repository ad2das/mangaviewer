package ml.melun.mangaview.account

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
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
) : CloudRemotePort {
    private val document = firestore.collection("users").document(uid).collection("mangaView").document("state")
    private val firestore = firestore
    private val catalogs = mutableMapOf<String, List<SourceEpisode>>()
    private var checkedLegacy = false
    private var nextLegacyRetryAt = 0L
    override var unresolvedRecords = 0
        private set

    override fun observe(changed: () -> Unit): () -> Unit {
        val registration = document.addSnapshotListener { _, _ -> changed() }
        return registration::remove
    }

    override suspend fun exchange(local: List<CloudLibraryRecord>): List<CloudLibraryRecord> {
        prepareLegacy()
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
            merged
        }.await()
    }

    private suspend fun prepareLegacy() {
        if (checkedLegacy && (unresolvedRecords == 0 || System.currentTimeMillis() < nextLegacyRetryAt)) return
        val fields = document.get(com.google.firebase.firestore.Source.SERVER).await().data.orEmpty()
        val legacy = LegacyCloudLibrary.decode(fields, catalogs)
        for (series in legacy.unresolvedSeries) {
            val known = withTimeoutOrNull(10_000L) { episodes(SeriesId(SourceId("ntk"), series)) }
            if (!known.isNullOrEmpty()) catalogs[series] = known
        }
        checkedLegacy = true
        nextLegacyRetryAt = System.currentTimeMillis() + 60_000L
    }
}
