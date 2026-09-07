package ml.melun.mangaview.content

import ml.melun.mangaview.core.PageId

internal fun preemptObsoleteFetch(records: Collection<PageRecord>, networkLimit: Int) {
    val active = records.filter { it.raw is RawState.Fetching }
    if (active.size < networkLimit || active.any { (it.raw as RawState.Fetching).cancelRequested }) return
    if (pipelineCandidates(records).none {
        it.raw == RawState.Absent && it.demand?.let { target -> hardLane(target.demandClass) } == true
    }) return
    val victim = active.filter { page -> page.demand?.let { hardLane(it.demandClass) } != true }
        .maxByOrNull { it.demand?.rank ?: Int.MAX_VALUE } ?: return
    val operation = victim.raw as RawState.Fetching
    victim.raw = operation.copy(cancelRequested = true)
    operation.job.cancel()
}

internal fun acceptFetchStopped(
    command: PipelineCommand.FetchStopped,
    generation: Long,
    pages: Map<PageId, PageRecord>,
) {
    val page = pages[command.pageId] ?: return
    val active = page.raw as? RawState.Fetching ?: return
    if (command.generation != generation || active.token != command.token || !active.cancelRequested) return
    check(active.job.isCompleted) { "Fetch capacity released before worker completion" }
    page.raw = RawState.Absent
}
