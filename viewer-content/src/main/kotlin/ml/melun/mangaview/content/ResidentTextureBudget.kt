package ml.melun.mangaview.content

import ml.melun.mangaview.viewer.session.DemandClass
import ml.melun.mangaview.viewer.session.SemanticViewportAnchor
import ml.melun.mangaview.viewer.session.SourceRangeFraction

internal fun residentEvictions(
    records: Collection<PageRecord>,
    budgetBytes: Long,
): List<TextureRef> {
    require(budgetBytes > 0L)
    val candidates = records.flatMap { page ->
        page.residents.map { texture -> ResidentCandidate(page.demand, texture) }
    }
    var excess = candidates.sumOf { it.texture.byteCount } - budgetBytes
    if (excess <= 0L) return emptyList()
    return buildList {
        candidates.sortedWith(
            compareByDescending<ResidentCandidate> { it.evictionTier() }
                .thenByDescending { it.demand?.rank ?: Int.MAX_VALUE }
                .thenBy { it.texture.key },
        ).forEach { candidate ->
            if (excess > 0L) {
                add(candidate.texture)
                excess -= candidate.texture.byteCount
            }
        }
    }
}

internal fun evictExcessResidents(
    records: Collection<PageRecord>,
    generation: Long,
    displayWidthPx: Int,
    viewportHeightPx: Int,
    sink: ContentPipelineSink,
    uploader: TextureUploadPort,
    budgetBytes: Long = adaptiveResidentBudgetBytes(null),
): List<TextureRef> {
    if (displayWidthPx <= 0 || viewportHeightPx <= 0) return emptyList()
    val evictions = residentEvictions(
        records,
        budgetBytes,
    )
    val pages = records.associateBy { it.page.id }
    evictions.forEach { texture ->
        val page = pages[texture.pageId] ?: return@forEach
        if (texture !in page.residents) return@forEach
        page.residents = page.residents - texture
        sink.emit(ContentPipelineEvent.TextureEvicted(generation, texture))
        uploader.release(texture)
    }
    return evictions
}

internal fun evictColdResidents(records: Collection<PageRecord>, generation: Long,
    sink: ContentPipelineSink, uploader: TextureUploadPort) {
    records.forEach { page ->
        val target = page.demand
        val evicted = page.residents.filter { texture ->
            target == null || !hardLane(target.demandClass) ||
                target.sourceRange?.let(texture::intersects) != true
        }
        page.residents = page.residents - evicted.toSet()
        evicted.forEach {
            sink.emit(ContentPipelineEvent.TextureEvicted(generation, it))
            uploader.release(it)
        }
    }
}

private data class ResidentCandidate(
    val demand: DemandTarget?,
    val texture: TextureRef,
) {
    fun evictionTier(): Int {
        val target = demand ?: return 100
        if (target.sourceRange?.let(texture::intersects) == false) return 90
        return target.evictionTier()
    }
}

private fun DemandTarget.evictionTier(): Int = when (demandClass) {
            DemandClass.BEHIND -> 80
            DemandClass.CURRENT_FORWARD_FAR -> 70
            DemandClass.ADJACENT_PREFIX -> 60
            DemandClass.CURRENT_FORWARD_NEAR -> 40
            DemandClass.CURRENT_BEHIND_NEAR -> 50
            DemandClass.VISIBLE,
            DemandClass.RESUME_ANCHOR,
            -> 10
}

internal fun reclaimableResidentBytes(records: Collection<PageRecord>, target: DemandTarget): Long =
    records.sumOf { page ->
        page.residents.filter { texture ->
            val candidate = ResidentCandidate(page.demand, texture)
            val newTier = target.evictionTier()
            candidate.evictionTier() > newTier ||
                candidate.evictionTier() == newTier && (page.demand?.rank ?: Int.MAX_VALUE) > target.rank
        }.sumOf(TextureRef::byteCount)
    }

private fun TextureRef.intersects(range: SourceRangeFraction): Boolean {
    val start = sourceTopPx.toLong() * SemanticViewportAnchor.Q32_ONE / sourceHeightPx
    val end = sourceBottomPx.toLong() * SemanticViewportAnchor.Q32_ONE / sourceHeightPx
    return start < range.endQ32 && end > range.startQ32
}
