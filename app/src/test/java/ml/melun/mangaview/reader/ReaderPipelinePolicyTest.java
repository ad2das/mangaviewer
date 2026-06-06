package ml.melun.mangaview.reader;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ReaderPipelinePolicyTest {
    @Test
    public void busyScrollKeepsBothDirectionsDecoded() {
        assertEquals(8, ReaderPipelinePolicy.windowBefore(true));
        assertEquals(14, ReaderPipelinePolicy.windowAfter(true));
        assertEquals(5, ReaderPipelinePolicy.decodeParallelism(true));
        assertTrue(ReaderPipelinePolicy.BUSY_DECODE_WIDTH >= 1080);
    }

    @Test
    public void idleWindowCanFillAroundAnchorWithoutFanout() {
        assertEquals(6, ReaderPipelinePolicy.windowBefore(false));
        assertTrue(ReaderPipelinePolicy.windowAfter(false) <= ReaderPipelinePolicy.windowAfter(true));
        assertEquals(3, ReaderPipelinePolicy.decodeParallelism(false));
        assertTrue(ReaderPipelinePolicy.IDLE_WINDOW_AFTER <= 10);
    }

    @Test
    public void heightResolveAdjustsOnlyPagesFullyAboveViewport() {
        assertTrue(ReaderSurfaceView.shouldAdjustScrollForChangedPageHeightForTest(
                false, false, false, true, false, 900f, 1000f));
        assertFalse(ReaderSurfaceView.shouldAdjustScrollForChangedPageHeightForTest(
                false, false, false, true, false, 1200f, 1000f));
    }

    @Test
    public void heightResolveDoesNotCorrectScrollDuringOrRightAfterFling() {
        assertFalse(ReaderSurfaceView.shouldAdjustScrollForChangedPageHeightForTest(
                true, false, false, true, false, 900f, 1000f));
        assertFalse(ReaderSurfaceView.shouldAdjustScrollForChangedPageHeightForTest(
                false, true, false, true, false, 900f, 1000f));
        assertFalse(ReaderSurfaceView.shouldAdjustScrollForChangedPageHeightForTest(
                false, false, true, true, false, 900f, 1000f));
        assertFalse(ReaderSurfaceView.shouldAdjustScrollForChangedPageHeightForTest(
                false, false, false, false, false, 900f, 1000f));
        assertFalse(ReaderSurfaceView.shouldAdjustScrollForChangedPageHeightForTest(
                false, false, false, true, true, 900f, 1000f));
    }

    @Test
    public void pendingResolveAnchorRestoreOnlyWhenReaderIsIdle() {
        assertTrue(ReaderSurfaceView.shouldRestoreAnchorAfterPendingResolvesForTest(
                false, false, false, true, false));
        assertFalse(ReaderSurfaceView.shouldRestoreAnchorAfterPendingResolvesForTest(
                true, false, false, true, false));
        assertFalse(ReaderSurfaceView.shouldRestoreAnchorAfterPendingResolvesForTest(
                false, true, false, true, false));
        assertFalse(ReaderSurfaceView.shouldRestoreAnchorAfterPendingResolvesForTest(
                false, false, true, true, false));
        assertFalse(ReaderSurfaceView.shouldRestoreAnchorAfterPendingResolvesForTest(
                false, false, false, false, false));
        assertFalse(ReaderSurfaceView.shouldRestoreAnchorAfterPendingResolvesForTest(
                false, false, false, true, true));
    }

    @Test
    public void preparedAutoCutOnlySplitsWideSpreadPages() {
        assertFalse(ReaderSession.shouldSplitPreparedBitmapForTest(true, true, 720, 1600));
        assertFalse(ReaderSession.shouldSplitPreparedBitmapForTest(true, false, 1600, 1200));
        assertFalse(ReaderSession.shouldSplitPreparedBitmapForTest(false, true, 1600, 1200));
        assertTrue(ReaderSession.shouldSplitPreparedBitmapForTest(true, true, 1600, 1200));
    }
}
