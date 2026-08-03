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
    fun cancelledCandidateWithoutAnAcquiredConnectionIsNotAClientInstance() {
        val cache = readSource("ReaderImageCache.kt")
        val report = cache.substringAfter("private fun reportNetworkObservation(")
            .substringBefore("private fun takeQuarantineConnectionObservation(")

        assertTrue(report.contains("observation.connectionId.isBlank()"))
        assertTrue(report.contains("protocol.equals(\"unknown\", ignoreCase = true)"))
        assertTrue(report.indexOf("observation.connectionId.isBlank()") <
            report.indexOf("observation.physicalClientInstanceId.ifBlank"))
    }

    @Test
    fun telemetryUsesThePhysicalOkHttpClientSelectedByARouteWrapper() {
        val cache = readSource("ReaderImageCache.kt")
        val instrumented = cache.substringAfter("private fun strictInstrumentedClient(")
            .substringBefore("private fun replicaFailoverFactory(")
        val report = cache.substringAfter("private fun reportNetworkObservation(")
            .substringBefore("private fun takeQuarantineConnectionObservation(")

        assertTrue(instrumented.contains("telemetryClientInstanceId(base)"))
        assertTrue(instrumented.contains("physicalClientInstanceId = physicalClientInstanceId"))
        assertTrue(report.contains("observation.physicalClientInstanceId.ifBlank"))
        assertTrue(report.contains("telemetryClientInstanceId(callFactory)"))
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
            "pageIndex in 0 until DIRECT_WIFI_ADJACENT_PHYSICAL_RUNWAY_PAGES"
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
        assertTrue(quarantine.contains("(forwardFirstPage until pageLimit).associateWith"))
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
        assertTrue(quarantine.contains("if (!isLiveOrdinaryDirectWifiCandidate(candidate))"))
        assertTrue(
            quarantine.contains(
                "if (ordinaryWifiLease != null && pageIndex != forwardFirstPage)"
            )
        )
        assertTrue(cache.contains("rememberNtkDirectWifiOrdinaryManhwaEpisode("))
        assertTrue(cache.contains("clickOwnedDirectWifiOrdinaryRouteFactory("))
        assertTrue(cache.contains("NtkDirectWifiOrdinaryTransportSelection"))
        assertTrue(cache.contains("selectedNetworkBoundH1() == true"))
        assertTrue(cache.contains("selectDirectWifiOrdinaryNetworkBoundH1("))
        assertTrue(cache.contains("selected.newCall(request)"))
        assertTrue(quarantine.contains("liveHandle == capturedHandle"))
        assertTrue(quarantine.contains("httpClient.isNtkCellularResilientTransportActive"))
        assertTrue(quarantine.contains("isKnownDirectWifiMixedManhwaEpisode(candidate)"))
        assertTrue(quarantine.contains("selectDirectWifiOrdinaryNetworkBoundH1(route, true)"))
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
        assertTrue(wave.contains("val initialPageLimit = pageLimit"))
        assertTrue(
            wave.contains("(forwardFirstPage until initialPageLimit).associateWith")
        )
        assertFalse(
            wave.contains(
                "minOf(pageLimit, NtkClickOwnedManhwaWavePolicy.SPECULATION_DEBT_LIMIT)"
            )
        )
        assertTrue(wave.contains("return Wave(preparedInitial)"))
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
