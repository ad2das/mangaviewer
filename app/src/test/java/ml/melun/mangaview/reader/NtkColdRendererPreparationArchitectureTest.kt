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
    private val registrySource = File(
        "src/main/java/ml/melun/mangaview/reader/NtkSourceSpoolRegistry.kt"
    ).readText()

    @Test
    fun committedPixelIdentityProofExcludesStructuralTransitionCards() {
        val rollingProofStart = source.indexOf("val rollingVisiblePageIndexes")
        val rollingProofEnd = source.indexOf(
            "val rollingVisiblePageIdentities",
            startIndex = rollingProofStart
        )
        assertTrue(rollingProofStart >= 0)
        assertTrue(rollingProofEnd > rollingProofStart)

        val rollingProof = source.substring(rollingProofStart, rollingProofEnd)
        assertTrue(rollingProof.contains("item.cardText == null"))
        assertTrue(rollingProof.contains("item.top < state.height.toFloat()"))
    }
    private val sessionSource = File(
        "src/main/java/ml/melun/mangaview/reader/ReaderSession.kt"
    ).readText()
    private val rollingRendererSource = File(
        "src/main/cpp/ntk_rolling_surface_renderer.cpp"
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
    fun strictViewerKeepsLoadingChromeUntilAnIdentityValidPhysicalCommit() {
        val pagesReady = functionBody("override fun onPagesReady(", activitySource)
        val completedDraw = functionBody(
            "private fun handleStrictRollingCompletedDraw(",
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
        assertTrue(prepare.contains("!isDirectWifiStrictAdjacentTransportActive()"))
        assertTrue(prepare.contains("!viewportBusy.get()"))
        assertTrue(prepare.contains("!isActiveGeneratedTouchOrQuiet()"))
        assertTrue(delivery.contains("strictDescriptor != null || strictBody != null"))
        assertTrue(delivery.contains("prepareDecodeResultForDraw(decoded)"))
        assertTrue(delivery.contains("decoded"))
        assertFalse(delivery.contains("}.also(::prepareDecodeResultForDraw)"))
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
    fun strictRendererPreparationCannotPrecedeTheFirstCompleteActualHwuiCommit() {
        val attached = functionBody("override fun onAttachedToWindow()")
        val commit = functionBody("private fun onFrameCommitted(")
        val dispatch = functionBody("private fun drainCompletedDrawDispatch()")
        val reveal = functionBody("private fun revealNativeSurfaceAfterFirstHwuiCommit(")

        assertFalse(activitySource.contains("prepareDeferredSurfaceProducerAfterRootFrame"))
        assertFalse(attached.contains("prepareRollingNativeRendererLocked()"))
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

        assertTrue(completedDraw.contains("NtkVisibleIdentityPolicy.isValid("))
        assertTrue(completedDraw.contains("val telemetryEpisodeOwned ="))
        assertTrue(completedDraw.contains("episodeMatches = telemetryEpisodeOwned"))
        assertFalse(completedDraw.contains("currentManga?.ntkEpisodePath"))
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

        assertTrue(preparation.contains("NtkRollingNativeBridge.nativeCreate(this)"))
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
        val stopThread = functionBody("private fun stopRenderThreadLocked()")

        val surfaceFence = destroyed.indexOf("directSurfaceReady = false")
        val callbackRetirement = destroyed.indexOf("retireDirectSurfaceSchedulingLocked()")
        val framePipeRetirement = destroyed.indexOf("clearFramePipeLocked(preserveDirty = true)")
        assertTrue(surfaceFence >= 0)
        assertTrue(callbackRetirement > surfaceFence)
        assertTrue(framePipeRetirement > callbackRetirement)
        assertTrue(retirement.contains("removeCallbacks(directFramePostRunnable)"))
        assertTrue(retirement.contains("removeCallbacks(directCadenceWatchdog)"))
        assertTrue(retirement.contains("removeFrameCallback(directFrameCallback)"))
        assertTrue(retirement.contains("directFrameCallbackPosted = false"))
        assertTrue(retirement.contains("directLateInputCatchupPosted = false"))
        assertTrue(retirement.contains("directAdjacentExactP0CatchupPosted = false"))
        assertTrue(stopThread.contains("retireDirectSurfaceSchedulingLocked()"))
    }

    @Test
    fun physicalFrameContainsOnlyViewportPixels() {
        val submit = functionBody("private fun submitNativeFrame(")
        val tileIntersection = functionBody("private fun nativeTileIntersectsViewport(")

        assertTrue(submit.contains("NtkRollingNativeBridge.nativeSubmit("))
        assertTrue(submit.contains("filterDirectWifiNativeTiles"))
        assertTrue(submit.contains("directWifiExpandedNativeTextureEpisodePaths"))
        assertTrue(submit.contains("nativeTileIntersectsViewport("))
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
        val onDraw = functionBody("override fun onDraw(", source)

        assertTrue(source.contains("nativeSurfaceView.holder.setFormat(PixelFormat.TRANSLUCENT)"))
        assertTrue(draw.contains("glClearColor(0.0F, 0.0F, 0.0F, 0.0F)"))
        assertFalse(draw.contains("glClearColor(0.0F, 0.0F, 0.0F, 1.0F)"))
        assertTrue(surfaceCreated.contains("nativeSurfaceView.alpha = 0f"))
        assertTrue(surfaceDestroyed.contains("nativeSurfaceContentRevealed = false"))
        assertTrue(firstHwuiReveal.contains("nativeSurfaceView.alpha = 0f"))
        assertTrue(presentedReveal.contains("nativePresentationVisible"))
        assertTrue(presentedReveal.contains("nativePresentedStructureEpoch == expectedStructureEpoch"))
        assertTrue(presentedReveal.contains("traversalStructureEpoch == expectedStructureEpoch"))
        assertTrue(presentedReveal.contains("nativeSurfaceContentRevealed = true"))
        assertTrue(presentedReveal.contains("nativeSurfaceView.alpha = 1f"))
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
            "private fun handleStrictRollingCompletedDraw(",
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
        assertTrue(
            discoveryRetirement.contains(
                "val retirementClaimed = claimNetworkOwnershipRetirement(owned)"
            )
        )
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
        assertTrue(physicalAdjacent.contains("updateCurrentEpisode(displayPage"))
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
        val inside = functionBody(
            "private fun isViewportInsideEpisode(",
            sessionSource
        )

        assertTrue(actual.contains("wakeRemainingAdjacentAppendAfterExactFirstActual(normalizedPath)"))
        assertTrue(wake.contains("pendingRemainingAdjacentRunwayAppends[path]"))
        assertTrue(wake.contains("scheduledRemainingAdjacentRunwayRetries.remove(path)"))
        assertTrue(wake.contains("main.removeCallbacks(scheduled.runnable)"))
        assertTrue(wake.contains("pendingRemainingAdjacentRunwayAppends.remove(path, pending)"))
        assertTrue(wake.contains("appendRemainingAdjacentRunwayRefs("))
        assertFalse(wake.contains("currentViewportAnchor.set("))
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
            "private fun shouldSkipForwardNtkOutOfOrderAppendLocked(",
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
    fun visibleNativeFramesUseAtomicMultiBufferPublication() {
        val attach = functionBody("bool attachBackend(", rollingRendererSource)

        assertTrue(attach.contains("hostGpuEmulatorQueue ? 1 : 0"))
        assertTrue(attach.contains("eglSwapInterval(display_, requestedSwapInterval)"))
        assertTrue(attach.contains("setNativeWindowSwapInterval(command.window, requestedSwapInterval)"))
        assertTrue(attach.contains("const int8_t frameRateCompatibility = hostGpuEmulatorQueue ? 1 : 0"))
        assertTrue(
            attach.contains(
                "command.window, requestedFrameRate, frameRateCompatibility",
            ),
        )
        assertTrue(source.contains("Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE"))
        assertTrue(source.contains("Surface.CHANGE_FRAME_RATE_ALWAYS"))
        assertTrue(attach.contains("setSharedBufferMode(command.window, false)"))
        assertTrue(attach.contains("setAutoRefresh(command.window, false)"))
        assertTrue(attach.contains("hostGpuEmulatorSurfaceProfile_.load"))
        assertTrue(
            attach.contains(
                "setBufferCount(command.window, hostGpuEmulatorQueue ? 4 : 3)",
            ),
        )
        assertTrue(attach.contains("tryAllocateBuffers(command.window)"))
        assertTrue(attach.contains("EGL_BUFFER_DESTROYED"))
        assertFalse(attach.contains("setNativeWindowSwapInterval(command.window, 1)"))
        assertFalse(attach.contains("setSharedBufferMode(command.window, true)"))
        assertFalse(attach.contains("setAutoRefresh(command.window, true)"))
        assertFalse(attach.contains("EGL_BUFFER_PRESERVED"))
    }

    @Test
    fun hostGpuEmulatorNeverRunsNonPresentingTextureUploadsDuringContinuousInput() {
        val pause = functionBody("void setPrewarmPaused(bool paused)", rollingRendererSource)
        val active = functionBody(
            "bool isActiveDirectWifiPrewarmLocked() const noexcept",
            rollingRendererSource,
        )

        assertTrue(pause.contains("!hostGpuEmulatorSurfaceProfile_.load"))
        assertTrue(pause.contains("nowNanos() + kPrewarmResumeQuietNanos"))
        assertTrue(active.contains("!hostGpuEmulatorSurfaceProfile_.load"))
    }

    @Test
    fun nativeSubmissionRemainsChoreographerPacedWithoutReleaseInterpolation() {
        val commit = functionBody("private fun onFrameCommitted(", source)
        val directRender = functionBody("private fun renderDirectSurfaceFrame(", source)

        assertTrue(commit.contains("scheduleFrameLocked()"))
        assertTrue(directRender.contains("postReservedDirectFrameCallback"))
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
        assertTrue(snapshot.contains("snapshot.bitmaps,\n                false,"))
        assertTrue(source.contains("DIRECT_WIFI_NATIVE_PREWARM_AHEAD_PAGES = 8"))
        assertTrue(source.contains("DIRECT_WIFI_NATIVE_PREWARM_MAX_TILES = 16"))
        assertTrue(source.contains("DIRECT_WIFI_NATIVE_PREWARM_MAX_BYTES = 96L * 1024L * 1024L"))
        assertTrue(rollingRendererSource.contains("kMaxTextureBudgetBytes = 96ULL * 1024ULL * 1024ULL"))
        assertTrue(rollingRendererSource.contains("kMaxResidentTextureCount = 24"))
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

        assertTrue(upload.contains("replaceExistingWithFreshName"))
        assertTrue(upload.contains("existing != textures_.end() && !replaceExistingWithFreshName"))
        assertTrue(upload.contains("recycleTextureStorage(std::move(previousTextureStorage))"))
        assertTrue(recycle.contains("!directWifiTextureProfile_.load"))
        assertTrue(recycle.contains("glDeleteTextures(1, &texture.texture)"))
    }

    @Test
    fun directWifiReleaseGapUploadsOnlyTheNearestUnreadPageBeforeTheQuietGate() {
        val pause = functionBody("void setPrewarmPaused(bool paused) noexcept", rollingRendererSource)
        val admission = functionBody(
            "bool canUploadNextPrewarmLocked() const noexcept",
            rollingRendererSource,
        )

        assertTrue(pause.contains("directWifiTextureProfile_.load"))
        assertTrue(pause.contains("presentedMaxPage + 1"))
        assertTrue(pause.contains("directWifiFullPrewarmResumeNanos_ = now + kPrewarmResumeQuietNanos"))
        assertTrue(pause.contains("if (nextPrewarmUploadNanos_ > now) nextPrewarmUploadNanos_ = now"))
        assertTrue(pause.contains("lastPresentedMaxPageSnapshot_.load"))
        assertTrue(rollingRendererSource.contains("lastPresentedMaxPageSnapshot_.store"))
        assertTrue(admission.contains("directWifiImmediateResumeMaxPage_"))
        assertTrue(admission.contains("next.key.page > directWifiImmediateResumeMaxPage_"))
        assertTrue(admission.contains("return false"))
        assertFalse(admission.contains("directWifiTextureProfile_.load"))
        assertTrue(pause.contains("nowNanos() + kPrewarmResumeQuietNanos"))
    }

    @Test
    fun activeDirectWifiPrewarmUsesOnlyAnIdleForwardDripLane() {
        val admission = functionBody(
            "bool canUploadNextPrewarmLocked() const noexcept",
            rollingRendererSource,
        )
        val loop = functionBody("void run() noexcept", rollingRendererSource)

        assertTrue(admission.contains("isActiveDirectWifiPrewarmLocked()"))
        assertTrue(admission.contains("resident->second.bitmapIdentity == next.bitmapIdentity"))
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
        assertTrue(loop.contains("prewarmTiles_.push_front(prewarmTile)"))
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

        assertTrue(attach.contains("advanceRollingNativeSurfaceEpochLocked()"))
        assertTrue(attach.contains("rollingNativeSurfaceEpochCounter == request[1]"))
        assertFalse(attach.contains("lifecycleEpoch == request[1]"))
        assertTrue(schedule.contains("rollingNativeAttachEpoch > 0L"))
        assertFalse(schedule.contains("rollingNativeAttachEpoch == epoch"))
        assertTrue(directCallback.contains("rollingNativeAttachEpoch == 0L"))
        assertFalse(directCallback.contains("rollingNativeAttachEpoch != lifecycleEpoch"))
        assertTrue(latch.contains("rollingNativeAttachEpoch == submission.nativeSurfaceEpoch"))
        assertTrue(latch.contains("traversalStructureEpoch == submission.proof.structureEpoch"))
    }

    @Test
    fun overdueIdleProducerCallbackCannotPoisonLaterPhysicalInput() {
        val watchdog = functionBody("private val directCadenceWatchdog:")

        assertTrue(watchdog.contains("hasAdmittedFrame"))
        assertTrue(watchdog.contains("hasAdmittedFrame || shouldKeepDirectCadenceArmedLocked()"))
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
        assertTrue(note.contains("strictExactShortWebtoonRollingPixelResidency.get()"))
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
        assertTrue(quiet.contains("NTK_DIRECT_WIFI_FORWARD_HISTORY_NATIVE_QUIET_MS"))
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
        assertFalse(
            register.substringAfter("try {")
                .substringBefore("} catch")
                .contains("synchronized(stateLock)")
        )
    }

    @Test
    fun synchronousNativePresentationCannotFillPendingCommitWindow() {
        val render = functionBody("private fun renderFrame(")
        val callback = functionBody("private fun completeOrBufferNativePresentation(")
        val clear = functionBody("private fun clearFramePipeLocked(")

        assertTrue(render.contains("earlyNativePresentations.remove(work.frameToken)"))
        assertTrue(callback.contains("token == inFlightToken"))
        assertTrue(callback.contains("framePipe == FramePipe.INVALIDATION_POSTED"))
        assertTrue(clear.contains("earlyNativePresentations.clear()"))
    }

    @Test
    fun retiredNativePresentationRequestsAProofBearingReplacementFrame() {
        val callback = functionBody("private fun completeOrBufferNativePresentation(")

        assertTrue(callback.contains("registered == null && token !in earlyNativePresentations"))
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
        assertTrue(mixedClient.contains(".socketFactory(directWifiNetwork.socketFactory)"))
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
        assertTrue(execute.contains("strictPageIndex == 0"))
        assertTrue(execute.contains("manhwaRangeReplica"))
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
    fun directWifiShortWebtoonKeepsOnlyAnExactForwardPixelWindow() {
        val strictWindow = functionBody(
            "private fun requestStrictExactColdWindow(",
            sessionSource,
        )
        val rollingTrim = functionBody(
            "private fun trimShortWebtoonLaunchPixelsOutsideWindow(",
            sessionSource,
        )
        val release = functionBody("private fun postBitmapReleases(", sessionSource)
        val budgetTrim = functionBody("private fun trimDeliveredBudgetLocked(", sessionSource)
        val pressureTrim = functionBody(
            "private fun trimRetainedBitmapUnderPressureLocked(",
            sessionSource,
        )
        val windowChanged = functionBody("override fun onWindowChanged(", activitySource)
        val rollingEvicted = functionBody("override fun onPageRollingEvicted(", activitySource)
        val completedDraw = functionBody(
            "private fun handleStrictRollingCompletedDraw(",
            activitySource,
        )
        val nativePrewarm = functionBody("private fun flushResidentNativeTexturePrewarm(")

        assertTrue(sessionSource.contains("isCurrentDirectWifiRendererProfile("))
        assertTrue(strictWindow.contains("admission.allowedFirstSource"))
        assertTrue(strictWindow.contains("StrictRollingAdmission.REVERSE_PREDECESSOR_SOURCE_COUNT"))
        assertTrue(strictWindow.contains("visibleLast + if (shortWebtoon)"))
        assertTrue(strictWindow.contains("trimShortWebtoonLaunchPixelsOutsideWindow()"))
        assertTrue(rollingTrim.contains("isStrictExactLaunchPage(page)"))
        assertTrue(rollingTrim.contains("entry.key in keepFirst..keepLast"))
        assertTrue(rollingTrim.contains("preserveStrictReady = true"))
        assertTrue(release.contains("if (!release.preserveStrictReady)"))
        assertTrue(windowChanged.contains("forwardRequestStartPage()"))
        assertTrue(
            windowChanged.contains("directWifiShortWebtoonForwardRequestStartPage(")
        )
        assertTrue(windowChanged.contains("maxOf(\n                    requestFirstPage,"))
        assertTrue(windowChanged.contains("forwardRequestEndPage("))
        assertTrue(windowChanged.contains("NTK_DIRECT_WIFI_SHORT_WEBTOON_FORWARD_VIEWPORTS"))
        assertTrue(rollingEvicted.contains("forwardRequestStartPage()"))
        assertTrue(rollingEvicted.contains("requestWindowAsync(first, last, first, false)"))
        assertFalse(completedDraw.contains("reader_ntk_strict_handle_enter"))
        assertTrue(completedDraw.contains("currentTraversalStructureEpoch()"))
        assertFalse(completedDraw.contains("traversalSnapshot()"))
        assertTrue(nativePrewarm.contains("shortWebtoonPixelWindow"))
        assertTrue(nativePrewarm.contains("appendPixelWindowTile("))
        assertTrue(budgetTrim.contains("isStrictExactLaunchDisplayIndex(entry.key)"))
        assertFalse(budgetTrim.contains("0 until launchDisplayLimit"))
        assertTrue(pressureTrim.contains("isStrictExactLaunchDisplayIndex(it)"))
        assertTrue(sessionSource.contains("private fun isStrictExactLaunchDisplayIndex(index: Int)"))
        val resumeStart = functionBody(
            "fun directWifiShortWebtoonForwardRequestStartPage(",
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
        val rehydrate = functionBody(
            "private fun rehydrateSameStrictExactColdWindow(",
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
            "val sourceDemandChanged = !previous.hasSameSourceDemand(admission)",
            unchangedStart,
        )
        assertTrue(unchangedStart >= 0)
        assertTrue(unchangedEnd > unchangedStart)
        val unchanged = strictWindow.substring(unchangedStart, unchangedEnd)

        assertTrue(unchanged.contains("admission.physicalDrawPresented"))
        assertTrue(unchanged.contains("strictExactRollingPixelResidency.get()"))
        assertTrue(unchanged.contains("rehydrateSameStrictExactColdWindow("))
        assertFalse(unchanged.contains("applyStrictExactSourceDemand("))
        assertFalse(unchanged.contains("windowGeneration.incrementAndGet()"))
        assertTrue(strictWindow.contains("if (sourceDemandChanged) {"))
        assertTrue(strictWindow.contains("if (sourceDemandChanged) applyStrictExactSourceDemand(admission)"))
        assertTrue(strictWindow.contains("if (strictExactRollingPixelResidency.get())"))
        assertTrue(strictWindow.contains("visibleFirst..visibleLast"))
        assertTrue(rehydrate.contains("windowOrder("))
        assertTrue(rehydrate.contains("visibleFirst"))
        assertTrue(rehydrate.contains("visibleLast"))
        assertTrue(rehydrate.contains("strictExactRollingRehydratePages.contains(index)"))
        assertTrue(rehydrate.contains("if (!isStrictExactColdPageDemanded(index))"))
        assertTrue(rehydrate.contains("listener.isPageAuthoritativeDrawableInstalled(index)"))
        assertTrue(rehydrate.contains("strictExactRollingRehydratePages.remove(index)"))
        assertTrue(rehydrate.contains("requestPage("))
        assertFalse(rehydrate.contains("applyStrictExactSourceDemand("))
        assertFalse(rehydrate.contains("demandFirst"))
        assertTrue(releases.contains("strictExactRollingRehydratePages.addAll(rollingEvictedPages)"))
        assertTrue(
            removeState.contains(
                "shiftConcurrentSetAfterRemoval(strictExactRollingRehydratePages"
            )
        )
        assertTrue(
            clearState.contains("clearConcurrentSetFromIndex(strictExactRollingRehydratePages")
        )
        assertTrue(exactRequest.contains("listener.isPageAuthoritativeDrawableInstalled(index)"))
        assertTrue(exactRequest.contains("strictExactAuthoritativeHandoffPages.remove(index)"))
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
        assertTrue(boundary.contains("return strictAllImagesReadyPublished"))

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
        assertTrue(tilePolicy.contains("isDirectWifiStrictAdjacentTransportActive()"))

        val publishBound = functionBody(
            "private fun remainingAdjacentRunwayPublishPages(",
            sessionSource
        )
        assertTrue(publishBound.contains("isDirectWifiStrictAdjacentTransportActive()"))
        assertTrue(publishBound.contains("installed in 1 until requiredInitialRunway"))
        assertTrue(publishBound.contains("return requiredInitialRunway - installed"))
        assertTrue(
            publishBound.indexOf("isActiveGeneratedTouchOrQuiet() || viewportBusy.get()") <
                publishBound.indexOf("isInitialTailAdjacentPreappendTarget(target)")
        )
        assertTrue(
            source.contains(
                "HOST_GPU_DIRECT_WIFI_NATIVE_PREWARM_AHEAD_PAGES = 8"
            )
        )

        val retryBound = functionBody(
            "private fun remainingAdjacentRunwayAppendMinRetryMs(",
            sessionSource
        )
        assertTrue(retryBound.contains("isDirectWifiStrictAdjacentTransportActive()"))
        assertTrue(retryBound.contains("installed in 1 until requiredInitialRunway"))
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

        assertTrue(move.contains("return applyDragOffsetLocked(requestedOffset)"))
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
        assertTrue(
            defer.indexOf("if (isViewportInsideEpisode(target)) return false") <
                defer.indexOf("if (viewportBusy.get()) return true")
        )
    }

    @Test
    fun aCompletedBoundaryKeepsRevealOwnershipUntilTheForeignEpisodeIsPublished() {
        val boundary = functionBody("override fun onBoundaryReached(", activitySource)
        val appended = functionBody("override fun onPagesAppended(", activitySource)
        val finished = functionBody("override fun onBoundaryAppendFinished(", activitySource)

        assertTrue(boundary.contains("pendingNextBoundaryReveal = true"))
        assertTrue(boundary.contains("pendingNextBoundaryRevealPredecessorKey ="))
        assertTrue(boundary.contains("Manga.episodeIdentityKey(it.manga)"))
        assertTrue(appended.contains("appendedForeignEpisodeStartsAt(oldCount)"))
        assertTrue(appended.contains("pendingNextBoundaryRevealPredecessorKey"))
        assertTrue(appended.contains("shouldRevealCompletedNtkBoundaryGrowth("))
        assertTrue(appended.contains("revealAppendedBoundary = revealCompletedBoundary"))
        assertTrue(appended.contains("if (appendedForeignEpisode && pendingNextBoundaryReveal)"))
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
        assertTrue(completion.contains("main.post { flushDeferredAdjacentPrepare() }"))
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
        assertTrue(sessionSource.contains("ViewerTelemetry.activeGeneration() == generation"))
        assertTrue(
            allResident.contains(
                "activeGeneration == strictExactForegroundViewerGenerationAtCreation"
            )
        )
        assertTrue(
            allResident.contains(
                "ViewerTelemetry.activeGeneration() == strictExactForegroundViewerGenerationAtCreation"
            )
        )
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
            "} else if (shouldTryWifiManhwaPrimaryExactQuic(",
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
    fun activeScrollStrictDecodeGateIsWiredOnlyAroundCurrentWifiManhwaWorker() {
        val worker = functionBody("private fun requestStrictExactSourcePage(", sessionSource)

        assertTrue(worker.contains("NtkStrictActiveScrollDecodePolicy.shouldShareVisibleDecodeGate("))
        assertTrue(worker.contains("directWifi = isDirectWifiStrictAdjacentTransportActive()"))
        assertTrue(worker.contains("currentForegroundEpisode = isStrictExactCurrentForegroundEpisode()"))
        assertTrue(worker.contains("activeInput = isActiveGeneratedInputOrQuietForDelivery()"))
        assertTrue(worker.contains("anchor = anchor"))
        assertTrue(worker.contains("?.startsWith(\"/manhwa/\", ignoreCase = true) == true"))
        assertTrue(worker.contains("activeVisibleDecodeGate.acquire()"))
        assertTrue(worker.contains("activeScrollDecodeGateAcquired = true"))
        assertTrue(worker.contains("if (activeScrollDecodeGateAcquired)"))
        assertTrue(worker.contains("releaseActiveGeneratedProofDecodeGate()"))
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
        assertTrue(runwayCount.contains(".take(initialRunwayPageLimit)"))
        assertTrue(runwayCount.contains("val publishable = if (strictExactDescriptorOnly)"))
        assertTrue(runwayCount.contains("imageRefs.map { strictAdjacentBodyDescriptor(it) != null }"))
        assertTrue(runwayCount.contains("sourceSides = imageRefs.map { it.side }"))
        assertTrue(runwayPreparation.contains("val initialDrawablePages = directWifiInitialAttachedRunwayPages()"))
        assertTrue(runwayPreparation.contains(".take(initialDrawablePages)"))
        assertTrue(runwayPreparation.contains("strictBodiesReady = refs.all"))
        assertTrue(runwayPreparation.contains("\"initial_strict_source\""))
        assertTrue(runwayPublication.contains("val minimumReadyRunwayCount = runwayRefs.size"))
        assertTrue(runwayPublication.contains("if (readyRunwayCount < minimumReadyRunwayCount)"))
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
            "!directWifiStrictWebtoonRestoreOwnedBySurface()",
        )
        val relock = window.indexOf("renderView.lockRestoredPageOffset(")
        val rerequest = window.indexOf("activeSession?.requestWindowAsync(", relock)
        assertTrue(directWifiGate >= 0)
        assertTrue(relock > directWifiGate)
        assertTrue(rerequest > directWifiGate)

        val gate = functionBody(
            "private fun directWifiStrictWebtoonRestoreOwnedBySurface()",
            activitySource,
        )
        assertTrue(gate.contains("client.isNtkWifiTransportActive"))
        assertTrue(gate.contains("client.isNtkCellularResilientTransportActive"))

        val apply = functionBody("private fun applyLockedRestorePositionLocked(")
        assertTrue(apply.contains("lockedRestorePage"))
        assertTrue(apply.contains("setScrollOffsetLocked("))
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
        val completed = functionBody(
            "private fun handleStrictRollingCompletedDraw(",
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
        assertTrue(apply.contains("surfaceAttachmentDeferredUntilActualPixels"))
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
        assertTrue(drawState.contains("emulatorNativeSurfaceRuntime"))
        assertTrue(drawState.contains("qualifyDirectWifiForwardOnlyInitialResumeRevealLocked()"))
        assertTrue(drawState.contains("directWifiForwardOnlyInitialResumeRevealQualified &&"))
        assertTrue(completed.contains("val launchPixelsVisible = identities.any"))
        assertTrue(completed.contains("if (!launchPixelsVisible)"))
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
        val strictActual = completed.indexOf(
            "strictTelemetryValidCommittedFrames++",
            startIndex = defectCounter,
        )
        assertTrue(identity >= 0)
        assertTrue(transport > identity)
        assertTrue(exactTailCheck > transport)
        assertTrue(completed.contains("capturedIdentities != null &&"))
        assertTrue(effectiveDefect > exactTailCheck)
        assertTrue(defectCounter > effectiveDefect)
        assertTrue(strictActual > defectCounter)
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
        assertTrue(recovery.contains("clickOwnedDirectWifiAdjacentWebtoonRecoveryPermits.acquire()"))
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

        assertTrue(selector.contains("preferredPageIndexes = if (preferHealthyColdCohortLeaders)"))
        assertTrue(selector.contains("coldConnectionCohortLeaderSet"))
        assertTrue(selector.indexOf("coldConnectionCohortLeaderSet") < selector.indexOf("emptySet()"))
    }

    private fun functionBody(signature: String, text: String = source): String {
        val start = text.indexOf(signature)
        check(start >= 0) { "Missing function: $signature" }
        val open = text.indexOf('{', start)
        check(open >= 0)
        var depth = 0
        for (index in open until text.length) {
            when (text[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return text.substring(start, index + 1)
                }
            }
        }
        error("Unclosed function: $signature")
    }
}
