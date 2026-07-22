package ml.melun.mangaview.reader

internal const val PINNED_90_HZ_FRAME_PERIOD_NANOS = 1_000_000_000L / 90L

/** Submission order is independent from the round-robin order of latch-proof callbacks. */
internal data class NtkFrameOrderKey(
    val surfaceEpoch: Long,
    val frameSequence: Long
) : Comparable<NtkFrameOrderKey> {
    override fun compareTo(other: NtkFrameOrderKey): Int {
        val epochOrder = surfaceEpoch.compareTo(other.surfaceEpoch)
        return if (epochOrder != 0) epochOrder else frameSequence.compareTo(other.frameSequence)
    }
}

internal fun isStrictlyNewerNtkFrame(
    candidate: NtkFrameOrderKey,
    current: NtkFrameOrderKey
): Boolean = candidate > current

internal fun areAdjacentNtkFrames(
    first: NtkFrameOrderKey,
    second: NtkFrameOrderKey
): Boolean = first.surfaceEpoch == second.surfaceEpoch &&
    first.frameSequence < Long.MAX_VALUE &&
    second.frameSequence == first.frameSequence + 1L

/** Functional submission cadence deliberately does not inspect compositor proof state. */
internal fun functionalSubmissionDeltaNanos(
    first: NtkFrameOrderKey,
    firstGesture: Long,
    firstPostSwapNanos: Long,
    second: NtkFrameOrderKey,
    secondGesture: Long,
    secondPostSwapNanos: Long
): Long? {
    if (!areAdjacentNtkFrames(first, second) || firstGesture <= 0L ||
        firstGesture != secondGesture || firstPostSwapNanos <= 0L ||
        secondPostSwapNanos <= firstPostSwapNanos
    ) return null
    return secondPostSwapNanos - firstPostSwapNanos
}

/** Keeps both frames attached to the cadence delta so later phase attribution cannot drift. */
internal data class FunctionalFramePair<T>(
    val first: T,
    val second: T,
    val submissionDeltaNanos: Long
)

internal data class FunctionalGestureDelta(
    val gestureId: Long,
    val deltaNanos: Long
)

internal fun countFunctionalSubmissionPauses(
    deltasNanos: Iterable<Long>,
    pauseThresholdNanos: Long
): Int = deltasNanos.count { it >= pauseThresholdNanos }

internal fun functionalSubmissionMaxOverBudgetStreak(
    pairs: Iterable<FunctionalGestureDelta>,
    overBudgetThresholdNanos: Long
): Int {
    var currentGesture = 0L
    var currentStreak = 0
    var maximumStreak = 0
    pairs.forEach { pair ->
        if (pair.gestureId != currentGesture) {
            currentGesture = pair.gestureId
            currentStreak = 0
        }
        if (pair.deltaNanos > overBudgetThresholdNanos) {
            currentStreak++
            maximumStreak = maxOf(maximumStreak, currentStreak)
        } else {
            currentStreak = 0
        }
    }
    return maximumStreak
}

internal fun functionalRendererReadyToQueueWithinBudget(
    rendererReadyToQueueMaxNanos: Long,
    renderFrameMaxNanos: Long
): Boolean = rendererReadyToQueueMaxNanos >= 0L && renderFrameMaxNanos > 0L &&
    rendererReadyToQueueMaxNanos <= renderFrameMaxNanos

internal data class FunctionalRendererReadyFrameDebt(
    /** Valid renderer-ready samples that exceeded one pinned refresh period. */
    val missedFrames: Int,
    /** Sum of ceil(duration / period) - 1 across valid renderer-ready samples. */
    val droppedFrames: Int
)

/**
 * App-owned renderer-ready deadline debt. Invalid frame pairs must be rejected before this
 * function and remain visible through their independent invalid-pair counters.
 */
internal fun functionalRendererReadyFrameDebt(
    rendererReadyToQueueNanos: Iterable<Long>,
    framePeriodNanos: Long = PINNED_90_HZ_FRAME_PERIOD_NANOS
): FunctionalRendererReadyFrameDebt {
    require(framePeriodNanos > 0L)
    var missedFrames = 0
    var droppedFrames = 0
    rendererReadyToQueueNanos.forEach { durationNanos ->
        require(durationNanos >= 0L)
        if (durationNanos > framePeriodNanos) {
            missedFrames = Math.addExact(missedFrames, 1)
            val intervalDebt = (durationNanos - 1L) / framePeriodNanos
            droppedFrames = Math.addExact(droppedFrames, Math.toIntExact(intervalDebt))
        }
    }
    return FunctionalRendererReadyFrameDebt(missedFrames, droppedFrames)
}

