package ml.melun.mangaview.reader

import okhttp3.Call
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

class NtkClickOwnedExactBodyStreamTest {

    @Test
    fun predecessorPhysicalExtensionEvidenceIsUniformOrdinaryAndConsumeOnce() {
        NtkDirectWifiPredecessorPhysicalExtensionRegistry.resetForTest()
        try {
            assertTrue(
                NtkDirectWifiPredecessorPhysicalExtensionRegistry.record(
                    predecessorEpisodePath = "/manhwa/work/episode-a",
                    viewerGeneration = 71L,
                    capturedNetworkHandle = 411L,
                    liveWifiTransport = true,
                    cellularResilientTransport = false,
                    liveNetworkHandle = 411L,
                    observedCandidates = listOf(
                        "https://booktoki9.org/manhwa/work/episode-a/p001.jpeg",
                        "https://booktoki8.org/manhwa/work/episode-a/p002.JPEG",
                    ),
                    ordinaryH1WarmCandidates = listOf(
                        "https://booktoki9.org/manhwa/work/episode-a/p001.jpeg",
                        "https://booktoki8.org/manhwa/work/episode-a/p002.JPEG",
                    ),
                )
            )
            // A premature/current consumer is fail-closed and does not burn the evidence.
            assertEquals(
                null,
                NtkDirectWifiPredecessorPhysicalExtensionRegistry.consume(
                    predecessorEpisodePath = "/manhwa/work/episode-a",
                    viewerGeneration = 71L,
                    capturedNetworkHandle = 411L,
                    directWifiCompletionGatedAdjacent = true,
                    predecessorComplete = false,
                    liveWifiTransport = true,
                    cellularResilientTransport = false,
                    liveNetworkHandle = 411L,
                ),
            )
            val evidence = NtkDirectWifiPredecessorPhysicalExtensionRegistry.consume(
                    predecessorEpisodePath = "/manhwa/work/episode-a",
                    viewerGeneration = 71L,
                    capturedNetworkHandle = 411L,
                    directWifiCompletionGatedAdjacent = true,
                    predecessorComplete = true,
                    liveWifiTransport = true,
                    cellularResilientTransport = false,
                    liveNetworkHandle = 411L,
                )
            assertEquals("jpeg", evidence?.extension)
            assertEquals(listOf("booktoki9.org", "booktoki8.org"), evidence?.warmReplicaHosts)
            assertEquals(
                null,
                NtkDirectWifiPredecessorPhysicalExtensionRegistry.consume(
                    predecessorEpisodePath = "/manhwa/work/episode-a",
                    viewerGeneration = 71L,
                    capturedNetworkHandle = 411L,
                    directWifiCompletionGatedAdjacent = true,
                    predecessorComplete = true,
                    liveWifiTransport = true,
                    cellularResilientTransport = false,
                    liveNetworkHandle = 411L,
                ),
            )
        } finally {
            NtkDirectWifiPredecessorPhysicalExtensionRegistry.resetForTest()
        }
    }

    @Test
    fun predecessorEvidenceWaitsOnlyForTheActuallyOwnedForwardRange() {
        assertEquals(
            listOf(33, 34, 35, 36),
            NtkDirectWifiPredecessorPhysicalExtensionRegistry
                .ownedForwardPages(forwardFirstPage = 33, exactPageCount = 37)
                .toList(),
        )
        assertEquals(
            listOf(0, 1, 2, 3),
            NtkDirectWifiPredecessorPhysicalExtensionRegistry
                .ownedForwardPages(forwardFirstPage = 0, exactPageCount = 4)
                .toList(),
        )
    }

    @Test
    fun predecessorPhysicalExtensionRejectsMixedNonOrdinaryAndNetworkChanges() {
        NtkDirectWifiPredecessorPhysicalExtensionRegistry.resetForTest()
        try {
            fun record(candidates: List<String>) =
                NtkDirectWifiPredecessorPhysicalExtensionRegistry.record(
                    predecessorEpisodePath = "/manhwa/work/episode-a",
                    viewerGeneration = 72L,
                    capturedNetworkHandle = 412L,
                    liveWifiTransport = true,
                    cellularResilientTransport = false,
                    liveNetworkHandle = 412L,
                    observedCandidates = candidates,
                )

            assertFalse(record(listOf("https://x/p001.jpg", "https://x/p002.jpeg")))
            assertFalse(record(listOf("https://x/p001.png", "https://x/p002.png")))
            assertFalse(record(emptyList()))
            assertTrue(record(listOf("https://x/p001.jpg", "https://x/p002.jpg")))

            fun consume(
                adjacent: Boolean = true,
                wifi: Boolean = true,
                cellular: Boolean = false,
                liveHandle: Long? = 412L,
            ) = NtkDirectWifiPredecessorPhysicalExtensionRegistry.consume(
                predecessorEpisodePath = "/manhwa/work/episode-a",
                viewerGeneration = 72L,
                capturedNetworkHandle = 412L,
                directWifiCompletionGatedAdjacent = adjacent,
                predecessorComplete = true,
                liveWifiTransport = wifi,
                cellularResilientTransport = cellular,
                liveNetworkHandle = liveHandle,
            )

            assertEquals(null, consume(adjacent = false))
            assertEquals(null, consume(wifi = false))
            assertEquals(null, consume(cellular = true))
            assertEquals(null, consume(liveHandle = 413L))
            assertEquals("jpg", consume()?.extension)
        } finally {
            NtkDirectWifiPredecessorPhysicalExtensionRegistry.resetForTest()
        }
    }

    @Test
    fun inheritedOrdinaryTransportIsLimitedToCompletionGatedDirectWifiRunway() {
        fun eligible(
            adjacent: Boolean = true,
            page: Int = 0,
            extension: String = "jpg",
            wifi: Boolean = true,
            cellular: Boolean = false,
            capturedHandle: Long? = 412L,
            liveHandle: Long? = 412L,
        ) = NtkClickOwnedManhwaWavePolicy
            .shouldUseInheritedOrdinaryDirectWifiTransport(
                directWifiAdjacentOwned = adjacent,
                runwayPageIndex = page,
                inheritedExtension = extension,
                liveWifiTransport = wifi,
                cellularResilientTransport = cellular,
                capturedNetworkHandle = capturedHandle,
                liveNetworkHandle = liveHandle,
            )

        assertTrue(eligible(page = 0, extension = "jpg"))
        assertTrue(eligible(page = 3, extension = "JPEG"))
        assertFalse(eligible(adjacent = false))
        assertFalse(eligible(page = 4))
        assertFalse(eligible(extension = "png"))
        assertFalse(eligible(wifi = false))
        assertFalse(eligible(cellular = true))
        assertFalse(eligible(capturedHandle = null))
        assertFalse(eligible(liveHandle = 413L))
    }

    @Test
    fun residentExactAdoptionIsLimitedToPendingDirectWifiAdjacentRunwayIdentity() {
        fun eligible(
            adjacent: Boolean = true,
            page: Int = 3,
            reconciliationComplete: Boolean = false,
            expected: String = "https://booktoki8.org/manhwa/32685/1659488/p004.jpeg",
            resident: String = expected,
        ) = NtkDirectWifiAdjacentResidentExactAdoptionPolicy.shouldAdopt(
            directWifiAdjacentOwned = adjacent,
            forwardFirstPage = 0,
            pageIndex = page,
            runwayPageCount = 4,
            candidateReconciliationComplete = reconciliationComplete,
            expectedCanonicalAsset = expected,
            residentCanonicalAsset = resident,
        )

        assertTrue(eligible(page = 0))
        assertTrue(eligible(page = 3))
        assertFalse(eligible(adjacent = false))
        assertFalse(eligible(page = 4))
        assertFalse(eligible(reconciliationComplete = true))
        assertFalse(eligible(expected = ""))
        assertTrue(eligible(resident = "https://booktoki9.org/manhwa/32685/1659488/p004.jpg"))
        assertFalse(eligible(resident = "https://booktoki8.org/manhwa/32685/1659488/p005.jpeg"))
        assertFalse(eligible(resident = "https://example.org/manhwa/32685/1659488/p004.jpeg"))
    }

