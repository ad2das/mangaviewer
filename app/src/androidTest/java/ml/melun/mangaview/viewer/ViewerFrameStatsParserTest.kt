package ml.melun.mangaview.viewer

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ViewerFrameStatsParserTest {
    @Test
    fun freshMotionBatchEachFrameStillCountsEveryCadenceInterval() {
        val summary = ViewerFrameStatsParser.parseMotion(
            longArrayOf(1, 110_000_000, 2, 130_000_000, 3, 150_000_000),
            1, listOf(100_000_000L..160_000_000L), 20_000_000,
            longArrayOf(111_000_000, 131_000_000, 151_000_000), longArrayOf(99_000_000),
        )
        assertEquals(2, summary.sampleCount)
        assertEquals(20_000_000L, summary.maximumNanos)
        assertEquals(0, summary.missedFrameCount)
    }

    @Test
    fun changingMotionBatchesCannotHideARealFrozenCadence() {
        val summary = ViewerFrameStatsParser.parseMotion(
            longArrayOf(1, 110_000_000, 2, 230_000_000, 3, 510_000_000, 4, 530_000_000),
            1, listOf(100_000_000L..240_000_000L, 500_000_000L..540_000_000L), 20_000_000,
            longArrayOf(111_000_000, 231_000_000, 511_000_000, 531_000_000),
            longArrayOf(99_000_000, 499_000_000),
        )
        assertEquals(2, summary.sampleCount)
        assertEquals(120_000_000L, summary.maximumNanos)
        assertEquals(1, summary.freezeCount)
        assertEquals(5, summary.missedFrameCount)
    }

    @Test
    fun motionRespondsAtActualApplicationEvenWhenVsyncPrecedesInput() {
        val summary = ViewerFrameStatsParser.parseMotion(
            packedSamples = longArrayOf(1L, 90_000_000L, 1L, 110_000_000L, 1L, 130_000_000L),
            startedAtNanos = 1L,
            interactionWindows = listOf(100_000_000L..150_000_000L),
            refreshPeriodNanos = 20_000_000L,
            applicationTimestamps = longArrayOf(108_000_000L, 120_000_000L, 145_000_000L),
            injectedGestureStarts = longArrayOf(95_000_000L),
        )

        assertEquals(13_000_000L, summary.maximumResponseNanos)
        assertEquals(1, summary.coveredInteractionWindowCount)
        assertEquals(0, summary.responseFreezeCount)
        // Cadence continues to use the two VSYNC timestamps within the observed window.
        assertEquals(20_000_000L, summary.maximumNanos)
    }

    @Test
    fun delayedActualMotionApplicationStillFailsTheResponseFreezeGate() {
        val summary = ViewerFrameStatsParser.parseMotion(
            packedSamples = longArrayOf(1L, 90_000_000L, 1L, 210_000_000L),
            startedAtNanos = 1L,
            interactionWindows = listOf(100_000_000L..240_000_000L),
            refreshPeriodNanos = 20_000_000L,
            applicationTimestamps = longArrayOf(205_000_000L, 225_000_000L),
            injectedGestureStarts = longArrayOf(95_000_000L),
        )

        assertEquals(110_000_000L, summary.maximumResponseNanos)
        assertEquals(1, summary.responseFreezeCount)
    }

    @Test
    fun inputDeliveryDelayRemainsPartOfTheMeasuredResponse() {
        val summary = ViewerFrameStatsParser.parseMotion(
            packedSamples = longArrayOf(1L, 190_000_000L),
            startedAtNanos = 1L,
            interactionWindows = listOf(200_000_000L..225_000_000L),
            refreshPeriodNanos = 20_000_000L,
            applicationTimestamps = longArrayOf(210_000_000L),
            injectedGestureStarts = longArrayOf(100_000_000L),
        )

        assertEquals(110_000_000L, summary.maximumResponseNanos)
        assertEquals(1, summary.responseFreezeCount)
        assertEquals(1, summary.coveredInteractionWindowCount)
    }

    @Test
    fun presentationThatStopsBeforeGestureEndsCannotHideItsFrozenTail() {
        val summary = ViewerFrameStatsParser.parseSurface(
            "16666667\n0 110000000 0\n0 126666667 0\n",
            100_000_000L,
            listOf(100_000_000L..300_000_000L),
        )
        assertEquals(173_333_333L, summary.maximumTailNanos)
        assertEquals(1, summary.tailFreezeCount)
        org.junit.Assert.assertTrue(summary.missedFrameCount >= 9)
    }

    @Test
    fun renderUsesMeasuredCpuDurationsOnlyInsideInteractionWindows() {
        val samples = longArrayOf(
            90_000_000L, 15_000_000L,
            110_000_000L, 4_000_000L,
            120_000_000L, -1L,
            130_000_000L, 6_000_000L,
            310_000_000L, 120_000_000L,
        )

        val summary = ViewerFrameStatsParser.parseRender(
            packedSamples = samples,
            startedAtNanos = 100_000_000L,
            interactionWindows = listOf(
                100_000_000L..200_000_000L,
                300_000_000L..400_000_000L,
            ),
            refreshPeriodNanos = 16_666_667L,
        )

        assertEquals("hwui-draw-cpu", summary.source)
        assertEquals(3, summary.sampleCount)
        assertEquals(120_000_000L, summary.p95Nanos)
        assertEquals(1, summary.freezeCount)
        assertEquals(2, summary.interactionWindowCount)
        assertEquals(2, summary.coveredInteractionWindowCount)
    }

    @Test
    fun gfxUsesOnlyValidUniqueAppFramesInsideInteractionWindows() {
        val raw = """
            Flags,IntendedVsync,FrameCompleted
            0,110000000,120000000
            0,110000000,120000000
            1,130000000,140000000
            0,150000000,270000000
            0,90000000,100000000
            0,170000000,9223372036854775807
        """.trimIndent()

        val summary = ViewerFrameStatsParser.parseGfx(
            raw = raw,
            startedAtNanos = 100_000_000L,
            interactionWindows = listOf(100_000_000L..200_000_000L, 300_000_000L..400_000_000L),
        )

        assertEquals("app-gfxinfo", summary.source)
        assertEquals(2, summary.sampleCount)
        assertEquals(120_000_000L, summary.p95Nanos)
        assertEquals(1, summary.freezeCount)
        assertEquals(2, summary.interactionWindowCount)
        assertEquals(1, summary.coveredInteractionWindowCount)
    }

    @Test
    fun surfaceMeasuresFirstResponseAndCadenceWithoutSentinelsOrDuplicates() {
        val raw = """
            8333333
            100000000 110000000 111000000
            110000000 120000000 121000000
            120000000 140000000 141000000
            110000000 120000000 121000000
            200000000 250000000 251000000
            0 0 0
            0 9223372036854775807 0
        """.trimIndent()

        val summary = ViewerFrameStatsParser.parseSurface(
            raw = raw,
            startedAtNanos = 100_000_000L,
            interactionWindows = listOf(100_000_000L..150_000_000L, 200_000_000L..260_000_000L),
        )

        assertEquals("viewer-surface-presentation", summary.source)
        assertEquals(2, summary.sampleCount)
        assertEquals(20_000_000L, summary.p95Nanos)
        assertEquals(1, summary.missedFrameCount)
        assertEquals(1.0 / 3.0, summary.missedFrameRatio, 0.0)
        assertEquals(0, summary.freezeCount)
        assertEquals(2, summary.responseSampleCount)
        assertEquals(50_000_000L, summary.p95ResponseNanos)
        assertEquals(0, summary.responseFreezeCount)
        assertEquals(2, summary.interactionWindowCount)
        assertEquals(2, summary.coveredInteractionWindowCount)
    }

    @Test
    fun overlappingInteractionWindowsDoNotDoubleCountSurfaceIntervals() {
        val raw = """
            8333333
            100000000 110000000 111000000
            150000000 160000000 161000000
        """.trimIndent()

        val summary = ViewerFrameStatsParser.parseSurface(
            raw = raw,
            startedAtNanos = 100_000_000L,
            interactionWindows = listOf(100_000_000L..150_000_000L, 140_000_000L..200_000_000L),
        )

        assertEquals(1, summary.sampleCount)
        assertEquals(50_000_000L, summary.maximumNanos)
        assertEquals(1, summary.responseSampleCount)
        assertEquals(2, summary.coveredInteractionWindowCount)
    }

    @Test
    fun surfaceFirstResponseFreezeIsCountedSeparatelyFromCadence() {
        val raw = """
            8333333
            100000000 210000000 211000000
        """.trimIndent()

        val summary = ViewerFrameStatsParser.parseSurface(
            raw = raw,
            startedAtNanos = 100_000_000L,
            interactionWindows = listOf(100_000_000L..220_000_000L),
        )

        assertEquals(0, summary.sampleCount)
        assertEquals(1, summary.responseSampleCount)
        assertEquals(110_000_000L, summary.p95ResponseNanos)
        assertEquals(1, summary.responseFreezeCount)
    }

    @Test
    fun missedFramesUseCeilingAtRefreshBoundaries() {
        val budget = 10_000_000L
        val summary = ViewerFrameStatsParser.parseRender(
            packedSamples = longArrayOf(
                110_000_000L, budget,
                120_000_000L, budget + 1L,
                130_000_000L, budget * 2L,
                140_000_000L, budget * 2L + 1L,
            ),
            startedAtNanos = 100_000_000L,
            interactionWindows = listOf(100_000_000L..200_000_000L),
            refreshPeriodNanos = budget,
        )

        assertEquals(4, summary.sampleCount)
        assertEquals(4, summary.missedFrameCount)
    }
}
