package ml.melun.mangaview.glide;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ViewerPreloadPolicyTest {
    @Test
    public void firstFrameWindow_decodesFirstPageAndQueuesNearbyPages() {
        ViewerPreloadPolicy.Window window = ViewerPreloadPolicy.firstFrameWindow(false);

        assertEquals(3, window.decodedLimit);
        assertEquals(6, window.immediateLimit);
        assertEquals(18, window.highLimit);
        assertEquals(18, window.totalLimit);
        assertEquals(ViewerPreloadPolicy.TIER_DECODED, ViewerPreloadPolicy.tierForOffset(window, 0));
        assertEquals(ViewerPreloadPolicy.TIER_DECODED, ViewerPreloadPolicy.tierForOffset(window, 2));
        assertEquals(ViewerPreloadPolicy.TIER_IMMEDIATE, ViewerPreloadPolicy.tierForOffset(window, 3));
        assertEquals(ViewerPreloadPolicy.TIER_HIGH, ViewerPreloadPolicy.tierForOffset(window, 6));
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
    public void episodeListWarmupWindowDecodesFirstVisiblePage() {
        ViewerPreloadPolicy.Window window = ViewerPreloadPolicy.episodeListWarmupWindow(false);

        assertEquals(1, window.decodedLimit);
        assertEquals(2, window.immediateLimit);
        assertEquals(4, window.highLimit);
        assertEquals(4, window.totalLimit);
        assertEquals(ViewerPreloadPolicy.TIER_DECODED, ViewerPreloadPolicy.tierForOffset(window, 0));
        assertEquals(ViewerPreloadPolicy.TIER_IMMEDIATE, ViewerPreloadPolicy.tierForOffset(window, 1));
        assertEquals(ViewerPreloadPolicy.TIER_HIGH, ViewerPreloadPolicy.tierForOffset(window, 2));
    }

    @Test
    public void episodeListWarmupWindowKeepsDataSaverTiny() {
        ViewerPreloadPolicy.Window window = ViewerPreloadPolicy.episodeListWarmupWindow(true);

        assertEquals(0, window.decodedLimit);
        assertEquals(1, window.immediateLimit);
        assertEquals(2, window.highLimit);
        assertEquals(2, window.totalLimit);
    }

    @Test
    public void episodeEntryWarmupWindowDecodesFirstPages() {
        ViewerPreloadPolicy.Window window = ViewerPreloadPolicy.episodeEntryWarmupWindow(false);

        assertEquals(2, window.decodedLimit);
        assertEquals(4, window.immediateLimit);
        assertEquals(8, window.highLimit);
        assertEquals(8, window.totalLimit);
        assertEquals(ViewerPreloadPolicy.TIER_DECODED, ViewerPreloadPolicy.tierForOffset(window, 0));
        assertEquals(ViewerPreloadPolicy.TIER_DECODED, ViewerPreloadPolicy.tierForOffset(window, 1));
        assertEquals(ViewerPreloadPolicy.TIER_IMMEDIATE, ViewerPreloadPolicy.tierForOffset(window, 2));
        assertEquals(ViewerPreloadPolicy.TIER_HIGH, ViewerPreloadPolicy.tierForOffset(window, 4));
    }

    @Test
    public void scrollAheadWindow_decodesNearPagesOnly() {
        ViewerPreloadPolicy.Window window = ViewerPreloadPolicy.scrollAheadWindow(false);

        assertEquals(4, window.decodedLimit);
        assertEquals(8, window.immediateLimit);
        assertEquals(24, window.highLimit);
        assertEquals(24, window.totalLimit);
        assertEquals(ViewerPreloadPolicy.TIER_HIGH, ViewerPreloadPolicy.tierForOffset(window, 18));
        assertEquals(ViewerPreloadPolicy.TIER_NORMAL, ViewerPreloadPolicy.tierForOffset(window, 24));
    }

    @Test
    public void scrollBusyWindow_keepsFastFlingWorkTiny() {
        ViewerPreloadPolicy.Window window = ViewerPreloadPolicy.scrollBusyWindow(false);

        assertEquals(1, window.decodedLimit);
        assertEquals(2, window.immediateLimit);
        assertEquals(2, window.highLimit);
        assertEquals(2, window.totalLimit);
    }
}