    @Test
    fun inheritedRunwayUsesCanonicalWarmHostsAndBalancesOnlyMissingStripe() {
        val policy = NtkClickOwnedManhwaWavePolicy
        val warm = listOf("booktoki9.org", "booktoki8.org")
        assertEquals("booktoki8.org", policy.preferredWarmAdjacentReplicaHost(0, warm))
        assertEquals("booktoki9.org", policy.preferredWarmAdjacentReplicaHost(1, warm))
        assertEquals("booktoki9.org", policy.preferredWarmAdjacentReplicaHost(2, warm))
        assertEquals("booktoki8.org", policy.preferredWarmAdjacentReplicaHost(3, warm))
        assertEquals(null, policy.previousWarmAdjacentReplicaPage(0, 4, warm))
        assertEquals(null, policy.previousWarmAdjacentReplicaPage(1, 4, warm))
        assertEquals(null, policy.previousWarmAdjacentReplicaPage(2, 4, warm))
        assertEquals(0, policy.previousWarmAdjacentReplicaPage(3, 4, warm))

        val shortResumeWarmHosts = listOf("mana.apihost93.com", "booktoki9.org")
        assertEquals(
            null,
            policy.previousWarmAdjacentReplicaPage(0, 5, shortResumeWarmHosts),
        )
        assertEquals(
            null,
            policy.previousWarmAdjacentReplicaPage(1, 5, shortResumeWarmHosts),
        )
        fun parallel(
            emulator: Boolean = true,
            adjacent: Boolean = true,
            page: Int = 3,
            previous: Int? = 0,
        ) = policy.shouldParallelizeHostGpuAdjacentFollower(
            hostGpuEmulatorRuntime = emulator,
            directWifiAdjacentOwned = adjacent,
            runwayPageIndex = page,
            runwayPageCount = 4,
            previousWarmPage = previous,
        )
        assertTrue(parallel())
        assertTrue(
            policy.shouldParallelizeHostGpuAdjacentFollower(
                hostGpuEmulatorRuntime = true,
                directWifiAdjacentOwned = true,
                runwayPageIndex = 4,
                runwayPageCount = 5,
                previousWarmPage = 1,
            ),
        )
        assertFalse(parallel(emulator = false))
        assertFalse(parallel(adjacent = false))
        assertFalse(parallel(page = 2, previous = null))
        assertFalse(parallel(page = 0, previous = null))
        assertEquals(null, policy.preferredWarmAdjacentReplicaHost(0, emptyList()))
        assertEquals(
            null,
            policy.preferredWarmAdjacentReplicaHost(0, listOf("not-a-replica.example")),
        )
    }

