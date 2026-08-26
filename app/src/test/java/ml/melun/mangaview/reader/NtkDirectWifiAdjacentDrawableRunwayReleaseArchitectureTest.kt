package ml.melun.mangaview.reader

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkDirectWifiAdjacentDrawableRunwayReleaseArchitectureTest {
    private val readerSession = source("ReaderSession.kt")
    private val strictSession = source("NtkStrictSourceSession.kt")
    private val strictTransport = source("NtkStrictSourceTransport.kt")
    private val cacheTransport = source("NtkCacheSourceTransport.kt")
    private val registry = source("NtkSourceSpoolRegistry.kt")

    @Test
    fun fourDrawableCommitCrossesEveryTransportBoundaryExactlyOnce() {
        val claim = block("private data class AdjacentStrictSourceClaim(", readerSession)
        val ready = block("private fun markExactAdjacentRunwayTelemetryIfReady(", readerSession)
        val signal = block(
            "private fun signalDirectWifiAdjacentDrawableRunwayCommitted(",
            readerSession,
        )
        val receive = block("fun onAdjacentDrawableRunwayCommitted(", strictSession)

        assertTrue(claim.contains("drawableRunwayCommitted: AtomicBoolean"))
        assertTrue(ready.contains("(0 until requiredPageCount).toSet()"))
        assertTrue(ready.contains("if (!allReady) return"))
        assertTrue(
            ready.indexOf("if (!allReady) return") <
                ready.indexOf("signalDirectWifiAdjacentDrawableRunwayCommitted(target)")
        )
        assertTrue(signal.contains("cancelled.get()"))
        assertTrue(signal.contains("isDirectWifiStrictAdjacentRunwayProfile(target)"))
        assertTrue(signal.contains("path.startsWith(\"/webtoon/\")"))
        assertTrue(signal.contains("isAdjacentStrictSourceClaimLive(path, claim)"))
        assertTrue(signal.contains("drawableRunwayCommitted.compareAndSet(false, true)"))
        assertTrue(signal.contains("transport.onAdjacentDrawableRunwayCommitted(claim.episode)"))
        assertTrue(receive.contains("if (closeRequested.get()) return"))
        assertTrue(receive.contains("if (!acceptsEpisode(episode)) return@executeActor"))
        assertTrue(receive.contains("markDrawableRunwayCommitted()"))
        assertTrue(strictTransport.contains("fun onAdjacentDrawableRunwayCommitted("))
        assertTrue(cacheTransport.contains("strictSession.onAdjacentDrawableRunwayCommitted(episode)"))
        assertTrue(registry.contains("transport.onAdjacentDrawableRunwayCommitted(episode)"))
        assertTrue(
            readerSession.contains(
                "if (!path.startsWith(\"/webtoon/\") && !path.startsWith(\"/manhwa/\")) return",
            ),
        )
    }

    @Test
    fun compositorConfirmedAdjacentEntryReleasesViewportWithoutStructureMutation() {
        val actual = block(
            "fun onExactNtkAdjacentActualFramePresented(",
            readerSession,
        )
        val viewport = actual.indexOf(
            "claim.transport.onAdjacentViewportActivated(claim.episode)",
        )
        val firstActual = actual.indexOf(
            "claim.transport.onFirstActualFramePresented(claim.episode)",
        )

        assertTrue(actual.contains("isAdjacentStrictSourceClaimLive(normalizedPath, claim)"))
        assertTrue(actual.contains("claim.viewportActivated.compareAndSet(false, true)"))
        assertTrue(viewport >= 0)
        assertTrue(firstActual > viewport)
        assertTrue(!actual.contains("pages.add"))
        assertTrue(!actual.contains("appendRemainingAdjacentRunway"))
    }

    @Test
    fun blockedForwardPhysicalIntentSurvivesAsynchronousExactDecode() {
        val route = block(
            "private fun routeStrictAdjacentExactRehydrate(",
            readerSession,
        )
        val worker = block(
            "private fun runStrictAdjacentExactRehydrate(",
            readerSession,
        )
        val deliveryGate = block(
            "private fun isDeliveryInsideProtectedNumericBitmapWindow(",
            readerSession,
        )
        val busyFrontier = block(
            "private fun shouldDeliverMissingPhysicalForwardFrontier(",
            readerSession,
        )

        assertTrue(route.contains("isStrictAdjacentPageInReportedPhysicalIntent(index, page)"))
        assertTrue(route.contains("exactAdjacentPhysicalIntent = exactAdjacentPhysicalIntent"))
        assertTrue(worker.contains("!flight.exactAdjacentPhysicalIntent"))
        assertTrue(
            worker.contains(
                "retainWhenBusy = visibleIntent ||\n" +
                    "                    flight.hostPressurePhysicalReentry ||\n" +
                    "                    flight.exactAdjacentPhysicalIntent",
            ),
        )
        assertTrue(worker.contains("exactAdjacentPhysicalIntent = flight.exactAdjacentPhysicalIntent"))
        assertTrue(deliveryGate.contains("delivery.exactAdjacentPhysicalIntent"))
        assertTrue(busyFrontier.contains("!delivery.exactAdjacentPhysicalIntent"))
    }

    @Test
    fun generatedBodyReadyCallbackFollowsPageIdentityAcrossPrefixCompaction() {
        val listener = block(
            "private fun registerInitialGeneratedAssetCachedDecode(",
            readerSession,
        )
        val callback = listener.substring(
            listener.indexOf("Runnable {").also { require(it >= 0) },
        )

        assertTrue(callback.contains("currentPageIndexForDelivery(page, index)"))
        assertTrue(callback.contains("initialGeneratedAssetDecodeListeners.remove(index)"))
        assertTrue(callback.contains("initialGeneratedAssetDecodeListeners.remove(currentIndex)"))
        assertTrue(callback.contains("pageRef(currentIndex) != page"))
        assertTrue(callback.contains("scheduleVisibleGeneratedCachedDecode(\n                    currentIndex,"))
        assertTrue(!callback.contains("pageRef(index) != page"))
    }

    @Test
    fun rehydrateRouteReplacesRolledBackPageOwnerAndReprobesAlreadyPublishedBody() {
        val route = block(
            "private fun routeStrictAdjacentExactRehydrate(",
            readerSession,
        )

        assertTrue(route.contains("pageIndexLocked(owner.page, owner.initialIndex) >= 0"))
        assertTrue(route.contains("strictAdjacentRehydrateFlights.remove(identity, owner)"))
        assertTrue(route.contains("strictAdjacentBodyDescriptor(owner.page) != null"))
        assertTrue(route.contains("strictAdjacentPublishedBody(owner.page) != null"))
        assertTrue(route.contains("owner.parked.set(false)"))
        assertTrue(route.contains("scheduleStrictAdjacentExactRehydrate(owner"))
    }

    @Test
    fun lateCompositorCallbackCannotEnterANewerPhysicalGestureCadence() {
        val surface = source("ReaderSurfaceView.kt")
        val cadence = block(
            "private fun publishQualifiedNativePresentationCadence(",
            surface,
        )

        assertTrue(
            cadence.contains(
                "proof.physicalGestureRevision != physicalGestureRevision",
            ),
        )
        assertTrue(
            cadence.indexOf("proof.physicalGestureRevision != physicalGestureRevision") <
                cadence.indexOf("nativeCadenceProofGestureRevision ="),
        )
    }

    @Test
    fun sessionPixelLedgerCannotSuppressMissingStrictAdjacentSurfaceRepair() {
        val route = block(
            "private fun routeStrictAdjacentExactRehydrate(",
            readerSession,
        )
        val busy = block("private fun deliverBusyDecodeResults()", readerSession)
        val mainDelivery = block(
            "private fun deliverDecodeResultOnMain(",
            readerSession,
        )
        val repair = block(
            "private fun requiresStrictAuthoritativeSurfaceRepair(",
            readerSession,
        )

        assertTrue(route.contains("listener.isPageAuthoritativeDrawableCurrentlyInstalled(index)"))
        assertTrue(
            route.contains(
                "hasDeliveredBitmap(index) && listener.isPageDrawableInstalled(index)",
            ),
        )
        assertTrue(!route.contains("|| hasDeliveredBitmap(index)) return true"))
        assertTrue(busy.contains("!requiresStrictAuthoritativeSurfaceRepair(delivery, delivery.index)"))
        assertTrue(mainDelivery.contains("!requiresStrictAuthoritativeSurfaceRepair(currentDelivery, index)"))
        assertTrue(repair.contains("requiresAuthoritativeInlineTileAck(delivery)"))
        assertTrue(
            repair.contains("!listener.isPageAuthoritativeDrawableCurrentlyInstalled(index)"),
        )
    }

    @Test
    fun drawableRequirementUsesTheQualifiedAdjacentBulkReleasePolicy() {
        val construction = block("internal class NtkStrictSourceSession(", strictSession)
        val release = block(
            "private fun maybeReleaseAdjacentPrefetchAfterRunwayActor(",
            strictSession,
        )

        assertTrue(construction.contains("requiresAdjacentViewportAndDrawableBulkRelease"))
        assertTrue(construction.contains("NtkAdjacentBulkReleasePolicy.requiresActualViewportAndDrawableRunway("))
        assertTrue(construction.contains("requiresAdjacentViewportAndDrawableBulkRelease &&"))
        assertTrue(construction.contains("planBinding.episodePath.startsWith(\"/webtoon/\")"))
        assertTrue(construction.contains("requireViewportActual = requiresAdjacentViewportAndDrawableBulkRelease"))
        val policy = block("internal object NtkAdjacentBulkReleasePolicy", strictSession)
        assertTrue(policy.contains("hostGpuEmulatorRuntime && episodePath.startsWith(\"/manhwa/\")"))
        assertTrue(construction.contains("demandBoundedAdjacentSuffix"))
        assertTrue(release.contains("runwayBodiesComplete"))
        assertTrue(release.contains("adjacentPrefetchReleaseGate.tryClaimRelease(runwayBodiesComplete)"))
        assertTrue(
            release.indexOf("tryClaimRelease(runwayBodiesComplete)") <
                release.indexOf("releaseAdjacentPrefetchActor(")
        )
    }

    @Test
    fun strictRemainderRearmsItsBodyWakeAfterEveryMissingDescriptorSnapshot() {
        val append = block(
            "private fun appendRemainingAdjacentRunwayRefs(",
            readerSession,
        )
        val strictWait = append.indexOf("if (strictExactDescriptorWaitOwned)")
        val viewportRelease = append.indexOf(
            "startRemainingAdjacentRunwayFileFetches(",
            strictWait,
        )
        val rearm = append.indexOf("Re-arm the body-publication wake")
        val waiter = append.indexOf(
            "waitingStrictRemainingAdjacentAppends[waitingPath] = waiting",
            rearm,
        )
        val wakeCheck = append.indexOf("deferStrictRemainingAdjacentWakeAfterRegistration(", waiter)
        val retry = append.indexOf("scheduleRemainingAdjacentRunwayAppend(", wakeCheck)
        assertTrue(strictWait >= 0)
        assertTrue(viewportRelease > strictWait)
        assertTrue(rearm > viewportRelease)
        assertTrue(waiter > rearm)
        assertTrue(wakeCheck > waiter)
        assertTrue(
            retry > wakeCheck
        )
    }

    @Test
    fun clearedAdjacentPublicationTokenCancelsQueuedReadinessPolls() {
        val claim = block(
            "private fun shouldPublishPendingAdjacentAppend(",
            readerSession,
        )
        val notify = block(
            "private fun notifyAdjacentAppendWhenNearReady(",
            readerSession,
        )
        assertTrue(
            claim.contains(
                "pendingAdjacentAppendPublishes[publishKey] ?: return false",
            ),
        )
        assertTrue(notify.contains("pendingAdjacentAppendPublishes[publishKey] == null"))
        assertTrue(
            notify.indexOf("pendingAdjacentAppendPublishes[publishKey] == null") <
                notify.indexOf("hasGeneratedAppendNearReady(")
        )
    }

    @Test
    fun asynchronousNextManifestRechecksActualReadingPageBeforePublishingNextNext() {
        val append = block("fun appendAdjacentEpisode(", readerSession)
        val completionGate = append.indexOf(
            "shouldRejectForwardAppendCompletionAfterSourceGrowth(",
        )
        val warm = append.indexOf("ntkBoundaryAppendWarmUrls(", completionGate)
        val publish = append.indexOf("appendResolvedEpisode(resolvedTarget", completionGate)
        val progress = block("fun noteForwardReadingPosition(", readerSession)
        val guard = block(
            "private fun shouldRejectForwardAppendCompletionAfterSourceGrowth(",
            readerSession,
        )

        assertTrue(completionGate >= 0)
        assertTrue(warm > completionGate)
        assertTrue(publish > completionGate)
        assertTrue(
            progress.contains(
                "latestReportedReadingPage.set(publishedPageIndex.get().getOrNull(anchor))",
            ),
        )
        assertTrue(guard.contains("pageIndexLocked(requestedPage"))
        assertTrue(guard.contains("Manga.sameEpisodeIdentity(page.manga, source)"))
        assertTrue(guard.contains("latestReportedReadingPage.get()"))
        assertTrue(guard.contains("NtkAdjacentAdmissionPolicy.shouldRejectStaleForwardTail("))
        assertTrue(!guard.contains("currentViewportAnchor.get()"))
    }

    private fun source(name: String): String = File(
        "src/main/java/ml/melun/mangaview/reader/$name"
    ).readText()

    private fun block(signature: String, source: String): String {
        val start = source.indexOf(signature)
        require(start >= 0) { "Missing source signature: $signature" }
        val brace = source.indexOf('{', start)
        require(brace >= 0) { "Missing opening brace: $signature" }
        var depth = 0
        for (index in brace until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        error("Missing closing brace: $signature")
    }
}
