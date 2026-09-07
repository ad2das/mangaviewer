package ml.melun.mangaview.content

internal fun decodeReservationBytes(plan: PipelineDecodePlan, displayWidthPx: Int): Long {
    val rows = (plan.range.bottomExclusive - plan.range.top).toLong()
    val width = displayWidthPx.toLong()
    val sourceWidth = plan.encoded.dimensions.widthPx.toLong()
    val height = (rows * width + sourceWidth - 1L) / sourceWidth
    return runCatching { Math.multiplyExact(Math.multiplyExact(width, height), 4L) }
        .getOrDefault(Long.MAX_VALUE)
}

internal fun canAdmitDecode(plan: PipelineDecodePlan, reservation: Long,
    records: Collection<PageRecord>, retiring: Collection<PageRecord>, budget: Long): Boolean {
    val allocated = (records + retiring).sumOf { page ->
        page.residents.sumOf(TextureRef::byteCount) + when (val active = page.decode) {
            is DecodeState.Decoding -> active.reservedByteCount
            is DecodeState.Uploading -> active.reservedByteCount
            else -> 0L
        }
    }
    val reclaimable = reclaimableResidentBytes(records, plan.target)
    val retained = (allocated - reclaimable).coerceAtLeast(0L)
    return reservation <= budget && retained <= budget - reservation
}
