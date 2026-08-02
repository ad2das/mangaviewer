package ml.melun.mangaview.mangaview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

public final class NtkExactImageShardPolicyTest {
    @Test
    public void directWifiExactWebtoonUsesSixteenPoolsWithoutChangingCarrier() {
        assertEquals(16, CustomHttpClient.ntkWebtoonExactImageShardCount(
                false, true, true));
        assertEquals(8, CustomHttpClient.ntkWebtoonExactImageShardCount(
                true, false, true));
        assertEquals(8, CustomHttpClient.ntkWebtoonExactImageShardCount(
                false, false, true));
        assertEquals(8, CustomHttpClient.ntkWebtoonExactImageShardCount(
                false, true, false));
    }

    @Test
    public void everyActualHostSequenceUsesSixteenBalancedDirectWifiPools() {
        for(int hostStripe = 0; hostStripe < 3; hostStripe++) {
            Set<Integer> usedShards = new HashSet<>();
            for(int pageIndex = 0; pageIndex < 193; pageIndex++) {
                int actualHostStripe = pageIndex <= 1 ? 0 : pageIndex % 3;
                if(actualHostStripe == hostStripe)
                    usedShards.add(CustomHttpClient.ntkWebtoonExactImageShardIndex(pageIndex, 16));
            }
            assertEquals("host stripe " + hostStripe, 16, usedShards.size());
        }
    }

    @Test
    public void everyActualHostSequenceUsesEightBalancedPools() {
        for(int hostStripe = 0; hostStripe < 3; hostStripe++) {
            int[] occupancy = new int[8];
            Set<Integer> usedShards = new HashSet<>();
            for(int pageIndex = 0; pageIndex < 122; pageIndex++) {
                int actualHostStripe = pageIndex <= 1 ? 0 : pageIndex % 3;
                if(actualHostStripe != hostStripe)
                    continue;
                int shard = CustomHttpClient.ntkWebtoonExactImageShardIndex(pageIndex, 8);
                assertTrue(shard >= 0 && shard < occupancy.length);
                occupancy[shard]++;
                usedShards.add(shard);
            }
            assertEquals("host stripe " + hostStripe, occupancy.length, usedShards.size());
            int minimum = Integer.MAX_VALUE;
            int maximum = Integer.MIN_VALUE;
            for(int index = 0; index < occupancy.length; index++) {
                int count = occupancy[index];
                minimum = Math.min(minimum, count);
                maximum = Math.max(maximum, count);
            }
            assertTrue("host stripe " + hostStripe, maximum - minimum <= 1);
        }
    }

    @Test
    public void openingViewportUsesDistinctPoolsOnItsSharedOrigin() {
        assertEquals(0, CustomHttpClient.ntkWebtoonExactImageShardIndex(0, 8));
        assertEquals(1, CustomHttpClient.ntkWebtoonExactImageShardIndex(1, 8));
    }

    @Test
    public void replicaRetriesRotateAwayFromTheFailedPhysicalPool() {
        int pageIndex = 43;
        Set<Integer> usedShards = new HashSet<>();
        for(int physicalAttempt = 0; physicalAttempt < 8; physicalAttempt++) {
            usedShards.add(CustomHttpClient.ntkWebtoonExactImageShardIndex(
                    pageIndex, 8, physicalAttempt));
        }
        assertEquals(8, usedShards.size());
        assertEquals(
                (CustomHttpClient.ntkWebtoonExactImageShardIndex(pageIndex, 8) + 1) % 8,
                CustomHttpClient.ntkWebtoonExactImageShardIndex(pageIndex, 8, 1));
    }

    @Test
    public void directWifiLogicalRetriesRotateWhileCarrierKeepsItsRoute() {
        assertEquals(1, CustomHttpClient.ntkWebtoonRoutePhysicalAttemptOrdinal(
                1, 9, false));
        assertEquals(9, CustomHttpClient.ntkWebtoonRoutePhysicalAttemptOrdinal(
                1, 9, true));
        assertEquals(
                CustomHttpClient.ntkWebtoonExactImageShardIndex(23, 8, 1),
                CustomHttpClient.ntkWebtoonExactImageShardIndex(23, 8,
                        CustomHttpClient.ntkWebtoonRoutePhysicalAttemptOrdinal(1, 1, true)));
        assertEquals(
                (CustomHttpClient.ntkWebtoonExactImageShardIndex(23, 8, 1) + 1) % 8,
                CustomHttpClient.ntkWebtoonExactImageShardIndex(23, 8,
                        CustomHttpClient.ntkWebtoonRoutePhysicalAttemptOrdinal(1, 2, true)));
    }

    @Test
    public void directWifiUsesIndependentHttp1OnlyAfterTheH2ReplicaRing() {
        assertFalse(CustomHttpClient.ntkWebtoonShouldUseHttp1Recovery(
                false, 2, false, true));
        assertTrue(CustomHttpClient.ntkWebtoonShouldUseHttp1Recovery(
                false, 3, false, true));
        assertFalse(CustomHttpClient.ntkWebtoonShouldUseHttp1Recovery(
                false, 3, false, false));
        assertTrue(CustomHttpClient.ntkWebtoonShouldUseHttp1Recovery(
                false, 3, true, false));
        assertTrue(CustomHttpClient.ntkWebtoonShouldUseHttp1Recovery(
                true, 0, false, false));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsEmptyConnectionTopology() {
        CustomHttpClient.ntkExactImageShardIndex(0, 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void webtoonPolicyRejectsNegativePageIndex() {
        CustomHttpClient.ntkWebtoonExactImageShardIndex(-1, 6);
    }
}