    @Test
    fun inheritedPhysicalExtensionUsesOneBodyAndNeverManifestSuffixAuthority() {
        val quarantine = readSource("NtkClickOwnedAnchorQuarantine.kt")
        val coordinator = readSource("NtkStrictEpisodeDiscoveryCoordinator.kt")
        val wave = quarantine.substringAfter("private fun startForwardWave()")
            .substringBefore("private fun startPreferredTailCandidate(")
        val inherited = quarantine.substringAfter(
            "private fun startDirectWifiAdjacentInheritedCandidate("
        ).substringBefore("private fun startVerifiedFrontierCandidate(")
        val adoption = quarantine.substringAfter("private fun adoptHeldBody(")
            .substringBefore("private fun startForwardWave()")
        val residentAdoption = quarantine.substringAfter(
            "private fun tryAdoptDirectWifiAdjacentResidentExactBody("
        ).substringBefore("private fun adoptHeldBody(")
        val residentPredecode = quarantine.substringAfter(
            "private fun attachDirectWifiAdjacentResidentPredecode("
        ).substringBefore("private fun tryAdoptDirectWifiAdjacentResidentExactBody(")
        val evidence = quarantine.substringAfter(
            "private fun armPredecessorPhysicalExtensionEvidence()"
        ).substringBefore("fun observedDocumentAuthorityFuture(")

        assertTrue(inherited.contains("predecessorPhysicalEvidence"))
        assertTrue(inherited.contains("candidateAsset(pageIndex, extension)"))
        assertTrue(inherited.contains("candidateFuture"))
        assertTrue(inherited.contains("started.candidate == candidate"))
        assertTrue(inherited.contains("started.future.thenCompose"))
        assertTrue(inherited.contains("startExactBody(candidate)"))
        assertTrue(inherited.contains("inheritedAdmissionHandoff"))
        assertTrue(inherited.indexOf("isCapturedDirectWifiTransportLive()") <
            inherited.indexOf("fetchOwnedCandidate("))
        assertTrue(inherited.contains("requireCapturedDirectWifi = true"))
        assertTrue(inherited.contains(
            "predecessorProvenOrdinaryDirectWifi ="
        ))
        assertTrue(inherited.contains(
            "shouldUseInheritedOrdinaryDirectWifiTransport("
        ))
        assertTrue(inherited.contains("reserveInheritedAdjacentHostHandoff("))
        assertTrue(inherited.contains("preferredWarmAdjacentReplicaHost("))
        assertTrue(inherited.contains("preferredOrdinaryDirectWifiReplicaHost ="))
        assertTrue(inherited.contains(".thenCombine(inheritedHostHandoff.first)"))
        assertTrue(inherited.contains("inherited.whenComplete"))
        assertTrue(inherited.contains(
            "primaryBodyExecutor(\n                    pageIndex,\n" +
                "                    inheritedCandidate,\n" +
                "                    predecessorProvenOrdinaryDirectWifi,"
        ))
        assertTrue(inherited.contains("val inheritedFollower = !inheritedHostHandoff.first.isDone"))
        assertTrue(inherited.contains("Executor { runnable -> runnable.run() }"))
        assertTrue(inherited.contains("click_adjacent_inherited_host_reuse_handoff"))
        assertTrue(inherited.contains("shouldParallelizeHostGpuAdjacentFollower("))
        assertTrue(inherited.contains("warmPreviousPage.takeUnless { parallelHostGpuFollower }"))
        assertTrue(inherited.contains("click_adjacent_host_gpu_follower_parallel"))
        assertFalse(inherited.contains("rememberNtkDirectWifiOrdinaryManhwaEpisode"))
        assertTrue(inherited.contains("startResolvedCandidate(pageIndex, candidate)"))
        assertFalse(inherited.contains("NtkAuthoritativeManifest"))
        assertFalse(inherited.contains("normalizedCanonicalAssets"))
        assertFalse(inherited.contains("listOf(\"jpg\", \"jpeg\")"))
        assertTrue(wave.contains("directWifiAdjacentOwned &&"))
        assertTrue(wave.contains("0 until directWifiAdjacentPhysicalRunwayPages"))
        assertTrue(coordinator.contains("viewerGeneration = flight.viewerGeneration"))
        assertTrue(coordinator.contains(
            "adjacentPredecessorEpisodePath = flight.adjacentPredecessorEpisodePath"
        ))
        assertTrue(adoption.indexOf("ReaderImageCache.adoptQuarantinedEncodedOriginal(") <
            adoption.indexOf("adoptedPhysicalCandidates[pageIndex]?.complete("))
        assertTrue(adoption.contains("held.body.canonicalAsset"))
        assertTrue(residentAdoption.contains(
            "NtkDirectWifiAdjacentResidentExactAdoptionPolicy.shouldAdopt("
        ))
        assertTrue(residentAdoption.contains("exactManifest.seal.normalizedCanonicalAssets"))
        assertTrue(residentAdoption.contains("manifestBoundResidentRunwayPages.add(pageIndex)"))
        assertTrue(residentAdoption.contains("attachDirectWifiAdjacentResidentPredecode("))
        assertTrue(residentAdoption.contains("predecodeQuarantinedOriginalAsync("))
        assertTrue(residentAdoption.contains("DIRECT_WIFI_ADJACENT_RUNWAY_PREDECODE_EXECUTOR"))
        assertTrue(residentPredecode.contains(
            "useNativeFileDecodeInsteadOfPrivateBitmap("
        ))
        assertTrue(
            residentPredecode.indexOf("useNativeFileDecodeInsteadOfPrivateBitmap(") <
                residentPredecode.indexOf("predecodeQuarantinedOriginalAsync(")
        )
        val genericPredecodeStart = quarantine.indexOf("private fun attachPrivatePredecodes(")
        val genericPredecodeEnd = quarantine.indexOf(
            "private fun startClickPrimaryCandidateRace(",
            genericPredecodeStart,
        )
        assertTrue(genericPredecodeStart >= 0 && genericPredecodeEnd > genericPredecodeStart)
        val genericPredecode = quarantine.substring(
            genericPredecodeStart,
            genericPredecodeEnd,
        )
        assertTrue(genericPredecode.contains(
            "useNativeFileDecodeInsteadOfPrivateBitmap("
        ))
        assertTrue(
            genericPredecode.indexOf("useNativeFileDecodeInsteadOfPrivateBitmap(") <
                genericPredecode.indexOf("predecodeQuarantinedOriginalAsync(")
        )
        assertTrue(residentPredecode.contains("adjacentRunwayOffset == 0"))
        assertTrue(
            residentPredecode.indexOf("ANCHOR_PREDECODE_EXECUTOR") <
                residentPredecode.indexOf("DIRECT_WIFI_ADJACENT_RUNWAY_PREDECODE_EXECUTOR")
        )
        assertTrue(quarantine.contains(
            "private val DIRECT_WIFI_ADJACENT_RUNWAY_PREDECODE_EXECUTOR =\n" +
                "            Executors.newSingleThreadExecutor"
        ))
        assertTrue(quarantine.contains(
            "\"ntk-click-adjacent-runway-predecode\",\n" +
                "                    Process.THREAD_PRIORITY_BACKGROUND"
        ))
        assertTrue(quarantine.contains(
            "residentAnchorProofMayPrecedeSampledCandidate =\n" +
                "                directWifiAdjacentOwned &&"
        ))
        assertTrue(coordinator.contains(
            "tokenBoundStream.residentAnchorProofMayPrecedeSampledCandidate"
        ))
        assertTrue(coordinator.contains("val residentExactAnchorBody = if ("))
        assertTrue(
            coordinator.indexOf("val residentExactAnchorBody = if (") <
                coordinator.indexOf("val sampledCandidate = if (residentExactAnchorBody == null)")
        )
        assertTrue(inherited.contains("manifestBoundResidentRunwayPages.contains(pageIndex)"))
        assertTrue(evidence.contains("checkNotNull(adoptedPhysicalCandidates[pageIndex])"))
        assertTrue(evidence.contains(".ownedForwardPages(forwardFirstPage, exactCount)"))
        assertTrue(evidence.contains("ordinaryH1WarmCandidates ="))
        assertFalse(evidence.contains("normalizedCanonicalAssets"))

        val primary = quarantine.substringAfter("private fun startClickPrimaryCandidateRace(")
            .substringBefore("private fun startResolvedCandidate(")
        assertTrue(primary.contains("restoredAnchorOrdinaryDirectWifi ="))
        assertTrue(primary.contains("isRestoredOrdinaryDirectWifiRunwayPage(pageIndex)"))
        assertTrue(quarantine.contains("click_restored_anchor_ordinary_h1_admit"))

        val fetch = quarantine.substringAfter("private fun fetchOwnedCandidate(")
            .substringBefore("private fun prepareOwnedCandidate(")
        assertTrue(fetch.contains("requireCapturedDirectWifi"))
        assertTrue(fetch.contains("!isCapturedDirectWifiTransportLive()"))
    }

    @Test
    fun directWifiCompletionGatedAdjacentProtectsOnlyTheFourPageRunwayFromHeadFanout() {
        assertEquals(
            150L,
            NtkDirectWifiAdjacentExtensionHedgePolicy.delayMs(
                directWifiCompletionGatedAdjacent = true,
                pageIndex = 9,
                forwardFirstPage = 9,
            ),
        )
        assertEquals(
            150L,
            NtkDirectWifiAdjacentExtensionHedgePolicy.delayMs(
                directWifiCompletionGatedAdjacent = true,
                pageIndex = 10,
                forwardFirstPage = 9,
            ),
        )
        assertEquals(
            150L,
            NtkDirectWifiAdjacentExtensionHedgePolicy.delayMs(
                directWifiCompletionGatedAdjacent = true,
                pageIndex = 12,
                forwardFirstPage = 9,
            ),
        )
        assertEquals(
            150L,
            NtkDirectWifiAdjacentExtensionHedgePolicy.delayMs(
                directWifiCompletionGatedAdjacent = true,
                pageIndex = 13,
                forwardFirstPage = 9,
            ),
        )
        assertEquals(
            150L,
            NtkDirectWifiAdjacentExtensionHedgePolicy.delayMs(
                directWifiCompletionGatedAdjacent = false,
                pageIndex = 9,
                forwardFirstPage = 9,
            ),
        )
        assertEquals(
            150L,
            NtkDirectWifiAdjacentExtensionHedgePolicy.delayMs(
                directWifiCompletionGatedAdjacent = false,
                pageIndex = 12,
                forwardFirstPage = 9,
            ),
        )
    }

    @Test
    fun telemetryClientIdentityFollowsFactoryObjectInsteadOfRouteLabel() {
        val sharedFactory = Call.Factory {
            throw AssertionError("Telemetry identity must not create a Call")
        }
        val otherFactory = Call.Factory {
            throw AssertionError("Telemetry identity must not create a Call")
        }

        val ordinaryRouteIdentity = ReaderImageCache.telemetryClientInstanceId(sharedFactory)
        val probeWarmRouteIdentity = ReaderImageCache.telemetryClientInstanceId(sharedFactory)

        assertEquals(ordinaryRouteIdentity, probeWarmRouteIdentity)
        assertNotEquals(
            ordinaryRouteIdentity,
            ReaderImageCache.telemetryClientInstanceId(otherFactory),
        )

        val cache = readSource("ReaderImageCache.kt")
        val telemetryIdentityBlock = cache.substringAfter(
            "internal fun telemetryClientInstanceId("
        ).substringBefore("private fun takeQuarantineConnectionObservation(")
        assertFalse(telemetryIdentityBlock.contains("callFactoryId"))
    }

    @Test
    fun missingPhysicalObservationIsExplicitlyUnmeasured() {
        val cache = readSource("ReaderImageCache.kt")
        val report = cache.substringAfter("private fun reportNetworkObservation(")
            .substringBefore("private fun takeQuarantineConnectionObservation(")

        assertTrue(report.contains("observation.connectionId.isBlank()"))
        assertTrue(report.contains("protocol.equals(\"unknown\", ignoreCase = true)"))
        assertTrue(report.contains("val clientInstanceMeasured = observation.connectionId.isNotBlank()"))
        assertTrue(report.contains("observation.clientInstanceId.isNotBlank()"))
        assertTrue(report.contains("\"unmeasured\""))
        assertFalse(report.contains("telemetryClientInstanceId(callFactory)"))
    }

