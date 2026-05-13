package ml.melun.mangaview.repository;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CachePolicyTest {
    @Test
    public void freshnessRejectsExpiredAndFutureEntries() {
        long now = 10_000L;
        long ttl = 1_000L;

        assertTrue(CachePolicy.isFreshForTest(now - 1_000L, ttl, now));
        assertFalse(CachePolicy.isFreshForTest(now - 1_001L, ttl, now));
        assertFalse(CachePolicy.isFreshForTest(now + 1L, ttl, now));
    }
}
