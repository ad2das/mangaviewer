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

    @Test
    public void staleEpisodeSnapshotsAreReusableForColdStart() {
        long now = 8L * 24L * 60L * 60L * 1000L;

        assertTrue(CachePolicy.isReusableForColdStartForTest(now - CachePolicy.EPISODE_TTL_MS - 1L, now));
        assertFalse(CachePolicy.isReusableForColdStartForTest(now - CachePolicy.EPISODE_COLD_START_TTL_MS - 1L, now));
        assertFalse(CachePolicy.isReusableForColdStartForTest(now + 1L, now));
    }
}
