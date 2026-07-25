package ml.melun.mangaview.reader

/**
 * Reconciles a partial adjacent-episode cache snapshot with its original source-page slots.
 *
 * The early URL cache is intentionally drained as pages become ready, so a later snapshot can be
 * a suffix such as p004..p030. Treating that suffix as a new zero-based manifest shifts p004 into
 * source slot 0 and eventually collapses a 30-page episode into the last few pages. Page-numbered
 * NTK assets are instead mapped back to their immutable source slot. Positional replacement is
 * allowed only when the snapshot is complete and therefore cannot shift a suffix.
 */
internal object NtkAdjacentRunwayRefreshPolicy {
    data class Assignment(
        val sourceIndex: Int,
        val image: String
    )

    private val pageAsset = Regex(
        "/p(\\d{3,})\\.(?:jpg|jpeg|png|webp)(?:[?#].*)?$",
        RegexOption.IGNORE_CASE
    )

    fun assignments(
        existingSourceIndexes: List<Int>,
        latestImages: List<String>
    ): List<Assignment> {
        if (existingSourceIndexes.isEmpty() || latestImages.isEmpty()) return emptyList()
        val existing = existingSourceIndexes.filter { it >= 0 }.toHashSet()
        if (existing.isEmpty()) return emptyList()

        val numbered = latestImages.mapNotNull { image ->
            val sourceIndex = sourceIndex(image) ?: return@mapNotNull null
            if (sourceIndex !in existing) return@mapNotNull null
            Assignment(sourceIndex, image)
        }
            .distinctBy { it.sourceIndex }
            .sortedBy { it.sourceIndex }
        if (numbered.isNotEmpty()) return numbered

        if (latestImages.size != existingSourceIndexes.size) return emptyList()
        return existingSourceIndexes.zip(latestImages)
            .mapNotNull { (sourceIndex, image) ->
                sourceIndex.takeIf { it >= 0 }?.let { Assignment(it, image) }
            }
    }

    private fun sourceIndex(image: String): Int? {
        return pageAsset.find(image)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?.minus(1)
    }
}
