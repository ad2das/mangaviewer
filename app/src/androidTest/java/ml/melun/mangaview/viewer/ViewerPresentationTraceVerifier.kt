package ml.melun.mangaview.viewer

import ml.melun.mangaview.viewer.runtime.NativePresentationEvidence

internal object ViewerPresentationTraceVerifier {
    fun verify(
        evidence: Collection<NativePresentationEvidence>,
        gestureWindows: Collection<LongRange>,
    ): List<String> = verifyDirected(
        evidence,
        gestureWindows.map { PresentationGestureWindow(it, TelemetryDirection.FORWARD) },
    )

    fun verifyDirected(
        evidence: Collection<NativePresentationEvidence>,
        gestureWindows: Collection<PresentationGestureWindow>,
    ): List<String> {
        val violations = mutableListOf<String>()
        gestureWindows.sortedBy { it.range.first }.forEach { window ->
            val active = evidence.filter { it.presentedNanos in window.range }
            if (active.isEmpty()) {
                violations.addOnce(
                    "A real gesture window had no Surface presentation evidence: ${window.range}",
                )
            }
            active.firstOrNull { !it.fullVisualCoverage }?.let { sample ->
                violations.addOnce(
                    "A presented gesture frame left part of the viewport visually uncovered: " +
                        sample.describe(),
                )
            }
            active.groupBy { it.rendererIdentity to it.generation }.values.forEach { rendererTrace ->
                verifyOrderedTrace(
                    rendererTrace.sortedWith(
                        compareBy(
                            NativePresentationEvidence::presentedNanos,
                            NativePresentationEvidence::token,
                        ),
                    ),
                    window.direction,
                    violations,
                )
            }
        }
        return violations.distinct()
    }

    private fun verifyOrderedTrace(
        trace: List<NativePresentationEvidence>,
        direction: TelemetryDirection,
        violations: MutableList<String>,
    ) {
        trace.zipWithNext().forEach { (before, after) ->
            if (before.anchorOrdinal < 0 || after.anchorOrdinal < 0) return@forEach
            val comparison = semanticPositionComparison(before, after)
            if (direction == TelemetryDirection.FORWARD && comparison < 0) {
                violations.addOnce(
                    "Presented semantic position moved backward during a forward gesture: " +
                        "${before.describe()} -> ${after.describe()}",
                )
                return
            }
            if (direction == TelemetryDirection.REVERSE && comparison > 0) {
                violations.addOnce(
                    "Presented semantic position moved forward during a reverse gesture: " +
                        "${before.describe()} -> ${after.describe()}",
                )
                return
            }
            if (comparison != 0 && jumpedTooFar(before, after)) {
                violations.addOnce(
                    "Presented semantic position jumped farther than real input can move: " +
                        "${before.describe()} -> ${after.describe()}",
                )
            }
        }
    }

    private fun semanticPositionComparison(
        before: NativePresentationEvidence,
        after: NativePresentationEvidence,
    ): Int {
        val ordinal = after.anchorOrdinal.compareTo(before.anchorOrdinal)
        return if (ordinal != 0) ordinal else after.anchorOffsetUnits.compareTo(before.anchorOffsetUnits)
    }

    private fun jumpedTooFar(
        before: NativePresentationEvidence,
        after: NativePresentationEvidence,
    ): Boolean {
        val delta = absoluteDifference(after.scrollOffsetUnits, before.scrollOffsetUnits)
        if (delta == 0L) return false
        val elapsed = (after.presentedNanos - before.presentedNanos).coerceAtLeast(0L)
        val velocityAllowance = multiplyDivideSaturated(
            MAXIMUM_INPUT_UNITS_PER_SECOND,
            elapsed,
            NANOS_PER_SECOND,
        )
        val allowance = saturatedAdd(maxOf(before.viewportHeightUnits, after.viewportHeightUnits), velocityAllowance)
        return delta > allowance
    }

    private fun absoluteDifference(left: Long, right: Long): Long = if (left >= right) {
        (left - right).takeIf { it >= 0L } ?: Long.MAX_VALUE
    } else {
        (right - left).takeIf { it >= 0L } ?: Long.MAX_VALUE
    }

    private fun multiplyDivideSaturated(value: Long, multiplier: Long, divisor: Long): Long {
        if (value <= 0L || multiplier <= 0L) return 0L
        val quotient = multiplier / divisor
        val remainder = multiplier % divisor
        val whole = if (quotient > Long.MAX_VALUE / value) Long.MAX_VALUE else value * quotient
        if (whole == Long.MAX_VALUE) return whole
        val fractional = if (remainder > Long.MAX_VALUE / value) {
            Long.MAX_VALUE
        } else {
            value * remainder / divisor
        }
        return saturatedAdd(whole, fractional)
    }

    private fun saturatedAdd(left: Long, right: Long): Long =
        if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private fun NativePresentationEvidence.describe(): String =
        "renderer=$rendererIdentity generation=$generation token=$token at=$presentedNanos " +
            "scroll=$scrollOffsetUnits anchor=$anchorOrdinal/$anchorOffsetUnits"

    private fun MutableList<String>.addOnce(message: String) {
        val category = message.substringBefore(':')
        if (none { it.substringBefore(':') == category }) add(message)
    }

    private const val NANOS_PER_SECOND = 1_000_000_000L
    private const val MAXIMUM_INPUT_UNITS_PER_SECOND = 64_000L * FixedPx.UNITS_PER_PIXEL
}

internal data class PresentationGestureWindow(
    val range: LongRange,
    val direction: TelemetryDirection,
)
