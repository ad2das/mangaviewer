package ml.melun.mangaview.content

internal data class PipelineDecodePlan(
    val page: PageRecord,
    val target: DemandTarget,
    val encoded: EncodedPageRef,
    val range: SourceRowRange,
    val hard: Boolean,
)

internal fun nextDecodePlan(
    records: Collection<PageRecord>,
    hard: Boolean,
    rendererEpoch: Long,
    displayWidthPx: Int,
    retiring: Collection<PageRecord> = emptyList(),
): PipelineDecodePlan? {
    val laneBusy = (records + retiring).any { page ->
        when (val decode = page.decode) {
            is DecodeState.Decoding -> decode.hardLane == hard
            is DecodeState.Uploading -> decode.hardLane == hard
            else -> false
        }
    }
    if (laneBusy) return null
    pipelineCandidates(records).forEach { page ->
        if (retiring.any { it.page.id == page.page.id && it.decode.isRunning() }) return@forEach
        val target = page.demand ?: return@forEach
        if (hardLane(target.demandClass) != hard || page.decode != DecodeState.Idle) return@forEach
        val encoded = (page.raw as? RawState.Verified)?.encoded ?: return@forEach
        val requested = target.sourceRange ?: return@forEach
        val residents = page.residents.filter { it.rendererEpoch == rendererEpoch }
        val range = nextDecodeRange(
            requested,
            encoded.dimensions,
            displayWidthPx,
            residents,
        ) ?: return@forEach
        return PipelineDecodePlan(page, target, encoded, range, hard)
    }
    return null
}
