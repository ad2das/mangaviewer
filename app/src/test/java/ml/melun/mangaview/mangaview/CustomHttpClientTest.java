package ml.melun.mangaview.mangaview;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CustomHttpClientTest {
    @Test
    public void activePageLoadWaitsOnlyWithoutStaleCache() {
        assertTrue(CustomHttpClient.shouldWaitForActivePageLoadForTest(false));
        assertFalse(CustomHttpClient.shouldWaitForActivePageLoadForTest(true));
    }

    @Test
    public void pageCacheFreshnessRejectsExpiredAndFutureEntries() {
        long now = 10_000L;
        long ttl = 1_000L;

        assertTrue(CustomHttpClient.isPageCacheFreshForTest(now - 999L, now, ttl));
        assertFalse(CustomHttpClient.isPageCacheFreshForTest(now - 1001L, now, ttl));
        assertFalse(CustomHttpClient.isPageCacheFreshForTest(now + 1L, now, ttl));
    }
}
