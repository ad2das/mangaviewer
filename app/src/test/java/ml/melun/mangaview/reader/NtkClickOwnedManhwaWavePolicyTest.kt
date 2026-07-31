package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkClickOwnedManhwaWavePolicyTest {

    @Test
    fun completeOwnershipRingHasBoundedH2MultiplexingHeadroom() {
        assertEquals(40, NtkClickOwnedManhwaWavePolicy.BODY_LANES)
        assertEquals(120, NtkClickOwnedManhwaWavePolicy.PROBE_LANES)
        assertEquals(24, NtkClickOwnedManhwaWavePolicy.CONNECTION_SHARDS)
        assertEquals(3, NtkClickOwnedManhwaWavePolicy.REPLICA_STRIPE_SIZE)
        assertEquals(40, NtkClickOwnedManhwaWavePolicy.ACTIVE_BODY_TRANSFERS)
        assertEquals(8, NtkClickOwnedManhwaWavePolicy.SPECULATION_DEBT_LIMIT)
        assertEquals(4, NtkClickOwnedManhwaWavePolicy.WIFI_ENTRY_SPECULATION_PAGES)
        assertEquals(12_000L, NtkClickOwnedManhwaWavePolicy.WIFI_ENTRY_RELEASE_TIMEOUT_MS)
        assertEquals(4, NtkClickOwnedManhwaWavePolicy.DIRECT_EXTENSION_RACE_PAGES)
        assertEquals(1, NtkClickOwnedManhwaWavePolicy.DIRECT_BODY_RACE_PAGES)
        assertEquals(2, NtkClickOwnedManhwaWavePolicy.PREFERRED_EXTENSION_EVIDENCE)
        assertEquals(3, NtkClickOwnedManhwaWavePolicy.WIFI_PREFERRED_EXTENSION_EVIDENCE)
        assertEquals("png", NtkManhwaWifiTransportPolicy.WIFI_FIRST_UNCOMMON_EXTENSION)
        assertEquals(
            7_000L,
            NtkManhwaWifiTransportPolicy.WIFI_UNCOMMON_EXACT_QUIC_TIMEOUT_MS,
        )
        assertEquals(
            12,
            NtkManhwaWifiTransportPolicy.WIFI_UNCOMMON_SESSION_STRIPES_PER_HOST,
        )
        assertEquals(3, NtkWifiExactQuicSessionPool.WEBTOON_SESSION_STRIPES_PER_HOST)
        assertEquals(6, NtkWifiExactQuicSessionPool.MANHWA_SESSION_STRIPES_PER_HOST)
        assertEquals(40, NtkClickOwnedManhwaWavePolicy.EXACT_PRE_FRAME_RUNWAY_PAGES)
        assertEquals(40, NtkClickOwnedManhwaWavePolicy.FORWARD_ADMISSION_RUNWAY_PAGES)
        assertEquals(2_500L, NtkClickOwnedManhwaWavePolicy.ENTRY_HEADER_FAILOVER_MS)
        assertEquals(1_800L, NtkClickOwnedManhwaWavePolicy.RUNWAY_HEADER_FAILOVER_MS)
        assertEquals(700L, NtkClickOwnedManhwaWavePolicy.TAIL_HEADER_FAILOVER_MS)
        assertEquals(
            2,
            NtkClickOwnedManhwaWavePolicy.MAX_CONCURRENT_TAIL_HEADER_FAILOVERS,
        )
        assertEquals(
            50L,
            NtkClickOwnedManhwaWavePolicy.HEADER_FAILOVER_PERMIT_RECHECK_MS,
        )
        assertTrue(NtkClickOwnedManhwaWavePolicy.ACTIVE_BODY_TRANSFERS <=
            NtkClickOwnedManhwaWavePolicy.BODY_LANES)
        assertTrue(NtkSourceLanePolicy.MAX_EPISODE_PAGES >= 270)
        assertTrue(NtkSourceLanePolicy.MAX_EPISODE_PAGES >
            NtkSourceLanePolicy.MAX_NETWORK_OPERATIONS)
        assertTrue(NtkClickOwnedManhwaWavePolicy.BODY_LANES <=
            NtkSourceLanePolicy.MAX_NETWORK_OPERATIONS)
    }

    @Test
    fun wifiLaunchProtectsFourViewportBodiesWhileCellularPolicyIsUnchanged() {
        assertEquals(
            4,
            NtkClickOwnedManhwaWavePolicy.initialSpeculationPages(wifiTransport = true),
        )
        assertEquals(
            8,
            NtkClickOwnedManhwaWavePolicy.initialSpeculationPages(wifiTransport = false),
        )
        assertTrue(
            NtkClickOwnedManhwaWavePolicy.shouldHoldExactPreFrameRunway(
                wifiTransport = true,
                pageCount = 176,
            ),
        )
        assertTrue(
            !NtkClickOwnedManhwaWavePolicy.shouldHoldExactPreFrameRunway(
                wifiTransport = false,
                pageCount = 176,
            ),
        )
        assertTrue(
            !NtkClickOwnedManhwaWavePolicy.shouldHoldExactPreFrameRunway(
                wifiTransport = true,
                pageCount = 2,
            ),
        )
        assertTrue(
            !NtkClickOwnedManhwaWavePolicy.shouldHoldExactPreFrameRunway(
                wifiTransport = true,
                pageCount = 3,
            ),
        )
        assertTrue(
            !NtkClickOwnedManhwaWavePolicy.shouldHoldExactPreFrameRunway(
                wifiTransport = true,
                pageCount = 4,
            ),
        )
    }

    @Test
    fun exactAdmissionPreservesOneForwardRingThenPullsFiniteTailForward() {
        assertEquals(
            (40 until 52).reversed().toList(),
            NtkClickOwnedManhwaWavePolicy.exactBodyAdmissionOrder(52),
        )
        assertEquals(
            emptyList<Int>(),
            NtkClickOwnedManhwaWavePolicy.exactBodyAdmissionOrder(24),
        )
        val heavy = NtkClickOwnedManhwaWavePolicy.exactBodyAdmissionOrder(119)
        assertEquals(
            (95 until 119).reversed().toList(),
            heavy.take(24),
        )
        assertEquals(94, heavy[24])
        assertEquals(93, heavy[25])
        assertEquals(40, heavy.last())
        assertEquals((40 until 119).toSet(), heavy.toSet())
        assertEquals(79, heavy.size)
        assertTrue(!NtkClickOwnedManhwaWavePolicy.shouldFailoverTailHeaders(39))
        assertTrue(NtkClickOwnedManhwaWavePolicy.shouldFailoverTailHeaders(40))
        assertTrue(NtkClickOwnedManhwaWavePolicy.shouldFailoverTailHeaders(118))
        assertEquals(0L, NtkClickOwnedManhwaWavePolicy.headerFailoverMs(0))
        assertEquals(2_500L, NtkClickOwnedManhwaWavePolicy.headerFailoverMs(1))
        assertEquals(2_500L, NtkClickOwnedManhwaWavePolicy.headerFailoverMs(3))
        assertEquals(1_800L, NtkClickOwnedManhwaWavePolicy.headerFailoverMs(4))
        assertEquals(1_800L, NtkClickOwnedManhwaWavePolicy.headerFailoverMs(39))
        assertEquals(700L, NtkClickOwnedManhwaWavePolicy.headerFailoverMs(40))
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
    fun wifiCanRequireThreeSamplesWithoutChangingTheCarrierConsensus() {
        val jpg = "https://booktoki8.org/manhwa/23939/235313/p001.jpg"
        val secondJpg = "https://booktoki9.org/manhwa/23939/235313/p003.jpg"
        assertEquals(
            "jpg",
            NtkClickOwnedManhwaWavePolicy.preferredSampleExtension(
                listOf(jpg, null, secondJpg, null),
            ),
        )
        assertNull(
            NtkClickOwnedManhwaWavePolicy.preferredSampleExtension(
                listOf(jpg, null, secondJpg, null),
                minimumEvidence =
                    NtkClickOwnedManhwaWavePolicy.WIFI_PREFERRED_EXTENSION_EVIDENCE,
            ),
        )
        assertEquals(
            "jpg",
            NtkClickOwnedManhwaWavePolicy.preferredSampleExtension(
                listOf(
                    jpg,
                    "https://booktoki8.org/manhwa/23939/235313/p002.jpg",
                    secondJpg,
                    null,
                ),
            ),
        )
        assertNull(
            NtkClickOwnedManhwaWavePolicy.preferredSampleExtension(
                listOf(jpg, null, null, null),
            ),
        )
        assertEquals(
            "jpeg",
            NtkClickOwnedManhwaWavePolicy.preferredSampleExtension(
                listOf(
                    "https://booktoki8.org/manhwa/8129/149361/p001.jpeg",
                    null,
                    "https://booktoki9.org/manhwa/8129/149361/p003.jpeg",
                    null,
                ),
            ),
        )
        assertEquals(
            "jpg",
            NtkClickOwnedManhwaWavePolicy.preferredSampleExtension(
                listOf(jpg, null, null, null),
                minimumEvidence = 1,
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