    @Test
    fun telemetryUsesThePhysicalOkHttpClientSelectedByARouteWrapper() {
        val cache = readSource("ReaderImageCache.kt")
        val instrumented = cache.substringAfter("private fun strictInstrumentedClient(")
            .substringBefore("private fun replicaFailoverFactory(")
        val report = cache.substringAfter("private fun reportNetworkObservation(")
            .substringBefore("private fun takeQuarantineConnectionObservation(")

        assertTrue(instrumented.contains("NtkPhysicalConnectionObservationBridge.record("))
        assertTrue(instrumented.contains("connection,"))
        assertTrue(instrumented.contains("base,"))
        assertTrue(report.contains("observation.clientInstanceId"))
        assertTrue(report.contains("clientInstanceMeasured"))
        assertTrue(report.contains("NtkStripDigests.normalizeEpisodePath(requestEpisodePath)"))
        assertTrue(report.contains("requestRole"))
        assertFalse(report.contains("telemetryClientInstanceId(callFactory)"))

        val httpClient = readSource("../mangaview/CustomHttpClient.java")
        assertTrue(httpClient.contains(
            "fallbackTransport = exactImageFallbackTelemetryClient(fallbackTransport);"
        ))
        assertTrue(httpClient.contains("private OkHttpClient exactImageFallbackTelemetryClient"))
        assertTrue(httpClient.contains("NtkPhysicalConnectionObservationBridge.record("))
    }

    @Test
    fun probeWarmAdjacentRouteCannotBeOverriddenByOrdinaryH1Admission() {
        val warm = ReaderImageCache.NtkDirectWifiOrdinaryTransportSelection(
            forceExistingFallback = true,
        )
        warm.select(networkBoundH1 = true)
        assertEquals(false, warm.selectedNetworkBoundH1())

        val ordinary = ReaderImageCache.NtkDirectWifiOrdinaryTransportSelection()
        assertEquals(null, ordinary.selectedNetworkBoundH1())
        ordinary.select(networkBoundH1 = true)
        assertEquals(true, ordinary.selectedNetworkBoundH1())
    }

    @Test
    fun probeWarmRouteIsLimitedToExactDirectWifiAdjacentRunwayCandidate() {
        val quarantine = readSource("NtkClickOwnedAnchorQuarantine.kt")
        val preparation = quarantine.substringAfter("private fun prepareOwnedCandidate(")
            .substringBefore("private fun acquireBodyTransferPermit(")

        assertTrue(preparation.contains("directWifiAdjacentOwned &&"))
        assertTrue(preparation.contains("capturedDirectWifiNetworkHandle != null"))
        assertTrue(preparation.contains(
            ".getOrNull() == capturedDirectWifiNetworkHandle"
        ))
        assertTrue(preparation.contains(
            "pageIndex in 0 until directWifiAdjacentPhysicalRunwayPages"
        ))
        assertTrue(preparation.contains(
            "earlyJpgCandidates[pageIndex]?.getNow(null)"
        ))
        assertTrue(preparation.contains(".getOrNull() == candidate"))
        assertTrue(preparation.contains(
            "preferProbeWarmRoute = probeWarmAdjacentRunway"
        ))
        assertTrue(preparation.contains(
            "enableProofBackedExactReplicaRoute = directWifiAdjacentRunway"
        ))
        val cache = readSource("ReaderImageCache.kt")
        assertTrue(cache.contains(
            "enableProofBackedExactReplicaRoute && generatedRef == null"
        ))
        assertTrue(cache.contains("ntk-click-adjacent-probe-warm-existing"))
        assertTrue(cache.contains("click_adjacent_probe_warm_existing_route"))
    }

    @Test
    fun closeRetiresTheOwningNetworkWaveExactlyOnce() {
        val closes = AtomicInteger()
        val stream = NtkClickOwnedExactBodyStream(
            mapOf(0 to CompletableFuture.completedFuture(null)),
            Closeable { closes.incrementAndGet() },
        )

        stream.close()
        stream.close()

        assertEquals(1, closes.get())
    }

    @Test
    fun adjacentViewportReleasesTheOwningPhysicalWaveExactlyOnce() {
        val releases = AtomicInteger()
        val stream = NtkClickOwnedExactBodyStream(
            mapOf(0 to CompletableFuture.completedFuture(null)),
            Closeable { },
            adjacentViewportActivated = { releases.incrementAndGet() },
        )

        stream.onAdjacentViewportActivated()
        stream.onAdjacentViewportActivated()
        stream.close()
        stream.onAdjacentViewportActivated()

        assertEquals(1, releases.get())

        val closedReleases = AtomicInteger()
        val closedStream = NtkClickOwnedExactBodyStream(
            mapOf(0 to CompletableFuture.completedFuture(null)),
            Closeable { },
            adjacentViewportActivated = { closedReleases.incrementAndGet() },
        )
        closedStream.close()
        closedStream.onAdjacentViewportActivated()
        assertEquals(0, closedReleases.get())
    }

    @Test
    fun viewportDemandOwnedSuffixNeverReopensTheClickWave() {
        val releases = AtomicInteger()
        val stream = NtkClickOwnedExactBodyStream(
            mapOf(0 to CompletableFuture.completedFuture(null)),
            Closeable { },
            adjacentRunwayReady = { releases.incrementAndGet() },
            viewportDemandOwnsSuffix = true,
        )

        stream.onAdjacentRunwayReady()
        stream.onAdjacentRunwayReady()

        assertEquals(0, releases.get())
    }

    @Test
    fun restoredInitialViewportReleasesExactlyOneBoundPage() {
        val releasedPages = mutableListOf<Int>()
        val drawableCommits = AtomicInteger()
        val stream = NtkClickOwnedExactBodyStream(
            mapOf(
                167 to CompletableFuture.completedFuture(null),
                168 to CompletableFuture.completedFuture(null),
            ),
            Closeable { },
            initialViewportActivated = { releasedPages += it },
            initialDrawableCommitted = { drawableCommits.incrementAndGet() },
        )

        stream.onInitialViewportActivated(168)
        stream.onInitialViewportActivated(167)
        stream.onInitialDrawableCommitted()
        stream.onInitialDrawableCommitted()
        stream.close()
        stream.onInitialDrawableCommitted()
        stream.onInitialViewportActivated(167)

        assertEquals(listOf(168), releasedPages)
        assertEquals(1, drawableCommits.get())
    }

    @Test
    fun restoredWifiTailBreaksOnlyTheInitialFrameAdmissionCycle() {
        val quarantine = readSource("NtkClickOwnedAnchorQuarantine.kt")
        val session = readSource("NtkStrictSourceSession.kt")
        val reader = readSource("ReaderSession.kt")
        val admission = quarantine.substringAfter("private fun tailAdmissionFuture(")
            .substringBefore("private fun discardHeldBody(")

        assertTrue(quarantine.contains("initialViewportActivated = ::notifyInitialViewportActivated"))
        val binding = session.substringAfter("fun bindEpisode(")
            .substringBefore("fun bindResidentBodies(")
        assertTrue(binding.contains("streamedExactBodies?.onInitialViewportActivated(initialPageIndex)"))
        assertTrue(admission.contains("if (wifiEntryPriorityMode)"))
        assertTrue(admission.contains("pageIndex == initialPageIndex"))
        assertTrue(admission.contains("networkRelease else wifiEntryReleaseGate"))
        assertTrue(admission.contains("CompletableFuture.anyOf("))
        assertTrue(admission.contains("wifiEntryReleaseGate,"))
        assertTrue(admission.contains("} else {\n            networkRelease"))
        assertTrue(admission.contains("restoredTailDrawableCommitted"))
        assertTrue(reader.contains("onInitialDrawableCommitted(episode)"))
    }

