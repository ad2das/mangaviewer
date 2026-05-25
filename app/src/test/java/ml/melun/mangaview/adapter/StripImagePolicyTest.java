package ml.melun.mangaview.adapter;

import org.junit.Test;

import androidx.recyclerview.widget.RecyclerView;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StripImagePolicyTest {
    @Test
    public void previewOnlyBindRunsOnlyDuringBusyScrollUnlessForcedFullQuality() {
        assertTrue(StripImagePolicy.shouldUsePreviewOnlyBind(true, false));
        assertFalse(StripImagePolicy.shouldUsePreviewOnlyBind(true, true));
        assertFalse(StripImagePolicy.shouldUsePreviewOnlyBind(false, false));
    }

    @Test
    public void previewWidthKeepsMinimumButNeverExceedsViewerWidth() {
        assertEquals(1, StripImagePolicy.previewWidth(1));
        assertEquals(360, StripImagePolicy.previewWidth(720));
        assertEquals(400, StripImagePolicy.previewWidth(1200));
    }

    @Test
    public void busyPreloadIsThrottledByDistanceAndTime() {
        assertFalse(StripImagePolicy.shouldRunBusyPreload(0, RecyclerView.NO_POSITION, 1000L));
        assertTrue(StripImagePolicy.shouldRunBusyPreload(RecyclerView.NO_POSITION, 10, 0L));
        assertFalse(StripImagePolicy.shouldRunBusyPreload(10, 11, 20L));
        assertTrue(StripImagePolicy.shouldRunBusyPreload(10, 14, 20L));
        assertTrue(StripImagePolicy.shouldRunBusyPreload(10, 11, 120L));
    }

    @Test
    public void decodedBitmapCacheRejectsOversizedEntries() {
        assertFalse(StripImagePolicy.shouldCacheDecodedBitmap(0, 4096));
        assertTrue(StripImagePolicy.shouldCacheDecodedBitmap(1024, 4096));
        assertFalse(StripImagePolicy.shouldCacheDecodedBitmap(2048, 4096));
    }
}
