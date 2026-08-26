package ml.melun.mangaview.reader

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BitmapNativeRetirementArchitectureTest {
    private val session = source("reader/ReaderSession.kt")
    private val surface = source("reader/ReaderSurfaceView.kt")
    private val activity = source("activity/ReaderV2Activity.kt")
    private val warmup = source("glide/ViewerWarmupManager.java")
    private val hostPool = source("reader/HostExactHardwareTilePool.kt")
    private val rollingNative = File("src/main/cpp/ntk_rolling_surface_renderer.cpp").readText()
    private val rollingBridge = source("reader/NtkRollingNativeBridge.kt")
    private val bitmapRetirement = source("reader/ReaderBitmapRetirementDispatcher.kt")
    private val preference = File("src/main/java/ml/melun/mangaview/Preference.java").readText()
    private val application = File("src/main/java/ml/melun/mangaview/MainApplication.java").readText()

    @Test
    fun physicalWindowIngressReadsAnImmutablePublishedPageIndexWithoutPagesLock() {
        val request = session.section(
            "fun requestWindowAsync(",
            "/** Caller holds [pagesLock]; maps the Surface's exact viewport",
        )
        val reading = session.section(
            "fun noteForwardReadingPosition(",
            "private fun shouldRejectForwardAppendCompletionAfterSourceGrowth(",
        )
        val publication = session.section(
            "private fun beginStructurePublish()",
            "private fun isStructurePublishPending()",
        )
        val pageInfo = session.section(
            "fun pageInfo(index: Int): PageInfo?",
            "/**\n     * Coalesces Activity page changes onto the session control lane.",
        )
        val physicalBoundary = session.section(
            "fun onPhysicalBoundaryReached(",
            "private fun shouldParkHostGpuAdjacentWebtoonRemainder(",
        )
        val forwardPresence = session.section(
            "private fun hasForwardNtkEpisodeAfterSource(",
            "private fun shouldStartWifiAdjacentCascade(",
        )
        val redrive = session.section(
            "private fun performRetainedWindowRedrive()",
            "private fun isImageNotFoundError(",
        )

        assertTrue(session.contains("private val publishedPageIndex = AtomicReference<Array<PageRef>>"))
        assertTrue(request.contains("val pageIndex = publishedPageIndex.get()"))
        assertFalse(request.contains("synchronized(pagesLock)"))
        assertTrue(reading.contains("publishedPageIndex.get().getOrNull(anchor)"))
        assertFalse(reading.contains("synchronized(pagesLock)"))
        assertTrue(publication.contains("capturePublishedPageIndexLocked()"))
        assertTrue(publication.contains("pendingPublishedPageIndex.getAndSet(null)"))
        assertTrue(pageInfo.contains("publishedPageIndex.get().getOrNull(index)"))
        assertFalse(pageInfo.contains("synchronized(pagesLock)"))
        assertTrue(physicalBoundary.contains("publishedPageIndex.get().getOrNull(anchorPage)"))
        assertFalse(physicalBoundary.contains("synchronized(pagesLock)"))
        assertTrue(forwardPresence.contains("val pageIndex = publishedPageIndex.get()"))
        assertFalse(forwardPresence.contains("synchronized(pagesLock)"))
        assertTrue(redrive.contains("pageIndexInPublishedSnapshot("))
        assertFalse(redrive.contains("synchronized(pagesLock)"))
    }

    @Test
    fun deferredProducerSceneReusesExactResourcesAcrossViewportRelativeTranslation() {
        val reuse = surface.section(
            "private fun nativeProducerSceneTranslationForState(",
            "/** Allocation-free exact comparison plus the current scene's common Y translation. */",
        )

        assertTrue(reuse.contains("if (nativeProducerSceneTileCount < 0)"))
        assertTrue(reuse.contains("pendingNativeProducerSceneTranslationForState("))
        assertTrue(reuse.contains("nativeProducerSceneBandItems === nativeItems"))
        assertTrue(reuse.contains("insideStableMembership"))
        assertTrue(reuse.contains("original.bitmap !== current.bitmap"))
        assertTrue(reuse.contains("original.tiles != current.tiles"))
        assertTrue(reuse.contains("current.top - currentViewportItemTopOffset"))
        assertTrue(reuse.contains("original.top - originalViewportItemTopOffset"))
        assertFalse(reuse.contains("nativeProducerSceneBandItems = currentItems"))
    }

    @Test
    fun residentProducerGeometryBypassesRepeatedTextureAdmissionWork() {
        val geometrySubmit = rollingNative.section(
            "std::int64_t submitProducerGeometry(",
            "private:\n    std::int64_t enqueueFrame",
        )
        val headroom = rollingNative.section(
            "bool hasResidentProducerGeometryScene(",
            "bool hasOptionalPrewarmTextureHeadroom(",
        )
        val windowPresent = rollingNative.section(
            "PresentResult presentWindowFrame(",
            "PresentResult applyPreparedDirectFrame(",
        )

        assertTrue(geometrySubmit.contains("command.producerSceneGeometryOnly = true"))
        assertTrue(headroom.contains("resident->second.contentIdentity != tile.contentIdentity"))
        assertTrue(windowPresent.contains(
            "const bool residentProducerGeometry = hasResidentProducerGeometryScene(frame)",
        ))
        assertTrue(windowPresent.contains("if (!residentProducerGeometry &&"))
        assertTrue(windowPresent.contains("if (!residentProducerGeometry) {"))
    }

    @Test
    fun ordinaryVisibleHeadroomDoesNotUnconditionallyPollDriverRetirementFence() {
        val headroom = rollingNative.section(
            "bool prepareVisibleFrameTextureHeadroom(",
            "bool hasResidentProducerGeometryScene(",
        )

        assertFalse(headroom.contains("(void)pollTextureRetirementFence();"))
        assertTrue(headroom.contains("shouldSettleTextureRetirementBeforeVisibleUpload("))
        assertTrue(headroom.contains("settleTextureRetirementBeforeVisibleUpload()"))
    }

    @Test
    fun eglOwnerNeverEntersArtForWindowPresentationProofs() {
        val callbackLoop = rollingNative.section(
            "void presentationCallbackLoop() noexcept",
            "void callbackWindowFramePresented(",
        )
        val rendererCallback = rollingNative.section(
            "void callbackWindowFramePresented(",
            "void fatal(",
        )

        assertTrue(rollingNative.contains(
            "presentationCallbackThread_ =\n            std::thread(&RollingRenderer::presentationCallbackLoop, this)",
        ))
        assertTrue(callbackLoop.contains("env->CallVoidMethod("))
        assertTrue(callbackLoop.contains("presentationCallbackRead_.store("))
        assertTrue(rendererCallback.contains("enqueueWindowPresentationCallback({"))
        assertFalse(rendererCallback.contains("env->CallVoidMethod("))
    }

    @Test
    fun hostKeepsSharedFrontInsteadOfBlockingOnMultiBufferSwap() {
        val attach = rollingNative.section(
            "bool attachBackend(",
            "bool detachBackend(",
        )

        assertTrue(attach.contains(
            "const bool requestHostFrontBuffer =\n            hostGpuEmulatorSurfaceProfile_.load",
        ))
    }

    @Test
    fun readerProgressSerializesWholeCollectionsOnlyAtPauseBoundary() {
        val save = activity.section(
            "private fun saveReadingProgressNow(",
            "private fun resumeNtkProgressSnapshot(",
        )
        val pause = activity.section(
            "override fun onPause()",
            "private fun resetStrictPhysicalPresentationCadence(",
        )
        val orderedBookmark = preference.section(
            "public void setBookmark(Title title, int id, int orderedEpisodeIndex",
            "private void setBookmarkInternal(",
        )
        val deferredViewer = preference.section(
            "public void setViewerBookmarkDeferred(",
            "private void setViewerBookmarkInternal(",
        )

        assertTrue(orderedBookmark.contains("orderedEpisodeCount, false"))
        assertTrue(deferredViewer.contains("offset, side, false"))
        assertTrue(save.contains("setViewerBookmarkDeferred("))
        assertTrue(pause.contains("flushDeferredReaderProgress()"))
    }

    @Test
    fun physicalInputDoesNotInstallOrRetirePendingDrawableResources() {
        val cap = surface.section(
            "private fun capForwardInputScrollLocked(",
            "/** Must be called with stateLock held. */",
        )
        val producer = surface.section(
            "private fun prepareRenderWork(",
            "private fun finishRenderedFrame(",
        )

        assertTrue(cap.contains("val pendingDrawableResolve = hasPendingPageResolvesLocked()"))
        assertFalse(cap.contains("applyVisiblePendingDrawableResolvesLocked()"))
        assertFalse(cap.contains("applyPendingPageResolveLocked("))
        assertTrue(producer.contains("applyVisiblePendingDrawableResolvesLocked()"))
    }

    @Test
    fun noPendingResolveFramePathIsIndependentOfAccumulatedPageCount() {
        val page = surface.section(
            "private class Page(",
            "private data class ExactEpisodeTailKey(",
        )
        val registry = surface.section(
            "private val pageBitmapReferences",
            "private var stripAuthorityToken",
        )
        val pendingQuery = surface.section(
            "private fun hasPendingPageResolvesLocked()",
            "private fun isRecentScrollSettlingLocked()",
        )
        val visibleApply = surface.section(
            "private fun applyVisiblePendingDrawableResolvesLocked()",
            "private fun shouldDeferVisiblePendingDrawableResolvesLocked()",
        )

        assertTrue(page.contains("private var pendingResolveRegistry: MutableSet<Page>?"))
        assertTrue(page.contains("if (value == PENDING_NONE) registry.remove(this)"))
        assertTrue(page.contains("else registry.add(this)"))
        assertTrue(registry.contains("Collections.newSetFromMap(IdentityHashMap<Page, Boolean>())"))
        assertTrue(pendingQuery.contains("return pendingResolvePages.isNotEmpty()"))
        assertFalse(pendingQuery.contains("for (page in pages)"))
        assertTrue(visibleApply.contains("pendingResolvePages.isEmpty()"))
        assertTrue(visibleApply.contains("pendingResolveIndexesLocked(drawablesOnly = true)"))
        assertFalse(visibleApply.contains("for (index in pages.indices)"))
    }

    @Test
    fun fullNativeSceneCommandIsPreallocatedAndPostsItselfWithoutAHandlerLambda() {
        val command = surface.section(
            "private inner class DeferredNativeSceneSubmission : Runnable",
            "private data class PackedDeferredNativeScene(",
        )
        val post = surface.section(
            "private fun postDeferredNativeSceneSubmission(",
            "private fun executeDeferredNativeSceneSubmission(",
        )

        assertTrue(command.contains("var poolNext: DeferredNativeSceneSubmission?"))
        assertTrue(command.contains("override fun run()"))
        assertTrue(post.contains("handler.post(submission)"))
        assertFalse(post.contains("handler.post { executeDeferredNativeSceneSubmission"))
        assertTrue(surface.contains("primeDeferredNativeSceneSubmissionPool()"))
        assertTrue(surface.contains("releaseDeferredNativeSceneSubmission(submission)"))
    }

    @Test
    fun hostPressureRetirementWinsAgainstLateDecodedPixelPublication() {
        val bitmapTracking = session.section(
            "private fun trackDeliveredBitmap(",
            "private fun trackDeliveredResult(",
        )
        val tileTracking = session.section(
            "private fun trackDeliveredTiles(",
            "private fun addReplacementBitmapReleases(",
        )
        val mainDelivery = session.section(
            "val trackedIndex = trackDeliveredResult(",
            "deliveryTrackNs = SystemClock.elapsedRealtimeNanos() - deliveryTrackStartedNs",
        )

        assertTrue(bitmapTracking.contains(
            "hostExactPoolPressureRetiredPages.contains(resolvedIndex)",
        ))
        assertTrue(bitmapTracking.contains("mayPublishDecodedPixels("))
        assertTrue(bitmapTracking.contains("pressureRejected && owned"))
        assertTrue(tileTracking.contains(
            "hostExactPoolPressureRetiredPages.contains(resolvedIndex)",
        ))
        assertTrue(tileTracking.contains("mayPublishDecodedPixels("))
        assertTrue(tileTracking.contains("pressureRejected && owned"))
        assertTrue(mainDelivery.contains("if (authoritativeTilesDispatched)"))
        assertTrue(mainDelivery.contains("rollbackPublishedBitmapConflict("))
        assertTrue(mainDelivery.contains("main_delivery_pressure_retired"))

        val releasePublication = session.section(
            "private fun postBitmapReleases(",
            "private fun postSessionOwnedBitmapRetirement(",
        )
        assertTrue(releasePublication.contains("dispatchWhenStructureOwnerFinishes(this)"))
        assertTrue(releasePublication.contains(
            "publishedRollingTargets.all { target -> target.hostPressureRetirement }",
        ))
        assertTrue(session.contains("private val structureProgressCallbacks"))
        assertTrue(session.contains("progressCallbacks.forEach(Runnable::run)"))
        assertTrue(session.contains("private val hostPressurePendingSurfaceRetirements"))
        assertTrue(session.contains("private val hostPressureSurfaceRetirementsInFlight"))
        assertTrue(session.contains("hostPressurePendingSurfaceRetirements.entries.iterator()"))
        assertTrue(releasePublication.contains(
            "hostPressurePendingSurfaceRetirements[target.page] = target.fallbackIndex",
        ))
        assertTrue(releasePublication.contains(
            "hostPressureSurfaceRetirementsInFlight.remove(target.page)",
        ))
        val clearAck = session.section(
            "fun onHostPressureSurfaceClearCompleted(",
            "fun noteUserInteraction()",
        )
        assertTrue(clearAck.contains("if (!cleared) return"))
        assertTrue(clearAck.contains("hostPressurePendingSurfaceRetirements.remove(candidate)"))
    }

    @Test
    fun physicalEpisodeTailCrossingWaitsForTheOrdinaryCleanCompositorProof() {
        val cap = surface.section(
            "private fun capForwardInputScrollLocked(",
            "private fun rememberBlockedForwardIntentLocked(",
        )
        val acknowledgement = surface.section(
            "fun acknowledgeCleanPhysicalEpisodeTail(",
            "fun testScrollByPixels(",
        )
        val drawState = surface.section(
            "private fun buildDrawStateLocked(",
            "private fun drawablePrefixDrawStateOrNull(",
        )
        val completedDraw = activity.section(
            "private fun handleStrictRollingCompletedDraw(",
            "private fun adoptPhysicallyPresentedAdjacentEpisode(",
        )

        assertTrue(cap.contains("physicalEpisodeTailHoldLimitLocked(rawNext, direction)"))
        assertTrue(cap.contains("NtkPhysicalEpisodeTailHoldPolicy.shouldHoldCrossing("))
        assertTrue(
            cap.indexOf("physicalEpisodeTailHoldLimitLocked(rawNext, direction)") <
                cap.indexOf("allPagesHaveDrawableContentLocked()"),
        )
        assertTrue(surface.contains("isCleanPhysicalEpisodeTailHoldAppliedLocked(boundedNext)"))
        assertTrue(drawState.contains(
            "val physicalTailDrawLimit = heldPhysicalEpisodeTailDrawLimitLocked()",
        ))
        assertTrue(drawState.contains(
            "if (physicalTailDrawLimit != null && index > physicalTailDrawLimit) break",
        ))
        assertTrue(acknowledgement.contains("proof.surfaceLifecycleEpoch != lifecycleEpoch"))
        assertTrue(acknowledgement.contains("proof.structureEpoch != traversalStructureEpoch"))
        assertTrue(acknowledgement.contains("NtkPhysicalEpisodeTailHoldPolicy.isCleanTailCommit("))
        assertTrue(surface.contains(
            "acknowledgedRevision < physicalGestureRevision",
        ))
        assertTrue(surface.contains("enteringNewHeldGesture"))
        assertTrue(surface.contains("!acknowledged && enteringNewHeldGesture"))
        assertTrue(completedDraw.contains("renderView.acknowledgeCleanPhysicalEpisodeTail(proof)"))
        assertTrue(
            completedDraw.indexOf("if (!commitValid)") <
                completedDraw.indexOf("renderView.acknowledgeCleanPhysicalEpisodeTail(proof)"),
        )
        assertTrue(surface.contains("isForwardEpisodeMetadataAdoptionAllowed("))
        assertTrue(activity.contains("reader_forward_episode_metadata_wait_clean_tail"))
        val identities = surface.section(
            "fun setCommittedPageIdentities(",
            "fun setPageBounds(",
        )
        assertTrue(identities.contains("if (page.committedIdentity != nextIdentity)"))
        assertTrue(identities.contains("traversalStructureEpoch++"))
        assertTrue(identities.contains("physicalEpisodeTailProofRequestedKey = null"))
        assertTrue(identities.contains("advanceDesiredVersionLocked()"))
    }

    @Test
    fun replacedHostExactDrawableTransfersTerminalOwnershipToSurface() {
        val replacement = surface.section(
            "private fun retireReplacedPageDrawableLocked(",
            "private fun scheduleRollingAuthoritativeRecycleLocked()",
        )
        assertTrue(replacement.contains(
            "outgoing.filter(HostExactHardwareTilePool::isActiveToken)",
        ))
        assertTrue(replacement.contains(
            "retireSurfaceOwnedBitmapIdentitiesLocked(pooled, holdReferenced = true)",
        ))
        assertTrue(replacement.contains(
            "outgoing.filterNot(HostExactHardwareTilePool::isActiveToken)",
        ))
        assertTrue(replacement.contains("holdBitmapIdentitiesForNativeRetirementLocked(borrowed)"))
    }

    @Test
    fun directNoStateRecoveryUsesTheProducerOwner() {
        val retry = surface.section(
            "private fun scheduleNoStateRetryLocked()",
            "private fun clearFramePipeLocked(",
        )
        assertTrue(retry.contains("val retryHandler = if ("))
        assertTrue(retry.contains("directRenderHandler"))
        assertTrue(retry.contains("retryHandler.postDelayed("))
        assertTrue(surface.contains("Handler.createAsync(thread.looper)"))
    }

    @Test
    fun incrementalOffscreenAdjacentStructureDoesNotSubmitAStationaryFrame() {
        val append = surface.section(
            "fun appendPageCount(",
            "fun finishBoundaryDispatch()",
        )
        assertTrue(append.contains("renderAppendedStructure: Boolean = true"))
        assertTrue(append.contains(
            "if (renderAppendedStructure || revealAppendedBoundary || shouldExtendActiveFling)",
        ))
        assertTrue(activity.contains("renderAppendedStructure = appendedForeignEpisode"))
    }

    @Test
    fun decodedWarmupCacheCrossesIntoReaderThroughAnExclusiveSoftwareCopy() {
        val cachedDecode = session.section(
            "private fun cachedDecodedResultUnlocked(",
            "private fun decodePage(",
        )
        val exclusiveLookup = warmup.section(
            "public static Bitmap getDecodedBitmapExclusive(",
            "public static void clearDecodedWork(",
        )
        val copy = warmup.section(
            "static Bitmap copyBitmapForExclusiveConsumer(",
            "private static int bitmapSizeKb(",
        )

        assertTrue(cachedDecode.contains("ViewerWarmupManager.getDecodedBitmapExclusive("))
        assertFalse(cachedDecode.contains("ViewerWarmupManager.getDecodedBitmap("))
        assertTrue(cachedDecode.contains("trimBlankVerticalEdges(bitmap, true)"))
        assertTrue(cachedDecode.contains("recycleSource = true"))
        assertTrue(exclusiveLookup.contains("synchronized (ViewerWarmupManager.class)"))
        assertTrue(exclusiveLookup.contains("copyBitmapForExclusiveConsumer(cached.bitmap)"))
        assertTrue(copy.contains("bitmap.copy(Bitmap.Config.ARGB_8888, false)"))
        assertTrue(copy.contains("copy == bitmap"))
    }

    @Test
    fun oneTileSplitCannotRecycleTheIdentityReturnedToTheRenderer() {
        val drawable = session.section(
            "private fun drawableResult(",
            "private fun shouldSplitDecodedPageForDraw(",
        )
        val singleTileGuard = drawable.indexOf("if (height <= safeTileHeight)")
        val crop = drawable.indexOf("Bitmap.createBitmap(bitmap")
        val sourceRecycle = drawable.indexOf("bitmap.recycle()")

        assertTrue(singleTileGuard >= 0)
        assertTrue(crop > singleTileGuard)
        assertTrue(sourceRecycle > crop)
    }

    @Test
    fun sessionTransfersPublishedFinalOwnersWithoutATimerOrDirectRecycleFallback() {
        val releases = session.section(
            "private fun postBitmapReleases(",
            "private fun clearReleasedPageStateIfStillUndelivered(",
        )
        val transfer = session.section(
            "private fun transferSessionOwnedBitmapRetirement(",
            "private fun clearReleasedPageStateIfStillUndelivered(",
        )
        val close = session.section(
            "private fun releaseDeliveredBitmaps()",
            "private fun trimPendingBitmapDeliveriesOutside(",
        )

        assertTrue(releases.contains("transferSessionOwnedBitmapRetirement(ownedBitmaps)"))
        assertTrue(releases.contains("postSessionOwnedBitmapRetirement(ownedBitmaps"))
        assertFalse(releases.contains("recycleBitmapAfterPressureDelay(bitmap)"))
        assertFalse(releases.contains("recycleBitmapAfterDelay(bitmap)"))
        assertTrue(close.contains("postSessionOwnedBitmapRetirement(toRetire)"))
        assertFalse(close.contains("recycleBitmapAsync"))
        assertFalse(close.contains("recycleBitmapAfterDelay"))

        val externalLock = transfer.indexOf("synchronized(externallyOwnedBitmaps)")
        val deliveredLock = transfer.indexOf("synchronized(deliveredBitmaps)")
        val terminalClaim = transfer.indexOf("surfaceRetirementTransferredBitmaps.add(bitmap)")
        val listener = transfer.indexOf("listener.onSessionOwnedBitmapRetirement(transferable)")
        assertTrue(externalLock >= 0 && deliveredLock > externalLock)
        assertTrue(terminalClaim > deliveredLock && listener > terminalClaim)
        assertTrue(transfer.contains("bitmap !in externallyOwnedBitmaps"))
        assertTrue(transfer.contains("!bitmapPublicationInFlight.containsKey(bitmap)"))
        assertTrue(transfer.contains("!isCurrentlyDeliveredBitmapLocked(bitmap)"))
        assertFalse(transfer.contains("bitmap.recycle()"))
    }

    @Test
    fun terminalTransferIsAnAbaBarrierForEverySessionRecyclerAndDelivery() {
        val externalClaim = session.section(
            "private fun claimBitmapIdentitiesExternallyOwned(",
            "private fun isCurrentlyDeliveredBitmapLocked(",
        )
        val recycleGuard = session.section(
            "private fun releaseBitmapToPoolOrRecycle(",
            "private fun bitmapBytes(",
        )
        val bitmapTrack = session.section(
            "private fun trackDeliveredBitmap(",
            "private fun trackDeliveredResult(",
        )
        val tileTrack = session.section(
            "private fun trackDeliveredTiles(",
            "private fun addReplacementBitmapReleases(",
        )
        val mainDelivery = session.section(
            "private fun deliverDecodeResultOnMain(",
            "private fun dispatchTileDeliveryToListener(",
        )

        assertTrue(externalClaim.contains("bitmap in surfaceRetirementTransferredBitmaps"))
        assertTrue(recycleGuard.contains("bitmap in surfaceRetirementTransferredBitmaps"))
        assertTrue(recycleGuard.contains("bitmapPublicationInFlight.containsKey(bitmap)"))
        assertTrue(bitmapTrack.contains("bitmap in surfaceRetirementTransferredBitmaps"))
        assertTrue(tileTrack.contains("bitmap in surfaceRetirementTransferredBitmaps"))
        assertTrue(bitmapTrack.indexOf("surfaceRetirementTransferredBitmaps") <
            bitmapTrack.indexOf("deliveredDrawableProofWidths[resolvedIndex]"))
        assertTrue(tileTrack.indexOf("surfaceRetirementTransferredBitmaps") <
            tileTrack.indexOf("deliveredDrawableProofWidths[resolvedIndex]"))
        assertTrue(mainDelivery.contains("if (trackedIndex < 0)"))
        assertTrue(mainDelivery.indexOf("if (trackedIndex < 0)") <
            mainDelivery.indexOf("listener.onPageReady"))
    }

    @Test
    fun terminalConflictRedriveReleasesItsQueuedMarkerBeforeRequestingAgain() {
        val redrive = session.section(
            "private fun redriveAfterTerminalBitmapConflict(",
            "private fun maybePromoteLowResGeneratedDrawable(",
        )
        val delayed = redrive.indexOf("main.postDelayed")
        val remap = redrive.indexOf("currentPageIndexForDelivery(page, fallbackIndex)", delayed)
        val release = redrive.indexOf("terminalBitmapRedrivePosted.remove(fallbackIndex)", remap)
        val request = redrive.indexOf("requestPage(", release)

        assertTrue(delayed >= 0)
        assertTrue(remap > delayed)
        assertTrue(release > remap)
        assertTrue(request > release)
    }

    @Test
    fun publishedConflictRollbackNeverClearsACompactedReplacementByStaleIndex() {
        val rollback = session.section(
            "private fun rollbackPublishedBitmapConflict(",
            "private fun redriveAfterTerminalBitmapConflict(",
        )
        val structureLock = rollback.indexOf("val resolvedIndex = synchronized(pagesLock)")
        val exactPage = rollback.indexOf("pageIndexLocked(page, fallbackIndex)")
        val clear = rollback.indexOf("listener.onPageCleared(index)")
        val absentReturn = rollback.indexOf("if (resolvedIndex < 0) return")

        assertTrue(structureLock >= 0)
        assertTrue(exactPage > structureLock)
        // The downstream numeric clear must remain inside the same structure transaction as the
        // PageRef lookup; the post-lock absent check is deliberately later.
        assertTrue(clear > exactPage)
        assertTrue(absentReturn > clear)
        assertFalse(rollback.contains("listener.onPageCleared(fallbackIndex)"))
        assertFalse(rollback.contains("listener.onPageCleared(resolvedIndex)"))
    }

    @Test
    fun surfaceSeparatesBorrowedNativeHoldsFromFinalRecycleOwnership() {
        val terminal = surface.section(
            "private fun retireSurfaceOwnedBitmapIdentitiesLocked(",
            "private fun holdBitmapIdentitiesForNativeRetirementLocked(",
        )
        val borrowed = surface.section(
            "private fun holdBitmapIdentitiesForNativeRetirementLocked(",
            "private fun retireCurrentPageDrawableLocked(",
        )
        val pendingClear = surface.section(
            "private fun clearPendingResolveLocked(",
            "private fun hasPendingPageResolvesLocked(",
        )
        val drain = surface.section(
            "private fun drainRollingAuthoritativeRecycles()",
            "private fun completeRollingNativeDestroy()",
        )
        val fence = surface.section(
            "private fun bitmapHasNativeRetirementFenceLocked(",
            "private fun isBitmapCurrentlyReferencedLocked(",
        )

        assertTrue(surface.contains("private val nativeRetirementHolds"))
        assertTrue(surface.contains("private val surfaceOwnedRecycleCandidates"))
        assertTrue(terminal.contains("surfaceOwnedRecycleCandidates.addAll(retired)"))
        assertTrue(terminal.contains("nativeRetirementHolds.addAll(holds)"))
        assertTrue(terminal.contains("isBitmapCurrentlyReferencedLocked"))
        assertTrue(borrowed.contains("nativeRetirementHolds.addAll(held)"))
        assertFalse(borrowed.contains("surfaceOwnedRecycleCandidates.addAll"))

        assertTrue(pendingClear.contains("page.bitmap?.let(::add)"))
        assertTrue(pendingClear.contains("page.tiles.forEach"))
        assertTrue(pendingClear.contains("retirePendingPageDrawableLocked(page, currentAndRetained)"))
        assertTrue(drain.contains("if (current[index]) nativeRetirementHolds.remove(bitmap)"))
        assertTrue(drain.contains("ReaderBitmapRetirementDispatcher.dispatchWork"))
        assertTrue(drain.contains("private fun executeRollingAuthoritativeRecycleProbe("))
        val capture = drain.substringBefore(
            "private fun executeRollingAuthoritativeRecycleProbe(",
        )
        assertFalse(capture.contains("nativeDiscardQueuedFramesWithRetiredBitmaps("))
        assertFalse(capture.contains("nativeDiscardQueuedPrewarmBitmaps("))
        assertFalse(capture.contains("nativeBitmapReferenceMask("))
        val queuedRetirement = drain.indexOf("nativeDiscardQueuedPrewarmBitmaps(")
        val referenceMask = drain.indexOf("nativeBitmapReferenceMask(")
        assertTrue(queuedRetirement >= 0 && referenceMask > queuedRetirement)
        assertTrue(drain.contains("protectedAdmissionToken = if ("))
        assertTrue(drain.contains("protectedAdmissionToken,"))
        assertFalse(drain.contains("if (framePipe != FramePipe.INVALIDATION_POSTED)"))
        assertTrue(drain.contains("nativeBitmapReferenceMask("))
        assertFalse(drain.contains("nativeIsQuiescent(handle)"))
        assertTrue(drain.contains("surfaceOwnedRecycleCandidates.remove(bitmap)"))
        assertFalse(drain.contains("bitmap.recycle()"))
        assertTrue(drain.indexOf("surfaceOwnedRecycleCandidates.remove(bitmap)") <
            drain.indexOf("ReaderBitmapRetirementDispatcher.dispatch(ordinaryRecycles)"))
        assertTrue(drain.contains("ordinaryBitmapRetirementsInFlight += ordinaryRecycles.size"))
        assertTrue(bitmapRetirement.contains("if (!bitmap.isRecycled) bitmap.recycle()"))
        assertTrue(fence.contains("return bitmap in nativeRetirementHolds"))
        assertFalse(fence.contains("pages.none"))
    }

    @Test
    fun everyBitmapGlobalReferenceJoinsTheExactNativeIdentityLedger() {
        val submit = rollingNative.section(
            "std::int64_t submit(",
            "bool prewarm(",
        )
        val prewarm = rollingNative.section(
            "bool prewarm(",
            "bool destroy()",
        )
        val release = rollingNative.section(
            "bool retainTileBitmapReference(",
            "void releaseFrame(",
        )

        assertTrue(submit.contains("retainFrameBitmapReferences(command.tiles)"))
        assertTrue(prewarm.contains("retainTileBitmapReference(tile)"))
        assertTrue(release.contains("bitmapReferenceLedger_.retain(tile.bitmapIdentity)"))
        assertTrue(release.contains("bitmapReferenceLedger_.release(tile.bitmapIdentity)"))
        assertTrue(release.indexOf("bitmapReferenceLedger_.release(tile.bitmapIdentity)") <
            release.indexOf("env->DeleteGlobalRef(tile.bitmap)"))
        assertTrue(rollingBridge.contains("external fun nativeBitmapReferenceMask("))
        assertTrue(rollingBridge.contains("external fun nativeDiscardQueuedPrewarmBitmaps("))
        assertTrue(rollingBridge.contains(
            "external fun nativeDiscardQueuedFramesWithRetiredBitmaps(",
        ))
        assertTrue(rollingBridge.contains("bitmapIdentities: IntArray"))
        assertTrue(rollingNative.contains(
            "Java_ml_melun_mangaview_reader_NtkRollingNativeBridge_nativeBitmapReferenceMask",
        ))
        val discard = rollingNative.section(
            "int discardQueuedPrewarmBitmaps(",
            "bool tryDiscardQueuedFramesWithRetiredBitmaps(",
        )
        assertTrue(discard.contains("prewarmTiles_.erase(iterator)"))
        assertTrue(discard.contains("++prewarmQueueRevision_"))
        assertTrue(discard.contains("bitmapIdentities[candidate] != iterator->bitmapIdentity"))
        assertTrue(discard.indexOf("bitmapIdentities[candidate] != iterator->bitmapIdentity") <
            discard.indexOf("env->IsSameObject(iterator->bitmap"))
        assertTrue(discard.contains("for (auto& tile : retired) releaseTile(env, tile)"))
        assertFalse(discard.contains("frames_.erase"))
        assertTrue(rollingNative.contains(
            "Java_ml_melun_mangaview_reader_NtkRollingNativeBridge_nativeDiscardQueuedPrewarmBitmaps",
        ))
        val frameDiscard = rollingNative.section(
            "bool tryDiscardQueuedFramesWithRetiredBitmaps(",
            "void setDirectWifiTextureProfile(",
        )
        assertTrue(frameDiscard.contains("command = frames_.erase(command)"))
        assertTrue(frameDiscard.contains("command->token == protectedToken"))
        assertFalse(frameDiscard.contains("pendingDirectFrame_ ="))
        assertTrue(frameDiscard.contains("releaseFrame(env, command)"))
        assertTrue(rollingNative.contains(
            "Java_ml_melun_mangaview_reader_NtkRollingNativeBridge_nativeDiscardQueuedFramesWithRetiredBitmaps",
        ))
    }

    @Test
    fun activityQueueAbandonmentJoinsSurfaceRetirementDomain() {
        assertFalse(activity.contains("if (!tile.bitmap.isRecycled) tile.bitmap.recycle()"))
        assertTrue(
            Regex("renderView\\.retireSurfaceOwnedBitmaps\\(install\\.tiles\\.map\\(ReaderTile::bitmap\\)\\)")
                .findAll(activity)
                .count() >= 2,
        )
        assertTrue(activity.contains("override fun onSessionOwnedBitmapRetirement("))
        assertTrue(activity.contains("renderView.retireSurfaceOwnedBitmaps(bitmaps)"))
    }

    @Test
    fun hostPoolPressureRetiresOnlyReplaceablePixelsAroundTheExactViewport() {
        val pressure = session.section(
            "private fun scheduleHostExactPoolPressureTrim(minimumRetirementBytes: Long)",
            "private fun bitmapReleaseLocked(",
        )
        val cancel = session.section(
            "private fun cancelInternal(",
            "private fun releaseStrictRequiredEpisodePath(",
        )

        assertTrue(session.contains("HostExactHardwareTilePool.subscribePressure("))
        assertTrue(hostPool.contains("minimumRetirementBytes: Long"))
        assertTrue(hostPool.contains("MAX_POOL_BYTES = 64L * 1024L * 1024L"))
        assertTrue(hostPool.contains("MAX_ATOMIC_PAGE_BYTES = 128L * 1024L * 1024L"))
        assertTrue(hostPool.contains("HARD_MAX_POOL_BYTES = 320L * 1024L * 1024L"))
        assertTrue(hostPool.contains("listener(minimumRetirementBytes)"))
        assertTrue(pressure.contains("cleanup.execute"))
        assertFalse(pressure.contains("control.execute"))
        assertTrue(pressure.contains("val physicalVisibleRange = reportedPhysicalWindowLocked("))
        assertTrue(pressure.contains(
            "val decodeProtectionRange =\n                            reportedPhysicalDecodeProtectionWindowLocked(",
        ))
        assertTrue(pressure.contains("retainedWindowIncludingPhysicalViewport("))
        assertTrue(pressure.contains("physicalVisibleFirst = decodeProtectionRange.first"))
        assertTrue(pressure.contains("physicalVisibleLast = decodeProtectionRange.last"))
        assertTrue(pressure.contains("synchronized(pagesLock)"))
        assertTrue(pressure.contains("hostPressureRetirement = true"))
        assertTrue(pressure.contains("trimPendingBitmapDeliveriesOutside(keep[0], keep[1])"))
        assertTrue(pressure.contains("evictDeliveredBitmapsWithinHostPressureWindow("))
        assertTrue(pressure.contains("physicalVisibleFirst = physicalVisibleRange.first"))
        assertTrue(pressure.contains("physicalVisibleLast = physicalVisibleRange.last"))
        assertEquals(2, Regex("deferPublication = true").findAll(pressure).count())
        assertTrue(pressure.contains("postBitmapReleases(pressureReleases)"))
        assertTrue(
            pressure.indexOf("postBitmapReleases(pressureReleases)") >
                pressure.lastIndexOf("synchronized(pagesLock)"),
        )
        assertTrue(pressure.contains("remainingRetirementBytes"))
        val adaptive = session.section(
            "private fun evictDeliveredBitmapsWithinHostPressureWindow(",
            "/**\n     * The short direct-Wi-Fi profile",
        )
        assertTrue(adaptive.contains("latestReportedWindow.get()"))
        assertTrue(adaptive.contains("index in visibleFirst..visibleLast"))
        assertTrue(adaptive.contains("minimumRetirementBytes"))
        assertTrue(adaptive.contains("HostExactHardwareTilePoolPressurePolicy.retirementOrder("))
        assertTrue(adaptive.contains("val preserveStrictReady = true"))
        assertTrue(adaptive.contains("hostExactPoolPressureRetiredPages.addAll("))
        assertFalse(adaptive.contains("index !in deliveredOwned"))
        assertTrue(adaptive.contains("val owned = deliveredOwned.remove(candidate.index)"))
        assertTrue(adaptive.contains("bitmap = null"))
        assertTrue(
            adaptive.indexOf("hostExactPoolPressureRetiredPages.add(candidate.index)") <
                adaptive.indexOf("for (bitmap in uniqueTileBitmaps(tiles))")
        )
        val eviction = session.section(
            "private fun evictDeliveredBitmaps(",
            "private fun trimShortWebtoonLaunchPixelsOutsideWindow()",
        )
        assertEquals(
            2,
            Regex("if \\(!forcePressure && entry\\.key in protectedPixelWindow\\) continue")
                .findAll(eviction)
                .count(),
        )
        assertTrue(eviction.contains("val preserveStrictReady = forcePressure ||"))
        assertTrue(eviction.contains("if (hostPressureRetirement)"))
        assertTrue(
            eviction.indexOf("hostExactPoolPressureRetiredPages.add(entry.key)") <
                eviction.indexOf("iterator.remove()")
        )
        assertTrue(eviction.contains("if (!hostPressureRetirement) {"))
        val windowRequest = session.section(
            "val order = windowOrder(demandFirst, demandLast, safeAnchor, direction)",
            "private fun rehydrateSameStrictExactColdWindow(",
        )
        assertTrue(windowRequest.contains("hostExactPoolPressureRetiredPages.contains(index)"))
        assertTrue(windowRequest.contains("isPhysicalRehydrateEligible("))
        assertTrue(windowRequest.contains("if (!physicallyVisible) continue"))
        assertTrue(
            windowRequest.indexOf("listener.isPageAuthoritativeDrawableInstalled(index)") <
                windowRequest.indexOf("hostExactPoolPressureRetiredPages.remove(index)"),
        )
        assertTrue(session.contains("reportedPhysicalWindowLocked("))
        val listenerAdoption = session.section(
            "private fun adoptAuthoritativeListenerDrawable(",
            "private fun shouldKeepPreparedRunwayDecodeColdUntilInput(",
        )
        assertTrue(listenerAdoption.contains(
            "listener.isPageAuthoritativeDrawableCurrentlyInstalled(index)",
        ))
        assertTrue(listenerAdoption.contains(
            "if (hasListenerDrawableDelivery(index, page) && authoritativeInstalled) return true",
        ))
        assertFalse(listenerAdoption.contains(
            "if (hasListenerDrawableDelivery(index, page)) return true",
        ))
        assertTrue(session.contains(
            "fun isPageAuthoritativeDrawableCurrentlyInstalled(index: Int): Boolean",
        ))
        assertTrue(activity.contains(
            "override fun isPageAuthoritativeDrawableCurrentlyInstalled(index: Int): Boolean",
        ))
        assertTrue(activity.contains(
            "renderView.hasAuthoritativeOriginalPage(index)",
        ))
        val rehydrateWindow = session.section(
            "private fun reportedHostPressureRehydrateWindowLocked(",
            "private fun offerWindowAsync(",
        )
        assertTrue(rehydrateWindow.contains("physicalViewportIncludingBlockedForwardBody("))
        assertTrue(rehydrateWindow.contains("hostExactPoolPressureRetiredPages"))
        val exactRehydrate = session.section(
            "private fun shouldKeepHostPressureRetiredExactPageParked(",
            "private fun scheduleStrictAdjacentExactRehydrate(",
        )
        val pressureParkAt = exactRehydrate.indexOf(
            "shouldKeepHostPressureRetiredExactPageParked(index, page)",
        )
        val authoritativeInstallAt = exactRehydrate.indexOf(
            "listener.isPageAuthoritativeDrawableCurrentlyInstalled(index)",
        )
        val pressureClaimAt = exactRehydrate.indexOf(
            "claimHostPressureRetiredExactPageForRehydrate(index, page)",
        )
        val exactFlightAt = exactRehydrate.indexOf("StrictAdjacentRehydrateFlight(")
        assertTrue(exactRehydrate.contains("reportedHostPressureRehydrateWindowLocked("))
        assertFalse(exactRehydrate.contains("reportedPhysicalDecodeProtectionWindowLocked("))
        assertTrue(exactRehydrate.contains("isPhysicalRehydrateEligible("))
        assertTrue(exactRehydrate.contains("if (!physicallyVisible)"))
        assertTrue(exactRehydrate.contains("hostExactPoolPressureRetiredPages.remove(currentIndex)"))
        assertTrue(exactRehydrate.contains("HostPressureRehydrateClaim.CLAIMED"))
        assertTrue(exactRehydrate.contains(
            "pressureClaim == HostPressureRehydrateClaim.NOT_RETIRED",
        ))
        assertTrue(session.contains("val hostPressurePhysicalReentry: Boolean = false"))
        assertTrue(exactRehydrate.contains(
            "pressureClaim == HostPressureRehydrateClaim.CLAIMED",
        ))
        assertTrue(pressureParkAt >= 0 && authoritativeInstallAt > pressureParkAt)
        assertTrue(pressureClaimAt > authoritativeInstallAt)
        assertTrue(exactFlightAt > pressureClaimAt)
        assertTrue(exactFlightAt > pressureParkAt)
        val workerHandoff = session.section(
            "private fun handOffStrictExactAuthoritativeTiles(",
            "private fun requestPage(",
        )
        val mainDelivery = session.section(
            "private fun deliverDecodeResultOnMain(",
            "private fun dispatchTileDeliveryToListener(",
        )
        assertFalse(workerHandoff.contains("hostExactPoolPressureRetiredPages.remove("))
        assertFalse(mainDelivery.contains("hostExactPoolPressureRetiredPages.remove("))
        assertEquals(
            3,
            Regex("hostExactPoolPressureRetiredPages\\.remove\\(").findAll(session).count(),
        )
        val surfaceWindow = surface.section(
            "private fun windowRequestLocked(busy: Boolean)",
            "private fun dispatchWindowRequest(",
        )
        assertTrue(surfaceWindow.contains("firstVisiblePageLocked(scrollOffset)"))
        assertTrue(surfaceWindow.contains("physicalFirst"))
        assertTrue(surfaceWindow.contains("physicalLast"))
        assertTrue(cancel.contains("hostExactPoolPressureSubscription?.close()"))
    }

    @Test
    fun hostPressureRetiredAdjacentRunwayUsesProofPreservingSurfaceAck() {
        val rehydrate = session.section(
            "private fun runStrictAdjacentExactRehydrate(",
            "private fun handleStrictAdjacentRehydrateBodyUnavailable(",
        )
        val deliveryAck = session.section(
            "private fun requiresAuthoritativeInlineTileAck(",
            "private fun claimStrictInlineOriginalDecode(",
        )
        val physicalFrontierDelivery = session.section(
            "private fun shouldDeliverMissingPhysicalForwardFrontier(",
            "private fun shouldAllowImmediateGeneratedProofSurfaceDelivery(",
        )
        val structureDelivery = session.section(
            "private fun shouldDeliverInitialGeneratedDuringStructurePublish(",
            "private fun shouldAllowInitialRequestDuringStructurePublish(",
        )
        val activityAck = activity.section(
            "override fun onPageAuthoritativeTilesReady(",
            "private fun scheduleStrictAuthoritativeInstallFlush(",
        )
        val surfaceInstall = surface.section(
            "fun setPageAuthoritativeOriginalTiles(",
            "data class AuthoritativeTileInstall(",
        )
        val surfaceClear = surface.section(
            "fun clearPageBitmap(index: Int)",
            "fun clearAllPages()",
        )
        val rollingClear = surface.section(
            "fun clearRollingAuthoritativePage(",
            "fun retireSurfaceOwnedBitmaps(",
        )

        assertTrue(session.contains("val authoritativeAdjacentRehydrate: Boolean = false"))
        assertTrue(session.contains("val hostPressurePhysicalReentry: Boolean = false"))
        assertTrue(rehydrate.contains("authoritativeAdjacentRehydrate = true"))
        assertTrue(rehydrate.contains(
            "!flight.hostPressurePhysicalReentry &&",
        ))
        assertTrue(rehydrate.contains(
            "hostPressurePhysicalReentry = flight.hostPressurePhysicalReentry",
        ))
        assertTrue(session.contains("delivery.hostPressurePhysicalReentry ||"))
        assertTrue(session.contains("delivery.exactAdjacentPhysicalIntent ||"))
        assertTrue(session.contains(
            "isInsideProtectedNumericBitmapWindow(delivery.index, delivery.page)",
        ))
        assertEquals(
            2,
            Regex("!isDeliveryInsideProtectedNumericBitmapWindow\\(currentDelivery\\)")
                .findAll(session)
                .count(),
        )
        assertTrue(deliveryAck.contains("delivery.authoritativeAdjacentRehydrate ||"))
        assertTrue(physicalFrontierDelivery.contains(
            "!delivery.authoritativeAdjacentRehydrate ||\n" +
                "            (!delivery.hostPressurePhysicalReentry &&\n" +
                "                !delivery.exactAdjacentPhysicalIntent)",
        ))
        assertFalse(physicalFrontierDelivery.contains("isNtkGeneratedImageUrl("))
        assertFalse(physicalFrontierDelivery.contains("index !in anchor.."))
        assertFalse(physicalFrontierDelivery.contains(
            "!delivery.retainWhenBusy || delivery.proofDrawable",
        ))
        assertTrue(structureDelivery.contains(
            "shouldDeliverMissingPhysicalForwardFrontier(delivery, currentIndex)",
        ))
        assertTrue(structureDelivery.contains("canAccessAppendOnlyStablePrefix(currentIndex)"))
        assertTrue(structureDelivery.indexOf("currentIndex != delivery.index") <
            structureDelivery.indexOf("canAccessAppendOnlyStablePrefix(currentIndex)"))
        assertTrue(activityAck.contains("val adjacentExactRehydrate = seal != null"))
        assertTrue(activityAck.contains("syncRenderPageIdentity(index)"))
        assertTrue(activityAck.contains("renderView.setPageAuthoritativeOriginalTiles("))
        assertTrue(surfaceInstall.contains("isIndependentAdjacentRunwayOriginalLocked("))
        assertTrue(surfaceInstall.contains("page.stripAuthority == 0L"))
        assertTrue(surfaceInstall.contains("page.adjacentExactOwner == null"))
        assertTrue(surfaceInstall.contains("ReaderPreparedStore.isCanonicalOriginalProof("))
        assertTrue(surfaceClear.contains("allowIndependentAdjacentRunway: Boolean"))
        assertTrue(surfaceClear.contains("cleared = true"))
        assertTrue(rollingClear.contains(
            "clearPageBitmap(index, allowIndependentAdjacentRunway = true)",
        ))
        assertTrue(
            rollingClear.indexOf("retireSurfaceOwnedBitmapIdentitiesLocked(") <
                rollingClear.indexOf(
                    "clearPageBitmap(index, allowIndependentAdjacentRunway = true)",
                ),
        )
    }

    @Test
    fun adjacentExactCohortsSerializeThroughTheExternalSurfaceTransaction() {
        val append = session.section(
            "private fun appendRemainingAdjacentRunwayRefs(",
            "private fun installedAdjacentRunwaySourceIndexes(",
        )

        assertTrue(append.contains("if (isAdjacentDrawableBatchPublicationPending())"))
        assertTrue(append.contains("dispatchWhenStructureStable("))
        assertTrue(append.contains("pendingRemainingAdjacentRunwayAppends[path]"))
        val mainPublish = append.substring(append.indexOf("val posted = main.post"))
        val listenerAt = mainPublish.indexOf("listener.onAdjacentExactRunwayBatchReady(")
        val finallyAt = mainPublish.indexOf("} finally {")
        assertTrue(listenerAt >= 0 && finallyAt > listenerAt)
        assertFalse(mainPublish.substring(0, listenerAt).contains(
            "finishOwnedStructurePublish(structurePublishOwner)",
        ))
        assertTrue(mainPublish.substring(finallyAt).contains(
            "finishOwnedStructurePublish(structurePublishOwner)",
        ))
    }

    @Test
    fun adjacentBatchPublicationOwnsItsPageUntilSurfaceCommitOrRollback() {
        val route = session.section(
            "private fun routeStrictAdjacentExactRehydrate(",
            "private fun scheduleStrictAdjacentExactRehydrate(",
        )
        val completion = session.section(
            "private fun finishAdjacentDrawableBatchPublication(",
            "private fun isAdjacentDrawableBatchPublicationPending()",
        )

        assertTrue(route.contains("if (isAdjacentDrawableBatchPublicationPending(page))"))
        assertTrue(route.indexOf("if (isAdjacentDrawableBatchPublicationPending(page))") <
            route.indexOf("StrictAdjacentRehydrateFlight("))
        assertTrue(completion.contains("publication.pageIdentities.forEach"))
        assertTrue(completion.contains("adjacentDrawableBatchPublicationPages.computeIfPresent"))
        assertTrue(completion.contains("requestRetainedWindowAfterStructureChange()"))
        assertTrue(completion.contains("compareAndSet(pending, remaining)"))
    }

    @Test
    fun hostPressureClearCannotDeadlockBehindItsOwnTailDecodeStructureFence() {
        val append = session.section(
            "private fun appendRemainingAdjacentRunwayRefs(",
            "private fun installedAdjacentRunwaySourceIndexes(",
        )
        val release = session.section(
            "private fun postBitmapReleases(",
            "private fun postSessionOwnedBitmapRetirement(",
        )
        val stablePrefixReentry = session.section(
            "private fun canAccessAppendOnlyStablePrefix(",
            "private fun routeStrictAdjacentExactRehydrate(",
        )
        val window = session.section(
            "private fun requestWindow(\n",
            "private fun requestStrictExactColdWindow(",
        )
        val activityClear = activity.section(
            "override fun onPageHostPressureRollingEvicted(",
            "override fun onSessionOwnedBitmapRetirement(",
        )
        val surfaceClear = surface.section(
            "fun clearRollingAuthoritativePage(",
            "fun retireSurfaceOwnedBitmaps(",
        )

        assertTrue(append.contains("beginOwnedAppendOnlyStructurePublish(startIndex)"))
        assertTrue(
            append.indexOf("startIndex = pages.size") <
                append.indexOf("beginOwnedAppendOnlyStructurePublish(startIndex)"),
        )
        assertTrue(release.contains("stablePrefixHostPressureClear"))
        assertTrue(release.contains("canPublishStablePrefixRetirement("))
        assertTrue(release.contains("appendOnlyStructurePublishStablePrefixCounts.values"))
        assertTrue(
            release.indexOf("stablePrefixHostPressureClear") <
                release.indexOf("deferredForStructure = true"),
        )
        assertTrue(stablePrefixReentry.contains("canPublishStablePrefixRetirement("))
        assertTrue(stablePrefixReentry.contains("reportedHostPressureRehydrateWindowLocked("))
        assertFalse(stablePrefixReentry.contains("reportedPhysicalDecodeProtectionWindowLocked("))
        assertTrue(stablePrefixReentry.contains("routeStrictAdjacentExactRehydrate("))
        assertTrue(window.contains("requestHostPressureStablePrefixDuringAppendOnlyPublish("))
        assertTrue(activityClear.contains(
            "val cleared = renderView.clearRollingAuthoritativePage(index)",
        ))
        assertFalse(activityClear.contains("pagesReady &&"))
        assertTrue(activityClear.contains("onHostPressureSurfaceClearCompleted(index, cleared)"))
        assertTrue(surfaceClear.contains("clearRollingAuthoritativePage(index: Int): Boolean"))
        assertTrue(surfaceClear.contains("return true"))
    }

    @Test
    fun aNewDeliveryCannotEvictItselfBeforeItsSurfacePublication() {
        val tracking = session.section(
            "private fun trackDeliveredBitmap(",
            "private fun addReplacementBitmapReleases(",
        )
        val trimming = session.section(
            "private fun trimDeliveredBudgetLocked(",
            "private fun trimDeliveredBitmapsToBudget(",
        )
        val retained = session.section(
            "private fun trimRetainedBitmapUnderPressureLocked(",
            "/**\n     * Display indexes are mutable:",
        )

        assertEquals(2, Regex("protectedDeliveryIndex = resolvedIndex").findAll(tracking).count())
        assertTrue(trimming.contains("if (entry.key == protectedDeliveryIndex) continue"))
        assertTrue(trimming.contains("protectedDeliveryIndex,"))
        assertTrue(retained.contains(".filter { it != protectedDeliveryIndex }"))
    }

    @Test
    fun visibleNativeResourceLookupNeverWaitsBehindHostBufferAllocation() {
        val validationLookup = hostPool.section(
            "fun isActiveToken(bitmap: Bitmap): Boolean",
            "/** Zero means the logical token has no native exact-pixel storage. */",
        )
        val lookup = hostPool.section(
            "fun nativeHandle(bitmap: Bitmap): Long",
            "fun storageBytes(bitmap: Bitmap): Long?",
        )
        val batchRetirement = hostPool.section(
            "fun retireAll(bitmaps: Iterable<Bitmap>)",
            "private fun createTokensForSlots(",
        )
        val batchPublication = hostPool.section(
            "private fun createTokensForSlots(",
            "private fun acquireSlots(",
        )
        val surfaceRetirement = surface.section(
            "private fun drainRollingAuthoritativeRecycles()",
            "private fun completeRollingNativeDestroy()",
        )

        assertTrue(hostPool.contains(
            "private val nativeResourceSnapshot = ConcurrentHashMap<Bitmap, Slot>()",
        ))
        assertTrue(validationLookup.contains("nativeResourceSnapshot.containsKey(bitmap)"))
        assertTrue(validationLookup.contains("nativeResourceSnapshot[bitmap]"))
        assertFalse(validationLookup.contains("synchronized(lock)"))
        assertFalse(validationLookup.contains("owners[bitmap]"))
        assertTrue(lookup.contains("nativeResourceSnapshot[bitmap]?.nativeHandle"))
        assertFalse(lookup.contains("synchronized(lock)"))
        assertFalse(hostPool.contains("publishNativeResourceSnapshotLocked"))
        assertTrue(batchRetirement.contains("nativeResourceSnapshot.remove(bitmap)"))
        assertFalse(batchRetirement.contains("bitmap.recycle()"))
        assertTrue(batchPublication.contains("nativeResourceSnapshot.put(token, slot)"))
        assertTrue(batchPublication.contains("nativeResourceSnapshot.remove(token)"))
        assertTrue(surfaceRetirement.contains("HostExactHardwareTilePool.retireAll(recyclable)"))
        assertFalse(surfaceRetirement.contains("HostExactHardwareTilePool.retire(bitmap)"))
    }

    @Test
    fun hostExactLeaseIdentitiesArePreallocatedButNeverReusedAfterPublication() {
        val tokenPublication = hostPool.section(
            "private fun createTokensForSlots(",
            "private fun acquireSlots(",
        )
        val retirement = hostPool.section(
            "fun retireAll(bitmaps: Iterable<Bitmap>)",
            "private fun createTokensForSlots(",
        )
        val reserve = hostPool.section(
            "private fun takeFreshTokens(",
            "private fun acquireSlots(",
        )

        assertTrue(tokenPublication.contains("takeFreshTokens(pageSlots.size)"))
        assertTrue(tokenPublication.contains("returnFreshTokens(tokens)"))
        assertTrue(retirement.contains("scheduleTokenReserveRefillIfNeeded()"))
        assertFalse(retirement.contains("returnFreshTokens("))
        assertTrue(reserve.contains("TOKEN_RESERVE_TARGET"))
        assertTrue(reserve.contains("lastPoolActivityAtMs"))
    }

    @Test
    fun hostExactIdentityReserveIsPrimedOffMainBeforeAnyReaderCanScroll() {
        val prime = hostPool.section(
            "fun primeProcessTokenReserve()",
            "/**\n     * The published resource index is the renderer-facing ownership authority.",
        )

        assertTrue(prime.contains("synchronized(tokenCreationLock)"))
        assertTrue(prime.contains("freshTokenReserve.size < TOKEN_RESERVE_TARGET"))
        assertTrue(prime.contains("tokenReservePrimed = true"))
        assertTrue(application.contains(
            "AppDispatchers.runIo(HostExactHardwareTilePool::primeProcessTokenReserve)",
        ))
        assertTrue(application.contains("HostExactHardwareTilePool.INSTANCE.supported("))
        assertTrue(application.contains("!NtkNativeSurfaceFrameRatePolicy.INSTANCE.isHwuiOverrideEnabled()"))
        assertFalse(application.contains("HostExactHardwareTilePool.primeProcessTokenReserve();"))
    }

    @Test
    fun hostExactDecodeReservesACompletePageBeforeCopyingItsFirstTile() {
        val decode = hostPool.section(
            "private fun decodePageWithDecoder(",
            "fun retire(bitmap: Bitmap)",
        )
        val fileDecode = hostPool.section(
            "/** File-backed counterpart that never materializes the compressed body in ART's heap. */",
            "private fun decodePageWithDecoder(",
        )
        val acquire = hostPool.section(
            "private fun acquireSlots(",
            "/** Slow gfxstream lifetime calls intentionally execute before the brief commit monitor. */",
        )
        val nativeAllocation = hostPool.section(
            "private fun fulfillSlotAllocationPlan(",
            "private fun rollbackSlotAllocationPlan(",
        )
        val slowAllocationPrefix =
            nativeAllocation.substringBefore("val poolStats = synchronized(lock)")

        val scratch = decode.indexOf("val scratchLease = acquireScratch(")
        val batch = decode.indexOf("val reservedSlots = acquireSlots(")
        val copy = decode.indexOf("nativeCopyExactBitmapToHardwareTile(")
        val tokens = decode.indexOf("createTokensForSlots(reservedSlots)")
        assertTrue(scratch >= 0)
        assertTrue(batch > scratch)
        assertTrue(copy > batch)
        assertTrue(tokens > copy)
        assertTrue(hostPool.contains("BitmapRegionDecoder.newInstance("))
        assertTrue(decode.contains("decoder.decodeRegion("))
        assertTrue(decode.contains("inBitmap = scratchLease.bitmap"))
        assertTrue(decode.contains("sourceWidth,\n                        0,\n                        span"))
        assertTrue(fileDecode.contains("nativeDecodeExactFileToHardwareTiles("))
        assertFalse(fileDecode.contains("readBytes()"))
        assertFalse(fileDecode.contains("BitmapRegionDecoder.newInstance("))
        assertFalse(decode.contains("acquireSlot(capacityWidth"))
        assertTrue(acquire.contains("count: Int"))
        assertTrue(acquire.contains("requiredBatch=\$newBytes"))
        assertTrue(acquire.contains("SlotAllocationPlan("))
        assertTrue(acquire.contains("allocatedBytes += newBytes"))
        assertTrue(acquire.contains("return fulfillSlotAllocationPlan("))
        assertFalse(acquire.contains("nativeAllocateExactHardwareBuffer("))
        assertFalse(acquire.contains("nativeReleaseExactHardwareBuffer("))
        assertTrue(slowAllocationPrefix.contains("nativeReleaseExactHardwareBuffer("))
        assertTrue(slowAllocationPrefix.contains("nativeAllocateExactHardwareBuffer("))
        assertFalse(slowAllocationPrefix.contains("synchronized(lock)"))
        assertTrue(nativeAllocation.contains("return plan.reusable + allocatedSlots"))
        assertTrue(acquire.contains("signalPressureLocked("))
        assertTrue(hostPool.contains("listener(minimumRetirementBytes)"))
    }

    @Test
    fun hostExactStorageUsesAppOwnedCpuTilesWithoutEnteringEmulatorGralloc() {
        val native = File("src/main/cpp/ntk_rolling_surface_renderer.cpp").readText()
        val allocation = native.section(
            "Java_ml_melun_mangaview_reader_NtkRollingNativeBridge_nativeAllocateExactHardwareBuffer(",
            "Java_ml_melun_mangaview_reader_NtkRollingNativeBridge_nativeDecodeExactFileToHardwareTiles(",
        )
        assertTrue(allocation.contains("posix_memalign(&pixels, 64U, allocationBytes)"))
        assertTrue(allocation.contains("ExactCpuTileStorage"))
        assertTrue(allocation.contains("validExactCpuTile(storage)"))
        assertFalse(allocation.contains("AHardwareBuffer_allocate"))
        assertFalse(allocation.contains("AHardwareBuffer_unlock"))
        assertTrue(allocation.contains("nativeReleaseExactHardwareBuffer"))
    }

    @Test
    fun strictResidentFilesBypassRedundantBitmapFactoryBoundsOnHostExactStorage() {
        val session = source("reader/ReaderSession.kt")
        val fileDecode = session.section(
            "private fun decodeStrictExactPageFile(",
            "/**\n     * Decodes exact source rows into reusable host-emulator HardwareBuffers.",
        )

        assertTrue(fileDecode.contains("postStrictExactPageBounds("))
        assertTrue(fileDecode.contains("decodeStrictExactHardwareTiles("))
        assertTrue(fileDecode.contains("decodePageUnlocked("))
        assertFalse(fileDecode.contains("BitmapFactory.decode"))
        assertTrue(session.windowAround("lease.file.isFile ->", 900).contains(
            "decodeStrictExactPageFile(",
        ))
        assertTrue(session.windowAround("strictBody?.file?.isFile == true", 900).contains(
            "decodeStrictExactPageFile(",
        ))
        assertTrue(session.windowAround("opened.predecodedOriginal", 2600).contains(
            "decodeStrictExactPageFile(",
        ))
    }

    @Test
    fun forwardHistoryRetirementAvoidsWholeSceneAliasAllocationForFreshHostTokens() {
        val session = source("reader/ReaderSession.kt")
        val retirement = session.section(
            "private fun retireConsumedForwardHistoryPixels(",
            "private fun scheduleForwardReadingRetry(",
        )

        assertTrue(retirement.contains("var hostExactPrefixOnly = true"))
        assertTrue(retirement.contains("HostExactHardwareTilePool.isActiveToken"))
        assertTrue(retirement.contains("val retainedIdentities = if (hostExactPrefixOnly)"))
        assertTrue(retirement.contains("entry.value.forEach").not())
    }

    @Test
    fun hostExactBulkDecodeNeverPromotesAboveBackgroundDuringPhysicalRendering() {
        val session = source("reader/ReaderSession.kt")
        val priority = session.windowAround(
            "strictExactBodyDescriptors.size >=",
            1200,
        )

        assertTrue(priority.contains("!hostGpuEmulatorRuntime"))
        assertTrue(priority.contains("Process.THREAD_PRIORITY_DEFAULT"))
        assertTrue(priority.contains("Process.THREAD_PRIORITY_BACKGROUND"))
    }

    @Test
    fun hostExactFileDecodeWritesCpuStorageWithoutAnyGrallocTransaction() {
        val native = File("src/main/cpp/ntk_rolling_surface_renderer.cpp").readText()
        val decode = native.section(
            "Java_ml_melun_mangaview_reader_NtkRollingNativeBridge_nativeDecodeExactFileToHardwareTiles(",
            "Java_ml_melun_mangaview_reader_NtkRollingNativeBridge_nativeCopyExactBitmapToHardwareTile(",
        )

        val completeScratchDecode = decode.indexOf(
            "bool completeImageDecoded = false;",
        )
        val tileScratchDecode = decode.indexOf("if (valid && !completeImageDecoded)")
        val cpuStorageValidation = decode.indexOf(
            "validExactCpuTile(storage)",
            completeScratchDecode,
        )
        val displayScale = decode.indexOf("valid = scaleRgba8888(", cpuStorageValidation)
        assertTrue(decode.contains("static std::uint8_t* exactDecodeScratch = nullptr"))
        assertTrue(decode.contains("std::realloc(exactDecodeScratch, requiredScratchBytes)"))
        assertTrue(decode.contains("completeImageFitsScratch"))
        assertTrue(decode.contains("completeImageDecoded = valid"))
        assertTrue(completeScratchDecode >= 0)
        assertTrue(cpuStorageValidation > completeScratchDecode)
        assertTrue(tileScratchDecode > cpuStorageValidation)
        assertTrue(displayScale > tileScratchDecode)
        assertTrue(decode.contains("storage->contentWidth ="))
        assertTrue(decode.contains("storage->logicalWidth ="))
        assertFalse(decode.contains("decoder, pixels"))
        assertFalse(decode.contains("AHardwareBuffer_lock"))
        assertFalse(decode.contains("AHardwareBuffer_unlock"))
        assertFalse(decode.contains("symbols.lock("))
    }

    @Test
    fun standardPngDecodeStreamsRowsIntoDisplayStorageWithoutSourceSizedScratch() {
        val native = File("src/main/cpp/ntk_rolling_surface_renderer.cpp").readText()
        val streaming = native.section(
            "bool decodeScaledPngFileToExactCpuTiles(",
            "bool decodeExactJpegFile(",
        )
        val decode = native.section(
            "Java_ml_melun_mangaview_reader_NtkRollingNativeBridge_nativeDecodeExactFileToHardwareTiles(",
            "Java_ml_melun_mangaview_reader_NtkRollingNativeBridge_nativeCopyExactBitmapToHardwareTile(",
        )

        assertTrue(streaming.contains("png_read_row(png, row, nullptr)"))
        assertTrue(streaming.contains("sourceRows.resize(sourceStride * 2U)"))
        assertTrue(streaming.contains("storage->pixels"))
        assertTrue(streaming.contains("RgbaHorizontalSample"))
        assertFalse(streaming.contains("expectedHeight) * sourceStride"))
        assertTrue(decode.contains("scaledDecodeValid && !directPng"))
        assertTrue(decode.contains("decodeScaledPngFileToExactCpuTiles("))
        assertTrue(decode.contains("scaledPngDirect = scaledDecodeValid"))
        assertTrue(decode.contains("scaledDecodeValid && !scaledPngDirect"))
    }

    @Test
    fun geometryOnlyProducerPoolNeverWaitsBehindTheCommitThread() {
        val surface = source("reader/ReaderSurfaceView.kt")
        val acquire = surface.section(
            "private fun acquireDeferredNativeGeometrySubmission(",
            "private fun releaseDeferredNativeGeometrySubmission(",
        )
        val release = surface.section(
            "private fun releaseDeferredNativeGeometrySubmission(",
            "private fun primeDeferredNativeGeometryPool()",
        )
        val prime = surface.section(
            "private fun primeDeferredNativeGeometryPool()",
            "/** Commit-lane preparation of a full immutable scene; never runs on the physical producer. */",
        )

        assertTrue(surface.contains("AtomicReference<DeferredNativeGeometrySubmission?>(null)"))
        assertTrue(surface.contains("deferredNativeGeometryPoolSize = AtomicInteger(0)"))
        assertTrue(surface.contains("var poolNext: DeferredNativeGeometrySubmission? = null"))
        assertTrue(acquire.contains("deferredNativeGeometryPool.compareAndSet(head, next)"))
        assertTrue(release.contains("deferredNativeGeometryPool.compareAndSet(head, submission)"))
        assertFalse(acquire.contains("synchronized("))
        assertFalse(release.contains("synchronized("))
        assertTrue(prime.contains("MAX_DEFERRED_NATIVE_GEOMETRY_POOL"))
        assertTrue(surface.windowAround("private fun startRenderThreadLocked()", 500).contains(
            "primeDeferredNativeGeometryPool()",
        ))
    }

    @Test
    fun hostExactFileDecodeDoesNotPermanentlyDemoteSharedPreparationWorkers() {
        val native = File("src/main/cpp/ntk_rolling_surface_renderer.cpp").readText()
        val decode = native.section(
            "Java_ml_melun_mangaview_reader_NtkRollingNativeBridge_nativeDecodeExactFileToHardwareTiles(",
            "Java_ml_melun_mangaview_reader_NtkRollingNativeBridge_nativeCopyExactBitmapToHardwareTile(",
        )

        assertFalse(decode.contains("setpriority("))
        assertFalse(decode.contains("sched_setscheduler"))
    }

    @Test
    fun rawExactBufferBorrowMovesWithThePrewarmBitmapLedgerLease() {
        val native = File("src/main/cpp/ntk_rolling_surface_renderer.cpp").readText()
        val prewarm = native.section(
            "bool prewarm(JNIEnv* env, std::int64_t structureEpoch,",
            "/**\n     * Retires only cache-only work",
        )
        val transfer = prewarm.substringAfter("prewarmTiles_.push_back(tile);")
            .substringBefore("accepted = true;")

        assertTrue(transfer.contains("tile.bitmap = nullptr"))
        assertTrue(transfer.contains("tile.bitmapReferenceTracked = false"))
        assertTrue(transfer.contains("tile.exactHardwareBuffer = nullptr"))
        assertTrue(transfer.contains("tile.exactCpuBuffer = nullptr"))
        val releaseTile = native.section(
            "void releaseTile(JNIEnv* env, FrameTile& tile)",
            "void releaseFrame(JNIEnv* env, FrameCommand& command)",
        )
        assertFalse(releaseTile.contains("exactLifetime.release"))
        assertTrue(releaseTile.contains("bitmapReferenceLedger_.release"))
    }

    @Test
    fun appendOnlyStructureEpochReusesExactVisibleTextures() {
        val native = File("src/main/cpp/ntk_rolling_surface_renderer.cpp").readText()
        val upload = native.section(
            "bool uploadTile(",
            "PrewarmUploadResult uploadPrewarmTile(",
        )
        val migrate = upload.indexOf("TextureTile migrated = std::move(prior->second)")
        val identityFastPath = upload.indexOf(
            "existing->second.contentIdentity == tile.contentIdentity",
        )

        assertTrue(upload.contains("prior->first.page != tile.key.page"))
        assertTrue(upload.contains("prior->first.slot != tile.key.slot"))
        assertTrue(upload.contains("prior->second.width != tile.sourceWidth"))
        assertTrue(upload.contains("prior->second.height != expectedHeight"))
        assertTrue(migrate >= 0)
        assertTrue(identityFastPath > migrate)
    }

    @Test
    fun enteredAdjacentEpisodeDoesNotPinConsumedInitialRunwayUnderPoolPressure() {
        val protection = session.section(
            "private fun protectedStrictExactLaunchDisplayIndexes(",
            "private fun shouldProtectDeliveredPixelFromClear(",
        )
        val viewportGate = protection.indexOf("if (isViewportInsideEpisode(page.manga)) continue")
        val initialRunwayProtection = protection.indexOf(
            "page.sourceIndex < requiredInitialAdjacentRunwayPages(page.manga)",
        )

        assertTrue(viewportGate >= 0)
        assertTrue(initialRunwayProtection > viewportGate)
    }

    @Test
    fun appendOnlyEpochDoesNotEagerlyDestroyEveryOffscreenTexture() {
        val native = File("src/main/cpp/ntk_rolling_surface_renderer.cpp").readText()
        val prune = native.section(
            "void pruneTexturesToBudget(",
            "void pruneTextures(const FrameCommand& frame)",
        )
        val headroom = native.section(
            "bool prepareVisibleFrameTextureHeadroom(",
            "bool hasOptionalPrewarmTextureHeadroom(",
        )

        assertFalse(prune.contains("entry->first.structureEpoch == structureEpoch"))
        assertTrue(prune.contains("residentTextureBytes_ > budget"))
        assertTrue(headroom.contains("plan.freshNames > 0"))
    }

    @Test
    fun adjacentRunwayReleaseRetainsGenerationBoundCommitHistoryAcrossEviction() {
        val source = session

        assertTrue(source.contains("drawableRunwayCommittedSources: MutableSet<Int>"))
        assertTrue(source.contains("claim.drawableRunwayCommittedSources.addAll(readySources)"))
        assertTrue(source.contains("claim.drawableRunwayCommittedSources.add(page.sourceIndex)"))
        assertTrue(
            source.contains(
                "if (complete) signalDirectWifiAdjacentDrawableRunwayCommitted(page.manga)",
            ),
        )
        assertTrue(source.split("recordAdjacentStrictDrawableCommit(").size >= 4)
        assertTrue(
            source.contains(
                "claim.drawableRunwayCommittedSources.containsAll(requiredSources)",
            ),
        )
        assertFalse(
            source.contains(
                "val allReady = snapshot.first.all { (index, page) ->",
            ),
        )
    }

    @Test
    fun physicalScrollProgressDebounceDoesNotScanMainQueuePerFrame() {
        val schedule = activity.section(
            "private fun scheduleSaveReadingProgress(",
            "private fun saveCurrentReadingProgress()",
        )
        val owner = activity.section(
            "private val saveProgressRunnable = object : Runnable",
            "private val drawableReadyDescriptionRunnable",
        )

        assertTrue(schedule.contains("armProgressSaveDeadline()"))
        assertFalse(schedule.substringBefore("private fun cancelProgressSaveDeadline()")
            .contains("removeCallbacks(saveProgressRunnable)"))
        assertTrue(owner.contains("progressSaveDeadlineMs - SystemClock.uptimeMillis()"))
        assertTrue(owner.contains("progressHandler.postDelayed(this, remainingMs)"))
    }

    @Test
    fun physicalWindowAndHistoryTrimsDoNotScanUnboundedWorkUnderPagesLock() {
        val window = session.section(
            "private fun requestWindow(\n",
            "private fun requestStrictExactColdWindow(",
        )
        val protectedCommit = window
            .substringAfter("val retainedCommitted = if (protectedBitmapWindow != null)")
            .substringBefore("} else {")
        val afterCommit = window.substringAfter("if (!retainedCommitted)")
        val history = session.section(
            "private fun retireConsumedForwardHistoryPixels(",
            "private fun scheduleForwardReadingRetry()",
        )

        assertFalse(protectedCommit.contains("trimDeliveredPixelsForRetainedWindow("))
        assertFalse(protectedCommit.contains("trimPendingProtectedNumericBitmaps("))
        assertTrue(afterCommit.contains("trimDeliveredPixelsForRetainedWindow(retainFirst, retainLast)"))
        assertTrue(afterCommit.contains("trimPendingProtectedNumericBitmaps(retainFirst, retainLast)"))
        assertFalse(history.contains("0 until minOf(retireBefore, pages.size)"))
        assertTrue(history.contains("pages.getOrNull(entry.key)"))
    }

    @Test
    fun resolvedPageGeometryUpdatesSuffixIncrementallyDuringPhysicalScroll() {
        val update = surface.section(
            "private fun updatePageHeightDeltaLocked(",
            "private fun applyPageHeightChangeLocked(",
        )

        assertTrue(update.contains("pageTopDeltas.add(index + 1, delta)"))
        assertTrue(update.contains("contentHeight = max(0f, contentHeight + delta)"))
        assertTrue(update.contains("pageTops.size != pages.size"))
        assertTrue(update.contains("pageTopDeltas.size != pages.size"))
        assertTrue(surface.contains("var placeholderRatio: Float"))
        assertTrue(surface.contains("viewWidth * page.placeholderRatio"))
    }

    @Test
    fun discardedProgressiveExactTailRetainsPhysicalRehydrateAuthority() {
        val busyDrain = session.section(
            "private fun deliverBusyDecodeResults()",
            "private fun enqueueRetainedPrimedDeliveries()",
        )
        val drop = busyDrain.substringAfter("} else {\n                pendingDeliveryWidths.remove(index)")
            .substringBefore("\n            }")
        assertTrue(drop.contains("parkUndeliveredHostExactAdjacentForPhysicalRehydrate(currentDelivery)"))
        assertTrue(drop.indexOf("parkUndeliveredHostExactAdjacentForPhysicalRehydrate") <
            drop.indexOf("recycleDecodeResult"))
        assertTrue(busyDrain.contains("HostExactHardwareTilePool.isActiveToken(tile.bitmap)"))
        assertTrue(busyDrain.contains("strictAdjacentRehydrateIdentity(delivery.page)"))
        assertTrue(busyDrain.contains("hostExactPoolPressureRetiredPages.add(currentIndex)"))
    }

    private fun source(relative: String): String =
        File("src/main/java/ml/melun/mangaview/$relative").readText()

    private fun String.section(startMarker: String, endMarker: String): String {
        val start = indexOf(startMarker)
        val end = indexOf(endMarker, start + startMarker.length)
        assertTrue("Missing source marker: $startMarker", start >= 0)
        assertTrue("Missing source marker: $endMarker", end > start)
        return substring(start, end)
    }

    private fun String.windowAround(marker: String, radius: Int): String {
        val center = indexOf(marker)
        assertTrue("Missing source marker: $marker", center >= 0)
        return substring(
            (center - radius).coerceAtLeast(0),
            (center + marker.length + radius).coerceAtMost(length),
        )
    }
}
