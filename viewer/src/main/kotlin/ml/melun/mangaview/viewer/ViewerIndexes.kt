package ml.melun.mangaview.viewer

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageId

class ColdFetchSweep private constructor(
    val pageCount: Int,
    val cursor: Int,
    val pausedUntilNanos: Long,
    private val pendingIndex: ImmutableLongSumTree,
) {
    init {
        require(pageCount > 0) { "Cold fetch sweep needs pages" }
        require(cursor in 0 until pageCount) { "Cold fetch cursor is outside its page order" }
        require(pausedUntilNanos >= 0L) { "Cold fetch pause must not be negative" }
        require(pendingIndex.size == pageCount) { "Cold fetch index size changed" }
    }

    val isComplete: Boolean
        get() = pendingIndex.sum == 0L

    val pendingCount: Int
        get() = pendingIndex.sum.toInt()

    fun resumed(): ColdFetchSweep = if (pausedUntilNanos == 0L) this else {
        ColdFetchSweep(pageCount, cursor, 0L, pendingIndex)
    }

    fun without(pageIndex: Int): ColdFetchSweep {
        if (!isPending(pageIndex)) return resumed()
        return ColdFetchSweep(pageCount, cursor, 0L, pendingIndex.update(pageIndex, 0L))
    }

    fun append(additionalPages: Int): ColdFetchSweep {
        if (additionalPages == 0) return this
        require(additionalPages > 0)
        val expanded = ImmutableLongSumTree.create(
            pendingIndex.values() + List(additionalPages) { 1L },
        )
        // Never abandon a nearer forward gap when several manifests are appended quickly.
        // Move to the new episode only after every previously known original is complete.
        val nextCursor = if (isComplete) pageCount else cursor
        return ColdFetchSweep(pageCount + additionalPages, nextCursor, 0L, expanded)
    }

    fun nextPendingIndex(fromIndex: Int): Int? {
        if (isComplete) return null
        require(fromIndex in 0 until pageCount)
        return pendingIndex.firstPositiveAtOrAfter(fromIndex)
            ?: pendingIndex.firstPositiveAtOrAfter(0)
    }

    fun previousPendingIndex(fromIndex: Int): Int? {
        if (isComplete) return null
        require(fromIndex in 0 until pageCount)
        return pendingIndex.lastPositiveAtOrBefore(fromIndex)
            ?: pendingIndex.lastPositiveAtOrBefore(pageCount - 1)
    }

    fun isPending(pageIndex: Int): Boolean {
        require(pageIndex in 0 until pageCount)
        return pendingIndex.prefixSum(pageIndex + 1) != pendingIndex.prefixSum(pageIndex)
    }

    fun advanced(nextCursor: Int, pauseUntilNanos: Long): ColdFetchSweep =
        ColdFetchSweep(pageCount, nextCursor, pauseUntilNanos, pendingIndex)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        return other is ColdFetchSweep && pageCount == other.pageCount && cursor == other.cursor &&
            pausedUntilNanos == other.pausedUntilNanos &&
            (pendingIndex === other.pendingIndex || pendingIndex.values() == other.pendingIndex.values())
    }

    override fun hashCode(): Int {
        var result = pageCount
        result = 31 * result + cursor
        result = 31 * result + pausedUntilNanos.hashCode()
        return 31 * result + pendingIndex.values().hashCode()
    }

    companion object {
        fun create(pageCount: Int, startIndex: Int = 0): ColdFetchSweep {
            require(pageCount > 0)
            require(startIndex in 0 until pageCount)
            return ColdFetchSweep(
                pageCount,
                startIndex,
                0L,
                ImmutableLongSumTree.create(List(pageCount) { 1L }),
            )
        }
    }
}

data class EpisodeProgress(
    val pageCount: Int,
    val verifiedCount: Int,
    val lastPageId: PageId,
) {
    init {
        require(pageCount > 0) { "Episode page count must be positive" }
        require(verifiedCount in 0..pageCount) { "Verified page count is outside its episode" }
    }

    val allVerified: Boolean
        get() = verifiedCount == pageCount
}

internal fun ViewerState.replacePage(pageId: PageId, replacement: PageRuntime): ViewerState {
    val current = pages.getValue(pageId)
    require(current.spec.id == replacement.spec.id) { "Replacement page id changed" }
    val updatedResidents = updateResidents(pageId, current.pixel, replacement.pixel)
    val updatedProgress = updateProgress(pageId, current.encoded, replacement.encoded)
    val updatedSweep = when {
        current.encoded == null && replacement.encoded != null ->
            coldFetchSweep.without(requireNotNull(layout.indexOf(pageId)))
        current.encoded != null && replacement.encoded == null -> error("Verified pages cannot become absent")
        else -> coldFetchSweep
    }
    return copy(
        pages = pages.put(pageId, replacement),
        residentPageIds = updatedResidents.pageIds,
        residentBytes = updatedResidents.bytes,
        episodeProgress = updatedProgress,
        coldFetchSweep = updatedSweep,
    )
}

internal fun ViewerState.replacePages(replacements: Map<PageId, PageRuntime>): ViewerState {
    var result = this
    replacements.forEach { (pageId, runtime) -> result = result.replacePage(pageId, runtime) }
    return result
}

private fun ViewerState.updateResidents(
    pageId: PageId,
    oldPixel: PixelRef?,
    newPixel: PixelRef?,
): ResidentUpdate {
    val retainedBytes = residentBytes - (oldPixel?.allocationBytes ?: 0L)
    require(retainedBytes >= 0L) { "Resident byte accounting became inconsistent" }
    val bytes = saturatingAdd(retainedBytes, newPixel?.allocationBytes ?: 0L)
    val pageIds = when {
        oldPixel == null && newPixel != null -> insertResident(pageId)
        oldPixel != null && newPixel == null -> residentPageIds - pageId
        else -> residentPageIds
    }
    return ResidentUpdate(pageIds, bytes)
}

private fun ViewerState.insertResident(pageId: PageId): List<PageId> {
    if (pageId in residentPageIds) return residentPageIds
    val pageIndex = requireNotNull(layout.indexOf(pageId))
    val insertion = residentPageIds.binarySearch { residentId ->
        requireNotNull(layout.indexOf(residentId)).compareTo(pageIndex)
    }.let { if (it >= 0) it else -it - 1 }
    return residentPageIds.toMutableList().also { it.add(insertion, pageId) }
}

private fun ViewerState.updateProgress(
    pageId: PageId,
    oldEncoded: VerifiedPageRef?,
    newEncoded: VerifiedPageRef?,
): Map<EpisodeId, EpisodeProgress> {
    if ((oldEncoded == null) == (newEncoded == null)) return episodeProgress
    val episodeId = pageId.episodeId
    val current = episodeProgress.getValue(episodeId)
    val delta = if (newEncoded != null) 1 else -1
    return episodeProgress + (episodeId to current.copy(verifiedCount = current.verifiedCount + delta))
}

private data class ResidentUpdate(
    val pageIds: List<PageId>,
    val bytes: Long,
)
