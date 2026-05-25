package ml.melun.mangaview.mangaview;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PageCachePolicyTest {
    @Test
    public void freshPageCacheUsesExclusiveTtlBoundary() {
        long now = 10_000L;

        assertTrue(PageCachePolicy.isFresh(now - 999L, now, 1_000L));
        assertFalse(PageCachePolicy.isFresh(now - 1_000L, now, 1_000L));
        assertFalse(PageCachePolicy.isFresh(now + 1L, now, 1_000L));
    }

    @Test
    public void coldStartPageCacheUsesInclusiveTtlBoundary() {
        long now = 20_000L;

        assertTrue(PageCachePolicy.isUsableForColdStart(now - 5_000L, now, 5_000L));
        assertFalse(PageCachePolicy.isUsableForColdStart(now - 5_001L, now, 5_000L));
        assertFalse(PageCachePolicy.isUsableForColdStart(now + 1L, now, 5_000L));
    }

    @Test
    public void staleColdStartCacheIsServedOnlyOutsideCacheOnlyMode() {
        assertTrue(PageCachePolicy.shouldServeColdStartImmediately(true,
                CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW, true, false));
        assertFalse(PageCachePolicy.shouldServeColdStartImmediately(true,
                CustomHttpClient.FetchMode.CACHE_ONLY, true, false));
        assertFalse(PageCachePolicy.shouldServeColdStartImmediately(true,
                CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW, true, true));
        assertFalse(PageCachePolicy.shouldServeColdStartImmediately(false,
                CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW, true, false));
    }

    @Test
    public void activeLoadWaitIsSkippedWhenStaleFallbackExists() {
        assertTrue(PageCachePolicy.shouldWaitForActiveLoad(false));
        assertFalse(PageCachePolicy.shouldWaitForActiveLoad(true));
    }
}
