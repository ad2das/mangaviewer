package ml.melun.mangaview.glide;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ViewerPreloadPolicyTest {
    @Test
    public void firstFrameWindowQueuesNearbyPagesWithoutDecodedPreload() {
        ViewerPreloadPolicy.Window window = ViewerPreloadPolicy.firstFrameWindow(false);

        assertEquals(0, window.decodedLimit);
        assertEquals(3, window.immediateLimit);
        assertEquals(6, window.highLimit);
        assertEquals(8, window.totalLimit);
        assertEquals(ViewerPreloadPolicy.TIER_IMMEDIATE, ViewerPreloadPolicy.tierForOffset(window, 0));
        assertEquals(ViewerPreloadPolicy.TIER_IMMEDIATE, ViewerPreloadPolicy.tierForOffset(window, 2));
        assertEquals(ViewerPreloadPolicy.TIER_HIGH, ViewerPreloadPolicy.tierForOffset(window, 3));
        assertEquals(ViewerPreloadPolicy.TIER_NORMAL, ViewerPreloadPolicy.tierForOffset(window, 6));
    }

    @Test
    public void firstFrameWindowKeepsDataSaverConservative() {
        ViewerPreloadPolicy.Window window = ViewerPreloadPolicy.firstFrameWindow(true);

        assertEquals(0, window.decodedLimit);
        assertEquals(2, window.immediateLimit);
        assertEquals(6, window.highLimit);
        assertEquals(6, window.totalLimit);
    }

    @Test
    public void episodeListWarmupWindowAvoidsDecodedPreload() {
        ViewerPreloadPolicy.Window window = ViewerPreloadPolicy.episodeListWarmupWindow(false);

        assertEquals(0, window.decodedLimit);
        assertEquals(3, window.immediateLimit);
        assertEquals(6, window.highLimit);
        assertEquals(6, window.totalLimit);
        assertEquals(ViewerPreloadPolicy.TIER_IMMEDIATE, ViewerPreloadPolicy.tierForOffset(window, 0));
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
    public void episodeEntryWarmupWindowAvoidsDecodeStorm() {
        ViewerPreloadPolicy.Window window = ViewerPreloadPolicy.episodeEntryWarmupWindow(false);

        assertEquals(0, window.decodedLimit);
        assertEquals(2, window.immediateLimit);
        assertEquals(4, window.highLimit);
        assertEquals(6, window.totalLimit);
        assertEquals(ViewerPreloadPolicy.TIER_IMMEDIATE, ViewerPreloadPolicy.tierForOffset(window, 0));
        assertEquals(ViewerPreloadPolicy.TIER_IMMEDIATE, ViewerPreloadPolicy.tierForOffset(window, 1));
        assertEquals(ViewerPreloadPolicy.TIER_HIGH, ViewerPreloadPolicy.tierForOffset(window, 2));
        assertEquals(ViewerPreloadPolicy.TIER_NORMAL, ViewerPreloadPolicy.tierForOffset(window, 4));
    }

    @Test
    public void immediateDisplayWindowUsesSourcePriorityBeforeAdapterBind() {
        ViewerPreloadPolicy.Window window = ViewerPreloadPolicy.immediateDisplayWindow(false);

        assertEquals(0, window.decodedLimit);
        assertEquals(3, window.immediateLimit);
        assertEquals(6, window.highLimit);
        assertEquals(8, window.totalLimit);
    }

    @Test
    public void immediateDisplayWindowKeepsDataSaverSmall() {
        ViewerPreloadPolicy.Window window = ViewerPreloadPolicy.immediateDisplayWindow(true);

        assertEquals(0, window.decodedLimit);
        assertEquals(1, window.immediateLimit);
        assertEquals(2, window.highLimit);
        assertEquals(2, window.totalLimit);
    }

    @Test
    public void scrollAheadWindowAvoidsDecodedPreloadDuringViewerScroll() {
        ViewerPreloadPolicy.Window window = ViewerPreloadPolicy.scrollAheadWindow(false);

        assertEquals(0, window.decodedLimit);
        assertEquals(2, window.immediateLimit);
        assertEquals(5, window.highLimit);
        assertEquals(8, window.totalLimit);
        assertEquals(ViewerPreloadPolicy.TIER_IMMEDIATE, ViewerPreloadPolicy.tierForOffset(window, 0));
        assertEquals(ViewerPreloadPolicy.TIER_NORMAL, ViewerPreloadPolicy.tierForOffset(window, 5));
    }

    @Test
    public void scrollBusyWindowKeepsFastFlingWorkTiny() {
        ViewerPreloadPolicy.Window window = ViewerPreloadPolicy.scrollBusyWindow(false);

        assertEquals(0, window.decodedLimit);
        assertEquals(1, window.immediateLimit);
        assertEquals(2, window.highLimit);
        assertEquals(2, window.totalLimit);
    }

    @Test
    public void nextEpisodeWindowCoversFastFlingBoundaryWithoutDecodedPreload() {
        ViewerPreloadPolicy.Window window = ViewerPreloadPolicy.nextEpisodeWindow(false);

        assertEquals(0, window.decodedLimit);
        assertEquals(2, window.immediateLimit);
        assertEquals(4, window.highLimit);
        assertEquals(6, window.totalLimit);
    }
}
