package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkClickOwnedManhwaWavePolicyTest {

    @Test
    fun completeOwnershipRingHasBoundedH2MultiplexingHeadroom() {
        assertEquals(40, NtkClickOwnedManhwaWavePolicy.BODY_LANES)
        assertEquals(8, NtkClickOwnedManhwaWavePolicy.PROBE_LANES)
        assertEquals(24, NtkClickOwnedManhwaWavePolicy.CONNECTION_SHARDS)
        assertEquals(3, NtkClickOwnedManhwaWavePolicy.REPLICA_STRIPE_SIZE)
        assertEquals(40, NtkClickOwnedManhwaWavePolicy.ACTIVE_BODY_TRANSFERS)
        assertEquals(
            40,
            NtkClickOwnedManhwaWavePolicy.DIRECT_WIFI_ORDINARY_BODY_TRANSFERS,
        )
        assertEquals(
            4,
            NtkClickOwnedManhwaWavePolicy.HOST_GPU_CURRENT_RESTORED_VIEWPORT_BODIES,
        )
        assertEquals(
            24,
            NtkClickOwnedManhwaWavePolicy.HOST_GPU_CURRENT_BULK_BODY_TRANSFERS,
        )
        assertEquals(6, NtkClickOwnedManhwaWavePolicy.HOST_GPU_CURRENT_BULK_INITIAL_TRANSFERS)
        assertEquals(
            48,
            NtkClickOwnedManhwaWavePolicy.HOST_GPU_CURRENT_BULK_UPWARD_PROBE_MIN_BODIES,
        )
        assertEquals(
            listOf(4, 6, 8, 12, 24),
            NtkClickOwnedManhwaWavePolicy.hostGpuCurrentBulkTransferLadder().toList(),
        )
        assertEquals(
            4,
            NtkClickOwnedManhwaWavePolicy.HOST_GPU_DIRECT_WIFI_ADJACENT_TAIL_BODY_TRANSFERS,
        )
        assertEquals(
            4,
            NtkClickOwnedManhwaWavePolicy.HOST_GPU_DIRECT_WIFI_ADJACENT_TAIL_EXECUTOR_LANES,
        )
        assertEquals(4, NtkClickOwnedManhwaWavePolicy.MIXED_UNCOMMON_BODY_TRANSFERS)
        assertEquals(8, NtkClickOwnedManhwaWavePolicy.SPECULATION_DEBT_LIMIT)
        assertEquals(1, NtkClickOwnedManhwaWavePolicy.FOREGROUND_RUNWAY_BODY_COUNT)
        assertEquals(12, NtkClickOwnedManhwaWavePolicy.WIFI_ENTRY_SPECULATION_PAGES)
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
        assertEquals(2, NtkWifiExactQuicSessionPool.WEBTOON_CALLBACK_THREADS_PER_SESSION)
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
    fun everyTransportKeepsOnlyTheCurrentAnchorAheadOfTheOwnedSuffix() {
        assertTrue(NtkClickOwnedManhwaWavePolicy.isForegroundRunwayBody(0, 0, 13))
        assertTrue(!NtkClickOwnedManhwaWavePolicy.isForegroundRunwayBody(1, 0, 13))
        assertTrue(!NtkClickOwnedManhwaWavePolicy.isForegroundRunwayBody(4, 0, 13))
        assertTrue(NtkClickOwnedManhwaWavePolicy.isForegroundRunwayBody(120, 120, 123))
        assertTrue(!NtkClickOwnedManhwaWavePolicy.isForegroundRunwayBody(122, 120, 123))
    }

    @Test
    fun hostGpuAdjacentTailWindowStartsAfterTheFivePagePhysicalRunwayOnly() {
        fun bounded(
            hostGpu: Boolean = true,
            adjacent: Boolean = true,
            pageIndex: Int = 5,
            forwardFirstPage: Int = 0,
            runwayPages: Int = 5,
        ) = NtkClickOwnedManhwaWavePolicy.shouldBoundHostGpuAdjacentTailTransfers(
            hostGpuEmulatorRuntime = hostGpu,
            directWifiAdjacentOwned = adjacent,
            pageIndex = pageIndex,
            forwardFirstPage = forwardFirstPage,
            physicalRunwayPages = runwayPages,
        )

        assertTrue(!bounded(pageIndex = 0))
        assertTrue(!bounded(pageIndex = 4))
        assertTrue(bounded(pageIndex = 5))
        assertTrue(bounded(pageIndex = 96))
        assertTrue(!bounded(hostGpu = false))
        assertTrue(!bounded(adjacent = false))
        assertTrue(!bounded(pageIndex = 14, forwardFirstPage = 10))
        assertTrue(bounded(pageIndex = 15, forwardFirstPage = 10))
    }

    @Test
    fun onlyHostGpuDirectWifiCurrentResumeFencesTheFourViewportBodies() {
        fun enabled(
            hostGpu: Boolean = true,
            adjacent: Boolean = false,
            wifi: Boolean = true,
            cellular: Boolean = false,
            handle: Long? = 100L,
            first: Int = 20,
        ) = NtkClickOwnedManhwaWavePolicy.shouldFenceHostGpuCurrentRestoredViewportBodies(
            hostGpuEmulatorRuntime = hostGpu,
            directWifiAdjacentOwned = adjacent,
            wifiTransport = wifi,
            cellularResilientTransport = cellular,
            capturedNetworkHandle = handle,
            forwardFirstPage = first,
        )

        assertTrue(enabled())
        assertTrue(!enabled(hostGpu = false))
        assertTrue(!enabled(adjacent = true))
        assertTrue(!enabled(wifi = false))
        assertTrue(!enabled(cellular = true))
        assertTrue(!enabled(handle = null))
        assertTrue(!enabled(first = 0))

        assertTrue(NtkClickOwnedManhwaWavePolicy.isHostGpuCurrentRestoredViewportBody(20, 20, 83))
        assertTrue(NtkClickOwnedManhwaWavePolicy.isHostGpuCurrentRestoredViewportBody(23, 20, 83))
        assertTrue(!NtkClickOwnedManhwaWavePolicy.isHostGpuCurrentRestoredViewportBody(24, 20, 83))
        assertTrue(NtkClickOwnedManhwaWavePolicy.isHostGpuCurrentRestoredViewportBody(82, 82, 83))
    }

    @Test
    fun adaptiveCurrentBulkIncludesColdPageZeroButExcludesAdjacentAndHandoffs() {
        fun enabled(
            hostGpu: Boolean = true,
            adjacent: Boolean = false,
            wifi: Boolean = true,
            cellular: Boolean = false,
            handle: Long? = 100L,
        ) = NtkClickOwnedManhwaWavePolicy.shouldAdaptHostGpuCurrentBulkBodies(
            hostGpuEmulatorRuntime = hostGpu,
            directWifiAdjacentOwned = adjacent,
            wifiTransport = wifi,
            cellularResilientTransport = cellular,
            capturedNetworkHandle = handle,
        )

        assertTrue(enabled())
        assertTrue(!enabled(hostGpu = false))
        assertTrue(!enabled(adjacent = true))
        assertTrue(!enabled(wifi = false))
        assertTrue(!enabled(cellular = true))
        assertTrue(!enabled(handle = null))
    }

    @Test
    fun lateVerifiedCurrentResumePeersOwnTheExistingEntryExecutorOnly() {
        fun prioritize(
            hostGpu: Boolean = true,
            adjacent: Boolean = false,
            entryWifi: Boolean = true,
            liveWifi: Boolean = true,
            cellular: Boolean = false,
            capturedHandle: Long? = 100L,
            liveHandle: Long? = 100L,
            page: Int = 21,
            first: Int = 20,
            pages: Int = 83,
            extension: String = "jpg",
        ) = NtkClickOwnedManhwaWavePolicy
            .shouldPrioritizeHostGpuCurrentRestoredViewportEntryBody(
                hostGpuEmulatorRuntime = hostGpu,
                directWifiAdjacentOwned = adjacent,
                wifiEntryPriorityMode = entryWifi,
                liveWifiTransport = liveWifi,
                cellularResilientTransport = cellular,
                capturedNetworkHandle = capturedHandle,
                liveNetworkHandle = liveHandle,
                pageIndex = page,
                forwardFirstPage = first,
                pageCount = pages,
                candidateExtension = extension,
            )

        assertTrue(prioritize(page = 21, extension = "jpg"))
        assertTrue(prioritize(page = 22, extension = "GIF"))
        assertTrue(prioritize(page = 23, extension = "png"))

        // The anchor has its own lane; the fourth peer and every non-resume profile stay unchanged.
        assertTrue(!prioritize(page = 20))
        assertTrue(!prioritize(page = 24))
        assertTrue(!prioritize(first = 0, page = 1))
        assertTrue(!prioritize(hostGpu = false))
        assertTrue(!prioritize(adjacent = true))
        assertTrue(!prioritize(entryWifi = false))
        assertTrue(!prioritize(liveWifi = false))
        assertTrue(!prioritize(cellular = true))
        assertTrue(!prioritize(capturedHandle = null))
        assertTrue(!prioritize(liveHandle = null))
        assertTrue(!prioritize(liveHandle = 101L))
        assertTrue(!prioritize(extension = "avif"))
    }

    @Test
    fun wifiLaunchProtectsTwelveForwardBodiesWhileCellularPolicyIsUnchanged() {
        assertEquals(
            12,
            NtkClickOwnedManhwaWavePolicy.initialSpeculationPages(wifiTransport = true),
        )
        assertEquals(
            8,
            NtkClickOwnedManhwaWavePolicy.initialSpeculationPages(wifiTransport = false),
        )
        assertTrue(
            NtkClickOwnedManhwaWavePolicy.shouldUseWifiEntryFallbackLane(
                wifiTransport = true,
                pageIndex = 1,
            ),
        )
        assertTrue(
            NtkClickOwnedManhwaWavePolicy.shouldUseWifiEntryFallbackLane(
                wifiTransport = true,
                pageIndex = 3,
            ),
        )
        assertTrue(
            NtkClickOwnedManhwaWavePolicy.shouldUseWifiEntryFallbackLane(
                wifiTransport = true,
                pageIndex = 11,
            ),
        )
        assertTrue(
            !NtkClickOwnedManhwaWavePolicy.shouldUseWifiEntryFallbackLane(
                wifiTransport = true,
                pageIndex = 12,
            ),
        )
        assertTrue(
            !NtkClickOwnedManhwaWavePolicy.shouldUseWifiEntryFallbackLane(
                wifiTransport = false,
                pageIndex = 1,
            ),
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
        assertTrue(
            !NtkClickOwnedManhwaWavePolicy.shouldHoldExactPreFrameRunway(
                wifiTransport = true,
                pageCount = 12,
            ),
        )
    }

    @Test
    fun restoredTailViewportOwnsReservedDirectWifiLane() {
        assertTrue(
            NtkClickOwnedManhwaWavePolicy.shouldUseDirectWifiRestoredViewportLane(
                wifiTransport = true,
                pageIndex = 168,
                initialViewportPage = 168,
            )
        )
        assertTrue(
            !NtkClickOwnedManhwaWavePolicy.shouldUseDirectWifiRestoredViewportLane(
                wifiTransport = false,
                pageIndex = 168,
                initialViewportPage = 168,
            )
        )
        assertTrue(
            !NtkClickOwnedManhwaWavePolicy.shouldUseDirectWifiRestoredViewportLane(
                wifiTransport = true,
                pageIndex = 167,
                initialViewportPage = 168,
            )
        )
        assertTrue(
            !NtkClickOwnedManhwaWavePolicy.shouldUseDirectWifiRestoredViewportLane(
                wifiTransport = true,
                pageIndex = 39,
                initialViewportPage = 39,
            )
        )
    }

    @Test
    fun restoredRunwayEarlyH1IsBoundedDeepCurrentDirectWifiJpegOnly() {
        fun eligible(
            adjacent: Boolean = false,
            pageIndex: Int = 40,
            forwardFirstPage: Int = 40,
            extension: String = "jpg",
            wifi: Boolean = true,
            cellular: Boolean = false,
            capturedHandle: Long? = 91L,
            liveHandle: Long? = 91L,
        ) = NtkClickOwnedManhwaWavePolicy
            .shouldUseRestoredAnchorOrdinaryDirectWifiTransport(
                directWifiAdjacentOwned = adjacent,
                pageIndex = pageIndex,
                forwardFirstPage = forwardFirstPage,
                extension = extension,
                liveWifiTransport = wifi,
                cellularResilientTransport = cellular,
                capturedNetworkHandle = capturedHandle,
                liveNetworkHandle = liveHandle,
            )

        assertTrue(eligible(extension = "jpg"))
        assertTrue(eligible(extension = "JPEG"))
        assertTrue(eligible(pageIndex = 41))
        assertTrue(eligible(pageIndex = 43))
        assertTrue(!eligible(adjacent = true))
        assertTrue(!eligible(pageIndex = 44))
        assertTrue(!eligible(pageIndex = 11, forwardFirstPage = 11))
        assertTrue(!eligible(extension = "png"))
        assertTrue(!eligible(wifi = false))
        assertTrue(!eligible(cellular = true))
        assertTrue(!eligible(capturedHandle = null))
        assertTrue(!eligible(liveHandle = 92L))
    }

    @Test
    fun verifiedEntryPriorityRequiresCurrentStableDirectWifiAndExactUncommonSuffix() {
        fun prioritize(
            pageIndex: Int = 1,
            extension: String = "gif",
            currentEpisode: Boolean = true,
            wifiEntryPriorityMode: Boolean = true,
            liveWifiTransport: Boolean = true,
            cellularResilientTransport: Boolean = false,
            capturedNetworkHandle: Long? = 41L,
            liveNetworkHandle: Long? = 41L,
        ): Boolean = NtkClickOwnedManhwaWavePolicy
            .shouldPrioritizeVerifiedDirectWifiEntryBody(
                pageIndex = pageIndex,
                candidateExtension = extension,
                currentEpisode = currentEpisode,
                wifiEntryPriorityMode = wifiEntryPriorityMode,
                liveWifiTransport = liveWifiTransport,
                cellularResilientTransport = cellularResilientTransport,
                capturedNetworkHandle = capturedNetworkHandle,
                liveNetworkHandle = liveNetworkHandle,
            )

        assertTrue(prioritize(pageIndex = 1, extension = "gif"))
        assertTrue(prioritize(pageIndex = 2, extension = "webp"))
        assertTrue(prioritize(pageIndex = 3, extension = "PNG"))
        assertTrue(prioritize(pageIndex = 11, extension = "gif"))

        // p001, the offscreen wave, and ordinary JPEGs keep their original executor.
        assertTrue(!prioritize(pageIndex = 0))
        assertTrue(!prioritize(pageIndex = 12))
        assertTrue(!prioritize(extension = "jpg"))
        assertTrue(!prioritize(extension = "JPEG"))
        assertTrue(!prioritize(extension = "avif"))

        // Adjacent work, mobile/SNI, and every detected network transition fail closed.
        assertTrue(!prioritize(currentEpisode = false))
        assertTrue(!prioritize(wifiEntryPriorityMode = false))
        assertTrue(!prioritize(liveWifiTransport = false))
        assertTrue(!prioritize(cellularResilientTransport = true))
        assertTrue(!prioritize(capturedNetworkHandle = null))
        assertTrue(!prioritize(liveNetworkHandle = null))
        assertTrue(!prioritize(liveNetworkHandle = 42L))
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

        val adjacent = NtkClickOwnedManhwaWavePolicy.adjacentExactBodyAdmissionOrder(
            pageCount = 180,
            admittedRunwayPages = 4,
        )
        assertEquals((4 until 40).toList(), adjacent.take(36))
        assertEquals((40 until 180).reversed().toList(), adjacent.drop(36))
        assertEquals((4 until 180).toSet(), adjacent.toSet())
        assertEquals(176, adjacent.size)
        assertTrue(!NtkClickOwnedManhwaWavePolicy.shouldFailoverTailHeaders(39))
        assertTrue(NtkClickOwnedManhwaWavePolicy.shouldFailoverTailHeaders(40))
        assertTrue(NtkClickOwnedManhwaWavePolicy.shouldFailoverTailHeaders(118))
        assertEquals(0L, NtkClickOwnedManhwaWavePolicy.headerFailoverMs(0))
        assertEquals(2_500L, NtkClickOwnedManhwaWavePolicy.headerFailoverMs(1))
        assertEquals(2_500L, NtkClickOwnedManhwaWavePolicy.headerFailoverMs(3))
        assertEquals(1_800L, NtkClickOwnedManhwaWavePolicy.headerFailoverMs(4))
        assertEquals(1_800L, NtkClickOwnedManhwaWavePolicy.headerFailoverMs(39))
        assertEquals(700L, NtkClickOwnedManhwaWavePolicy.headerFailoverMs(40))
        assertEquals(
            1_800L,
            NtkClickOwnedManhwaWavePolicy.headerFailoverMs(
                40,
                directWifiOrdinaryJpeg = true,
            ),
        )
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
    fun mixedEntrySamplePreservesOnlyObservedExactExtensionOrder() {
        assertEquals(
            listOf("jpg", "png"),
            NtkClickOwnedManhwaWavePolicy.observedSampleExtensions(
                listOf(
                    "https://booktoki8.org/manhwa/35765/1782298/p001.jpg",
                    "https://mana.apihost93.com/manhwa/35765/1782298/p002.jpg",
                    "https://booktoki9.org/manhwa/35765/1782298/p003.png",
                    "https://booktoki8.org/manhwa/35765/1782298/p004.png?token=ignored",
                ),
            ),
        )
        assertEquals(
            listOf("gif"),
            NtkClickOwnedManhwaWavePolicy.observedSampleExtensions(
                listOf(
                    "https://booktoki8.org/manhwa/8/9/p001.GIF",
                    null,
                    "https://booktoki9.org/manhwa/8/9/p003.gif#fragment",
                ),
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
    fun mixedPngBytesUseDeterministicLargestFirstReplicaPlacement() {
        fun body(pageIndex: Int, byteCount: Long, host: String) =
            NtkClickOwnedManhwaWavePolicy.SizedReplicaBody(pageIndex, byteCount, host)
        val assignments = NtkClickOwnedManhwaWavePolicy.sizeBalancedReplicaHosts(
            fixedBodies = listOf(
                body(2, 1_100L, "booktoki9.org"),
                body(3, 1_400L, "booktoki8.org"),
            ),
            movableBodies = listOf(
                body(11, 5_400L, "booktoki9.org"),
                body(12, 3_700L, "booktoki8.org"),
                body(24, 2_800L, "booktoki8.org"),
                body(30, 3_100L, "booktoki8.org"),
            ),
        )

        assertEquals("mana.apihost93.com", assignments[11])
        assertEquals("booktoki9.org", assignments[12])
        assertEquals("booktoki8.org", assignments[30])
        assertEquals("booktoki8.org", assignments[24])
        assertEquals(
            assignments,
            NtkClickOwnedManhwaWavePolicy.sizeBalancedReplicaHosts(
                fixedBodies = listOf(
                    body(2, 1_100L, "booktoki9.org"),
                    body(3, 1_400L, "booktoki8.org"),
                ),
                movableBodies = listOf(
                    body(24, 2_800L, "booktoki8.org"),
                    body(30, 3_100L, "booktoki8.org"),
                    body(12, 3_700L, "booktoki8.org"),
                    body(11, 5_400L, "booktoki9.org"),
                ),
            )
        )
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
