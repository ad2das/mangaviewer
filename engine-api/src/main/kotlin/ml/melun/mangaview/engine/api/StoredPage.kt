package ml.melun.mangaview.engine.api

import java.io.Closeable
import java.io.File
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.source.OpenedPage

/** Immutable complete body; readers each open their own descriptor while holding a pin. */
data class StoredPage(
    val pageId: PageId,
    val contentRevision: String,
    val file: File,
    val byteCount: Long,
    val sha256: String,
    val dimensions: PageDimensions,
    val mediaType: String,
) {
    init {
        require(contentRevision.isNotBlank() && byteCount > 0L && mediaType.startsWith("image/"))
        require(sha256.matches(Regex("[0-9a-f]{64}")))
    }
}

interface StoredPageLease : Closeable {
    val page: StoredPage
}

/** Completed immutable staging bytes; only the creating storage instance may publish or discard. */
interface PreparedPage {
    val page: StoredPage
}

data class StorageOwnershipSnapshot(
    val fileLeases: Int,
    val preparedPages: Int,
    val pendingPublications: Int,
)

/** Durable publication and active file pins are owned by this port, never by the UI. */
interface EngineStoragePort {
    /** Consumes and closes this response body. Publication is a separate storage operation. */
    suspend fun prepare(pageId: PageId, contentRevision: String, opened: OpenedPage): PreparedPage
    suspend fun publish(prepared: PreparedPage): StoredPageLease
    suspend fun discard(prepared: PreparedPage)
    suspend fun recover()
    suspend fun find(pageId: PageId, contentRevision: String): StoredPageLease?
    suspend fun pin(page: StoredPage): StoredPageLease
    /** Evicts only unleased committed files, oldest first, until at most targetBytes remain. */
    suspend fun trimTo(targetBytes: Long): Long
    suspend fun savePosition(anchor: SourceAnchor, legacyScreenOffsetUnits: Long)
    suspend fun loadPosition(episodeId: EpisodeId): SourceAnchor?
    suspend fun ownership(): StorageOwnershipSnapshot
}

/** Position persistence is separate from file publication and never owns page bytes. */
interface EnginePositionPort {
    suspend fun save(anchor: SourceAnchor, legacyScreenOffsetUnits: Long)
    suspend fun load(episodeId: EpisodeId): SourceAnchor?
}
