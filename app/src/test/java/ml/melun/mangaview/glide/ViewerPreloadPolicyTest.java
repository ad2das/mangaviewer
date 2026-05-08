package ml.melun.mangaview.glide;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ViewerPreloadPolicyTest {
    @Test
    public void firstFrameWindow_decodesFirstPageAndQueuesNearbyPages() {
        ViewerPreloadPolicy.Window window = ViewerPreloadPolicy.firstFrameWindow(false);

        assertEquals(1, window.decodedLimit);
        assertEquals(4, window.immediateLimit);
        assertEquals(18, window.highLimit);
        assertEquals(18, window.totalLimit);
        assertEquals(ViewerPreloadPolicy.TIER_DECODED, ViewerPreloadPolicy.tierForOffset(window, 0));
        assertEquals(ViewerPreloadPolicy.TIER_IMMEDIATE, ViewerPreloadPolicy.tierForOffset(window, 1));
        assertEquals(ViewerPreloadPolicy.TIER_HIGH, ViewerPreloadPolicy.tierForOffset(window, 4));
    }

    @Test
    public void firstFrameWindow_keepsDataSaverConservative() {
        ViewerPreloadPolicy.Window window = ViewerPreloadPolicy.firstFrameWindow(true);

        assertEquals(1, window.decodedLimit);
        assertEquals(2, window.immediateLimit);
        assertEquals(6, window.highLimit);
        assertEquals(6, window.totalLimit);
    }

    @Test
    public void scrollAheadWindow_decodesNearPagesOnly() {
        ViewerPreloadPolicy.Window window = ViewerPreloadPolicy.scrollAheadWindow(false);

        assertEquals(1, window.decodedLimit);
        assertEquals(4, window.immediateLimit);
        assertEquals(ViewerPreloadPolicy.TIER_NORMAL, ViewerPreloadPolicy.tierForOffset(window, 18));
    }
}
