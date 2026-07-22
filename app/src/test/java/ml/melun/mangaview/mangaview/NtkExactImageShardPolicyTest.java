package ml.melun.mangaview.mangaview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

public final class NtkExactImageShardPolicyTest {
    @Test
    public void everyThreeHostStripeUsesEightBalancedPools() {
        for(int hostStripe = 0; hostStripe < 3; hostStripe++) {
            int[] occupancy = new int[8];
            Set<Integer> usedShards = new HashSet<>();
            for(int pageIndex = hostStripe; pageIndex < 122; pageIndex += 3) {
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
    public void everyCompleteHostLocalBlockStillUsesEveryPoolExactlyOnce() {
        for(int block = 0; block < 12; block++) {
            Set<Integer> usedShards = new HashSet<>();
            for(int localOffset = 0; localOffset < 8; localOffset++) {
                int hostLocalOrdinal = block * 8 + localOffset;
                int pageIndex = hostLocalOrdinal * 3;
                usedShards.add(CustomHttpClient.ntkWebtoonExactImageShardIndex(pageIndex, 8));
            }
            assertEquals("block " + block, 8, usedShards.size());
        }
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

    @Test(expected = IllegalArgumentException.class)
    public void rejectsEmptyConnectionTopology() {
        CustomHttpClient.ntkExactImageShardIndex(0, 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void webtoonPolicyRejectsNegativePageIndex() {
        CustomHttpClient.ntkWebtoonExactImageShardIndex(-1, 6);
    }
}