    @Test
    fun directWifiAdjacentBodyWaveWaitsForPredecessorWhileFirstFourKeepFastRoute() {
        val quarantine = readSource("NtkClickOwnedAnchorQuarantine.kt")
        val coordinator = readSource("NtkStrictEpisodeDiscoveryCoordinator.kt")
        val admissionPolicy = readSource("NtkAdjacentAdmissionPolicy.kt")
        val session = readSource("NtkStrictSourceSession.kt")

        assertTrue(
            quarantine.contains(
                "private const val DIRECT_WIFI_ADJACENT_PHYSICAL_RUNWAY_PAGES = 4"
            )
        )
        assertTrue(
            quarantine.contains(
                "private const val HOST_GPU_DIRECT_WIFI_ADJACENT_PHYSICAL_RUNWAY_PAGES = 5"
            )
        )
        assertTrue(quarantine.contains("networkRelease.thenCombine(adjacentRunwayRelease)"))
        assertFalse(quarantine.contains("networkRelease.thenCombine(adjacentViewportRelease)"))
        assertTrue(
            quarantine.contains(
                "minOf(forwardFirstPage + initialSpeculationPages, exactCount)"
            )
        )
        assertTrue(quarantine.contains("adjacentPhysicalAdmissionFuture(pageIndex, callCancellation)"))
        assertTrue(quarantine.contains("awaitAdjacentPhysicalAdmission(pageIndex, callCancellation)"))
        val physicalAdmission = quarantine.substringAfter("private fun adjacentPhysicalAdmissionFuture(")
            .substringBefore("/** Final physical-call backstop")
        assertTrue(physicalAdmission.contains("adjacentPredecessorComplete.handle"))
        assertTrue(physicalAdmission.contains("adjacentRunwayRelease"))
        assertFalse(physicalAdmission.contains("adjacentViewportRelease"))
        val physicalBackstop = quarantine.substringAfter("private fun awaitAdjacentPhysicalAdmission(")
            .substringBefore("private fun releaseWave")
        assertTrue(physicalBackstop.contains("adjacentPredecessorComplete"))
        assertTrue(physicalBackstop.contains("adjacentRunwayRelease"))
        assertFalse(physicalBackstop.contains("adjacentViewportRelease.get("))
        assertTrue(quarantine.contains("adjacentViewportActivated = ::notifyAdjacentViewportActivated"))
        assertTrue(coordinator.contains("NtkAdjacentAdmissionPolicy.decide("))
        assertTrue(
            coordinator.contains(
                "val adjacentPredecessorGate = adjacentAdmission.predecessorCompletionRequired"
            )
        )
        assertTrue(
            coordinator.contains(
                "val directWifiAdjacentBodyGate = adjacentAdmission.directWifiPhysicalRunway"
            )
        )
        assertTrue(admissionPolicy.contains("!cellularResilientTransportActive"))
        val flightWorker = coordinator.substringAfter("private fun runFlight(")
            .substringBefore("private fun recoverStrictRouteAndRestart(")
        assertFalse(flightWorker.contains("isNtkWifiTransportActive"))
        assertFalse(flightWorker.contains("isNtkCellularResilientTransportActive"))
        assertFalse(flightWorker.contains("directWifiAdjacentOwned"))
        assertTrue(flightWorker.contains("if (flight.adjacentPredecessorGate)"))
        assertTrue(flightWorker.contains("awaitAdjacentPredecessorComplete(flight)"))
        assertTrue(
            flightWorker.contains("plan == null && !flight.directWifiAdjacentBodyGate")
        )
        assertTrue(flightWorker.contains("flight.directWifiAdjacentBodyGate,"))
        assertTrue(session.contains("streamedExactBodies?.onAdjacentViewportActivated()"))
        assertTrue(session.contains("val previousAdmission = rollingAdmittedPages"))
        assertTrue(session.contains("pageIndex !in previousAdmission"))
        assertTrue(session.contains("preGeometryPendingPages.addLast(pageIndex)"))
    }

    @Test
    fun productionHandoffDoesNotWaitForEveryBodyBeforePlanReservation() {
        val quarantine = readSource("NtkClickOwnedAnchorQuarantine.kt")
        val coordinator = readSource("NtkStrictEpisodeDiscoveryCoordinator.kt")
        val session = readSource("NtkStrictSourceSession.kt")
        val streamStart = quarantine.indexOf("fun streamIfExact(")
        val streamEnd = quarantine.indexOf("private fun adoptHeldBody(", streamStart)
        val streamBody = quarantine.substring(streamStart, streamEnd)

        assertTrue(streamBody.contains("future.handle"))
        assertFalse(streamBody.contains("future.get(remainingNanos"))
        assertTrue(streamBody.contains("val completeEpisodeStream ="))
        assertTrue(
            streamBody.contains(
                "exactFutures.size == effectivePageCount.get() - forwardFirstPage"
            )
        )
        assertTrue(streamBody.contains("published.size == exactFutures.size"))
        assertTrue(streamBody.contains("if (completeEpisodeStream) close()"))
        assertTrue(quarantine.contains("probeLanes="))
        assertTrue(quarantine.contains("(pageStart until pageLimit).associateWith"))
        assertTrue(quarantine.contains("documentValidated.thenApplyAsync("))
        assertTrue(quarantine.contains("completeExactForwardWave(provisionalWave)"))
        assertTrue(quarantine.contains("candidateFuture.thenCompose"))
        assertTrue(coordinator.contains("streamIfExact(exactManifestPreview)"))
        assertTrue(coordinator.contains("clickOwnedExactStream,"))
        assertTrue(session.contains("streamedExactBodyPending"))
        assertTrue(session.contains("acceptStreamedExactBodyCompletionActor"))
        assertTrue(session.contains("!it.streamedExactBodyPending"))
    }

    @Test
    fun finiteTailNetworkReleaseUsesResidentAnchorWhileDecodeStillWaitsForActualFrame() {
        val quarantine = readSource("NtkClickOwnedAnchorQuarantine.kt")
        val releaseStart = quarantine.indexOf("private fun releaseWave(reason: String)")
        val releaseEnd = quarantine.indexOf(
            "private fun completeNetworkRelease(",
            releaseStart,
        )
        val release = quarantine.substring(releaseStart, releaseEnd)

        assertTrue(release.contains("wave?.futures?.get(forwardFirstPage)"))
        assertTrue(release.contains("anchor.whenComplete"))
        assertTrue(release.contains("\"anchor_body_resident\""))
        assertFalse(release.contains("firstActualFramePresented.whenComplete"))
        assertTrue(release.contains("completeNetworkRelease(reason, \"anchor_failed\")"))
        assertTrue(
            quarantine.contains(
                "val bulkRouteReady = extensionRouteReady.thenCombine(firstActualFramePresented)"
            )
        )
        assertFalse(release.contains("sleep("))
        assertFalse(release.contains("delay("))
        assertFalse(release.contains("schedule("))
    }

