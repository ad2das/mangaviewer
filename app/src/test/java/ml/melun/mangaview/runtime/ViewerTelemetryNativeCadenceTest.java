package ml.melun.mangaview.runtime;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ViewerTelemetryNativeCadenceTest {
    private static final long REFRESH = 16_666_667L;
    private static final long PRESENTED = 1_000_000_000L;

    @Test
    public void sparseFreshInputIsNotMisclassifiedAsRendererJank() {
        assertFalse(ViewerFrameCadencePolicy.isDemandBackedSlowInterval(
                80_000_000L,
                REFRESH,
                PRESENTED,
                PRESENTED - 37_000_000L,
                PRESENTED - 37_000_000L));
    }

    @Test
    public void nearSimultaneousSamplesRemainOnePhysicalInputBatch() {
        assertFalse(ViewerFrameCadencePolicy.isDemandBackedSlowInterval(
                80_000_000L,
                REFRESH,
                PRESENTED,
                PRESENTED - 37_000_000L,
                PRESENTED - 36_000_000L));
    }

    @Test
    public void queuedInputOlderThanTheFrameDeadlineIsRealJank() {
        assertTrue(ViewerFrameCadencePolicy.isDemandBackedSlowInterval(
                80_000_000L,
                REFRESH,
                PRESENTED,
                PRESENTED - 70_000_000L,
                PRESENTED - 3_000_000L));
    }

    @Test
    public void isolatedInputTakingOneHundredMillisecondsIsARealStall() {
        assertTrue(ViewerFrameCadencePolicy.isDemandBackedSlowInterval(
                175_000_000L,
                REFRESH,
                PRESENTED,
                PRESENTED - 100_000_000L,
                PRESENTED - 100_000_000L));
    }

    @Test
    public void missingOrMalformedCausalEvidenceFailsClosed() {
        assertTrue(ViewerFrameCadencePolicy.isDemandBackedSlowInterval(
                80_000_000L, REFRESH, PRESENTED, 0L, 0L));
        assertTrue(ViewerFrameCadencePolicy.isDemandBackedSlowInterval(
                80_000_000L, REFRESH, PRESENTED, PRESENTED, PRESENTED - 1L));
    }

    @Test
    public void onTimeIntervalNeverBecomesSlowBecauseOfOldInput() {
        assertFalse(ViewerFrameCadencePolicy.isDemandBackedSlowInterval(
                16_000_000L,
                REFRESH,
                PRESENTED,
                PRESENTED - 500_000_000L,
                PRESENTED - 1_000_000L));
    }
}
