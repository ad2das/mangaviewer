package ml.melun.mangaview.reader;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ReaderSurfaceViewTest {
    @Test
    public void shortFastSwipeStartsFling() {
        assertTrue(ReaderSurfaceView.shouldStartFlingForTest(42f, 2400, 50, 16));
    }

    @Test
    public void tapSizedMovementDoesNotStartFling() {
        assertFalse(ReaderSurfaceView.shouldStartFlingForTest(8f, 2400, 50, 16));
    }

    @Test
    public void slowDragDoesNotStartFling() {
        assertFalse(ReaderSurfaceView.shouldStartFlingForTest(80f, 30, 50, 16));
    }

    @Test
    public void pendingHeightResolveWaitsForStableIdle() {
        assertFalse(ReaderSurfaceView.shouldApplyPendingPageResolvesForTest(true, 2000L, 1000L, 450L));
        assertFalse(ReaderSurfaceView.shouldApplyPendingPageResolvesForTest(false, 1200L, 1000L, 450L));
        assertTrue(ReaderSurfaceView.shouldApplyPendingPageResolvesForTest(false, 1450L, 1000L, 450L));
        assertTrue(ReaderSurfaceView.shouldApplyPendingPageResolvesForTest(false, 100L, 0L, 450L));
    }
}