    @Test
    fun uncommonViewportExtensionCannotQueueBehindUnadmittedTailWorkers() {
        val quarantine = readSource("NtkClickOwnedAnchorQuarantine.kt")
        val raceStart = quarantine.indexOf("private fun startClickPrimaryCandidateRace(")
        val raceEnd = quarantine.indexOf(
            "private fun startResolvedCandidate(",
            raceStart,
        )
        val race = quarantine.substring(raceStart, raceEnd)

        assertTrue(
            race.contains("primaryAdmissionFuture(pageIndex, alternativeCancellation)")
        )
        assertTrue(race.contains("fallbackBodyExecutor(pageIndex)"))
        val cancel = race.indexOf("primaryCancellation.cancel()")
        val alternativeAdmission = race.indexOf(
            "primaryAdmissionFuture(pageIndex, alternativeCancellation)"
        )
        val alternativeFetch = race.indexOf(
            "fetchOwnedCandidate(",
            alternativeAdmission,
        )
        assertTrue(cancel >= 0)
        assertTrue(cancel < alternativeAdmission)
        assertTrue(cancel < alternativeFetch)
        assertFalse(race.contains("awaitRollingNumericAdmission"))
        assertFalse(quarantine.contains("private fun awaitRollingNumericAdmission("))
        assertTrue(quarantine.contains("NtkClickOwnedManhwaWavePolicy.SPECULATION_DEBT_LIMIT"))
    }

    @Test
    fun headProvenEntryBodyUsesOnlyTheGuardedExistingPriorityExecutor() {
        val quarantine = readSource("NtkClickOwnedAnchorQuarantine.kt")
        val verified = quarantine.substringAfter(
            "private fun startVerifiedFrontierCandidate("
        ).substringBefore("private fun attachPrivatePredecodes(")
        val exactBody = verified.substringAfter(
            "fun startExactBody(candidate: String)"
        ).substringBefore("val verified =")
        val selector = quarantine.substringAfter(
            "private fun verifiedExactBodyExecutor("
        ).substringBefore("private fun fallbackBodyExecutor(")

        assertTrue(exactBody.contains("verifiedExactBodyExecutor(pageIndex, candidate)"))
        assertTrue(
            selector.contains("shouldPrioritizeHostGpuCurrentRestoredViewportEntryBody(")
        )
        assertTrue(selector.contains("hostGpuEmulatorRuntime = hostGpuEmulatorRuntime"))
        assertTrue(selector.contains("pageIndex = pageIndex"))
        assertTrue(selector.contains("forwardFirstPage = forwardFirstPage"))
        assertTrue(selector.contains("pageCount = effectivePageCount.get()"))
        assertTrue(selector.contains("val prioritize = prioritizeRestoredViewport ||"))
        assertTrue(
            selector.contains("shouldPrioritizeVerifiedDirectWifiEntryBody(")
        )
        assertTrue(selector.contains("currentEpisode = !directWifiAdjacentOwned"))
        assertTrue(selector.contains("liveWifiTransport = liveWifiTransport"))
        assertTrue(selector.contains("cellularResilientTransport = cellularResilientTransport"))
        assertTrue(selector.contains("capturedNetworkHandle = capturedDirectWifiNetworkHandle"))
        assertTrue(selector.contains("liveNetworkHandle = liveNetworkHandle"))
        assertTrue(selector.contains("if (!prioritize) return original"))
        assertTrue(selector.contains("return WIFI_ENTRY_FALLBACK_BODY_EXECUTOR"))

        // This branch changes only executor selection: request, permit, and transport stay shared.
        assertFalse(selector.contains("fetchOwnedCandidate("))
        assertFalse(selector.contains("acquireBodyTransferLease("))
        assertFalse(selector.contains("newFixedThreadPool("))
    }

    @Test
    fun resolvedNonJpgBodiesCanFillTheMeasuredPhysicalConnectionRing() {
        val quarantine = readSource("NtkClickOwnedAnchorQuarantine.kt")
        val fallbackExecutor = quarantine.substringAfter(
            "private val FALLBACK_BODY_EXECUTOR ="
        ).substringBefore("private val ANCHOR_FALLBACK_BODY_EXECUTOR")

        assertTrue(
            fallbackExecutor.contains(
                "NtkClickOwnedManhwaWavePolicy.ACTIVE_BODY_TRANSFERS"
            )
        )
        assertFalse(fallbackExecutor.contains("newFixedThreadPool(8)"))
    }

    @Test
    fun ordinaryDirectWifiLaneIsProofBoundAndFallsBackAtCallTime() {
        val quarantine = readSource("NtkClickOwnedAnchorQuarantine.kt")
        val cache = readSource("ReaderImageCache.kt")
        val policy = readSource("NtkClickOwnedManhwaWavePolicy.kt")

        assertTrue(policy.contains("const val BODY_LANES = 40"))
        assertTrue(policy.contains("const val DIRECT_WIFI_ORDINARY_BODY_TRANSFERS = 40"))
        assertTrue(quarantine.contains("private val DIRECT_WIFI_ORDINARY_BODY_EXECUTOR"))
        assertTrue(quarantine.contains("isKnownDirectWifiOrdinaryManhwaEpisode(candidate)"))
        assertTrue(quarantine.contains("liveHandle == capturedHandle"))
        assertTrue(quarantine.contains("isLiveOrdinaryDirectWifiCandidate("))
        assertTrue(quarantine.contains("predecessorProvenOrdinaryDirectWifi"))
        assertTrue(
            quarantine.contains(
                "if (ordinaryWifiLease != null && pageIndex != forwardFirstPage)"
            )
        )
        assertTrue(cache.contains("rememberNtkDirectWifiOrdinaryManhwaEpisode("))
        assertTrue(cache.contains("clickOwnedDirectWifiOrdinaryRouteFactory("))
        assertTrue(cache.contains("NtkDirectWifiOrdinaryTransportSelection"))
        assertTrue(cache.contains("selectedNetworkBoundH1() == true"))
        assertTrue(cache.contains("selectedPreferredReplicaHost()"))
        assertTrue(cache.contains("selectDirectWifiOrdinaryNetworkBoundH1("))
        assertTrue(cache.contains("selected.newCall(request)"))
        assertTrue(quarantine.contains("liveHandle == capturedHandle"))
        assertTrue(quarantine.contains("httpClient.isNtkCellularResilientTransportActive"))
        assertTrue(quarantine.contains("isKnownDirectWifiMixedManhwaEpisode(candidate)"))
        assertTrue(quarantine.contains("preferredOrdinaryDirectWifiReplicaHost"))
        assertTrue(quarantine.contains("rememberNtkDirectWifiMixedManhwaEpisode("))
    }

    @Test
    fun virtualGeneratedManhwaRouteFallsThroughToSignedAuthority() {
        val quarantine = readSource("NtkClickOwnedAnchorQuarantine.kt")
        val coordinator = readSource("NtkStrictEpisodeDiscoveryCoordinator.kt")
        val branch = coordinator.substringAfter(
            "val tokenBoundStream = anchor.streamIfExact(tokenBoundAuthority.manifest)"
        ).substringBefore("if (plan == null && tokenBoundAuthority == null)")

        assertTrue(quarantine.contains("val sampledAnchorCandidate: CompletableFuture<String?>?"))
        assertTrue(
            quarantine.contains(
                "sampledAnchorCandidate = earlyJpgCandidates[forwardFirstPage]"
            )
        )
        assertTrue(branch.contains("tokenBoundStream.sampledAnchorCandidate"))
        assertTrue(branch.contains("residentAnchorProofMayPrecedeSampledCandidate"))
        assertTrue(branch.contains("sampledCandidate = if (residentExactAnchorBody == null)"))
        assertTrue(branch.contains("tokenBoundStream.bodyFutures[forwardFirstPage]"))
        assertTrue(branch.contains("tokenBoundStream.close()"))
        assertTrue(branch.contains("clickOwnedAnchor = null"))
        assertTrue(branch.contains("fallback=signed_image_api"))
        assertTrue(
            branch.indexOf("token_bound_numeric_anchor_validation") <
                branch.indexOf("reserveTokenBoundGeneratedDocumentPlan(")
        )
    }

