package ml.melun.mangaview.content

internal val EMPTY_CONTENT_PIPELINE_SNAPSHOT = ContentPipelineSnapshot(
    generation = 0L,
    rendererEpoch = 0L,
    activeFetches = 0,
    activeDecodes = 0,
    activeUploads = 0,
    retryWakeups = 0,
    pages = emptyList(),
)

internal fun contentPipelineSnapshot(
    generation: Long,
    rendererEpoch: Long,
    records: Collection<PageRecord>,
    retryWakeups: Int,
    retiring: Collection<PageRecord> = emptyList(),
): ContentPipelineSnapshot = ContentPipelineSnapshot(
    generation,
    rendererEpoch,
    records.count { it.raw is RawState.Fetching } + retiring.count { it.raw is RawState.Fetching },
    records.count { it.decode is DecodeState.Decoding } + retiring.count { it.decode is DecodeState.Decoding },
    records.count { it.decode is DecodeState.Uploading } + retiring.count { it.decode is DecodeState.Uploading },
    retryWakeups,
    records.map(::pageSnapshot),
    retiring.map(::pageSnapshot),
)

private fun pageSnapshot(page: PageRecord) = PagePipelineSnapshot(
    page.page.id, page.raw.javaClass.simpleName, page.decode.javaClass.simpleName, page.residents.size,
)
