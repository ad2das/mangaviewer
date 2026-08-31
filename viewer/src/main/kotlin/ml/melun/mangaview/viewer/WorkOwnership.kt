package ml.melun.mangaview.viewer

import ml.melun.mangaview.core.PageId

enum class WorkKind {
    FETCH,
    DECODE,
}

enum class WorkPriority {
    HARD,
    WARM,
    COLD,
}

data class OperationToken(
    val generation: Long,
    val pageId: PageId,
    val kind: WorkKind,
    val attempt: Int,
    val operationSequence: Long,
    val priority: WorkPriority,
) {
    init {
        require(generation > 0L) { "Generation must be positive" }
        require(attempt > 0) { "Attempt must be positive" }
        require(operationSequence > 0L) { "Operation sequence must be positive" }
    }
}

data class WorkClaim(
    val ownership: WorkOwnership,
    val token: OperationToken,
)

data class WorkOwnership(
    val fetches: Map<PageId, OperationToken> = emptyMap(),
    val decodes: Map<PageId, OperationToken> = emptyMap(),
    val nextOperationSequence: Long = 1L,
) {
    init {
        require(nextOperationSequence > 0L) { "Next operation sequence must be positive" }
    }

    fun owner(kind: WorkKind, pageId: PageId): OperationToken? = mapFor(kind)[pageId]

    fun claim(
        generation: Long,
        pageId: PageId,
        kind: WorkKind,
        attempt: Int,
        priority: WorkPriority,
    ): WorkClaim {
        require(owner(kind, pageId) == null) { "Page already has an operation owner" }
        check(nextOperationSequence < Long.MAX_VALUE) { "Operation sequence is exhausted" }
        val token = OperationToken(
            generation = generation,
            pageId = pageId,
            kind = kind,
            attempt = attempt,
            operationSequence = nextOperationSequence,
            priority = priority,
        )
        val claimed = when (kind) {
            WorkKind.FETCH -> copy(
                fetches = fetches + (pageId to token),
                nextOperationSequence = nextOperationSequence + 1L,
            )
            WorkKind.DECODE -> copy(
                decodes = decodes + (pageId to token),
                nextOperationSequence = nextOperationSequence + 1L,
            )
        }
        return WorkClaim(claimed, token)
    }

    fun release(token: OperationToken): WorkOwnership {
        if (owner(token.kind, token.pageId) != token) return this
        return when (token.kind) {
            WorkKind.FETCH -> copy(fetches = fetches - token.pageId)
            WorkKind.DECODE -> copy(decodes = decodes - token.pageId)
        }
    }

    fun clearDecodes(): WorkOwnership = copy(decodes = emptyMap())

    private fun mapFor(kind: WorkKind): Map<PageId, OperationToken> = when (kind) {
        WorkKind.FETCH -> fetches
        WorkKind.DECODE -> decodes
    }
}