    @Test
    fun everyExactPageBelongsToOneClickOwnedStreamWithBoundedFallback() {
        val quarantine = readSource("NtkClickOwnedAnchorQuarantine.kt")
        val cache = readSource("ReaderImageCache.kt")
        val waveStart = quarantine.indexOf("private fun startForwardWave()")
        val waveEnd = quarantine.indexOf("private fun discardHeldBody(", waveStart)
        val wave = quarantine.substring(waveStart, waveEnd)
        val raceStart = quarantine.indexOf("private fun startClickPrimaryCandidateRace(")
        val raceEnd = quarantine.indexOf("private fun startResolvedCandidate(", raceStart)
        val race = quarantine.substring(raceStart, raceEnd)

        assertTrue(quarantine.contains("rememberNtkGeneratedEpisodeExtensionHint("))
        assertTrue(quarantine.contains("val sourceRoutePreparationReady = preferredExtension.thenApply"))
        assertTrue(quarantine.contains("val bulkRouteReady = extensionRouteReady.thenCombine(firstActualFramePresented)"))
        assertFalse(quarantine.contains("val latePlaceholders ="))
        assertTrue(wave.contains("forwardFirstPage + initialSpeculationPages"))
        assertTrue(wave.contains("buildForwardBodyFutures(forwardFirstPage, pageLimit)"))
        assertTrue(wave.contains("buildForwardBodyFutures(tailStart, exactPageLimit)"))
        assertTrue(wave.contains("(pageStart until pageLimit).associateWith"))
        assertFalse(
            wave.contains(
                "minOf(pageLimit, NtkClickOwnedManhwaWavePolicy.SPECULATION_DEBT_LIMIT)"
            )
        )
        assertTrue(wave.contains("return Wave(initialBodies)"))
        assertTrue(wave.contains("return Wave(exactBodies)"))
        assertTrue(race.contains("val canonicalCandidate = candidateAsset("))
        assertTrue(race.contains("startCompletedHeadMissCandidate(pageIndex)"))
        assertTrue(cache.contains("val hintedTransportAsset = hintedNtkGeneratedImageUrl(asset) ?: asset"))
        assertTrue(cache.contains("stripeStrictManhwaTransportAsset("))
        assertTrue(cache.contains("requestFor(manga, transportAsset, foregroundPriority = true)"))
        val session = readSource("NtkStrictSourceSession.kt")
        assertTrue(session.contains("streamedExactBodies?.sourceRoutePreparationReady"))
        assertTrue(session.contains("bulkSourcePhysicalAdmissionReady"))
        assertTrue(session.contains("isBulkSourcePhysicalAdmissionReady(pageIndex)"))
        assertTrue(session.contains("streamedExactBodyFutures.isNotEmpty() -> 0"))
        assertTrue(session.contains("pageIndex == initialPageIndex"))
        assertFalse(session.contains("anchorSourceRoutePreparationReady"))
    }

    @Test
    fun transferAdmissionPrecedesPhysicalCallCreationAndHeaders() {
        val cache = readSource("ReaderImageCache.kt")
        val spoolStart = cache.indexOf("internal fun spoolQuarantinedEncodedOriginal(")
        val spoolEnd = cache.indexOf(
            "fun predecodeQuarantinedOriginalAsync(",
            spoolStart,
        )
        val spool = cache.substring(spoolStart, spoolEnd)

        val admission = spool.indexOf("bodyReadAdmission?.invoke()")
        val callCreation = spool.indexOf("newTrackedNtkEpisodeCall(")
        val execute = spool.indexOf("call.execute().use")
        assertTrue(admission >= 0)
        assertTrue(admission < callCreation)
        assertTrue(callCreation < execute)
        assertEquals(
            1,
            Regex(Regex.escape("bodyReadAdmission?.invoke()")).findAll(spool).count(),
        )
    }

    @Test
    fun hostGpuAdjacentSuffixIsBoundedBeforeOrdinaryWifiAndPhysicalCallAdmission() {
        val quarantine = readSource("NtkClickOwnedAnchorQuarantine.kt")
        val admissionStart = quarantine.indexOf("bodyReadAdmission = {")
        val admissionEnd = quarantine.indexOf("onPhysicalBodyProven = {", admissionStart)
        val admission = quarantine.substring(admissionStart, admissionEnd)
        val tailWindow = admission.indexOf("acquireHostGpuAdjacentTailBodyTransferLease(")
        val ordinaryWindow = admission.indexOf("acquireOrdinaryDirectWifiTransferLease(")
        val baseWindow = admission.indexOf("acquireBodyTransferLease(")

        assertTrue(tailWindow >= 0)
        assertTrue(tailWindow < ordinaryWindow)
        assertTrue(ordinaryWindow < baseWindow)
        assertTrue(admission.contains("adjacentTailLease?.close()"))
        assertTrue(
            quarantine.contains(
                "NtkClickOwnedManhwaWavePolicy.HOST_GPU_DIRECT_WIFI_ADJACENT_TAIL_BODY_TRANSFERS"
            )
        )
        assertTrue(
            quarantine.contains(
                "HOST_GPU_DIRECT_WIFI_ADJACENT_TAIL_BODY_EXECUTOR.execute(runnable)"
            )
        )
        assertTrue(
            quarantine.contains(
                "shouldBoundHostGpuAdjacentTailTransfers("
            )
        )
    }

    @Test
    fun hostGpuCurrentResumeViewportFenceRunsBeforeEveryPhysicalCallAdmission() {
        val quarantine = readSource("NtkClickOwnedAnchorQuarantine.kt")
        val admissionStart = quarantine.indexOf("bodyReadAdmission = {")
        val admissionEnd = quarantine.indexOf("onPhysicalBodyProven = {", admissionStart)
        val admission = quarantine.substring(admissionStart, admissionEnd)
        val viewportFence = admission.indexOf(
            "awaitHostGpuCurrentRestoredViewportBodyAdmission("
        )
        val adjacentFence = admission.indexOf("awaitAdjacentPhysicalAdmission(")
        val restoredBulk = admission.indexOf(
            "acquireHostGpuCurrentRestoredBulkBodyTransferLease("
        )
        val mixedUncommon = admission.indexOf(
            "acquireMixedUncommonTransferLease("
        )
        val ordinaryWifi = admission.indexOf(
            "acquireOrdinaryDirectWifiTransferLease("
        )
        val physicalPermit = admission.indexOf("acquireBodyTransferLease(")

        assertTrue(viewportFence >= 0)
        assertTrue(viewportFence < adjacentFence)
        assertTrue(adjacentFence < restoredBulk)
        assertTrue(restoredBulk < mixedUncommon)
        assertTrue(mixedUncommon < ordinaryWifi)
        assertTrue(ordinaryWifi < physicalPermit)
        assertTrue(admission.contains("mixedUncommonLease?.close()"))
        assertTrue(admission.contains("currentRestoredBulkOwner?.abandonAdaptive()"))
        assertTrue(admission.contains("currentRestoredBulkOutcome = null"))
        val beforeAdmission = quarantine.substring(0, admissionStart)
        assertFalse(beforeAdmission.contains("mixedUncommonLease = acquireMixedUncommonTransferLease("))
        assertTrue(
            quarantine.contains(
                "armHostGpuCurrentRestoredViewportBodyRelease(initialBodies)"
            )
        )
        assertTrue(
            quarantine.contains(
                "if (!isCapturedDirectWifiTransportLive()) return"
            )
        )
        val restoredHelperStart = quarantine.indexOf(
            "private fun acquireHostGpuCurrentRestoredBulkBodyTransferLease("
        )
        val restoredHelperEnd = quarantine.indexOf(
            "private fun acquireOrdinaryDirectWifiTransferLease(",
            restoredHelperStart,
        )
        val restoredHelper = quarantine.substring(restoredHelperStart, restoredHelperEnd)
        assertTrue(
            restoredHelper.indexOf("hostGpuCurrentRestoredTotalBulkBodyTransferPermits.tryAcquire(") <
                restoredHelper.indexOf("hostGpuCurrentRestoredBulkAdmission.tryAcquire(")
        )
        assertTrue(restoredHelper.contains("isLiveOrdinaryDirectWifiCandidate("))
        assertTrue(quarantine.contains("val stillComparableOrdinaryBody ="))
        val wrapperStart = quarantine.indexOf("private class CurrentRestoredBulkBodyLease(")
        val wrapperEnd = quarantine.indexOf(
            "/** Purely local request material",
            wrapperStart,
        )
        val wrapper = quarantine.substring(wrapperStart, wrapperEnd)
        assertTrue(wrapper.contains("adaptive.getAndSet(null)?.aborted()"))
        assertTrue(wrapper.contains("totalLease.close()"))
        assertFalse(wrapper.contains("adaptive.getAndSet(null)?.close()"))
        assertTrue(quarantine.contains("currentRestoredBulkOutcome?.close()"))
    }

