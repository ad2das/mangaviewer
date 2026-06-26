package ml.melun.mangaview.reader;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
    public void heightResolveWaitsUntilRecentScrollSettles() {
        assertFalse(ReaderSurfaceView.shouldAdjustScrollForChangedPageHeightForTest(
                true, false, false, true, false, 900f, 1000f));
        assertFalse(ReaderSurfaceView.shouldAdjustScrollForChangedPageHeightForTest(
                false, false, false, true, true, 900f, 1000f));
    }

    @Test
    public void heightResolveDoesNotCorrectScrollDuringActiveInputOrFling() {
        assertFalse(ReaderSurfaceView.shouldAdjustScrollForChangedPageHeightForTest(
                false, true, false, true, false, 900f, 1000f));
        assertFalse(ReaderSurfaceView.shouldAdjustScrollForChangedPageHeightForTest(
                false, false, true, true, false, 900f, 1000f));
        assertFalse(ReaderSurfaceView.shouldAdjustScrollForChangedPageHeightForTest(
                false, false, false, false, false, 900f, 1000f));
    }

    @Test
    public void pendingResolveAnchorRestoreWaitsForStoppedInput() {
        assertTrue(ReaderSurfaceView.shouldRestoreAnchorAfterPendingResolvesForTest(
                false, false, false, true, false));
        assertFalse(ReaderSurfaceView.shouldRestoreAnchorAfterPendingResolvesForTest(
                false, false, false, true, true));
        assertFalse(ReaderSurfaceView.shouldRestoreAnchorAfterPendingResolvesForTest(
                true, false, false, true, false));
        assertFalse(ReaderSurfaceView.shouldRestoreAnchorAfterPendingResolvesForTest(
                false, true, false, true, false));
        assertFalse(ReaderSurfaceView.shouldRestoreAnchorAfterPendingResolvesForTest(
                false, false, true, true, false));
        assertFalse(ReaderSurfaceView.shouldRestoreAnchorAfterPendingResolvesForTest(
                false, false, false, false, false));
    }

    @Test
    public void preparedAutoCutOnlySplitsWideSpreadPages() {
        assertFalse(ReaderSession.shouldSplitPreparedBitmapForTest(true, true, 720, 1600));
        assertFalse(ReaderSession.shouldSplitPreparedBitmapForTest(true, false, 1600, 1200));
        assertFalse(ReaderSession.shouldSplitPreparedBitmapForTest(false, true, 1600, 1200));
        assertTrue(ReaderSession.shouldSplitPreparedBitmapForTest(true, true, 1600, 1200));
    }

    @Test
    public void earlyNtkPartialSubsetDoesNotReplaceFullGeneratedList() {
        List<String> full = generatedNtkWebtoonImages(57);
        List<String> partial = full.subList(0, 3);

        assertFalse(ReaderImageCache.shouldReplaceWithVerifiedGeneratedSubsetForTest(full, partial));
    }

    private static List<String> generatedNtkWebtoonImages(int count) {
        ArrayList<String> images = new ArrayList<>();
        for(int page = 1; page <= count; page++) {
            images.add(String.format(Locale.ROOT,
                    "https://fifa.worldcup73.xyz/black/episodes/12046/1186913/p%03d.jpg",
                    page));
        }
        return images;
    }
}
