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
    private val macrobenchmarkSource = File(
        "../macrobenchmark/src/main/java/ml/melun/mangaview/macrobenchmark/NtkColdViewerMacrobenchmark.kt"
    ).readText()
    private val qualificationSource = File(
        "../tools/ntk_cold_qualification.ps1"
    ).readText()

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
    fun strictProducerQueuePreparationDoesNotDelayTheReaderPipelineOrPublishPixels() {
        val setContent = activitySource.indexOf("setContentView(root)")
        val prepare = activitySource.indexOf(
            "renderView.prepareDeferredSurfaceProducerAfterRootFrame()"
        )
        val pipeline = activitySource.indexOf("startReaderPipeline.run()")
        val preparation = functionBody("fun prepareDeferredSurfaceProducerAfterRootFrame()")

        assertTrue(setContent >= 0)
        assertTrue(prepare > setContent)
        assertTrue(pipeline > prepare)
        assertTrue(preparation.contains("registerFrameCommitCallback"))
        assertFalse(preparation.contains("nativeSubmit("))
        assertFalse(preparation.contains("ImageRequest"))
        assertFalse(preparation.contains("Bitmap"))
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
    fun deferredStrictSurfacePreparesRendererAfterViewAttachment() {
        val attached = functionBody("override fun onAttachedToWindow()")

        assertTrue(attached.contains("super.onAttachedToWindow()"))
        assertTrue(attached.contains("if (rollingNativePresentationEnabled)"))
        assertTrue(attached.contains("startRenderThreadLocked()"))
        assertTrue(attached.contains("if (surfaceAttachmentDeferredUntilActualPixels)"))
        assertTrue(attached.contains("prepareRollingNativeRendererLocked()"))
    }

    @Test
    fun measuredSizeCompletesTheCreateBeforeMeasurePreparationOrdering() {
        val sizeChanged = functionBody("override fun onSizeChanged(")

        assertTrue(sizeChanged.contains("if (width > 0 && height > 0)"))
        assertTrue(
            sizeChanged.contains(
                "prepareRollingNativeRenderTargetsLocked(width, height)"
            )
        )
    }

    @Test
    fun earlyTransparentProducerStillRequiresExactIdentityAndActualPixelsBeforeRendering() {
        val activation = functionBody("fun activateDeferredSurfaceProducer()")
        val stage = functionBody("private fun postSurfaceRevealLocked()")
        val commit = functionBody("private fun onFrameCommitted(")

        assertFalse(activation.contains("nativeSurfaceView.visibility = View.VISIBLE"))
        assertFalse(stage.contains("nativeSurfaceView.visibility = View.VISIBLE"))
        assertTrue(activation.contains("deferredSurfaceIdentityActivated = true"))
        assertTrue(stage.contains("!deferredSurfaceIdentityActivated"))
        assertTrue(stage.contains("nativeSurfaceView.visibility != View.VISIBLE"))
        assertTrue(commit.contains("cleanCommittedHwuiActualPixels"))
        assertTrue(commit.contains("listener?.onCompletedDraw(proof)"))
        assertTrue(commit.contains("revealNativeSurfaceAfterFirstHwuiCommit("))
        assertTrue(
            commit.indexOf("listener?.onCompletedDraw(proof)") <
                commit.indexOf("revealNativeSurfaceAfterFirstHwuiCommit(")
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
    fun physicalFrameContainsOnlyViewportPixels() {
        val submit = functionBody("private fun submitNativeFrame(")

        assertTrue(submit.contains("NtkRollingNativeBridge.nativeSubmit("))
        assertFalse(submit.contains("nativePrewarmTile"))
        assertFalse(submit.contains("NATIVE_PREWARM_OFFSCREEN_GAP_PX"))
    }

    @Test
    fun translucentNativeSurfaceCannotOccludeTheHwuiFallbackWithBlack() {
        val draw = functionBody("bool drawFrame(", rollingRendererSource)

        assertTrue(source.contains("nativeSurfaceView.holder.setFormat(PixelFormat.TRANSLUCENT)"))
        assertTrue(draw.contains("glClearColor(0.0F, 0.0F, 0.0F, 0.0F)"))
        assertFalse(draw.contains("glClearColor(0.0F, 0.0F, 0.0F, 1.0F)"))
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

        assertTrue(completionPolicy.contains("MainApplication.getHttpClient().isNtk"))
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
        assertTrue(
            exactManifest.contains(
                "listener.onAdjacentExactManifestRequired(target, predecessorPath)"
            )
        )
        assertTrue(exactManifest.contains("!isEpisodeFullyDrawableForAdjacent(source)"))
        assertTrue(
            exactManifest.indexOf("!isEpisodeFullyDrawableForAdjacent(source)") <
                exactManifest.indexOf(
                    "listener.onAdjacentExactManifestRequired(target, predecessorPath)"
                )
        )
        assertTrue(resolvedMetadata.contains("!isEpisodeFullyDrawableForAdjacent(source)"))
        assertTrue(
            resolvedMetadata.indexOf("!isEpisodeFullyDrawableForAdjacent(source)") <
                resolvedMetadata.indexOf("ReaderImageCache.allowAdjacentNtkForegroundViewerPath(")
        )
        assertTrue(completionRelease.contains("releaseAdjacentBodiesAfterPredecessorComplete("))
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
                completionRelease.indexOf("releaseAdjacentBodiesAfterPredecessorComplete(")
        )
        assertTrue(
            completionRelease.indexOf("releaseAdjacentBodiesAfterPredecessorComplete(") <
                completionRelease.indexOf("control.execute {")
        )
        assertTrue(
            completionRelease.indexOf("startForwardAdjacentExactDiscoveryAtCompletion(") <
                completionRelease.indexOf("control.execute {")
        )
        assertTrue(
            appendedCompletionRelease.contains("NtkCompletedForwardEpisodePolicy.isComplete(")
        )
        assertTrue(appendedCompletionRelease.contains("releaseAdjacentBodiesAfterPredecessorComplete("))
        assertTrue(physicalAdmission.contains("adjacentPredecessorComplete"))
        assertTrue(physicalAdmission.contains("adjacentRunwayRelease"))
        assertTrue(exactInstall.contains("if (flight.adjacentPredecessorGate)"))
        assertTrue(exactInstall.contains("awaitAdjacentPredecessorComplete(flight)"))
        assertTrue(exactInstall.contains("flight.directWifiAdjacentBodyGate,"))
        val adjacentAwait = exactInstall.indexOf("awaitAdjacentPredecessorComplete(flight)")
        assertTrue(adjacentAwait >= 0)
        assertTrue(adjacentAwait < exactInstall.indexOf("enterForegroundNetworkIfNeeded(flight)"))
        assertTrue(adjacentAwait < exactInstall.indexOf("startAckNetworkPrerequisites("))
        assertTrue(
            adjacentAwait <
                exactInstall.indexOf("NtkClickOwnedManhwaProbeFrontier.start(")
        )
        assertTrue(adjacentAwait < exactInstall.indexOf("client.fetchExactNtkEpisodeDocument("))
        assertTrue(exactInstall.contains("if (plan != null)"))
        assertTrue(adjacentAwait < exactInstall.indexOf("\"document_plan_reserve\""))
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
        assertTrue(strictDiscoveryStart.contains("val explicitPredecessorPath ="))
        assertTrue(
            strictDiscoveryStart.contains(
                "val predecessorPath = explicitPredecessorPath.ifBlank { currentPath }"
            )
        )
        assertTrue(strictDiscoveryStart.contains("ownerPath,\n                predecessorPath,"))
        assertTrue(exactManifestCallback.contains("val capturedPredecessorPath ="))
        assertTrue(exactManifestCallback.contains("capturedPredecessorPath,"))
        assertFalse(exactManifestCallback.contains("currentManga"))
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
                "NTK_APPEND_REMAINING_RUNWAY_ACTIVE_PUBLISH_PAGES = 4"
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
            "addCandidate(ntkTrustedProvidedAdjacentCandidate(source, direction))"
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

        assertTrue(visible >= 0)
        assertTrue(canonical > visible)
        assertTrue(exactDecision > canonical)
        assertTrue(legacy > exactDecision)
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

        assertTrue(attach.contains("eglSwapInterval(display_, 0)"))
        assertTrue(attach.contains("setNativeWindowSwapInterval(command.window, 0)"))
        assertTrue(attach.contains("setFrameRate(command.window, requestedFrameRate, 0)"))
        assertTrue(source.contains("Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE"))
        assertTrue(source.contains("Surface.CHANGE_FRAME_RATE_ALWAYS"))
        assertTrue(attach.contains("setSharedBufferMode(command.window, false)"))
        assertTrue(attach.contains("setAutoRefresh(command.window, false)"))
        assertTrue(attach.contains("setBufferCount(command.window, 4)"))
        assertTrue(attach.contains("tryAllocateBuffers(command.window)"))
        assertTrue(attach.contains("EGL_BUFFER_DESTROYED"))
        assertFalse(attach.contains("setNativeWindowSwapInterval(command.window, 1)"))
        assertFalse(attach.contains("setSharedBufferMode(command.window, true)"))
        assertFalse(attach.contains("setAutoRefresh(command.window, true)"))
        assertFalse(attach.contains("EGL_BUFFER_PRESERVED"))
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
        assertTrue(snapshot.contains("DIRECT_WIFI_NATIVE_PREWARM_MAX_TILES"))
        assertTrue(snapshot.contains("DIRECT_WIFI_NATIVE_PREWARM_MAX_BYTES"))
        assertTrue(snapshot.contains("identity.normalizedEpisodePath !in"))
        assertTrue(source.contains("directWifiExpandedNativeTextureMinimumPage"))
        assertTrue(snapshot.contains("pageBytes > maxBytes - selectedBytes"))
        assertTrue(snapshot.contains("fun appendOrdinaryTile("))
        assertTrue(snapshot.contains("if (bitmapList.size >= maxTiles) return"))
        assertTrue(snapshot.contains("snapshot.bitmaps,\n                false,"))
        assertTrue(source.contains("DIRECT_WIFI_NATIVE_PREWARM_MAX_TILES = 48"))
        assertTrue(source.contains("DIRECT_WIFI_NATIVE_PREWARM_MAX_BYTES = 288L * 1024L * 1024L"))
        assertFalse(snapshot.contains("NATIVE_FULL_EPISODE_PREWARM_MAX_TILES"))
        assertTrue(nativeQueue.contains("const bool fullSceneSnapshot = completeSceneSnapshot"))
        assertFalse(nativeQueue.contains("bitmapCount) >"))
        assertTrue(nativeQueue.contains("for (auto& queued : prewarmTiles_) releaseTile(env, queued)"))
        assertTrue(nativeQueue.contains("prewarmTiles_.clear()"))
    }

    @Test
    fun completedCurrentEpisodeHandsOnlyItsForwardNeighborToTheWifiRunway() {
        val completion = functionBody("private fun queueStrictAllImagesRenderReady(", activitySource)
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

        assertTrue(completion.contains("authorizeCompletedForwardNativeTextureEpisode"))
        assertTrue(completion.indexOf("authorizeCompletedForwardNativeTextureEpisode") <
            completion.indexOf("prepareForwardAdjacentAfterCurrentComplete"))
        assertTrue(authorize.contains("if (!directWifiExpandedNativeTextureRunway"))
        assertTrue(authorize.contains("directWifiExpandedNativeTextureEpisodePaths.add"))
        assertTrue(adoption.contains("advanceCompletedForwardNativeTextureEpisode("))
        assertTrue(advance.contains("directWifiExpandedNativeTextureEpisodePaths.clear()"))
        assertTrue(advance.contains("directWifiExpandedNativeTextureMinimumPage"))
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
            "pageIndex in 0 until NtkStrictInitialWavePolicy.WIFI_ADJACENT_INITIAL_RUNWAY_BODIES"
        ))
        assertTrue(route.contains(
            "hasActiveAdjacentNtkForegroundViewerGrant(manifestSeal.normalizedEpisodePath)"
        ))
        assertTrue(route.contains("httpClient.isNtkWifiTransportActive()"))
        assertTrue(route.contains("!httpClient.isNtkCellularResilientTransportActive()"))
        assertTrue(route.contains("recentExactNtkApiReplicaCandidates("))
        assertTrue(route.contains("tag(NtkExactApiReplicaRouteTag::class.java, proof)"))
        assertTrue(route.contains("ntk-demand-bound-exact-image-proof-replica"))
        assertTrue(route.contains("val replicaAwareFactory = replicaFailoverFactory(baseFactory)"))
        assertTrue(imageCacheSource.contains("!liveDirectWifiAdjacentProofRoute &&"))
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
    fun directWifiInitialAdjacentRemainderUsesOnlyTheBoundedBackgroundOverlapDecode() {
        val batch = functionBody(
            "private fun prepareAdjacentRunwayDrawableBatch(",
            sessionSource
        )

        assertTrue(batch.contains("reason == \"append_runway_remaining_publish\""))
        assertTrue(batch.contains("isDirectWifiStrictAdjacentTransportActive()"))
        assertTrue(batch.contains("indexedPages.size in 2 until NTK_APPEND_INITIAL_RUNWAY_PAGES"))
        assertTrue(batch.contains("page.sourceIndex in 1 until NTK_APPEND_INITIAL_RUNWAY_PAGES"))
        assertTrue(batch.contains("tasks.forEach(strictExactOverlapDecode::execute)"))

        val publishBound = functionBody(
            "private fun remainingAdjacentRunwayPublishPages(",
            sessionSource
        )
        assertTrue(publishBound.contains("isDirectWifiStrictAdjacentTransportActive()"))
        assertTrue(publishBound.contains("installed in 1 until requiredInitialRunway"))
        assertTrue(publishBound.contains("return requiredInitialRunway - installed"))

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
        assertTrue(construction.contains("adjacentPrefetch = directWifiTransport"))
        assertTrue(construction.contains("currentForegroundViewerGeneration == 0L"))
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
        assertTrue(recovery.contains(".request(request)"))
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
            append.indexOf("append_adjacent_wait_current_complete") <
                append.indexOf("appendExecutor.execute")
        )
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
    fun adjacentQualificationRequiresAnExactFourPageRunwayAndFailsClosed() {
        val drive = functionBody(
            "private fun driveIntoExpectedAdjacentEpisode(",
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
        assertTrue(drive.contains("ADJACENT_REQUIRED_RUNWAY_PAGES"))
        assertTrue(drive.contains("maxExpectedSource >= requiredLastSource"))
        assertTrue(drive.contains("require(expectedEpisodePath.isNotBlank())"))
        assertFalse(drive.contains("expectedEpisodePath.isBlank()"))
        assertTrue(drive.contains("telemetryNanos(\"adjacentRunwayPageCount\")"))
        assertTrue(drive.contains("exactAtomicRunwayProven"))
        assertTrue(drive.contains("if (exactAtomicRunwayProven)"))
        assertFalse(drive.contains("if (exactAtomicRunwayProven &&"))
        assertTrue(drive.contains("provenExpectedRunwayDrawableCount"))
        assertTrue(drive.contains("if (maxExpectedSource >= 0 ||"))
        assertTrue(drive.contains("firstAdjacentActualAtNanos > 0L"))
        assertTrue(drive.contains("firstAdjacentActualEpisode == expectedEpisodePath"))
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
        assertTrue(macrobenchmarkSource.contains("val requiredRunwayPages = ADJACENT_REQUIRED_RUNWAY_PAGES"))
        assertTrue(macrobenchmarkSource.contains("runwayReadyBeforeTail ="))
        assertTrue(macrobenchmarkSource.contains("check(adjacentBoundaryWaitMs <= ADJACENT_ATTACH_SLA_MS)"))
        assertFalse(
            macrobenchmarkSource.contains(
                "firstAdjacentActualAtNanos - forwardBoundaryReachedAtNanos <="
            )
        )
        assertTrue(macrobenchmarkSource.contains("ADJACENT_BOUNDARY_WAIT_SLA_MS = 500L"))
        assertTrue(qualificationSource.contains("\$requiredAdjacentRunwayPages = 4"))
        assertTrue(qualificationSource.contains("\$expectedAdjacentPageCount -lt \$requiredAdjacentRunwayPages"))
        assertTrue(qualificationSource.contains("\"adjacentObservedRunwayDrawableCount\""))
        assertTrue(qualificationSource.contains("\"runwayReadyBeforeTail\""))
        assertTrue(qualificationSource.contains("\$ProductionMaxAdjacentBoundaryWaitMs = 500.0"))
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