    @Test
    fun mixedPngEarlyBodyRemainsWifiOnlyDocumentAndHeadProved() {
        val quarantine = readSource("NtkClickOwnedAnchorQuarantine.kt")
        val cache = readSource("ReaderImageCache.kt")
        val start = quarantine.indexOf("private fun startVerifiedFrontierCandidate(")
        val end = quarantine.indexOf("private fun attachPrivatePredecodes(", start)
        val verified = quarantine.substring(start, end)
        val guardStart = cache.indexOf(
            "fun directWifiMixedManhwaSpeculativeUncommonExtension("
        )
        val guardEnd = cache.indexOf("\n    }\n", guardStart) + 7
        val guard = cache.substring(guardStart, guardEnd)

        assertTrue(verified.contains("val admission = primaryAdmissionFuture("))
        assertTrue(verified.contains(".thenCombine(documentValidated.handle"))
        assertTrue(verified.contains("directWifiMixedManhwaSpeculativeUncommonExtension("))
        assertTrue(verified.contains("started.candidate == candidate"))
        assertTrue(verified.contains("observedCandidates[pageIndex]?.complete(candidate)"))
        assertTrue(verified.contains("earlyVerifiedCancellation.cancel()"))
        assertFalse(verified.contains("candidateFuture.complete("))
        assertTrue(guard.contains("httpClient.isNtkWifiTransportActive"))
        assertTrue(guard.contains("!httpClient.isNtkCellularResilientTransportActive()"))
    }

    @Test
    fun mixedPngPhysicalPlanIsFiniteWifiOnlyAndHeadSized() {
        val quarantine = readSource("NtkClickOwnedAnchorQuarantine.kt")
        val cache = readSource("ReaderImageCache.kt")
        val policy = readSource("NtkClickOwnedManhwaWavePolicy.kt")
        val publishStart = cache.indexOf(
            "fun rememberNtkDirectWifiMixedManhwaPhysicalHostPlan("
        )
        val publishEnd = cache.indexOf("\n    }\n", publishStart) + 7
        val publish = cache.substring(publishStart, publishEnd)
        val routeStart = cache.indexOf(
            "private fun directWifiMixedManhwaPhysicalAsset("
        )
        val routeEnd = cache.indexOf("\n    }\n", routeStart) + 7
        val route = cache.substring(routeStart, routeEnd)

        assertTrue(quarantine.contains("onUsableResponse = { asset, byteCount ->"))
        assertTrue(quarantine.contains("PHYSICAL_PLAN_WAIT_MS"))
        assertTrue(quarantine.contains("sizeBalancedReplicaHosts("))
        assertTrue(quarantine.contains("val firstUsableMixedExtensions"))
        assertTrue(quarantine.contains("probeDirectWifiTailPage("))
        assertTrue(quarantine.contains("rememberNtkDirectWifiMixedManhwaPhysicalHostPlan("))
        assertTrue(quarantine.contains("val routedFutures = futures.mapValues"))
        assertTrue(quarantine.contains("mixedUncommonTransferPermits"))
        assertTrue(quarantine.contains("acquireMixedUncommonTransferLease("))
        assertTrue(publish.contains("httpClient.getNtkDirectWifiNetwork()"))
        assertTrue(publish.contains("val networkHandle = directWifiNetwork.networkHandle"))
        assertTrue(publish.contains("existing.networkHandle != networkHandle"))
        assertTrue(quarantine.contains("byteCount >= DIRECT_WIFI_LARGE_PNG_BODY_BYTES"))
        assertTrue(quarantine.contains("it.byteCount < DIRECT_WIFI_LARGE_PNG_BODY_BYTES"))
        assertTrue(route.contains("directWifiNetwork: android.net.Network"))
        assertTrue(route.contains("plan.networkHandle != directWifiNetwork.networkHandle"))
        assertTrue(policy.contains("compareByDescending<SizedReplicaBody> { it.byteCount }"))
    }

    @Test
    fun privateFullBitmapDecodeIsBoundedToTheEntryRunway() {
        val quarantine = readSource("NtkClickOwnedAnchorQuarantine.kt")
        val attachStart = quarantine.indexOf("private fun attachPrivatePredecodes(")
        val attachEnd = quarantine.indexOf("private fun startClickPrimaryCandidateRace(", attachStart)
        val attach = quarantine.substring(attachStart, attachEnd)

        assertTrue(
            quarantine.contains(
                "private const val PRIVATE_PREDECODE_RUNWAY_PAGES = SPECULATIVE_CLICK_PAGES"
            )
        )
        assertTrue(
            attach.contains(
                "pageIndex - forwardFirstPage >= PRIVATE_PREDECODE_RUNWAY_PAGES"
            )
        )
        assertTrue(attach.contains("retained[pageIndex] = held"))
        assertTrue(attach.contains("ReaderImageCache.predecodeQuarantinedOriginalAsync("))
    }

    @Test
    fun proofBackedAdjacentExactBodiesUseTheNetworkBoundH1BodyLane() {
        val cache = readSource("ReaderImageCache.kt")
        val start = cache.indexOf("val directWifiProofBackedExactBody =")
        val end = cache.indexOf("return NtkResolvedSourceRoute(", start)
        val route = cache.substring(start, end)

        assertTrue(route.contains("exactApiReplicaTag != null"))
        assertTrue(route.contains("httpClient.isNtkWifiTransportActive()"))
        assertTrue(route.contains("clickOwnedDirectWifiOrdinaryBodyClient("))
        assertTrue(route.contains("ntk-click-adjacent-exact-api-replica-h1"))
    }

    private fun readSource(name: String): String {
        var cursor = File(checkNotNull(System.getProperty("user.dir"))).canonicalFile
        repeat(8) {
            val candidate = File(
                cursor,
                "app/src/main/java/ml/melun/mangaview/reader/$name",
            )
            if (candidate.isFile) return candidate.readText()
            cursor = cursor.parentFile ?: return@repeat
        }
        error("Repository source not found: $name")
    }
}
