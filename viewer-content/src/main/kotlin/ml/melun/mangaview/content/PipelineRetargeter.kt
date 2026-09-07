package ml.melun.mangaview.content

internal fun retargetPipelineOperations(records: Collection<PageRecord>) {
    records.forEach { page ->
        val target = page.demand
        val active = page.decode
        val cancel = when (active) {
            is DecodeState.Decoding -> shouldRetarget(active.hardLane, target)
            is DecodeState.Uploading -> shouldRetarget(active.hardLane, target)
            else -> false
        }
        if (!cancel) return@forEach
        cancelActiveDecode(page)
    }
}

internal fun pipelineCandidates(records: Collection<PageRecord>): List<PageRecord> = records
    .filter { it.demand != null }
    .sortedBy { requireNotNull(it.demand).rank }

internal fun reviveFailedOperationOnPromotion(
    page: PageRecord,
    previous: DemandTarget?,
    current: DemandTarget,
) {
    if (previous != null && current.demandClass.ordinal >= previous.demandClass.ordinal) return
    if (page.raw == RawState.Failed) page.raw = RawState.Absent
    if (page.decode == DecodeState.Failed) page.decode = DecodeState.Idle
}

private fun shouldRetarget(activeHardLane: Boolean, target: DemandTarget?): Boolean =
    target?.sourceRange == null || (hardLane(target.demandClass) && !activeHardLane)
