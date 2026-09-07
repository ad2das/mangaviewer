package ml.melun.mangaview.viewer.runtime

import java.io.Closeable
import kotlinx.coroutines.CancellationException
import ml.melun.mangaview.content.EncodedPageRef
import ml.melun.mangaview.content.RawPagePort
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.data.cache.CompleteEpisodeLease
import ml.melun.mangaview.data.cache.CompleteEpisodeSnapshot
import ml.melun.mangaview.data.cache.CompleteEpisodeSnapshotStore
import ml.melun.mangaview.data.cache.SnapshotPageBinding
import ml.melun.mangaview.source.PageFetchPriority

internal enum class ViewerCachedResumeRoute {
    NOT_ATTEMPTED,
    COMPLETE_LEASE_OPENED,
    SOURCE_FALLBACK,
}

/** Read-only diagnostic state for the startup probe; it does not alter cache ownership. */
internal data class ViewerCachedResumeDiagnostic(
    val route: ViewerCachedResumeRoute,
    val episodeId: EpisodeId?,
    val manifestPageCount: Int?,
    val routeAtNanos: Long?,
)

/** Uses the normal pipeline with immutable complete-episode bodies; no source requests for this lease. */
internal class ViewerCachedResume(
    private val store: CompleteEpisodeSnapshotStore,
    private val online: RawPagePort,
) : RawPagePort, Closeable {
    @Volatile private var lease: CompleteEpisodeLease? = null
    private val leaseLock = Any()
    @Volatile private var closed = false
    private val manifests = mutableMapOf<EpisodeId, EpisodeManifest>()
    private val bindings = mutableMapOf<PageId, SnapshotPageBinding>()
    private val saved = mutableSetOf<EpisodeId>()
    @Volatile private var diagnostic = ViewerCachedResumeDiagnostic(
        ViewerCachedResumeRoute.NOT_ATTEMPTED,
        null,
        null,
        null,
    )

    suspend fun open(id: EpisodeId): EpisodeManifest? {
        if (closed) return null
        val acquired = store.open(id)
        if (acquired == null) {
            diagnostic = ViewerCachedResumeDiagnostic(
                ViewerCachedResumeRoute.SOURCE_FALLBACK,
                id,
                null,
                System.nanoTime(),
            )
            return null
        }
        synchronized(leaseLock) {
            if (closed) {
                acquired.close()
                return null
            }
            if (lease != null) {
                acquired.close()
                error("Cached resume was opened twice")
            }
            lease = acquired
        }
        diagnostic = ViewerCachedResumeDiagnostic(
            ViewerCachedResumeRoute.COMPLETE_LEASE_OPENED,
            id,
            acquired.snapshot.manifest.pages.size,
            System.nanoTime(),
        )
        return acquired.snapshot.manifest
    }

    /** Exposes only the route selected by this live viewer session to debug instrumentation. */
    fun diagnosticSnapshot(): ViewerCachedResumeDiagnostic = diagnostic

    fun manifestResolved(manifest: EpisodeManifest) {
        if (lease?.snapshot?.manifest?.id == manifest.id) return
        manifests[manifest.id] = manifest
    }

    fun rawVerified(encoded: EncodedPageRef): CompleteEpisodeSnapshot? {
        val id = encoded.pageId.episodeId
        val manifest = manifests[id] ?: return null
        if (id in saved || manifest.pages.none { it.id == encoded.pageId }) return null
        bindings[encoded.pageId] = SnapshotPageBinding(encoded.pageId, encoded.byteCount,
            encoded.fingerprint, encoded.dimensions)
        if (manifest.pages.any { it.id !in bindings }) return null
        saved += id
        return CompleteEpisodeSnapshot(manifest, manifest.pages.map { bindings.getValue(it.id) })
    }

    suspend fun persist(snapshot: CompleteEpisodeSnapshot) {
        try {
            store.save(snapshot)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            android.util.Log.w("ViewerCache", "Complete resume snapshot was not saved", failure)
        }
    }

    override suspend fun find(pageId: PageId): EncodedPageRef? {
        check(!closed) { "Cached resume owner is closed" }
        val pinned = lease?.takeIf { it.snapshot.manifest.id == pageId.episodeId }
            ?: return online.find(pageId)
        val page = pinned.page(pageId)
        return EncodedPageRef(page.pageId, page.file.absolutePath, page.byteCount, page.sha256, page.dimensions)
    }

    override suspend fun fetch(pageId: PageId, priority: PageFetchPriority, responseStarted: () -> Unit): EncodedPageRef {
        check(!closed) { "Cached resume owner is closed" }
        check(lease?.snapshot?.manifest?.id != pageId.episodeId) {
            "A leased complete episode cannot mix fresh positional page data"
        }
        return online.fetch(pageId, priority, responseStarted)
    }

    override fun close() {
        val acquired = synchronized(leaseLock) {
            closed = true
            lease
        }
        acquired?.close()
        synchronized(leaseLock) { if (lease === acquired) lease = null }
    }
}
