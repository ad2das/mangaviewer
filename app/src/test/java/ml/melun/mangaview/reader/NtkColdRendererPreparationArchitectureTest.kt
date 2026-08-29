package ml.melun.mangaview.reader

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkColdRendererPreparationArchitectureTest {
    private val source = File(
        "src/main/java/ml/melun/mangaview/reader/ReaderSurfaceView.kt"
    ).readText()
    private val activitySource = File(
        "src/main/java/ml/melun/mangaview/activity/ReaderV2Activity.kt"
    ).readText()
    private val telemetrySource = File(
        "src/main/java/ml/melun/mangaview/runtime/ViewerTelemetry.java"
    ).readText()
    private val registrySource = File(
        "src/main/java/ml/melun/mangaview/reader/NtkSourceSpoolRegistry.kt"
    ).readText()

    @Test
    fun committedPixelIdentityProofExcludesStructuralTransitionCards() {
        val rollingProof = functionBody("internal fun packNtkVisibleFrameProof(")
        assertTrue(rollingProof.contains("item.cardText == null"))
        assertTrue(rollingProof.contains("val itemTop = item.top - viewportTopOffset"))
        assertTrue(rollingProof.contains("itemTop < viewportBottom"))
        assertTrue(rollingProof.contains("itemTop + item.pageHeight > 0f"))
    }

    @Test
    fun nativePhysicalMoveReusesAnImmutableLogicalBandAndPublishesOnlyTranslation() {
        val state = functionBody("private fun buildDrawStateLocked(")
        val submit = functionBody("private fun submitNativeFrame(")
        val translation = functionBody("private fun nativeProducerSceneTranslationForState(")

        assertTrue(state.contains("retainNativeItemsForViewport"))
        assertTrue(state.contains("nativeBandItems"))
        assertTrue(state.contains("viewportItemTopOffset = if (retainNativeItemsForViewport)"))
        assertTrue(state.contains("bandOrigin = nativeBandOrigin"))
        assertTrue(state.contains("bandBottom = nativeBandOrigin + nativeBandSpan"))
        assertFalse(state.contains("bandBottom = if (retainNativeItemsForViewport)"))
        assertTrue(submit.contains("val nativeSceneItemTopOffset = if (state.retainedNativeBandHeight > 0f)"))
        assertTrue(submit.contains("state.scrollOffset - state.nativeBandOrigin"))
        assertFalse(submit.contains("state.retainedNativeBandHeight <= 0f"))
        assertTrue(translation.contains("pendingNativeProducerSceneTranslationForState("))
        assertTrue(translation.contains("nativeProducerSceneBandItems === nativeItems"))
        assertTrue(translation.contains("nativeProducerSceneReuseMinOffset"))
        assertTrue(translation.contains(
            "val itemTop = item.top - viewportItemTopOffset",
        ))
    }

    @Test
    fun geometryCropRetainsNativePulseEvidenceInsteadOfMergedJavaTransactions() {
        val compare = functionBody("private fun activeNativeBandSourceTopForState(")
        val activate = functionBody("fun onNtkRollingBandActivated(")
        val submit = functionBody("private fun submitNativeFrame(")
        val geometryRequest = functionBody("fun onNtkRollingGeometryFrameRequested(")
        val nativeEvents = functionBody("bool consumeEvents(", rollingRendererSource)

        assertTrue(compare.contains("sameActiveBandItemPixels(applied, current)"))
        assertTrue(compare.contains("activeVisibleCount != currentVisibleCount"))
        assertTrue(compare.contains("state.scrollOffset - active.bandOrigin"))
        assertTrue(activate.contains("activeNativeBandCandidates.remove(producerSceneId)"))
        assertTrue(activate.contains("candidate.attachEpoch != rollingNativeAttachEpoch"))
        assertTrue(submit.contains(
            "val directActiveBandGeometry: FrameSyncedGeometryRequest? = null",
        ))
        assertTrue(source.contains("nativeSubmitProducerGeometry("))
        assertTrue(rollingRendererSource.contains("javaFrameSyncedGeometry_ = false"))
        assertTrue(rollingRendererSource.contains("applyGeometryTransactionDirect("))
        assertTrue(geometryRequest.contains("directActiveBandNativeFollowupTokens.remove(token)"))
        assertTrue(rollingRendererSource.contains("command.producerSceneId = producerSceneId"))
        assertTrue(nativeEvents.contains("callbackBandActivated(env, event)"))
        assertTrue(
            nativeEvents.indexOf("callbackBandActivated(env, event)") <
                nativeEvents.indexOf("callbackLatched(env, event)",
                    nativeEvents.indexOf("TRANSACTION_COMPLETED")),
        )
    }
    private val sessionSource = File(
        "src/main/java/ml/melun/mangaview/reader/ReaderSession.kt"
    ).readText()
    private val rollingRendererSource = File(
        "src/main/cpp/ntk_rolling_surface_renderer.cpp"
    ).readText()
    private val surfaceControlBackendSource = File(
        "src/main/cpp/present/SurfaceControlPresentBackend.cpp"
    ).readText()
    private val surfaceControlBackendHeader = File(
        "src/main/cpp/present/SurfaceControlPresentBackend.h"
    ).readText()
    private val strictOwnershipSource = File(
        "src/main/java/ml/melun/mangaview/reader/NtkStrictSourceOwnershipRegistry.kt"
    ).readText()
    private val imageCacheSource = File(
        "src/main/java/ml/melun/mangaview/reader/ReaderImageCache.kt"
    ).readText()
    private val quicFetcherSource = File(
        "src/main/java/ml/melun/mangaview/activity/NtkQuicFetcher.java"
    ).readText()
    private val clickOwnedQuarantineSource = File(
        "src/main/java/ml/melun/mangaview/reader/NtkClickOwnedAnchorQuarantine.kt"
    ).readText()
    private val strictDiscoveryCoordinatorSource = File(
        "src/main/java/ml/melun/mangaview/reader/NtkStrictEpisodeDiscoveryCoordinator.kt"
    ).readText()
    private val manifestEvidenceParserSource = File(
        "src/main/java/ml/melun/mangaview/reader/NtkManifestEvidenceParser.kt"
    ).readText()
    private val macrobenchmarkSource = File(
        "../macrobenchmark/src/main/java/ml/melun/mangaview/macrobenchmark/NtkColdViewerMacrobenchmark.kt"
    ).readText()
    private val macrobenchmarkResumePlanSource = File(
        "../macrobenchmark/src/main/java/ml/melun/mangaview/macrobenchmark/ResumeTraversalPlan.kt"
    ).readText()
    private val qualificationSource = File(
        "../tools/ntk_cold_qualification.ps1"
    ).readText()

    @Test
    fun appendOnlyPublicationSynchronizesOnlyNewIdentitySuffix() {
        val appended = functionBody("override fun onPagesAppended(", activitySource)

        assertTrue(appended.contains("syncRenderPageIdentities(oldCount, count - oldCount)"))
        assertTrue(appended.contains("syncRenderPageIdentities(\n                    previousPageCount,"))
        assertFalse(appended.contains("syncRenderPageIdentities(count)"))
        assertFalse(appended.contains("syncRenderPageIdentities(publishCount)"))
    }

    @Test
    fun nativeCadenceBaselineCannotBeRegressedByALatePreviousGestureProof() {
        assertTrue(telemetrySource.contains(
            "actualFrameNanos < nativePhysicalGestureStartedNanos",
        ))
        val monotonicGuard = telemetrySource.indexOf(
            "if(previous > 0L && actualFrameNanos <= previous)",
        )
        val baselineWrite = telemetrySource.indexOf(
            "lastNativeScrollPresentationNanos = actualFrameNanos;",
            monotonicGuard,
        )
        assertTrue(monotonicGuard >= 0)
        assertTrue(baselineWrite > monotonicGuard)
        assertTrue(telemetrySource.contains(
            "nativePhysicalGestureStartedNanos = SystemClock.elapsedRealtimeNanos();",
        ))
    }

    @Test
    fun strictViewerKeepsLoadingChromeUntilAnIdentityValidPhysicalCommit() {
        val pagesReady = functionBody("override fun onPagesReady(", activitySource)
        val completedDraw = functionBody(
            "private fun handleAcceptedStrictRollingCompletedDraw(",
            activitySource,
        )
        val hideStatus = functionBody("private fun hideBoundaryStatus()", activitySource)

        assertTrue(pagesReady.contains("shouldHoldInitialStatusUntilPhysicalCommit()"))
        assertTrue(pagesReady.contains("showInitialPhysicalLoadingStatus()"))
        assertTrue(completedDraw.contains("strictTelemetryActualInLifecycle = true"))
        assertTrue(completedDraw.contains("finishInitialPhysicalLoadingStatus()"))
        assertTrue(hideStatus.contains("shouldHoldInitialStatusUntilPhysicalCommit()"))
        val finishStatus = functionBody(
            "private fun finishInitialPhysicalLoadingStatus()",
            activitySource,
        )
        assertTrue(finishStatus.contains("isNativeSurfaceRevealPendingForLoadingStatus()"))
        assertTrue(finishStatus.contains("finishInitialPhysicalLoadingStatusRunnable"))
        val showStatus = functionBody(
            "private fun showInitialPhysicalLoadingStatus()",
            activitySource,
        )
        val loadingWindow = functionBody(
            "private fun attachInitialPhysicalLoadingWindow()",
            activitySource,
        )
        val pause = functionBody("override fun onPause()", activitySource)
        assertTrue(showStatus.contains("showInitialPhysicalLoadingWindowRunnable"))
        assertTrue(loadingWindow.contains("TYPE_APPLICATION_PANEL"))
        assertTrue(loadingWindow.contains("FLAG_NOT_TOUCHABLE"))
        assertTrue(pause.contains("hideInitialPhysicalLoadingWindow()"))
    }

    @Test
    fun ordinaryViewerKeepsLoadingChromeUntilTheVisibleDrawableIsInstalled() {
        val onCreate = functionBody("override fun onCreate(", activitySource)
        val pagesReady = functionBody("override fun onPagesReady(", activitySource)
        val bitmap = functionBody("private fun applyPageBitmap(", activitySource)
        val tiles = functionBody("private fun applyPageTiles(", activitySource)
        val hideStatus = functionBody("private fun hideBoundaryStatus()", activitySource)

        assertTrue(onCreate.contains("initial_draw_gate_skip reason=explicit_loading_chrome"))
        assertFalse(onCreate.contains("installInitialDrawGate(root"))
        assertTrue(pagesReady.contains("initialStatusPending && !initialVisibleDrawableApplied"))
        assertTrue(pagesReady.contains("showInitialDrawableLoadingStatus()"))
        assertTrue(bitmap.contains("if (visibleInitialDrawable) finishInitialDrawableLoadingStatus()"))
        assertTrue(tiles.contains("if (visibleInitialDrawable) finishInitialDrawableLoadingStatus()"))
        assertTrue(hideStatus.contains("initialStatusPending && !initialVisibleDrawableApplied"))
        val ordinaryStatus = functionBody(
            "private fun showInitialDrawableLoadingStatus()",
            activitySource,
        )
        assertTrue(ordinaryStatus.contains("hideInitialPhysicalLoadingWindow()"))
        assertFalse(ordinaryStatus.contains("showInitialPhysicalLoadingWindowRunnable"))
    }

    @Test
    fun forwardResumeSuffixCannotClaimTheImmutableFullScene() {
        val installed = functionBody(
            "override fun areAllAuthoritativeDrawablesInstalled(",
            activitySource,
        )

        assertTrue(installed.contains("historicallyComplete &&"))
        assertTrue(installed.contains("renderView.hasCompleteAuthoritativeOriginalScene(pageCount)"))
        assertFalse(installed.contains("strictForwardReadyFirstPage > 0"))
        assertFalse(installed.contains("strictRollingHistoricalScene"))
    }

    @Test
    fun forwardResumeSuffixUsesAnIdentityBoundO1FastPath() {
        val strictWindow = functionBody(
            "private fun requestStrictExactColdWindow(",
            sessionSource,
        )
        val suffixProof = functionBody(
            "override fun currentStrictForwardSuffixProofRevision(",
            activitySource,
        )
        val pageCleared = functionBody("override fun onPageCleared(", activitySource)
        val publishReady = functionBody(
            "private fun queueStrictAllImagesRenderReady(",
            activitySource,
        )
        val prepended = functionBody("override fun onPagesPrepended(", activitySource)
        val removed = functionBody("override fun onPagesRemoved(", activitySource)
        val shiftForPrepend = functionBody(
            "private fun shiftPageStateForPrepend(",
            sessionSource,
        )
        val removeState = functionBody("private fun removePageStateRange(", sessionSource)
        val staleLoadingClear = functionBody(
            "private fun clearInitialStaleLoadingBeforePromotedStart(",
            sessionSource,
        )
        val sourceChanged = strictWindow.indexOf(
            "val sourceDemandChanged = admissionChanged && !previous.hasSameSourceDemand(admission)",
        )
        val suffixCall = strictWindow.indexOf(
            "tryCommitStrictForwardSuffixWindow(",
            sourceChanged,
        )
        val protectedScan = strictWindow.indexOf(
            "protectedNumericBitmapWindowBoundsForSession(",
            suffixCall,
        )
        val fullScene = strictWindow.indexOf(
            "listener.areAllAuthoritativeDrawablesInstalled(pageCount)",
            protectedScan,
        )
        val finishDemand = strictWindow.indexOf(
            "finishStrictExactColdWindowDemand(",
            fullScene,
        )

        assertTrue(sourceChanged >= 0 && suffixCall > sourceChanged)
        assertTrue(
            protectedScan > suffixCall && fullScene > protectedScan &&
                finishDemand > fullScene,
        )
        assertFalse(strictWindow.substring(fullScene, finishDemand).contains("pages.indices"))
        val suffixBranch = functionBody(
            "private fun tryCommitStrictForwardSuffixWindow(",
            sessionSource,
        )
        val suffixStart = suffixBranch.indexOf("val activeFloorBeforeSuffixProof")
        val suffix = suffixBranch.indexOf(
            "NtkStrictForwardSuffixFastPathPolicy.canCommit(",
            suffixStart,
        )
        assertTrue(suffixStart >= 0 && suffix > suffixStart)
        assertTrue(suffixBranch.contains("sourceDemandChanged = sourceDemandChanged"))
        assertTrue(suffixBranch.contains("activeFloorBeforeSuffixProof"))
        assertTrue(suffixBranch.contains("activeFloorAfterSuffixProof"))
        assertTrue(suffixBranch.contains("launchShapeBeforeSuffixProof"))
        assertTrue(suffixBranch.contains("launchShapeAfterSuffixProof"))
        assertTrue(suffixBranch.contains("currentSuffixProofRevision()"))
        assertTrue(suffixBranch.contains("isStrictForwardSuffixCommitProofCurrent()"))
        assertTrue(suffixBranch.contains("isCommitProofCurrent("))
        assertTrue(suffixBranch.contains("pages.size != pageCount"))
        assertTrue(suffixBranch.contains("indexedStateGeneration.get()"))
        assertFalse(suffixBranch.contains("physicalDeliveryFirstPage = 0"))
        assertFalse(suffixBranch.contains("applyStrictExactSourceDemand("))
        assertTrue(suffixBranch.contains("retainedAnchorPage ="))
        assertTrue(suffixBranch.contains("publishProtectedBitmapWindowSnapshot("))
        val committedFailure = suffixBranch.indexOf("if (!committed)")
        val finalRedriveAck = suffixBranch.lastIndexOf(
            "acknowledgeRetainedWindowRedrive(retainedRedriveRevisionAtStart)"
        )
        assertTrue(finalRedriveAck >= 0 && finalRedriveAck < committedFailure)
        assertFalse(
            suffixBranch.substring(committedFailure).contains(
                "acknowledgeRetainedWindowRedrive(retainedRedriveRevisionAtStart)"
            )
        )

        assertTrue(suffixProof.contains("strictReaderSessionGeneration == activeGeneration"))
        assertTrue(suffixProof.contains("seal.discoveryGeneration == discoveryGeneration"))
        assertTrue(suffixProof.contains("proof.firstSource == firstSource"))
        assertTrue(suffixProof.contains("proof.revision"))
        assertFalse(suffixProof.contains("for ("))
        assertFalse(suffixProof.contains("renderView."))
        assertTrue(
            pageCleared.indexOf("strictForwardSuffixReadyProof = null") <
                pageCleared.indexOf("renderView.clearPageBitmap(index)"),
        )
        assertTrue(pageCleared.contains("preservePublishedRollingCompletion"))
        assertTrue(
            pageCleared.contains(
                "strictRollingHistoricalScene && strictAllImagesReadyPublished"
            )
        )
        assertTrue(publishReady.contains("hasCanonicalStrictLaunchDisplayCardinality("))
        assertTrue(publishReady.contains("!strictForwardSuffixFastPathDisabled"))
        assertTrue(prepended.contains("strictForwardSuffixFastPathDisabled = true"))
        assertTrue(removed.contains("strictForwardSuffixFastPathDisabled = true"))
        assertTrue(shiftForPrepend.contains("strictForwardSuffixLaunchShapeValid.set(false)"))
        assertTrue(removeState.contains("strictForwardSuffixLaunchShapeValid.set(false)"))
        assertTrue(pageCleared.contains("index in strictForwardReadyFirstPage until seal.pageCount"))
        assertTrue(
            staleLoadingClear.indexOf("listener.onPageCleared(resolvedIndex)") <
                staleLoadingClear.indexOf("requestRetainedWindowAfterStructureChange()")
        )
    }

    @Test
    fun promotedStartStaleLoadingClearIsBoundToTheExactPageRefAndStructureEpoch() {
        val clear = functionBody(
            "private fun clearInitialStaleLoadingBeforePromotedStart(",
            sessionSource,
        )
        val capture = clear.indexOf(
            "val stalePage = synchronized(pagesLock) { pages.getOrNull(index) }",
        )
        val transaction = clear.indexOf("synchronized(pagesLock)", capture + 1)
        val structureFence = clear.indexOf("isStructurePublishPending()", transaction)
        val resolve = clear.indexOf("pageIndexLocked(stalePage, index)", structureFence)
        val surfaceClear = clear.indexOf("listener.onPageCleared(resolvedIndex)", resolve)
        val transactionEnd = clear.indexOf("if (deferredForStructure)", surfaceClear)

        assertTrue(capture >= 0)
        assertTrue(transaction > capture)
        assertTrue(structureFence > transaction)
        assertTrue(resolve > structureFence)
        assertTrue(surfaceClear > resolve)
        assertTrue(transactionEnd > surfaceClear)
        assertTrue(clear.contains("dispatchWhenStructureStable(this)"))
        assertFalse(clear.contains("listener.onPageCleared(index)"))
    }

    @Test
    fun appendOnlyPageGrowthKeepsTheAlreadyPresentedPrefixResident() {
        val append = functionBody("fun appendPageCount(", source)
        assertTrue(append.contains("appendEmptyPagesLocked"))
        assertTrue(append.contains("extendTraversalProofLocked"))
        assertFalse(append.contains("invalidatePreparedRenderSceneStateLocked()"))
        assertFalse(append.contains("clearRetainedPageNodesStateLocked()"))
    }

    @Test
    fun ordinaryPagedMangaDoesNotEnterTheNativeSurfaceProducer() {
        val profile = functionBody("fun setSourceNativeWebtoonCompositingEnabled(", source)
        assertTrue(profile.contains("if (!enabled) rollingNativePresentationEnabled = false"))
        assertTrue(activitySource.contains("setSourceNativeWebtoonCompositingEnabled("))
    }

    @Test
    fun prependedEpisodeWarmsItsAbsoluteFirstImageForReverseScrolling() {
        val warmPrepend = functionBody("private fun warmPrependedEpisode(", sessionSource)
        val readyCount = functionBody("private fun prependedHoldUntilReadyCount(", sessionSource)
        val prependSurface = functionBody("fun prependPageCount(", source)
        val boundary = functionBody("private fun boundaryRequestLocked(", source)
        assertTrue(warmPrepend.contains("if (firstImageIndex >= 0)"))
        assertTrue(warmPrepend.contains("val firstDecoded = max(0, inserted - decodeAhead)"))
        assertTrue(warmPrepend.contains("val firstByte = max(0, inserted - byteAhead)"))
        assertFalse(warmPrepend.contains("max(1, inserted - decodeAhead)"))
        assertTrue(readyCount.contains("if (!isNtkSource(target, title)) return 1"))
        assertTrue(prependSurface.contains("if (holdUntilReadyCount > 0)"))
        assertTrue(prependSurface.contains("prependedRevealHoldPage = (insertedCount - 1)"))
        assertTrue(boundary.contains("!pageHasCompleteDrawableContentLocked(0)"))
        assertTrue(boundary.contains("reader_boundary_previous_blocked_unresolved_head"))
    }

    @Test
    fun macroSeparatesFourSecondFirstImageFromEightSecondFullCompletionGoal() {
        assertTrue(
            macrobenchmarkSource.contains(
                "WEBTOON_FIRST_IMAGE_SLA_MS = 4_000L"
            )
        )
        assertTrue(
            macrobenchmarkSource.contains(
                "MANHWA_FIRST_IMAGE_SLA_MS = 4_000L"
            )
        )
        assertTrue(macrobenchmarkSource.contains("ALL_IMAGES_SLA_MS = 8_000L"))
        assertTrue(
            macrobenchmarkSource.contains(
                "?: ALL_IMAGES_SLA_MS"
            )
        )
        assertFalse(macrobenchmarkSource.contains("ALL_IMAGES_SLA_MS = 4_000L"))
    }

    @Test
    fun timedOutExactQuicPrefixCannotOverlapItsRangeContinuation() {
        assertTrue(quicFetcherSource.contains("boolean terminalAfterCancel = done.await"))
        assertTrue(quicFetcherSource.contains("if(!terminalAfterCancel)"))
        assertTrue(quicFetcherSource.contains("if(!exactIdentityRequest)"))
        assertTrue(quicFetcherSource.contains("state.responseSnapshot()"))
        assertTrue(
            quicFetcherSource.contains(
                "completed = done.await(boundedTimeoutMs"
            )
        )
        assertFalse(quicFetcherSource.contains("done.await(\n" +
            "                                    EXACT_IDENTITY_TAIL_PROGRESS_GRACE_MS"))
        assertTrue(
            imageCacheSource.contains(
                "NtkExactQuicPartialResumePolicy.completeBodyLength("
            )
        )
        assertTrue(imageCacheSource.contains("isCompleteImageBytes(request.url.toString()"))
        assertTrue(quicFetcherSource.contains("state.terminalKind"))
        assertTrue(quicFetcherSource.contains("CANCELED_BY_INTERNAL_TIMEOUT"))
        assertTrue(
            imageCacheSource.contains(
                "NtkExactQuicPartialResumePolicy.IDENTITY_REQUEST_MARKER"
            )
        )
        assertTrue(
            imageCacheSource.contains(
                "requiresDirectWifiContinuation"
            )
        )
        assertTrue(
            imageCacheSource.contains(
                "return maybeWrapStalledReplicaBody("
            )
        )
    }

    @Test
    fun directStrictColdSessionEnablesGpuRunwayWithoutChangingPixelProofMode() {
        assertTrue(
            activitySource.contains("it.setSurfaceAttachmentDeferredUntilActualPixels(strictNtkEpisode)")
        )
        assertTrue(
            activitySource.contains("it.setForwardNativeTexturePrewarmEnabled(")
        )
        assertFalse(activitySource.contains("it.setInlineRealPixelsOnly(strictNtkEpisode)"))
    }

    @Test
    fun expandedWifiRunwayUsesTheExactSourceConstructionCapability() {
        val rememberFloor = functionBody(
            "private fun rememberStrictForwardReadyFloor(",
            activitySource,
        )
        val profileGetter = functionBody(
            "fun isCurrentDirectWifiRendererProfile(",
            registrySource,
        )

        assertTrue(registrySource.contains("entry.directWifiRendererProfileViewerGeneration ="))
        assertTrue(registrySource.contains("spec.rollingAdmission && directWifiTransport &&"))
        assertTrue(registrySource.contains("!cellularResilientTransport && it > 0L"))
        assertTrue(registrySource.contains("spec.forwardResumeViewerGeneration == it"))
        assertTrue(rememberFloor.contains("isCurrentDirectWifiRendererProfile("))
        assertTrue(
            rememberFloor.contains(
                "renderView.setLimitScrollToDrawablePrefix(directWifiRendererProfile)"
            )
        )
        assertTrue(rememberFloor.contains("expandedMinimumPage = strictForwardReadyFirstPage"))
        assertFalse(rememberFloor.contains("getHttpClient()"))
        assertTrue(profileGetter.contains("viewerGeneration > 0L"))
        assertTrue(
            profileGetter.contains(
                "directWifiRendererProfileViewerGeneration == viewerGeneration"
            )
        )
    }

    @Test
    fun directWifiExactAdjacentSkipsOnlyTheSpeculativeHwuiUploadDuringInput() {
        val prepare = functionBody(
            "private fun shouldPrepareAdjacentRunwayDecodeResultForDraw(",
            sessionSource,
        )
        val delivery = functionBody(
            "private fun prepareAdjacentRunwayDelivery(",
            sessionSource,
        )

        assertTrue(prepare.contains("!strictExactAdjacent"))
        assertTrue(prepare.contains("!isDirectWifiStrictAdjacentRunwayProfile(page.manga)"))
        assertTrue(prepare.contains("!viewportBusy.get()"))
        assertTrue(prepare.contains("!isActiveGeneratedTouchOrQuiet()"))
        assertTrue(delivery.contains("strictDescriptor != null || strictBody != null"))
        assertTrue(delivery.contains("prepareDecodeResultForDraw(decoded)"))
        assertTrue(delivery.contains("decoded"))
        assertFalse(delivery.contains("}.also(::prepareDecodeResultForDraw)"))
    }

    @Test
    fun exactAdjacentRunwayUsesTheClaimFrozenTransportProfile() {
        val transportSource = File(
            "src/main/java/ml/melun/mangaview/reader/NtkStrictSourceTransport.kt"
        ).readText()
        val strictSessionSource = File(
            "src/main/java/ml/melun/mangaview/reader/NtkStrictSourceSession.kt"
        ).readText()
        val cacheTransportSource = File(
            "src/main/java/ml/melun/mangaview/reader/NtkCacheSourceTransport.kt"
        ).readText()
        val profile = functionBody(
            "private fun isDirectWifiStrictAdjacentRunwayProfile(",
            sessionSource,
        )
        val tailSnapshot = functionBody(
            "private fun directWifiAdjacentInitialRunwayTailSnapshot(",
            sessionSource,
        )

        assertTrue(transportSource.contains("val directWifiAdjacentRunwayProfile: Boolean"))
        assertTrue(strictSessionSource.contains("get() = directWifiTransport &&"))
        assertTrue(strictSessionSource.contains("!cellularResilientTransport"))
        assertTrue(cacheTransportSource.contains("strictSession.directWifiAdjacentRunwayProfile"))
        assertTrue(registrySource.contains("transport.directWifiAdjacentRunwayProfile"))
        assertTrue(profile.contains("adjacentStrictSourceClaims[path]"))
        assertTrue(profile.contains("claim?.directWifiRunwayProfile == true"))
        assertTrue(sessionSource.contains("val directWifiRunwayProfile: Boolean"))
        assertTrue(sessionSource.contains("directWifiRunwayProfile ="))
        assertTrue(sessionSource.contains("transport.directWifiAdjacentRunwayProfile ||"))
        assertTrue(profile.contains("isDirectWifiAdjacentRouteProfile("))
        assertTrue(profile.contains("claim?.episode?.value ?: 0L"))
        assertTrue(profile.contains("return isDirectWifiStrictAdjacentTransportActive()"))
        assertTrue(strictDiscoveryCoordinatorSource.contains("flight.routeSnapshot.directWifiTransport"))
        assertTrue(strictDiscoveryCoordinatorSource.contains("!flight.routeSnapshot.cellularResilientTransport"))
        assertTrue(tailSnapshot.contains("isDirectWifiStrictAdjacentRunwayProfile(target, path)"))
    }

    @Test
    fun strictSurfaceCannotPublishBeforeItsIdentityProvidingSessionIsBound() {
        val start = functionBody(
            "private fun startReaderSession(",
            activitySource
        )
        val prestartedBinding = start.indexOf("session = prestartedStrictSession")
        val regularBinding = start.indexOf("session = ReaderSession(")
        val producerActivation = start.indexOf("renderView.activateDeferredSurfaceProducer()")

        assertTrue(prestartedBinding >= 0)
        assertTrue(regularBinding >= 0)
        assertTrue(producerActivation > prestartedBinding)
        assertTrue(producerActivation > regularBinding)
    }

    @Test
    fun strictRendererPreparationStartsAfterIdentityButAttachmentWaitsForActualHwuiCommit() {
        val attached = functionBody("override fun onAttachedToWindow()")
        val activation = functionBody("fun activateDeferredSurfaceProducer()")
        val commit = functionBody("private fun onFrameCommitted(")
        val dispatch = functionBody("private fun drainCompletedDrawDispatch()")
        val reveal = functionBody("private fun revealNativeSurfaceAfterFirstHwuiCommit(")

        assertFalse(activitySource.contains("prepareDeferredSurfaceProducerAfterRootFrame"))
        assertFalse(attached.contains("prepareRollingNativeRendererLocked()"))
        assertFalse(activation.contains("prepareRollingNativeRendererLocked()"))
        assertFalse(activation.contains("nativeSurfaceView.visibility = View.VISIBLE"))
        assertTrue(commit.contains("cleanCommittedHwuiActualPixels"))
        assertTrue(commit.contains("completedDrawDispatchQueue.offer("))
        assertTrue(dispatch.contains("revealNativeSurfaceAfterFirstHwuiCommit("))
        assertTrue(reveal.contains("prepareRollingNativeRendererLocked()"))
    }

    @Test
    fun physicalAdjacentCommitDoesNotRaceTheDelayedToolbarEpisodeLabel() {
        val completedDraw = functionBody(
            "private fun handleStrictRollingCompletedDraw(",
            activitySource
        )
        val acceptedDraw = functionBody(
            "private fun handleAcceptedStrictRollingCompletedDraw(",
            activitySource,
        )

        assertTrue(completedDraw.contains("NtkVisibleIdentityPolicy.isValidCommitted("))
        assertTrue(completedDraw.contains("val telemetryEpisodeOwned ="))
        assertTrue(completedDraw.contains("episodeMatches = telemetryEpisodeOwned"))
        val telemetryOwnership = completedDraw.substring(
            completedDraw.indexOf("val telemetryEpisodeOwned ="),
            completedDraw.indexOf("val commitValidWithoutViewport"),
        )
        assertFalse(telemetryOwnership.contains("currentManga?.ntkEpisodePath"))
        assertTrue(acceptedDraw.contains("NtkPhysicalAdjacentMetadataAdoptionPolicy"))
    }

    @Test
    fun validatedDirectManifestAckClearDoesNotRescanUrlsOrTheMainMessageQueue() {
        val clear = functionBody(
            "private fun clearDeferredNtkAckPreflightAfterValidatedDirectManifest(",
            activitySource
        )

        assertTrue(clear.contains("deferredNtkAckPreflightManga = null"))
        assertFalse(clear.contains("hasCompleteNativeDirectManifest("))
        assertFalse(clear.contains("hasForegroundDirectManifestOwnership("))
        assertFalse(clear.contains("statusHandler.removeCallbacks("))
        assertFalse(
            activitySource.contains(
                "clearDeferredNtkAckPreflightForCurrentDirectManifest("
            )
        )
    }

    @Test
    fun strictDirectManifestAckDecisionIsPrecomputedBeforeTheBitmapWave() {
        val remember = functionBody(
            "private fun rememberStrictDirectManifestAckAuthority(",
            activitySource
        )
        val skip = functionBody(
            "private fun shouldSkipPostDrawableNtkAckForDirectManifest(",
            activitySource
        )
        val strictFastPath = skip.indexOf("hasStrictDirectManifestAckAuthority(path)")
        val cacheFallback = skip.indexOf("hasCompleteNativeDirectManifest(manga)")

        assertTrue(remember.contains("seal.canonicalAssets.none"))
        assertTrue(remember.contains("strictDirectManifestAckSkipPath"))
        assertTrue(
            activitySource.contains(
                "strictExactLaunchSeal = seal\n                    " +
                    "rememberStrictForwardReadyFloor(seal)\n                    " +
                    "rememberStrictDirectManifestAckAuthority(seal)"
            )
        )
        assertTrue(strictFastPath >= 0)
        assertTrue(cacheFallback > strictFastPath)
    }

    @Test
    fun forwardRendererRetiresReadGpuTexturesBeforeTheGenericBudget() {
        val prune = functionBody(
            "void pruneTexturesToBudget(",
            rollingRendererSource
        )
        val backwardRetirement = prune.indexOf(
            "lastPresentedMinPage_ - kRetainedBackwardTexturePages"
        )
        val genericBudget = prune.indexOf(
            "const std::uint64_t budget"
        )

        assertTrue(
            rollingRendererSource.contains(
                "constexpr int kRetainedBackwardTexturePages = 2"
            )
        )
        assertTrue(backwardRetirement >= 0)
        assertTrue(genericBudget > backwardRetirement)
        assertTrue(prune.contains("protectedKeys.find(entry->first)"))
        assertTrue(prune.contains("eraseTexture(victim)"))
    }

    @Test
    fun deferredStrictSurfaceStartsOnlyItsIdleProducerThreadAfterAttachment() {
        val attached = functionBody("override fun onAttachedToWindow()")

        assertTrue(attached.contains("super.onAttachedToWindow()"))
        assertTrue(attached.contains("if (rollingNativePresentationEnabled)"))
        assertTrue(attached.contains("startRenderThreadLocked()"))
        assertFalse(attached.contains("prepareRollingNativeRendererLocked()"))
    }

    @Test
    fun hostResumeRevealRequiresContinuousViewportOrExactTerminalTail() {
        val visible = functionBody("private fun hasVisibleActualPixelsLocked()")
        val eligible = functionBody("private fun hasRevealableActualPixelsLocked()")
        val reevaluate = functionBody("private fun reevaluateDeferredSurfaceRevealLocked(")
        val reveal = functionBody("private fun postSurfaceRevealLocked()")
        val identities = functionBody("fun setCommittedPageIdentities(")
        val authoritativeTiles = functionBody("fun setPageAuthoritativeOriginalTiles(")
        val authoritativeBatch = functionBody("fun installAuthoritativeTileBatch(")
        val adjacentP0 = functionBody("fun installAdjacentExactP0Delta(")
        val adjacentBatch = functionBody("fun installAdjacentExactRunwayBatch(")
        val card = functionBody("fun setPageCard(")

        assertTrue(visible.contains("page.committedIdentity != null"))
        assertTrue(visible.contains("pageHasCompleteActualPixelsLocked(page)"))
        assertTrue(eligible.contains("emulatorNativeSurfaceRuntime"))
        assertTrue(eligible.contains("directWifiForwardOnlyInitialResumeEnabled"))
        assertTrue(eligible.contains("hasVisibleActualPixelsLocked()"))
        assertTrue(eligible.contains("hasContinuousActualViewportPixelsLocked()"))
        assertTrue(eligible.contains("directWifiForwardOnlyTerminalTailActualLocked()"))
        assertTrue(reevaluate.contains("hasRevealableActualPixelsLocked()"))
        assertTrue(reveal.contains("hasRevealableActualPixelsLocked()"))
        assertTrue(identities.contains("reevaluateDeferredSurfaceRevealLocked("))
        assertTrue(identities.contains("\"identity\""))
        assertTrue(authoritativeTiles.contains("\"authoritative_tiles\""))
        assertTrue(authoritativeBatch.contains("\"authoritative_tile_batch\""))
        assertTrue(adjacentP0.contains("\"adjacent_exact_p0\""))
        assertTrue(adjacentBatch.contains("\"adjacent_exact_runway_batch\""))
        assertTrue(card.contains("reevaluateDeferredSurfaceRevealLocked(\"card\""))
    }

    @Test
    fun measuredSizeCompletesTheCreateBeforeMeasurePreparationOrdering() {
        val sizeChanged = functionBody("override fun onSizeChanged(")

        assertTrue(sizeChanged.contains("if (width > 0 && height > 0)"))
        assertTrue(sizeChanged.contains("captureNativeRenderTargetGeometryLocked(width, height)"))
        assertTrue(
            sizeChanged.contains(
                "prepareRollingNativeRenderTargetsLocked(width, height)"
            )
        )
    }

    @Test
    fun hostBoundsChangeRetiresTheOldNativeOwnerAndKeepsHwuiUntilStableReattach() {
        val sizeChanged = functionBody("override fun onSizeChanged(")
        val surfaceCreated = functionBody("override fun surfaceCreated(")
        val surfaceChanged = functionBody("override fun surfaceChanged(")

        assertTrue(sizeChanged.contains("retireDirectSurfaceSchedulingLocked()"))
        assertTrue(sizeChanged.contains("rollingNativeAttachEpoch = 0L"))
        assertTrue(sizeChanged.contains("nativePresentationVisible = false"))
        assertTrue(sizeChanged.contains("nativeSurfaceContentRevealed = false"))
        assertTrue(sizeChanged.contains("NtkRollingNativeBridge.nativeDetach(handle, epoch)"))
        assertTrue(sizeChanged.contains("HOST_BOUNDS_REATTACH_SETTLE_MS"))
        assertTrue(surfaceCreated.contains("nativeHostBoundsReattachPending"))
        assertTrue(surfaceChanged.contains("nativeHostBoundsReattachPending"))
    }

    @Test
    fun measuredTargetGeometryIsFrozenWithoutStartingNativeOrPixelWork() {
        val capture = functionBody("private fun captureNativeRenderTargetGeometryLocked(")
        val selection = functionBody("private fun nativeRenderTargetSizeLocked(")
        val preparation = functionBody("private fun prepareRollingNativeRenderTargetsLocked(")

        assertTrue(capture.contains("computeNativeRenderTargetSizeLocked("))
        assertTrue(capture.contains("rollingNativeFrozenTargetWidth = target.first"))
        assertTrue(selection.contains("rollingNativeFrozenViewportWidth == viewportWidth"))
        assertTrue(selection.contains("rollingNativeFrozenTargetWidth"))
        assertFalse(capture.contains("NtkRollingNativeBridge"))
        assertFalse(capture.contains("Bitmap"))
        assertFalse(capture.contains("Surface"))
        assertFalse(preparation.contains("surfaceAttachmentDeferredUntilActualPixels"))
    }

    @Test
    fun hostSurfaceControlTargetUsesByteBoundedAdaptiveCropRunway() {
        val target = functionBody("private fun computeNativeRenderTargetSizeLocked(")
        val state = functionBody("private fun buildDrawStateLocked(")

        assertTrue(target.contains("if (!emulatorNativeSurfaceRuntime) return viewportTarget"))
        assertTrue(target.contains("emulatorNativeBandViewportCount("))
        assertTrue(target.contains("EMULATOR_NATIVE_MAX_BAND_HEIGHT_PX"))
        assertTrue(source.contains("EMULATOR_NATIVE_TARGET_BAND_BYTES = 24L * 1024L * 1024L"))
        assertTrue(source.contains("EMULATOR_NATIVE_MIN_BAND_VIEWPORTS = 3"))
        assertTrue(source.contains("EMULATOR_NATIVE_MAX_BAND_VIEWPORTS = 6"))
        assertTrue(state.contains("retainedNativeBandHeight = if (retainNativeItemsForViewport)"))
        assertTrue(state.contains("nativeBandSpan"))
        assertFalse(state.contains("val nativeBandOrigin = if (retainNativeItemsForViewport)"))
        assertTrue(state.contains("floor(scrollOffset / nativeBandStep) * nativeBandStep"))
    }

    @Test
    fun exactHwuiScrollMovesIdentityCheckedPageNodesInsteadOfRerecordingBitmaps() {
        val draw = functionBody("private fun drawState(")
        val retained = functionBody("private fun retainedPageNode(")

        assertTrue(draw.contains("allowRetainedPageNode = true"))
        assertFalse(draw.contains("allowRetainedPageNode = !directPreparedBitmap"))
        assertTrue(retained.contains("cached.bitmap === item.bitmap"))
        assertTrue(retained.contains("sameBitmapIdentity(cached.tileBitmaps, liveTiles)"))
        assertTrue(retained.contains("HostExactHardwareTilePool.isActiveToken"))
    }

    @Test
    fun earlyTransparentProducerStillRequiresExactIdentityAndActualPixelsBeforeRendering() {
        val activation = functionBody("fun activateDeferredSurfaceProducer()")
        val stage = functionBody("private fun postSurfaceRevealLocked()")
        val commit = functionBody("private fun onFrameCommitted(")
        val dispatch = functionBody("private fun drainCompletedDrawDispatch()")

        assertFalse(activation.contains("nativeSurfaceView.visibility = View.VISIBLE"))
        assertFalse(stage.contains("nativeSurfaceView.visibility = View.VISIBLE"))
        assertTrue(activation.contains("deferredSurfaceIdentityActivated = true"))
        assertTrue(stage.contains("!deferredSurfaceIdentityActivated"))
        assertTrue(stage.contains("nativeSurfaceView.visibility != View.VISIBLE"))
        assertTrue(commit.contains("cleanCommittedHwuiActualPixels"))
        assertTrue(commit.contains("completedDrawDispatchQueue.offer("))
        assertTrue(dispatch.contains("listener?.onCompletedDraw(proof)"))
        assertTrue(dispatch.contains("revealNativeSurfaceAfterFirstHwuiCommit("))
        assertTrue(
            dispatch.indexOf("listener?.onCompletedDraw(proof)") <
                dispatch.indexOf("revealNativeSurfaceAfterFirstHwuiCommit(")
        )
    }

    @Test
    fun preparationCreatesNoSurfaceAndSubmitsNoFrame() {
        val preparation = functionBody("private fun prepareRollingNativeRendererLocked()")

        assertTrue(preparation.contains("NtkRollingNativeBridge.nativeCreate("))
        assertFalse(preparation.contains("nativeAttach("))
        assertFalse(preparation.contains("nativeSubmit("))
        assertFalse(preparation.contains("visibility = View.VISIBLE"))
    }

    @Test
    fun renderTargetPreparationHasNoPixelsSurfaceOrPresentationPath() {
        val preparation = functionBody(
            "private fun prepareRollingNativeRenderTargetsLocked("
        )

        assertTrue(preparation.contains("NtkRollingNativeBridge.nativePrepare("))
        assertFalse(preparation.contains("nativeAttach("))
        assertFalse(preparation.contains("nativeSubmit("))
        assertFalse(preparation.contains("nativePrewarm("))
        assertFalse(preparation.contains("Bitmap"))
        assertFalse(preparation.contains("Surface"))
    }

    @Test
    fun detachInvalidatesPendingCreationAndLateHandleIsDestroyed() {
        val detached = functionBody("override fun onDetachedFromWindow()")
        val preparation = functionBody("private fun prepareRollingNativeRendererLocked()")

        assertTrue(detached.contains("rollingNativeCreateGeneration += 1L"))
        assertTrue(detached.contains("rollingNativeCreatePending = false"))
        assertTrue(preparation.contains("rollingNativeCreateGeneration == generation"))
        assertTrue(preparation.contains("NtkRollingNativeBridge.nativeDestroy(createdHandle)"))
    }

    @Test
    fun surfaceReplacementRetiresProducerCallbacksBeforeFramePipe() {
        val destroyed = functionBody("override fun surfaceDestroyed(holder: SurfaceHolder)")
        val retirement = functionBody("private fun retireDirectSurfaceSchedulingLocked()")
        val callbackRemoval = functionBody("private fun removeReservedDirectDisplayCallback(")
        val stopThread = functionBody("private fun stopRenderThreadLocked()")

        val surfaceFence = destroyed.indexOf("directSurfaceReady = false")
        val callbackRetirement = destroyed.indexOf("retireDirectSurfaceSchedulingLocked()")
        val framePipeRetirement = destroyed.indexOf("clearFramePipeLocked(preserveDirty = true)")
        assertTrue(surfaceFence >= 0)
        assertTrue(callbackRetirement > surfaceFence)
        assertTrue(framePipeRetirement > callbackRetirement)
        assertTrue(retirement.contains("removeCallbacks(directFramePostRunnable)"))
        assertTrue(retirement.contains("directCadenceDeadlineGate.clear()"))
        assertFalse(retirement.contains("removeCallbacks(directCadence"))
        assertTrue(retirement.contains("removeReservedDirectDisplayCallback(choreographer)"))
        assertTrue(callbackRemoval.contains("choreographer.removeFrameCallback(directFrameCallback)"))
        assertFalse(retirement.contains("removeDirectEmulatorVsyncCallback()"))
        assertFalse(retirement.contains("directEmulatorVsyncDispatchRunnable"))
        assertTrue(retirement.contains("directFrameCallbackPosted = false"))
        assertTrue(retirement.contains("directLateInputCatchupPosted = false"))
        assertTrue(retirement.contains("directAdjacentExactP0CatchupPosted = false"))
        assertTrue(stopThread.contains("retireDirectSurfaceSchedulingLocked()"))
    }

    @Test
    fun hostEmulatorCadenceUsesBinderFreeAbsoluteProducerDeadline() {
        val post = functionBody("private fun postReservedDirectFrameCallback(")
        val runnable = functionBody("private val directEmulatorFrameRunnable = Runnable")

        assertTrue(post.contains("handler.postAtTime(directEmulatorFrameRunnable"))
        assertFalse(post.contains("postDirectEmulatorVsyncCallback"))
        assertFalse(source.contains("directEmulatorVsyncCallback"))
        assertFalse(source.contains("DIRECT_EMULATOR_VSYNC_FALLBACK_LAG_NANOS"))
        assertTrue(post.contains("deadlineNanos - wakeAheadNanos"))
        assertFalse(post.contains("emulatorFrameTimelineChoreographer"))
        assertTrue(runnable.contains("directEmulatorNextFrameDeadlineNanos"))
        assertTrue(runnable.contains("expectedPresentationTimeNanos = expectedPresentationNanos"))
    }

    @Test
    fun surfaceLossRestoresHwuiBeforeTheReplacementNativeSurfaceCanAttach() {
        val destroyed = functionBody("override fun surfaceDestroyed(holder: SurfaceHolder)")
        val commit = functionBody("private fun onFrameCommitted(")
        val dispatch = functionBody("private fun drainCompletedDrawDispatch()")
        val reveal = functionBody("private fun revealNativeSurfaceAfterFirstHwuiCommit(")
        val created = functionBody("override fun surfaceCreated(holder: SurfaceHolder)")

        val framePipeRetirement = destroyed.indexOf(
            "clearFramePipeLocked(preserveDirty = true)"
        )
        val replacementGate = destroyed.indexOf(
            "nativeSurfaceRevealAfterFirstHwuiCommitPending = true"
        )
        val hideEmptyChild = destroyed.indexOf(
            "nativeSurfaceView.visibility = View.GONE"
        )
        assertTrue(framePipeRetirement >= 0)
        assertTrue(replacementGate > framePipeRetirement)
        assertTrue(hideEmptyChild > replacementGate)
        assertTrue(
            destroyed.contains(
                "rollingNativePresentationEnabled && renderRunning && pages.isNotEmpty()"
            )
        )
        assertTrue(destroyed.contains("nativeSurfaceView.visibility == View.VISIBLE"))
        assertTrue(destroyed.contains("nativeSurfaceRevealAfterPrepareEpoch = 0L"))
        assertFalse(destroyed.contains("nativeSurfaceView.visibility = View.VISIBLE"))
        assertFalse(destroyed.contains("attachRollingNativeSurface("))

        assertTrue(commit.contains("cleanCommittedHwuiActualPixels"))
        assertTrue(commit.contains("nativeSurfaceRevealAfterFirstHwuiCommitPending"))
        assertTrue(dispatch.contains("listener?.onCompletedDraw(proof)"))
        assertTrue(dispatch.contains("revealNativeSurfaceAfterFirstHwuiCommit("))
        assertTrue(
            dispatch.indexOf("listener?.onCompletedDraw(proof)") <
                dispatch.indexOf("revealNativeSurfaceAfterFirstHwuiCommit(")
        )
        assertTrue(reveal.contains("nativeSurfaceView.visibility = View.VISIBLE"))
        assertTrue(created.contains("attachRollingNativeSurface("))
    }

    @Test
    fun pendingSurfaceRecoveryRepulsesTheAdmittedHwuiTokenWithoutReplacingIt() {
        val recovery = functionBody("fun requestPendingNativeSurfaceHwuiCommit()")

        assertTrue(recovery.contains("nativeSurfaceRevealAfterFirstHwuiCommitPending"))
        assertTrue(recovery.contains("framePipe == FramePipe.IDLE"))
        assertTrue(recovery.contains("scheduleFrameLocked()"))
        assertTrue(recovery.contains("invalidate()"))
        assertTrue(recovery.contains("(parent as? View)?.invalidate()"))
        assertTrue(recovery.contains("postInvalidateOnAnimation()"))
        assertFalse(recovery.contains("clearFramePipeLocked("))
        assertFalse(recovery.contains("advanceDesiredVersionLocked("))
        assertFalse(recovery.contains("markPixelsDirtyLocked("))
        assertFalse(recovery.contains("invalidateCommittedPresentationProof("))
    }

    @Test
    fun physicalFrameContainsOnlyViewportPixels() {
        val submit = functionBody("private fun submitNativeFrame(")
        val snapshot = functionBody("private fun nativeSubmissionSnapshotLocked(")
        val packTile = functionBody("private fun packNativeFrameTile(")
        val packTileInto = functionBody("private fun packNativeFrameTileInto(")
        val tileIntersection = functionBody("private fun nativeTileIntersectsViewport(")

        assertTrue(submit.contains("NtkRollingNativeBridge.nativeSubmit("))
        assertTrue(submit.contains("frameTimelineVsyncId"))
        assertTrue(submit.contains("expectedPresentationTimeNanos"))
        assertTrue(submit.contains("filterDirectWifiNativeTiles"))
        assertTrue(snapshot.contains("directWifiExpandedNativeTextureEpisodePaths"))
        assertTrue(submit.contains("nativeTileIntersectsViewport("))
        assertTrue(submit.contains("ensureNativeFrameScratchCapacity(requiredTileCount)"))
        assertTrue(submit.contains("nativeFrameTileDataScratch"))
        assertTrue(submit.contains("nativeFrameGeometryScratch"))
        assertTrue(submit.contains("nativeFrameBitmapScratch"))
        assertTrue(submit.contains("nativeFrameResourceScratch"))
        assertTrue(packTile.contains("packNativeFrameTileInto("))
        assertTrue(packTile.contains("nativeFrameResourceScratch"))
        assertTrue(packTileInto.contains("HostExactHardwareTilePool.nativeHandle(bitmap)"))
        assertTrue(packTileInto.contains("integerOffset = ordinal * NATIVE_TILE_INT_STRIDE"))
        assertTrue(packTileInto.contains("resources[ordinal] = bitmap"))
        assertFalse(submit.contains("ArrayList<Bitmap>()"))
        assertFalse(submit.contains("ArrayList<Int>()"))
        assertFalse(submit.contains("ArrayList<Float>()"))
        assertTrue(tileIntersection.contains("tileBottom > 0f && tileTop < nativeHeight.toFloat()"))
        assertFalse(submit.contains("nativePrewarmTile"))
        assertFalse(submit.contains("NATIVE_PREWARM_OFFSCREEN_GAP_PX"))
    }

    @Test
    fun translucentNativeSurfaceCannotOccludeTheHwuiFallbackWithBlack() {
        val draw = functionBody("bool drawFrame(", rollingRendererSource)
        val surfaceCreated = functionBody("override fun surfaceCreated(", source)
        val surfaceDestroyed = functionBody("override fun surfaceDestroyed(", source)
        val firstHwuiReveal = functionBody("private fun revealNativeSurfaceAfterFirstHwuiCommit(", source)
        val presentedReveal = functionBody("private fun revealNativeSurfaceAfterPresentedFrame(", source)
        val revealDrain = functionBody("private fun drainNativeSurfaceReveal(", source)
        val onDraw = functionBody("override fun onDraw(", source)

        assertTrue(source.contains("nativeSurfaceView.holder.setFormat(PixelFormat.TRANSLUCENT)"))
        assertTrue(draw.contains("glClearColor(0.0F, 0.0F, 0.0F, 0.0F)"))
        assertFalse(draw.contains("glClearColor(0.0F, 0.0F, 0.0F, 1.0F)"))
        assertTrue(surfaceCreated.contains("nativeSurfaceView.alpha = 0f"))
        assertTrue(surfaceDestroyed.contains("nativeSurfaceContentRevealed = false"))
        assertTrue(firstHwuiReveal.contains("nativeSurfaceView.alpha = 0f"))
        assertTrue(presentedReveal.contains("nativeSurfaceRevealDispatchGate.offer"))
        assertTrue(presentedReveal.contains("nativePresentedStructureEpoch != expectedStructureEpoch"))
        assertTrue(presentedReveal.contains("traversalStructureEpoch != expectedStructureEpoch"))
        assertTrue(revealDrain.contains("nativePresentationVisible"))
        assertTrue(revealDrain.contains("nativeSurfaceContentRevealed = true"))
        assertTrue(revealDrain.contains("nativeSurfaceView.alpha = 1f"))
        assertTrue(onDraw.contains("nativePresentationVisible && nativeSurfaceContentRevealed"))
    }

    @Test
    fun nativeSurfaceRetainsOriginalImageLayerAndToolbarUsesPanelWindows() {
        assertTrue(source.contains("nativeSurfaceView.setZOrderOnTop(true)"))
        assertFalse(source.contains("nativeSurfaceView.setZOrderMediaOverlay(true)"))
        assertTrue(activitySource.contains("WindowManager.LayoutParams.TYPE_APPLICATION_PANEL"))
        assertTrue(activitySource.contains("manager.addView(topBar"))
        assertTrue(activitySource.contains("manager.addView(bottomBar"))
    }

    @Test
    fun strictLaunchStillAllowsContinuousAdjacentEpisodeAppend() {
        val append = functionBody("fun appendAdjacentEpisode(", sessionSource)
        val completionOwnerJoin = functionBody(
            "private fun joinCompletionOwnedForwardExactAppend(",
            sessionSource
        )
        val currentManifestGate = functionBody(
            "private fun ntkCurrentGeneratedManifestDelayMs(",
            sessionSource
        )
        val adjacentStreams = functionBody(
            "private fun startAdjacentForegroundStreams(",
            sessionSource
        )
        val adjacentPreStartGate = functionBody(
            "private fun shouldSkipActiveAdjacentPreStartForegroundStreams(",
            sessionSource
        )
        val currentEpisodeGate = functionBody(
            "private fun isCurrentEpisodeCompleteForImmediateAdjacentStream(",
            sessionSource
        )
        val boundedForegroundStream = functionBody(
            "private fun startBoundedForegroundStreamFetch(",
            sessionSource
        )
        val requestPage = functionBody("private fun requestPage(", sessionSource)
        val nearBoundary = functionBody("override fun onNearBoundary(", activitySource)

        assertFalse(append.contains("if (strictExactColdRolling) return AppendStartResult.CANCELLED"))
        assertTrue(append.contains("joinCompletionOwnedForwardExactAppend(anchorManga, direction)"))
        assertTrue(
            append.indexOf("joinCompletionOwnedForwardExactAppend(anchorManga, direction)") <
                append.indexOf("syncNtkTitlePathFromEpisode(currentTitle, anchorManga)")
        )
        assertTrue(completionOwnerJoin.contains("forwardAdjacentExactManifestAppends.entries.filter"))
        assertTrue(completionOwnerJoin.contains("pending.size != 1"))
        assertTrue(completionOwnerJoin.contains("return true"))
        assertTrue(completionOwnerJoin.contains("currentAuthoritativeManifest(targetPath)"))
        assertFalse(completionOwnerJoin.contains("adjacentEpisodeCandidates("))
        assertFalse(completionOwnerJoin.contains("fetchEpisodesForeground("))
        assertTrue(append.contains("!firstBitmapLogged.get()"))
        assertTrue(append.contains("!firstDrawableDelivered.get()"))
        assertTrue(append.contains("ntkFirstBitmapAtMs.get() <= 0L"))
        assertTrue(currentManifestGate.contains("strictExactColdRolling"))
        assertTrue(currentManifestGate.contains("installed >= known"))
        assertTrue(currentManifestGate.contains("isCurrentGeneratedTailReadyForAdjacent"))
        assertTrue(currentManifestGate.contains("append_adjacent_current_exact_tail_ready"))
        assertTrue(adjacentStreams.contains("isCurrentEpisodeCompleteForImmediateAdjacentStream(target, direction)"))
        assertTrue(adjacentStreams.contains("currentEpisodeCompleteForImmediateAdjacent"))
        assertTrue(adjacentPreStartGate.contains("isCurrentEpisodeCompleteForImmediateAdjacentStream(target, direction)"))
        assertTrue(currentEpisodeGate.contains("hasForwardAdjacentBoundaryDemand(target, direction)"))
        assertTrue(currentEpisodeGate.contains("completeCurrentStructure"))
        assertTrue(currentEpisodeGate.contains("allCurrentDrawablesReady"))
        assertTrue(currentEpisodeGate.contains("currentTailDemandReady"))
        assertTrue(currentEpisodeGate.contains("shouldBypassAdjacentInputQuiet"))
        assertTrue(
            boundedForegroundStream.contains(
                "strictExactColdRolling && Manga.sameEpisodeIdentity(target, manga)"
            )
        )
        assertFalse(boundedForegroundStream.contains("if (strictExactColdRolling) return false"))
        assertTrue(requestPage.contains("isStrictExactLaunchPage(initialPage)"))
        assertTrue(nearBoundary.contains("session?.prepareAdjacentEpisode(prepareAnchor, direction)"))
        assertFalse(nearBoundary.contains("near_boundary_prepare_defer_active_ntk_scroll"))
    }

    @Test
    fun chainedLookaheadCannotTruncateOrCompeteWithTheCurrentEpisode() {
        val append = functionBody("fun appendAdjacentEpisode(", sessionSource)
        val generatedSeed = functionBody(
            "private fun seedNtkAppendGeneratedUrlsFromNeighbor(",
            sessionSource
        )

        assertFalse(sessionSource.contains("scheduleNtkForwardLookahead("))
        assertFalse(sessionSource.contains("appendNtkForwardLookahead("))
        assertFalse(sessionSource.contains("loadLookaheadAppendUrls("))
        assertTrue(append.contains("val useAuthoritativeManifest ="))
        assertTrue(append.contains("isolatedAdjacentPrefetchCandidate("))
        assertTrue(append.contains("loadAuthoritativeAdjacentUrlsForPrefetch("))
        assertTrue(append.contains("if (!useAuthoritativeManifest)"))
        assertTrue(generatedSeed.contains("allowSpeculativeExtension ->"))
        assertTrue(
            generatedSeed.contains(
                "append_adjacent_seed_generated_strict_extension_unresolved"
            )
        )
        assertTrue(
            sessionSource.contains(
                "NtkAdjacentRunwayPreparationPolicy.CURRENT_EPISODE_COMPLETE_IDLE_REASON"
            )
        )
        assertTrue(sessionSource.contains("NtkCompletedForwardEpisodePolicy.isComplete("))
        assertTrue(sessionSource.contains("hasForwardNtkEpisodeAfterSource(snapshot.episode)"))
    }

    @Test
    fun everyNtkAdjacentNetworkAndBodyStartsOnlyAfterEveryCurrentDrawable() {
        val completionPolicy = functionBody(
            "private fun isNtkContinuousAdjacentCompletionPolicyActive(",
            sessionSource
        )
        val directTransport = functionBody(
            "private fun isDirectWifiStrictAdjacentTransportActive(",
            sessionSource
        )
        val complete = functionBody(
            "private fun isEpisodeFullyDrawableForAdjacent(",
            sessionSource
        )
        val completedEpisodePolicy = functionBody(
            "fun isComplete(",
            sessionSource
        )
        val metadata = functionBody(
            "private fun maybeStartInitialAdjacentMetadataPrefetch(",
            sessionSource
        )
        val exactManifest = functionBody(
            "private fun loadAuthoritativeAdjacentUrlsForPrefetch(",
            sessionSource
        )
        val exactManifestWait = functionBody(
            "private fun waitForExactViewerApiAdjacentUrls(",
            sessionSource
        )
        val resolvedMetadata = functionBody(
            "private fun prefetchResolvedMetadataAdjacent(",
            sessionSource
        )
        val completionRelease = functionBody(
            "fun prepareForwardAdjacentAfterCurrentComplete(",
            sessionSource
        )
        val appendedCompletionRelease = functionBody(
            "private fun maybeWarmCompletedForwardEpisode(",
            sessionSource
        )
        val physicalAdmission = functionBody(
            "private fun awaitAdjacentPhysicalAdmission(",
            clickOwnedQuarantineSource
        )
        val exactInstall = functionBody(
            "private fun runFlight(",
            strictDiscoveryCoordinatorSource
        )
        val discoveryStart = functionBody(
            "private fun startInternal(",
            strictDiscoveryCoordinatorSource
        )
        val bodyGateRelease = functionBody(
            "private fun releaseAdjacentBodyGate(",
            strictDiscoveryCoordinatorSource
        )
        val completionGateRelease = functionBody(
            "fun releaseAdjacentBodiesAfterPredecessorComplete(",
            strictDiscoveryCoordinatorSource
        )
        val foregroundEntry = functionBody(
            "private fun enterForegroundNetworkIfNeeded(",
            strictDiscoveryCoordinatorSource
        )
        val discoveryRetirement = functionBody(
            "fun retireViewerOwnership(",
            strictDiscoveryCoordinatorSource
        )
        val networkRetirement = functionBody(
            "private fun claimNetworkOwnershipRetirement(",
            strictDiscoveryCoordinatorSource
        )
        val strictDiscoveryStart = functionBody(
            "private fun startStrictNtkDiscovery(",
            activitySource
        )
        val exactManifestCallback = functionBody(
            "override fun onAdjacentExactManifestRequired(",
            activitySource
        )
        val exactManifestGenerationPost = functionBody(
            "private fun postAdjacentExactManifestForGeneration(",
            activitySource,
        )
        val prefetch = functionBody(
            "private fun maybeStartInitialTailAdjacentPrefetch(",
            sessionSource
        )
        val preappend = functionBody(
            "private fun maybeStartInitialTailAdjacentPreappend(",
            sessionSource
        )
        val streams = functionBody(
            "private fun startAdjacentForegroundStreams(",
            sessionSource
        )
        val refStreams = functionBody(
            "private fun startAdjacentForegroundStreamsForRefs(",
            sessionSource
        )
        val completionCallback = functionBody(
            "private fun queueStrictAllImagesRenderReady(",
            activitySource
        )
        val committedFrame = functionBody(
            "private fun handleAcceptedStrictRollingCompletedDraw(",
            activitySource
        )
        val ntkAdjacentPolicy = functionBody(
            "fun isNtkForwardAdjacentCompletionPolicyActive(",
            sessionSource
        )
        val prime = functionBody(
            "private fun primeAdjacentLaunchWindow(",
            activitySource
        )
        val appendAdjacent = functionBody(
            "fun appendAdjacentEpisode(",
            sessionSource
        )
        val wifiCascade = functionBody(
            "private fun shouldStartWifiAdjacentCascade(",
            sessionSource
        )
        val hybridPrime = functionBody(
            "private fun maybePrimeHybridNtkNextEpisode(",
            activitySource
        )
        val hybridStart = functionBody(
            "private fun maybeStartHybridNtkNextEpisode(",
            activitySource
        )
        val hybridComplete = functionBody(
            "private fun isHybridNtkCurrentEpisodeComplete(",
            activitySource
        )
        val pageBottomGeometry = functionBody(
            "fun isPageBottomReachedAtScroll(",
            source
        )

        assertTrue(completionPolicy.contains("isNtkSource(manga, title)"))
        assertFalse(completionPolicy.contains("MainApplication.getHttpClient().isNtk"))
        val ntkSourceClassifier = functionBody(
            "private fun isNtkSource(",
            sessionSource
        )
        val wfwfSourceClassifier = functionBody(
            "private fun isWfwfSource(",
            sessionSource
        )
        assertTrue(sessionSource.contains("private val legacyBlankSourceNtkAtCreation"))
        assertTrue(ntkSourceClassifier.contains("source.isBlank() && legacyBlankSourceNtkAtCreation"))
        assertTrue(wfwfSourceClassifier.contains("source.isBlank() && !legacyBlankSourceNtkAtCreation"))
        assertFalse(ntkSourceClassifier.contains("MainApplication.getHttpClient()"))
        assertFalse(wfwfSourceClassifier.contains("MainApplication.getHttpClient()"))
        assertFalse(completionPolicy.contains("isNtkWifiTransportActive"))
        assertFalse(completionPolicy.contains("isNtkCellularResilientTransportActive"))
        assertTrue(directTransport.contains("isNtkContinuousAdjacentCompletionPolicyActive()"))
        assertTrue(directTransport.contains("httpClient.isNtkWifiTransportActive()"))
        assertTrue(
            directTransport.contains(
                "!httpClient.isNtkCellularResilientTransportActive()"
            )
        )
        assertTrue(complete.contains("NtkCompletedForwardEpisodePolicy.isComplete("))
        assertTrue(complete.contains("hasListenerDrawableDelivery(index, page)"))
        assertTrue(complete.contains("hasDeliveredBitmap(index)"))
        assertTrue(completedEpisodePolicy.contains("authoritativeCount <= 0"))
        assertTrue(completedEpisodePolicy.contains("sourceIndexes.isEmpty()"))
        assertTrue(completedEpisodePolicy.contains("requiredFirstSource !in 0 until authoritativeCount"))
        assertTrue(completedEpisodePolicy.contains("sourceIndexes[index] < requiredFirstSource"))
        assertTrue(completedEpisodePolicy.contains("drawableReady[index]"))
        assertTrue(
            completedEpisodePolicy.contains(
                "sourceIndexes.distinct() != (0 until authoritativeCount).toList()"
            )
        )
        assertTrue(metadata.contains("!isEpisodeFullyDrawableForAdjacent(source)"))
        assertFalse(metadata.contains("ViewerTelemetry.adjacentWorkStarted("))
        assertTrue(
            exactManifest.contains(
                "waitForExactViewerApiAdjacentUrls("
            )
        )
        assertTrue(exactManifest.contains("!isEpisodeFullyDrawableForAdjacent(source)"))
        assertTrue(
            exactManifest.indexOf("!isEpisodeFullyDrawableForAdjacent(source)") <
                exactManifest.indexOf(
                    "waitForExactViewerApiAdjacentUrls("
                )
        )
        assertTrue(
            exactManifestWait.indexOf("ReaderImageCache.allowAdjacentNtkForegroundViewerPath(") <
                exactManifestWait.indexOf("listener.onAdjacentExactManifestRequired(")
        )
        assertTrue(resolvedMetadata.contains("!isEpisodeFullyDrawableForAdjacent(source)"))
        assertTrue(
            resolvedMetadata.indexOf("!isEpisodeFullyDrawableForAdjacent(source)") <
                resolvedMetadata.indexOf("ReaderImageCache.allowAdjacentNtkForegroundViewerPath(")
        )
        assertTrue(
            completionRelease.contains(
                "releaseClaimedForwardAdjacentBodiesAfterPredecessorComplete("
            )
        )
        assertTrue(completionRelease.contains("startForwardAdjacentExactDiscoveryAtCompletion("))
        assertTrue(
            completionRelease.substringBefore("control.execute {")
                .contains("!trustedLaunchCompletionProof")
        )
        assertTrue(completionRelease.contains("strictExactLaunchSeal?.matchesEpisodePath"))
        assertTrue(
            completionRelease.substringBefore("control.execute {")
                .contains("!isEpisodeFullyDrawableForAdjacent(completedSource)")
        )
        assertTrue(
            completionRelease.indexOf("!isEpisodeFullyDrawableForAdjacent(completedSource)") <
                completionRelease.indexOf(
                    "releaseClaimedForwardAdjacentBodiesAfterPredecessorComplete("
                )
        )
        assertTrue(
            completionRelease.indexOf(
                "releaseClaimedForwardAdjacentBodiesAfterPredecessorComplete("
            ) <
                completionRelease.indexOf("control.execute {")
        )
        assertTrue(
            completionRelease.indexOf("startForwardAdjacentExactDiscoveryAtCompletion(") <
                completionRelease.indexOf("control.execute {")
        )
        assertTrue(
            appendedCompletionRelease.contains("NtkCompletedForwardEpisodePolicy.isComplete(")
        )
        assertTrue(
            appendedCompletionRelease.contains(
                "releaseClaimedForwardAdjacentBodiesAfterPredecessorComplete("
            )
        )
        assertTrue(physicalAdmission.contains("adjacentPredecessorComplete"))
        assertTrue(physicalAdmission.contains("adjacentRunwayRelease"))
        assertTrue(exactInstall.contains("if (flight.adjacentPredecessorGate)"))
        assertTrue(exactInstall.contains("awaitAdjacentControlReady(flight)"))
        assertTrue(exactInstall.contains("awaitAdjacentPredecessorComplete(flight)"))
        assertTrue(exactInstall.contains("flight.directWifiAdjacentBodyGate,"))
        val adjacentControlAwait = exactInstall.indexOf("awaitAdjacentControlReady(flight)")
        val adjacentBodyAwait = exactInstall.indexOf("awaitAdjacentPredecessorComplete(flight)")
        assertTrue(adjacentControlAwait >= 0)
        assertTrue(adjacentBodyAwait >= 0)
        assertTrue(adjacentControlAwait < exactInstall.indexOf("enterForegroundNetworkIfNeeded(flight)"))
        assertTrue(adjacentControlAwait < exactInstall.indexOf("startAckNetworkPrerequisites("))
        assertTrue(
            adjacentControlAwait <
                exactInstall.indexOf("NtkClickOwnedManhwaProbeFrontier.start(")
        )
        assertTrue(adjacentControlAwait < exactInstall.indexOf("client.fetchExactNtkEpisodeDocument("))
        assertTrue(
            exactInstall.indexOf("client.fetchExactNtkEpisodeDocument(") < adjacentBodyAwait
        )
        assertTrue(adjacentBodyAwait < exactInstall.indexOf("streamedRequestSeed ="))
        assertTrue(exactInstall.contains("if (plan != null)"))
        assertTrue(adjacentBodyAwait < exactInstall.indexOf("\"document_plan_reserve\""))
        assertTrue(
            exactInstall.indexOf("awaitAdjacentPredecessorComplete(flight)") <
                exactInstall.indexOf("\"exact_plan_reserve\"")
        )
        assertTrue(
            exactInstall.indexOf("\"exact_plan_reserve\"") <
                exactInstall.lastIndexOf("clickOwnedAnchor = null")
        )
        assertTrue(discoveryStart.contains("NtkAdjacentAdmissionPolicy.decide("))
        assertTrue(
            discoveryStart.contains(
                "val adjacentPredecessorGate = adjacentAdmission.predecessorCompletionRequired"
            )
        )
        assertTrue(
            discoveryStart.contains(
                "val directWifiAdjacentBodyGate = adjacentAdmission.directWifiPhysicalRunway"
            )
        )
        assertTrue(discoveryStart.contains("if (!flight.adjacentPredecessorGate)"))
        assertTrue(
            discoveryStart.indexOf("if (!flight.adjacentPredecessorGate)") <
                discoveryStart.indexOf("startAckNetworkPrerequisites(client, flight, path, route)")
        )
        assertTrue(bodyGateRelease.contains("flight.networkOwnershipRetiring.get()"))
        assertTrue(bodyGateRelease.contains("flight.retirement.isRetired()"))
        assertTrue(bodyGateRelease.contains("flights[flight.episodePath] !== flight"))
        assertTrue(bodyGateRelease.contains("!isViewerOwnerActive(flight)"))
        assertTrue(bodyGateRelease.contains("ViewerTelemetry.adjacentWorkStarted("))
        assertTrue(discoveryRetirement.contains("claimNetworkOwnershipRetirement(owned)"))
        assertTrue(discoveryRetirement.contains("owned.retirement.retire("))
        assertFalse(discoveryRetirement.contains("if (!retirementClaimed"))
        assertTrue(networkRetirement.contains("synchronized(flight)"))
        assertTrue(networkRetirement.contains("flight.networkOwnershipRetiring.set(true)"))
        assertFalse(bodyGateRelease.contains("enterForegroundNetworkIfNeeded(flight)"))
        assertTrue(completionGateRelease.contains("flight.adjacentPredecessorGate"))
        assertFalse(completionGateRelease.contains("flight.directWifiAdjacentBodyGate"))
        assertTrue(foregroundEntry.contains("synchronized(flight)"))
        assertTrue(foregroundEntry.contains("flight.networkOwnershipRetiring.get()"))
        val foregroundMonitorStart = foregroundEntry.indexOf("val entered = synchronized(flight) {")
        val foregroundMonitorExit = foregroundEntry.indexOf("if (!entered) return")
        val compatibilityCancel = foregroundEntry.indexOf("flight.client.cancelNtkWebViewFallbacks()")
        assertTrue(foregroundMonitorStart >= 0)
        assertTrue(foregroundMonitorExit > foregroundMonitorStart)
        assertTrue(
            compatibilityCancel > foregroundMonitorExit
        )
        assertTrue(
            foregroundEntry.indexOf("Viewer ownership retired during foreground enter") >
                compatibilityCancel
        )
        assertTrue(strictDiscoveryStart.contains("val explicitPredecessorPath ="))
        assertTrue(
            strictDiscoveryStart.contains(
                "val predecessorPath = explicitPredecessorPath.ifBlank { currentPath }"
            )
        )
        assertTrue(strictDiscoveryStart.contains("ownerPath,\n                predecessorPath,"))
        assertTrue(exactManifestCallback.contains("postAdjacentExactManifestForGeneration("))
        assertTrue(exactManifestCallback.contains("generation,"))
        assertTrue(exactManifestGenerationPost.contains("val capturedPredecessorPath ="))
        assertTrue(exactManifestGenerationPost.contains("capturedPredecessorPath,"))
        assertTrue(
            exactManifestGenerationPost.contains(
                "generation != activeReaderSessionGeneration.get()",
            ),
        )
        assertFalse(exactManifestGenerationPost.contains("currentManga"))
        assertTrue(prefetch.contains("!isEpisodeFullyDrawableForAdjacent(source)"))
        assertTrue(preappend.contains("isEpisodeFullyDrawableForAdjacent(source)"))
        assertTrue(appendAdjacent.contains("direction < 0"))
        assertTrue(appendAdjacent.contains("reader_adjacent_previous_auto_append_disabled"))
        assertTrue(wifiCascade.contains("directWifiCurrentEpisodeComplete ="))
        assertTrue(wifiCascade.contains("isDirectWifiStrictAdjacentTransportActive()"))
        assertTrue(wifiCascade.contains("isEpisodeFullyDrawableForAdjacent(source)"))
        assertTrue(streams.contains("append_adjacent_foreground_streams_wait_current_complete"))
        assertTrue(
            refStreams.contains(
                "append_adjacent_foreground_ref_streams_wait_current_complete"
            )
        )
        val completionPrepare = "session?.prepareForwardAdjacentAfterCurrentComplete("
        assertTrue(completionCallback.contains(completionPrepare))
        assertTrue(completionCallback.contains("seal.normalizedEpisodePath"))
        assertTrue(completionCallback.contains("authoritativeCompletionProof = true"))
        assertTrue(completionCallback.contains("ViewerTelemetry.markAllImagesRenderReady("))
        assertTrue(
            completionCallback.indexOf("ViewerTelemetry.markAllImagesRenderReady(") <
                completionCallback.indexOf(completionPrepare)
        )
        assertTrue(
            completionCallback.indexOf(completionPrepare) <
                completionCallback.indexOf("ViewerTelemetry.allImagesRenderReady(")
        )
        assertTrue(
            completionCallback.indexOf(completionPrepare) <
                completionCallback.indexOf("renderView.currentProgressPosition()")
        )
        assertTrue(prime.contains("val activeSession = session ?: return"))
        assertTrue(
            prime.contains(
                "!activeSession.canPrepareForwardAdjacentNow(currentManga?.ntkEpisodePath)"
            )
        )
        assertTrue(hybridPrime.contains("!isHybridNtkCurrentEpisodeComplete()"))
        assertTrue(hybridStart.contains("!isHybridNtkCurrentEpisodeComplete()"))
        assertTrue(hybridComplete.contains("readiness.drawablePages == readiness.pageCount"))
        assertTrue(hybridComplete.contains("readiness.loadingPages == 0"))
        assertTrue(hybridComplete.contains("readiness.errorPages == 0"))
        assertFalse(hybridComplete.contains("isNtkWifiTransportActive"))
        assertFalse(hybridComplete.contains("isNtkCellularResilientTransportActive"))
        assertTrue(ntkAdjacentPolicy.contains("!reverse"))
        assertTrue(
            ntkAdjacentPolicy.contains(
                "isNtkContinuousAdjacentCompletionPolicyActive()"
            )
        )
        assertTrue(
            committedFrame.contains(
                "activeSession.isNtkForwardAdjacentCompletionPolicyActive()"
            )
        )
        assertTrue(
            committedFrame.contains(
                "ViewerTelemetry.adjacentActualDrawCommitted("
            )
        )
        assertTrue(committedFrame.contains("adoptPhysicallyPresentedAdjacentEpisode("))
        val physicalAdjacent = functionBody(
            "private fun adoptPhysicallyPresentedAdjacentEpisode(",
            activitySource
        )
        assertTrue(physicalAdjacent.contains("activeSession.pageInfo(displayPage)"))
        assertTrue(physicalAdjacent.contains("info.transitionCard"))
        assertTrue(physicalAdjacent.contains("physicalEpisodePath"))
        val updateEpisode = physicalAdjacent.indexOf("updateCurrentEpisode(")
        assertTrue(updateEpisode >= 0)
        assertTrue(physicalAdjacent.indexOf("displayPage", updateEpisode) > updateEpisode)
        assertFalse(physicalAdjacent.contains("scrollTo"))
        assertFalse(physicalAdjacent.contains("scrollBy"))
        assertTrue(committedFrame.contains("direction > 0"))
        assertTrue(committedFrame.contains("presentedUptimeNanos > 0L"))
        assertTrue(pageBottomGeometry.contains("pageTopOrElseLocked"))
        assertTrue(pageBottomGeometry.contains("pageDrawHeightLocked"))
        assertTrue(pageBottomGeometry.contains("committedScrollOffsetPx + height.toFloat()"))
        assertTrue(
            committedFrame.contains(
                "identity.sourcePageIndex == launchSeal.canonicalAssets.lastIndex"
            )
        )
        assertTrue(
            committedFrame.contains(
                "renderView.isPageBottomReachedAtScroll(launchTailDisplayPage, scrollOffset)"
            )
        )
        assertTrue(
            committedFrame.contains(
                "ViewerTelemetry.forwardBoundaryReached(presentedUptimeNanos)"
            )
        )
        val actualPublish = committedFrame.indexOf(
            "ViewerTelemetry.actualImageDrawCommittedForEpisode("
        )
        assertTrue(
            committedFrame.indexOf("ViewerTelemetry.forwardBoundaryReached(") < actualPublish
        )
        assertTrue(
            committedFrame.indexOf("ViewerTelemetry.adjacentActualDrawCommitted(") < actualPublish
        )
        assertTrue(activitySource.contains("boundary_append_skip_ntk_previous_auto"))
    }

    @Test
    fun adjacentRunwayPublishesOriginalCdnImagesOnlyAfterDrawablePreparation() {
        val appendResolved = functionBody(
            "private fun appendResolvedEpisode(",
            sessionSource
        )
        val runwayGate = functionBody(
            "private fun shouldUseAdjacentInitialAppendRunway(",
            sessionSource
        )
        val publishable = functionBody(
            "private fun isAdjacentRunwayRefPublishable(",
            sessionSource
        )
        val prepare = functionBody(
            "private fun prepareAdjacentRunwayDelivery(",
            sessionSource
        )
        val remainingFetch = functionBody(
            "private fun startRemainingAdjacentRunwayFileFetches(",
            sessionSource
        )
        val requestWindow = functionBody(
            "private fun requestWindow(",
            sessionSource
        )
        val foregroundStream = functionBody(
            "private fun startBoundedForegroundStreamFetch(",
            sessionSource
        )

        assertTrue(
            appendResolved.contains(
                "direction > 0 &&\n            appendResolvedEpisodeInitialRunway"
            )
        )
        assertFalse(
            appendResolved.contains(
                "useInitialRunway &&\n            appendResolvedEpisodeInitialRunway"
            )
        )
        assertFalse(runwayGate.contains("isNtkGeneratedImageUrl"))
        assertTrue(runwayGate.contains("urls.all { it.isNotBlank() }"))
        assertFalse(runwayGate.contains("!isActiveGeneratedTouchOrQuiet()"))
        assertTrue(publishable.contains("ReaderImageCache.cachedFile"))
        assertFalse(publishable.contains("if (!isNtkGeneratedImageUrl(image)) return true"))
        assertTrue(prepare.contains("ReaderImageCache.cachedFile"))
        assertFalse(prepare.contains("Adjacent runway page is not a generated NTK image"))
        assertTrue(remainingFetch.contains(".filter { !it.image.isNullOrBlank() }"))
        assertFalse(
            remainingFetch.contains(".filter { isNtkGeneratedImageUrl(it.image.orEmpty()) }")
        )
        assertTrue(
            sessionSource.contains(
                "NTK_APPEND_REMAINING_RUNWAY_ACTIVE_FILE_FETCH_PAGES = 4"
            )
        )
        assertTrue(
            sessionSource.contains(
                "NTK_APPEND_REMAINING_RUNWAY_ACTIVE_PUBLISH_PAGES = 1"
            )
        )
        assertTrue(
            sessionSource.contains(
                "NTK_APPEND_REMAINING_ACTIVE_VISIBLE_PRIORITY_AHEAD_PAGES = 4"
            )
        )
        assertTrue(requestWindow.contains("val activeViewportPath = anchorPage?.manga?.ntkEpisodePath"))
        assertTrue(
            requestWindow.contains(
                "ReaderImageCache.pauseOtherNtkEpisodeActiveWork(\n                    activeViewportPath"
            )
        )
        assertTrue(
            requestWindow.contains(
                "ReaderPreparedStore.clearOtherNtkEpisodeBitmaps(activeViewportPath)"
            )
        )
        assertTrue(foregroundStream.contains("resolvedPage != null"))
        assertTrue(
            foregroundStream.contains(
                "ReaderImageCache.allowAdjacentNtkForegroundViewerPath("
            )
        )
        assertTrue(
            foregroundStream.contains(
                "initialInteractiveRunwayByteFetches.remove(pageIndex)"
            )
        )
        assertTrue(
            foregroundStream.contains(
                "adjacentForegroundStreamedAppendKeys.remove("
            )
        )
    }

    @Test
    fun adjacentEpisodePreparationIsCompletionGatedAndStructurallyOrdered() {
        val prepareInitial = functionBody(
            "private fun prepareInitialTailAdjacentRunway(",
            sessionSource
        )
        val metadataFirst = functionBody(
            "private fun maybeStartInitialAdjacentMetadataPrefetch(",
            sessionSource
        )
        val suffixGate = functionBody(
            "private fun shouldDeferAdjacentRunwayBehindActiveRemaining(",
            sessionSource
        )

        assertTrue(
            sessionSource.contains(
                "Executors.newFixedThreadPool(\n" +
                    "            NTK_INITIAL_ADJACENT_RUNWAY_FETCH_PARALLELISM"
            )
        )
        assertTrue(prepareInitial.contains("fetchTasks.forEach(initialAdjacentRunwayNetwork::execute)"))
        assertTrue(prepareInitial.contains("refs.forEachIndexed { offset, page ->"))
        assertTrue(
            sessionSource.contains(
                "NTK_INITIAL_ADJACENT_RUNWAY_FETCH_PARALLELISM =\n" +
                    "            NTK_APPEND_INITIAL_RUNWAY_PAGES"
            )
        )
        assertTrue(metadataFirst.contains("metadataOnly = true"))
        assertTrue(metadataFirst.contains("!isEpisodeFullyDrawableForAdjacent(source)"))
        assertTrue(metadataFirst.contains("releaseAdjacentBodiesAfterPredecessorComplete("))
        assertTrue(
            metadataFirst.indexOf("!isEpisodeFullyDrawableForAdjacent(source)") <
                metadataFirst.indexOf("adjacentNetwork.execute")
        )
        assertTrue(metadataFirst.contains("initialTailAdjacentPrefetchKeys.contains(fullRunwayKey)"))
        assertTrue(suffixGate.contains("path != targetPath"))
        assertFalse(suffixGate.contains("isActiveGeneratedTouchOrQuiet"))
        assertFalse(suffixGate.contains("viewportBusy"))
    }

    @Test
    fun exactAdjacentFirstActualFrameImmediatelyPromotesItsCanonicalSuffix() {
        val actual = functionBody(
            "fun onExactNtkAdjacentActualFramePresented(",
            sessionSource
        )
        val wake = functionBody(
            "private fun wakeRemainingAdjacentAppendAfterExactFirstActual(",
            sessionSource
        )
        val strictWake = functionBody(
            "private fun wakeStrictRemainingAdjacentAppend(",
            sessionSource
        )
        val inside = functionBody(
            "private fun isViewportInsideEpisode(",
            sessionSource
        )

        assertTrue(actual.contains("wakeRemainingAdjacentAppendAfterExactFirstActual(normalizedPath)"))
        assertTrue(wake.contains("wakeStrictRemainingAdjacentAppend(path)"))
        assertTrue(strictWake.contains("pendingRemainingAdjacentRunwayAppends[path]"))
        assertTrue(strictWake.contains("strictRemainingAdjacentWakeLatch.tryAcquire(path)"))
        assertTrue(strictWake.contains("scheduledRemainingAdjacentRunwayRetries.cancelPath(path)"))
        assertTrue(strictWake.contains("main.removeCallbacks(scheduled.token)"))
        assertTrue(strictWake.contains("pendingRemainingAdjacentRunwayAppends.remove(path, waiting)"))
        assertTrue(strictWake.contains("appendRemainingAdjacentRunwayRefs("))
        assertFalse(strictWake.contains("currentViewportAnchor.set("))
        assertTrue(inside.contains("firstActualFramePresented?.get() == true"))
    }

    @Test
    fun exactAdjacentConsensusPrecedesLegacyThenIndividualRecoveryCandidates() {
        val candidates = functionBody(
            "private fun adjacentEpisodeCandidates(",
            sessionSource
        )
        val trustedCandidate = functionBody(
            "private fun ntkTrustedProvidedAdjacentCandidate(",
            sessionSource
        )
        val consensus = candidates.indexOf("addCandidate(consensusAuthority)")
        val legacy = candidates.indexOf(
            "addCandidate(trustedAuthority)",
            consensus,
        )
        val visible = candidates.indexOf("addCandidate(visibleAuthority)")
        val canonical = candidates.indexOf("addCandidate(canonicalAuthority)")

        assertTrue(consensus >= 0)
        assertTrue(legacy > consensus)
        assertTrue(visible > legacy)
        assertTrue(canonical > visible)
        assertTrue(
            trustedCandidate.contains(
                "NtkAdjacentEpisodeAuthorityPolicy.isTrustedCandidateDirectionallyConsistent("
            )
        )
        assertTrue(trustedCandidate.contains("return null"))
    }

    @Test
    fun forwardAppendOrderGuardUsesExactAuthoritiesBeforeTheLegacyNeighbor() {
        val guard = functionBody(
            "private fun shouldSkipForwardNtkOutOfOrderAppend(\n" +
                "        target: Manga,",
            sessionSource
        )
        val visible = guard.indexOf("val visibleAuthority = ntkVisibleNumberAdjacentCandidate(")
        val canonical = guard.indexOf("val canonicalAuthority = ntkNumericPathAdjacentCandidate(")
        val exactDecision = guard.indexOf("NtkAdjacentAuthorityConsensusPolicy.decideTarget(")
        val legacy = guard.indexOf("ntkTrustedProvidedAdjacentCandidate(")
        val directWifiClaimFallback = guard.indexOf(
            "NtkDirectWifiStrictAdjacentOrderPolicy.shouldSkipNumericIdFallback("
        )

        assertTrue(visible >= 0)
        assertTrue(canonical > visible)
        assertTrue(exactDecision > canonical)
        assertTrue(legacy > exactDecision)
        assertTrue(directWifiClaimFallback > legacy)
        assertTrue(guard.contains("isDirectWifiStrictAdjacentTransportActive()"))
        assertTrue(guard.contains("adjacentStrictSourceClaims[targetPath]"))
        assertTrue(guard.contains("isAdjacentStrictSourceClaimLive(targetPath, it)"))
    }

    @Test
    fun exactSealAndOwnershipClaimUseTheSameSuspendAwareClockDomain() {
        val ownershipClock = functionBody(
            "private fun monotonicMs()",
            strictOwnershipSource
        )

        assertTrue(ownershipClock.contains("SystemClock.elapsedRealtime()"))
        assertFalse(ownershipClock.startsWith("private fun monotonicMs(): Long = System.nanoTime()"))
    }

    @Test
    fun hostEmulatorUsesCropOnlySurfaceControlMotionWhileDevicesStayAsync() {
        val attach = functionBody("bool attachBackend(", rollingRendererSource)
        val directAttach = functionBody(
            "bool attachHostGpuDirectBackend(",
            rollingRendererSource,
        )
        val cpuWindowAttach = functionBody(
            "bool attachHostCpuWindowBackend(",
            rollingRendererSource,
        )
        val cpuWindowPresent = functionBody(
            "PresentResult presentHostCpuWindowFrame(",
            rollingRendererSource,
        )
        val cpuRegion = functionBody(
            "bool composeCpuFrameRegion(",
            rollingRendererSource,
        )
        val geometry = functionBody(
            "PresentResult presentGeometryOnlyFrame(",
            rollingRendererSource,
        )
        val present = functionBody(
            "PresentResult presentFrame(",
            rollingRendererSource,
        )
        val cpuPrecompose = functionBody(
            "bool startCpuBandPrecomposition(",
            rollingRendererSource,
        )
        val gpuPrecompose = functionBody(
            "bool startGpuBandPrecomposition(",
            rollingRendererSource,
        )
        val viewGeometry = functionBody(
            "private fun applyFrameSyncedGeometryTransaction(",
            source,
        )
        val deferredScene = functionBody(
            "private fun executeDeferredNativeSceneSubmission(",
            source,
        )
        val deferredGeometry = functionBody(
            "private fun executeDeferredNativeGeometrySubmission(",
            source,
        )

        assertTrue(attach.contains("ANativeWindow_setBuffersGeometry"))
        assertFalse(attach.contains("return attachHostGpuDirectBackend(env, command)"))
        assertTrue(attach.contains("const bool hostGpuEmulatorQueue ="))
        assertTrue(attach.contains("eglCreateWindowSurface("))
        assertTrue(attach.contains("const int requestedSwapInterval = 0"))
        assertTrue(attach.contains("hostGpuEmulatorQueue ? 6 : 4"))
        assertTrue(attach.contains("? -4"))
        assertTrue(cpuPrecompose.contains("!backend_.cpuComposerOnly() ||"))
        assertTrue(cpuPrecompose.contains("!supportsExactCpuBandPrecomposition(frame) ||"))
        assertTrue(cpuPrecompose.contains("readyGpuBand_.occupied"))
        assertTrue(cpuPrecompose.contains("gpuFenceInFlight_.load"))
        assertFalse(gpuPrecompose.contains("ScopedBulkPixelNice"))
        assertTrue(gpuPrecompose.contains("sharedWorkerAvailable"))
        assertTrue(rollingRendererSource.contains("composeGpuBandOnSharedContext"))
        assertTrue(present.contains("startGpuBandPrecomposition(env, frame, true)"))
        assertTrue(present.contains("required-gpu-band-admission"))
        assertTrue(present.contains("backend_.cpuComposerOnly() || cpuComposeInFlight_"))
        assertFalse(present.contains("composedFrameCoversForwardRunway("))
        assertTrue(present.contains("shouldPrecomposeRollingBandSuccessor("))
        assertTrue(geometry.contains("geometryDesiredPresentNanosForRuntime("))
        assertTrue(geometry.contains("hostHandlerOwnsCadence"))
        assertTrue(present.contains("appliedViewportSourceTop,\n                    frame.viewportSourceTop"))
        assertTrue(present.contains("if (shouldPrepareSuccessor &&"))
        assertTrue(
            present.substringAfter("if (result == PresentResult::APPLIED")
                .contains("!composedFrameCoversViewport("),
        )
        assertFalse(
            present.substringBefore("if (submittedFrames_ > 0 &&\n            hostGpuEmulatorSurfaceProfile_")
                .contains("startCpuBandPrecomposition(frame)"),
        )
        assertTrue(present.contains("Host-GPU SurfaceControl never replaces a missed runway"))
        assertTrue(present.contains("candidate.cpuComposed = backend_.cpuComposerOnly()"))
        assertTrue(directAttach.contains("static_cast<std::uint32_t>(command.height), false"))
        assertTrue(directAttach.contains("per-tile presenter is the complete host-emulator transport"))
        assertTrue(directAttach.contains("if (directTilesAttached)"))
        assertTrue(
            present.contains(
                "if (submittedFrames_ > 0 &&",
            ),
        )
        assertTrue(present.contains("matchesLastAppliedFrame(frame, &appliedViewportSourceTop)"))
        assertTrue(present.contains("presentGeometryOnlyFrame("))
        assertTrue(present.contains("appliedViewportSourceTop, failureStage"))
        assertTrue(geometry.contains("if (javaFrameSyncedGeometry_)"))
        assertFalse(geometry.contains("unchangedPresentedCrop"))
        assertTrue(geometry.contains("callbackGeometryFrameRequested("))
        assertTrue(geometry.contains("if (submissionAwaitingLatch_)"))
        assertTrue(geometry.contains("PresentResult::TRANSIENT_BACKPRESSURE"))
        assertTrue(geometry.contains("backend_.applyGeometryTransactionDirect("))
        assertTrue(directAttach.contains("javaFrameSyncedGeometry_ = false"))
        assertTrue(geometry.contains("frame.expectedPresentationTimeNanos"))
        assertTrue(geometry.contains("receipt.setBufferCount != 0"))
        assertTrue(surfaceControlBackendSource.contains("NtkGeometryPulse"))
        assertTrue(surfaceControlBackendSource.contains(
            "surfaceApi_.createFromWindow(\n        parentWindow_, \"NtkGeometryPulse\")",
        ))
        assertFalse(surfaceControlBackendSource.contains(
            "surfaceApi_.create(\n        childSurface_, \"NtkGeometryPulse\")",
        ))
        assertTrue(surfaceControlBackendSource.contains(
            "geometryPulseBuffers_[*pulseIndex], -1",
        ))
        assertTrue(surfaceControlBackendSource.contains("rgba[3] = 1U"))
        assertTrue(surfaceControlBackendHeader.contains(
            "kGeometryPulseBufferCount = 2",
        ))
        assertTrue(surfaceControlBackendSource.contains(
            "ASURFACE_TRANSACTION_TRANSPARENCY_TRANSLUCENT",
        ))
        assertTrue(surfaceControlBackendSource.contains(
            "setBufferAlpha(transaction, geometryPulseSurface_, 1.0F)",
        ))
        assertTrue(directAttach.contains("backend_.configureGeometryPulseFrameRate("))
        assertTrue(surfaceControlBackendSource.contains(
            "surfaceApi_.setFrameRate(\n            transaction, geometryPulseSurface_",
        ))
        assertTrue(surfaceControlBackendSource.contains(
            "ANATIVEWINDOW_FRAME_RATE_COMPATIBILITY_FIXED_SOURCE",
        ))
        assertFalse(surfaceControlBackendSource.contains(
            "setBufferAlpha(transaction, geometryPulseSurface_, 0.0F)",
        ))
        assertTrue(surfaceControlBackendSource.contains(
            "surfaceApi_.setOnComplete(transaction, cookie, &onCompleted)",
        ))
        assertFalse(surfaceControlBackendSource.contains(
            "surfaceApi_.setColor(\n        transaction, geometryPulseSurface_",
        ))
        assertTrue(geometry.contains("frame.frameTimelineVsyncId"))
        assertTrue(geometry.contains("frame.expectedPresentationTimeNanos"))
        assertTrue(geometry.contains("receipt.setFrameTimelineCount"))
        assertTrue(geometry.contains("lastGeometryDesiredPresentNanos_ = desiredPresentNanos"))
        assertTrue(rollingRendererSource.contains("if (valid) callbackLatched(env, event)"))
        assertTrue(rollingRendererSource.contains("if (valid && !geometryOnly) {"))
        assertTrue(rollingRendererSource.contains("callbackLatched(env, event);"))
        assertFalse(geometry.contains("rememberAppliedFrame(frame)"))
        assertFalse(geometry.contains("pruneTextures(frame)"))
        assertTrue(source.contains("fun onNtkRollingGeometryFrameRequested("))
        assertTrue(rollingRendererSource.contains("\"(JIIIJJ)Z\""))
        assertTrue(source.contains("geometryBaseSourceTop: Int"))
        assertTrue(source.contains("frameTimelineVsyncId: Long"))
        assertTrue(source.contains("expectedPresentationTimeNanos: Long"))
        assertFalse(viewGeometry.contains("nativeSurfaceView.viewTreeObserver"))
        assertTrue(source.contains("request.sourceTop.toDouble() * viewportHeight.toDouble()"))
        assertTrue(viewGeometry.contains("transaction.setPosition(plan.child, 0f, plan.positionY)"))
        assertTrue(source.contains("MAX_FRAME_SYNCED_GEOMETRY_IN_FLIGHT = 96"))
        assertTrue(source.contains("frameSyncedGeometryInFlightTokens.size >="))
        assertTrue(source.contains("if (backpressured) return false"))
        assertFalse(viewGeometry.contains("postInvalidateOnAnimation()"))
        assertFalse(viewGeometry.contains("nativeSurfaceView.translationY"))
        assertTrue(source.contains("private var pagesAvailableForInput = false"))
        assertTrue(source.contains("private fun isEmpty(): Boolean = !pagesAvailableForInput"))
        assertFalse(source.contains(
            "private fun isEmpty(): Boolean = synchronized(stateLock)",
        ))
        assertTrue(viewGeometry.contains("addTransactionCommittedListener("))
        assertTrue(source.contains("frameSyncedGeometryCommitIngressUptimeNanos"))
        assertTrue(source.contains("command.run()"))
        assertFalse(viewGeometry.contains("context.mainExecutor"))
        assertTrue(source.contains("surfaceControlLatchObserved = true"))
        assertTrue(source.contains("setName(\"NtkStripGeometry\")"))
        assertTrue(source.contains("rollingJavaGeometrySurfaceControl"))
        assertTrue(source.contains("vararg additionalSurfaceControls: SurfaceControl"))
        assertTrue(source.contains("surfaces.first"))
        assertTrue(source.contains("surfaces.second"))
        assertTrue(
            attach.contains(
                "const int requestedSwapInterval = 0",
            ),
        )
        assertTrue(attach.contains("eglSwapInterval(display_, requestedSwapInterval)"))
        assertTrue(attach.contains("setNativeWindowSwapInterval(command.window, requestedSwapInterval)"))
        assertTrue(attach.contains("const int8_t frameRateCompatibility = 0"))
        assertTrue(
            attach.contains(
                "command.window, requestedFrameRate, frameRateCompatibility",
            ),
        )
        assertTrue(source.contains("Surface.FRAME_RATE_COMPATIBILITY_DEFAULT"))
        assertTrue(source.contains("Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE"))
        assertTrue(source.contains("Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS"))
        assertTrue(attach.contains("setSharedBufferMode(command.window, false)"))
        assertTrue(attach.contains("setAutoRefresh(command.window, false)"))
        assertTrue(attach.contains("hostGpuEmulatorSurfaceProfile_.load"))
        assertTrue(attach.contains("return attachHostGpuDirectBackend(env, std::move(command))"))
        assertFalse(attach.contains("return attachHostCpuWindowBackend(env, std::move(command))"))
        assertTrue(cpuWindowAttach.contains("ANativeWindow_getWidth(command.window)"))
        assertTrue(cpuWindowAttach.contains("ANativeWindow_getHeight(command.window)"))
        assertTrue(cpuWindowAttach.contains("viewportBufferWidth"))
        assertTrue(cpuWindowAttach.contains("viewportBufferHeight"))
        assertFalse(cpuWindowAttach.contains("command.height,\n                AHARDWAREBUFFER_FORMAT"))
        assertTrue(cpuWindowPresent.contains("composeCpuFrameRegion("))
        assertTrue(cpuWindowPresent.contains("frame.viewportSourceTop"))
        assertTrue(cpuWindowPresent.contains("frame.viewportSourceHeight"))
        assertTrue(cpuRegion.contains("sourceRegionTop"))
        assertTrue(cpuRegion.contains("destinationHeight"))
        assertFalse(rollingRendererSource.contains("forceCompleteHostFrame"))
        assertTrue(directAttach.contains("javaFrameSyncedGeometry_ = false"))
        assertTrue(geometry.contains("appliedFrameTimelineVsyncId"))
        assertTrue(geometry.contains("const std::int64_t appliedFrameTimelineVsyncId = 0"))
        assertTrue(geometry.contains("geometryDesiredPresentNanosForRuntime("))
        assertTrue(geometry.contains("frame.expectedPresentationTimeNanos"))
        assertTrue(geometry.contains("lastGeometryDesiredPresentNanos_"))
        assertTrue(geometry.contains("refreshPeriodNanos_ > 0"))
        assertFalse(
            geometry.substringAfter("backend_.applyGeometryTransactionDirect(")
                .contains("submissionAwaitingLatch_ = true"),
        )
        assertTrue(deferredScene.contains("submission.token < latestSynchronousNativeSubmitToken"))
        assertTrue(deferredScene.contains("retireDeferredNativeGeometryAsSuperseded(terminalToken)"))
        assertTrue(deferredGeometry.contains("latestSynchronousNativeSubmitToken = submission.token"))
        assertTrue(rollingRendererSource.contains("const bool queuedGeometryFrame = !frames_.empty() &&"))
        assertFalse(
            rollingRendererSource.contains(
                "const bool queuedGeometryFrame = !frames_.empty() &&\n" +
                    "                        !hostGpuEmulatorSurfaceProfile_",
            ),
        )
        assertTrue(
            attach.contains(
                "setBufferCount(command.window, hostGpuEmulatorQueue ? 6 : 4)",
            ),
        )
        assertTrue(attach.contains("tryAllocateBuffers(command.window)"))
        assertTrue(attach.contains("EGL_BUFFER_DESTROYED"))
        assertFalse(attach.contains("setNativeWindowSwapInterval(command.window, 1)"))
        assertTrue(cpuWindowAttach.contains("setSharedBufferMode(command.window, false)"))
        assertTrue(cpuWindowAttach.contains("setAutoRefresh(command.window, false)"))
        assertFalse(attach.contains("EGL_BUFFER_PRESERVED"))
    }

    @Test
    fun hostGpuEmulatorNeverRunsNonPresentingTextureUploadsDuringContinuousInput() {
        val pause = functionBody(
            "void applyRequestedPrewarmPauseLocked() noexcept",
            rollingRendererSource,
        )
        val ingress = functionBody(
            "void setPrewarmPaused(bool paused) noexcept",
            rollingRendererSource,
        )
        val active = functionBody(
            "bool isActiveDirectWifiPrewarmLocked() const noexcept",
            rollingRendererSource,
        )

        assertFalse(ingress.contains("lock_guard"))
        assertTrue(ingress.contains("requestedPrewarmPaused_.store"))
        assertTrue(ingress.contains("prewarmPauseCommandPending_.store"))
        assertTrue(pause.contains("!hostGpuEmulatorSurfaceProfile_.load"))
        assertTrue(pause.contains("now + kPrewarmResumeQuietNanos"))
        assertTrue(active.contains("!hostGpuEmulatorSurfaceProfile_.load"))
    }

    @Test
    fun nativeSubmissionUsesHostHandlerCadenceAndDeviceChoreographerFallback() {
        val commit = functionBody("private fun onFrameCommitted(", source)
        val directRender = functionBody("private fun renderDirectSurfaceFrame(", source)
        val post = functionBody("private fun postReservedDirectFrameCallback(", source)
        val lateInputPost = functionBody("private fun postLateDirectInputCatchupLocked()", source)
        val retire = functionBody("private fun retireDirectSurfaceSchedulingLocked()", source)
        val removeDisplay = functionBody("private fun removeReservedDirectDisplayCallback(", source)
        val lateInput = functionBody("private val directLateInputCatchup:", source)
        val adjacentP0 = functionBody("private val directAdjacentExactP0Catchup:", source)

        assertTrue(commit.contains("scheduleFrameLocked()"))
        assertTrue(directRender.contains("postReservedDirectFrameCallback"))
        assertTrue(post.contains("if (emulatorNativeSurfaceRuntime)"))
        assertTrue(post.contains("handler.postAtTime(directEmulatorFrameRunnable, wakeUptimeMillis)"))
        assertTrue(post.contains("directEmulatorNextFrameDeadlineNanos"))
        assertTrue(post.contains("choreographer.postFrameCallback(directFrameCallback)"))
        assertTrue(lateInputPost.contains("if (emulatorNativeSurfaceRuntime)"))
        assertTrue(lateInputPost.indexOf("if (emulatorNativeSurfaceRuntime)") <
            lateInputPost.indexOf("NtkLateInputCatchupPolicy.shouldPost("))
        assertTrue(retire.contains("removeCallbacks(directEmulatorFrameRunnable)"))
        assertTrue(retire.contains("directEmulatorNextFrameDeadlineNanos = 0L"))
        assertFalse(source.contains("removeDirectEmulatorVsyncCallback"))
        assertTrue(removeDisplay.contains("removeCallbacks(directEmulatorFrameRunnable)"))
        assertTrue(removeDisplay.contains("choreographer.removeFrameCallback(directFrameCallback)"))
        assertTrue(lateInput.contains("removeReservedDirectDisplayCallback"))
        assertFalse(lateInput.contains("directEmulatorNextFrameDeadlineNanos = 0L"))
        assertTrue(adjacentP0.contains("removeReservedDirectDisplayCallback"))
        assertFalse(adjacentP0.contains("directEmulatorNextFrameDeadlineNanos = 0L"))
        assertFalse(commit.contains("postDirectNativeRetirementContinuation()"))
        assertFalse(source.contains("directNativeRetirementContinuation"))
        assertFalse(source.contains("ViewerDirectNativeRetirement"))
        assertFalse(source.contains("directReleaseInputCatchup"))
    }

    @Test
    fun decodedTexturePrewarmHasNoPresentationCapability() {
        val prewarm = functionBody("private fun flushResidentNativeTexturePrewarm()")

        assertTrue(prewarm.contains("NtkRollingNativeBridge.nativePrewarm("))
        assertFalse(prewarm.contains("nativeSubmit("))
        assertFalse(prewarm.contains("nativeAttach("))
        assertFalse(prewarm.contains("visibility = View.VISIBLE"))
    }

    @Test
    fun completedEpisodeKeepsGpuUploadsBoundedToTheForwardRunway() {
        val completion = functionBody("fun queueAllAuthoritativeOriginalTextures(")
        val snapshot = functionBody("private fun flushResidentNativeTexturePrewarm()")
        val nativeQueue = functionBody("bool prewarm(", rollingRendererSource)

        assertTrue(completion.contains("canonicalPageCount: Int"))
        assertTrue(completion.contains("pages.size < canonicalPageCount"))
        assertTrue(completion.contains("pages.take(canonicalPageCount).any { page ->"))
        assertTrue(completion.contains("flushResidentNativeTexturePrewarm()"))
        assertTrue(completion.contains("onQueued.run()"))
        assertFalse(completion.contains("(0..pages.lastIndex).toList()"))
        assertTrue(snapshot.contains("NATIVE_PREWARM_AHEAD_VIEWPORTS"))
        assertTrue(snapshot.contains("val requestedPages = if (expandedDirectWifiRunway)"))
        assertTrue(snapshot.contains("DIRECT_WIFI_NATIVE_PREWARM_AHEAD_PAGES"))
        assertTrue(snapshot.contains("HOST_GPU_DIRECT_WIFI_NATIVE_PREWARM_AHEAD_PAGES"))
        assertTrue(snapshot.contains("DIRECT_WIFI_NATIVE_PREWARM_MAX_TILES"))
        assertTrue(snapshot.contains("DIRECT_WIFI_NATIVE_PREWARM_MAX_BYTES"))
        assertTrue(snapshot.contains("identity.normalizedEpisodePath !in"))
        assertTrue(source.contains("directWifiExpandedNativeTextureMinimumPage"))
        assertTrue(snapshot.contains("pageBytes > maxBytes - selectedBytes"))
        assertTrue(snapshot.contains("fun appendOrdinaryTile("))
        assertTrue(snapshot.contains("if (bitmapList.size >= maxTiles) return"))
        assertTrue(snapshot.contains("snapshot.resources,\n                false,"))
        assertTrue(source.contains("DIRECT_WIFI_NATIVE_PREWARM_AHEAD_PAGES = 8"))
        assertTrue(source.contains("DIRECT_WIFI_NATIVE_PREWARM_MAX_TILES = 16"))
        assertTrue(source.contains("DIRECT_WIFI_NATIVE_PREWARM_MAX_BYTES = 96L * 1024L * 1024L"))
        assertTrue(rollingRendererSource.contains("kMaxTextureBudgetBytes = 48ULL * 1024ULL * 1024ULL"))
        assertTrue(rollingRendererSource.contains("kMaxResidentTextureCount = 12"))
        assertFalse(snapshot.contains("NATIVE_FULL_EPISODE_PREWARM_MAX_TILES"))
        assertTrue(nativeQueue.contains("const bool fullSceneSnapshot = completeSceneSnapshot"))
        assertFalse(nativeQueue.contains("bitmapCount) >"))
        assertTrue(nativeQueue.contains("for (auto& queued : prewarmTiles_) releaseTile(env, queued)"))
        assertTrue(nativeQueue.contains("prewarmTiles_.clear()"))
    }

    @Test
    fun completedCurrentEpisodeHandsOnlyItsForwardNeighborToTheWifiRunway() {
        val completion = functionBody("private fun queueStrictAllImagesRenderReady(", activitySource)
        val completionPrepare = functionBody(
            "fun prepareForwardAdjacentAfterCurrentComplete(",
            sessionSource,
        )
        val completionDiscovery = functionBody(
            "private fun startForwardAdjacentExactDiscoveryAtCompletion(",
            sessionSource,
        )
        val completionActivation = functionBody(
            "private fun activateForwardAdjacentCompletionTargetClaim(",
            sessionSource,
        )
        val completionEpisodeResolution = functionBody(
            "private fun resolveForwardAdjacentEpisodeListAtCompletion(",
            sessionSource,
        )
        val completionRunwayHandoff = functionBody(
            "private fun shouldReenterDirectWifiCompletionRunway(",
            sessionSource,
        )
        val exactManifestWatch = functionBody(
            "private fun watchForwardAdjacentExactManifestForPreappend(",
            sessionSource,
        )
        val exactManifestPreappend = functionBody(
            "private fun onForwardAdjacentExactManifestInstalled(",
            sessionSource,
        )
        val adoption = functionBody(
            "private fun adoptPhysicallyPresentedAdjacentEpisode(",
            activitySource,
        )
        val authorize = functionBody(
            "fun authorizeCompletedForwardNativeTextureEpisode(",
        )
        val advance = functionBody(
            "fun advanceCompletedForwardNativeTextureEpisode(",
        )

        assertTrue(completion.contains("onResolvedForwardPath ="))
        assertFalse(completion.contains("cachedNextEpisode?.ntkEpisodePath?.let"))
        assertTrue(
            completionPrepare.contains(
                "onResolvedForwardPath: ((String, String, Long) -> Unit)? = null"
            )
        )
        assertTrue(
            completionActivation.contains(
                "listener.onForwardAdjacentPathResolved(\n" +
                    "            predecessorPath,\n" +
                    "            targetPath,\n" +
                    "            claim.revision,"
            )
        )
        assertTrue(
            completionActivation.contains(
                "onResolvedForwardPath?.invoke(predecessorPath, targetPath, claim.revision)"
            )
        )
        assertTrue(
            completionActivation.indexOf("watchForwardAdjacentExactManifestForPreappend(") <
                completionActivation.lastIndexOf("listener.onAdjacentExactManifestRequired(")
        )
        assertTrue(completionActivation.contains("if (controlOnly)"))
        assertTrue(completionDiscovery.contains("persistedExactAdjacentAuthority"))
        assertTrue(completionDiscovery.contains("isDirectWifiStrictAdjacentTransportActive()"))
        assertTrue(
            completionDiscovery.contains(
                "resolvedNextProviderAuthority || resolvedNextPersistedAuthority"
            )
        )
        assertTrue(
            completionDiscovery.indexOf(
                "resolvedNextProviderAuthority || resolvedNextPersistedAuthority"
            ) <
                completionDiscovery.indexOf("resolveForwardAdjacentEpisodeListAtCompletion(")
        )
        assertTrue(completionDiscovery.contains("resolveForwardAdjacentEpisodeListAtCompletion("))
        assertTrue(completionEpisodeResolution.contains("adjacentNetwork.execute"))
        assertTrue(completionEpisodeResolution.contains("imageRepository.fetchEpisodesForeground("))
        assertTrue(completionEpisodeResolution.contains("isEpisodeFullyDrawableForAdjacent(source)"))
        assertTrue(
            completionEpisodeResolution.contains("startForwardAdjacentExactDiscoveryAtCompletion(")
        )
        assertTrue(
            completionEpisodeResolution.indexOf(
                "startForwardAdjacentExactDiscoveryAtCompletion("
            ) < completionEpisodeResolution.indexOf(
                "maybeStartInitialAdjacentMetadataPrefetch("
            )
        )
        assertTrue(
            completionEpisodeResolution.contains(
                "shouldReenterDirectWifiCompletionRunway(source)"
            )
        )
        assertTrue(
            completionEpisodeResolution.contains(
                "NtkAdjacentRunwayPreparationPolicy.CURRENT_EPISODE_COMPLETE_IDLE_REASON"
            )
        )
        assertTrue(completionEpisodeResolution.contains("finally"))
        assertTrue(
            completionEpisodeResolution.contains(
                "completionAdjacentEpisodeResolutionPaths.remove(predecessorPath)"
            )
        )
        assertTrue(completionRunwayHandoff.contains("cancelled.get()"))
        assertTrue(completionRunwayHandoff.contains("reverse"))
        assertTrue(completionRunwayHandoff.contains("strictExactColdRolling"))
        assertTrue(
            completionRunwayHandoff.contains("isDirectWifiStrictAdjacentTransportActive()")
        )
        assertTrue(
            completionRunwayHandoff.contains("isEpisodeFullyDrawableForAdjacent(source)")
        )
        assertTrue(exactManifestWatch.contains("isDirectWifiStrictAdjacentTransportActive()"))
        assertTrue(exactManifestWatch.contains("addAuthoritativeManifestListener("))
        assertTrue(exactManifestWatch.contains("currentAuthoritativeManifest(targetPath)"))
        assertTrue(exactManifestPreappend.contains("isDirectWifiStrictAdjacentTransportActive()"))
        assertTrue(exactManifestPreappend.contains("isEpisodeFullyDrawableForAdjacent(predecessor)"))
        assertTrue(exactManifestPreappend.contains("exactViewerApiAdjacentUrls(pending.target)"))
        assertTrue(exactManifestPreappend.contains("appendResolvedEpisode("))
        assertFalse(exactManifestPreappend.contains("fetchEpisodesForeground("))
        assertTrue(
            completionDiscovery.indexOf("if (expectedPath.isEmpty() || predecessorPath.isEmpty())") <
                completionDiscovery.indexOf("claimForwardAdjacentCompletionTarget(")
        )
        assertTrue(authorize.contains("if (!directWifiExpandedNativeTextureRunway"))
        assertTrue(authorize.contains("NtkExpandedNativeTextureHistoryPolicy.authorizeForward("))
        assertTrue(authorize.contains("replaceDirectWifiExpandedNativeTextureEpisodePathsLocked()"))
        assertTrue(authorize.contains("reevaluateDeferredSurfaceRevealLocked("))
        assertTrue(adoption.contains("advanceCompletedForwardNativeTextureEpisode("))
        assertTrue(advance.contains("advanceAfterPhysicalCommit("))
        assertTrue(advance.contains("committedIdentity"))
        assertTrue(advance.contains("replaceDirectWifiExpandedNativeTextureEpisodePathsLocked()"))
        assertTrue(advance.contains("directWifiExpandedNativeTextureMinimumPage"))
    }

    @Test
    fun alreadyCompletedAdjacentGateReachesTheFirstSourceBodySample() {
        val strictSource = File(
            "src/main/java/ml/melun/mangaview/reader/NtkStrictSourceSession.kt"
        ).readText()

        assertTrue(
            strictDiscoveryCoordinatorSource.contains("internal fun isAdjacentBodyGateOpen(")
        )
        assertTrue(
            strictDiscoveryCoordinatorSource.contains(
                "flight.adjacentPredecessorComplete.isDone"
            )
        )
        assertTrue(registrySource.contains("val adjacentPredecessorAlreadyComplete ="))
        assertTrue(registrySource.contains("isAdjacentBodyGateOpen("))
        assertTrue(strictSource.contains("adjacentPredecessorAlreadyComplete: Boolean = false"))
        assertTrue(
            strictSource.contains(
                "if (adjacentPredecessorAlreadyComplete) profile?.markPredecessorComplete()"
            )
        )
        assertTrue(
            strictSource.contains(
                "predecessorAlreadyComplete = adjacentPredecessorAlreadyComplete"
            )
        )
    }

    @Test
    fun expandedWifiTextureRunwayCrossesOnlyAnAuthorizedForwardTransitionCard() {
        val authorized = setOf("/webtoon/1/next")

        assertTrue(
            NtkExpandedNativeTextureTransitionPolicy.mayCrossCard(
                cardText = "next",
                errorText = null,
                nextCardText = null,
                nextErrorText = null,
                nextEpisodePath = "/webtoon/1/next",
                authorizedEpisodePaths = authorized,
            )
        )
        assertFalse(
            NtkExpandedNativeTextureTransitionPolicy.mayCrossCard(
                cardText = "next",
                errorText = null,
                nextCardText = null,
                nextErrorText = null,
                nextEpisodePath = "/webtoon/1/stale",
                authorizedEpisodePaths = authorized,
            )
        )
        assertFalse(
            NtkExpandedNativeTextureTransitionPolicy.mayCrossCard(
                cardText = "next",
                errorText = null,
                nextCardText = null,
                nextErrorText = "failed",
                nextEpisodePath = "/webtoon/1/next",
                authorizedEpisodePaths = authorized,
            )
        )
    }

    @Test
    fun directWifiTextureProfileNeverMutatesRecycledGlStorage() {
        val upload = functionBody("bool uploadTile(", rollingRendererSource)
        val recycle = functionBody("void recycleTextureStorage(", rollingRendererSource)
        val retire = functionBody("void retireTextureName(", rollingRendererSource)

        assertTrue(upload.contains("replaceExistingWithFreshName"))
        assertTrue(upload.contains("existing != textures_.end() && !replaceExistingWithFreshName"))
        assertTrue(upload.contains(
            "std::move(previousTextureStorage), !directWifiTextureProfile",
        ))
        assertTrue(recycle.contains("if (allowPool &&"))
        assertTrue(recycle.contains("retireTextureName(texture.texture, texture.bytes)"))
        assertTrue(retire.contains("glDeleteTextures(1, &texture)"))
        assertTrue(retire.contains("textureRetirementDebt_.record(bytes)"))
    }

    @Test
    fun directWifiReleaseGapUploadsOnlyTheNearestUnreadPageBeforeTheQuietGate() {
        val pause = functionBody(
            "void applyRequestedPrewarmPauseLocked() noexcept",
            rollingRendererSource,
        )
        val admission = functionBody(
            "bool canUploadNextPrewarmLocked() const noexcept",
            rollingRendererSource,
        )

        assertTrue(pause.contains("directWifiTextureProfile_.load"))
        assertTrue(pause.contains("presentedMaxPage + 1"))
        assertTrue(pause.contains("directWifiFullPrewarmResumeNanos_ = now + kPrewarmResumeQuietNanos"))
        assertTrue(pause.contains("nextPrewarmUploadNanos_ > immediateDeadline"))
        assertTrue(pause.contains("nextPrewarmUploadNanos_ = immediateDeadline"))
        assertTrue(pause.contains("cpuExactStorageProfile_.load"))
        assertTrue(pause.contains("lastPresentedMaxPageSnapshot_.load"))
        assertTrue(rollingRendererSource.contains("lastPresentedMaxPageSnapshot_.store"))
        assertTrue(admission.contains("directWifiImmediateResumeMaxPage_"))
        assertTrue(admission.contains("next.key.page > directWifiImmediateResumeMaxPage_"))
        assertTrue(admission.contains("return false"))
        assertFalse(admission.contains("directWifiTextureProfile_.load"))
        assertTrue(pause.contains("now + kPrewarmResumeQuietNanos"))
    }

    @Test
    fun activeDirectWifiPrewarmUsesOnlyAnIdleForwardDripLane() {
        val admission = functionBody(
            "bool canUploadNextPrewarmLocked() const noexcept",
            rollingRendererSource,
        )
        val loop = functionBody("void run() noexcept", rollingRendererSource)
        val restore = functionBody("void restoreDeferredPrewarmTile(", rollingRendererSource)

        assertTrue(admission.contains("isActiveDirectWifiPrewarmLocked()"))
        assertTrue(admission.contains("resident->second.contentIdentity == next.contentIdentity"))
        assertTrue(admission.contains("next.key.page >= lastPresentedMaxPage_"))
        assertTrue(admission.contains("hostGpuEmulatorSurfaceProfile_.load"))
        assertTrue(admission.contains("kHostGpuPausedForwardPrewarmPages"))
        assertTrue(admission.contains("lastPresentedMaxPage_ + forwardPrewarmPages"))
        assertTrue(loop.contains("activeDirectWifiCandidate"))
        assertTrue(loop.contains("frames_.empty()"))
        assertTrue(loop.contains("!backend_.hasPendingEvent()"))
        assertTrue(loop.contains("kActiveDirectWifiPrewarmPeriods"))
        assertTrue(loop.contains("prewarmEndNanos - prewarmBeginNanos > refresh"))
        assertTrue(loop.contains("activeDirectWifiPrewarmSuppressed_ = true"))
        assertTrue(loop.contains("restoreDeferredPrewarmTile(env, prewarmTile, prewarmPopRevision)"))
        assertTrue(restore.contains("prewarmQueueRevision_ == expectedRevision"))
        assertTrue(restore.contains("prewarmTiles_.push_front(tile)"))
    }

    @Test
    fun authoritativeStripDeliveryQueuesTheExactValidatedPageSlot() {
        val stripInstall = functionBody("fun installAuthoritativeStripTileDelta(")
        val stripPrewarm = functionBody("private fun flushResidentNativeTexturePrewarm()")

        assertTrue(stripInstall.contains("if (!valid)"))
        assertTrue(stripInstall.contains("postNativeStripTexturePrewarmLocked(command.key, tile)"))
        assertTrue(stripPrewarm.contains("page.stripSlots.forEachIndexed { slot, tile ->"))
        assertTrue(stripPrewarm.contains("pageTiles += slot to tile"))
        assertTrue(stripPrewarm.contains("indexedTile.first"))
        assertTrue(stripPrewarm.contains("NtkRollingNativeBridge.nativePrewarm("))
    }

    @Test
    fun physicalSurfaceAttachmentOutlivesContentFrameEpochResets() {
        val attach = functionBody("private fun attachRollingNativeSurface(")
        val schedule = functionBody("private fun scheduleFrameLocked()")
        val directCallback = functionBody("private fun postDirectFrameCallbackLocked()")
        val latch = functionBody("fun onNtkRollingFrameLatched(")
        val complete = functionBody("private fun completeOrBufferNativePresentation(")

        assertTrue(attach.contains("advanceRollingNativeSurfaceEpochLocked()"))
        assertTrue(attach.contains("rollingNativeSurfaceEpochCounter == request[1]"))
        assertFalse(attach.contains("lifecycleEpoch == request[1]"))
        assertTrue(schedule.contains("rollingNativeAttachEpoch > 0L"))
        assertFalse(schedule.contains("rollingNativeAttachEpoch == epoch"))
        assertTrue(directCallback.contains("rollingNativeAttachEpoch == 0L"))
        assertFalse(directCallback.contains("rollingNativeAttachEpoch != lifecycleEpoch"))
        assertTrue(latch.contains("completeOrBufferNativePresentation("))
        assertTrue(complete.contains("rollingNativeAttachEpoch == submission.nativeSurfaceEpoch"))
        assertTrue(complete.contains("traversalStructureEpoch == submission.proof.structureEpoch"))
    }

    @Test
    fun hostGpuReusesBoundedSourceTextureStorageWhileDevicesKeepFreshNames() {
        val policy = functionBody("bool usesFreshTextureNames() const noexcept", rollingRendererSource)
        val directPresent = functionBody("PresentResult presentFrame(", rollingRendererSource)
        val eviction = functionBody(
            "void eraseTexture(\n            std::unordered_map<TileKey, TextureTile, TileKeyHash>::iterator entry) noexcept",
            rollingRendererSource,
        )

        assertTrue(policy.contains("directWifiTextureProfile_.load"))
        assertTrue(policy.contains("!hostGpuEmulatorSurfaceProfile_.load"))
        assertTrue(directPresent.contains("val directWifiFreshNames").not())
        assertTrue(directPresent.contains("const bool directWifiFreshNames = usesFreshTextureNames()"))
        assertTrue(directPresent.contains("textureUseFrame, directWifiFreshNames"))
        assertTrue(eviction.contains("!usesFreshTextureNames()"))
        assertTrue(rollingRendererSource.contains("kMaxPooledTextureBytes = 24ULL * 1024ULL * 1024ULL"))
        assertTrue(rollingRendererSource.contains("kMaxPooledTextureCount = 12"))
    }

    @Test
    fun appendedStructureRekeysExactResidentTextureBeforeHeadroomPlanning() {
        val headroom = functionBody(
            "bool prepareVisibleFrameTextureHeadroom(",
            rollingRendererSource,
        )

        assertTrue(headroom.contains("candidate->first.page == tile.key.page"))
        assertTrue(headroom.contains("candidate->first.slot == tile.key.slot"))
        assertTrue(headroom.contains("candidate->second.contentIdentity == tile.contentIdentity"))
        assertTrue(headroom.contains("auto adopted = textures_.extract(exactPrior)"))
        assertTrue(headroom.contains("adopted.key() = tile.key"))
        assertTrue(
            headroom.indexOf("textures_.insert(std::move(adopted))") <
                headroom.indexOf("planVisibleTextureHeadroom("),
        )
    }

    @Test
    fun rejectedDirectCallbackCannotStrandAnAdmittedDirtyFrame() {
        val schedule = functionBody("private fun scheduleFrameLocked()")
        val retry = functionBody("private fun scheduleNoStateRetryLocked()")

        assertTrue(schedule.contains("val callbackReady = postDirectFrameCallbackLocked()"))
        assertTrue(schedule.contains("!callbackReady && !directFrameCallbackPosted"))
        assertTrue(schedule.contains("releasePostedAdmissionLocked(preserveDirty = true)"))
        assertTrue(schedule.contains("scheduleNoStateRetryLocked()"))
        assertTrue(retry.contains("val retryHandler = if ("))
        assertTrue(retry.contains("val posted = retryHandler.postDelayed("))
        assertTrue(retry.contains("NO_STATE_RETRY_EXECUTOR.schedule("))
        assertTrue(retry.contains("if (!posted && !backupPosted) noStateRetryPosted = false"))
    }

    @Test
    fun callbackAdmissionGuardTracksActualPixelSubmission() {
        val callback = functionBody("private fun renderDirectSurfaceFrame(")

        assertTrue(callback.contains("if (!rendered) directCallbackHadAdmission = false"))
        assertTrue(callback.contains("if (!rendered) {"))
        assertTrue(callback.contains("recoverDirectSurfaceSubmission(admission.first, admission.second)"))
    }

    @Test
    fun overdueIdleProducerCallbackCannotPoisonLaterPhysicalInput() {
        val watchdog = functionBody("private fun recoverOverdueDirectCadence(")

        assertTrue(watchdog.contains("hasAdmittedFrame"))
        assertTrue(watchdog.contains("hasCurrentDemand = hasAdmittedFrame || shouldKeepDirectCadenceArmedLocked()"))
        assertTrue(watchdog.contains("directFrameCallbackPosted = false"))
        assertFalse(
            watchdog.contains(
                "!shouldKeepDirectCadenceArmedLocked() || rollingTextureSurface?.isValid != true"
            )
        )
    }

    @Test
    fun directWifiForwardHistoryWaitsForNativeFlingToBecomeQuietBeforeRebasing() {
        val note = functionBody("fun noteForwardReadingPosition(", sessionSource)
        val trim = functionBody("private fun trimConsumedForwardHistory(", sessionSource)
        val quiet = functionBody(
            "private fun directWifiForwardHistoryMotionQuietRemainingMs()",
            sessionSource,
        )
        val activityWindow = functionBody("override fun onWindowChanged(", activitySource)

        assertTrue(note.contains("surfaceMotionActive: Boolean = false"))
        assertTrue(note.contains("strictExactDirectWifiRollingPixelResidency.get()"))
        assertTrue(note.contains("directWifiForwardSurfaceMotionAtMs.set("))
        assertTrue(note.contains("directWifiForwardSurfaceMotionActive.set(surfaceMotionActive)"))
        assertTrue(sessionSource.contains("directWifiForwardPhysicalTouchActive.set(active)"))
        assertTrue(quiet.contains("directWifiForwardPhysicalTouchActive.get()"))
        assertTrue(quiet.contains("directWifiForwardSurfaceMotionActive.get()"))
        assertTrue(trim.contains("directWifiForwardHistoryMotionQuietRemainingMs() > 0L"))
        assertTrue(
            trim.lastIndexOf("directWifiForwardHistoryMotionQuietRemainingMs() > 0L") >
                trim.indexOf("synchronized(pagesLock)")
        )
        val finalMutationLock = trim.lastIndexOf("synchronized(pagesLock)")
        val destructiveMutation = trim.indexOf(
            "pages.subList(0, candidate.removeCount).clear()",
            finalMutationLock,
        )
        val finalMotionGate = trim.indexOf("viewportBusy.get() ||", finalMutationLock)
        val finalNativeQuietGate = trim.indexOf(
            "directWifiForwardHistoryMotionQuietRemainingMs() > 0L ||",
            finalMotionGate,
        )
        val finalReaderQuietGate = trim.indexOf(
            "readerQuietRemainingMs(NTK_FORWARD_HISTORY_TRIM_QUIET_MS) > 0L",
            finalNativeQuietGate,
        )
        assertTrue(finalMutationLock >= 0)
        assertTrue(finalMotionGate > finalMutationLock)
        assertTrue(finalNativeQuietGate > finalMotionGate)
        assertTrue(finalReaderQuietGate > finalNativeQuietGate)
        assertTrue(destructiveMutation > finalReaderQuietGate)
        assertTrue(quiet.contains("NTK_DIRECT_WIFI_FORWARD_HISTORY_NATIVE_QUIET_MS"))
        assertTrue(sessionSource.contains("NTK_FORWARD_HISTORY_TRIM_QUIET_MS = 5_000L"))
        assertTrue(
            sessionSource.contains(
                "NTK_DIRECT_WIFI_FORWARD_HISTORY_NATIVE_QUIET_MS = 5_000L"
            )
        )
        assertTrue(activityWindow.contains("noteForwardReadingPosition(adjustedProgressPage, busy)"))
        assertTrue(activitySource.contains("session?.noteForwardSurfaceMotionEnded()"))
    }

    @Test
    fun shortForwardEpisodesRetireOnlyHistoryOlderThanOneExactPredecessor() {
        val candidate = functionBody(
            "private fun forwardHistoryTrimCandidateLocked(",
            sessionSource,
        )
        val trim = functionBody("private fun trimConsumedForwardHistory(", sessionSource)

        assertTrue(candidate.contains("NtkForwardHistoryPolicy.terminalShortEpisodeReached("))
        assertTrue(candidate.contains("authoritativeSourceCount = authoritativeSourceCount"))
        assertTrue(candidate.contains("observedSourceIndexes = observedSourcePages"))
        assertTrue(candidate.contains("activeSourceIndex = activePage.sourceIndex"))
        assertTrue(candidate.contains("retainedPreviousEpisodeStartLocked("))
        assertTrue(candidate.contains("retainedPreviousEpisodeStartIndex = retainedPreviousEpisodeStart"))
        assertTrue(
            candidate.indexOf("terminalShortEpisode = terminalShortEpisode") !=
                candidate.lastIndexOf("terminalShortEpisode = terminalShortEpisode"),
        )
        assertTrue(trim.contains("pages.subList(candidate.removeCount, pages.size)"))
        assertTrue(trim.contains("consumedStrictPaths.removeAll(retainedPaths)"))
    }

    @Test
    fun recurringProducerVsyncRegistrationCannotWaitBehindImageMessages() {
        val render = functionBody("private fun renderDirectSurfaceFrame(")
        val register = functionBody("private fun postReservedDirectFrameCallback(")

        assertTrue(render.contains("nextFrameChoreographer"))
        assertTrue(render.contains("nextFrameChoreographer?.let(::postReservedDirectFrameCallback)"))
        assertTrue(register.contains("choreographer.postFrameCallback(directFrameCallback)"))
        assertTrue(register.contains("val stillCurrent = synchronized(stateLock)"))
        assertTrue(
            register.indexOf("handler.postAtTime(directEmulatorFrameRunnable, wakeUptimeMillis)") >
                register.indexOf("val stillCurrent = synchronized(stateLock)"),
        )
        assertTrue(
            register.indexOf("choreographer.postFrameCallback(directFrameCallback)") >
                register.indexOf("val stillCurrent = synchronized(stateLock)"),
        )
    }

    @Test
    fun synchronousNativePresentationCannotFillPendingCommitWindow() {
        val render = functionBody("private fun finishRenderedFrame(")
        val callback = functionBody("private fun completeOrBufferNativePresentation(")
        val clear = functionBody("private fun clearFramePipeLocked(")

        assertTrue(render.contains("earlyNativeOutcomes.remove(work.frameToken)"))
        assertTrue(render.contains("is EarlyNativeOutcome.Presented"))
        assertTrue(render.contains("is EarlyNativeOutcome.PresentFailed"))
        assertTrue(callback.contains("token == inFlightToken"))
        assertTrue(callback.contains("framePipe == FramePipe.INVALIDATION_POSTED"))
        assertTrue(clear.contains("earlyNativeOutcomes.clear()"))
    }

    @Test
    fun retiredNativePresentationRequestsAProofBearingReplacementFrame() {
        val callback = functionBody("private fun completeOrBufferNativePresentation(")

        assertTrue(callback.contains("registered == null && token !in earlyNativeOutcomes"))
        assertTrue(callback.contains("framePipe == FramePipe.IDLE"))
        assertTrue(callback.contains("renderRequested = true"))
        assertTrue(callback.contains("scheduleFrameLocked()"))
        assertTrue(callback.contains("if (submission == null) return"))
        assertFalse(callback.contains("onFrameCommitted(\n                lifecycleEpoch,\n                token"))
    }

    @Test
    fun strictExactInitialDecodedCohortCannotStarveBehindDeliveryThrottle() {
        val flush = functionBody(
            "private fun initialHeldDeliveriesForImmediateFlush(",
            sessionSource
        )

        assertTrue(flush.contains("if (strictExactColdRolling)"))
        assertTrue(flush.contains("held.mapTo(HashSet(held.size)) { it.index }"))
        assertTrue(flush.contains("return initialViewportHeldDeliveries(held)"))
    }

    @Test
    fun clickOwnedManhwaAnchorUsesBoundedShardAndMixedWifiPngUsesIndependentH1() {
        val route = functionBody(
            "fun resolveClickOwnedAnchorQuarantineRoute(",
            imageCacheSource
        )

        assertTrue(route.contains(".header(\"X-MangaViewer-No-Quic\", \"1\")"))
        assertTrue(
            route.contains(
                "clickOwnedManhwaClient(shared, pageIndex)"
            )
        )
        assertTrue(
            route.contains(
                "clickOwnedMixedFormatBodyClient(shared, checkNotNull(directWifiNetwork))"
            )
        )
        assertTrue(route.contains("httpClient.getNtkDirectWifiNetwork()"))
        assertTrue(route.contains("directWifiMixedPngPage"))
        assertTrue(route.contains("generatedRef.extension"))
        assertTrue(route.contains("generatedRef.extension == \"png\""))
        assertTrue(
            route.contains(
                "directWifiMixedManhwaPhysicalAsset(asset, checkNotNull(directWifiNetwork))"
            )
        )
        assertTrue(route.contains("\"ntk-click-mixed-png-h1\""))
        val physicalRoute = functionBody(
            "private fun directWifiMixedManhwaPhysicalAsset(",
            imageCacheSource
        )
        assertTrue(physicalRoute.contains("plan.networkHandle != directWifiNetwork.networkHandle"))
        val mixedClient = functionBody(
            "private fun clickOwnedMixedFormatBodyClient(",
            imageCacheSource
        )
        assertTrue(mixedClient.contains(".protocols(listOf(Protocol.HTTP_1_1))"))
        assertTrue(mixedClient.contains(".socketFactory(CustomHttpClient.sniFragmentingSocketFactory("))
        assertTrue(mixedClient.contains("directWifiNetwork.socketFactory"))
        assertTrue(mixedClient.contains("directWifiNetwork.getAllByName(hostname)"))
        assertFalse(route.contains("generatedRef.extension != \"jpg\""))
        assertTrue(route.contains("\"ntk-click-anchor-okhttp\""))
        assertFalse(route.contains("ntkDemandBoundExactImageFactory()"))
        assertFalse(route.contains("ntk-click-anchor-http3"))
    }

    @Test
    fun mixedPngPlanUsesOnlyObservedSuffixesAndNeverBlocksJpegBodies() {
        val start = functionBody(
            "fun start(\n            manga: Manga,",
            clickOwnedQuarantineSource
        )

        assertTrue(start.contains("val exactResolutionExtensions = observedExtensions"))
        assertFalse(start.contains("snapshot + candidateAsset"))
        assertTrue(start.contains("candidateFuture.thenCompose { candidate ->"))
        assertTrue(start.contains("!candidate.substringAfterLast('.', \"\")"))
        assertTrue(start.contains("physicalPlanReady.handle { _, _ -> candidate }"))
        assertTrue(start.contains("DIRECT_WIFI_LARGE_PNG_BODY_BYTES"))
        assertTrue(start.contains("CompletableFuture.completedFuture(candidate)"))
        assertFalse(start.contains("candidateFuture.thenCombine("))
    }

    @Test
    fun validatorSafeSegmentedTransportIsConfinedToTheManhwaAnchor() {
        val execute = functionBody(
            "override fun execute(): Response",
            imageCacheSource
        )

        assertTrue(
            imageCacheSource.contains(
                "private const val NTK_MANHWA_SEGMENTED_TRANSPORT_ENABLED = false"
            )
        )
        assertTrue(
            imageCacheSource.contains(
                "private const val NTK_MANHWA_PAGE_ZERO_SEGMENTED_TRANSPORT_ENABLED = true"
            )
        )
        assertTrue(
            imageCacheSource.contains(
                "private const val NTK_MANHWA_RANGE_PREFIX_BYTES = 32L * 1024L"
            )
        )
        assertTrue(
            imageCacheSource.contains(
                "private const val NTK_MANHWA_RANGE_SEGMENT_BYTES = 256L * 1024L"
            )
        )
        assertTrue(
            imageCacheSource.contains(
                "private const val NTK_MANHWA_ANCHOR_SEGMENT_EXECUTOR_LANES = 24"
            )
        )
        assertTrue(execute.contains("strictPageIndex == 0"))
        assertTrue(execute.contains("manhwaRangeReplica"))
        assertTrue(execute.contains("identitySafeRangeReplicaCount >= 2"))
        assertTrue(execute.contains("executeSegmentedManhwa(candidates)"))
    }

    @Test
    fun exactReplicaFailoverWrapperIsStableAcrossExtensionRetries() {
        val route = functionBody(
            "fun resolveClickOwnedAnchorQuarantineRoute(",
            imageCacheSource
        )
        val factory = functionBody(
            "private fun replicaFailoverFactory(",
            imageCacheSource
        )

        assertTrue(route.contains("val factory = replicaFailoverFactory(transportFactory)"))
        assertTrue(factory.contains("replicaFailoverFactories.computeIfAbsent(identity)"))
        assertFalse(route.contains("NtkReplicaFailoverCallFactory(strictInstrumentedClient(bounded))"))
    }

    @Test
    fun signedApiReplicaProofReachesOnlyTheDirectWifiAdjacentStrictRunway() {
        val route = functionBody(
            "fun resolveStrictSourceRoute(",
            imageCacheSource
        )

        assertTrue(route.contains(
            "pageIndex in 0 until proofBackedAdjacentRunwayBodyCount"
        ))
        assertTrue(route.contains(
            "val proofBackedAdjacentGrant = hasActiveAdjacentNtkForegroundViewerGrant("
        ))
        assertTrue(route.contains("httpClient.isNtkWifiTransportActive()"))
        assertTrue(route.contains("!httpClient.isNtkCellularResilientTransportActive()"))
        assertTrue(route.contains("recentExactNtkApiReplicaCandidates("))
        assertTrue(route.contains("tag(NtkExactApiReplicaRouteTag::class.java, proof)"))
        assertTrue(route.contains("ntk-demand-bound-exact-image-proof-replica"))
        assertTrue(route.contains("val replicaAwareFactory = replicaFailoverFactory(baseFactory)"))
        assertTrue(imageCacheSource.contains("!liveDirectWifiAdjacentProofRoute &&"))
        assertTrue(imageCacheSource.contains("!liveDirectWifiAdjacentQuarantineRoute &&"))
        assertTrue(imageCacheSource.contains(
            "originalRequest.tag(NtkQuarantineSourceCallIdentity::class.java)"
        ))
        assertTrue(imageCacheSource.contains(
            "originalRequest.tag(NtkStrictEpisodePathTag::class.java)?.path"
        ))
        assertTrue(imageCacheSource.contains("isLiveDirectWifiAdjacentPath(path)"))
    }

    @Test
    fun adjacentStrictRunwayConsumesPrivatePredecodeBeforeFallbackDecode() {
        val batch = functionBody(
            "private fun prepareAdjacentRunwayDelivery(",
            sessionSource
        )

        assertTrue(batch.contains("lease.predecodedOriginal"))
        assertTrue(batch.contains("takeIfReadyOrAbandon("))
        assertTrue(batch.contains("adoptPrivateStrictExactBitmapForPage("))
        assertTrue(
            batch.indexOf("takeIfReadyOrAbandon(") <
                batch.indexOf("lease.encodedBytes != null")
        )
    }

    @Test
    fun oversizedResidentWebtoonUsesCanvasSafeTilesAfterTheVisibleAnchor() {
        val residentDecode = functionBody(
            "private fun decodeStrictExactPageBytes(",
            sessionSource,
        )
        val tiledDecode = functionBody(
            "private fun decodeStrictExactResidentTiles(",
            sessionSource,
        )
        val request = functionBody(
            "private fun requestStrictExactSourcePage(",
            sessionSource,
        )

        assertTrue(residentDecode.contains("ReaderExactDecodeStoragePolicy.useSharedFullPageBitmap("))
        assertTrue(residentDecode.contains("decodeStrictExactResidentTiles("))
        assertTrue(residentDecode.contains("parallelOversizedResidentTiles"))
        assertTrue(residentDecode.contains("decodeStrictExactResidentTilesParallel("))
        assertTrue(tiledDecode.contains("BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)"))
        assertTrue(tiledDecode.contains("inSampleSize = 1"))
        assertTrue(request.contains("waitForOversizedDirectWifiAnchor"))
        assertTrue(request.contains("parallelOversizedDirectWifiAnchor"))
        assertTrue(
            request.indexOf("if (parallelOversizedDirectWifiAnchor)") <
                request.indexOf("Process.THREAD_PRIORITY_DEFAULT"),
        )
        assertTrue(request.contains("strictExactInitialAnchorPixelsInstalled.await("))
        val parallelDecode = functionBody(
            "private fun decodeStrictExactResidentTilesParallel(",
            sessionSource,
        )
        assertTrue(parallelDecode.contains("BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)"))
        assertTrue(parallelDecode.contains("STRICT_EXACT_ANCHOR_TILE_DECODE_PARALLELISM"))
        assertTrue(parallelDecode.contains("slots.filterNotNull()"))
        assertTrue(sessionSource.contains("strictExactInitialAnchorPixelsInstalled.countDown()"))
    }

    @Test
    fun completedDrawListenerYieldsMainAfterEverySemanticDelivery() {
        val dispatch = functionBody("private fun drainCompletedDrawDispatch()")

        assertTrue(source.contains("MAX_COMPLETED_DRAW_DELIVERIES_PER_RUN = 1"))
        assertTrue(dispatch.contains("Trace.beginSection(\"ViewerCompletedDrawListener\")"))
        assertTrue(dispatch.contains("completedDrawDispatchQueue.finishRun("))
        assertTrue(dispatch.contains("mainHandler.post(completedDrawDispatchRunnable)"))
    }

    @Test
    fun activeInputImageCompletionsUseTheBoundedDeliveryDrain() {
        val decode = functionBody("private fun postDecodeResult(", sessionSource)

        assertTrue(decode.contains("physicalTouchActive.get() || viewportBusy.get()"))
        assertTrue(decode.contains("deliveryQueue.add(currentDelivery.copy(retainWhenBusy = true))"))
        assertTrue(decode.contains("scheduleDeliveryDrain()"))
        assertFalse(decode.contains("prioritizeStrictPhysicalRunway"))
    }

    @Test
    fun continuousEpisodeLabelsReuseTheStableOrderedEpisodeSnapshot() {
        val source = activitySource
        val stable = functionBody("private fun stableEpisodeSnapshot(", source)
        val result = functionBody("private fun updateResultEpisode(", source)
        val display = functionBody("private fun displayEpisodeTitle(", source)

        assertTrue(stable.contains("owner === stableEpisodeSnapshotOwner"))
        assertTrue(stable.contains("count == stableEpisodeSnapshotCount"))
        assertTrue(stable.contains("return stableEpisodeSnapshot"))
        assertTrue(result.contains("stableEpisodeSnapshot(manga, title)"))
        assertTrue(display.contains("stableEpisodeSnapshot(manga, title)"))
        assertFalse(result.contains("Utils.snapshotEpisodes"))
        assertFalse(display.contains("Utils.snapshotEpisodes"))
    }

    @Test
    fun continuousProgressDoesNotAttachTheWholeEpisodeGraphOnTheInputThread() {
        val progress = functionBody("private fun saveReadingProgressNow(", activitySource)

        assertTrue(progress.contains("stableEpisodeSnapshot(info.manga, title)"))
        assertTrue(progress.contains("info.manga.attachSeriesMetadata(title)"))
        assertTrue(progress.contains(
            "p?.setBookmark(title, progressEpisodeId, episodeIndex, episodeCount)",
        ))
        assertFalse(progress.contains("p?.addRecent(title)"))
        assertFalse(progress.contains("info.manga.setAuthoritativelyOrderedEps("))
        assertFalse(progress.contains("Utils.snapshotEpisodes"))
    }

    @Test
    fun hostGpuResidentExactPagesUseImmutableSharedMemoryWithoutChangingPhysicalDecode() {
        val decode = functionBody(
            "private fun decodeExactFullPageBitmap(\n        bytes: ByteArray,",
            sessionSource,
        )

        assertTrue(decode.contains("if (hostGpuEmulatorRuntime &&"))
        assertTrue(decode.contains("ImageDecoder.createSource(ByteBuffer.wrap(bytes))"))
        assertTrue(decode.contains("ImageDecoder.ALLOCATOR_SHARED_MEMORY"))
        assertTrue(decode.contains("decoder.isMutableRequired = false"))
        assertTrue(decode.contains("decoder.setTargetSize(sourceWidth, sourceHeight)"))
        assertTrue(decode.contains("BitmapFactory.decodeByteArray("))
        assertTrue(
            decode.indexOf("ImageDecoder.ALLOCATOR_SHARED_MEMORY") <
                decode.indexOf("BitmapFactory.decodeByteArray("),
        )
    }

    @Test
    fun directWifiEpisodeKeepsOnlyAnExactForwardPixelWindow() {
        val strictWindow = functionBody(
            "private fun requestStrictExactColdWindow(",
            sessionSource,
        )
        val rollingTrim = functionBody(
            "private fun trimDirectWifiLaunchPixelsOutsideWindow(",
            sessionSource,
        )
        val release = functionBody("private fun postBitmapReleases(", sessionSource)
        val releaseState = functionBody(
            "private fun clearReleasedPageStateIfStillUndelivered(",
            sessionSource,
        )
        val budgetTrim = functionBody("private fun trimDeliveredBudgetLocked(", sessionSource)
        val pressureTrim = functionBody(
            "private fun trimRetainedBitmapUnderPressureLocked(",
            sessionSource,
        )
        val windowChanged = functionBody("override fun onWindowChanged(", activitySource)
        val pageCleared = functionBody("override fun onPageCleared(", activitySource)
        val rollingEvicted = functionBody("override fun onPageRollingEvicted(", activitySource)
        val hostPressureRollingEvicted = functionBody(
            "override fun onPageHostPressureRollingEvicted(",
            activitySource,
        )
        val retireAcceptedIdentity = functionBody(
            "private fun retireAcceptedStrictAuthoritativeIdentity(",
            activitySource,
        )
        val completedDraw = functionBody(
            "private fun handleStrictRollingCompletedDraw(",
            activitySource,
        )
        val nativePrewarm = functionBody("private fun flushResidentNativeTexturePrewarm(")

        assertTrue(sessionSource.contains("isCurrentDirectWifiRendererProfile("))
        assertTrue(strictWindow.contains("admission.allowedFirstSource"))
        assertTrue(sessionSource.contains("StrictRollingAdmission.REVERSE_PREDECESSOR_SOURCE_COUNT"))
        assertTrue(sessionSource.contains("forwardRetainAheadPages("))
        assertTrue(sessionSource.contains("trimDirectWifiLaunchPixelsOutsideWindow()"))
        assertTrue(rollingTrim.contains("isStrictExactLaunchPage(page)"))
        assertTrue(rollingTrim.contains("entry.key in keepFirst..keepLast"))
        assertTrue(rollingTrim.contains("preserveStrictReady = true"))
        assertTrue(release.contains("pagePreserves.getValue(page)"))
        assertTrue(releaseState.contains("if (!preserveStrictReady)"))
        assertTrue(windowChanged.contains("forwardRequestStartPage()"))
        assertTrue(
            windowChanged.contains("directWifiRollingForwardRequestStartPage(")
        )
        assertTrue(windowChanged.contains("maxOf(\n                    requestFirstPage,"))
        assertTrue(windowChanged.contains("forwardRequestEndPage("))
        assertTrue(windowChanged.contains("NTK_DIRECT_WIFI_SHORT_WEBTOON_FORWARD_VIEWPORTS"))
        assertTrue(pageCleared.contains("retireAcceptedStrictAuthoritativeIdentity(index)"))
        assertTrue(
            pageCleared.indexOf("retireAcceptedStrictAuthoritativeIdentity(index)") <
                pageCleared.indexOf("renderView.clearPageBitmap(index)")
        )
        assertTrue(rollingEvicted.contains("retireAcceptedStrictAuthoritativeIdentity(index)"))
        assertTrue(
            retireAcceptedIdentity.contains("acceptedStrictAuthoritativeIdentities.remove(index)")
        )
        assertTrue(retireAcceptedIdentity.contains("pendingStrictAuthoritativeInstalls[index]"))
        assertTrue(
            retireAcceptedIdentity.contains(
                "acceptedStrictAuthoritativeIdentities[index] = pending.identity"
            )
        )
        assertFalse(retireAcceptedIdentity.contains("pendingStrictAuthoritativeInstalls.remove(index)"))
        assertTrue(
            hostPressureRollingEvicted.contains(
                "pendingStrictAuthoritativeInstalls.remove(index)"
            )
        )
        assertTrue(
            hostPressureRollingEvicted.indexOf(
                "pendingStrictAuthoritativeInstalls.remove(index)"
            ) < hostPressureRollingEvicted.indexOf("renderView.clearRollingAuthoritativePage(index)")
        )
        assertTrue(hostPressureRollingEvicted.contains("renderView.retireSurfaceOwnedBitmaps("))
        assertTrue(release.contains("target.hostPressureRetirement"))
        assertTrue(release.contains("listener.onPageHostPressureRollingEvicted(index)"))
        assertTrue(rollingEvicted.contains("forwardRequestStartPage()"))
        assertTrue(rollingEvicted.contains("requestWindowAsync(first, last, first, false)"))
        assertFalse(completedDraw.contains("reader_ntk_strict_handle_enter"))
        assertTrue(completedDraw.contains("currentTraversalStructureEpoch()"))
        assertFalse(completedDraw.contains("traversalSnapshot()"))
        assertTrue(nativePrewarm.contains("shortWebtoonPixelWindow"))
        assertTrue(nativePrewarm.contains("appendPixelWindowTile("))
        assertTrue(budgetTrim.contains("strictLaunchIndexes"))
        assertFalse(budgetTrim.contains("synchronized(pagesLock)"))
        assertFalse(budgetTrim.contains("0 until launchDisplayLimit"))
        assertTrue(pressureTrim.contains("strictLaunchIndexes"))
        assertFalse(pressureTrim.contains("synchronized(pagesLock)"))
        assertTrue(sessionSource.contains("private fun strictExactLaunchDisplayIndexesLocked()"))
        val resumeStart = functionBody(
            "fun directWifiRollingForwardRequestStartPage(",
            sessionSource,
        )
        assertTrue(resumeStart.contains("!isStrictExactLaunchPage(first)"))
        assertTrue(resumeStart.contains("directionHint < 0"))
        assertTrue(resumeStart.contains("strictActiveSourceFloor.get()"))
        assertTrue(resumeStart.contains("page.sourceIndex >= activeFloor"))
    }

    @Test
    fun sameStrictAdmissionRehydratesOnlyTheCurrentPhysicalDisplayWindow() {
        val strictWindow = functionBody(
            "private fun requestStrictExactColdWindow(",
            sessionSource,
        )
        val pixelDemand = functionBody(
            "private fun finishStrictExactColdWindowDemand(",
            sessionSource,
        )
        val exactRequest = functionBody(
            "private fun requestStrictExactSourcePage(",
            sessionSource,
        )
        val releases = functionBody("private fun postBitmapReleases(", sessionSource)
        val removeState = functionBody("private fun removePageStateRange(", sessionSource)
        val clearState = functionBody("private fun clearPageStateFromIndex(", sessionSource)
        val unchangedStart = strictWindow.indexOf("if (admission === previous) {")
        val unchangedEnd = strictWindow.indexOf(
            "val generation = if (sourceDemandChanged)",
            unchangedStart,
        )
        assertTrue(unchangedStart >= 0)
        assertTrue(unchangedEnd > unchangedStart)
        val unchanged = strictWindow.substring(unchangedStart, unchangedEnd)

        assertTrue(unchanged.contains("admission.physicalDrawPresented"))
        assertTrue(unchanged.contains("strictExactRollingPixelResidency.get()"))
        assertTrue(unchanged.contains("finishStrictExactColdWindowDemand("))
        assertFalse(unchanged.contains("applyStrictExactSourceDemand("))
        assertFalse(unchanged.contains("windowGeneration.incrementAndGet()"))
        assertTrue(strictWindow.contains("if (sourceDemandChanged) {"))
        assertTrue(pixelDemand.contains("if (sourceDemandChanged) applyStrictExactSourceDemand(admission)"))
        assertTrue(strictWindow.contains("if (strictExactRollingPixelResidency.get())"))
        assertTrue(strictWindow.contains("visibleFirst..visibleLast"))
        assertTrue(pixelDemand.contains("windowOrder(demandFirst, demandLast, safeAnchor, direction)"))
        assertTrue(pixelDemand.contains("visibleFirst"))
        assertTrue(pixelDemand.contains("visibleLast"))
        assertTrue(pixelDemand.contains("hostExactPoolPressureRetiredPages.contains(index)"))
        assertTrue(pixelDemand.contains("if (!isStrictExactColdPageDemanded(index))"))
        assertTrue(pixelDemand.contains("listener.isPageAuthoritativeDrawableInstalled(index)"))
        assertTrue(pixelDemand.contains("hostExactPoolPressureRetiredPages.remove(index)"))
        assertTrue(pixelDemand.contains("requestStrictExactSourcePage("))
        assertTrue(releases.contains("strictExactRollingRehydratePages.addAll(rollingEvictedPages)"))
        assertTrue(
            removeState.contains(
                "shiftConcurrentSetAfterRemoval(strictExactRollingRehydratePages"
            )
        )
        assertTrue(
            clearState.contains("clearConcurrentSetFromIndex(strictExactRollingRehydratePages")
        )
        assertTrue(exactRequest.contains("listener.isPageAuthoritativeDrawableCurrentlyInstalled(index)"))
        assertTrue(exactRequest.contains("strictExactAuthoritativeHandoffPages.remove(index)"))
    }

    @Test
    fun tailOnlyStateCleanupPreservesThePublishedViewportGeneration() {
        val clearState = functionBody("private fun clearPageStateFromIndex(", sessionSource)
        val classificationAt = clearState.indexOf(
            "val clearsPublishedIndex = synchronized(pagesLock)",
        )
        val firstFenceAt = clearState.indexOf(
            "if (clearsPublishedIndex) indexedStateGeneration.incrementAndGet()",
            classificationAt,
        )
        val secondFenceAt = clearState.indexOf(
            "if (clearsPublishedIndex) indexedStateGeneration.incrementAndGet()",
            firstFenceAt + 1,
        )
        assertTrue(
            classificationAt >= 0 && firstFenceAt > classificationAt &&
                secondFenceAt > firstFenceAt,
        )
        assertFalse(clearState.contains("\n            indexedStateGeneration.incrementAndGet()"))
        assertTrue(clearState.contains("!appendOnlyTail && startIndex < pages.size"))
    }

    @Test
    fun adjacentWaitsUntilTheCompleteCurrentSceneIsQueuedToNative() {
        val markReady = functionBody("private fun markStrictInstalledPageReady(", activitySource)
        val queueReady = functionBody("private fun queueStrictAllImagesRenderReady(", activitySource)
        val boundary = functionBody(
            "private fun shouldStartNtkNextBoundaryImmediately(",
            activitySource,
        )

        assertTrue(markReady.contains("strictAllImagesReadyQueueScheduled = true"))
        assertTrue(markReady.contains("session?.markStrictAuthoritativeDrawableInstalled("))
        assertTrue(markReady.contains("reason=session_canonical_ack"))
        assertFalse(markReady.contains("strictAllImagesReadyPublished = true"))
        assertTrue(queueReady.contains("strictAllImagesReadyPublished = true"))
        assertTrue(queueReady.contains("renderView.queueResidentAuthoritativeTextureRunway("))
        assertTrue(queueReady.indexOf("strictAllImagesReadyPublished = true") <
            queueReady.indexOf("resolveListedForwardAdjacentAfterCurrentComplete("))
        assertTrue(queueReady.indexOf("resolveListedForwardAdjacentAfterCurrentComplete(") <
            queueReady.indexOf("restorePersistedDirectWifiStrictNextAfterCurrentComplete("))
        assertTrue(queueReady.indexOf("restorePersistedDirectWifiStrictNextAfterCurrentComplete(") <
            queueReady.indexOf("session?.prepareForwardAdjacentAfterCurrentComplete("))
        assertTrue(queueReady.contains("cachedNextHasPersistedExactAuthority = true"))
        assertTrue(
            queueReady.contains(
                "persistedExactAdjacentAuthority = cachedNextHasPersistedExactAuthority"
            )
        )
        assertTrue(boundary.contains("session?.canPrepareForwardAdjacentNow("))
        assertTrue(boundary.contains("return isCurrentNtkManhwaOrWebtoonPath() &&"))

        val persistedRestore = functionBody(
            "private fun restorePersistedDirectWifiStrictNextAfterCurrentComplete(",
            activitySource,
        )
        assertTrue(persistedRestore.contains("if (!strictAllImagesReadyPublished) return null"))
        assertTrue(persistedRestore.contains("client.isNtkWifiTransportActive"))
        assertTrue(persistedRestore.contains("client.isNtkCellularResilientTransportActive"))
        assertFalse(persistedRestore.contains("startStrictNtkDiscovery("))
        assertFalse(persistedRestore.contains("primeAdjacentLaunchWindow("))
        assertFalse(persistedRestore.contains("fetchEpisodesForeground("))

        val listedRestore = functionBody(
            "private fun resolveListedForwardAdjacentAfterCurrentComplete(",
            activitySource,
        )
        assertTrue(listedRestore.contains("if (!strictAllImagesReadyPublished) return null"))
        assertTrue(listedRestore.contains("normalizedSource != normalizedPredecessor"))
        assertTrue(listedRestore.contains("ViewerEpisodeResolver.episodeListFor("))
        assertTrue(listedRestore.contains("attachEpisodeList(currentEpisodeTitle, source, episodes)"))
        assertTrue(listedRestore.contains("adjacentEpisodeFastPrepared("))
        assertFalse(listedRestore.contains("fetchEpisodesForeground("))
        assertFalse(listedRestore.contains("startStrictNtkDiscovery("))
    }

    @Test
    fun surfaceInstallAckReleasesTheInitialExactDecodeRunway() {
        val canonicalAck = functionBody(
            "fun markStrictAuthoritativeDrawableInstalled(",
            sessionSource,
        )

        assertTrue(canonicalAck.contains("markCanonicalDrawableCompletion(index, page)"))
        assertTrue(
            canonicalAck.contains(
                "installedInitialAnchor = completed && index == currentStartPage()"
            )
        )
        assertTrue(canonicalAck.contains("strictExactInitialAnchorPixelsInstalled.countDown()"))
        assertTrue(
            canonicalAck.indexOf("markCanonicalDrawableCompletion(index, page)") <
                canonicalAck.indexOf("strictExactInitialAnchorPixelsInstalled.countDown()")
        )
    }

    @Test
    fun directWifiWebtoonInitialAdjacentRunwayUsesBoundedInnerTileDecode() {
        val batch = functionBody(
            "private fun prepareAdjacentRunwayDrawableBatch(",
            sessionSource
        )
        val delivery = functionBody(
            "private fun prepareAdjacentRunwayDelivery(",
            sessionSource,
        )
        val tilePolicy = functionBody(
            "private fun shouldParallelDecodeDirectWifiAdjacentResidentTiles(",
            sessionSource,
        )

        assertTrue(batch.contains("reason == \"initial_strict_source\""))
        assertTrue(batch.contains("tasks.forEach(strictExactOverlapDecode::execute)"))
        assertTrue(
            delivery.contains("shouldParallelDecodeDirectWifiAdjacentResidentTiles(page)")
        )
        assertTrue(
            tilePolicy.contains("0 until initialAdjacentRunwayPageLimit(")
        )
        assertTrue(tilePolicy.contains("path.startsWith(\"/webtoon/\")"))
        assertTrue(tilePolicy.contains("isDirectWifiStrictAdjacentRunwayProfile(page.manga)"))

        val publishBound = functionBody(
            "private fun remainingAdjacentRunwayPublishPages(",
            sessionSource
        )
        assertTrue(publishBound.contains("isDirectWifiStrictAdjacentRunwayProfile(target)"))
        assertTrue(publishBound.contains("installedAdjacentRunwaySourceIndexes(target).size"))
        assertTrue(publishBound.contains("installedSourceCount in 1 until requiredInitialRunway"))
        assertTrue(publishBound.contains(".startsWith(\"/manhwa/\")"))
        assertTrue(publishBound.contains("nextSourceRefCount.coerceAtLeast(1)"))
        assertTrue(publishBound.contains("hostExactNativeSurfaceStorageEnabled"))
        assertTrue(
            sessionSource.contains(
                "HostExactHardwareTilePool.supported(hostGpuEmulatorRuntime)",
            ),
        )
        assertTrue(
            sessionSource.contains("!NtkNativeSurfaceFrameRatePolicy.isHwuiOverrideEnabled()"),
        )
        assertTrue(
            Regex("nextSourceRefCount\\.coerceAtLeast\\(1\\)")
                .findAll(publishBound)
                .count() >= 2,
        )
        assertTrue(publishBound.contains("atomicTailReadyRefCount"))
        assertFalse(publishBound.contains("installedDrawablePageCountForEpisode(target)"))
        val installedSources = functionBody(
            "private fun installedAdjacentRunwaySourceIndexes(",
            sessionSource,
        )
        assertTrue(installedSources.contains("hasListenerDrawableDelivery(page.pageIndex) ||"))
        assertTrue(installedSources.contains("hasDeliveredOrPendingDrawable(page.pageIndex)"))
        assertTrue(
            publishBound.indexOf("isActiveGeneratedTouchOrQuiet() || viewportBusy.get()") <
                publishBound.indexOf("isInitialTailAdjacentPreappendTarget(target)")
        )
        assertTrue(
            source.contains(
                "HOST_GPU_DIRECT_WIFI_NATIVE_PREWARM_AHEAD_PAGES = 4"
            )
        )

        val retryBound = functionBody(
            "private fun remainingAdjacentRunwayAppendMinRetryMs(",
            sessionSource
        )
        assertTrue(retryBound.contains("isDirectWifiStrictAdjacentRunwayProfile(target)"))
        assertTrue(retryBound.contains("installedAdjacentRunwaySourceIndexes(target).size"))
        assertTrue(retryBound.contains("installedSourceCount in 1 until requiredInitialRunway"))
        assertTrue(retryBound.contains("!viewportBusy.get()"))
        assertTrue(
            retryBound.contains(
                "physicalTouchQuietRemainingMs(" +
                    "NTK_APPEND_REMAINING_RUNWAY_PHYSICAL_QUIET_MS"
            )
        )
        assertTrue(retryBound.contains("return NTK_ADJACENT_FOREGROUND_STREAM_RECHECK_MS"))
    }

    @Test
    fun strictSourceReceivesDirectWifiIdentityIndependentlyFromOptionalQuicBulk() {
        val registrySource = File(
            "src/main/java/ml/melun/mangaview/reader/NtkSourceSpoolRegistry.kt"
        ).readText()
        val construction = functionBody("val session = try {", registrySource)

        assertTrue(construction.contains("directWifiTransport = directWifiTransport"))
        assertTrue(construction.contains("wifiQuicBulkTransport ="))
        assertTrue(registrySource.contains("val adjacentPrefetch = directWifiTransport"))
        assertTrue(registrySource.contains("currentForegroundViewerGeneration == 0L"))
        assertTrue(construction.contains("adjacentPrefetch = adjacentPrefetch"))
        assertTrue(
            File("src/main/java/ml/melun/mangaview/reader/NtkStrictSourceSession.kt")
                .readText()
                .contains("directWifiTransport = directWifiTransport && adjacentPrefetch")
        )
    }

    @Test
    fun blockedLegacySingleOriginUsesCancelableExactQuicWithoutChangingAssetIdentity() {
        val execute = functionBody(
            "override fun execute(): Response",
            imageCacheSource
        )
        val legacyRecovery = functionBody(
            "private fun executeExactQuicLegacyImageRecovery(",
            imageCacheSource
        )
        val recovery = functionBody(
            "private fun executeExactQuicImageRecovery(",
            imageCacheSource
        )
        val fragmentedRecovery = functionBody(
            "private fun executeExactFragmentedLegacyImageRecovery(",
            imageCacheSource
        )
        val callFactory = functionBody(
            "override fun newCall(request: Request): Call",
            imageCacheSource
        )

        assertTrue(execute.contains("shouldTryExactQuicLegacyImageRecovery(physicalCandidate)"))
        assertTrue(imageCacheSource.contains("NTK_LEGACY_BLOCKED_IMAGE_ROOT = \"webimg7.com\""))
        assertTrue(callFactory.contains("isLegacyBlockedImageRequest(request)"))
        assertTrue(legacyRecovery.contains("executeExactQuicImageRecovery("))
        assertTrue(recovery.contains("request.url.toString()"))
        assertTrue(recovery.contains(".request(recoveryRequest)"))
        assertTrue(recovery.contains("NtkExactImagePhysicalAttempt(recoveryAttempt)"))
        assertTrue(recovery.contains("result.bodyBytes"))
        assertTrue(recovery.contains("activeExactQuicRecovery.compareAndSet"))
        assertFalse(recovery.contains("workId"))
        assertTrue(quicFetcherSource.contains("class CancelableExactRequest"))
        assertTrue(quicFetcherSource.contains("fetchExactOwned("))
        assertTrue(quicFetcherSource.contains("fetchWithEngineExactOwned("))
        assertTrue(quicFetcherSource.contains("request.cancel()"))
        assertTrue(fragmentedRecovery.contains("LocalWebViewProxy.start()"))
        assertTrue(fragmentedRecovery.contains("Proxy.Type.HTTP"))
        assertTrue(fragmentedRecovery.contains("InetSocketAddress(\"127.0.0.1\", proxy.port())"))
        assertTrue(fragmentedRecovery.contains(".followRedirects(false)"))
        assertTrue(fragmentedRecovery.contains("for (candidateRequest in listOf(request))"))
        assertTrue(fragmentedRecovery.contains("response.request.url == wireCandidateRequest.url"))
        assertTrue(fragmentedRecovery.contains(".removeHeader(\"Referer\")"))
        assertTrue(fragmentedRecovery.contains(".removeHeader(\"Cookie\")"))
        assertTrue(fragmentedRecovery.contains("looksLikeImage(bodyBytes)"))
        assertFalse(fragmentedRecovery.contains("workId"))
    }

    @Test
    fun physicalDragUsesOnlyRealMotionEventPositions() {
        val move = functionBody("private fun applyPhysicalDragPositionLocked(")
        val directFrame = functionBody("private fun renderDirectSurfaceFrame(")

        assertTrue(
            move.contains(
                "val moved = applyDragOffsetLocked(requestedOffset, physicalRequestedOffset)",
            ),
        )
        assertFalse(source.contains("applyDragResamplingAtFrameLocked"))
        assertFalse(source.contains("nextDragResampleOffset"))
        assertFalse(source.contains("DRAG_RESAMPLE_"))
        assertFalse(directFrame.contains("applyDragOffsetLocked("))
    }

    @Test
    fun physicalInputCancelsOnlyRedundantAdjacentMetadataWork() {
        val touch = functionBody("fun notePhysicalTouch(", sessionSource)
        val cancel = functionBody(
            "private fun cancelRedundantInitialAdjacentMetadataForPhysicalInput(",
            sessionSource
        )
        val fetch = functionBody(
            "private fun loadAuthoritativeAdjacentUrlsForPrefetch(",
            sessionSource
        )

        assertTrue(touch.contains("if (active) cancelRedundantInitialAdjacentMetadataForPhysicalInput()"))
        assertTrue(cancel.contains("hasForwardNtkEpisodeAfterSource(activeFetch.source)"))
        assertTrue(cancel.contains("activeFetch.cancellation.cancel()"))
        assertFalse(cancel.contains("repositoryCancellations"))
        assertTrue(fetch.contains("activeInitialAdjacentMetadataFetches.put"))
        assertTrue(fetch.contains("shouldYieldInitialAdjacentMetadata(metadataSource)"))
        assertTrue(fetch.contains("activeInitialAdjacentMetadataFetches.remove"))
    }

    @Test
    fun adjacentRemainderPromotesAfterTheRealAnchorCrossesItsPublishedBoundary() {
        val inside = functionBody("private fun isViewportInsideEpisode(", sessionSource)
        val defer = functionBody(
            "private fun shouldDeferRemainingAdjacentRunwayForActiveInput(",
            sessionSource
        )

        assertTrue(inside.contains("val boundedAnchor ="))
        assertTrue(inside.contains("val firstIncomingIndex = pages.indexOfFirst"))
        assertTrue(inside.contains("firstIncomingIndex >= 0 && boundedAnchor >= firstIncomingIndex"))
        assertFalse(inside.contains("currentViewportAnchor.incrementAndGet"))
        assertFalse(inside.contains("currentViewportAnchor.set("))
        assertTrue(defer.contains("if (isViewportInsideEpisode(target))"))
        assertTrue(defer.contains("NtkReaderTransferPacer.isPhysicalMotionActive()"))
        assertTrue(defer.contains("physicalTouchQuietRemainingMs("))
        assertTrue(
            defer.indexOf("if (isViewportInsideEpisode(target))") <
                defer.indexOf(
                    "if (NtkReaderTransferPacer.isPhysicalMotionActive() || viewportBusy.get()) return true",
                )
        )
        assertTrue(defer.contains("installedAdjacentRunwaySourceIndexes(target).size"))
        assertFalse(defer.contains("installedDrawablePageCountForEpisode(target)"))
    }

    @Test
    fun aCompletedBoundaryKeepsRevealOwnershipUntilTheForeignEpisodeIsPublished() {
        val boundary = functionBody("override fun onBoundaryReached(", activitySource)
        val appended = functionBody("override fun onPagesAppended(", activitySource)
        val preparedAppend = functionBody(
            "override fun onPreparedAdjacentPagesAppended(",
            activitySource,
        )
        val finished = functionBody("override fun onBoundaryAppendFinished(", activitySource)

        assertTrue(boundary.contains("pendingNextBoundaryReveal = true"))
        assertTrue(boundary.contains("pendingNextBoundaryRevealPredecessorKey ="))
        assertTrue(boundary.contains("Manga.episodeIdentityKey(it.manga)"))
        assertTrue(appended.contains("appendedForeignEpisodeStartsAt(oldCount)"))
        assertTrue(appended.contains("pendingNextBoundaryRevealPredecessorKey"))
        assertTrue(appended.contains("shouldRevealCompletedNtkBoundaryGrowth("))
        assertTrue(appended.contains("revealAppendedBoundary = revealCompletedBoundary"))
        assertTrue(appended.contains("if (appendedForeignEpisode && pendingNextBoundaryReveal)"))
        assertTrue(appended.contains("preparedAdjacentAppendPublicationDepth == 0"))
        assertTrue(preparedAppend.contains("preparedAdjacentAppendPublicationDepth++"))
        assertTrue(preparedAppend.contains("onPagesAppended(count)"))
        // Worker completion is not structural publication. Clearing here recreated the exact
        // lost-wakeup where the reader stayed clamped on the old tail until a second gesture.
        assertFalse(finished.contains("clearPendingNextBoundaryReveal()"))
    }

    @Test
    fun everyNtkNetworkHasNoPreCompletionOrPreviousAutoFetchEntry() {
        val prepare = functionBody("fun prepareAdjacentEpisode(", sessionSource)
        val append = functionBody("fun appendAdjacentEpisode(", sessionSource)
        val unavailable = functionBody(
            "private fun resolveInitialNtkUnavailableEpisode(",
            sessionSource
        )
        val runway = functionBody(
            "private fun shouldUseAdjacentInitialAppendRunway(",
            sessionSource
        )
        val prime = functionBody(
            "private fun primeAdjacentLaunchWindow(",
            activitySource
        )
        val completion = functionBody(
            "private fun maybeWarmCompletedForwardEpisode(",
            sessionSource,
        )

        assertTrue(prepare.contains("reader_adjacent_previous_auto_prepare_disabled"))
        assertTrue(
            prepare.contains(
                "!canPrepareForwardAdjacentNow(pageRef(anchor)?.manga?.ntkEpisodePath)"
            )
        )
        assertTrue(prepare.contains("isNtkContinuousAdjacentCompletionPolicyActive()"))
        assertFalse(prepare.contains("isDirectWifiStrictAdjacentTransportActive()"))
        assertTrue(append.contains("reader_adjacent_previous_auto_append_disabled"))
        assertTrue(append.contains("append_adjacent_wait_current_complete"))
        assertTrue(
            append.contains(
                "!canPrepareForwardAdjacentNow(pageRef(anchor)?.manga?.ntkEpisodePath)"
            )
        )
        assertTrue(append.contains("isNtkContinuousAdjacentCompletionPolicyActive()"))
        assertFalse(append.contains("isDirectWifiStrictAdjacentTransportActive()"))
        assertTrue(
            prepare.indexOf("reader_adjacent_prepare_wait_current_complete") <
                prepare.indexOf("scheduleDeferredAdjacentPrepare(")
        )
        assertTrue(
            append.indexOf("append_adjacent_wait_current_complete") <
                append.indexOf("scheduleDeferredAdjacentPrepare(")
        )
        assertTrue(
            append.substring(
                append.indexOf("append_adjacent_wait_current_complete"),
                append.indexOf("// The launch episode remains sealed"),
            ).contains("return AppendStartResult.STARTED")
        )
        assertFalse(
            append.substring(
                append.indexOf("append_adjacent_wait_current_complete"),
                append.indexOf("// The launch episode remains sealed"),
            ).contains("return AppendStartResult.CANCELLED")
        )
        assertTrue(
            append.indexOf("append_adjacent_wait_current_complete") <
                append.indexOf("appendExecutor.execute")
        )
        assertTrue(completion.contains("deferredAdjacentPrepareMailbox.hasPending()"))
        assertTrue(completion.contains("deferredAdjacentPrepareMailbox.accelerate()"))
        assertTrue(append.contains("ViewerTelemetry.adjacentWorkStarted("))
        assertTrue(unavailable.contains("listOf(ReaderSurfaceView.DIRECTION_NEXT)"))
        assertFalse(unavailable.contains("ReaderSurfaceView.DIRECTION_PREVIOUS"))
        assertFalse(unavailable.contains("isNtkContinuousAdjacentCompletionPolicyActive()"))
        assertFalse(unavailable.contains("isDirectWifiStrictAdjacentTransportActive()"))
        assertTrue(runway.contains("urls.isEmpty()"))
        assertFalse(runway.contains("urls.size <= NTK_APPEND_INITIAL_RUNWAY_PAGES"))
        assertTrue(prime.contains("val activeSession = session ?: return"))
        assertTrue(
            prime.contains(
                "!activeSession.canPrepareForwardAdjacentNow(currentManga?.ntkEpisodePath)"
            )
        )
    }

    @Test
    fun exactForwardRunwayRacesHealthyReplicasOnlyAfterDirectWifiGrant() {
        val candidates = functionBody(
            "private fun initialGeneratedDirectRaceCandidates(",
            imageCacheSource
        )
        val gate = functionBody(
            "private fun isDirectWifiGrantedAdjacentGeneratedPath(",
            imageCacheSource
        )
        val transportGate = functionBody(
            "private fun isDirectWifiAdjacentTransportAllowed(",
            imageCacheSource
        )
        val replicaCandidates = functionBody(
            "private fun ntkDirectWifiAdjacentReplicaRaceCandidates(",
            imageCacheSource
        )
        val race = functionBody(
            "private fun requestDirectInitialGeneratedCandidateRaceUnshared(",
            imageCacheSource
        )
        val sharedRace = functionBody(
            "private fun requestDirectInitialGeneratedCandidateRace(",
            imageCacheSource
        )
        val exactResponse = functionBody(
            "private fun isExactNtkGeneratedPageResponse(",
            imageCacheSource
        )
        val strictAdjacentResponse = functionBody(
            "private fun isStrictDirectWifiAdjacentResponse(",
            imageCacheSource
        )

        assertTrue(
            candidates.contains(
                "directWifiExactAdjacent =\n            isDirectWifiGrantedAdjacentGeneratedPath(target.path)"
            )
        )
        assertTrue(candidates.contains("ntkDirectWifiAdjacentReplicaRaceCandidates(image)"))
        assertTrue(
            candidates.indexOf("ntkDirectWifiAdjacentReplicaRaceCandidates(image)") <
                candidates.indexOf("ntkGeneratedExtensionFallbacks(image)")
        )
        assertTrue(gate.contains("isDirectWifiAdjacentTransportAllowed(path)"))
        assertTrue(gate.contains("trustedNtkImageApiCount(path, minCreatedAtMs)"))
        assertTrue(gate.contains("hasAuthoritativeCompleteEarlyNtkImageUrls(path, trustedCount, minCreatedAtMs)"))
        assertTrue(transportGate.contains("hasActiveAdjacentNtkForegroundViewerGrant(path)"))
        assertTrue(transportGate.contains("httpClient.isNtkWifiTransportActive"))
        assertTrue(transportGate.contains("!httpClient.isNtkCellularResilientTransportActive()"))
        assertTrue(replicaCandidates.contains("addFirst(\"moamoabon.com\")"))
        assertTrue(replicaCandidates.contains("addFirst(\"aws-cdn1.site\", \"https\")"))
        assertTrue(replicaCandidates.contains("addFirst(\"aws-cdn1.site\", \"http\")"))
        assertTrue(replicaCandidates.contains("ntkDirectWifiAdjacentReplicaOrigins"))
        assertTrue(race.contains("if (directWifiAdjacentCandidate &&"))
        assertTrue(race.contains("!isDirectWifiAdjacentTransportAllowed(target.path)"))
        assertTrue(race.contains("response.close()"))
        assertTrue(race.contains("rememberDirectWifiAdjacentReplicaOrigin("))
        assertTrue(race.contains("result.second.request.url.toString()"))
        assertTrue(race.contains("isStrictDirectWifiAdjacentResponse(image, target, response)"))
        assertTrue(strictAdjacentResponse.contains("isExactNtkGeneratedPageResponse(target, response)"))
        assertTrue(sharedRace.contains("shouldUseDirectWifiAdjacentReplicaPath(target.path)"))
        assertTrue(sharedRace.indexOf("shouldUseDirectWifiAdjacentReplicaPath(target.path)") <
            sharedRace.indexOf("initialGeneratedDirectRaceFlightKey("))
        assertTrue(exactResponse.contains("actual.path.trimEnd('/').equals(expected.path.trimEnd('/')"))
    }

    @Test
    fun adjacentQualificationRequiresPhysicalP0ThroughP4WithoutOrderingThem() {
        val drive = functionBody(
            "private fun driveIntoExpectedAdjacentEpisode(",
            macrobenchmarkSource
        )
        val continuation = functionBody(
            "private fun driveThroughExpectedAdjacentRunway(",
            macrobenchmarkSource
        )
        val initialPublish = functionBody(
            "private fun appendResolvedEpisodeInitialRunway(",
            sessionSource
        )
        val remainingPublish = functionBody(
            "private fun appendRemainingAdjacentRunwayRefs(",
            sessionSource
        )
        val telemetryGate = functionBody(
            "private fun markExactAdjacentRunwayTelemetryIfReady(",
            sessionSource
        )

        assertTrue(drive.contains("maxExpectedSource = maxOf(maxExpectedSource, source)"))
        assertTrue(drive.contains("telemetryNanos(\"adjacentTotalPageCount\")"))
        assertTrue(continuation.contains("ADJACENT_REQUIRED_RUNWAY_PAGES"))
        assertTrue(drive.contains("require(expectedEpisodePath.isNotBlank())"))
        assertFalse(drive.contains("expectedEpisodePath.isBlank()"))
        assertTrue(drive.contains("telemetryNanos(\"adjacentRunwayPageCount\")"))
        assertTrue(drive.contains("if (proofUpdate.boundaryEnteredNow)"))
        assertTrue(drive.contains("continuousInput?.requestStop()"))
        assertTrue(drive.contains("runwayDrawableCount = proof.runwayDrawableCount"))
        assertTrue(drive.contains("if (maxExpectedSource >= 0 ||"))
        assertTrue(continuation.contains("if (update.physicalSourceObservedNow"))
        assertTrue(continuation.contains("if (update.complete)"))
        assertFalse(continuation.contains("source == ADJACENT_REQUIRED_RUNWAY_PAGES - 1"))
        assertFalse(continuation.contains("callbacks were not observed in forward physical order"))
        assertTrue(continuation.contains("proof.observedSourceIndices.joinToString()"))
        assertTrue(macrobenchmarkResumePlanSource.contains("actualSourceIndex == 0"))
        assertTrue(
            macrobenchmarkResumePlanSource.contains(
                "runwayDrawableCount == requiredRunwayPageCount"
            )
        )
        assertTrue(
            macrobenchmarkResumePlanSource.contains(
                "adjacentRunwayTargetEpisode == expectedEpisodePath"
            )
        )
        assertTrue(macrobenchmarkResumePlanSource.contains("firstAdjacentActualAtNanos > 0L"))
        assertTrue(
            macrobenchmarkResumePlanSource.contains(
                "firstAdjacentActualEpisode == expectedEpisodePath"
            )
        )
        assertFalse(
            macrobenchmarkSource.contains("repeat(INITIAL_MODERATE_FORWARD_GESTURES)")
        )
        assertTrue(initialPublish.contains("markExactAdjacentRunwayTelemetryIfReady("))
        assertTrue(remainingPublish.contains("markExactAdjacentRunwayTelemetryIfReady(target)"))
        assertTrue(telemetryGate.contains("(0 until requiredPageCount).toSet()"))
        assertTrue(telemetryGate.contains("hasListenerDrawableDelivery(index, page)"))
        assertTrue(telemetryGate.contains("ViewerTelemetry.adjacentRunwayReady("))
        assertTrue(
            macrobenchmarkSource.contains(
                "require(expectedAdjacentPageCount >= ADJACENT_REQUIRED_RUNWAY_PAGES)"
            )
        )
        assertTrue(macrobenchmarkSource.contains("ADJACENT_REQUIRED_RUNWAY_PAGES = 5"))
        assertTrue(macrobenchmarkSource.contains("ADJACENT_PREPARED_RUNWAY_PAGES = 5"))
        assertFalse(
            macrobenchmarkSource.contains(
                "adjacentTotalPageCount == expectedAdjacentPageCount"
            )
        )
        assertTrue(
            manifestEvidenceParserSource.contains(
                "path=\$episodePath,sourceSlots=\${images.length()}"
            )
        )
        assertTrue(
            qualificationSource.contains("function Get-AdjacentPageCountReconciliation")
        )
        assertTrue(
            qualificationSource.contains(
                "\$adjacentPageCountProof.reconciled -ne \$true"
            )
        )
        assertTrue(macrobenchmarkSource.contains("runwayReadyBeforeTail ="))
        assertTrue(macrobenchmarkSource.contains("adjacentP0SeamMs ="))
        assertFalse(macrobenchmarkSource.contains("check(adjacentP0SeamMs <="))
        assertTrue(macrobenchmarkSource.contains("p0IpcContinuousInputPreserved"))
        assertTrue(macrobenchmarkSource.contains("val boundaryGestures = sourceCheckpoint"))
        assertTrue(macrobenchmarkSource.contains("gestures = boundaryGestures"))
        assertFalse(macrobenchmarkSource.contains("check(runwayReadyBeforeTail)"))
        assertFalse(macrobenchmarkSource.contains("ADJACENT_BOUNDARY_WAIT_SLA_MS"))
        assertTrue(qualificationSource.contains("\$requiredAdjacentRunwayPages = 5"))
        assertTrue(qualificationSource.contains("\$requiredAdjacentPhysicalPages = 5"))
        assertTrue(qualificationSource.contains("\$expectedAdjacentPageCount -lt \$requiredAdjacentPhysicalPages"))
        assertTrue(qualificationSource.contains("\"adjacentObservedRunwayDrawableCount\""))
        assertTrue(qualificationSource.contains("\"runwayReadyBeforeTail\""))
        assertTrue(
            qualificationSource.contains(
                "\$runwayReadyBeforeTail = Get-OptionalProperty \$macroResult \"runwayReadyBeforeTail\""
            )
        )
        assertFalse(qualificationSource.contains("if(\$runwayReadyBeforeTail -ne \$true"))
        assertFalse(qualificationSource.contains("\$ProductionMaxAdjacentP0SeamMs"))
        assertTrue(qualificationSource.contains("\$terminalResumeInitialViewportP0 ="))
        assertTrue(qualificationSource.contains("\$p0InputOrderValid ="))
        assertFalse(qualificationSource.contains("\$ProductionMaxAdjacentBoundaryWaitMs"))
        assertTrue(qualificationSource.contains("\$ProductionMinForwardGestures = 1"))
        assertTrue(
            qualificationSource.contains(
                "Get-OptionalProperty \$macroResult \"adjacentRunwayReadyAtNanos\""
            )
        )
    }

    @Test
    fun terminalDecodeExpansionIsGenerationOwnedAndCurrentWifiWebtoonOnly() {
        val allResidentStart = sessionSource.indexOf(
            "if (residentPageCount == launchSeal.pageCount)"
        )
        val allResidentEnd = sessionSource.indexOf(
            "// A terminal sweep makes the invariant explicit",
            startIndex = allResidentStart,
        )
        assertTrue(allResidentStart >= 0)
        assertTrue(allResidentEnd > allResidentStart)
        val allResident = sessionSource.substring(allResidentStart, allResidentEnd)

        assertTrue(sessionSource.contains("strictExactForegroundViewerGenerationAtCreation"))
        assertTrue(sessionSource.contains("ViewerTelemetry.isActiveViewer("))
        assertTrue(
            allResident.contains(
                "ViewerTelemetry.isActiveViewer("
            )
        )
        assertTrue(
            allResident.contains(
                "strictExactForegroundViewerGenerationAtCreation"
            )
        )
        assertFalse(allResident.contains("ViewerTelemetry.activeGeneration()"))
        assertFalse(allResident.contains("ViewerTelemetry.isActiveEpisode("))
        assertTrue(allResident.contains("val directWifiActive ="))
        assertTrue(allResident.contains("directWifi = directWifiActive"))
        assertTrue(allResident.contains("webtoon = strictWebtoon"))
        assertTrue(
            sessionSource.contains(
                "NtkStrictTerminalDecodePolicy.DIRECT_WIFI_CURRENT_WEBTOON_PARALLELISM"
            )
        )
    }

    @Test
    fun alternateWebtoonH3IsLiveWifiGenerationBoundAndUsesOnlyAnExistingSession() {
        val alternateStart = imageCacheSource.indexOf(
            "val liveDirectWifi ="
        )
        val alternateEnd = imageCacheSource.indexOf(
            "if (!shouldTryWifiManhwaPrimaryExactQuic(",
            startIndex = alternateStart,
        )
        assertTrue(alternateStart >= 0)
        assertTrue(alternateEnd > alternateStart)
        val alternate = imageCacheSource.substring(alternateStart, alternateEnd)

        assertTrue(alternate.contains("isNtkCellularResilientTransportActive()"))
        assertTrue(alternate.contains("isNtkWifiTransportActive()"))
        assertTrue(alternate.contains("ViewerTelemetry.activeGeneration() == viewerGeneration"))
        assertTrue(alternate.contains("wifiExactQuicSessionPool?.existingSession("))
        assertFalse(alternate.contains("wifiExactQuicSessionPool?.session("))
        assertTrue(alternate.contains("recheckCancellationAfterRegistration = true"))
        assertTrue(alternate.contains("admissionCheck ="))
        assertTrue(alternate.contains("explicitReplicaMissHosts.add("))
        assertTrue(alternate.contains("getNtkDirectWifiNetwork()?.networkHandle"))
        assertTrue(alternate.contains("sameDirectWifiNetwork"))
        assertTrue(imageCacheSource.contains("NtkQuicFetcher.newDirectWifiQuicSession("))
        assertTrue(quicFetcherSource.contains("setDefaultNetworkMigration("))
        assertTrue(quicFetcherSource.contains("setPathDegradationMigration("))
        assertTrue(quicFetcherSource.contains("setAllowNonDefaultNetworkUsage("))
        assertTrue(quicFetcherSource.contains("continuationCheck.getAsBoolean()"))
        assertTrue(quicFetcherSource.contains("cancelIfOwnerRetired(request)"))
    }

    @Test
    fun activeScrollStrictDecodeDefersHostSpeculationAndKeepsMobileManhwaGate() {
        val worker = functionBody("private fun requestStrictExactSourcePage(", sessionSource)

        assertTrue(worker.contains("shouldDeferHostOffscreenUntilQuiet("))
        assertTrue(worker.contains("!isCurrentLaunchBlockedForwardPage(index, page)"))
        assertTrue(worker.contains("isHostExactOffscreenDecodeInputProtected()"))
        assertTrue(worker.contains("Thread.sleep(16L)"))
        assertTrue(worker.contains("NtkReaderTransferPacer.isPhysicalMotionActive() ||"))
        assertTrue(worker.contains("viewportBusy.get()"))
        assertTrue(worker.contains("NtkStrictActiveScrollDecodePolicy.shouldShareVisibleDecodeGate("))
        assertTrue(worker.contains("hostSurfaceRuntime = hostGpuEmulatorRuntime"))
        assertTrue(worker.contains("physicalScrollEverStarted = physicalScrollEverStarted.get()"))
        assertTrue(worker.contains("directWifi = isDirectWifiStrictAdjacentTransportActive()"))
        assertTrue(worker.contains("currentForegroundEpisode = isStrictExactCurrentForegroundEpisode()"))
        assertTrue(worker.contains("activeInput = isActiveGeneratedInputOrQuietForDelivery()"))
        assertTrue(worker.contains("anchor = anchor"))
        assertTrue(worker.contains("?.startsWith(\"/manhwa/\", ignoreCase = true) == true"))
        assertTrue(worker.contains("activeVisibleDecodeGate.tryAcquire("))
        assertTrue(worker.contains("passiveMotionDecodeGate"))
        assertTrue(worker.contains("blockedMotionDecodeGate"))
        assertTrue(worker.contains("activeScrollDecodeGate = decodeGate"))
        assertTrue(worker.contains("activeScrollDecodeGate?.release()"))
    }

    @Test
    fun exactRollingViewportBlockerMovesItsSingleFlightTaskAheadOfOffscreenFifo() {
        val worker = functionBody("private fun requestStrictExactSourcePage(", sessionSource)
        val promotion = functionBody(
            "private fun promoteQueuedStrictExactViewportBlocker(",
            sessionSource,
        )
        val lazyExecutor = functionBody("private class LazySessionExecutor(", sessionSource)

        assertTrue(worker.contains("promoteQueuedStrictExactViewportBlocker(index, page)"))
        assertTrue(worker.contains("strictExactRollingQueuedDecodes[index] = decodeTask"))
        assertTrue(worker.contains("strictExactRollingQueuedDecodes.remove(index, decodeTask)"))
        assertTrue(worker.contains("promotedViewportBlocker ||"))
        assertTrue(promotion.contains("isCurrentLaunchBlockedForwardPage(index, page)"))
        assertTrue(promotion.contains("strictExactRollingDecode.removeQueued(task)"))
        assertTrue(promotion.contains("strictExactRollingQueuedDecodes.remove(index, task)"))
        assertTrue(promotion.contains("strictExactViewportBlockerDecode.execute(task)"))
        assertFalse(promotion.contains("requestStrictExactSourcePage("))
        assertTrue(lazyExecutor.contains("pool.queue.remove(command)"))
    }

    @Test
    fun directWifiForwardAdjacentEpisodeKeepsTheStructuralTransitionCard() {
        val refs = functionBody("private fun pageRefsForEpisode(", sessionSource)
        assertFalse(refs.contains("direct_wifi_forward_skip_transition_card"))
        assertTrue(refs.contains("add(PageRef(target, null, transitionTitle"))
        assertTrue(refs.contains("addAll(pageRefs)"))
    }

    @Test
    fun directWifiAdjacentRunwayPublishesResidentManhwaAtomicallyAndOtherwiseProgressively() {
        val runwayCount = functionBody(
            "private fun initialAdjacentAppendRunwayRefCount(\n" +
                "        refs: List<PageRef>,\n" +
                "        strictExactDescriptorOnly: Boolean,",
            sessionSource,
        )
        val runwayPreparation = functionBody(
            "private fun prepareInitialTailAdjacentRunway(",
            sessionSource,
        )
        val runwayPublication = functionBody(
            "private fun appendResolvedEpisodeInitialRunway(",
            sessionSource,
        )

        assertTrue(
            sessionSource.contains(
                "private const val NTK_DIRECT_WIFI_INITIAL_ATTACHED_RUNWAY_PAGES = 1"
            )
        )
        assertTrue(runwayCount.contains(
            "NtkDirectWifiAdjacentInitialAtomicRunwayPolicy.attachedImagePageCount("
        ))
        assertTrue(runwayCount.contains("normalizedPath.startsWith(\"/webtoon/\")"))
        assertTrue(runwayCount.contains("NTK_DIRECT_WIFI_INITIAL_ATTACHED_RUNWAY_PAGES"))
        assertTrue(runwayCount.contains("val requiredRunwaySources ="))
        assertTrue(runwayCount.contains("NtkAdjacentRunwaySourceCohortPolicy.leadingRefCount("))
        assertTrue(runwayCount.contains("allImageRefs.map { it.sourceIndex }"))
        assertFalse(runwayCount.contains(".take(initialRunwayPageLimit)"))
        assertTrue(runwayCount.contains("val publishable = if (strictExactDescriptorOnly)"))
        assertTrue(runwayCount.contains("imageRefs.map { strictAdjacentBodyDescriptor(it) != null }"))
        assertTrue(runwayCount.contains("sourceSides = imageRefs.map { it.side }"))
        assertTrue(runwayPreparation.contains("val initialDrawablePages = directWifiInitialAttachedRunwayPages(target)"))
        assertTrue(runwayPreparation.contains(".take(initialDrawablePages)"))
        assertTrue(runwayPreparation.contains("strictBodiesReady = refs.all"))
        assertTrue(runwayPreparation.contains("\"initial_strict_source\""))
        assertTrue(runwayPublication.contains("val minimumReadyRunwayCount = runwayRefs.size"))
        assertTrue(runwayPublication.contains("if (readyRunwayCount < minimumReadyRunwayCount)"))
        val exactAuthority = runwayPublication.indexOf(
            "val strictExactAuthority = strictExactInitialManhwaRunwayAuthority(target)"
        )
        val exactWait = runwayPublication.indexOf(
            "if (requiresStrictExactInitialManhwaRunway(target) && strictExactAuthority == null)"
        )
        val runwayDecision = runwayPublication.indexOf("initialAdjacentAppendRunwayRefCount(")
        assertTrue(exactAuthority >= 0)
        assertTrue(exactWait > exactAuthority)
        assertTrue(runwayDecision > exactWait)
        assertTrue(runwayPublication.contains("append_adjacent_exact_manhwa_wait_authority"))
        assertTrue(runwayPublication.contains("containsEpisodeForAppendLocked(target)"))
        assertTrue(
            runwayPublication.indexOf("containsEpisodeForAppendLocked(target)") <
                runwayPublication.indexOf("prepareAdjacentRunwayDrawableBatch(")
        )
        assertTrue(runwayPublication.contains("commitAdjacentRunwayDrawableBatch(drawableBatch)"))
    }

    @Test
    fun resumedRendererRunwayDoesNotRequireTheHistoricalPagePeekingAboveTheAnchor() {
        val queue = functionBody("fun queueResidentAuthoritativeTextureRunway(")

        assertTrue(activitySource.contains("renderView.queueResidentAuthoritativeTextureRunway("))
        assertTrue(activitySource.contains("strictForwardReadyFirstPage,"))
        assertTrue(queue.contains("minimumAuthoritativePage: Int"))
        assertTrue(
            queue.contains(
                "val requiredFirst = max(first, minimumAuthoritativePage.coerceIn(0, pages.lastIndex))"
            )
        )
        assertTrue(queue.contains("val requiredLast = max(requiredFirst, last)"))
        assertTrue(queue.contains("if ((requiredFirst..requiredLast).any"))
        assertTrue(queue.contains("onQueued.run()"))
    }

    @Test
    fun directWifiStrictResumeWaitsForSurfaceGeometryInsteadOfSelfTriggeringWindowCallbacks() {
        val window = functionBody("override fun onWindowChanged(", activitySource)
        val directWifiGate = window.indexOf(
            "!directWifiStrictEpisodeRestoreOwnedBySurface()",
        )
        val relock = window.indexOf("renderView.lockRestoredPageOffset(")
        val rerequest = window.indexOf("activeSession?.requestWindowAsync(", relock)
        assertTrue(directWifiGate >= 0)
        assertTrue(relock > directWifiGate)
        assertTrue(rerequest > directWifiGate)
        val restoreStart = window.indexOf("activeInitialRestorePage >= 0")
        val restoreEnd = window.indexOf("if (activeInitialRestorePage >= 0)", restoreStart + 1)
        val restoreBranch = window.substring(restoreStart, restoreEnd)
        assertTrue(
            restoreBranch.windowed("activeSession?.requestWindowAsync(".length)
                .count { it == "activeSession?.requestWindowAsync(" } == 2,
        )
        assertTrue(restoreBranch.contains("reader_restore_surface_motion_async"))
        assertTrue(restoreBranch.contains("physicalFirstPage"))
        assertTrue(restoreBranch.contains("physicalLastPage"))

        val gate = functionBody(
            "private fun directWifiStrictEpisodeRestoreOwnedBySurface()",
            activitySource,
        )
        assertTrue(gate.contains("client.isNtkWifiTransportActive"))
        assertTrue(gate.contains("client.isNtkCellularResilientTransportActive"))

        val apply = functionBody("private fun applyLockedRestorePositionLocked(")
        assertTrue(apply.contains("lockedRestorePage"))
        assertTrue(apply.contains("setScrollOffsetLocked("))
    }

    @Test
    fun historyRemovalReconcilesEpisodeOnlyAfterTheNewPageIndexIsPublished() {
        val removed = functionBody("override fun onPagesRemoved(", activitySource)
        val stableDispatch = removed.indexOf("removalSession?.runAfterStructureStable(")
        val stablePage = removed.indexOf("val stablePage = currentPage.coerceIn(")
        val episodeUpdate = removed.indexOf("updateCurrentEpisode(stablePage)")
        assertTrue(stableDispatch >= 0)
        assertTrue(stablePage > stableDispatch)
        assertTrue(episodeUpdate > stablePage)
        assertFalse(removed.substring(0, stableDispatch).contains("updateCurrentEpisode("))

        val sessionDispatch = functionBody("fun runAfterStructureStable(", sessionSource)
        assertTrue(sessionDispatch.contains("dispatchWhenStructureStable("))
        assertTrue(sessionDispatch.contains("if (!cancelled.get()) callback.run()"))
    }

    @Test
    fun homeRoundTripUsesTheIdentityProofFromTheCurrentCommittedViewport() {
        val snapshot = activitySource.substring(
            activitySource.indexOf("data class CleanPhysicalSourceSnapshot("),
            activitySource.indexOf("data class FirstPhysicalDrawProof("),
        )
        assertTrue(snapshot.contains("val committedScrollOffsetPx: Float = Float.NaN"))
        assertTrue(snapshot.contains("val firstVisibleIdentity: ReaderSurfaceView.CommittedPageIdentity? = null"))
        assertTrue(snapshot.contains("val firstVisiblePageTopPx: Float = Float.NaN"))
        assertTrue(snapshot.contains("val visiblePageIdentities: List<ReaderSurfaceView.CommittedPageIdentity>"))
        assertTrue(snapshot.contains("val visiblePageTopPx: FloatArray = FloatArray(0)"))

        val record = functionBody(
            "private fun recordStrictCleanPhysicalSourceSnapshot(",
            activitySource,
        )
        assertTrue(record.contains("committedScrollOffsetPx = committedScrollOffsetPx"))
        assertTrue(record.contains("firstVisibleIdentity = firstVisibleIdentity"))
        assertTrue(record.contains("firstVisiblePageTopPx = firstVisiblePageTopPx"))
        assertTrue(record.contains("visiblePageIdentities = visiblePageIdentities.toList()"))
        assertTrue(record.contains("visiblePageTopPx = visiblePageTopPx.copyOf()"))

        val capture = functionBody(
            "private fun capturePausedPhysicalViewportAnchor()",
            activitySource,
        )
        assertTrue(capture.contains("currentCommittedViewportAnchorSnapshot()"))
        assertTrue(capture.contains("candidate.visiblePageIdentities.indexOfFirst"))
        assertTrue(capture.contains("val committedLiveTop = candidate.visiblePageTopPx"))
        assertTrue(capture.contains("!liveAnchor.busy"))
        assertTrue(capture.contains("committedLiveTop - liveAnchor.pageTopInViewportPx"))
        assertTrue(capture.contains("restorePageTopPx = exactLiveAnchor.pageTopInViewportPx"))
        assertTrue(capture.contains("visibleIdentity.displayPageIndex == position.page"))
        assertTrue(capture.contains("restoreIdentity = restoreIdentity"))
        assertTrue(capture.contains("restorePageTopPx = restoreTop"))

        val reconcile = functionBody(
            "private fun reconcilePausedPhysicalViewportAnchor(",
            activitySource,
        )
        assertTrue(reconcile.contains("proof.visiblePageIdentities.indexOfFirst"))
        assertTrue(reconcile.contains("proof.visiblePageTopPx.getOrNull(expectedIndex)"))
        assertFalse(
            reconcile.substringBefore("val restored = restorePausedPhysicalViewportAnchor()")
                .contains("pausedPhysicalViewportAnchor = null"),
        )

        val dispatchTouch = functionBody("override fun dispatchTouchEvent(", activitySource)
        val actionDown = dispatchTouch.substringAfter("MotionEvent.ACTION_DOWN)")
        assertTrue(actionDown.contains("pausedPhysicalViewportAnchor = null"))

        val surfaceTouch = functionBody("override fun onTouchEvent(")
        val surfaceDown = surfaceTouch.substringBefore("MotionEvent.ACTION_MOVE ->")
        assertTrue(surfaceDown.contains("if (lifecycleRestoreInputPending)"))
        assertTrue(surfaceDown.contains("lifecycleViewportAnchorIdentity = null"))
        assertTrue(surfaceDown.contains("lifecycleViewportAnchorPageTopPx = Float.NaN"))
        assertTrue(surfaceDown.contains("clearLockedRestorePositionLocked()"))
        assertTrue(surfaceDown.contains("structuralScrollAdjustUntilMs = 0L"))
        assertTrue(surfaceDown.contains("clearDirectWifiForwardOnlyInitialResumeContractLocked()"))

        val lifecycleRestore = functionBody(
            "fun restoreCommittedViewportForLifecycle(",
        )
        assertTrue(lifecycleRestore.contains("current.normalizedEpisodePath == identity.normalizedEpisodePath"))
        assertTrue(lifecycleRestore.contains("current.sourcePageIndex == identity.sourcePageIndex"))
        assertTrue(lifecycleRestore.contains("pageTopOrElseLocked(target, 0f) - pageTopInViewportPx"))
        assertTrue(lifecycleRestore.contains("lifecycleRestoreInputPending = true"))
        assertTrue(lifecycleRestore.contains("lifecycleViewportAnchorIdentity = identity"))
        assertTrue(lifecycleRestore.contains("lifecycleViewportAnchorPageTopPx = pageTopInViewportPx"))

        val geometryRestore = functionBody(
            "private fun restoreLifecycleViewportAnchorAfterGeometryLocked(",
        )
        assertTrue(geometryRestore.contains("current.sourcePageIndex == identity.sourcePageIndex"))
        assertTrue(geometryRestore.contains("pageTopOrElseLocked(target, 0f) - lifecycleViewportAnchorPageTopPx"))
        assertTrue(geometryRestore.contains("setStructuralScrollOffsetLocked(desired)"))
        val ordinaryRestore = functionBody("private fun restoreViewportAnchorLocked(")
        assertTrue(
            ordinaryRestore.indexOf("restoreLifecycleViewportAnchorAfterGeometryLocked(") <
                ordinaryRestore.indexOf("val target = anchor?.page"),
        )

        val physicalPosition = functionBody("fun currentScrollPositionSnapshot()")
        assertTrue(physicalPosition.contains("blockedForwardIntentPending = false"))
        assertFalse(physicalPosition.contains("hasLiveBlockedForwardIntentLocked()"))
        val liveAnchor = functionBody("fun currentCommittedViewportAnchorSnapshot()")
        assertTrue(liveAnchor.contains("pageTop - scrollOffset"))
        assertTrue(liveAnchor.contains("page.committedIdentity"))
        assertTrue(liveAnchor.contains("val progressPage = progressPositionLocked()?.page"))
        assertTrue(liveAnchor.contains("latestDeliveredStableViewportProof()"))
        assertTrue(liveAnchor.contains("NtkCommittedViewportProofPolicy.readingIdentityIndex("))
        assertFalse(liveAnchor.contains("visiblePageIdentities?.firstOrNull()"))

        val pause = functionBody("override fun onPause()", activitySource)
        assertTrue(pause.indexOf("freezePhysicalViewportForLifecycle()") < pause.indexOf("saveCurrentReadingProgress()"))
        val userLeave = functionBody("override fun onUserLeaveHint()", activitySource)
        assertTrue(userLeave.contains("freezePhysicalViewportForLifecycle()"))
        val freeze = functionBody("private fun freezePhysicalViewportForLifecycle()", activitySource)
        assertTrue(freeze.indexOf("capturePausedPhysicalViewportAnchor()") < freeze.indexOf("interruptPhysicalScrollForLifecycle()"))
        assertTrue(freeze.indexOf("interruptPhysicalScrollForLifecycle()") < freeze.indexOf("restorePausedPhysicalViewportAnchor()"))
        val resume = functionBody("override fun onResume()", activitySource)
        assertTrue(resume.contains("restorePausedPhysicalViewportAnchor()"))

        val instrumentation = File(
            "src/androidTest/java/ml/melun/mangaview/mangaview/" +
                "NtkOnePiecePreviousScrollReproTest.java",
        ).readText()
        val homeRoundTrip = functionBody("private ReaderV2Activity.CleanPhysicalSourceSnapshot performStrictHomeRoundTrip(", instrumentation)
        assertTrue(homeRoundTrip.contains("isCurrentCommittedViewport(evidence)"))
        assertTrue(homeRoundTrip.contains("beforePhysical.getFirstVisibleSourcePage()"))
        assertTrue(homeRoundTrip.contains("resumedPhysical.getFirstVisibleSourcePage()"))
        assertTrue(homeRoundTrip.contains("device.swipe(x, fromY, x, toY, 60)"))
        assertTrue(homeRoundTrip.contains("candidateCommittedAnchor.getPageTopInViewportPx()"))
        assertTrue(homeRoundTrip.contains("evidence.coverage.getMissingPx() == 0"))
    }

    @Test
    fun rollingSurfaceOwnsCadenceWhileActivityMailboxPublishesSemanticsOnly() {
        val completed = functionBody(
            "private fun handleAcceptedStrictRollingCompletedDraw(",
            activitySource,
        )
        val semanticPublish = completed.substring(
            completed.indexOf("ViewerTelemetry.actualImageDrawCommittedForEpisode(").also {
                require(it >= 0)
            },
        )
        assertTrue(semanticPublish.contains("proof.physicalInputReceivedNewestNanos,\n            true,"))
        assertTrue(telemetrySource.contains("if(!cadenceOwnedBySurface)"))
        assertTrue(telemetrySource.contains("session.recordQualifiedNativePresentationFrame("))
    }

    @Test
    fun enteredEpisodeIdleCompletionReassertsItsCompositorProvenTransferOwner() {
        val drain = functionBody(
            "private fun drainEnteredExactEpisodePixelCompletion(",
            sessionSource,
        )
        val latestGuard = drain.indexOf("latestEnteredExactEpisodePath.get() != path")
        val foregroundOwner = drain.indexOf(
            "NtkReaderTransferPacer.notePhysicalForegroundEpisode(this, path)",
        )
        val sourceRelease = drain.indexOf(
            "claim.transport.onForegroundIdleCompletionRequested(claim.episode)",
        )
        assertTrue(latestGuard >= 0)
        assertTrue(foregroundOwner > latestGuard)
        assertTrue(sourceRelease > foregroundOwner)
        assertTrue(drain.contains("val missingRehydrateFlight = strictAdjacentRehydrateFlights["))
        assertTrue(drain.contains("strictAdjacentBodyDescriptor(missingPage) != null"))
        assertTrue(drain.contains("strictAdjacentPublishedBody(missingPage) != null"))
        assertTrue(drain.contains("missingRehydrateFlight.enteredIdleCompletionIntent.set(true)"))
        assertTrue(drain.contains("wakeStrictAdjacentExactRehydrate("))
        assertTrue(drain.contains("enteredIdleCompletionIntent = true"))

        val rehydrate = functionBody(
            "private fun runStrictAdjacentExactRehydrate(",
            sessionSource,
        )
        assertTrue(
            rehydrate.contains(
                "enteredIdleCompletionIntent = flight.enteredIdleCompletionIntent.get()",
            ),
        )
        assertTrue(
            "the worker admission gate must honor the same entered-idle authority",
            rehydrate.contains("!flight.enteredIdleCompletionIntent.get() &&"),
        )

        val delivery = functionBody(
            "private fun prepareAdjacentRunwayDelivery(",
            sessionSource,
        )
        assertTrue(delivery.contains("val enteredIdleCompletionEligible = enteredIdleCompletionIntent"))
        assertTrue(delivery.contains("latestEnteredExactEpisodePath.get() == normalizedPath"))
        assertTrue(delivery.contains("claim?.firstActualFramePresented?.get() == true"))
        assertTrue(delivery.contains("isAdjacentStrictSourceClaimLive(normalizedPath, claim)"))
        assertTrue(delivery.contains("!foregroundMotion"))

        val publishedRunway = functionBody(
            "private fun drainPublishedExactAdjacentInitialRunwayCompletion(",
            sessionSource,
        )
        val publishedRunwayDescriptorProof = functionBody(
            "private fun publishedInitialRunwayDescriptorsReady(",
            sessionSource,
        )
        assertTrue(publishedRunway.contains("initialAdjacentRunwayPageLimit(path)"))
        assertTrue(
            publishedRunway.contains(
                "publishedInitialRunwayDescriptorsReady(",
            ),
        )
        assertTrue(publishedRunwayDescriptorProof.contains("adjacentStrictBodyDescriptors.containsKey("))
        assertTrue(publishedRunway.contains("reason = \"published_exact_initial_runway_completion\""))
        assertTrue(publishedRunway.contains("offscreenInitialRunwayCompletionIntent = true"))
        assertTrue(publishedRunway.contains("val missingCohort = synchronized(pagesLock)"))
        assertTrue(publishedRunway.contains("refs = missingPages"))
        assertTrue(publishedRunway.contains("sameContiguousCohort"))
        assertTrue(publishedRunway.contains("commitAdjacentRunwayDrawableBatch("))
        assertTrue(publishedRunway.contains("publishedExactOffscreenRunwayPaths.remove(path)"))

        val batchPreparation = functionBody(
            "private fun prepareAdjacentRunwayDrawableBatch(",
            sessionSource,
        )
        assertTrue(
            batchPreparation.contains(
                "\"published_exact_initial_runway_completion\" ->"
            )
        )
        assertTrue(batchPreparation.contains("firstSource in 1 until atomicRunwayPageLimit"))

        val exactSourceRequest = functionBody(
            "private fun requestStrictExactSourcePage(",
            sessionSource,
        )
        val authoritativeHandoff = exactSourceRequest.indexOf(
            "markFirstPreparedBitmapDelivered(resultDrawHeightPx(result))",
        )
        val firstDrawableCommit = exactSourceRequest.indexOf(
            "markFirstDrawableCommitted(index, \"strict_authoritative_handoff\")",
        )
        assertTrue(authoritativeHandoff >= 0)
        assertTrue(firstDrawableCommit > authoritativeHandoff)

        val initialHold = functionBody(
            "private fun shouldHoldInitialNtkDelivery(",
            sessionSource,
        )
        assertTrue(initialHold.contains("firstDrawableDelivered.get()"))
        assertTrue(initialHold.contains("strictOffscreenPublishResumeAtMs.compareAndSet("))

        val requestWindow = functionBody("private fun requestWindow(", sessionSource)
        assertFalse(
            "a p0 viewport callback must not retire the bounded p0..pN completion owner",
            requestWindow.contains("publishedExactOffscreenRunwayPaths::remove"),
        )

        assertTrue(
            delivery.contains(
                "val offscreenInitialRunwayCompletionEligible =",
            ),
        )
        assertTrue(delivery.contains("normalizedPath in publishedExactOffscreenRunwayPaths"))
        assertTrue(delivery.contains("NtkPublishedInitialRunwayCompletionPolicy.stillOwnsPixelCompletion("))
        assertTrue(delivery.contains("latestEnteredExactEpisodePath.get() == normalizedPath"))
        assertTrue(delivery.contains("initialAdjacentRunwayPageLimit(normalizedPath)"))
        assertTrue(delivery.contains("!offscreenInitialRunwayCompletionEligible"))

        val append = functionBody("private fun appendResolvedEpisodeInitialRunway(", sessionSource)
        assertTrue(append.contains("requestPublishedExactAdjacentInitialRunwayCompletion(path)"))

        val completion = functionBody(
            "private fun drainPublishedExactAdjacentInitialRunwayCompletion(",
            sessionSource,
        )
        val descriptorOwnerRelease = completion.indexOf(
            "publishedExactAdjacentInitialRunwayCompletionPaths.remove(path)",
        )
        val descriptorPostReleaseProbe = completion.indexOf(
            "publishedInitialRunwayDescriptorsReady(path, claim, requiredSourceCount)",
            descriptorOwnerRelease + 1,
        )
        assertTrue(descriptorOwnerRelease >= 0)
        assertTrue(descriptorPostReleaseProbe > descriptorOwnerRelease)
        assertTrue(
            "motion retry must retain one path owner until its delayed handoff",
            completion.contains(
                "if (publishedExactAdjacentInitialRunwayCompletionPaths.remove(path))",
            ),
        )
    }

    @Test
    fun directWifiTerminalResumePublishesOnlyAfterCurrentCardAndExactNextPixelsCoverTheViewport() {
        val profile = functionBody("fun setForwardNativeTexturePrewarmEnabled(")
        val lock = functionBody("fun lockRestoredPageOffset(")
        val attachment = functionBody("fun setSurfaceAttachmentDeferredUntilActualPixels(")
        val maxScroll = functionBody("private fun maxScrollLocked()")
        val viewport = functionBody(
            "private fun directWifiForwardOnlyInitialResumeViewportOpaqueLocked()"
        )
        val qualify = functionBody(
            "private fun qualifyDirectWifiForwardOnlyInitialResumeRevealLocked()"
        )
        val drawState = functionBody("private fun buildDrawStateLocked(")
        val physicalDrag = functionBody("private fun applyPhysicalDragPositionLocked(")
        val cleanLedger = functionBody(
            "private fun recordStrictCleanPhysicalSourceSnapshot(",
            activitySource,
        )
        assertTrue(profile.contains("directWifiForwardOnlyInitialResumeEnabled = expanded"))
        assertTrue(profile.contains("if (!expanded)"))
        assertTrue(lock.contains("surfaceAttachmentDeferredUntilActualPixels"))
        assertTrue(lock.contains("val forwardOnlyResume ="))
        assertFalse(lock.contains("index == pages.lastIndex"))
        assertTrue(lock.contains("directWifiForwardOnlyInitialResumePage = index"))
        assertTrue(lock.contains("min(0, offset)"))
        assertTrue(lock.contains("directWifiForwardOnlyInitialResumeOffset"))
        assertTrue(lock.contains("val resumeIdentityChanged ="))
        assertTrue(lock.contains("if (resumeIdentityChanged)"))
        assertTrue(attachment.contains("if (!enabled && !directWifiForwardOnlyInitialResumeEnabled)"))
        assertTrue(maxScroll.contains("directWifiForwardOnlyInitialResumeEnabled"))
        assertTrue(maxScroll.contains("pageTopOrElseLocked(forwardOnlyTarget"))
        assertTrue(maxScroll.contains("directWifiForwardOnlyInitialResumeOffset"))
        val apply = functionBody("private fun applyLockedRestorePositionLocked(")
        assertTrue(apply.contains("preserveForwardOnlyUntilPhysicalReveal"))
        assertTrue(apply.contains("!directWifiForwardOnlyInitialResumeRevealQualified"))
        assertTrue(
            apply.indexOf("preserveForwardOnlyUntilPhysicalReveal") <
                apply.indexOf("SystemClock.uptimeMillis() > lockedRestoreUntilMs"),
        )
        assertTrue(viewport.contains("!sawTransition"))
        assertTrue(viewport.contains("identity.normalizedEpisodePath != currentEpisodePath"))
        assertTrue(viewport.contains("identity.sourcePageIndex > previousCurrentSource + 1"))
        assertTrue(viewport.contains("previousCurrentSource != currentManifestPageCount - 1"))
        assertTrue(viewport.contains("index != currentTailLastIndex + 1"))
        assertTrue(viewport.contains("sawAdjacentActual"))
        assertTrue(viewport.contains("val expectedScroll = targetTop -"))
        assertTrue(viewport.contains("directWifiForwardOnlyInitialResumeOffset"))
        assertFalse(viewport.contains("requiredAdjacentSources"))
        assertFalse(viewport.contains("DIRECT_WIFI_INITIAL_ADJACENT_RUNWAY_PAGES"))
        assertTrue(viewport.contains("directWifiExpandedNativeTextureEpisodePaths"))
        assertTrue(qualify.contains("directWifiForwardOnlyInitialResumeViewportOpaqueLocked()"))
        assertFalse(qualify.contains("emulatorNativeSurfaceRuntime"))
        assertTrue(qualify.contains("directWifiForwardOnlyInitialResumeRevealQualified = true"))
        assertTrue(drawState.contains("qualifyDirectWifiForwardOnlyInitialResumeRevealLocked()"))
        assertFalse(drawState.contains("if (emulatorNativeSurfaceRuntime)"))
        assertFalse(drawState.contains("directWifiForwardOnlyInitialResumeRevealQualified &&"))
        assertTrue(cleanLedger.contains("coverage.visibleCards == 1"))
        assertTrue(cleanLedger.contains("coverage.directWifiForwardOnlyInitialResume"))
        assertTrue(cleanLedger.contains("coverage.visibleCards == 0 || qualifiedTransitionCard"))
        assertTrue(cleanLedger.contains("qualifiedTransitionCard = qualifiedTransitionCard"))
        assertTrue(physicalDrag.contains("clearLockedRestorePositionLocked()"))
        assertTrue(physicalDrag.contains("clearDirectWifiForwardOnlyInitialResumeContractLocked()"))
        assertTrue(activitySource.contains("var launchPixelsVisible = false"))
        assertTrue(activitySource.contains("launchPixelsVisible = true"))
        assertTrue(activitySource.contains("val outgoingPixelsFullyConsumed = currentOwnedPath.isNotEmpty() &&"))
        assertTrue(activitySource.contains("identities.none { identity ->"))
        assertTrue(
            activitySource.contains(
                "NtkPhysicalAdjacentMetadataAdoptionPolicy.shouldAdoptMixedBoundary("
            )
        )
        assertTrue(
            activitySource.contains(
                "freshPhysicalInputAfterEpisodeLaunch ="
            )
        )
        assertTrue(
            activitySource.contains(
                "advanceNativeTextureHistory = outgoingPixelsFullyConsumed"
            )
        )
    }

    @Test
    fun shortTerminalTailIsIdentityQualifiedActualWithoutFabricatingViewportPixels() {
        val forwardPrewarm = functionBody("fun setForwardNativeTexturePrewarmEnabled(")
        val terminalTail = functionBody(
            "private fun directWifiForwardOnlyTerminalTailActualLocked()"
        )
        val liveTail = functionBody("fun hasExactForwardOnlyTerminalTailActual(")
        val drawState = functionBody("private fun buildDrawStateLocked(")
        val completed = functionBody(
            "private fun handleStrictRollingCompletedDraw(",
            activitySource,
        )

        assertTrue(forwardPrewarm.contains("lockedRestorePage in pages.indices"))
        assertFalse(forwardPrewarm.contains("lockedRestorePage == pages.lastIndex"))
        val expandedRetirement = forwardPrewarm.indexOf("if (!expanded)")
        val tailLatchReset = forwardPrewarm.indexOf(
            "directWifiForwardOnlyTerminalTailRevealQualified = false"
        )
        assertTrue(expandedRetirement >= 0)
        assertTrue(tailLatchReset > expandedRetirement)
        assertFalse(
            forwardPrewarm.substring(0, expandedRetirement).contains(
                "directWifiForwardOnlyTerminalTailRevealQualified = false"
            )
        )
        assertTrue(terminalTail.contains("emulatorNativeSurfaceRuntime"))
        assertTrue(terminalTail.contains("directWifiForwardOnlyInitialResumeEnabled"))
        assertFalse(terminalTail.contains("target != pages.lastIndex"))
        assertTrue(terminalTail.contains("for (index in target until pages.size)"))
        assertTrue(terminalTail.contains("pageHasCompleteActualPixelsLocked(page)"))
        assertTrue(terminalTail.contains("page.pendingResolveType != PENDING_NONE"))
        assertTrue(terminalTail.contains("identity.normalizedEpisodePath != episodePath"))
        assertTrue(terminalTail.contains("identity.manifestDigest != manifestDigest"))
        assertTrue(terminalTail.contains("identity.manifestPageCount != manifestPageCount"))
        assertTrue(terminalTail.contains("identity.sourcePageIndex > previousSource + 1"))
        assertTrue(terminalTail.contains("previousSource == manifestPageCount - 1"))
        assertTrue(terminalTail.contains("val expectedScroll = targetTop -"))
        assertTrue(terminalTail.contains("terminalBottom < viewportBottom"))
        assertTrue(drawState.contains("directWifiForwardOnlyTerminalTailActualLocked()"))
        assertTrue(liveTail.contains("synchronized(stateLock)"))
        assertTrue(liveTail.contains("directWifiForwardOnlyTerminalTailRevealQualified ||"))
        assertTrue(liveTail.contains("directWifiForwardOnlyTerminalTailActualLocked()"))
        assertTrue(liveTail.contains("capturedForwardOnlyTerminalTailIdentitiesLocked"))
        val capturedTail = functionBody(
            "private fun capturedForwardOnlyTerminalTailIdentitiesLocked("
        )
        assertTrue(capturedTail.contains("emulatorNativeSurfaceRuntime"))
        assertTrue(capturedTail.contains("directWifiForwardOnlyInitialResumeEnabled"))
        assertTrue(capturedTail.contains("directWifiExpandedNativeTextureMinimumPage <= 0"))
        assertTrue(capturedTail.contains("directWifiExpandedNativeTextureEpisodePaths.firstOrNull()"))
        assertFalse(capturedTail.contains("directWifiForwardOnlyInitialResumePage"))
        assertTrue(capturedTail.contains("identity.displayPageIndex != previousDisplay + 1"))
        assertTrue(capturedTail.contains("identity.sourcePageIndex > previousSource + 1"))
        assertTrue(capturedTail.contains("previousSource == manifestPageCount - 1"))
        assertTrue(
            completed.contains(
                "renderView.hasExactForwardOnlyTerminalTailActual(capturedIdentities.orEmpty())"
            )
        )
        assertTrue(completed.contains("val exactTerminalTailIdentitySequence"))
        assertTrue(completed.contains("val hostGpuDirectWifiResumeProfile"))
        assertTrue(completed.contains("NtkNativeSurfaceFrameRatePolicy.isEmulatorRuntime("))
        assertTrue(completed.contains("client.isNtkWifiTransportActive"))
        assertTrue(completed.contains("!client.isNtkCellularResilientTransportActive"))
        assertTrue(completed.contains("identity.canonicalAsset =="))
        assertTrue(
            completed.contains(
                "ReaderPipelinePolicy.isExactForwardOnlyTerminalTailSourceSequence("
            )
        )
        assertTrue(completed.contains("hostGpuDirectWifiResumeProfile,"))
        assertTrue(completed.contains("terminalProfile=\$hostGpuDirectWifiResumeProfile"))
        assertTrue(completed.contains("terminalSequence=\$exactTerminalTailIdentitySequence"))
        assertTrue(completed.contains("terminalSources="))
        assertTrue(drawState.contains("forwardOnlyTerminalTailActual"))
        val revealable = functionBody("private fun hasRevealableActualPixelsLocked(")
        assertTrue(revealable.contains("directWifiForwardOnlyTerminalTailRevealQualified = true"))

        val identity = completed.indexOf("if (!identityValid)")
        val transport = completed.indexOf("val commitValidWithoutViewport")
        val exactTailCheck = completed.indexOf("val exactTerminalTailActual")
        val effectiveDefect = completed.indexOf("val effectiveViewportDefect")
        val defectCounter = completed.indexOf(
            "if (effectiveViewportDefect) strictTelemetryViewportDefectFrames++",
            startIndex = effectiveDefect,
        )
        val acceptedCall = completed.indexOf(
            "handleAcceptedStrictRollingCompletedDraw(",
            startIndex = defectCounter,
        )
        val accepted = functionBody(
            "private fun handleAcceptedStrictRollingCompletedDraw(",
            activitySource,
        )
        assertTrue(identity >= 0)
        assertTrue(transport > identity)
        assertTrue(exactTailCheck > transport)
        assertTrue(completed.contains("capturedIdentities != null &&"))
        assertTrue(effectiveDefect > exactTailCheck)
        assertTrue(defectCounter > effectiveDefect)
        assertTrue(acceptedCall > defectCounter)
        assertTrue(accepted.contains("strictTelemetryValidCommittedFrames++"))
        assertTrue(completed.contains("commitValidWithoutViewport && !effectiveViewportDefect"))
        assertFalse(completed.substring(exactTailCheck, effectiveDefect).contains("return"))
    }

    @Test
    fun directWifiInitialRestoreRepairsOnlyResolvedBookmarkGeometryWithoutRevealGating() {
        val apply = functionBody("private fun applyLockedRestorePositionLocked(")
        val repair = functionBody("private fun repairedDirectWifiInitialRestoreOffsetLocked(")

        assertTrue(apply.contains("repairedDirectWifiInitialRestoreOffsetLocked(target)"))
        assertTrue(apply.contains("preserveUntilBookmarkGeometryResolves"))
        assertTrue(apply.contains("!pageHasCompleteActualPixelsLocked(pages[target])"))
        assertTrue(repair.contains("directWifiForwardOnlyInitialResumeEnabled"))
        assertFalse(repair.contains("surfaceAttachmentDeferredUntilActualPixels"))
        assertFalse(repair.contains("hasDrawnContentFrame"))
        assertTrue(repair.contains("page.pendingResolveType != PENDING_NONE"))
        assertTrue(repair.contains("pageHasCompleteActualPixelsLocked(page)"))
        assertFalse(repair.contains("adjacent"))
        assertFalse(repair.contains("runway"))
        assertFalse(repair.contains("postSurfaceRevealLocked"))
    }

    @Test
    fun adjacentWebtoonH1RecoveryIsSequentialAfterH2AndExactProofBound() {
        val h2 = functionBody("private fun executeDirectWifiWebtoonH2(", imageCacheSource)
        val recovery = functionBody(
            "private fun executeDirectWifiAdjacentWebtoonH1Recovery(",
            imageCacheSource,
        )
        val recoveryClient = functionBody(
            "private fun clickOwnedDirectWifiAdjacentWebtoonRecoveryClient(",
            imageCacheSource,
        )
        val h2Ring = h2.indexOf("attempts.forEachIndexed")
        // Post-fence current work uses the bounded H1 recovery path before opening another H2
        // deadline. The adjacent runway path still reaches H1 only after its complete H2 ring.
        val currentFence = h2.indexOf("val currentRecoveryCallStartedAfterFence")
        val firstRecoveryCall = h2.indexOf("executeDirectWifiAdjacentWebtoonH1Recovery(")
        val recoveryCall = h2.lastIndexOf("executeDirectWifiAdjacentWebtoonH1Recovery(")

        assertTrue(h2Ring >= 0)
        assertTrue(currentFence >= 0)
        assertTrue(firstRecoveryCall > currentFence)
        assertTrue(firstRecoveryCall < h2Ring)
        assertTrue(recoveryCall > h2Ring)
        assertTrue(h2.contains("transport=h1_direct"))
        assertTrue(h2.contains("transport=h1_after_h2"))
        assertTrue(h2.contains("currentRecoveryPageRequiresDirectH1"))
        assertTrue(h2.contains("currentRecoveryCallStartedAfterFence && socketPressure"))
        assertFalse(h2.contains("cancelled.get() || index == attempts.lastIndex"))
        assertTrue(h2.contains("if (cancelled.get()) throw failure"))
        assertTrue(recovery.contains("NtkExactApiReplicaRouteTag::class.java"))
        assertTrue(recovery.contains("hasActiveAdjacentNtkForegroundViewerGrant(proof.path)"))
        assertTrue(recovery.contains("sameNetwork ="))
        assertTrue(recovery.contains("sameViewerGeneration ="))
        assertTrue(recovery.contains("clickOwnedDirectWifiAdjacentWebtoonRecoveryClient("))
        assertTrue(recovery.contains("clickOwnedDirectWifiAdjacentWebtoonRecoveryPermits"))
        assertTrue(recovery.contains("clickOwnedDirectWifiCurrentWebtoonRecoveryPermits"))
        assertTrue(recovery.contains("recoveryPermits.acquire()"))
        assertTrue(recovery.contains("NtkH1RecoveryPermitResponseBody(recoveredBody)"))
        assertTrue(recoveryClient.contains("directWifiNetwork.socketFactory"))
        assertTrue(recoveryClient.contains("protocols(listOf(Protocol.HTTP_1_1))"))
        assertFalse(recovery.contains("currentEpisodeOwned"))
    }

    @Test
    fun currentWebtoonSocketFailureOwnsTheProbeBeforeOrdinaryRefill() {
        val strictSource = File(
            "src/main/java/ml/melun/mangaview/reader/NtkStrictSourceSession.kt"
        ).readText()
        val refill = functionBody("private fun refillLanesActor(", strictSource)
        val service = functionBody(
            "private fun serviceCurrentWebtoonRecoveryProofOwnerActor(",
            strictSource,
        )
        val complete = functionBody("private fun completePhysicalActor(", strictSource)
        val retry = functionBody("private fun schedulePhysicalRetryActor(", strictSource)

        val fenceHold = refill.indexOf("currentWebtoonRecoveryProofOwner == null")
        val proofService = refill.indexOf("serviceCurrentWebtoonRecoveryProofOwnerActor()")
        val ordinaryLaneLoop = refill.indexOf("laneLoop@ for (laneIndex")
        assertTrue(fenceHold >= 0)
        assertTrue(proofService > fenceHold)
        assertTrue(ordinaryLaneLoop > proofService)
        assertTrue(service.contains("currentWebtoonRecoveryFence.requiresDirectH1"))
        assertTrue(service.contains("BASE_TARGET"))
        assertTrue(service.contains("BACKGROUND_OWNER_RECHECK_MS"))
        assertTrue(service.contains("schedulePhysicalRetryActor("))
        assertTrue(service.contains("launchPrimaryFullBodyActor(laneIndex, page)"))
        assertTrue(
            service.indexOf("schedulePhysicalRetryActor(") <
                service.indexOf("launchPrimaryFullBodyActor(laneIndex, page)")
        )
        assertFalse(service.contains("executePhysical("))

        val claim = complete.indexOf("CurrentWebtoonRecoveryProofOwner(")
        val schedule = complete.indexOf("schedulePhysicalRetryActor(page, delayMs)")
        assertTrue(claim >= 0)
        assertTrue(schedule > claim)
        val pressure = complete.indexOf("val recoveryPressure =")
        val stateTransition = complete.indexOf("pressureObserved = recoveryPressure")
        val delay = complete.indexOf("recoveryEligible && (recoveryPressure")
        val owner = complete.lastIndexOf("pressureObserved = recoveryPressure")
        assertTrue(pressure >= 0)
        assertTrue(complete.contains("fenceRequiresDirectH1 = pageFenceRequiresDirectH1"))
        assertTrue(stateTransition > pressure)
        assertTrue(delay > stateTransition)
        assertTrue(owner > delay)
        assertTrue(complete.contains("currentWebtoonRecoveryProofOwner?.activeWorkId"))
        assertTrue(retry.lastIndexOf("refillLanesActor()") >= 0)
    }

    @Test
    fun healthyColdCohortLeaderPreferenceIsScopedBehindRecoveryAndC8Proof() {
        val strictSource = File(
            "src/main/java/ml/melun/mangaview/reader/NtkStrictSourceSession.kt"
        ).readText()
        val refill = functionBody("private fun refillLanesActor(", strictSource)
        val selector = functionBody("private fun selectPrimaryPageActor(", strictSource)

        val recoveryOwner = refill.indexOf("serviceCurrentWebtoonRecoveryProofOwnerActor()")
        val ordinarySelector = refill.indexOf("selectPrimaryPageActor(")
        assertTrue(recoveryOwner >= 0)
        assertTrue(ordinarySelector > recoveryOwner)

        val preferenceStart = refill.indexOf("preferHealthyColdCohortLeaders =")
        val preferenceEnd = refill.indexOf(") ?: break", preferenceStart)
        assertTrue(preferenceStart > ordinarySelector)
        assertTrue(preferenceEnd > preferenceStart)
        val preference = refill.substring(preferenceStart, preferenceEnd)
        assertTrue(preference.contains("anchorBodyPublished"))
        assertTrue(preference.contains("healthyCurrentWebtoonBulkExpansion"))
        assertTrue(preference.contains("currentWebtoonRecoveryLiveAdmissionEligibleActor()"))
        assertTrue(refill.contains("currentWebtoonC8HealthState.qualified"))
        assertTrue(refill.contains("!currentWebtoonC8HealthState.frozen"))
        assertTrue(refill.contains("!currentWebtoonRecoveryFence.isTripped()"))

        assertTrue(selector.contains("preferredPageIndexes = if (nextOpeningProofPage != null)"))
        assertTrue(selector.contains("else if (preferHealthyColdCohortLeaders)"))
        assertTrue(selector.contains("coldConnectionCohortLeaderSet"))
        assertTrue(selector.indexOf("coldConnectionCohortLeaderSet") < selector.indexOf("emptySet()"))
    }

    @Test
    fun forwardHistoryPruneRequiresTheLatestIdentityBearingViewportObservation() {
        val note = functionBody("fun noteForwardReadingPosition(", sessionSource)
        val drain = functionBody("private fun drainForwardReadingPosition()", sessionSource)
        val trim = functionBody("private fun trimConsumedForwardHistory(", sessionSource)
        val retire = functionBody(
            "private fun retireConsumedForwardHistoryPixels(",
            sessionSource,
        )
        val requestWindow = functionBody("private fun requestWindow(", sessionSource)

        val revisionAt = note.indexOf("forwardReadingObservationRevision.incrementAndGet()")
        val publishedIndexAt = note.indexOf("publishedPageIndex.get().getOrNull(anchor)")
        assertTrue(revisionAt >= 0)
        assertTrue(publishedIndexAt > revisionAt)
        assertFalse(note.contains("synchronized(pagesLock)"))
        assertTrue(note.contains("latestReportedReadingPageRevision.set(observationRevision)"))

        assertTrue(drain.contains("currentForwardReadingObservationLocked("))
        assertTrue(drain.contains("expectedObservationRevision"))
        assertFalse(drain.contains("pages.getOrNull(requestedAnchor)"))

        val finalObservationCheck = trim.lastIndexOf(
            "forwardReadingObservationStillAuthorizesLocked("
        )
        val destructiveClear = trim.indexOf("pages.subList(0, candidate.removeCount).clear()")
        assertTrue(finalObservationCheck >= 0)
        assertTrue(destructiveClear > finalObservationCheck)
        assertTrue(retire.indexOf("forwardReadingObservationStillAuthorizesLocked(") <
            retire.indexOf("val protectedPixelWindow"))

        val ignoredBacktrack = requestWindow.substring(
            requestWindow.indexOf("reader_ntk_active_anchor_backtrack_ignored")
                .coerceAtLeast(0),
        )
        assertTrue(requestWindow.contains("directionHint >= 0"))
        assertTrue(ignoredBacktrack.contains("windowAnchor = previousViewportAnchor"))
    }

    @Test
    fun nextEpisodeDocumentAndAckControlCannotStartDuringPhysicalScroll() {
        val drain = functionBody("private fun drainForwardReadingPosition()", sessionSource)
        val busyBranchAt = drain.indexOf("if (physicalInputActive)")
        val quietBranchAt = drain.indexOf("} else {", busyBranchAt)
        val documentControlAt = drain.indexOf(
            "maybeStartInitialAdjacentMetadataPrefetch(",
            quietBranchAt,
        )

        assertTrue(drain.contains("physicalTouchQuietRemainingMs("))
        assertTrue(drain.contains("NTK_STRICT_ACTIVE_RUNWAY_IDLE_COMPLETION_QUIET_MS"))
        assertTrue(busyBranchAt >= 0)
        assertTrue(quietBranchAt > busyBranchAt)
        assertTrue(documentControlAt > quietBranchAt)
        assertTrue(
            drain.substring(busyBranchAt, quietBranchAt)
                .contains("scheduleForwardReadingRetry()"),
        )
        assertFalse(
            drain.substring(0, quietBranchAt)
                .contains("maybeStartForwardAdjacentDocumentControl("),
        )
        assertFalse(
            drain.substring(0, quietBranchAt)
                .contains("maybeStartInitialAdjacentMetadataPrefetch("),
        )
    }

    @Test
    fun adoptedExactRemainderRetainsTheFourSourceActiveScrollBound() {
        val policy = functionBody(
            "private fun shouldDeferRemainingAdjacentRunwayForActiveInput(",
            sessionSource,
        )
        val strictPark = functionBody(
            "private fun parkStrictOwnedOffscreenAdjacentRemainder(",
            sessionSource,
        )

        assertTrue(policy.contains("refs: List<PageRef>"))
        assertTrue(policy.contains("if (isViewportInsideEpisode(target))"))
        val currentEpisodeBranch = policy.substringBefore(
            "// Never extend the physical list underneath a real gesture/fling",
        )
        assertFalse(currentEpisodeBranch.contains("isStrictActiveRunwayPhysicalInputActive()"))
        assertTrue(currentEpisodeBranch.contains("viewportBusy.get()"))
        assertTrue(
            currentEpisodeBranch.contains(
                "forwardSourceRunway = NTK_STRICT_ACTIVE_FORWARD_SOURCE_RUNWAY",
            ),
        )
        assertTrue(strictPark.contains("NtkStrictAdjacentProgressiveRunwayPolicy.shouldPark("))
        assertFalse(strictPark.contains("scheduleStrictActiveRunwayIdleCompletion("))
        assertFalse(sessionSource.contains("strictActiveRunwayIdleWakeups"))
        assertTrue(policy.contains("currentViewportSourceIndexForEpisode(target)"))
        assertTrue(policy.contains("NtkStrictAdjacentProgressiveRunwayPolicy.shouldPark("))
        assertTrue(policy.contains("NTK_STRICT_ACTIVE_FORWARD_SOURCE_RUNWAY"))
    }

    @Test
    fun busyPhysicalForwardFrontierCanLeaveThePrimedBacklogOneAtATime() {
        val admission = functionBody(
            "private fun shouldDeliverBusyGeneratedOutsideRetained(",
            sessionSource,
        )
        val frontier = functionBody(
            "private fun shouldDeliverMissingPhysicalForwardFrontier(",
            sessionSource,
        )
        val enqueue = functionBody("private fun enqueueRetainedPrimedDeliveries()", sessionSource)
        val drain = functionBody("private fun deliverBusyDecodeResults()", sessionSource)

        assertTrue(admission.contains("shouldDeliverMissingPhysicalForwardFrontier(delivery, index)"))
        assertTrue(frontier.contains("!delivery.retainWhenBusy || index < 0"))
        assertTrue(frontier.contains(
            "!delivery.authoritativeAdjacentRehydrate ||\n" +
                "            (!delivery.hostPressurePhysicalReentry &&\n" +
                "                !delivery.exactAdjacentPhysicalIntent)",
        ))
        assertTrue(frontier.contains("!isImmediateNtkGeneratedUx()"))
        assertTrue(frontier.contains("!isActiveGeneratedInputOrQuietForDelivery()"))
        assertTrue(frontier.contains("isNtkManhwaOrWebtoonEpisodePath"))
        assertTrue(frontier.contains("hasDeliveredBitmap(index)"))
        assertFalse(frontier.contains("isNtkGeneratedImageUrl("))
        assertFalse(frontier.contains("index !in anchor.."))
        assertTrue(frontier.contains("reportedPhysicalDecodeProtectionWindowLocked("))
        assertTrue(enqueue.contains("shouldDeliverBusyGeneratedOutsideRetained(currentDelivery, index)"))
        assertTrue(drain.contains("deliveredFullCount < BUSY_DELIVERY_DRAIN_LIMIT"))
        assertTrue(sessionSource.contains("private const val BUSY_DELIVERY_DRAIN_LIMIT = 1"))
    }

    @Test
    fun forwardHistoryPruneRebasesRetainedCommittedIdentityIndexesBeforeNewProofs() {
        val remove = functionBody("fun removePageRange(")
        val clear = remove.indexOf("removePageRangeLocked(startIndex, endExclusive)")
        val rebase = remove.indexOf("identity.copy(displayPageIndex = index)")
        val tailRebase = remove.indexOf("rebasePhysicalEpisodeTailHoldAfterRemovalLocked(")
        val viewportRebase = remove.indexOf("setStructuralScrollOffsetLocked(", tailRebase)
        val reset = remove.indexOf("resetTraversalProofLocked(pages.size)")

        assertTrue(clear >= 0)
        assertTrue(rebase > clear)
        assertTrue(tailRebase > rebase)
        assertTrue(viewportRebase > tailRebase)
        assertTrue(reset > rebase)
    }

    @Test
    fun structuralCoordinateRebaseCannotBeRejectedAsBackwardUserInput() {
        val structural = functionBody("private fun setStructuralScrollOffsetLocked(")
        val setter = functionBody("private fun setScrollOffsetLocked(")

        assertTrue(structural.contains("allowStructuralCoordinateCorrection = true"))
        assertTrue(setter.contains("!allowStructuralCoordinateCorrection"))
        assertFalse(setter.contains("allowContentMaxShrinkCorrection"))
    }

    @Test
    fun nonFlingPhysicalSwipeReleasesNativePrewarmAtActionUp() {
        val touch = functionBody("override fun onTouchEvent(event: MotionEvent): Boolean")
        val idle = touch.indexOf("if (!pointerDown && scroller.isFinished)")
        val unpause = touch.indexOf("setNativeTexturePrewarmPausedLocked(false)", idle)

        assertTrue(idle >= 0)
        assertTrue(unpause > idle)
        assertFalse(touch.substring(idle, unpause).contains("!upMoved"))
    }

    @Test
    fun physicalMoveReleasesStateBeforeDerivingItsControllerWindow() {
        val touch = functionBody("override fun onTouchEvent(event: MotionEvent): Boolean")
        val moveStart = touch.indexOf("MotionEvent.ACTION_MOVE ->")
        val moveEnd = touch.indexOf("MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->", moveStart)
        val move = touch.substring(moveStart, moveEnd)
        val positionSection = move.indexOf("val requestBusyWindow = synchronized(stateLock)")
        val releasePoint = move.indexOf("if (sampleVelocity) velocityTracker?.addMovement(event)")
        val windowRecapture = move.indexOf(
            "synchronized(stateLock) { windowRequestLocked(true) }",
            releasePoint,
        )

        assertTrue(positionSection >= 0)
        assertTrue(releasePoint > positionSection)
        assertFalse(move.substring(positionSection, releasePoint).contains("windowRequestLocked(true)"))
        assertTrue(windowRecapture > releasePoint)
    }

    @Test
    fun repeatedAdjacentAssemblyDoesNotReopenTheSameAutoSplitHeader() {
        val split = functionBody("private fun shouldAutoSplitImage(", sessionSource)
        val cacheRead = split.indexOf("autoSplitDecisionByCanonicalAsset[decisionKey]")
        val boundsDecode = split.indexOf("BitmapFactory.decodeFile(")
        val cacheWrite = split.indexOf("autoSplitDecisionByCanonicalAsset.putIfAbsent(")

        assertTrue(sessionSource.contains(
            "private val autoSplitDecisionByCanonicalAsset = ConcurrentHashMap<String, Boolean>()"
        ))
        assertTrue(cacheRead >= 0)
        assertTrue(boundsDecode > cacheRead)
        assertTrue(cacheWrite in (cacheRead + 1) until boundsDecode)
        assertTrue(split.substring(boundsDecode).contains(
            "autoSplitDecisionByCanonicalAsset.putIfAbsent("
        ))
    }

    @Test
    fun aNewerCallbackCannotRegressTheFurthestCleanSourceOfTheSameEpisode() {
        val completed = functionBody(
            "private fun handleAcceptedStrictRollingCompletedDraw(",
            activitySource,
        )
        assertTrue(completed.contains(
            "physicalEpisodePath != strictTelemetryLastCleanPhysicalEpisodePath ||"
        ))
        assertTrue(completed.contains(
            "cleanSourcePage > strictTelemetryLastCleanSourcePage"
        ))
        val guardedUpdate = completed.indexOf("strictTelemetryLastCleanSourcePage = cleanSourcePage")
        val pathUpdate = completed.indexOf(
            "strictTelemetryLastCleanPhysicalEpisodePath = physicalEpisodePath",
            guardedUpdate,
        )
        assertTrue(guardedUpdate >= 0)
        assertTrue(pathUpdate > guardedUpdate)
    }

    @Test
    fun residentP0CannotSpinTheRunwayExecutorWhileItsPreparationOwnerIsStillActive() {
        val append = functionBody(
            "private fun appendResolvedEpisodeInitialRunway(",
            sessionSource,
        )
        val join = append.indexOf("NtkAdjacentRunwayPreparationPolicy.shouldJoin(")
        val completionJoin = append.indexOf("waitForPreparation = activePreparation", join)
        val joinedReturn = append.indexOf("return true", completionJoin)

        assertTrue(join >= 0)
        assertTrue(completionJoin > join)
        assertTrue(joinedReturn > completionJoin)
    }

    @Test
    fun emptyNativePrewarmIntentSleepsUntilPixelsOrTheWindowActuallyChange() {
        val request = functionBody("private fun requestResidentNativeTexturePrewarmLocked(")
        val flush = functionBody("private fun flushResidentNativeTexturePrewarm()")
        val pause = functionBody("private fun setNativeTexturePrewarmPausedLocked(")

        assertTrue(request.contains("newPixelOrWindowIntent: Boolean = true"))
        assertTrue(request.contains("nativeTexturePrewarmDormant = false"))
        assertTrue(request.contains("else if (nativeTexturePrewarmDormant)"))
        assertTrue(flush.contains("nativeTexturePrewarmDormant = true"))
        assertTrue(flush.contains("nativeTexturePrewarmDormant = false"))
        assertTrue(pause.contains(
            "requestResidentNativeTexturePrewarmLocked(newPixelOrWindowIntent = false)",
        ))
    }

    @Test
    fun rollingPixelDemandUsesCleanCompositorIdentitiesAndOnlyTheImmediateBlocker() {
        val presented = functionBody(
            "fun onExactNtkPhysicalDrawPresented(",
            sessionSource,
        )
        val window = functionBody(
            "private fun compositorProvenStrictExactPixelWindowLocked(",
            sessionSource,
        )
        val request = functionBody(
            "private fun requestStrictExactColdWindow(",
            sessionSource,
        )

        assertTrue(presented.contains("val published = publishedPageIndex.get()"))
        assertTrue(presented.contains("candidate.transitionTitle == null"))
        assertTrue(presented.contains("isStrictExactLaunchPage(candidate)"))
        assertTrue(presented.contains("latestExactCompositorWindow.set("))
        assertTrue(
            presented.indexOf("latestExactCompositorWindow.set(") <
                presented.indexOf("strictRollingControlMailbox.offerPhysicalDraw("),
        )
        assertTrue(window.contains("pageIndexLocked(page, -1)"))
        assertTrue(window.contains("val coldLast = minOf(safeFallbackLast, coldFirst + 1)"))
        assertTrue(window.contains("latestCurrentLaunchBlockedForwardPage.get()"))
        assertTrue(window.contains("blockerIndex in base.last..minOf(pages.lastIndex, base.last + 1)"))
        assertTrue(request.contains("compositorProvenStrictExactPixelWindowLocked("))
        assertFalse(request.contains("reportedPhysicalWindowLocked("))
        assertFalse(request.contains("reportedPhysicalDecodeProtectionWindowLocked("))
    }

    @Test
    fun toolbarEpisodeTransitionPublishesItsResumeContractBeforeDiscovery() {
        val launch = functionBody(
            "private fun launchAdjacent(source: Manga, target: Manga, title: Title?",
            activitySource,
        )
        val resolve = launch.indexOf("val startAtFirstPage = shouldStartEpisodeAtFirstPage(target)")
        val publish = launch.indexOf("initialStartAtFirstPage = startAtFirstPage", resolve)
        val discover = launch.indexOf("startStrictNtkDiscovery(target, \"adjacent_episode\")", publish)

        assertTrue(resolve >= 0)
        assertTrue(publish > resolve)
        assertTrue(discover > publish)
    }

    @Test
    fun everyStrictEpisodeRearmsItsReusableSurfaceBeforePublishingTheResumeFloor() {
        val start = activitySource.indexOf("private fun startReaderSession(")
        val rearm = activitySource.indexOf(
            "renderView.setSurfaceAttachmentDeferredUntilActualPixels(true)",
            start,
        )
        val floor = activitySource.indexOf("rememberStrictForwardReadyFloor(exactLaunchSeal)", start)
        val activate = activitySource.indexOf("renderView.activateDeferredSurfaceProducer()", floor)

        assertTrue(start >= 0)
        assertTrue(rearm > start)
        assertTrue(floor > rearm)
        assertTrue(activate > floor)
    }

    private fun functionBody(signature: String, text: String = source): String {
        return SourceFunctionBody.extract(text, signature)
    }
}
