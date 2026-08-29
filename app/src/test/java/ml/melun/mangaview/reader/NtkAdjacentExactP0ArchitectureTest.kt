package ml.melun.mangaview.reader

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkAdjacentExactP0ArchitectureTest {
    private val reader = source("ReaderSession.kt")
    private val surface = source("ReaderSurfaceView.kt")
    private val strict = source("NtkStrictSourceSession.kt")
    private val hostPool = source("HostExactHardwareTilePool.kt")
    private val imageCache = source("ReaderImageCache.kt")
    private val transport = source("NtkStrictSourceTransport.kt")
    private val cacheTransport = source("NtkCacheSourceTransport.kt")
    private val registry = source("NtkSourceSpoolRegistry.kt")
    private val listenerGate = source("ReaderSessionListenerGate.kt")
    private val adjacentContracts = source("NtkAdjacentExactP0.kt")
    private val activity = File(
        "src/main/java/ml/melun/mangaview/activity/ReaderV2Activity.kt",
    ).readText()

    @Test
    fun exactP0OwnerAcceptsBothSupportedLongStripEpisodeKinds() {
        assertTrue(adjacentContracts.contains("normalizedEpisodePath.startsWith(\"/webtoon/\") ||"))
        assertTrue(adjacentContracts.contains("normalizedEpisodePath.startsWith(\"/manhwa/\")"))
    }

    @Test
    fun failedResidentP0PreparationRetriesFromTimerInsteadOfRewakingTheSameEvent() {
        val append = block("private fun appendResolvedEpisodeInitialRunway(", reader)
        val failure = block("if (flight == null) {", append)
        assertTrue(failure.contains("forceDelayedPolling = true"))
    }

    @Test
    fun p1ToP3OpenOnlyAfterRendererHeadAckAcrossEveryTransportBoundary() {
        val publish = block("private fun publishDirectWifiAdjacentExactP0Head(", reader)
        val receive = block("fun onAdjacentHeadPixelsInstalled(", strict)
        assertTrue(publish.contains("listener.onAdjacentExactP0HeadReady(publication)"))
        assertTrue(publish.contains("if (!accepted)"))
        assertTrue(publish.indexOf("listener.onAdjacentExactP0HeadReady(publication)") <
            publish.indexOf("signalDirectWifiAdjacentHeadPixelsInstalled(target, flight.owner)"))
        assertTrue(receive.contains("adjacentHeadPixelsInstalled = true"))
        assertTrue(receive.contains("refillLanesActor()"))
        assertTrue(transport.contains("fun onAdjacentHeadPixelsInstalled("))
        assertTrue(cacheTransport.contains("strictSession.onAdjacentHeadPixelsInstalled(episode)"))
        assertTrue(registry.contains("transport.onAdjacentHeadPixelsInstalled(episode)"))
    }

    @Test
    fun exactP0TailWorkerReleasesItsLeaseAfterEveryExit() {
        val schedule = block("private fun scheduleDirectWifiAdjacentExactP0Tail(", reader)
        val worker = block("strictExactOverlapDecode.execute {", schedule)
        val cancel = block("private fun cancelInternal(", reader)
        val workerStarted = worker.indexOf("flight.tailWorkerStarted.set(true)")
        val earlyExit = worker.indexOf("return@execute")
        val terminalFinally = worker.lastIndexOf("finally {")
        val terminalRelease = worker.lastIndexOf("releaseAdjacentExactP0Flight(flight)")
        val queuedRelease = cancel.indexOf(
            "if (!flight.tailWorkerStarted.get()) releaseAdjacentExactP0Flight(flight)",
        )
        val overlapShutdown = cancel.indexOf("strictExactOverlapDecode.shutdownNow()")

        assertTrue(workerStarted >= 0 && earlyExit > workerStarted)
        assertTrue(terminalFinally > earlyExit)
        assertTrue(terminalRelease > terminalFinally)
        assertTrue(queuedRelease >= 0 && overlapShutdown > queuedRelease)
        assertTrue(reader.contains("if (!flight.released.compareAndSet(false, true)) return"))
    }

    @Test
    fun headPublicationIsFrameAtomicAndFailureRollsBackBothModels() {
        val callback = block("override fun onAdjacentExactP0HeadReady(", activity)
        val publish = block("private fun publishDirectWifiAdjacentExactP0Head(", reader)
        assertTrue(callback.contains("setFrameSchedulingSuppressed(true)"))
        assertTrue(callback.contains("onPreparedAdjacentPagesAppended(publication.totalPageCount)"))
        assertTrue(callback.contains("setPageCard(publication.cardIndex"))
        assertTrue(callback.contains("installAdjacentExactP0Delta(publication.delta)"))
        assertTrue(callback.contains("setFrameSchedulingSuppressed(false)"))
        assertTrue(callback.contains(
            "requestDirectWifiAdjacentExactP0ContentCatchup(publication.delta.owner)"
        ))
        assertTrue(callback.contains("catchupEligible = result.accepted && result.firstInstall"))
        assertTrue(
            callback.indexOf("setFrameSchedulingSuppressed(false)") <
                callback.indexOf("requestDirectWifiAdjacentExactP0ContentCatchup")
        )
        val catchup = block("fun requestDirectWifiAdjacentExactP0ContentCatchup(", surface)
        assertTrue(catchup.contains("directWifiExpandedNativeTextureEpisodePaths"))
        assertTrue(catchup.contains("NtkAdjacentExactP0FrameCatchupPolicy.shouldPost"))
        assertTrue(catchup.contains("inFlightEpoch"))
        assertTrue(catchup.contains("inFlightToken"))
        assertTrue(catchup.contains("postAtFrontOfQueue(directAdjacentExactP0Catchup)"))
        val catchupRunnable = block("private val directAdjacentExactP0Catchup:", surface)
        assertTrue(catchupRunnable.contains("directWifiExpandedNativeTextureRunway"))
        assertTrue(catchupRunnable.contains(
            "pages.any { it.adjacentExactOwner == expectedOwner }"
        ))
        assertTrue(catchupRunnable.contains("inFlightEpoch == expectedEpoch"))
        assertTrue(catchupRunnable.contains("inFlightToken == expectedToken"))
        assertTrue(catchupRunnable.contains("rollingNativeAttachEpoch == expectedAttachEpoch"))
        assertTrue(catchupRunnable.contains("removeCallbacks(directFramePostRunnable)"))
        assertTrue(publish.contains("rollbackAdjacentRunwayStructure(cardIndex, allRefs)"))
        assertTrue(publish.contains("recycleAdjacentExactP0Delta(flight.head)"))
    }

    @Test
    fun sparseHeadNeverClaimsWholePageReadinessAndScrollUsesContinuousRows() {
        val install = block("fun installAdjacentExactP0Delta(", surface)
        val complete = block("private fun pageHasCompleteDrawableContentLocked(", surface)
        val scroll = block("private fun forwardScrollLimitLocked(", surface)
        val readiness = block("fun hasAuthoritativeOriginalPage(", surface)
        assertTrue(install.contains("if (delta.complete != full)"))
        assertTrue(install.contains("contiguousAdjacentExactSourceBottom"))
        assertTrue(complete.contains("plan.sourceHeight"))
        assertTrue(scroll.contains("adjacentExactPrefixBottomLocked(index)"))
        assertTrue(readiness.contains("usableAuthoritativeOriginalTilePage"))
        assertFalse(readiness.contains("adjacentExactPrefixBottomLocked"))
    }

    @Test
    fun hostGpuDrawStateRequalifiesTheExactCombinedResumeViewport() {
        val drawState = block("private fun buildDrawStateLocked(", surface)
        val qualifier = block(
            "private fun qualifyDirectWifiForwardOnlyInitialResumeRevealLocked(",
            surface,
        )
        assertTrue(drawState.contains("emulatorNativeSurfaceRuntime"))
        assertTrue(drawState.contains("qualifyDirectWifiForwardOnlyInitialResumeRevealLocked()"))
        assertTrue(drawState.contains(
            "forwardOnlyInitialResumeViewport =\n" +
                "            qualifyDirectWifiForwardOnlyInitialResumeRevealLocked()"
        ))
        assertTrue(qualifier.contains("directWifiForwardOnlyInitialResumeViewportOpaqueLocked()"))
        assertFalse(qualifier.contains("emulatorNativeSurfaceRuntime"))
    }

    @Test
    fun hostGpuExactNativeSubmissionHoldsStructuralGapsWithoutLosingBoundaryState() {
        val prepare = block("private fun prepareRenderWork(", surface)
        val drawState = block("private fun buildDrawStateLocked(", surface)
        val traversal = block("private fun frameTraversalProof(", surface)
        val structure = block(
            "private fun hasNonContiguousExactNativeStructure(",
            surface,
        )
        assertTrue(prepare.contains("exactStructureHeld"))
        assertTrue(prepare.contains("state = null"))
        assertTrue(prepare.contains("boundaryDispatchInFlight = false"))
        assertTrue(prepare.contains("boundaryArmedDirection = heldBoundary.direction"))
        assertTrue(prepare.contains("releasePostedAdmissionLocked(preserveDirty = true)"))
        assertTrue(prepare.contains("if (exactStructureHeld && animateScroll)"))
        assertTrue(prepare.indexOf("exactStructureHeld") <
            prepare.indexOf("consumePendingPixelMutationTimingLocked"))
        assertTrue(traversal.contains("exactStructureContinuous"))
        assertTrue(drawState.contains("NtkDirectWifiExactVisibleStructurePolicy.shouldEnforce"))
        assertFalse(drawState.substringAfter(
            "NtkDirectWifiExactVisibleStructurePolicy.shouldEnforce"
        ).substringBefore("),").contains("directWifiExpandedNativeTextureRunway"))
        assertTrue(structure.contains("state.enforceContiguousExactStructure"))
        assertTrue(structure.contains("item.stripAuthoritative || item.adjacentExactAuthoritative ||"))
        assertTrue(structure.contains("item.committedIdentity != null"))
        assertTrue(structure.contains("item.index != previousIndex + 1"))
    }

    @Test
    fun terminalTailActualFlagIsImmutableAndDoesNotRelaxTheCombinedViewportProof() {
        val terminalTail = block(
            "private fun directWifiForwardOnlyTerminalTailActualLocked(",
            surface,
        )
        val drawState = block("private fun buildDrawStateLocked(", surface)
        val combined = block(
            "private fun directWifiForwardOnlyInitialResumeViewportOpaqueLocked(",
            surface,
        )
        assertTrue(terminalTail.contains("emulatorNativeSurfaceRuntime"))
        assertFalse(terminalTail.contains("target != pages.lastIndex"))
        assertTrue(terminalTail.contains("for (index in target until pages.size)"))
        assertTrue(terminalTail.contains("identity.normalizedEpisodePath != episodePath"))
        assertTrue(terminalTail.contains("identity.sourcePageIndex > previousSource + 1"))
        assertTrue(terminalTail.contains("previousSource == manifestPageCount - 1"))
        assertTrue(terminalTail.contains("terminalBottom < viewportBottom"))
        assertTrue(drawState.contains("forwardOnlyTerminalTailActual"))
        assertTrue(combined.contains("sawTransition"))
        assertTrue(combined.contains("sawAdjacentActual"))
        assertFalse(combined.contains("directWifiForwardOnlyTerminalTailActual"))
    }

    @Test
    fun adjacentP0CanCoexistWithTheCurrentEpisodesStripAuthority() {
        val install = block("fun installAdjacentExactP0Delta(", surface)
        assertFalse(install.contains("stripAuthorityToken != 0L"))
        assertTrue(install.contains("identity.normalizedEpisodePath == delta.owner.normalizedEpisodePath"))
        assertTrue(install.contains("identity.sourcePageIndex == 0"))
        assertTrue(install.contains("identity.canonicalAsset == delta.owner.canonicalAsset"))
        assertTrue(install.contains("identity.manifestDigest == delta.owner.manifestDigest"))
        assertTrue(install.contains("page.adjacentExactOwner != owner"))
        assertTrue(install.contains("page.stripAuthority != 0L"))
        assertTrue(install.contains("page.stripSlots.isNotEmpty()"))
        assertTrue(install.contains("page.cardBitmap != null"))
    }

    @Test
    fun exactSparseRouteAlsoProtectsHostGpuAdjacentManhwaP0() {
        val candidate = block("private fun isDirectWifiAdjacentExactP0HeadCandidate(", reader)
        val prepare = block("private fun prepareDirectWifiAdjacentExactP0Head(", reader)
        val publish = block("private fun appendResolvedEpisodeInitialRunway(", reader)
        val install = block("fun installAdjacentExactP0Delta(", surface)
        val construction = block("internal class NtkStrictSourceSession(", strict)
        assertTrue(candidate.contains("isDirectWifiStrictAdjacentRunwayProfile(target)"))
        assertTrue(candidate.contains("path.startsWith(\"/webtoon/\")"))
        assertTrue(candidate.contains("hostGpuEmulatorRuntime && path.startsWith(\"/manhwa/\")"))
        assertTrue(candidate.contains("p0.sourceIndex == 0"))
        assertTrue(prepare.contains("promoteAdjacentExactTilesForDirectPresenter("))
        assertTrue(prepare.contains("decodeAdjacentExactSoftwareSlots(lease, plan, headSlots, parallel = true)"))
        assertTrue(publish.contains("adjacentExactP0PreparePaths.add(exactPath)"))
        assertTrue(publish.contains("append_adjacent_exact_p0_prepare_join"))
        assertTrue(publish.contains("adjacentExactP0PreparePaths.remove(exactPath)"))
        val passiveJoin = block("if (!adjacentExactP0PreparePaths.add(exactPath)) {", publish)
        assertFalse(passiveJoin.contains("scheduleInitialAdjacentRunwayAppendRetry("))
        assertTrue(passiveJoin.contains("shouldLogRateLimitedDiagnostic("))
        assertTrue(publish.contains("val publishedFallbackReady = !descriptorReady"))
        assertTrue(install.contains("!ownerPath.startsWith(\"/webtoon/\")"))
        assertTrue(install.contains("!ownerPath.startsWith(\"/manhwa/\")"))
        assertTrue(construction.contains("adjacentPrefetch && directWifiTransport"))
        assertTrue(construction.contains("!cellularResilientTransport"))
        assertTrue(construction.contains("planBinding.episodePath.startsWith(\"/webtoon/\")"))
        assertTrue(construction.contains("hostGpuAdjacentManhwaHeadInstall"))
        assertTrue(construction.contains("hostGpuAdjacentManhwaHeadInstall -> 1"))
        val completion = block(
            "private fun drainPublishedExactAdjacentInitialRunwayCompletion(",
            reader,
        )
        val runwayLimit = block("private fun initialAdjacentRunwayPageLimit(", reader)
        assertTrue(runwayLimit.contains("path.startsWith(\"/webtoon/\")"))
        assertTrue(runwayLimit.contains("path.startsWith(\"/manhwa/\")"))
        assertTrue(runwayLimit.contains("NTK_HOST_GPU_DIRECT_WIFI_ADJACENT_RUNWAY_PAGES"))
        assertTrue(reader.contains("NTK_HOST_GPU_DIRECT_WIFI_ADJACENT_RUNWAY_PAGES = 5"))
        assertTrue(completion.contains("initialAdjacentRunwayPageLimit(path)"))
        assertFalse(completion.contains("forceSoftwareExactTiles"))
        val promotion = block(
            "private fun promoteAdjacentExactTilesForDirectPresenter(",
            reader,
        )
        assertTrue(promotion.contains("HostExactHardwareTilePool.copyExactTileBitmap(software)"))
        assertTrue(promotion.contains("HostExactHardwareTilePool.hasExactStorage("))
        assertTrue(promotion.contains("HostExactHardwareTilePool.retireAll("))
        val exactCopy = block("fun copyExactTileBitmap(", hostPool)
        assertTrue(exactCopy.contains("allowTransientOvercommit = false"))
        assertTrue(exactCopy.contains("waitForCompatibleRetirement = false"))
        assertTrue(exactCopy.contains("bounded rolling"))
        val complete = block("private fun pageHasCompleteDrawableContentLocked(", surface)
        val nativeResources = block("private fun pageHasDirectPresenterResourcesLocked(", surface)
        val nativeReady = block("private fun nativeStructuralPixelsReady(", surface)
        val itemReady = block("private fun nativeItemPixelsReadyForSubmission(", surface)
        assertTrue(complete.contains("pageHasDirectPresenterResourcesLocked(page)"))
        assertTrue(nativeResources.contains("directWifiExpandedNativeTextureEpisodePaths"))
        assertTrue(nativeResources.contains("HostExactHardwareTilePool.nativeHandle(tile.bitmap) != 0L"))
        assertTrue(nativeReady.contains("nativeItemPixelsReadyForSubmission(item, directTiles)"))
        assertTrue(itemReady.contains("HostExactHardwareTilePool.nativeHandle(tile.bitmap) == 0L"))
        val headPublish = block("private fun publishDirectWifiAdjacentExactP0Head(", reader)
        assertTrue(headPublish.contains(
            "requestPublishedExactAdjacentInitialRunwayCompletion(publishedPath)"
        ))
    }

    @Test
    fun exactP0ResidentBodyWakesTheInitialAppendWithoutChangingOtherTransports() {
        val bind = block("private fun ensureAdjacentStrictSourceClaim(", reader)
        val install = block("private fun acceptAdjacentStrictBodyDescriptor(", reader)
        val wake = block("private fun wakeInitialAdjacentExactP0Append(", reader)
        val schedule = block("private fun scheduleInitialAdjacentRunwayAppendRetry(", reader)
        val scope = block("private fun initialAdjacentExactP0WakePath(", reader)

        assertTrue(reader.contains("waitingInitialAdjacentExactP0Appends"))
        assertTrue(bind.contains("seal.digestSha256"))
        assertTrue(bind.contains("acceptAdjacentStrictBodyDescriptor("))
        assertTrue(install.contains("wakeInitialAdjacentExactP0Append("))
        assertTrue(
            install.indexOf("adjacentStrictBodyDescriptors.putIfAbsent") <
                install.indexOf("wakeInitialAdjacentExactP0Append("),
        )
        assertTrue(wake.contains("sourceIndex != 0"))
        assertTrue(wake.contains("path.startsWith(\"/webtoon/\")"))
        assertTrue(wake.contains("!isDirectWifiStrictAdjacentRunwayProfile(episodePath = path)"))
        assertTrue(wake.contains("adjacentExactP0WakeKey(path, manifestDigest)"))
        assertTrue(wake.contains("waitingInitialAdjacentExactP0Appends.wake"))
        assertTrue(scope.contains("Manga.sameEpisodeIdentity(manga, target)"))
        assertTrue(scope.contains("isDirectWifiAdjacentExactP0HeadCandidate(target, runwayRefs)"))
        assertTrue(schedule.contains("val strictAdjacentAuthority = exactViewerApiAdjacentAuthority(target)"))
        assertTrue(schedule.contains("strictAdjacentAuthority?.takeIf"))
        assertTrue(schedule.contains("exactP0WakePath.isNotEmpty()"))
        assertTrue(schedule.contains("authority.seal.digestSha256"))
        assertTrue(schedule.contains("exactWakeRegistry.register"))
        assertTrue(schedule.contains("adjacentStrictBodyDescriptors.containsKey(descriptorKey)"))
        assertTrue(schedule.contains("initialAdjacentRunwayAppendRetryDelayMs(target)"))
        assertTrue(schedule.contains("initialAdjacentWebtoonP0EventWakeLane.get() == true"))
        assertTrue(schedule.contains("if (directWebtoonEventWake || directManhwaEventWake)"))
        assertTrue(reader.contains("waitingInitialAdjacentExactP0Appends.cancelAll()"))
        assertTrue(reader.contains("initialAdjacentWebtoonP0EventWakeLane.set(true)"))
        assertTrue(reader.contains("initialAdjacentWebtoonP0EventWakeLane.remove()"))
    }

    @Test
    fun activeScrollCannotCancelAnOwnedAdjacentSourceAfterItsLaunchTtlExpires() {
        val start = block("fun enqueueStartQuarantined()", strict)
        val finish = block("private fun maybeFinishClosedActor()", strict)
        val grant = block(
            "internal fun hasActiveAdjacentNtkForegroundViewerGrant(",
            imageCache,
        )
        val protected = block("private fun protectedNtkEpisodePaths(", imageCache)
        val allow = block("fun allowAdjacentNtkForegroundViewerPath(", imageCache)

        assertTrue(start.contains("if (adjacentPrefetch &&"))
        assertTrue(start.contains("retainActiveStrictAdjacentNtkEpisodePath("))
        assertTrue(start.contains("releaseActiveStrictAdjacentPath(\"start_failure\")"))
        assertTrue(finish.contains("releaseActiveStrictAdjacentPath(\"close_barrier\")"))
        assertTrue(
            finish.indexOf("NtkQuarantineSourceOwnershipRegistry.release(") <
                finish.indexOf("releaseActiveStrictAdjacentPath(\"close_barrier\")"),
        )
        assertTrue(grant.contains("activeStrictAdjacentNtkEpisodePaths.contains(key)"))
        assertTrue(protected.contains(
            "protected.addAll(activeStrictAdjacentNtkEpisodePaths.snapshot())",
        ))
        assertTrue(allow.contains(
            "if (activeStrictAdjacentNtkEpisodePaths.contains(key)) return@schedule",
        ))
    }

    @Test
    fun residentManhwaRunwayBodyWakesTheSameRaceSafeInitialAppendRegistry() {
        val bind = block("private fun ensureAdjacentStrictSourceClaim(", reader)
        val install = block("private fun acceptAdjacentStrictBodyDescriptor(", reader)
        val wake = block("private fun wakeInitialAdjacentManhwaRunwayAppend(", reader)
        val scope = block("private fun initialAdjacentExactP0WakePath(", reader)
        val schedule = block("private fun scheduleInitialAdjacentRunwayAppendRetry(", reader)

        assertTrue(bind.contains("acceptAdjacentStrictBodyDescriptor("))
        assertTrue(install.contains("wakeInitialAdjacentManhwaRunwayAppend("))
        assertTrue(wake.contains("path.startsWith(\"/manhwa/\")"))
        assertTrue(wake.contains("isDirectWifiStrictAdjacentRunwayProfile(episodePath = path)"))
        assertTrue(wake.contains("minOf(initialAdjacentRunwayPageLimit(path), pageCount)"))
        assertTrue(wake.contains("sourceIndex !in 0 until requiredRunwayPages"))
        assertTrue(wake.contains("adjacentStrictBodyDescriptorKey(path, manifestDigest, 0)"))
        assertFalse(wake.contains("(0 until requiredRunwayPages).all"))
        assertTrue(wake.contains("adjacentStrictBodyDescriptors.containsKey("))
        assertTrue(wake.contains("waitingInitialAdjacentManhwaRunwayAppends.wake("))
        assertTrue(wake.contains("initialAdjacentManhwaRunwayEventWakeKeys.add(wakeKey)"))
        assertTrue(scope.contains("path.startsWith(\"/manhwa/\")"))
        assertTrue(scope.contains("refs.firstOrNull { it.transitionTitle == null }?.sourceIndex == 0"))
        assertTrue(schedule.contains("wakeInitialAdjacentManhwaRunwayAppend("))
        assertTrue(schedule.contains("waitingInitialAdjacentManhwaRunwayAppends"))
        assertTrue(schedule.contains(
            "initialAdjacentManhwaRunwayEventWakeKeys.remove(exactP0WakeKey)"
        ))
        assertTrue(schedule.contains("if (directWebtoonEventWake || directManhwaEventWake)"))
        assertTrue(schedule.contains("appendWork.run()"))
        assertTrue(reader.contains(
            "postImmediate = { runnable -> initialAdjacentRunwayNetwork.execute(runnable) }"
        ))
        assertTrue(reader.contains("waitingInitialAdjacentManhwaRunwayAppends.cancelAll()"))
    }

    @Test
    fun exactP0WakeDuringTimeoutInstallationRunsOnceAndLeavesNoCallback() {
        val immediate = ArrayDeque<Runnable>()
        val delayed = ArrayDeque<Runnable>()
        lateinit var wakeRegistry: NtkAdjacentExactP0WakeRegistry
        var runs = 0
        wakeRegistry = NtkAdjacentExactP0WakeRegistry(
            postImmediate = immediate::addLast,
            postDelayed = { runnable, _ ->
                delayed.addLast(runnable)
                assertTrue(wakeRegistry.wake("/webtoon/next|digest-a"))
            },
            removeCallbacks = delayed::remove,
        )

        assertTrue(
            wakeRegistry.register(
                "/webtoon/next|digest-a",
                Runnable { runs++ },
                32L,
            ),
        )
        while (immediate.isNotEmpty()) immediate.removeFirst().run()
        while (delayed.isNotEmpty()) delayed.removeFirst().run()

        assertEquals(1, runs)
        assertEquals(0, wakeRegistry.sizeForTests())
    }

    @Test
    fun exactP0TimeoutAndLateEventStillRunExactlyOnce() {
        val immediate = ArrayDeque<Runnable>()
        val delayed = ArrayDeque<Runnable>()
        var runs = 0
        val wakeRegistry = NtkAdjacentExactP0WakeRegistry(
            postImmediate = immediate::addLast,
            postDelayed = { runnable, _ -> delayed.addLast(runnable) },
            removeCallbacks = delayed::remove,
        )

        assertTrue(
            wakeRegistry.register(
                "/webtoon/next|digest-a",
                Runnable { runs++ },
                32L,
            ),
        )
        delayed.removeFirst().run()
        assertFalse(wakeRegistry.wake("/webtoon/next|digest-a"))
        while (immediate.isNotEmpty()) immediate.removeFirst().run()

        assertEquals(1, runs)
        assertEquals(0, wakeRegistry.sizeForTests())
    }

    @Test
    fun exactP0CancelDuringTimeoutInstallationLeavesNothingRunnable() {
        val immediate = ArrayDeque<Runnable>()
        val delayed = ArrayDeque<Runnable>()
        lateinit var wakeRegistry: NtkAdjacentExactP0WakeRegistry
        var runs = 0
        wakeRegistry = NtkAdjacentExactP0WakeRegistry(
            postImmediate = immediate::addLast,
            postDelayed = { runnable, _ ->
                delayed.addLast(runnable)
                wakeRegistry.cancelAll()
            },
            removeCallbacks = delayed::remove,
        )

        assertTrue(
            wakeRegistry.register(
                "/webtoon/next|digest-a",
                Runnable { runs++ },
                32L,
            ),
        )
        while (delayed.isNotEmpty()) delayed.removeFirst().run()

        assertEquals(0, runs)
        assertEquals(0, wakeRegistry.sizeForTests())
        assertFalse(wakeRegistry.wake("/webtoon/next|digest-a"))
    }

    @Test
    fun exactP0WakeCannotCrossManifestDigest() {
        val immediate = ArrayDeque<Runnable>()
        val delayed = ArrayDeque<Runnable>()
        var runs = 0
        val wakeRegistry = NtkAdjacentExactP0WakeRegistry(
            postImmediate = immediate::addLast,
            postDelayed = { runnable, _ -> delayed.addLast(runnable) },
            removeCallbacks = delayed::remove,
        )

        assertTrue(
            wakeRegistry.register(
                "/webtoon/next|digest-new",
                Runnable { runs++ },
                32L,
            ),
        )
        assertFalse(wakeRegistry.wake("/webtoon/next|digest-old"))
        assertTrue(wakeRegistry.wake("/webtoon/next|digest-new"))
        while (immediate.isNotEmpty()) immediate.removeFirst().run()

        assertEquals(1, runs)
        assertEquals(0, wakeRegistry.sizeForTests())
    }

    @Test
    fun residentP0PreparationHandsOffToSparsePublisherAsSuccess() {
        val prepare = block("private fun prepareInitialTailAdjacentRunway(", reader)
        val sparseHandoff = prepare.indexOf(
            "append_adjacent_exact_p0_prefetch_deferred_to_sparse_head",
        )
        assertTrue(sparseHandoff >= 0)
        assertTrue(prepare.indexOf("strictBodiesReady", 0) in 0 until sparseHandoff)
        assertTrue(prepare.indexOf("isDirectWifiStrictAdjacentRunwayProfile(target)", 0) in 0 until sparseHandoff)
        assertTrue(prepare.indexOf("isNtkManhwaOrWebtoonEpisodePath(path)", 0) in 0 until sparseHandoff)
        assertTrue(prepare.indexOf("refs.singleOrNull()?.sourceIndex == 0", 0) in 0 until sparseHandoff)
        assertTrue(
            prepare.indexOf("if (strictExactManhwaLeaseRequired)", 0) > sparseHandoff,
        )

        val success = prepare.indexOf("return true", sparseHandoff)
        val failure = prepare.indexOf("return false", sparseHandoff)
        assertTrue(success > sparseHandoff)
        assertTrue(failure < 0 || success < failure)

        val consume = block("private fun prefetchResolvedMetadataAdjacent(", reader)
        val preparation = consume.indexOf("prepareInitialTailAdjacentRunway(candidate, publishUrls)")
        val publication = consume.indexOf("appendResolvedEpisode(candidate, publishUrls, direction)")
        assertTrue(preparation >= 0)
        assertTrue(publication > preparation)
    }

    @Test
    fun rollingLaunchEvictionClearsTheAuthoritativeStripSlotLedgerAtomically() {
        val clear = block("fun clearRollingAuthoritativePage(", surface)
        val retire = clear.indexOf("retireCurrentPageDrawableLocked(page)")
        val cycle = clear.indexOf("stripResidentCycles.remove(tileGeometry.key)")
        val coverage = clear.indexOf("stripResidentCoverage.remove(")
        val slots = clear.indexOf("page.stripSlots = List(pageGeometry.tiles.size) { null }")
        val drawables = clear.indexOf("page.tiles = emptyList()")

        assertTrue(clear.contains("page.stripAuthority == authority"))
        assertTrue(clear.contains("page.stripEpisode != geometry.episode.value"))
        assertTrue(clear.contains("page.stripAsset != pageGeometry.asset.canonicalAsset"))
        assertTrue(retire >= 0)
        assertTrue(cycle > retire)
        assertTrue(coverage > cycle)
        assertTrue(slots > coverage)
        assertTrue(drawables > slots)
        assertTrue(clear.contains("invalidateRetainedPageNodeStateLocked(index)"))
        assertFalse(clear.contains("Launch-strip and exact-p0 slot resources"))
    }

    @Test
    fun sparseP0PromotionWaitsForPointerQuietButBoundaryDemandCannotDeadlock() {
        val prepare = block("private fun prepareDirectWifiAdjacentExactP0Head(", reader)
        val admission = block(
            "private fun awaitDirectWifiAdjacentP0PromotionInputQuiet(",
            reader,
        )
        assertTrue(prepare.contains("awaitDirectWifiAdjacentP0PromotionInputQuiet(p0.manga)"))
        val promote = block("private fun promoteAdjacentExactTilesForDirectPresenter(", reader)
        assertTrue(
            promote.indexOf("awaitDirectWifiAdjacentP0PromotionInputQuiet(target)") in
                0 until promote.indexOf("HostExactHardwareTilePool.copyExactTileBitmap(software)"),
        )
        assertTrue(admission.contains("isPhysicalBoundaryDemandingAdjacentTarget(target)"))
        assertTrue(admission.contains("physicalTouchActive.get()"))
        assertTrue(admission.contains("NTK_DIRECT_ADJACENT_PIXEL_INPUT_QUIET_MS"))
        assertTrue(admission.contains("Thread.sleep(minOf(16L"))
    }

    @Test
    fun generationGateForwardsBothSparseP0Callbacks() {
        val head = block("override fun onAdjacentExactP0HeadReady(", listenerGate)
        val tail = block("override fun onAdjacentExactP0TailReady(", listenerGate)
        assertTrue(head.contains("active() && downstream.onAdjacentExactP0HeadReady(publication)"))
        assertTrue(tail.contains("active() && downstream.onAdjacentExactP0TailReady(delta)"))
    }

    @Test
    fun sparseP0ReestablishesDrawablePrefixBeforeGrowingTheSuccessorTable() {
        val callback = block("override fun onAdjacentExactP0HeadReady(", activity)
        val append = callback.indexOf("onPreparedAdjacentPagesAppended(publication.totalPageCount)")
        val guard = callback.indexOf("renderView.setLimitScrollToDrawablePrefix(true)")
        val appendPageCount = block("fun appendPageCount(\n", surface)

        assertTrue(guard >= 0)
        assertTrue(append > guard)
        assertTrue(appendPageCount.contains("val shouldExtendActiveFling = !limitScrollToDrawablePrefix"))
    }

    @Test
    fun entireForwardTailUsesProofAwareAtomicBatchesInsteadOfLegacyTileSetter() {
        val publish = block("private fun appendRemainingAdjacentRunwayRefs(", reader)
        val build = block("private fun directWifiAdjacentExactRunwayPublication(", reader)
        val commit = block("private fun commitAdjacentExactRunwayDrawableBatch(", reader)
        val gate = block("override fun onAdjacentExactRunwayBatchReady(", listenerGate)
        val install = block("fun installAdjacentExactRunwayBatch(", surface)
        val callback = block("override fun onAdjacentExactRunwayBatchReady(", activity)
        assertTrue(publish.contains("listener.onAdjacentExactRunwayBatchReady("))
        assertTrue(publish.contains("commitAdjacentExactRunwayDrawableBatch(drawableBatch)"))
        assertTrue(publish.contains("requiresStrictExactRemainingAdjacentRunway(candidateSnapshot)"))
        assertTrue(publish.contains("requireStrictDescriptor = strictExactDescriptorOnly"))
        assertTrue(publish.contains("nativeExactBatchRequired && exactRunwayPublication == null"))
        assertTrue(publish.contains("append_adjacent_exact_runway_invalid_publication"))
        assertTrue(build.contains("result.originalProof ?: return null"))
        assertTrue(build.contains("page.sourceIndex < 1"))
        assertTrue(build.contains("!path.startsWith(\"/webtoon/\")"))
        assertTrue(build.contains("!path.startsWith(\"/manhwa/\")"))
        val runwayContract = block("data class NtkAdjacentExactRunwayTilePage(", adjacentContracts)
        assertTrue(runwayContract.contains("startsWith(\"/webtoon/\")"))
        assertTrue(runwayContract.contains("startsWith(\"/manhwa/\")"))
        val proofValidation = build.indexOf("ReaderPreparedStore.isCanonicalOriginalProof(")
        val publicationConstruction = build.indexOf("NtkAdjacentExactRunwayTilePage(")
        assertTrue(proofValidation >= 0)
        assertTrue(publicationConstruction > proofValidation)
        assertFalse(build.contains("page.sourceIndex !in 1..3"))
        assertTrue(gate.contains("downstream.onAdjacentExactRunwayBatchReady(publication)"))
        assertTrue(gate.contains("replaceWithCurrentAuthoritative"))
        assertTrue(callback.contains("installAdjacentExactRunwayBatch(publication)"))
        assertTrue(callback.contains("onPreparedAdjacentPagesAppended(publication.totalPageCount)"))
        assertTrue(callback.contains("rollbackAdjacentExactAppendedTail("))
        assertTrue(install.contains("identity.normalizedEpisodePath == command.normalizedEpisodePath"))
        assertTrue(install.contains("page.stripAuthority == 0L"))
        assertTrue(install.contains("usableAuthoritativeOriginalTilePage("))
        assertFalse(install.contains("stripAuthorityToken != 0L"))
        assertFalse(commit.contains("listener.onPageTilesReady"))
    }

    @Test
    fun pixelRetirementCannotReleaseDeferredDocumentGeometry() {
        val pixelClear = block("private fun clearPendingResolveLocked(", surface)
        val geometryClear = block("private fun clearDeferredLayoutGeometryLocked(", surface)
        val retirement = block("private fun clearPageBitmap(", surface)
        val idleResolve = block("private fun applyPendingPageResolvesLocked(", surface)

        assertFalse(pixelClear.contains("frozenLayoutRatio"))
        assertFalse(pixelClear.contains("deferredLayoutGeometryPages.remove"))
        assertTrue(geometryClear.contains("page.frozenLayoutRatio = Float.NaN"))
        assertTrue(geometryClear.contains("deferredLayoutGeometryPages.remove(page)"))
        assertTrue(retirement.contains("clearPendingResolveLocked(page)"))
        assertFalse(retirement.contains("clearDeferredLayoutGeometryLocked(page)"))
        assertTrue(idleResolve.contains("clearDeferredLayoutGeometryLocked(page)"))
        assertTrue(
            idleResolve.indexOf("clearDeferredLayoutGeometryLocked(page)") <
                idleResolve.indexOf("restoreViewportAnchorLocked(viewportAnchor")
        )
    }

    @Test
    fun everyReplicaSuccessRetainsStallAwareBodyRecovery() {
        val factory = block("private class NtkReplicaFailoverCallFactory(", imageCache)
        val replicaCall = block("private class NtkReplicaFailoverCall(", imageCache)

        assertTrue(factory.contains("NtkReplicaFailoverCall(delegate, request)"))
        assertTrue(factory.contains("strictOwnedKnownReplica"))
        assertTrue(factory.contains("NtkQuarantineSourceCallIdentity::class.java"))
        assertFalse(imageCache.contains("NtkDirectWifiManhwaSuccessCall"))
        assertTrue(imageCache.contains("identitySafeRangeReplicaCount >= 2"))
        assertTrue(replicaCall.contains("maybeWrapStalledReplicaBody("))
        assertTrue(replicaCall.contains("NtkStalledReplicaResponseBody("))
        assertTrue(replicaCall.contains("strictOwnedManhwaRangeCandidates("))
        assertTrue(replicaCall.contains("val strictOwned ="))
        assertTrue(replicaCall.contains("NTK_MANHWA_RANGE_REPLICA_HOSTS.forEach"))
        assertTrue(replicaCall.contains("exact total, strong validator, identity encoding"))
    }

    private fun source(name: String): String = File(
        "src/main/java/ml/melun/mangaview/reader/$name",
    ).readText()

    private fun block(signature: String, source: String): String {
        return SourceFunctionBody.extract(source, signature)
    }
}
