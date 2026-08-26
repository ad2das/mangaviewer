package ml.melun.mangaview.reader

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderNativeLifecycleRecoveryTest {
    private val surface = File(
        "src/main/java/ml/melun/mangaview/reader/ReaderSurfaceView.kt",
    ).readText()
    private val rollingNative = File(
        "src/main/cpp/ntk_rolling_surface_renderer.cpp",
    ).readText()
    private val rollingBridge = File(
        "src/main/java/ml/melun/mangaview/reader/NtkRollingNativeBridge.kt",
    ).readText()
    private val textureHeadroomPlanner = File(
        "src/main/cpp/RollingTextureHeadroomPlanner.h",
    ).readText()

    @Test
    fun lifecycleCommandsCloseSubmissionBeforeWakingTheWorker() {
        val prepare = slice(rollingNative, "bool prepare(", "bool attach(")
        assertTrue(prepare.indexOf("surfaceAttached_ = false") in
            0 until prepare.indexOf("preparePending_ = true"))

        val attach = slice(rollingNative, "bool attach(", "void detach(")
        assertTrue(attach.indexOf("surfaceAttached_ = false") in
            0 until attach.indexOf("attachPending_ = true"))

        val detach = slice(rollingNative, "void detach(", "std::int64_t submit(")
        assertTrue(detach.indexOf("surfaceAttached_ = false") in
            0 until detach.indexOf("detachPending_ = true"))

        val run = slice(rollingNative, "void run() noexcept", "JavaVM* vm_")
        val reopen = run.indexOf("surfaceAttached_ = backendAttached_ &&")
        assertTrue(reopen >= 0)
        val reopenEnd = run.indexOf(';', reopen)
        val predicate = run.substring(reopen, reopenEnd)
        assertTrue(predicate.contains("!preparePending_"))
        assertTrue(predicate.contains("!attachPending_"))
        assertTrue(predicate.contains("!detachPending_"))
        assertTrue(predicate.contains("!stopped_"))
        assertTrue(predicate.contains("!failed_"))
        assertTrue(run.contains("if (doPrepare && !doAttach && !prepareBackend(prepareCommand))"))
    }

    @Test
    fun resizeEnqueuesNativeDetachBeforeAnyTargetPrepare() {
        val resize = slice(
            surface,
            "override fun onSizeChanged(",
            "override fun onDetachedFromWindow(",
        )
        val detach = resize.indexOf("NtkRollingNativeBridge.nativeDetach(handle, epoch)")
        val prepare = resize.indexOf("prepareRollingNativeRenderTargetsLocked(width, height)")
        assertTrue(detach >= 0)
        assertTrue(prepare > detach)
        assertEquals(1, occurrenceCount(resize, "prepareRollingNativeRenderTargetsLocked("))
        assertTrue(resize.contains("prepareNativeTargetsAfterLifecycleFence"))
    }

    @Test
    fun recoveryStateCompletesBeforeTheRemovableMainReattach() {
        val fatal = slice(
            surface,
            "fun onNtkRollingRendererFatal(",
            "private fun recoverDirectSurfaceSubmission(",
        )
        val destroy = fatal.indexOf("NtkRollingNativeBridge.nativeDestroy(retiredHandle)")
        val finish = fatal.indexOf(
            "finishRollingNativeRecoveryAfterDestroy(recoveryGeneration)",
            destroy,
        )
        val uiPost = fatal.indexOf("mainHandler.post {", finish)
        assertTrue(destroy >= 0)
        assertTrue(finish > destroy)
        assertTrue(uiPost > finish)

        val completion = slice(
            surface,
            "private fun finishRollingNativeRecoveryAfterDestroy(",
            "private fun completeRollingNativeRecovery(",
        )
        val owner = completion.indexOf("ownsRollingNativeRecoveryCompletion(")
        val releasePending = completion.indexOf("rollingNativeRecoveryPending = false")
        val releaseFatal = completion.indexOf("rollingNativeFatal = false")
        assertTrue(owner >= 0)
        assertTrue(releasePending > owner)
        assertTrue(releaseFatal > releasePending)

        val generationFence = fatal.indexOf(
            "rollingNativeRecoveryGeneration == recoveryGeneration",
            uiPost,
        )
        val reattach = fatal.indexOf("completeRollingNativeRecovery(reason)", generationFence)
        assertTrue(generationFence > uiPost)
        assertTrue(reattach > generationFence)

        // The declaration is the sole zero assignment. A physical presentation must not replenish
        // the View-lifetime recreate budget.
        assertEquals(1, occurrenceCount(surface, "rollingNativeRecoveryAttempts = 0"))
    }

    @Test
    fun authoritativeBitmapRecycleWaitsForItsExactNativeReferenceOwners() {
        val clear = slice(
            surface,
            "fun clearRollingAuthoritativePage(",
            "private fun completeRollingNativeDestroy()",
        )
        val markRetiring = clear.indexOf(
            "retireSurfaceOwnedBitmapIdentitiesLocked(bitmaps, holdReferenced = true)",
        )
        val clearPage = clear.indexOf(
            "clearPageBitmap(index, allowIndependentAdjacentRunway = true)",
        )
        assertTrue(markRetiring >= 0)
        assertTrue(clearPage > markRetiring)
        assertFalse(clear.contains("ROLLING_AUTHORITATIVE_RECYCLE_DELAY_MS"))

        val drain = slice(
            surface,
            "private fun drainRollingAuthoritativeRecycles()",
            "private fun completeRollingNativeDestroy()",
        )
        assertTrue(drain.contains("!nativeFrameSubmissionInFlight"))
        assertTrue(drain.contains("deferredNativeSceneSubmissionsInFlight.get() == 0"))
        assertTrue(drain.contains("!nativeTexturePrewarmSubmissionInFlight"))
        assertTrue(drain.contains("rollingNativeDestroyInFlightCount == 0"))
        assertTrue(drain.contains("NtkRollingNativeBridge.nativeBitmapReferenceMask("))
        val retirementDispatch = drain.indexOf("ReaderBitmapRetirementDispatcher.dispatch(")
        assertTrue(drain.indexOf("NtkRollingNativeBridge.nativeBitmapReferenceMask(") in
            0 until retirementDispatch)
        assertFalse(drain.contains("bitmap.recycle()"))
        assertFalse(drain.contains("NtkRollingNativeBridge.nativeIsQuiescent(handle)"))

        val prewarm = slice(
            surface,
            "private fun flushResidentNativeTexturePrewarm()",
            "private fun postResidentNativeTexturePrewarmLocked()",
        )
        val singleFlightGate = prewarm.indexOf("if (nativeTexturePrewarmSubmissionInFlight)")
        val acquirePrewarm = prewarm.indexOf("nativeTexturePrewarmSubmissionInFlight = true")
        val nativePrewarm = prewarm.indexOf("NtkRollingNativeBridge.nativePrewarm(")
        val releasePrewarm =
            prewarm.indexOf("nativeTexturePrewarmSubmissionInFlight = false", nativePrewarm)
        val dirtiedWhileInFlight = prewarm.indexOf(
            "val dirtiedWhileInFlight = nativeTexturePrewarmDirty",
            nativePrewarm,
        )
        val rejected = prewarm.indexOf("if (!accepted)", dirtiedWhileInFlight)
        val repostGate = prewarm.indexOf("(accepted || dirtiedWhileInFlight)", rejected)
        assertTrue(singleFlightGate >= 0)
        assertTrue(acquirePrewarm > singleFlightGate)
        assertTrue(nativePrewarm > acquirePrewarm)
        assertTrue(releasePrewarm > nativePrewarm)
        assertTrue(dirtiedWhileInFlight > nativePrewarm)
        assertTrue(rejected > dirtiedWhileInFlight)
        assertTrue(repostGate > rejected)
        assertTrue(prewarm.substring(singleFlightGate, acquirePrewarm)
            .contains("nativeTexturePrewarmDirty = true"))
        assertTrue(prewarm.substring(rejected, repostGate)
            .contains("nativeTexturePrewarmDirty = true"))
        assertFalse(prewarm.substring(rejected, repostGate)
            .contains("requestResidentNativeTexturePrewarmLocked()"))

        val requestPrewarm = slice(
            surface,
            "private fun requestResidentNativeTexturePrewarmLocked()",
            "private fun effectiveNativeTexturePrewarmAnchorLocked(",
        )
        assertTrue(requestPrewarm.contains(
            "nativeTexturePrewarmFlushPosted || nativeTexturePrewarmPaused",
        ))
        val pausePrewarm = slice(
            surface,
            "private fun setNativeTexturePrewarmPausedLocked(paused: Boolean)",
            "fun setPageBitmap(index: Int, bitmap: Bitmap)",
        )
        val unpauseGate = pausePrewarm.indexOf("if (!paused && nativeTexturePrewarmDirty)")
        val coalescedRequest = pausePrewarm.indexOf(
            "requestResidentNativeTexturePrewarmLocked()",
            unpauseGate,
        )
        assertTrue(unpauseGate >= 0)
        assertTrue(coalescedRequest > unpauseGate)

        val submit = slice(surface, "private fun submitNativeFrame(", "private fun drawState(")
        val prepare = slice(surface, "private fun prepareRenderWork(", "private fun finishRenderedFrame(")
        val packTile = slice(
            surface,
            "private fun packNativeFrameTile(",
            "private fun nativeTileIntersectsViewport(",
        )
        val capturedState = prepare.indexOf("val nativeCaptureLease =")
        val retirementFence = prepare.indexOf(
            "deferredNativeSceneSubmissionsInFlight.incrementAndGet()",
            capturedState,
        )
        val lease = submit.indexOf("nativeFrameSubmissionInFlight = true")
        val nativeSubmit = submit.indexOf("NtkRollingNativeBridge.nativeSubmit(")
        val release = submit.indexOf("nativeFrameSubmissionInFlight = false", nativeSubmit)
        assertTrue(capturedState >= 0)
        assertTrue(retirementFence > capturedState)
        assertTrue(lease >= 0)
        assertTrue(nativeSubmit > lease)
        assertTrue(release > nativeSubmit)
        assertTrue(submit.contains("nativeFrameTileDataScratch"))
        assertTrue(submit.contains("nativeFrameGeometryScratch"))
        assertTrue(submit.contains("nativeFrameBitmapScratch"))
        assertTrue(submit.contains("nativeFrameResourceScratch"))
        assertTrue(packTile.contains("HostExactHardwareTilePool.nativeHandle(bitmap)"))
        assertTrue(packTile.contains("integerOffset = ordinal * NATIVE_TILE_INT_STRIDE"))
        assertTrue(packTile.contains("packNativeFrameTileInto("))
        assertTrue(packTile.contains("nativeFrameResourceScratch,"))
        assertFalse(submit.contains("integers.toIntArray()"))
        assertFalse(submit.contains("geometry.toFloatArray()"))
        assertFalse(submit.contains("bitmaps.toTypedArray()"))

        val deferredScenePost = slice(
            surface,
            "private fun postDeferredNativeSceneSubmission(",
            "private fun executeDeferredNativeGeometrySubmission(",
        )
        val deferredScenePack = slice(
            surface,
            "private fun packDeferredNativeScene(",
            "private fun releaseDeferredNativeScenePacket(",
        )
        val immutableScene = submit.indexOf("acquireDeferredNativeSceneSubmission(")
        val rememberScene = submit.indexOf("rememberPendingNativeProducerScene(")
        assertTrue(rememberScene >= 0)
        assertTrue(immutableScene > rememberScene)
        assertTrue(submit.substring(immutableScene).contains("nativeItems"))
        assertFalse(submit.contains("acquireDeferredNativeScenePacket(bitmapCount)"))
        assertFalse(submit.contains("nativeFrameTileDataScratch.copyOf("))
        assertFalse(submit.contains("nativeFrameGeometryScratch.copyOf("))
        assertTrue(deferredScenePack.contains("acquireDeferredNativeScenePacket(required)"))
        assertTrue(deferredScenePack.contains("packNativeFrameTileInto("))
        assertTrue(deferredScenePost.contains("synchronized(nativeSubmitOrderLock)"))
        assertTrue(deferredScenePost.contains("NtkRollingNativeBridge.nativeIsSurfaceAttached("))
        assertTrue(deferredScenePost.contains("handler.postDelayed("))
        assertTrue(deferredScenePost.contains("packDeferredNativeScene(submission)"))
        assertTrue(deferredScenePost.contains("NtkRollingNativeBridge.nativeSubmit("))
        val deferredNativeSubmit = deferredScenePost.indexOf("NtkRollingNativeBridge.nativeSubmit(")
        val packetReleaseAfterSubmit = deferredScenePost.indexOf(
            "releaseDeferredNativeScenePacket(packet, bitmapCount)",
            deferredNativeSubmit,
        )
        assertTrue(packetReleaseAfterSubmit > deferredNativeSubmit)
        assertTrue(deferredScenePost.contains("finishDeferredNativeSceneSubmission(submission)"))
        assertTrue(deferredScenePost.contains(
            "deferredNativeSceneSubmissionsInFlight.getAndDecrement()",
        ))
        assertTrue(
            Regex("clearNativeProducerScene\\((producerSceneId|terminalProducerSceneId)\\)")
                .findAll(deferredScenePost)
                .count() >= 3,
        )
        assertTrue(deferredScenePost.contains("handler.post(submission)"))
        assertFalse(deferredScenePost.contains("handler.post { executeDeferredNativeSceneSubmission"))
        assertTrue(deferredScenePost.contains("releaseDeferredNativeSceneSubmission(submission)"))
        assertTrue(submit.contains("acquireDeferredNativeGeometrySubmission("))
        assertFalse(submit.contains("val synchronousNativeGeometry"))
        val deferredGeometryPost = slice(
            surface,
            "private fun postDeferredNativeGeometrySubmission(",
            "private fun postDeferredNativeSceneSubmission(",
        )
        assertTrue(deferredGeometryPost.contains("handler.post(submission)"))
        assertFalse(deferredGeometryPost.contains("handler.post {"))
        assertTrue(deferredGeometryPost.contains("releaseDeferredNativeGeometrySubmission(submission)"))
        val threadStart = slice(
            surface,
            "private fun startRenderThreadLocked()",
            "private fun computeNativeRenderTargetSizeLocked(",
        )
        assertTrue(threadStart.contains("\"ReaderSurfaceProducer\""))
        assertTrue(threadStart.contains("Process.THREAD_PRIORITY_URGENT_DISPLAY"))
        assertTrue(threadStart.contains("\"ReaderSurfaceCommit\""))
        val commitThread = threadStart.substringAfter("\"ReaderSurfaceCommit\"")
            .substringBefore("frameSyncedGeometryCommitThread = commitThread")
        assertTrue(commitThread.contains("Process.THREAD_PRIORITY_URGENT_DISPLAY"))
        assertTrue(surface.contains("Process.THREAD_PRIORITY_URGENT_AUDIO"))

        val stop = slice(
            surface,
            "fun stopRenderingAndClearPages()",
            "fun setCommittedPageIdentities(",
        )
        val resetRetirementPost = stop.indexOf("rollingAuthoritativeRecyclePosted = false")
        val rearmRetirement = stop.indexOf(
            "scheduleRollingAuthoritativeRecycleLocked()",
            resetRetirementPost,
        )
        val stripAuthorityReturn = stop.indexOf("if (stripAuthorityToken != 0L) return")
        assertTrue(resetRetirementPost >= 0)
        assertTrue(rearmRetirement > resetRetirementPost)
        assertTrue(stripAuthorityReturn > rearmRetirement)

        val detached = slice(
            surface,
            "override fun onDetachedFromWindow()",
            "override fun surfaceCreated(",
        )
        val destroyBegin = detached.indexOf("rollingNativeDestroyInFlightCount += 1")
        val destroy = detached.indexOf("NtkRollingNativeBridge.nativeDestroy(", destroyBegin)
        val destroyComplete = detached.indexOf("completeRollingNativeDestroy()", destroy)
        assertTrue(destroyBegin >= 0)
        assertTrue(destroy > destroyBegin)
        assertTrue(destroyComplete > destroy)
    }

    @Test
    fun nativeRecreationBudgetIsViewLifetimeAndBoundedToOne() {
        assertFalse(ReaderSurfaceView.shouldRecreateRollingNativeRendererForTest(0))
        assertTrue(ReaderSurfaceView.shouldRecreateRollingNativeRendererForTest(1))
        assertFalse(ReaderSurfaceView.shouldRecreateRollingNativeRendererForTest(2))
        assertFalse(ReaderSurfaceView.shouldRecreateRollingNativeRendererForTest(Int.MAX_VALUE))
    }

    @Test
    fun onlyTheExactPendingFatalGenerationCanCompleteRecovery() {
        assertTrue(
            ReaderSurfaceView.ownsRollingNativeRecoveryCompletionForTest(
                expectedGeneration = 7L,
                currentGeneration = 7L,
                recoveryPending = true,
                nativeFatal = true,
                nativeHandle = 0L,
            ),
        )
        assertFalse(
            ReaderSurfaceView.ownsRollingNativeRecoveryCompletionForTest(
                7L, 8L, true, true, 0L,
            ),
        )
        assertFalse(
            ReaderSurfaceView.ownsRollingNativeRecoveryCompletionForTest(
                7L, 7L, false, true, 0L,
            ),
        )
        assertFalse(
            ReaderSurfaceView.ownsRollingNativeRecoveryCompletionForTest(
                7L, 7L, true, false, 0L,
            ),
        )
        assertFalse(
            ReaderSurfaceView.ownsRollingNativeRecoveryCompletionForTest(
                7L, 7L, true, true, 41L,
            ),
        )
    }

    @Test
    fun retirementSnapshotAndQuiescenceHookCoverEveryPendingLane() {
        val hooks = slice(
            surface,
            "fun nativeRetirementStatsSnapshot()",
            "fun visibleCoverageSnapshot()",
        )
        listOf(
            "mailboxSuperseded = nativeMailboxSupersededRetirements",
            "presentFailed = nativePresentFailedRetirements",
            "lifecycleRetired = nativeLifecycleRetirements",
            "unknown = nativeUnknownRetirements",
            "rendererFatal = nativeRendererFatals",
            "recreate = nativeRendererRecreates",
            "permanentFallback = nativePermanentFallbacks",
            "attachEpoch = rollingNativeAttachEpoch",
        ).forEach { field -> assertTrue(hooks.contains(field)) }

        listOf(
            "framePipe == FramePipe.IDLE",
            "pendingFrameCommits.isEmpty()",
            "earlyNativeOutcomes.isEmpty()",
            "!renderRequested",
            "scroller.isFinished",
            "nativeTexturePrewarmFlushPosted",
            "nativeTexturePrewarmSubmissionInFlight",
            "rollingNativeRecoveryPending",
            "rollingNativeCreatePending",
            "nativeHostBoundsReattachPending",
            "NtkRollingNativeBridge.nativeIsQuiescent(nativeHandle)",
        ).forEach { lane -> assertTrue(hooks.contains(lane)) }

        val quiescence = slice(
            surface,
            "fun isNativePipelineQuiescentForTest()",
            "fun visibleCoverageSnapshot()",
        )
        val prewarmFence = slice(
            quiescence,
            "val nativePrewarmPending =",
            "val nativeLifecyclePending =",
        )
        assertTrue(prewarmFence.contains("nativeTexturePrewarmFlushPosted"))
        assertTrue(prewarmFence.contains("nativeTexturePrewarmSubmissionInFlight"))
        assertFalse(prewarmFence.contains("nativeTexturePrewarmDirty"))
        assertFalse(prewarmFence.contains("nativeTexturePrewarmPendingPages"))

        val registration = slice(
            surface,
            "var hardwareCommitUnavailable = false",
            "if (fallbackCommit)",
        )
        assertTrue(registration.contains("nativeMailboxSupersededRetirements++"))
        val drop = slice(
            surface,
            "fun onNtkRollingFrameDropped(",
            "fun onNtkRollingRendererFatal(",
        )
        assertTrue(drop.contains("nativePresentFailedRetirements++"))
        assertTrue(drop.contains("nativeLifecycleRetirements++"))
        assertTrue(drop.contains("nativeUnknownRetirements++"))
        val registeredLookup = drop.indexOf("val registered = pendingFrameCommits[token]")
        val unownedGuard = drop.indexOf("if (registered == null && !ownsPostedAdmission)")
        val presentFailureCount = drop.indexOf("nativePresentFailedRetirements++")
        assertTrue(registeredLookup >= 0)
        assertTrue(unownedGuard > registeredLookup)
        assertTrue(presentFailureCount > unownedGuard)
        assertTrue(registration.contains(
            "recoverDirectSurfaceSubmission(work.frameEpoch, work.frameToken)",
        ))
        assertFalse(registration.contains(
            "onNtkRollingFrameDropped(work.frameToken, early.reason)",
        ))
    }

    @Test
    fun eglInitializationFailureFallsThroughTheSingleTerminalCleanup() {
        val run = slice(rollingNative, "void run() noexcept", "JavaVM* vm_")
        val initialize = run.indexOf("const bool eglReady = env != nullptr && initializeEgl()")
        val fatal = run.indexOf("fatal(env, \"cold-egl-initialize\")", initialize)
        val terminal = run.indexOf("std::deque<FrameCommand> remaining", fatal)
        assertTrue(initialize >= 0)
        assertTrue(fatal > initialize)
        assertTrue(terminal > fatal)
        assertFalse(run.substring(fatal, terminal).contains("return;"))
        listOf(
            "remaining.swap(frames_)",
            "remainingPrewarm.swap(prewarmTiles_)",
            "pending = pendingAttach_",
            "ANativeWindow_release(pending.window)",
            "destroyEgl()",
        ).forEach { cleanup -> assertTrue(run.substring(terminal).contains(cleanup)) }

        val prepare = slice(rollingNative, "bool prepare(", "bool attach(")
        val attach = slice(rollingNative, "bool attach(", "void detach(")
        val submit = slice(rollingNative, "std::int64_t submit(", "bool prewarm(")
        assertTrue(occurrenceCount(prepare, "failed_.load(std::memory_order_acquire)") >= 2)
        assertTrue(occurrenceCount(attach, "failed_.load(std::memory_order_acquire)") >= 2)
        assertTrue(occurrenceCount(submit, "failed_.load(std::memory_order_acquire)") >= 2)
    }

    @Test
    fun nativeQuiescenceSnapshotIncludesQueuesActiveWorkAndBackendEvidence() {
        assertTrue(rollingBridge.contains("external fun nativeIsQuiescent(handle: Long): Boolean"))
        assertTrue(rollingNative.contains(
            "Java_ml_melun_mangaview_reader_NtkRollingNativeBridge_nativeIsQuiescent",
        ))
        val publish = slice(
            rollingNative,
            "void publishPipelineQuiescenceLocked() noexcept",
            "void run() noexcept",
        )
        listOf(
            "frames_.empty()",
            "!runnablePrewarmWork",
            "!preparePending_",
            "!attachPending_",
            "!detachPending_",
            "!pendingDirectFrame_.occupied",
            "!textureRetirementDebt_.pending()",
            "snapshot.outstandingSubmissionCount == 0",
            "snapshot.previousReleaseRecordDepth == 0",
            "snapshot.acquireFenceRecordDepth == 0",
            "snapshot.appOwnedAcquireFdCount == 0",
            "!backend_.hasPendingEvent()",
        ).forEach { lane -> assertTrue(publish.contains(lane)) }
        assertTrue(publish.contains("canUploadNextPrewarmLocked()"))
        assertTrue(publish.contains("prewarmPacingReady"))
        assertTrue(publish.contains("quiescence blocked by backend evidence"))

        val run = slice(rollingNative, "void run() noexcept", "JavaVM* vm_")
        val publishBeforeWait = run.indexOf("publishPipelineQuiescenceLocked()")
        val wait = run.indexOf("condition_.wait_for", publishBeforeWait)
        val clearAfterWait = run.indexOf(
            "pipelineQuiescent_.store(false, std::memory_order_release)",
            wait,
        )
        assertTrue(publishBeforeWait >= 0)
        assertTrue(wait > publishBeforeWait)
        assertTrue(clearAfterWait > wait)

        val query = slice(
            rollingNative,
            "bool isQuiescent() noexcept",
            "void setDirectWifiTextureProfile(",
        )
        val ownerSnapshot = query.indexOf("pipelineQuiescent_.load(std::memory_order_acquire)")
        val pendingEventRecheck = query.indexOf("!backend_.hasPendingEvent()", ownerSnapshot)
        assertTrue(ownerSnapshot >= 0)
        assertTrue(pendingEventRecheck > ownerSnapshot)
    }

    @Test
    fun delayedFatalCallbackCannotRetireAReplacementRenderer() {
        assertTrue(ReaderSurfaceView.ownsRollingNativeFatalCallbackForTest(
            41L, 41L, 7L, 7L, false,
        ))
        assertFalse(ReaderSurfaceView.ownsRollingNativeFatalCallbackForTest(
            41L, 42L, 7L, 7L, false,
        ))
        // Allocators may reuse an old pointer; creation generation closes that ABA.
        assertFalse(ReaderSurfaceView.ownsRollingNativeFatalCallbackForTest(
            41L, 41L, 7L, 8L, false,
        ))
        assertFalse(ReaderSurfaceView.ownsRollingNativeFatalCallbackForTest(
            41L, 41L, 7L, 7L, true,
        ))
        assertFalse(ReaderSurfaceView.ownsRollingNativeFatalCallbackForTest(
            0L, 41L, 7L, 7L, false,
        ))
        // R1 can report after detach and R2 may reuse its pointer. The immutable renderer-owned
        // generation still identifies the callback as R1 even if the allocator reuses the value.
        assertFalse(ReaderSurfaceView.ownsRollingNativeFatalCallbackForTest(
            41L, 41L, 7L, 8L, false,
        ))

        assertTrue(rollingNative.contains(
            "\"onNtkRollingRendererFatal\", \"(JJLjava/lang/String;)V\"",
        ))
        assertTrue(rollingNative.contains(
            "static_cast<jlong>(creationGeneration_), message",
        ))
        val fatal = slice(
            surface,
            "fun onNtkRollingRendererFatal(",
            "private fun finishRollingNativeRecoveryAfterDestroy(",
        )
        val identityFence = fatal.indexOf("ownsRollingNativeFatalCallback(")
        val retire = fatal.indexOf("rollingNativeHandle = 0L", identityFence)
        assertTrue(identityFence >= 0)
        assertTrue(retire > identityFence)
    }

    @Test
    fun asynchronousCreateCannotInstallAWorkerThatAlreadyReportedFatal() {
        assertTrue(rollingBridge.contains("external fun nativeHasFailed(handle: Long): Boolean"))
        assertTrue(rollingNative.contains(
            "Java_ml_melun_mangaview_reader_NtkRollingNativeBridge_nativeHasFailed",
        ))
        val preparation = slice(
            surface,
            "private fun prepareRollingNativeRendererLocked()",
            "private fun attachPreparedRollingNativeSurfaceIfReady(",
        )
        val lock = preparation.indexOf("val installed = synchronized(stateLock)")
        val query = preparation.indexOf(
            "NtkRollingNativeBridge.nativeHasFailed(createdHandle)",
            lock,
        )
        val install = preparation.indexOf("rollingNativeHandle = createdHandle", query)
        assertTrue(lock >= 0)
        assertTrue(query > lock)
        assertTrue(install > query)
        assertTrue(preparation.substring(query, install).contains("!failedBeforeInstall"))
    }

    @Test
    fun aggregateTextureHeadroomRunsBeforeEitherVisibleUploadLoop() {
        val window = slice(
            rollingNative,
            "PresentResult presentWindowFrame(",
            "PresentResult applyPreparedDirectFrame(",
        )
        val windowPlan = window.indexOf(
            "prepareVisibleFrameTextureHeadroom(frame, directWifiFreshNames)",
        )
        val windowUpload = window.indexOf("uploadTile(", windowPlan)
        assertTrue(windowPlan >= 0)
        assertTrue(windowUpload > windowPlan)
        assertTrue(window.contains("textureUseFrame, directWifiFreshNames"))

        val direct = slice(
            rollingNative,
            "PresentResult presentFrame(",
            "bool servicePreparedDirectFrame(",
        )
        val directPlan = direct.indexOf(
            "prepareVisibleFrameTextureHeadroom(frame, directWifiFreshNames)",
        )
        val directUpload = direct.indexOf("uploadTile(", directPlan)
        assertTrue(directPlan >= 0)
        assertTrue(directUpload > directPlan)
        assertTrue(direct.contains("textureUseFrame, directWifiFreshNames"))

        val upload = slice(rollingNative, "bool uploadTile(", "PrewarmUploadResult uploadPrewarmTile(")
        assertFalse(upload.contains("glGenTextures"))
        assertTrue(upload.contains("takeReservedTextureName()"))
        assertTrue(upload.contains("importExactHardwareBufferTile("))
        assertTrue(upload.contains("hostGpuEmulatorSurfaceProfile_.load"))
        assertTrue(upload.contains("ensureHostUploadScratch"))
        assertTrue(upload.contains("std::memcpy("))
        assertTrue(upload.contains("uploadStride = tightStride"))
        assertTrue(upload.contains("glTexImage2D"))
        assertTrue(upload.contains("stage=bitmap-info"))
        assertTrue(upload.contains("stage=bitmap-lock"))
        assertTrue(upload.contains("GLenum uploadError = drainGlErrors()"))
        val directImport = slice(
            rollingNative,
            "bool importExactHardwareBufferTile(",
            "bool uploadTile(",
        )
        assertTrue(directImport.contains("eglGetNativeClientBufferANDROID") ||
            rollingNative.contains("eglGetNativeClientBufferANDROID"))
        assertTrue(directImport.contains("EGL_NATIVE_BUFFER_ANDROID"))
        assertTrue(directImport.contains("imageTargetTexture_"))
        assertTrue(directImport.contains("const float scaleX"))
        assertTrue(rollingNative.contains("float textureScaleX = 1.0F"))
        assertTrue(rollingNative.contains("vTexCoord=aTexCoord*uTexScale"))
        assertTrue(textureHeadroomPlanner.contains("protectedFrameOversize"))
        assertTrue(textureHeadroomPlanner.contains("hasUploadWork"))
        assertTrue(textureHeadroomPlanner.contains("directWifiFreshNames"))
        assertTrue(textureHeadroomPlanner.contains("isProtectedTextureKey"))

        val initialize = slice(rollingNative, "bool initializeEgl()", "void destroyEgl()")
        val reserve = slice(
            rollingNative,
            "bool refillTextureNameReserve(",
            "GLuint takeReservedTextureName()",
        )
        assertTrue(initialize.contains("refillTextureNameReserve(kTextureNameReserveCount)"))
        assertTrue(reserve.contains("glGenTextures"))
        assertTrue(reserve.contains("spareTextureNames_.insert"))

        val preflight = slice(
            rollingNative,
            "bool prepareVisibleFrameTextureHeadroom(",
            "bool hasOptionalPrewarmTextureHeadroom(",
        )
        val plannedEviction = preflight.indexOf("eraseTexture(")
        val nonBlockingPoll = preflight.indexOf("pollTextureRetirementFence()")
        val fenceArm = preflight.lastIndexOf("armTextureRetirementFence()")
        val uploadFence = preflight.indexOf(
            "shouldSettleTextureRetirementBeforeVisibleUpload("
        )
        val physicalBarrier = preflight.indexOf(
            "settleTextureRetirementBeforeVisibleUpload()",
        )
        assertTrue(nonBlockingPoll >= 0)
        assertTrue(plannedEviction >= 0)
        assertTrue(fenceArm > plannedEviction)
        assertTrue(uploadFence > plannedEviction)
        assertTrue(uploadFence > fenceArm)
        assertTrue(physicalBarrier > uploadFence)
        assertTrue(textureHeadroomPlanner.contains(
            "const std::uint64_t transientByteLimit"
        ))
        assertTrue(textureHeadroomPlanner.contains(
            "const std::size_t transientNameLimit"
        ))

        val barrier = slice(
            rollingNative,
            "bool settleTextureRetirementBeforeVisibleUpload()",
            "bool prepareVisibleFrameTextureHeadroom(",
        )
        val fence = slice(
            rollingNative,
            "bool armTextureRetirementFence()",
            "bool settleTextureRetirementBeforeVisibleUpload()",
        )
        assertTrue(fence.contains("glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0)"))
        assertTrue(fence.contains("glClientWaitSync(textureRetirementFence_, 0, 0)"))
        assertTrue(fence.contains("GL_TIMEOUT_EXPIRED"))
        assertTrue(fence.indexOf("glClientWaitSync") <
            fence.indexOf("textureRetirementDebt_.completeBarrier(true)"))
        assertTrue(barrier.indexOf("pollTextureRetirementFence()") <
            barrier.indexOf("glFinish()"))
        assertTrue(barrier.indexOf("glFinish()") <
            barrier.indexOf("textureRetirementDebt_.completeBarrier(succeeded)"))
        assertTrue(barrier.contains("errorBeforeBarrier == GL_NO_ERROR"))
        assertTrue(barrier.contains("errorAfterBarrier == GL_NO_ERROR"))
    }

    @Test
    fun optionalPrewarmSkipsWithoutApplyingVisibleVictimPlan() {
        val prewarm = slice(
            rollingNative,
            "PrewarmUploadResult uploadPrewarmTile(",
            "void recycleTextureStorage(",
        )
        val headroom = prewarm.indexOf(
            "hasOptionalPrewarmTextureHeadroom(tile, directWifiFreshNames)",
        )
        val upload = prewarm.indexOf("uploadTile(", headroom)
        assertTrue(headroom >= 0)
        assertTrue(upload > headroom)
        assertTrue(prewarm.contains("bool issuedGlUpload = false"))
        assertTrue(prewarm.contains("uploaded && issuedGlUpload"))
        assertTrue(prewarm.contains("glFlush()"))
        assertFalse(prewarm.contains("glFinish()"))
        val skipped = prewarm.indexOf("PrewarmUploadResult::SKIPPED_FOR_HEADROOM")
        val release = prewarm.indexOf("releaseTile(env, tile)", headroom)
        assertTrue(skipped > headroom)
        assertTrue(release in (headroom + 1) until skipped)
        val run = slice(rollingNative, "void run() noexcept", "JavaVM* vm_")
        assertTrue(run.contains("prewarmTiles_.pop_front()"))
        assertFalse(prewarm.contains("prewarmTiles_.push_front"))

        val optionalAdmission = slice(
            rollingNative,
            "bool hasOptionalPrewarmTextureHeadroom(",
            "bool uploadTile(",
        )
        val debtFence = optionalAdmission.indexOf("textureRetirementDebt_.pending()")
        val optionalPlan = optionalAdmission.indexOf(
            "canUploadOptionalTextureWithoutEviction",
        )
        assertTrue(debtFence >= 0)
        assertTrue(optionalPlan > debtFence)

        val retirement = slice(
            rollingNative,
            "void retireTextureName(",
            "bool settleTextureRetirementBeforeVisibleUpload()",
        )
        assertTrue(retirement.contains("glDeleteTextures"))
        assertTrue(retirement.contains("textureRetirementDebt_.record(bytes)"))
        // Three raw calls are terminal EGL/name-reserve cleanup. Every mapped live-context
        // retirement remains centralized through retireTextureName.
        assertEquals(5, occurrenceCount(rollingNative, "glDeleteTextures("))

        assertTrue(rollingNative.contains("DEFERRED_FOR_RETIREMENT"))
        val ownerLoop = slice(rollingNative, "void run() noexcept", "JavaVM* vm_")
        val deferred = ownerLoop.indexOf(
            "uploadResult == PrewarmUploadResult::DEFERRED_FOR_RETIREMENT",
        )
        val requeue = ownerLoop.indexOf("restoreDeferredPrewarmTile(", deferred)
        assertTrue(deferred >= 0)
        assertTrue(requeue > deferred)
        val idleDebt = ownerLoop.indexOf("settleIdleTextureRetirement")
        val idleBarrier = ownerLoop.indexOf(
            "settleTextureRetirementBeforeVisibleUpload()",
            idleDebt,
        )
        assertTrue(idleDebt >= 0)
        assertTrue(idleBarrier > idleDebt)
        assertTrue(ownerLoop.contains("prewarmPauseAllowsIdleTextureRetirementLocked("))
        assertTrue(rollingNative.contains(
            "kTextureRetirementPausedExtraQuietNanos = 1'250'000'000"
        ))
        assertTrue(ownerLoop.contains("nextIdleTextureRetirementNanos_"))
        assertTrue(ownerLoop.contains(
            "!pendingDirectFrame_.occupied && !backend_.hasPendingEvent()",
        ))
        assertTrue(ownerLoop.contains("frames_.empty() && !submissionAwaitingLatch_"))
        assertTrue(ownerLoop.contains("prewarmPopRevision = ++prewarmQueueRevision_"))
        val nextPrewarmPublication = ownerLoop.indexOf("nextPrewarmUploadNanos_ = std::max(")
        val nextPrewarmPublicationLock = ownerLoop.lastIndexOf(
            "std::lock_guard<std::mutex> lock(mutex_)",
            nextPrewarmPublication,
        )
        assertTrue(nextPrewarmPublication >= 0)
        assertTrue(nextPrewarmPublicationLock in 0 until nextPrewarmPublication)
        assertTrue(ownerLoop.substring(nextPrewarmPublicationLock, nextPrewarmPublication)
            .contains("if (issuedUpload)"))
        val restore = slice(
            rollingNative,
            "void restoreDeferredPrewarmTile(",
            "void callbackDropped(",
        )
        assertTrue(restore.contains("prewarmQueueRevision_ == expectedRevision"))
        assertTrue(restore.contains("++prewarmQueueRevision_"))
    }

    @Test
    fun fullSceneBudgetPublishesOneStableAtomicEpochBytePair() {
        assertTrue(rollingNative.contains(
            "std::atomic<std::int64_t> fullSceneTextureBudgetEpoch_{0}",
        ))
        assertTrue(rollingNative.contains(
            "std::atomic<std::uint64_t> fullSceneTextureBudgetBytes_{0}",
        ))
        val clear = rollingNative.indexOf(
            "fullSceneTextureBudgetEpoch_.store(0, std::memory_order_seq_cst)",
        )
        val bytes = rollingNative.indexOf("fullSceneTextureBudgetBytes_.store(", clear)
        val publish = rollingNative.indexOf(
            "fullSceneTextureBudgetEpoch_.store(",
            bytes,
        )
        assertTrue(clear >= 0)
        assertTrue(bytes > clear)
        assertTrue(publish > bytes)

        val budget = slice(
            rollingNative,
            "std::uint64_t effectiveTextureBudget(",
            "static bool plannedTextureBytes(",
        )
        assertTrue(occurrenceCount(budget, "fullSceneTextureBudgetEpoch_.load") == 2)
        assertTrue(budget.contains(
            "fullSceneTextureBudgetBytes_.load(std::memory_order_seq_cst)",
        ))
        assertTrue(budget.contains("before == structureEpoch && after == before"))
    }

    @Test
    fun staleDetachCannotCancelANewerAttachmentEpoch() {
        val attach = slice(rollingNative, "bool attach(", "void detach(")
        assertTrue(attach.contains("if (latestAcceptedAttachEpoch_ > epoch) return false"))
        assertTrue(attach.indexOf("latestAcceptedAttachEpoch_ = epoch") <
            attach.indexOf("attachPending_ = true"))

        val detach = slice(rollingNative, "void detach(", "std::int64_t submit(")
        val exactFence = detach.indexOf(
            "if (epoch == 0 || epoch != latestAcceptedAttachEpoch_) return",
        )
        val releasePending = detach.indexOf("ANativeWindow_release(pendingAttach_.window)")
        val cancelAttach = detach.indexOf("attachPending_ = false")
        val scheduleDetach = detach.indexOf("detachPending_ = true")
        assertTrue(exactFence >= 0)
        assertTrue(releasePending > exactFence)
        assertTrue(cancelAttach > exactFence)
        assertTrue(scheduleDetach > exactFence)
    }

    @Test
    fun blanketMainQueueRemovalInvalidatesStatsReservationBeforeReattach() {
        val stop = slice(
            surface,
            "fun stopRenderingAndClearPages()",
            "fun setCommittedPageIdentities(",
        )
        assertTrue(stop.indexOf("mainHandler.removeCallbacksAndMessages(null)") <
            stop.indexOf("invalidateFrameStatsFinalizeDeadlineLocked()"))

        val detach = slice(
            surface,
            "override fun onDetachedFromWindow()",
            "override fun surfaceCreated(",
        )
        assertTrue(detach.indexOf("mainHandler.removeCallbacksAndMessages(null)") <
            detach.indexOf("invalidateFrameStatsFinalizeDeadlineLocked()"))

        val invalidation = slice(
            surface,
            "private fun invalidateFrameStatsFinalizeDeadlineLocked()",
            "private fun finalizeActiveFrameStatsLocked(",
        )
        val clearScheduled = invalidation.indexOf("frameStatsFinalizeScheduled = false")
        val clearDeadline = invalidation.indexOf("frameStatsFinalizeDeadlineMs = 0L")
        val advanceReservation = invalidation.indexOf("frameStatsFinalizeReservation++")
        assertTrue(clearScheduled >= 0)
        assertTrue(clearDeadline > clearScheduled)
        assertTrue(advanceReservation > clearDeadline)

        val arm = slice(
            surface,
            "private fun armFrameStatsFinalizeDeadline()",
            "private fun drainFrameStatsFinalizeDeadline(",
        )
        assertTrue(arm.contains("if (frameStatsFinalizeScheduled)"))
        assertTrue(arm.contains("frameStatsFinalizeScheduled = true"))
        assertTrue(arm.contains("mainHandler.postDelayed("))
    }

    private fun slice(source: String, startMarker: String, endMarker: String): String {
        val start = source.indexOf(startMarker)
        val end = source.indexOf(endMarker, startIndex = start.coerceAtLeast(0) + 1)
        check(start >= 0 && end > start) {
            "missing architecture markers: $startMarker -> $endMarker"
        }
        return source.substring(start, end)
    }

    private fun occurrenceCount(source: String, needle: String): Int {
        var count = 0
        var offset = 0
        while (true) {
            val next = source.indexOf(needle, offset)
            if (next < 0) return count
            count += 1
            offset = next + needle.length
        }
    }
}
