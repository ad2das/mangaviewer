package ml.melun.mangaview.glide;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ViewerPreloadPolicyTest {
    @Test
    public void firstFrameWindow_decodesFirstPageAndQueuesNearbyPages() {
        ViewerPreloadPolicy.Window window = ViewerPreloadPolicy.firstFrameWindow(false);

        assertEquals(6, window.decodedLimit);
        assertEquals(12, window.immediateLimit);
        assertEquals(20, window.highLimit);
        assertEquals(20, window.totalLimit);
        assertEquals(ViewerPreloadPolicy.TIER_DECODED, ViewerPreloadPolicy.tierForOffset(window, 0));
        assertEquals(ViewerPreloadPolicy.TIER_DECODED, ViewerPreloadPolicy.tierForOffset(window, 1));
        assertEquals(ViewerPreloadPolicy.TIER_DECODED, ViewerPreloadPolicy.tierForOffset(window, 2));
        assertEquals(ViewerPreloadPolicy.TIER_DECODED, ViewerPreloadPolicy.tierForOffset(window, 3));
        assertEquals(ViewerPreloadPolicy.TIER_DECODED, ViewerPreloadPolicy.tierForOffset(window, 4));
        assertEquals(ViewerPreloadPolicy.TIER_DECODED, ViewerPreloadPolicy.tierForOffset(window, 5));
        assertEquals(ViewerPreloadPolicy.TIER_IMMEDIATE, ViewerPreloadPolicy.tierForOffset(window, 6));
        assertEquals(ViewerPreloadPolicy.TIER_HIGH, ViewerPreloadPolicy.tierForOffset(window, 12));
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

        assertEquals(2, window.decodedLimit);
        assertEquals(3, window.immediateLimit);
        assertEquals(6, window.highLimit);
        assertEquals(6, window.totalLimit);
        assertEquals(ViewerPreloadPolicy.TIER_DECODED, ViewerPreloadPolicy.tierForOffset(window, 0));
        assertEquals(ViewerPreloadPolicy.TIER_DECODED, ViewerPreloadPolicy.tierForOffset(window, 1));
        assertEquals(ViewerPreloadPolicy.TIER_IMMEDIATE, ViewerPreloadPolicy.tierForOffset(window, 2));
        assertEquals(ViewerPreloadPolicy.TIER_HIGH, ViewerPreloadPolicy.tierForOffset(window, 3));
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

        assertEquals(4, window.decodedLimit);
        assertEquals(8, window.immediateLimit);
        assertEquals(14, window.highLimit);
        assertEquals(14, window.totalLimit);
        assertEquals(ViewerPreloadPolicy.TIER_DECODED, ViewerPreloadPolicy.tierForOffset(window, 0));
        assertEquals(ViewerPreloadPolicy.TIER_DECODED, ViewerPreloadPolicy.tierForOffset(window, 1));
        assertEquals(ViewerPreloadPolicy.TIER_DECODED, ViewerPreloadPolicy.tierForOffset(window, 2));
        assertEquals(ViewerPreloadPolicy.TIER_DECODED, ViewerPreloadPolicy.tierForOffset(window, 3));
        assertEquals(ViewerPreloadPolicy.TIER_IMMEDIATE, ViewerPreloadPolicy.tierForOffset(window, 4));
        assertEquals(ViewerPreloadPolicy.TIER_HIGH, ViewerPreloadPolicy.tierForOffset(window, 8));
    }

    @Test
    public void immediateDisplayWindowStartsDecodeBeforeAdapterBind() {
        ViewerPreloadPolicy.Window window = ViewerPreloadPolicy.immediateDisplayWindow(false);

        assertEquals(4, window.decodedLimit);
        assertEquals(8, window.immediateLimit);
        assertEquals(14, window.highLimit);
        assertEquals(14, window.totalLimit);
    }

    @Test
    public void immediateDisplayWindowKeepsDataSaverSmall() {
        ViewerPreloadPolicy.Window window = ViewerPreloadPolicy.immediateDisplayWindow(true);

        assertEquals(1, window.decodedLimit);
        assertEquals(1, window.immediateLimit);
        assertEquals(2, window.highLimit);
        assertEquals(2, window.totalLimit);
    }

    @Test
    public void scrollAheadWindow_decodesNearPagesOnly() {
        ViewerPreloadPolicy.Window window = ViewerPreloadPolicy.scrollAheadWindow(false);

        assertEquals(4, window.decodedLimit);
        assertEquals(8, window.immediateLimit);
        assertEquals(16, window.highLimit);
        assertEquals(16, window.totalLimit);
        assertEquals(ViewerPreloadPolicy.TIER_HIGH, ViewerPreloadPolicy.tierForOffset(window, 8));
        assertEquals(ViewerPreloadPolicy.TIER_NORMAL, ViewerPreloadPolicy.tierForOffset(window, 16));
    }

    @Test
    public void scrollBusyWindow_keepsFastFlingWorkTiny() {
        ViewerPreloadPolicy.Window window = ViewerPreloadPolicy.scrollBusyWindow(false);

        assertEquals(0, window.decodedLimit);
        assertEquals(1, window.immediateLimit);
        assertEquals(2, window.highLimit);
        assertEquals(2, window.totalLimit);
    }

    @Test
    public void nextEpisodeWindowKeepsBackgroundWorkBounded() {
        ViewerPreloadPolicy.Window window = ViewerPreloadPolicy.nextEpisodeWindow(false);

        assertEquals(3, window.decodedLimit);
        assertEquals(6, window.immediateLimit);
        assertEquals(10, window.highLimit);
        assertEquals(10, window.totalLimit);
    }
}