internal fun <T> functionalFramePair(
    first: T,
    firstKey: NtkFrameOrderKey,
    firstGesture: Long,
    firstPostSwapNanos: Long,
    second: T,
    secondKey: NtkFrameOrderKey,
    secondGesture: Long,
    secondPostSwapNanos: Long
): FunctionalFramePair<T>? {
    val delta = functionalSubmissionDeltaNanos(
        firstKey,
        firstGesture,
        firstPostSwapNanos,
        secondKey,
        secondGesture,
        secondPostSwapNanos
    ) ?: return null
    return FunctionalFramePair(first, second, delta)
}

internal data class FunctionalPhaseDecomposition(
    val nextWorkStartDelayNanos: Long,
    val backendPreparationNanos: Long,
    val residualPriorTargetGateNanos: Long,
    val phaseAdmissionAfterBothReadyNanos: Long,
    val rendererReadyToQueueNanos: Long,
    val swapCallNanos: Long,
    val preparationOverlapNanos: Long,
    val reconstructedSubmissionDeltaNanos: Long
)

internal data class FunctionalFramePhaseTimestamps(
    val drawBeginNanos: Long,
    val backendWaitReturnNanos: Long,
    val preSwapNanos: Long,
    val postSwapNanos: Long,
    val targetRetirementCompleteNanos: Long
)

internal fun hasCompleteFunctionalPhaseOrder(
    frame: FunctionalFramePhaseTimestamps
): Boolean = frame.drawBeginNanos > 0L &&
    frame.backendWaitReturnNanos >= frame.drawBeginNanos &&
    frame.preSwapNanos >= frame.backendWaitReturnNanos &&
    frame.postSwapNanos >= frame.preSwapNanos &&
    frame.targetRetirementCompleteNanos >= frame.postSwapNanos

/**
 * Exact single-submission retire/prepare-overlap decomposition for adjacent
 * submitted generations g and g+1:
 *
 * Qn-Qg = (Dn-Qg) + (Bn-Dn) + max(0,Tg-Bn)
 *         + (Pn-max(Bn,Tg)) + (Qn-Pn).
 */
internal fun functionalPhaseDecompositionNanos(
    first: FunctionalFramePhaseTimestamps,
    second: FunctionalFramePhaseTimestamps,
    submissionDeltaNanos: Long
): FunctionalPhaseDecomposition? {
    if (!hasCompleteFunctionalPhaseOrder(first) || !hasCompleteFunctionalPhaseOrder(second) ||
        submissionDeltaNanos <= 0L
    ) return null

    val decomposition = try {
        val qg = first.postSwapNanos
        val dn = second.drawBeginNanos
        val bn = second.backendWaitReturnNanos
        val tg = first.targetRetirementCompleteNanos
        val pn = second.preSwapNanos
        val qn = second.postSwapNanos
        if (dn < qg || bn < dn || pn < maxOf(bn, tg) || qn < pn) return null
        val nextWorkStart = Math.subtractExact(dn, qg)
        val backendPreparation = Math.subtractExact(bn, dn)
        val residualPriorTarget = maxOf(0L, Math.subtractExact(tg, bn))
        val phaseAdmission = Math.subtractExact(pn, maxOf(bn, tg))
        val rendererReady = Math.subtractExact(pn, bn)
        val swapCall = Math.subtractExact(qn, pn)
        val overlap = maxOf(0L, Math.subtractExact(minOf(bn, tg), dn))
        val reconstructed = listOf(
            nextWorkStart,
            backendPreparation,
            residualPriorTarget,
            phaseAdmission,
            swapCall
        ).fold(0L) { sum, component -> Math.addExact(sum, component) }
        FunctionalPhaseDecomposition(
            nextWorkStart,
            backendPreparation,
            residualPriorTarget,
            phaseAdmission,
            rendererReady,
            swapCall,
            overlap,
            reconstructed
        )
    } catch (_: ArithmeticException) {
        return null
    }
    if (decomposition.reconstructedSubmissionDeltaNanos != submissionDeltaNanos ||
        second.postSwapNanos - first.postSwapNanos != submissionDeltaNanos
    ) return null
    if (decomposition.rendererReadyToQueueNanos !=
        Math.addExact(
            decomposition.residualPriorTargetGateNanos,
            decomposition.phaseAdmissionAfterBothReadyNanos
        )
    ) return null
    return decomposition
}
