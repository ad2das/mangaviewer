package ml.melun.mangaview.viewer.runtime

import ml.melun.mangaview.viewer.FramePlan

/** Handler-thread confined primitive storage for accepted native submission tokens. */
internal class NativeSubmissionLedger(
    capacity: Int = DEFAULT_CAPACITY,
) {
    private val tokens = LongArray(capacity)
    private val startedAtNanos = LongArray(capacity)
    private val durationsNanos = LongArray(capacity)
    private val generations = LongArray(capacity)
    private val scrollOffsets = LongArray(capacity)
    private val viewportHeights = LongArray(capacity)
    private val anchorOrdinals = IntArray(capacity)
    private val anchorOffsets = LongArray(capacity)
    private val frameTimelineVsyncIds = LongArray(capacity)
    private val expectedPresentationTimes = LongArray(capacity)
    private val flags = IntArray(capacity)

    init {
        require(capacity > 0)
    }

    fun record(
        token: Long,
        plan: FramePlan,
        readableActualContent: Boolean,
        fullVisualCoverage: Boolean,
        fullActualCoverage: Boolean,
    ) {
        require(token > 0L)
        val index = index(token)
        val anchor = semanticAnchor(plan)
        tokens[index] = token
        startedAtNanos[index] = System.nanoTime()
        durationsNanos[index] = -1L
        generations[index] = plan.generation
        scrollOffsets[index] = plan.scrollOffset.units
        viewportHeights[index] = plan.viewport.height.units
        anchorOrdinals[index] = anchor.ordinal
        anchorOffsets[index] = anchor.offsetUnits
        frameTimelineVsyncIds[index] = plan.frameTimelineVsyncId
        expectedPresentationTimes[index] = plan.expectedPresentationTimeNanos
        flags[index] = evidenceFlags(
            readableActualContent,
            fullVisualCoverage,
            fullActualCoverage,
        )
    }

    fun finish(token: Long) {
        val index = index(token)
        if (tokens[index] == token) {
            durationsNanos[index] =
                (System.nanoTime() - startedAtNanos[index]).coerceAtLeast(0L)
        }
    }

    fun clear(token: Long) {
        val index = index(token)
        if (tokens[index] == token) tokens[index] = 0L
    }

    fun complete(
        token: Long,
        rendererIdentity: Long,
        presentedNanos: Long,
    ): NativePresentationEvidence? {
        val index = index(token)
        if (tokens[index] != token) return null
        tokens[index] = 0L
        val evidence = flags[index]
        return NativePresentationEvidence(
            rendererIdentity = rendererIdentity,
            token = token,
            generation = generations[index],
            presentedNanos = presentedNanos,
            renderLatencyNanos = durationsNanos[index],
            scrollOffsetUnits = scrollOffsets[index],
            viewportHeightUnits = viewportHeights[index],
            anchorOrdinal = anchorOrdinals[index],
            anchorOffsetUnits = anchorOffsets[index],
            frameTimelineVsyncId = frameTimelineVsyncIds[index],
            expectedPresentationTimeNanos = expectedPresentationTimes[index],
            readableActualContent = evidence and READABLE_ACTUAL != 0,
            fullVisualCoverage = evidence and FULL_VISUAL != 0,
            fullActualCoverage = evidence and FULL_ACTUAL != 0,
        )
    }

    private fun semanticAnchor(plan: FramePlan): SemanticAnchor {
        val offset = plan.scrollOffset.units
        for (page in plan.pages) {
            val bottom = saturatingAdd(page.top.units, page.height.units)
            if (offset >= page.top.units && offset < bottom) {
                return SemanticAnchor(page.ordinal, offset - page.top.units)
            }
        }
        return SemanticAnchor.NONE
    }

    private fun index(token: Long): Int = (token % tokens.size).toInt()

    private data class SemanticAnchor(val ordinal: Int, val offsetUnits: Long) {
        companion object {
            val NONE = SemanticAnchor(-1, 0L)
        }
    }

    private companion object {
        const val DEFAULT_CAPACITY = 512
        const val READABLE_ACTUAL = 1
        const val FULL_VISUAL = 1 shl 1
        const val FULL_ACTUAL = 1 shl 2

        fun evidenceFlags(readable: Boolean, visual: Boolean, actual: Boolean): Int =
            (if (readable) READABLE_ACTUAL else 0) or
                (if (visual) FULL_VISUAL else 0) or
                (if (actual) FULL_ACTUAL else 0)

        fun saturatingAdd(left: Long, right: Long): Long =
            if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
    }
}
