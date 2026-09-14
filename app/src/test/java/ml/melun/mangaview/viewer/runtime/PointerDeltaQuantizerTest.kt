package ml.melun.mangaview.viewer.runtime

import ml.melun.mangaview.viewer.FixedPx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PointerDeltaQuantizerTest {
    private fun unitPixels(units: Long): Double = units.toDouble() / FixedPx.UNITS_PER_PIXEL

    private fun subUnitPixels(units: Double): Double = units / FixedPx.UNITS_PER_PIXEL

    private fun floatDelta(from: Float, to: Float): Double = (to - from).toDouble()

    @Test fun regroupingFractionalStepsIsInvariant() {
        val step = floatDelta(100.6f, 101.2f)
        val quantizer = PointerDeltaQuantizer()
        val split = quantizer.apply(step).units + quantizer.apply(step).units

        assertEquals(FixedPx.fromPixels(step + step).units, split)
    }

    @Test fun floatCoordinateDerivedDeltasTelescope() {
        val first = floatDelta(120.3f, 121.1f)
        val second = floatDelta(121.1f, 121.4f)
        val quantizer = PointerDeltaQuantizer()
        val emitted = quantizer.apply(first).units + quantizer.apply(second).units

        assertEquals(FixedPx.fromPixels(first + second).units, emitted)
    }

    @Test fun thousandFractionalStepsDoNotDrift() {
        val step = floatDelta(0f, 0.0007f)
        val quantizer = PointerDeltaQuantizer()
        var cumulative = 0.0
        var emitted = 0L
        var nonzeroEmissions = 0
        repeat(1_000) {
            cumulative += step
            val value = quantizer.apply(step)
            emitted += value.units
            if (value.units != 0L) nonzeroEmissions++
        }

        assertEquals(FixedPx.fromPixels(cumulative).units, emitted)
        assertTrue(nonzeroEmissions > 0)
    }

    @Test fun subQuantumStepKeepsItsZeroReceiptAndCumulativeRound() {
        val quantizer = PointerDeltaQuantizer()
        val first = quantizer.apply(subUnitPixels(0.5))
        val second = quantizer.apply(subUnitPixels(1.0))

        assertEquals(FixedPx.ZERO, first)
        assertEquals(FixedPx.fromPixels(subUnitPixels(1.5)).units, first.units + second.units)
    }

    @Test fun reversalStartsAnIndependentRun() {
        val quantizer = PointerDeltaQuantizer()
        val first = quantizer.apply(0.6)
        val second = quantizer.apply(0.6)

        assertEquals(FixedPx.fromPixels(1.2).units, first.units + second.units)
        assertEquals(FixedPx.fromPixels(-0.2).units, quantizer.apply(-0.2).units)
    }

    @Test fun zeroSegmentsDoNotTouchTheActiveRun() {
        val quantizer = PointerDeltaQuantizer()
        quantizer.apply(0.6)
        val zero = quantizer.apply(0.0)
        val rest = quantizer.apply(0.6)

        assertEquals(FixedPx.ZERO, zero)
        assertEquals(FixedPx.fromPixels(1.2).units, FixedPx.fromPixels(0.6).units + rest.units)
    }

    @Test fun beginResetsTheRunButRebaseDoesNot() {
        val reset = PointerDeltaQuantizer()
        val resetFirst = reset.apply(0.6)
        reset.begin()
        val resetSecond = reset.apply(0.6)
        assertEquals(resetFirst.units, resetSecond.units)

        val rebased = PointerDeltaQuantizer()
        rebased.apply(0.6)
        rebased.rebase()
        val resumed = rebased.apply(0.6)
        assertEquals(FixedPx.fromPixels(1.2).units, FixedPx.fromPixels(0.6).units + resumed.units)
    }

    @Test fun negativeHalfEvenTieFollowsTheSameCumulativeRound() {
        val quantizer = PointerDeltaQuantizer()
        val first = quantizer.apply(subUnitPixels(-0.5))
        val second = quantizer.apply(subUnitPixels(-1.0))

        assertEquals(FixedPx.ZERO, first)
        assertEquals(FixedPx.fromPixels(subUnitPixels(-1.5)).units, first.units + second.units)
    }

    @Test fun thousandNegativeFractionalStepsDoNotDrift() {
        val step = floatDelta(0f, -0.0007f)
        val quantizer = PointerDeltaQuantizer()
        var cumulative = 0.0
        var emitted = 0L
        var nonzeroEmissions = 0
        repeat(1_000) {
            cumulative += step
            val value = quantizer.apply(step)
            emitted += value.units
            if (value.units != 0L) nonzeroEmissions++
        }

        assertEquals(FixedPx.fromPixels(cumulative).units, emitted)
        assertTrue(nonzeroEmissions > 0)
    }
}
