package ml.melun.mangaview.reader

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Race-safe one-shot handoff from an authoritative adjacent-p0 body event to its delayed retry.
 * The scheduler callbacks are injected so all event/timeout/cancel interleavings stay unit
 * testable without an Android Looper.
 */
internal class NtkAdjacentExactP0WakeRegistry(
    private val postImmediate: (Runnable) -> Unit,
    private val postDelayed: (Runnable, Long) -> Unit,
    private val removeCallbacks: (Runnable) -> Unit,
) {
    private inner class Entry(
        val key: String,
        val retry: Runnable,
    ) {
        val consumed = AtomicBoolean(false)
        val timeout = Runnable { consumeTimeout(this) }
    }

    private val cancelled = AtomicBoolean(false)
    private val entries = ConcurrentHashMap<String, Entry>()

    fun register(key: String, retry: Runnable, delayMs: Long): Boolean {
        require(key.isNotBlank() && delayMs >= 0L)
        if (cancelled.get()) return false
        val entry = Entry(key, retry)
        if (entries.putIfAbsent(key, entry) != null) return false
        if (cancelled.get() && entries.remove(key, entry)) {
            entry.consumed.set(true)
            return false
        }
        try {
            postDelayed(entry.timeout, delayMs)
        } catch (failure: Throwable) {
            if (entries.remove(key, entry)) entry.consumed.set(true)
            throw failure
        }
        // Event/cancel is allowed to win synchronously from inside postDelayed in tests and from
        // another thread in production. Never leave the just-posted timeout retained in that case.
        if (entry.consumed.get()) removeCallbacks(entry.timeout)
        return true
    }

    fun wake(key: String): Boolean {
        val entry = entries.remove(key) ?: return false
        if (!entry.consumed.compareAndSet(false, true)) return false
        removeCallbacks(entry.timeout)
        postImmediate(
            Runnable {
                // Closes event-before-postDelayed: the scheduling thread may have installed the
                // timeout after the first removal but before this main-queue handoff runs.
                removeCallbacks(entry.timeout)
                entry.retry.run()
            },
        )
        return true
    }

    fun unregister(key: String, retry: Runnable): Boolean {
        val entry = entries[key] ?: return false
        if (entry.retry !== retry || !entries.remove(key, entry)) return false
        entry.consumed.set(true)
        removeCallbacks(entry.timeout)
        return true
    }

    fun cancelAll() {
        cancelled.set(true)
        entries.forEach { (key, entry) ->
            if (entries.remove(key, entry)) {
                entry.consumed.set(true)
                removeCallbacks(entry.timeout)
            }
        }
    }

    internal fun sizeForTests(): Int = entries.size

    private fun consumeTimeout(entry: Entry) {
        if (!entries.remove(entry.key, entry)) return
        if (!entry.consumed.compareAndSet(false, true)) return
        entry.retry.run()
    }
}

/**
 * Immutable identity of the one direct-Wi-Fi adjacent `/webtoon/` source-page-zero flight.
 * Display indexes are intentionally absent: a transition-card prune may renumber p0 while the
 * tail decode is still running, but it must never transfer ownership to a different manifest.
 */
data class NtkAdjacentExactP0Owner(
    val ownerToken: Long,
    val episode: NtkEpisodeToken,
    val normalizedEpisodePath: String,
    val manifestRevision: Long,
    val manifestDigest: String,
    val manifestPageCount: Int,
    val canonicalAsset: String,
    val sourceKey: NtkStrictSourceKey,
    val metadataBindingDigest: String,
    val planDigest: String,
    val descriptorId: Long,
) {
    init {
        require(ownerToken > 0L && episode.value > 0L && descriptorId > 0L)
        require(normalizedEpisodePath.startsWith("/webtoon/"))
        require(manifestRevision >= 0L && NtkStripDigests.isSha256(manifestDigest))
        require(manifestPageCount > 0 && canonicalAsset.isNotBlank())
        require(sourceKey.pageIndex == 0 && sourceKey.manifestDigest == manifestDigest)
        require(NtkStripDigests.isSha256(metadataBindingDigest))
        require(NtkStripDigests.isSha256(planDigest))
    }
}

