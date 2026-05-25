package ml.melun.mangaview.runtime;

import com.bumptech.glide.Priority;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

public class ViewerPagePipelineTest {
    @Test
    public void performancePresetKeepsPreviewDecodesAheadOfTheFlingPath() {
        assertEquals(24, ViewerPagePipeline.forwardUrlWindow(false));
        assertEquals(6, ViewerPagePipeline.initialDiskWindow(false));
        assertEquals(6, ViewerPagePipeline.scrollDiskWindow(false));
        assertEquals(8, ViewerPagePipeline.busyUrlWindow(false));
        assertEquals(2, ViewerPagePipeline.busyDiskWindow(false));
        assertEquals(1, ViewerPagePipeline.busyDecodedWindow(false));
        assertEquals(4, ViewerPagePipeline.forwardDiskWindow(false));
        assertEquals(1, ViewerPagePipeline.forwardDecodedWindow(false));
        assertEquals(2, ViewerPagePipeline.idleDecodedWindow(false));
        assertEquals(1, ViewerPagePipeline.boundaryDecodedWindow(false));
        assertEquals(2, ViewerPagePipeline.futureDiskWindow(false));
        assertEquals(0, ViewerPagePipeline.futureDecodedWindow(false));
        assertEquals(1, ViewerPagePipeline.nextEpisodeDepth(false));
        assertEquals(0, ViewerPagePipeline.previousEpisodeDepth(false));
    }

    @Test
    public void dataSaverKeepsPipelineConservative() {
        assertEquals(8, ViewerPagePipeline.forwardUrlWindow(true));
        assertEquals(3, ViewerPagePipeline.initialDiskWindow(true));
        assertEquals(3, ViewerPagePipeline.scrollDiskWindow(true));
        assertEquals(4, ViewerPagePipeline.busyUrlWindow(true));
        assertEquals(1, ViewerPagePipeline.busyDiskWindow(true));
        assertEquals(0, ViewerPagePipeline.busyDecodedWindow(true));
        assertEquals(2, ViewerPagePipeline.forwardDiskWindow(true));
        assertEquals(1, ViewerPagePipeline.forwardDecodedWindow(true));
        assertEquals(1, ViewerPagePipeline.idleDecodedWindow(true));
        assertEquals(1, ViewerPagePipeline.boundaryDecodedWindow(true));
        assertEquals(0, ViewerPagePipeline.futureDiskWindow(true));
        assertEquals(0, ViewerPagePipeline.futureDecodedWindow(true));
        assertEquals(1, ViewerPagePipeline.nextEpisodeDepth(true));
        assertEquals(0, ViewerPagePipeline.previousEpisodeDepth(true));
    }

    @Test
    public void strongerWarmupRequestsReplaceWeakerPreparedWindows() {
        int weak = ViewerPagePipeline.requestStrengthForTest(6, 4, 0, Priority.HIGH);
        int strong = ViewerPagePipeline.requestStrengthForTest(24, 10, 2, Priority.IMMEDIATE);

        assertTrue(ViewerPagePipeline.shouldScheduleRequestForTest(null, weak, false));
        assertTrue(ViewerPagePipeline.shouldScheduleRequestForTest(weak, strong, false));
        assertFalse(ViewerPagePipeline.shouldScheduleRequestForTest(strong, weak, false));
        assertFalse(ViewerPagePipeline.shouldScheduleRequestForTest(weak, strong, true));
    }

    @Test
    public void adjacentPagesShareWarmupBuckets() {
        assertEquals(0, ViewerPagePipeline.pageBucketForTest(0));
        assertEquals(0, ViewerPagePipeline.pageBucketForTest(7));
        assertEquals(1, ViewerPagePipeline.pageBucketForTest(8));
    }
}
