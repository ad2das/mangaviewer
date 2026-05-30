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
        assertEquals(3, ReaderPipelinePolicy.decodeParallelism(true));
        assertEquals(720, ReaderPipelinePolicy.BUSY_DECODE_WIDTH);
    }

    @Test
    public void idleWindowCanFillAroundAnchorWithoutFanout() {
        assertEquals(6, ReaderPipelinePolicy.windowBefore(false));
        assertTrue(ReaderPipelinePolicy.windowAfter(false) <= ReaderPipelinePolicy.windowAfter(true));
        assertEquals(2, ReaderPipelinePolicy.decodeParallelism(false));
        assertTrue(ReaderPipelinePolicy.IDLE_WINDOW_AFTER <= 10);
    }

    @Test
    public void heightResolveAdjustsOnlyDuringPositionRestore() {
        assertFalse(ReaderSurfaceView.shouldAdjustScrollForChangedPageHeightForTest(
                false, false, false, false, true, 900f, 1000f));
        assertTrue(ReaderSurfaceView.shouldAdjustScrollForChangedPageHeightForTest(
                true, false, false, false, true, 900f, 1000f));
        assertFalse(ReaderSurfaceView.shouldAdjustScrollForChangedPageHeightForTest(
                true, false, false, false, true, 1200f, 1000f));
        assertFalse(ReaderSurfaceView.shouldAdjustScrollForChangedPageHeightForTest(
                true, true, false, false, true, 900f, 1000f));
        assertFalse(ReaderSurfaceView.shouldAdjustScrollForChangedPageHeightForTest(
                true, false, true, false, true, 900f, 1000f));
        assertFalse(ReaderSurfaceView.shouldAdjustScrollForChangedPageHeightForTest(
                true, false, false, true, true, 900f, 1000f));
        assertFalse(ReaderSurfaceView.shouldAdjustScrollForChangedPageHeightForTest(
                true, false, false, false, false, 900f, 1000f));
    }

    @Test
    public void preparedAutoCutOnlySplitsWideSpreadPages() {
        assertFalse(ReaderSession.shouldSplitPreparedBitmapForTest(true, true, 720, 1600));
        assertFalse(ReaderSession.shouldSplitPreparedBitmapForTest(true, false, 1600, 1200));
        assertFalse(ReaderSession.shouldSplitPreparedBitmapForTest(false, true, 1600, 1200));
        assertTrue(ReaderSession.shouldSplitPreparedBitmapForTest(true, true, 1600, 1200));
    }
}
