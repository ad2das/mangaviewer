package ml.melun.mangaview.glide;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ViewerPreloadPolicyTest {
    @Test
    public void firstFrameWindowQueuesNearbyPagesAndFirstDecode() {
        ViewerPreloadPolicy.Window window = ViewerPreloadPolicy.firstFrameWindow(false);

        assertEquals(2, window.decodedLimit);
        assertEquals(6, window.immediateLimit);
        assertEquals(12, window.highLimit);
        assertEquals(18, window.totalLimit);
        assertEquals(ViewerPreloadPolicy.TIER_DECODED, ViewerPreloadPolicy.tierForOffset(window, 0));
        assertEquals(ViewerPreloadPolicy.TIER_DECODED, ViewerPreloadPolicy.tierForOffset(window, 1));
        assertEquals(ViewerPreloadPolicy.TIER_IMMEDIATE, ViewerPreloadPolicy.tierForOffset(window, 2));
        assertEquals(ViewerPreloadPolicy.TIER_HIGH, ViewerPreloadPolicy.tierForOffset(window, 6));
        assertEquals(ViewerPreloadPolicy.TIER_NORMAL, ViewerPreloadPolicy.tierForOffset(window, 12));
    }

    @Test
    public void firstFrameWindowKeepsDataSaverConservative() {
        ViewerPreloadPolicy.Window window = ViewerPreloadPolicy.firstFrameWindow(true);

        assertEquals(1, window.decodedLimit);
        assertEquals(4, window.immediateLimit);
        assertEquals(7, window.highLimit);
        assertEquals(10, window.totalLimit);
    }

    @Test
    public void episodeListWarmupWindowAvoidsDecodedPreload() {
        ViewerPreloadPolicy.Window window = ViewerPreloadPolicy.episodeListWarmupWindow(false);

        assertEquals(0, window.decodedLimit);
        assertEquals(4, window.immediateLimit);
        assertEquals(8, window.highLimit);
        assertEquals(10, window.totalLimit);
        assertEquals(ViewerPreloadPolicy.TIER_IMMEDIATE, ViewerPreloadPolicy.tierForOffset(window, 0));
        assertEquals(ViewerPreloadPolicy.TIER_IMMEDIATE, ViewerPreloadPolicy.tierForOffset(window, 3));
        assertEquals(ViewerPreloadPolicy.TIER_HIGH, ViewerPreloadPolicy.tierForOffset(window, 4));
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
    public void episodeEntryWarmupWindowPrimesFirstPageWithoutDecodeStorm() {
        ViewerPreloadPolicy.Window window = ViewerPreloadPolicy.episodeEntryWarmupWindow(false);

        assertEquals(2, window.decodedLimit);
        assertEquals(4, window.immediateLimit);
        assertEquals(8, window.highLimit);
        assertEquals(12, window.totalLimit);
        assertEquals(ViewerPreloadPolicy.TIER_DECODED, ViewerPreloadPolicy.tierForOffset(window, 0));
        assertEquals(ViewerPreloadPolicy.TIER_DECODED, ViewerPreloadPolicy.tierForOffset(window, 1));
        assertEquals(ViewerPreloadPolicy.TIER_IMMEDIATE, ViewerPreloadPolicy.tierForOffset(window, 2));
        assertEquals(ViewerPreloadPolicy.TIER_HIGH, ViewerPreloadPolicy.tierForOffset(window, 4));
        assertEquals(ViewerPreloadPolicy.TIER_NORMAL, ViewerPreloadPolicy.tierForOffset(window, 8));
    }

    @Test
    public void immediateDisplayWindowOnlyPrimesTheFirstViewport() {
        ViewerPreloadPolicy.Window window = ViewerPreloadPolicy.immediateDisplayWindow(false);

        assertEquals(2, window.decodedLimit);
        assertEquals(5, window.immediateLimit);
        assertEquals(8, window.highLimit);
        assertEquals(10, window.totalLimit);
    }

    @Test
    public void immediateDisplayWindowKeepsDataSaverSmall() {
        ViewerPreloadPolicy.Window window = ViewerPreloadPolicy.immediateDisplayWindow(true);

        assertEquals(1, window.decodedLimit);
        assertEquals(2, window.immediateLimit);
        assertEquals(3, window.highLimit);
        assertEquals(4, window.totalLimit);
    }

    @Test
    public void scrollAheadWindowPrimesVisibleFlingRange() {
        ViewerPreloadPolicy.Window window = ViewerPreloadPolicy.scrollAheadWindow(false);

        assertEquals(4, window.decodedLimit);
        assertEquals(10, window.immediateLimit);
        assertEquals(18, window.highLimit);
        assertEquals(30, window.totalLimit);
        assertEquals(ViewerPreloadPolicy.TIER_DECODED, ViewerPreloadPolicy.tierForOffset(window, 0));
        assertEquals(ViewerPreloadPolicy.TIER_DECODED, ViewerPreloadPolicy.tierForOffset(window, 1));
        assertEquals(ViewerPreloadPolicy.TIER_NORMAL, ViewerPreloadPolicy.tierForOffset(window, 18));
    }

    @Test
    public void scrollBusyWindowCoversFastFlingSourceRange() {
        ViewerPreloadPolicy.Window window = ViewerPreloadPolicy.scrollBusyWindow(false);

        assertEquals(0, window.decodedLimit);
        assertEquals(4, window.immediateLimit);
        assertEquals(6, window.highLimit);
        assertEquals(8, window.totalLimit);
    }

    @Test
    public void nextEpisodeWindowCoversFastFlingBoundaryWithDecodedPreview() {
        ViewerPreloadPolicy.Window window = ViewerPreloadPolicy.nextEpisodeWindow(false);

        assertEquals(2, window.decodedLimit);
        assertEquals(8, window.immediateLimit);
        assertEquals(16, window.highLimit);
        assertEquals(24, window.totalLimit);
    }
}
