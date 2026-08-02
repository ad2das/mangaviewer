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
            activitySource.contains("it.setForwardNativeTexturePrewarmEnabled(strictNtkEpisode)")
        )
        assertFalse(activitySource.contains("it.setInlineRealPixelsOnly(strictNtkEpisode)"))
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
        assertTrue(sessionSource.contains("snapshot.second.any"))
        assertTrue(sessionSource.contains("hasForwardNtkEpisodeAfterSource(snapshot.first)"))
    }

    @Test
    fun directWifiAdjacentWorkStartsOnlyAfterEveryCurrentDrawable() {
        val directGate = functionBody(
            "private fun isDirectWifiStrictAdjacentCompletionGateActive(",
            sessionSource
        )
        val complete = functionBody(
            "private fun isEpisodeFullyDrawableForAdjacent(",
            sessionSource
        )
        val metadata = functionBody(
            "private fun maybeStartInitialAdjacentMetadataPrefetch(",
            sessionSource
        )
        val prefetch = functionBody(
            "private fun maybeStartInitialTailAdjacentPrefetch(",
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
        val directAdjacentPolicy = functionBody(
            "fun isDirectWifiForwardAdjacentPolicyActive(",
            sessionSource
        )
        val prime = functionBody(
            "private fun primeAdjacentLaunchWindow(",
            activitySource
        )
        val hybridPrime = functionBody(
            "private fun maybePrimeHybridNtkNextEpisode(",
            activitySource
        )
        val hybridStart = functionBody(
            "private fun maybeStartHybridNtkNextEpisode(",
            activitySource
        )
        val pageBottomGeometry = functionBody(
            "fun isPageBottomReachedAtScroll(",
            source
        )

        assertTrue(directGate.contains("if (!httpClient.isNtk) return false"))
        assertFalse(directGate.contains("strictExactColdRolling"))
        assertTrue(directGate.contains("httpClient.isNtkWifiTransportActive()"))
        assertTrue(
            directGate.contains(
                "!httpClient.isNtkCellularResilientTransportActive()"
            )
        )
        assertTrue(complete.contains("authoritativeCount <= 0"))
        assertTrue(complete.contains("refs.size < authoritativeCount"))
        assertTrue(complete.contains("hasListenerDrawableDelivery(index, page)"))
        assertTrue(complete.contains("hasDeliveredBitmap(index)"))
        assertTrue(metadata.contains("!isEpisodeFullyDrawableForAdjacent(source)"))
        assertTrue(prefetch.contains("!isEpisodeFullyDrawableForAdjacent(source)"))
        assertTrue(streams.contains("append_adjacent_foreground_streams_wait_current_complete"))
        assertTrue(
            refStreams.contains(
                "append_adjacent_foreground_ref_streams_wait_current_complete"
            )
        )
        assertTrue(
            completionCallback.contains(
                "session?.prepareForwardAdjacentAfterCurrentComplete(completedAnchor)"
            )
        )
        assertTrue(prime.contains("val activeSession = session ?: return"))
        assertTrue(prime.contains("!activeSession.canPrepareForwardAdjacentNow()"))
        assertTrue(hybridPrime.contains("!isDirectWifiHybridCurrentEpisodeComplete()"))
        assertTrue(hybridStart.contains("!isDirectWifiHybridCurrentEpisodeComplete()"))
        assertTrue(directAdjacentPolicy.contains("!reverse"))
        assertTrue(
            directAdjacentPolicy.contains(
                "isDirectWifiStrictAdjacentCompletionGateActive()"
            )
        )
        assertTrue(
            committedFrame.contains(
                "activeSession.isDirectWifiForwardAdjacentPolicyActive()"
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
            "private fun prepareAdjacentRunwayDrawableBatch(",
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
    fun adjacentEpisodePreparationIsParallelMetadataFirstAndStructurallyOrdered() {
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
        assertTrue(metadataFirst.contains("initialTailAdjacentPrefetchKeys.contains(fullRunwayKey)"))
        assertTrue(suffixGate.contains("path != targetPath"))
        assertFalse(suffixGate.contains("isActiveGeneratedTouchOrQuiet"))
        assertFalse(suffixGate.contains("viewportBusy"))
    }

    @Test
    fun exactAdjacentConsensusPrecedesLegacyThenIndividualRecoveryCandidates() {
        val candidates = functionBody(
            "private fun adjacentEpisodeCandidates(",
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
        val legacy = guard.indexOf("tailSource.nextEp()")

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

        assertTrue(attach.contains("eglSwapInterval(display_, 1)"))
        assertTrue(attach.contains("setNativeWindowSwapInterval(command.window, 1)"))
        assertFalse(attach.contains("setFrameRate(command.window, requestedFrameRate, 0)"))
        assertTrue(source.contains("Surface.FRAME_RATE_COMPATIBILITY_DEFAULT"))
        assertTrue(source.contains("Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS"))
        assertTrue(attach.contains("setSharedBufferMode(command.window, false)"))
        assertTrue(attach.contains("setAutoRefresh(command.window, false)"))
        assertTrue(attach.contains("setBufferCount(command.window, 6)"))
        assertTrue(attach.contains("tryAllocateBuffers(command.window)"))
        assertTrue(attach.contains("EGL_BUFFER_DESTROYED"))
        assertFalse(attach.contains("setNativeWindowSwapInterval(command.window, 0)"))
        assertFalse(attach.contains("setSharedBufferMode(command.window, true)"))
        assertFalse(attach.contains("setAutoRefresh(command.window, true)"))
        assertFalse(attach.contains("EGL_BUFFER_PRESERVED"))
    }

    @Test
    fun nativeRetirementImmediatelyContinuesAnAlreadyAdmittedMovingFrame() {
        val commit = functionBody("private fun onFrameCommitted(", source)
        val continuation = functionBody(
            "private fun postDirectNativeRetirementContinuation()",
            source
        )

        assertTrue(commit.contains("postDirectNativeRetirementContinuation()"))
        assertTrue(continuation.contains("postAtFrontOfQueue(directNativeRetirementContinuation)"))
        assertTrue(source.contains("ViewerDirectNativeRetirement"))
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
        assertTrue(snapshot.contains("val requestedPages = (first..runwayEnd).toList()"))
        assertTrue(snapshot.contains("val maxTiles = NATIVE_PREWARM_MAX_TILES"))
        assertFalse(snapshot.contains("NATIVE_FULL_EPISODE_PREWARM_MAX_TILES"))
        assertTrue(nativeQueue.contains("for (auto& queued : prewarmTiles_) releaseTile(env, queued)"))
        assertTrue(nativeQueue.contains("prewarmTiles_.clear()"))
    }

    @Test
    fun authoritativeStripDeliveryQueuesTheExactValidatedPageSlot() {
        val stripInstall = functionBody("fun installAuthoritativeStripTileDelta(")
        val stripPrewarm = functionBody("private fun flushResidentNativeTexturePrewarm()")

        assertTrue(stripInstall.contains("if (!valid)"))
        assertTrue(stripInstall.contains("postNativeStripTexturePrewarmLocked(command.key, tile)"))
        assertTrue(stripPrewarm.contains("page.stripSlots.forEachIndexed { slot, tile ->"))
        assertTrue(stripPrewarm.contains("appendTile(pageIndex, slot, tile)"))
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
            "fun start(manga: Manga, normalizedEpisodePath: String)",
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
    fun directWifiAdjacentHasNoPreCompletionOrPreviousAutoFetchEntry() {
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
        assertTrue(prepare.contains("!canPrepareForwardAdjacentNow()"))
        assertTrue(append.contains("reader_adjacent_previous_auto_append_disabled"))
        assertTrue(append.contains("append_adjacent_wait_current_complete"))
        assertTrue(
            append.indexOf("append_adjacent_wait_current_complete") <
                append.indexOf("appendExecutor.execute")
        )
        assertTrue(append.contains("ViewerTelemetry.adjacentWorkStarted("))
        assertTrue(unavailable.contains("listOf(ReaderSurfaceView.DIRECTION_NEXT)"))
        assertTrue(runway.contains("urls.isEmpty()"))
        assertFalse(runway.contains("urls.size <= NTK_APPEND_INITIAL_RUNWAY_PAGES"))
        assertTrue(prime.contains("val activeSession = session ?: return"))
        assertTrue(prime.contains("!activeSession.canPrepareForwardAdjacentNow()"))
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
    fun adjacentQualificationTraversesTheAtomicRunwayAndAcceptsShortEpisodes() {
        val drive = functionBody(
            "private fun driveIntoExpectedAdjacentEpisode(",
            macrobenchmarkSource
        )

        assertTrue(drive.contains("maxExpectedSource = maxOf(maxExpectedSource, source)"))
        assertTrue(drive.contains("telemetryNanos(\"adjacentTotalPageCount\")"))
        assertTrue(drive.contains("ADJACENT_REQUIRED_RUNWAY_PAGES"))
        assertTrue(drive.contains("maxExpectedSource >= requiredLastSource"))
        assertTrue(
            drive.contains(
                "if (expectedEpisodePath.isBlank() &&\n                launchReadyPageCount > 0"
            )
        )
        assertTrue(
            macrobenchmarkSource.contains(
                "ADJACENT_REQUIRED_RUNWAY_PAGES,\n                        adjacentTotalPageCount"
            )
        )
        assertTrue(
            qualificationSource.contains(
                "[Math]::Min(4, \$observedAdjacentTotalPageCount)"
            )
        )
        assertFalse(
            qualificationSource.contains(
                "adjacentRunwayPageCount\") -ne 4"
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
    fun directWifiForwardAdjacentEpisodeOmitsOnlyTheStructuralTransitionCard() {
        val refs = functionBody("private fun pageRefsForEpisode(", sessionSource)
        assertTrue(refs.contains("NtkAdjacentTransitionCardPolicy.shouldInsert("))
        assertTrue(refs.contains("isDirectWifiStrictAdjacentCompletionGateActive()"))
        assertTrue(refs.contains("isNtkManhwaOrWebtoonEpisodePath(target.ntkEpisodePath)"))
        assertTrue(refs.contains("if (!insertTransitionCard)"))
        assertTrue(refs.contains("return pageRefs"))
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
