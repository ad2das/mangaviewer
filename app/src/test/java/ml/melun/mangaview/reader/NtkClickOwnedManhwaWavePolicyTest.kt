package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkClickOwnedManhwaWavePolicyTest {

    @Test
    fun completeOwnershipRingHasExecutorHeadroomWithoutTransportOversubscription() {
        assertEquals(120, NtkClickOwnedManhwaWavePolicy.BODY_LANES)
        assertEquals(120, NtkClickOwnedManhwaWavePolicy.PROBE_LANES)
        assertEquals(24, NtkClickOwnedManhwaWavePolicy.CONNECTION_SHARDS)
        assertEquals(3, NtkClickOwnedManhwaWavePolicy.REPLICA_STRIPE_SIZE)
        assertEquals(120, NtkClickOwnedManhwaWavePolicy.ACTIVE_BODY_TRANSFERS)
        assertEquals(32, NtkClickOwnedManhwaWavePolicy.SPECULATION_DEBT_LIMIT)
        assertTrue(NtkClickOwnedManhwaWavePolicy.ACTIVE_BODY_TRANSFERS <=
            NtkClickOwnedManhwaWavePolicy.BODY_LANES)
        assertTrue(NtkSourceLanePolicy.MAX_EPISODE_PAGES >= 270)
        assertTrue(NtkSourceLanePolicy.MAX_EPISODE_PAGES >
            NtkSourceLanePolicy.MAX_NETWORK_OPERATIONS)
        assertTrue(NtkClickOwnedManhwaWavePolicy.BODY_LANES <=
            NtkSourceLanePolicy.MAX_NETWORK_OPERATIONS)
    }

    @Test
    fun canonicalGifPagesAreProbedWithoutPostAuthorityRedownload() {
        assertTrue("gif" in NtkClickOwnedManhwaWavePolicy.CANDIDATE_EXTENSIONS)
        assertEquals(
            listOf("jpg", "gif", "webp", "png", "jpeg"),
            NtkClickOwnedManhwaWavePolicy.CANDIDATE_EXTENSIONS,
        )
    }

    @Test
    fun normalReplicaHostRingUsesTheThreeRangeCompatibleR79Origins() {
        assertEquals(
            listOf("booktoki8.org", "mana.apihost93.com", "booktoki9.org"),
            NtkClickOwnedManhwaWavePolicy.replicaHosts(),
        )
        val hosts = (0 until 112).map(NtkClickOwnedManhwaWavePolicy::replicaHost)
        assertEquals(38, hosts.count { it == "booktoki8.org" })
        assertEquals(37, hosts.count { it == "mana.apihost93.com" })
        assertEquals(37, hosts.count { it == "booktoki9.org" })
    }

    @Test
    fun eachReplicaUsesEveryConnectionShardInsteadOfOnlyOneParity() {
        assertEquals(listOf(0, 0, 0), (0..2).map {
            NtkClickOwnedManhwaWavePolicy.replicaLocalPageIndex(it)
        })
        assertEquals(listOf(1, 1, 1), (3..5).map {
            NtkClickOwnedManhwaWavePolicy.replicaLocalPageIndex(it)
        })
        assertEquals(2, NtkClickOwnedManhwaWavePolicy.replicaLocalPageIndex(8))
        assertEquals(3, NtkClickOwnedManhwaWavePolicy.replicaLocalPageIndex(9))
        val shardsByHost = (0 until 384).groupBy(
            NtkClickOwnedManhwaWavePolicy::replicaHost,
        ).mapValues { (_, pages) ->
            pages.map(NtkClickOwnedManhwaWavePolicy::connectionShard).toSet()
        }
        assertEquals(
            setOf("booktoki8.org", "mana.apihost93.com", "booktoki9.org"),
            shardsByHost.keys,
        )
        assertTrue(shardsByHost.values.all {
            it.size == NtkClickOwnedManhwaWavePolicy.CONNECTION_SHARDS
        })
        assertEquals(
            (0 until NtkClickOwnedManhwaWavePolicy.CONNECTION_SHARDS).toList(),
            (0 until NtkClickOwnedManhwaWavePolicy.CONNECTION_SHARDS).map { shard ->
                NtkClickOwnedManhwaWavePolicy.connectionShard(
                    NtkClickOwnedManhwaWavePolicy.representativePageIndexForConnectionShard(shard)
                )
            },
        )

        val loadsForEightyEightPages = (0 until 88).groupBy(
            NtkClickOwnedManhwaWavePolicy::replicaHost,
        ).values.flatMap { pages ->
            pages.groupingBy(NtkClickOwnedManhwaWavePolicy::connectionShard)
                .eachCount()
                .values
        }
        assertEquals(1, loadsForEightyEightPages.min())
        assertEquals(2, loadsForEightyEightPages.max())
    }

}
