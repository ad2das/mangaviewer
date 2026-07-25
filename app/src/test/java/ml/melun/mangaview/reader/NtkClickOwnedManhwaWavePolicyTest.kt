package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkClickOwnedManhwaWavePolicyTest {

    @Test
    fun completeOwnershipRingHasExecutorHeadroomWithoutTransportOversubscription() {
        assertEquals(32, NtkClickOwnedManhwaWavePolicy.BODY_LANES)
        assertEquals(120, NtkClickOwnedManhwaWavePolicy.PROBE_LANES)
        assertEquals(24, NtkClickOwnedManhwaWavePolicy.CONNECTION_SHARDS)
        assertEquals(3, NtkClickOwnedManhwaWavePolicy.REPLICA_STRIPE_SIZE)
        assertEquals(24, NtkClickOwnedManhwaWavePolicy.ACTIVE_BODY_TRANSFERS)
        assertEquals(8, NtkClickOwnedManhwaWavePolicy.SPECULATION_DEBT_LIMIT)
        assertTrue(NtkClickOwnedManhwaWavePolicy.ACTIVE_BODY_TRANSFERS <=
            NtkClickOwnedManhwaWavePolicy.BODY_LANES)
        assertTrue(NtkSourceLanePolicy.MAX_EPISODE_PAGES >= 270)
        assertTrue(NtkSourceLanePolicy.MAX_EPISODE_PAGES >
            NtkSourceLanePolicy.MAX_NETWORK_OPERATIONS)
        assertTrue(NtkClickOwnedManhwaWavePolicy.BODY_LANES <=
            NtkSourceLanePolicy.MAX_NETWORK_OPERATIONS)
    }

    @Test
    fun ownershipLanePreservesOrdinaryPagesAndBoundsLargeEpisodeTail() {
        assertEquals(0, NtkClickOwnedManhwaWavePolicy.ownershipLane(0))
        assertEquals(119, NtkClickOwnedManhwaWavePolicy.ownershipLane(119))
        assertEquals(0, NtkClickOwnedManhwaWavePolicy.ownershipLane(120))
        assertEquals(47, NtkClickOwnedManhwaWavePolicy.ownershipLane(167))
        assertEquals(23, NtkClickOwnedManhwaWavePolicy.ownershipLane(383))
        assertTrue((0 until NtkSourceLanePolicy.MAX_EPISODE_PAGES).all {
            NtkClickOwnedManhwaWavePolicy.ownershipLane(it) in
                0 until NtkSourceLanePolicy.MAX_NETWORK_OPERATIONS
        })
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
    fun exactTailUsesOnlyAWellProvenDominantVolumeExtension() {
        val jpeg = (0 until 10).map {
            "https://booktoki${8 + it % 2}.org/manhwa/3360/18755/p%03d.jpeg"
                .format(it + 1)
        }
        assertEquals(
            "jpeg",
            NtkClickOwnedManhwaWavePolicy.dominantTailExtension(jpeg),
        )
        assertEquals(
            "jpeg",
            NtkClickOwnedManhwaWavePolicy.dominantTailExtension(
                jpeg.take(8) + listOf(
                    "https://booktoki8.org/manhwa/3360/18755/p009.gif",
                    "https://booktoki9.org/manhwa/3360/18755/p010.gif",
                ),
            ),
        )
        assertNull(
            NtkClickOwnedManhwaWavePolicy.dominantTailExtension(
                jpeg.take(5),
            ),
        )
        assertNull(
            NtkClickOwnedManhwaWavePolicy.dominantTailExtension(
                jpeg.take(5) + (0 until 5).map {
                    "https://booktoki8.org/manhwa/3360/18755/p%03d.gif".format(it + 6)
                },
            ),
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
    fun exactOwnerStripesOnlyImmutableNumericManhwaTransportUrls() {
        val canonical = "https://booktoki9.org/manhwa/2847/10588/p121.jpeg"
        assertEquals(
            "https://booktoki8.org/manhwa/2847/10588/p121.jpeg",
            ReaderImageCache.stripeStrictManhwaTransportAsset(canonical, 120),
        )
        assertEquals(
            "https://mana.apihost93.com/manhwa/2847/10588/p121.jpeg",
            ReaderImageCache.stripeStrictManhwaTransportAsset(canonical, 121),
        )
        assertEquals(
            canonical,
            ReaderImageCache.stripeStrictManhwaTransportAsset(canonical, 122),
        )
        val webtoon = "https://booktoki9.org/webtoon/2847/10588/p121.jpeg"
        assertEquals(
            webtoon,
            ReaderImageCache.stripeStrictManhwaTransportAsset(webtoon, 120),
        )
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