data class NtkAdjacentExactP0TileInstall(
    val slotIndex: Int,
    val resourceRevision: Long,
    val installLease: Long,
    val rgbaBytes: Long,
    val tile: ReaderTile,
) {
    init {
        require(slotIndex >= 0 && resourceRevision > 0L && installLease > 0L && rgbaBytes > 0L)
    }
}

/** A head or tail delta. `complete` is only legal when every plan slot is present after install. */
data class NtkAdjacentExactP0Delta(
    val owner: NtkAdjacentExactP0Owner,
    val plan: NtkPreGeometryPagePlan,
    val proof: ReaderPreparedStore.PreparedOriginalProof,
    val installs: List<NtkAdjacentExactP0TileInstall>,
    val complete: Boolean,
) {
    init {
        require(installs.isNotEmpty())
        require(plan.episode == owner.episode)
        require(plan.sourceKey == owner.sourceKey)
        require(plan.manifestRevision == owner.manifestRevision)
        require(plan.manifestDigest == owner.manifestDigest)
        require(plan.metadataBindingDigest == owner.metadataBindingDigest)
        require(plan.planDigest == owner.planDigest)
        require(installs.map { it.slotIndex }.distinct().size == installs.size)
    }
}

data class NtkAdjacentExactP0HeadPublication(
    val previousPageCount: Int,
    val totalPageCount: Int,
    val cardIndex: Int,
    val cardTitle: String,
    val delta: NtkAdjacentExactP0Delta,
) {
    init {
        require(previousPageCount >= 0 && totalPageCount > previousPageCount)
        require(cardIndex in previousPageCount until totalPageCount)
        require(!delta.complete || delta.plan.tiles.size <= delta.installs.size)
    }
}

/**
 * One complete, immutable forward page from the same direct-Wi-Fi adjacent strip.
 * Unlike the legacy tile callback this carries the committed manifest identity and original
 * proof all the way into the Surface transaction. The contract deliberately covers the whole
 * adjacent episode, not only the initial p1-p3 runway, so the later tail cannot fall back to a
 * proof-losing setter while the reader is continuously moving forward.
 */
data class NtkAdjacentExactRunwayTilePage(
    val displayPageIndex: Int,
    val normalizedEpisodePath: String,
    val sourcePageIndex: Int,
    val canonicalAsset: String,
    val manifestDigest: String,
    val manifestPageCount: Int,
    val pageWidth: Int,
    val pageHeight: Int,
    val tiles: List<ReaderTile>,
    val proof: ReaderPreparedStore.PreparedOriginalProof,
) {
    init {
        require(displayPageIndex >= 0)
        require(
            normalizedEpisodePath.startsWith("/webtoon/") ||
                normalizedEpisodePath.startsWith("/manhwa/")
        )
        require(sourcePageIndex >= 1)
        require(canonicalAsset.isNotBlank() && NtkStripDigests.isSha256(manifestDigest))
        require(manifestPageCount > sourcePageIndex)
        require(pageWidth > 0 && pageHeight > 0 && tiles.isNotEmpty())
        require(
            ReaderPreparedStore.isCanonicalOriginalProof(
                proof,
                canonicalAsset,
                pageWidth,
                pageHeight,
            )
        )
    }
}

/** A contiguous forward publication installed under one Surface lock and one frame request. */
data class NtkAdjacentExactRunwayBatchPublication(
    val previousPageCount: Int,
    val totalPageCount: Int,
    val pages: List<NtkAdjacentExactRunwayTilePage>,
) {
    init {
        require(previousPageCount >= 0 && pages.isNotEmpty())
        require(totalPageCount == previousPageCount + pages.size)
        val ordered = pages.sortedBy { it.displayPageIndex }
        require(ordered.map { it.displayPageIndex } ==
            (previousPageCount until totalPageCount).toList())
        require(ordered.map { it.sourcePageIndex }.distinct().size == ordered.size)
        require(ordered.map { it.sourcePageIndex }.zipWithNext().all { (a, b) -> b == a + 1 })
        val first = ordered.first()
        require(ordered.all {
            it.normalizedEpisodePath == first.normalizedEpisodePath &&
                it.manifestDigest == first.manifestDigest &&
                it.manifestPageCount == first.manifestPageCount
        })
    }
}
