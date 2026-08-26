package ml.melun.mangaview.reader

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkAdjacentLivenessArchitectureTest {
    private val sessionSource = File(
        "src/main/java/ml/melun/mangaview/reader/ReaderSession.kt",
    ).readText()
    private val activitySource = File(
        "src/main/java/ml/melun/mangaview/activity/ReaderV2Activity.kt",
    ).readText()

    @Test
    fun aSilentMissingListCannotPoisonLaterExplicitBoundaryRequests() {
        assertFalse(sessionSource.contains("adjacentMissingTargets"))
        assertFalse(sessionSource.contains("adjacentMissingRefreshes"))
        val append = functionBody(
            sessionSource,
            "fun appendAdjacentEpisode(",
        )
        assertTrue(append.contains("if (target == null && anchorManga.isOnline)"))
        assertTrue(append.contains("imageRepository.fetchEpisodesForeground"))
    }

    @Test
    fun aFailedJoinedDiscoveryIsObservedUntilItsManifestActuallyArrives() {
        val post = functionBody(
            activitySource,
            "private fun postAdjacentExactManifestForGeneration(",
        )
        assertTrue(post.contains("currentAuthoritativeManifest("))
        assertTrue(post.contains("capturedTargetPath"))
        assertTrue(post.contains("isInFlight(capturedTargetPath)"))
        assertTrue(post.contains("if (predecessorReady && !inFlight)"))
        assertTrue(post.contains("statusHandler.postDelayed("))
        assertFalse(post.contains("if (joined) return"))
        assertTrue(post.contains("session !== ownerSession"))
        assertTrue(post.contains("isForwardAdjacentExactManifestClaimCurrent("))
        assertTrue(post.contains("isAdjacentStrictReplacementDiscoveryCurrent("))
        assertTrue(post.contains("if (replacementRecoveryCurrent)"))
        assertTrue(post.contains("\"adjacent_strict_body_recovery\""))
        assertTrue(post.contains("NTK_ADJACENT_EXACT_DISCOVERY_MAX_LAUNCHES"))
        assertTrue(post.contains("reserveForwardAdjacentExactManifestDiscoveryLaunch("))
        assertTrue(post.contains("retireStalledForwardAdjacentExactManifestClaim("))
        assertTrue(post.contains("val retired ="))
        assertTrue(post.contains("if (!retired &&"))
    }

    @Test
    fun adjacentDiscoveryWatchdogsAreIndependentPerExactBoundaryAndAbaSafe() {
        assertFalse(activitySource.contains("adjacentExactDiscoveryRetryToken = AtomicLong"))
        assertTrue(activitySource.contains("data class AdjacentExactDiscoveryRetryKey("))
        assertTrue(activitySource.contains("val sessionGeneration: Int"))
        assertTrue(activitySource.contains("val viewerGeneration: Long"))
        assertTrue(activitySource.contains("val viewerOwnerPath: String"))
        assertTrue(activitySource.contains("val predecessorPath: String"))
        assertTrue(activitySource.contains("val targetPath: String"))
        val post = functionBody(
            activitySource,
            "private fun postAdjacentExactManifestForGeneration(",
        )
        assertTrue(post.contains("adjacentExactDiscoveryRetryTokens[retryKey] = retryToken"))
        assertTrue(post.contains("adjacentExactDiscoveryRetryTokens[retryKey] != retryToken"))
        assertTrue(post.contains("remove(retryKey, retryToken)"))
        assertTrue(post.contains("retainedForNextTurn = statusHandler.postDelayed("))
    }

    @Test
    fun committedAdjacentStructureCanAuthorizeOnlyItsExactBodyReplacement() {
        val recovery = functionBody(
            sessionSource,
            "fun isAdjacentStrictReplacementDiscoveryCurrent(",
        )
        assertTrue(recovery.contains("adjacentStrictRecoveryStates[targetPath]"))
        assertTrue(recovery.contains("state.awaitingReplacement && !state.exhausted"))
        assertTrue(recovery.contains("state.predecessorEpisodePath"))
    }

    @Test
    fun physicalBoundaryRedrivesADeadCompletionOwner() {
        val join = functionBody(
            sessionSource,
            "private fun joinCompletionOwnedForwardExactAppend(",
        )
        assertTrue(join.contains("!NtkStrictEpisodeDiscoveryCoordinator.isInFlight(targetPath)"))
        assertTrue(join.contains("listener.onAdjacentExactManifestRequired("))
        assertTrue(join.contains("isForwardAdjacentCompletionTargetCurrent("))
        assertTrue(join.contains("removePending(entry.key, entry.value)"))
    }

    @Test
    fun exhaustedStaleClaimFallsBackWithoutAnotherGesture() {
        val retire = functionBody(
            sessionSource,
            "fun retireStalledForwardAdjacentExactManifestClaim(",
        )
        assertTrue(retire.contains("currentAuthoritativeManifest(targetPath) != null"))
        assertTrue(retire.contains("isInFlight(targetPath)"))
        assertTrue(retire.contains("removePendingRevision("))
        assertTrue(retire.contains("forwardAdjacentCompletionTargetClaims.remove(predecessorPath)"))
        val cleanup = retire.indexOf("adjacentStrictPredecessorPaths.remove")
        val claimRemove = retire.indexOf("forwardAdjacentCompletionTargetClaims.remove")
        val pendingRemove = retire.indexOf("removePendingRevision(")
        assertTrue(cleanup >= 0 && cleanup < claimRemove && claimRemove < pendingRemove)
        assertTrue(retire.contains("scheduleDeferredAdjacentPrepare("))
        assertTrue(retire.contains("silentMissing = false"))
    }

    @Test
    fun repeatedListenerCallbacksShareOneClaimRevisionLaunchBudget() {
        val reserve = functionBody(
            sessionSource,
            "fun reserveForwardAdjacentExactManifestDiscoveryLaunch(",
        )
        val commit = functionBody(
            sessionSource,
            "fun commitForwardAdjacentExactManifestDiscoveryLaunch(",
        )
        assertTrue(reserve.contains("forwardAdjacentCompletionTargetClaims[predecessorPath]"))
        assertTrue(reserve.contains("selected.discoveryLaunchAttempts.get() >= maximumLaunches"))
        assertFalse(reserve.contains("discoveryLaunchAttempts.incrementAndGet()"))
        assertTrue(commit.contains("if (admittedOrJoined) selected.discoveryLaunchAttempts.incrementAndGet()"))
    }

    @Test
    fun validatedNetworkRearmsAnExhaustedInitialManifestOwnerWithANewRevision() {
        val retire = functionBody(
            sessionSource,
            "fun retireStalledForwardAdjacentExactManifestClaim(",
        )
        val tombstoneAt = retire.indexOf(
            "forwardAdjacentValidatedRecoveryTombstones[predecessorPath] =",
        )
        val claimRemoveAt = retire.indexOf(
            "forwardAdjacentCompletionTargetClaims.remove(predecessorPath)",
        )
        val pendingRemoveAt = retire.indexOf("removePendingRevision(")
        assertTrue(
            tombstoneAt >= 0 && claimRemoveAt > tombstoneAt && pendingRemoveAt > claimRemoveAt,
        )

        val rearm = functionBody(
            sessionSource,
            "fun redriveCurrentForwardAdjacentExactManifestAfterValidated(",
        )
        assertTrue(rearm.contains("ViewerTelemetry.isActiveViewer(viewerGeneration, viewerOwnerPath)"))
        assertTrue(rearm.contains("NtkForwardAdjacentValidatedRecoveryPolicy.shouldRearm("))
        assertTrue(rearm.contains("live.discoveryLaunchAttempts.set(0)"))
        assertTrue(rearm.contains("forwardAdjacentCompletionTargetClaimSequence.getAndIncrement()"))
        assertTrue(rearm.contains("lastValidatedRedriveEpoch = validatedEpoch"))
        assertTrue(rearm.contains("lastObservedForwardAdjacentValidatedNetworkEpoch = validatedEpoch"))
        assertFalse(rearm.contains("if (activePath.isEmpty()) return false"))
        val observedEdgeAt = rearm.indexOf(
            "lastObservedForwardAdjacentValidatedNetworkEpoch = validatedEpoch",
        )
        val liveClaimAt = rearm.indexOf(
            "val live = forwardAdjacentCompletionTargetClaims.values",
        )
        assertTrue(observedEdgeAt >= 0 && liveClaimAt > observedEdgeAt)
        val claimLockAt = rearm.indexOf("synchronized(forwardAdjacentCompletionTargetClaimLock)")
        val activateAt = rearm.indexOf("activateForwardAdjacentCompletionTargetClaim(")
        assertTrue(claimLockAt >= 0 && activateAt > claimLockAt)
        assertTrue(retire.contains("lastObservedForwardAdjacentValidatedNetworkEpoch"))
        assertTrue(retire.contains(".observedBoundaryMatches("))
        assertTrue(retire.contains("selected.discoveryLaunchAttempts.set(0)"))
        assertTrue(retire.contains("consumedValidatedEpoch = observedEpoch"))

        val claim = functionBody(
            sessionSource,
            "private fun claimForwardAdjacentCompletionTarget(",
        )
        val retiredLookupAt = claim.indexOf(
            "forwardAdjacentValidatedRecoveryTombstones[predecessorPath]",
        )
        val sameTargetBlockAt = claim.indexOf("blocksOrdinarySameTargetReselection(")
        val policyDecisionAt = claim.indexOf("NtkForwardAdjacentTargetClaimPolicy.decide(")
        assertTrue(
            retiredLookupAt >= 0 && sameTargetBlockAt > retiredLookupAt &&
                policyDecisionAt > sameTargetBlockAt,
        )
        assertTrue(
            sessionSource.contains(
                "forwardAdjacentCompletionTargetClaims.clear()\n" +
                    "            forwardAdjacentValidatedRecoveryTombstones.clear()",
            ),
        )
        val structure = functionBody(
            sessionSource,
            "private fun claimForwardAdjacentStructurePublication(",
        )
        assertTrue(structure.contains("forwardAdjacentValidatedRecoveryTombstones.remove(predecessorPath)"))

        val activityRedrive = functionBody(
            activitySource,
            "private fun runStrictNtkValidatedNetworkRedrive(",
        )
        val initialManifestRearmAt = activityRedrive.indexOf(
            "redriveCurrentForwardAdjacentExactManifestAfterValidated(ticket.epoch)",
        )
        val launchManifestCompleteAt = activityRedrive.indexOf(
            "currentAuthoritativeManifest(launchPath)",
        )
        assertTrue(
            initialManifestRearmAt >= 0 && launchManifestCompleteAt > initialManifestRearmAt,
        )
    }

    @Test
    fun frameStatsCancellationNeverScansTheMainMessageQueue() {
        val cancel = functionBody(
            File("src/main/java/ml/melun/mangaview/reader/ReaderSurfaceView.kt").readText(),
            "private fun cancelFrameStatsFinalizeDeadline()",
        )
        assertFalse(cancel.contains("removeCallbacks"))
        assertTrue(cancel.contains("frameStatsFinalizeReservation++"))
    }

    @Test
    fun launchTailRetainsTheSameScopedAdjacentWindowThatItAllowsToDecode() {
        val strictWindow = functionBody(
            sessionSource,
            "private fun requestStrictExactColdWindow(",
        )
        val scopedAt = strictWindow.indexOf("protectedNumericBitmapWindowBoundsForSession(")
        val retainedFirstAt = strictWindow.indexOf("installableBounds?.get(0)")
        val retainedLastAt = strictWindow.indexOf("installableBounds?.get(1)")
        val pendingTrimAt = strictWindow.indexOf(
            "trimPendingProtectedNumericBitmaps(retainedFirst, retainedLast)",
        )
        assertTrue(
            scopedAt >= 0 && retainedFirstAt > scopedAt && retainedLastAt > retainedFirstAt &&
                pendingTrimAt > retainedLastAt,
        )
    }

    @Test
    fun bitmapBudgetTrimNeverAcquiresThePageTableWhileHoldingDeliveredPixels() {
        val budgetTrim = functionBody(
            sessionSource,
            "private fun trimDeliveredBudgetLocked(",
        )
        val retainedTrim = functionBody(
            sessionSource,
            "private fun trimRetainedBitmapUnderPressureLocked(",
        )
        assertFalse(budgetTrim.contains("isStrictExactLaunchDisplayIndex("))
        assertFalse(retainedTrim.contains("isStrictExactLaunchDisplayIndex("))
        assertTrue(budgetTrim.contains("strictLaunchIndexes"))
        assertTrue(retainedTrim.contains("strictLaunchIndexes"))
    }

    @Test
    fun bitmapAdmissionConsumesOnePublishedWindowGeneration() {
        val admission = functionBody(
            sessionSource,
            "private fun isInsideProtectedNumericBitmapWindow(",
        )
        assertTrue(admission.contains("NtkProtectedBitmapWindowSnapshotPolicy.admits("))
        assertFalse(admission.contains("currentViewportAnchor.get()"))
        assertFalse(admission.contains("synchronized(windowLock)"))
        assertFalse(admission.contains("synchronized(deliveredBitmaps)"))

        val strictWindow = functionBody(
            sessionSource,
            "private fun requestStrictExactColdWindow(",
        )
        val strictPublish = strictWindow.indexOf("publishProtectedBitmapWindowSnapshot(")
        val unchangedAdmission = strictWindow.indexOf("if (admission === previous)")
        assertTrue(strictPublish >= 0 && unchangedAdmission > strictPublish)

        val genericWindow = functionBody(
            sessionSource,
            "private fun requestWindow(",
        )
        val bounds = genericWindow.indexOf("val protectedBitmapWindow =")
        val genericPublish = genericWindow.indexOf("publishProtectedBitmapWindowSnapshot(")
        assertTrue(bounds >= 0 && genericPublish > bounds)
    }

    @Test
    fun protectedBitmapWindowRevisionsBeginWhileThePageTableIsLocked() {
        assertProtectedWindowRevisionStartsInsidePagesLock(
            functionBody(sessionSource, "private fun requestWindow("),
        )
        assertProtectedWindowRevisionStartsInsidePagesLock(
            functionBody(sessionSource, "private fun requestStrictExactColdWindow("),
        )
    }

    @Test
    fun genericPhysicalDeliverySpanCommitsOnlyAfterProtectedSnapshotPublication() {
        val genericWindow = functionBody(sessionSource, "private fun requestWindow(")
        val candidateAt = genericWindow.indexOf("val physicalDeliveryFirstCandidate =")
        val retainedCommitAt = genericWindow.indexOf("val retainedCommitted =")
        assertTrue(candidateAt >= 0 && retainedCommitAt > candidateAt)

        val beforeRetainedCommit = genericWindow.substring(0, retainedCommitAt)
        assertTrue(beforeRetainedCommit.contains("val physicalDeliveryLastCandidate ="))
        assertFalse(beforeRetainedCommit.contains("physicalDeliveryFirstPage ="))
        assertFalse(beforeRetainedCommit.contains("physicalDeliveryLastPage ="))

        val commitLockAt = genericWindow.indexOf("synchronized(pagesLock)", retainedCommitAt)
        assertTrue(commitLockAt > retainedCommitAt)
        val commitLock = functionBody(
            genericWindow.substring(commitLockAt),
            "synchronized(pagesLock)",
        )
        val publishAt = commitLock.indexOf("if (!publishProtectedBitmapWindowSnapshot(")
        val publishFailureAt = commitLock.indexOf("false", publishAt)
        val physicalFirstAt = commitLock.indexOf(
            "physicalDeliveryFirstPage = physicalDeliveryFirstCandidate",
        )
        val physicalLastAt = commitLock.indexOf(
            "physicalDeliveryLastPage = physicalDeliveryLastCandidate",
        )
        assertTrue(
            publishAt >= 0 && publishFailureAt > publishAt &&
                physicalFirstAt > publishFailureAt && physicalLastAt > physicalFirstAt,
        )
        assertTrue(countOccurrences(genericWindow, "physicalDeliveryFirstPage =") == 2)
        assertTrue(countOccurrences(genericWindow, "physicalDeliveryLastPage =") == 2)
    }

    @Test
    fun strictWindowRedrivesEveryUnpublishableProtectedWindowTransaction() {
        val strictWindow = functionBody(
            sessionSource,
            "private fun requestStrictExactColdWindow(",
        )

        val visibleSourcesAt = strictWindow.indexOf("val visibleSources = synchronized(pagesLock)")
        val emptySourcesAt = strictWindow.indexOf("if (visibleSources.isEmpty())", visibleSourcesAt)
        val emptySourcesRedriveAt = strictWindow.indexOf(
            "retryRetainedWindowAfterFailedCommit(",
            emptySourcesAt,
        )
        val emptySourcesReturnAt = strictWindow.indexOf("return", emptySourcesRedriveAt)
        assertTrue(
            visibleSourcesAt >= 0 && emptySourcesAt > visibleSourcesAt &&
                emptySourcesRedriveAt > emptySourcesAt &&
                emptySourcesReturnAt > emptySourcesRedriveAt,
        )

        val initialPublishAt = strictWindow.indexOf("val published = synchronized(pagesLock)")
        val initialPublishFailureAt = strictWindow.indexOf("if (!published)", initialPublishAt)
        val initialPublishRedriveAt = strictWindow.indexOf(
            "retryRetainedWindowAfterFailedCommit(",
            initialPublishFailureAt,
        )
        val initialPublishReturnAt = strictWindow.indexOf("return", initialPublishRedriveAt)
        val unchangedAdmissionAt = strictWindow.indexOf("if (admission === previous)")
        assertTrue(
            initialPublishAt >= 0 && initialPublishFailureAt > initialPublishAt &&
                initialPublishRedriveAt > initialPublishFailureAt &&
                initialPublishReturnAt > initialPublishRedriveAt &&
                unchangedAdmissionAt > initialPublishReturnAt,
        )

        val fullSceneAt = strictWindow.indexOf("listener.areAllAuthoritativeDrawablesInstalled(pageCount)")
        val sourceDemandAt = strictWindow.indexOf(
            "if (sourceDemandChanged) applyStrictExactSourceDemand(admission)",
            fullSceneAt,
        )
        assertTrue(fullSceneAt >= 0 && sourceDemandAt > fullSceneAt)
        val fullScene = strictWindow.substring(fullSceneAt, sourceDemandAt)
        val fullScenePublishAt = fullScene.indexOf("if (!publishProtectedBitmapWindowSnapshot(")
        val fullSceneMutationAt = fullScene.indexOf("physicalDeliveryFirstPage = 0")
        val fullSceneFailureAt = fullScene.indexOf("if (!committed)")
        val fullSceneRedriveAt = fullScene.indexOf(
            "retryRetainedWindowAfterFailedCommit(",
            fullSceneFailureAt,
        )
        val fullSceneReturnAt = fullScene.indexOf("return", fullSceneRedriveAt)
        val fullSceneLock = functionBody(fullScene, "synchronized(pagesLock)")
        val lockedFullScenePublishAt = fullSceneLock.indexOf(
            "if (!publishProtectedBitmapWindowSnapshot(",
        )
        val lockedFullSceneFailureAt = fullSceneLock.indexOf("false", lockedFullScenePublishAt)
        val lockedFullSceneMutationAt = fullSceneLock.indexOf("physicalDeliveryFirstPage = 0")
        assertTrue(
            lockedFullScenePublishAt >= 0 &&
                lockedFullSceneFailureAt > lockedFullScenePublishAt &&
                lockedFullSceneMutationAt > lockedFullSceneFailureAt,
        )
        assertTrue(
            fullScenePublishAt >= 0 && fullSceneMutationAt > fullScenePublishAt &&
                fullSceneFailureAt > fullSceneMutationAt &&
                fullSceneRedriveAt > fullSceneFailureAt &&
                fullSceneReturnAt > fullSceneRedriveAt,
        )

        val finalRetainedAt = strictWindow.indexOf("val commitRetainedWindow =")
        val finalOrderAt = strictWindow.indexOf("val order = windowOrder(", finalRetainedAt)
        assertTrue(finalRetainedAt >= 0 && finalOrderAt > finalRetainedAt)
        val finalRetained = strictWindow.substring(finalRetainedAt, finalOrderAt)
        val transactionCheckAt = finalRetained.indexOf(
            "if (!isProtectedBitmapWindowTransactionCurrent(",
        )
        val retainedMutationAt = finalRetained.indexOf("commitRetainedWindow()")
        val finalFailureAt = finalRetained.indexOf("if (!committed)")
        val finalRedriveAt = finalRetained.indexOf(
            "retryRetainedWindowAfterFailedCommit(",
            finalFailureAt,
        )
        val finalReturnAt = finalRetained.indexOf("return", finalRedriveAt)
        val finalCommitAt = finalRetained.indexOf("val committed =")
        val finalTransactionLockAt = finalRetained.indexOf(
            "synchronized(pagesLock)",
            finalCommitAt,
        )
        assertTrue(finalCommitAt >= 0 && finalTransactionLockAt > finalCommitAt)
        val finalTransactionLock = functionBody(
            finalRetained.substring(finalTransactionLockAt),
            "synchronized(pagesLock)",
        )
        val lockedTransactionCheckAt = finalTransactionLock.indexOf(
            "if (!isProtectedBitmapWindowTransactionCurrent(",
        )
        val lockedTransactionFailureAt = finalTransactionLock.indexOf(
            "false",
            lockedTransactionCheckAt,
        )
        val lockedRetainedMutationAt = finalTransactionLock.indexOf("commitRetainedWindow()")
        assertTrue(
            lockedTransactionCheckAt >= 0 &&
                lockedTransactionFailureAt > lockedTransactionCheckAt &&
                lockedRetainedMutationAt > lockedTransactionFailureAt,
        )
        assertTrue(
            transactionCheckAt >= 0 && retainedMutationAt > transactionCheckAt &&
                finalFailureAt > retainedMutationAt && finalRedriveAt > finalFailureAt &&
                finalReturnAt > finalRedriveAt,
        )
    }

    @Test
    fun protectedWindowRedriveStaysArmedUntilASuccessfulWindowCommit() {
        val requestRedrive = functionBody(
            sessionSource,
            "private fun requestRetainedWindowAfterStructureChange(",
        )
        val armAt = requestRedrive.indexOf("retainedWindowRedrivePending.set(true)")
        val scheduleAt = requestRedrive.indexOf("scheduleRetainedWindowRedriveIfNeeded()")
        assertTrue(armAt >= 0 && scheduleAt > armAt)

        val retryRedrive = functionBody(
            sessionSource,
            "private fun retryRetainedWindowAfterFailedCommit(",
        )
        assertTrue(retryRedrive.contains("retainedWindowRedriveRevision == expectedRevision"))
        assertTrue(retryRedrive.contains("retainedWindowRedrivePending.set(true)"))
        assertFalse(retryRedrive.contains("retainedWindowRedriveRevision++"))

        val transactionWindows = listOf(
            functionBody(sessionSource, "private fun requestWindow("),
            functionBody(sessionSource, "private fun requestStrictExactColdWindow("),
        )
        for (window in transactionWindows) {
            val invalidTicketAt = window.indexOf("protectedBitmapWindowTicket <= 0L")
            val redriveAt = window.indexOf(
                "retryRetainedWindowAfterFailedCommit(",
                invalidTicketAt,
            )
            val returnAt = window.indexOf("return", redriveAt)
            assertTrue(
                invalidTicketAt >= 0 && redriveAt > invalidTicketAt && returnAt > redriveAt,
            )
        }

        val finishPublish = functionBody(sessionSource, "private fun finishStructurePublish(")
        val stableAt = finishPublish.indexOf("if (remaining == 0)")
        val finishScheduleAt = finishPublish.indexOf(
            "scheduleRetainedWindowRedriveIfNeeded()",
            stableAt,
        )
        assertTrue(stableAt >= 0 && finishScheduleAt > stableAt)
        val stableFinish = functionBody(
            finishPublish.substring(stableAt),
            "if (remaining == 0)",
        )
        assertTrue(stableFinish.contains("scheduleRetainedWindowRedriveIfNeeded()"))
        assertFalse(finishPublish.contains("performRetainedWindowRedrive()"))

        val scheduleRedrive = functionBody(
            sessionSource,
            "private fun scheduleRetainedWindowRedriveIfNeeded(",
        )
        val pendingGateAt = scheduleRedrive.indexOf("!retainedWindowRedrivePending.get()")
        val structureGateAt = scheduleRedrive.indexOf("isStructurePublishPending()", pendingGateAt)
        val ownerAt = scheduleRedrive.indexOf(
            "retainedWindowRedrivePosted.compareAndSet(false, true)",
        )
        val nextMainTurnAt = scheduleRedrive.indexOf("val posted = main.post", ownerAt)
        val ownerReleaseAt = scheduleRedrive.indexOf(
            "retainedWindowRedrivePosted.set(false)",
            nextMainTurnAt,
        )
        val structureRecheckAt = scheduleRedrive.indexOf(
            "if (isStructurePublishPending()) return@post",
            ownerReleaseAt,
        )
        val performAt = scheduleRedrive.indexOf(
            "performRetainedWindowRedrive()",
            structureRecheckAt,
        )
        assertTrue(
            pendingGateAt >= 0 && structureGateAt > pendingGateAt && ownerAt > structureGateAt &&
                nextMainTurnAt > ownerAt && ownerReleaseAt > nextMainTurnAt &&
                structureRecheckAt > ownerReleaseAt && performAt > structureRecheckAt,
        )

        val genericWindow = functionBody(sessionSource, "private fun requestWindow(")
        val genericFailureAt = genericWindow.indexOf("if (!retainedCommitted)")
        val genericRedriveAt = genericWindow.indexOf(
            "retryRetainedWindowAfterFailedCommit(",
            genericFailureAt,
        )
        val genericReturnAt = genericWindow.indexOf("return", genericRedriveAt)
        val genericClearAt = genericWindow.indexOf(
            "acknowledgeRetainedWindowRedrive(retainedRedriveRevisionAtStart)",
            genericReturnAt,
        )
        assertTrue(
            genericFailureAt >= 0 && genericRedriveAt > genericFailureAt &&
                genericReturnAt > genericRedriveAt && genericClearAt > genericReturnAt,
        )
        assertTrue(countOccurrences(genericWindow, "retainedWindowRedrivePending.set(false)") == 0)
        assertTrue(countOccurrences(genericWindow, "acknowledgeRetainedWindowRedrive(") == 1)

        val strictWindow = functionBody(
            sessionSource,
            "private fun requestStrictExactColdWindow(",
        )
        assertTrue(countOccurrences(strictWindow, "if (!committed)") == 4)
        assertTrue(countOccurrences(strictWindow, "retainedWindowRedrivePending.set(false)") == 0)
        assertTrue(countOccurrences(strictWindow, "acknowledgeRetainedWindowRedrive(") == 5)
        assertTrue(strictWindow.contains("retainedRedriveRevisionAtStart: Long"))
        var cursor = 0
        repeat(4) {
            val failureAt = strictWindow.indexOf("if (!committed)", cursor)
            val redriveAt = strictWindow.indexOf(
                "retryRetainedWindowAfterFailedCommit(",
                failureAt,
            )
            val returnAt = strictWindow.indexOf("return", redriveAt)
            assertTrue(
                failureAt >= cursor && redriveAt > failureAt && returnAt > redriveAt,
            )
            cursor = returnAt + 1
        }
    }

    @Test
    fun retainedWindowReplayUsesTheLatestIdentityBoundPhysicalViewport() {
        val publicOffer = functionBody(sessionSource, "fun requestWindowAsync(")
        val captureAt = publicOffer.indexOf("latestReportedWindow.set(synchronized(pagesLock)")
        val pageAt = publicOffer.indexOf("anchorPage = pages.getOrNull(safeAnchor)", captureAt)
        val offerAt = publicOffer.indexOf("offerWindowAsync(", pageAt)
        assertTrue(captureAt >= 0 && pageAt > captureAt && offerAt > pageAt)

        val replay = functionBody(sessionSource, "private fun performRetainedWindowRedrive(")
        val latestAt = replay.indexOf("val reported = latestReportedWindow.get()")
        val resolveAt = replay.indexOf("pageIndexLocked(page, window.fallbackAnchor)", latestAt)
        val pendingAt = replay.indexOf("if (!retainedWindowRedrivePending.get()", resolveAt)
        val internalOfferAt = replay.indexOf("offerWindowAsync(", pendingAt)
        assertTrue(
            latestAt >= 0 && resolveAt > latestAt && pendingAt > resolveAt &&
                internalOfferAt > pendingAt,
        )
        assertFalse(replay.contains("requestWindowAsync("))
    }

    @Test
    fun continuousWindowTransactionsDoNotClearAnOffscreenAdjacentRunway() {
        val window = functionBody(sessionSource, "private fun requestWindow(")
        val transactionDecisionAt = window.indexOf("val usesProtectedBitmapWindow =")
        val pageDecisionAt = window.indexOf(
            "usesProtectedNumericNtkPipeline(anchorPage) || usesProtectedNumericNtkPipeline()",
            transactionDecisionAt,
        )
        val transactionAt = window.indexOf(
            "val protectedBitmapWindowTransaction = synchronized(pagesLock)",
            pageDecisionAt,
        )
        assertTrue(
            transactionDecisionAt >= 0 && pageDecisionAt > transactionDecisionAt &&
                transactionAt > pageDecisionAt,
        )

        val admission = functionBody(
            sessionSource,
            "private fun isInsideProtectedNumericBitmapWindow(",
        )
        assertTrue(admission.contains("if (!usesProtectedNumericNtkPipeline(page)) return true"))
        assertFalse(
            admission.contains(
                "!usesProtectedNumericNtkPipeline(page) && !usesProtectedNumericNtkPipeline()",
            ),
        )
    }

    @Test
    fun adjacentStructurePublishOwnersFinishExactlyOnceAcrossEveryExit() {
        val beginOwned = functionBody(sessionSource, "private fun beginOwnedStructurePublish(")
        val beginAt = beginOwned.indexOf("beginStructurePublish()")
        val freshOwnerAt = beginOwned.indexOf("return AtomicBoolean(false)")
        assertTrue(beginAt >= 0 && freshOwnerAt > beginAt)

        val finishOwned = functionBody(sessionSource, "private fun finishOwnedStructurePublish(")
        val claimAt = finishOwned.indexOf("owner?.compareAndSet(false, true) == true")
        val finishAt = finishOwned.indexOf("finishStructurePublish()", claimAt)
        assertTrue(claimAt >= 0 && finishAt > claimAt)
        assertTrue(countOccurrences(finishOwned, "finishStructurePublish()") == 1)

        val capture = "structurePublishOwner = beginOwnedStructurePublish()"
        val appendOnlyCapture =
            "structurePublishOwner = beginOwnedAppendOnlyStructurePublish(startIndex)"
        assertTrue(countOccurrences(sessionSource, capture) == 4)
        assertTrue(countOccurrences(sessionSource, appendOnlyCapture) == 1)
        assertTrue(countOccurrences(sessionSource, "beginOwnedStructurePublish()") == 6)

        val resolvedAppend = functionBody(sessionSource, "private fun appendResolvedEpisode(")
        val prependCaptureAt = resolvedAppend.indexOf(capture)
        val forwardCaptureAt = resolvedAppend.indexOf(
            capture,
            prependCaptureAt + capture.length,
        )
        assertTrue(prependCaptureAt >= 0 && forwardCaptureAt > prependCaptureAt)

        fun ownedPath(source: String): String {
            val captureAt = source.indexOf(capture)
            assertTrue(captureAt >= 0)
            return source.substring(captureAt)
        }

        val remaining = functionBody(
            sessionSource,
            "private fun appendRemainingAdjacentRunwayRefs(",
        )
        val paths = listOf(
            resolvedAppend.substring(prependCaptureAt, forwardCaptureAt) to capture,
            resolvedAppend.substring(forwardCaptureAt) to capture,
            ownedPath(
                functionBody(sessionSource, "private fun appendResolvedEpisodeInitialRunway("),
            ) to capture,
            remaining.substring(remaining.indexOf(appendOnlyCapture)) to appendOnlyCapture,
            ownedPath(
                functionBody(sessionSource, "private fun publishDirectWifiAdjacentExactP0Head("),
            ) to capture,
        )
        val expectedFinishCounts = listOf(3, 3, 3, 4, 3)
        val ownedFinish = "finishOwnedStructurePublish(structurePublishOwner)"
        paths.zip(expectedFinishCounts).forEach { (pathAndCapture, expectedFinishCount) ->
            val (path, ownerCapture) = pathAndCapture
            assertTrue(countOccurrences(path, ownerCapture) == 1)
            assertTrue(countOccurrences(path, ownedFinish) == expectedFinishCount)
            assertTrue(
                countOccurrences(path, "finishOwnedStructurePublish(") == expectedFinishCount,
            )
            assertFalse(path.contains("finishStructurePublish()"))

            val firstFinishAt = path.indexOf(ownedFinish)
            val postedAt = path.indexOf("val posted = main.post")
            val finallyAt = path.indexOf("finally {", postedAt)
            val finallyFinishAt = path.indexOf(ownedFinish, finallyAt)
            val postFailureAt = path.lastIndexOf("if (!posted)")
            val postFailureFinishAt = path.indexOf(ownedFinish, postFailureAt)
            assertTrue(
                firstFinishAt > 0 && finallyAt > firstFinishAt &&
                    finallyFinishAt > finallyAt && postFailureAt > finallyFinishAt &&
                    postFailureFinishAt > postFailureAt,
            )
        }

        // The remaining-runway path can fail before it owns a main callback. Both independent
        // preparation errors must retire the same CAS owner captured with the page-table append.
        val remainingPath = paths[3].first
        val missingBatch = functionBody(
            remainingPath,
            "if (drawableBatch == null)",
        )
        val invalidExactPublication = functionBody(
            remainingPath,
            "if (nativeExactBatchRequired && exactRunwayPublication == null)",
        )
        assertTrue(missingBatch.contains(ownedFinish))
        assertTrue(invalidExactPublication.contains(ownedFinish))
        assertFalse(missingBatch.contains("finishStructurePublish()"))
        assertFalse(invalidExactPublication.contains("finishStructurePublish()"))

        val exactP0Path = paths[4].first
        val errorAt = exactP0Path.indexOf("catch (failure: Exception)")
        val errorFinallyAt = exactP0Path.indexOf("finally {", errorAt)
        val errorFinishAt = exactP0Path.indexOf(ownedFinish, errorFinallyAt)
        assertTrue(errorAt >= 0 && errorFinallyAt > errorAt && errorFinishAt > errorFinallyAt)

        val legacyGlobalFinish = Regex(
            """if\s*\(\s*isStructurePublishPending\(\)\s*\)\s*\{?\s*finishStructurePublish\(\)""",
        )
        assertFalse(legacyGlobalFinish.containsMatchIn(sessionSource))
    }

    @Test
    fun everyDestructivePixelTrimExcludesThePublishedProtectedWindow() {
        val budgetTrim = functionBody(
            sessionSource,
            "private fun trimDeliveredBudgetLocked(",
        )
        assertTrue(countOccurrences(budgetTrim, "entry.key in protectedPixelWindow") >= 2)
        assertBitmapAndTileLoopsExcludeProtectedPixels(budgetTrim)
        val retainedPressureAt = budgetTrim.indexOf("trimRetainedBitmapUnderPressureLocked(")
        val retainedProtectedArgumentAt = budgetTrim.indexOf(
            "protectedPixelWindow,",
            retainedPressureAt,
        )
        assertTrue(
            retainedPressureAt >= 0 && retainedProtectedArgumentAt > retainedPressureAt,
        )

        val outsideTrim = functionBody(sessionSource, "private fun evictDeliveredBitmaps(")
        assertProtectedPixelWindowHasLegacyEmptyFallback(outsideTrim)
        assertTrue(countOccurrences(outsideTrim, "entry.key in protectedPixelWindow") >= 2)
        assertBitmapAndTileLoopsExcludeProtectedPixels(outsideTrim)

        val retainedTrim = functionBody(
            sessionSource,
            "private fun trimRetainedBitmapUnderPressureLocked(",
        )
        assertTrue(countOccurrences(retainedTrim, ".filter { it !in protectedPixelWindow }") >= 2)

        val shortTrim = functionBody(
            sessionSource,
            "private fun trimShortWebtoonLaunchPixelsOutsideWindow(",
        )
        assertProtectedPixelWindowHasLegacyEmptyFallback(shortTrim)
        assertTrue(countOccurrences(shortTrim, "entry.key in protectedPixelWindow") >= 2)
        assertBitmapAndTileLoopsExcludeProtectedPixels(shortTrim)

        // These are every direct budget-trim caller. Each derives the immutable range while
        // holding pagesLock and makes legacy/non-NTK sessions explicitly unprotected.
        val budgetCallers = listOf(
            functionBody(sessionSource, "private fun trackDeliveredBitmap("),
            functionBody(sessionSource, "private fun trackDeliveredTiles("),
            outsideTrim,
            functionBody(sessionSource, "private fun trimDeliveredBitmapsToBudget("),
        )
        assertTrue(countOccurrences(sessionSource, "trimDeliveredBudgetLocked(") == 5)
        for (caller in budgetCallers) {
            assertProtectedPixelWindowHasLegacyEmptyFallback(caller)
            val callAt = caller.indexOf("trimDeliveredBudgetLocked(")
            val protectedArgumentAt = caller.indexOf("protectedPixelWindow,", callAt)
            assertTrue(callAt >= 0 && protectedArgumentAt > callAt)
        }
    }

    @Test
    fun forwardHistoryNeverRetiresOrRenumbersTheProtectedBitmapWindow() {
        val retirePixels = functionBody(
            sessionSource,
            "private fun retireConsumedForwardHistoryPixels(",
        )
        assertProtectedPixelWindowHasLegacyEmptyFallback(retirePixels)
        val retainedBitmapAt = retirePixels.indexOf("for ((index, bitmap) in deliveredBitmaps)")
        val retainedBitmapGuardAt = retirePixels.indexOf(
            "index >= retireBefore || index in protectedPixelWindow",
            retainedBitmapAt,
        )
        val retainedBitmapAddAt = retirePixels.indexOf(
            "retainedIdentities.add(bitmap)",
            retainedBitmapGuardAt,
        )
        val retainedTilesAt = retirePixels.indexOf("for ((index, tiles) in deliveredTiles)")
        val retainedTilesGuardAt = retirePixels.indexOf(
            "index >= retireBefore || index in protectedPixelWindow",
            retainedTilesAt,
        )
        val retainedTilesAddAt = retirePixels.indexOf(
            "tiles.forEach { tile -> retainedIdentities.add(tile.bitmap) }",
            retainedTilesGuardAt,
        )
        val bitmapIteratorAt = retirePixels.indexOf("val bitmapIterator =")
        val bitmapSkipAt = retirePixels.indexOf(
            "entry.key >= retireBefore || entry.key in protectedPixelWindow",
            bitmapIteratorAt,
        )
        val tileIteratorAt = retirePixels.indexOf("val tileIterator =")
        val tileSkipAt = retirePixels.indexOf(
            "entry.key >= retireBefore || entry.key in protectedPixelWindow",
            tileIteratorAt,
        )
        assertTrue(
            retainedBitmapAt >= 0 && retainedBitmapGuardAt > retainedBitmapAt &&
                retainedBitmapAddAt > retainedBitmapGuardAt && retainedTilesAt > retainedBitmapAddAt &&
                retainedTilesGuardAt > retainedTilesAt && retainedTilesAddAt > retainedTilesGuardAt &&
                bitmapIteratorAt > retainedTilesAddAt && bitmapSkipAt > bitmapIteratorAt &&
                tileIteratorAt > bitmapSkipAt && tileSkipAt > tileIteratorAt,
        )

        val trimHistory = functionBody(
            sessionSource,
            "private fun trimConsumedForwardHistory(",
        )
        assertProtectedPixelWindowHasLegacyEmptyFallback(trimHistory)
        val verifiedAt = trimHistory.indexOf(
            "val verified = forwardHistoryTrimCandidateLocked(verifiedAnchor, activePage)",
        )
        val protectedWindowAt = trimHistory.indexOf("val protectedPixelWindow =", verifiedAt)
        val removedPrefixAt = trimHistory.indexOf("val removedPrefixLast =", protectedWindowAt)
        val intersectionAt = trimHistory.indexOf(
            "if (!protectedPixelWindow.isEmpty() &&",
            removedPrefixAt,
        )
        val intersection = functionBody(
            trimHistory.substring(intersectionAt),
            "if (!protectedPixelWindow.isEmpty() &&",
        )
        val retryAt = intersection.indexOf("scheduleForwardReadingRetry()")
        val returnAt = intersection.indexOf("return verifiedAnchor", retryAt)
        val destructivePrefixAt = trimHistory.indexOf(
            "for (index in 0 until candidate.removeCount)",
            intersectionAt,
        )
        val structurePublishAt = trimHistory.indexOf("beginStructurePublish()", destructivePrefixAt)
        assertTrue(intersection.contains("protectedPixelWindow.first <= removedPrefixLast"))
        assertTrue(intersection.contains("protectedPixelWindow.last >= 0"))
        assertTrue(retryAt >= 0 && returnAt > retryAt)
        assertTrue(
            verifiedAt >= 0 && protectedWindowAt > verifiedAt &&
                removedPrefixAt > protectedWindowAt && intersectionAt > removedPrefixAt &&
                destructivePrefixAt > intersectionAt + intersection.length &&
                structurePublishAt > destructivePrefixAt,
        )
    }

    @Test
    fun surfaceClearsWaitForStableStructureBeforeRecycleAndHistoryRelease() {
        val retirePixels = functionBody(
            sessionSource,
            "private fun retireConsumedForwardHistoryPixels(",
        )
        assertTrue(retirePixels.contains("afterSurfaceClears: (() -> Unit)? = null"))
        val releasePostAt = retirePixels.indexOf(
            "postBitmapReleases(releases) {",
        )
        val releaseCallbackAt = retirePixels.indexOf(
            "afterSurfaceClears?.invoke()",
            releasePostAt,
        )
        val releaseSuccessAt = retirePixels.lastIndexOf("return true")
        assertTrue(
            releasePostAt >= 0 &&
                releaseCallbackAt > releasePostAt &&
                releaseSuccessAt > releaseCallbackAt,
        )

        val publishReleases = functionBody(sessionSource, "private fun postBitmapReleases(")
        val publishClearsAt = publishReleases.indexOf("val publishClears = object : Runnable")
        assertTrue(publishClearsAt >= 0)
        val publishClears = functionBody(
            publishReleases.substring(publishClearsAt),
            "override fun run()",
        )
        val structurePendingAt = publishClears.indexOf("isStructurePublishPending()")
        val surfaceClearAt = publishClears.indexOf(
            "currentUndeliveredPageIndex(target.page, target.fallbackIndex)",
            structurePendingAt,
        )
        val rollingSurfaceClearAt = publishClears.indexOf(
            "currentUndeliveredPageIndex(target.page, target.fallbackIndex)",
            surfaceClearAt + 1,
        )
        val deferredBranchAt = publishClears.indexOf(
            "if (deferredForStructure)",
            rollingSurfaceClearAt,
        )
        val parkAt = publishClears.indexOf("dispatchWhenStructureStable(this)")
        val parkReturnAt = publishClears.indexOf("return", parkAt)
        val retirementTransferAt = publishClears.indexOf(
            "transferSessionOwnedBitmapRetirement(ownedBitmaps)",
            parkReturnAt,
        )
        val afterClearsAt = publishClears.indexOf(
            "afterSurfaceClears?.invoke()",
            retirementTransferAt,
        )
        assertTrue(
            structurePendingAt >= 0 && surfaceClearAt > structurePendingAt &&
                rollingSurfaceClearAt > surfaceClearAt && deferredBranchAt > rollingSurfaceClearAt &&
                parkAt > deferredBranchAt && parkReturnAt > parkAt &&
                retirementTransferAt > rollingSurfaceClearAt &&
                afterClearsAt > retirementTransferAt,
        )
        assertFalse(publishReleases.contains("recycleBitmapAfterPressureDelay(bitmap)"))
        assertFalse(publishReleases.contains("recycleBitmapAfterDelay(bitmap)"))
        val parkedBranch = publishClears.substring(deferredBranchAt, parkReturnAt)
        assertFalse(parkedBranch.contains("recycleBitmap"))
        assertFalse(parkedBranch.contains("afterSurfaceClears"))

        val stableDispatch = functionBody(
            sessionSource,
            "private fun dispatchWhenStructureStable(",
        )
        val enqueueAt = stableDispatch.indexOf("structureStableCallbacks.addLast(callback)")
        val stableAt = stableDispatch.indexOf("if (!deferred)", enqueueAt)
        val mainThreadAt = stableDispatch.indexOf(
            "Looper.myLooper() === main.looper",
            stableAt,
        )
        val immediateAt = stableDispatch.indexOf("callback.run()", mainThreadAt)
        val postAt = stableDispatch.indexOf("main.post(callback)", immediateAt)
        assertTrue(
            enqueueAt >= 0 && stableAt > enqueueAt && mainThreadAt > stableAt &&
                immediateAt > mainThreadAt && postAt > immediateAt,
        )

        val trimHistory = functionBody(
            sessionSource,
            "private fun trimConsumedForwardHistory(",
        )
        val pendingGateAt = trimHistory.indexOf("if (forwardHistoryPixelClearPending.get())")
        val candidateAt = trimHistory.indexOf("val pixelCandidate = synchronized(pagesLock)")
        val reserveBarrierAt = trimHistory.indexOf(
            "forwardHistoryPixelClearPending.compareAndSet(false, true)",
        )
        val retireAt = trimHistory.indexOf("released = retireConsumedForwardHistoryPixels(")
        assertTrue(reserveBarrierAt > candidateAt && retireAt > reserveBarrierAt)
        val retireWithCallback = functionBody(
            trimHistory.substring(retireAt),
            "released = retireConsumedForwardHistoryPixels(",
        )
        val callbackClearAt = retireWithCallback.indexOf(
            "forwardHistoryPixelClearPending.set(false)",
        )
        val callbackRetryAt = retireWithCallback.indexOf(
            "scheduleForwardReadingRetry()",
            callbackClearAt,
        )
        assertTrue(callbackClearAt >= 0 && callbackRetryAt > callbackClearAt)

        val releasedBranchAt = trimHistory.indexOf(
            "if (released)",
            retireAt + retireWithCallback.length,
        )
        assertTrue(releasedBranchAt > retireAt)
        val releasedBranch = functionBody(
            trimHistory.substring(releasedBranchAt),
            "if (released)",
        )
        assertTrue(releasedBranch.contains("return currentPageIndex("))
        assertFalse(releasedBranch.contains("forwardHistoryPixelClearPending.set(false)"))
        val noReleaseClearAt = trimHistory.indexOf(
            "forwardHistoryPixelClearPending.set(false)",
            releasedBranchAt + releasedBranch.length,
        )
        val structureRemovalAt = trimHistory.indexOf("beginStructurePublish()", noReleaseClearAt)
        assertTrue(pendingGateAt >= 0 && candidateAt > pendingGateAt)
        val pendingGate = trimHistory.substring(pendingGateAt, candidateAt)
        assertTrue(pendingGate.contains("scheduleForwardReadingRetry()"))
        assertTrue(pendingGate.contains("return currentPageIndex(activePage, anchor)"))
        assertTrue(
            noReleaseClearAt > releasedBranchAt + releasedBranch.length &&
                structureRemovalAt > noReleaseClearAt,
        )
        val releaseToRemoval = trimHistory.substring(retireAt, structureRemovalAt)
        assertFalse(releaseToRemoval.contains("main.post"))

        val currentIndex = functionBody(
            sessionSource,
            "private fun currentUndeliveredPageIndex(",
        )
        assertFalse(currentIndex.contains("isStructurePublishPending()"))
        assertFalse(currentIndex.contains("return@synchronized fallbackIndex.takeIf"))
        assertTrue(currentIndex.contains("pageIndexLocked(page, fallbackIndex)"))
    }

    @Test
    fun bitmapTrackingAndReleaseFollowTheExactPageAcrossPrefixCompaction() {
        val trackBitmap = functionBody(sessionSource, "private fun trackDeliveredBitmap(")
        val bitmapPagesLock = trackBitmap.indexOf("synchronized(pagesLock)")
        val bitmapDeliveredLock = trackBitmap.indexOf("synchronized(deliveredBitmaps)")
        assertTrue(bitmapPagesLock >= 0 && bitmapDeliveredLock > bitmapPagesLock)
        assertTrue(trackBitmap.contains("pageIndexLocked(page, index)"))

        val trackTiles = functionBody(sessionSource, "private fun trackDeliveredTiles(")
        val tilePagesLock = trackTiles.indexOf("synchronized(pagesLock)")
        val tileDeliveredLock = trackTiles.indexOf("synchronized(deliveredBitmaps)")
        assertTrue(tilePagesLock >= 0 && tileDeliveredLock > tilePagesLock)
        assertTrue(trackTiles.contains("pageIndexLocked(page, index)"))

        val release = functionBody(sessionSource, "private fun postBitmapReleases(")
        assertTrue(release.contains("release.page?.let"))
        assertTrue(release.contains("clearReleasedPageStateIfStillUndelivered("))
        assertTrue(release.contains("currentUndeliveredPageIndex("))

        val shortTrim = functionBody(
            sessionSource,
            "private fun trimShortWebtoonLaunchPixelsOutsideWindow(",
        )
        assertTrue(
            shortTrim.indexOf("synchronized(pagesLock)") in
                0 until shortTrim.indexOf("synchronized(deliveredBitmaps)"),
        )
        val historyTrim = functionBody(
            sessionSource,
            "private fun retireConsumedForwardHistoryPixels(",
        )
        assertTrue(
            historyTrim.indexOf("synchronized(pagesLock)") in
                0 until historyTrim.indexOf("synchronized(deliveredBitmaps)"),
        )
    }

    @Test
    fun anEvictedStrictAdjacentPageRehydratesFromItsExactBodyWithoutAGenericFlight() {
        val request = functionBody(sessionSource, "private fun requestPage(")
        val routeAt = request.indexOf("routeStrictAdjacentExactRehydrate(")
        val joinAt = request.indexOf("joinExistingPagePipelineOwner(")
        val genericProofAt = request.indexOf("routeActiveGeneratedRequestToProofOrBytes(")
        assertTrue(routeAt >= 0 && joinAt > routeAt && genericProofAt > joinAt)

        val foreground = functionBody(
            sessionSource,
            "private fun startBoundedForegroundStreamFetch(",
        )
        val foregroundRouteAt = foreground.indexOf("routeStrictAdjacentExactRehydrate(")
        val genericFlightAt = foreground.indexOf("acquireForegroundByteFlight(")
        assertTrue(foregroundRouteAt >= 0 && genericFlightAt > foregroundRouteAt)

        val interactiveRunway = functionBody(
            sessionSource,
            "private fun startInitialInteractiveRunwayByteFetch(",
        )
        val directExactAt = interactiveRunway.indexOf("routeStrictAdjacentExactRehydrate(")
        val genericMarkerAt = interactiveRunway.indexOf("initialInteractiveRunwayByteFetches.add")
        assertTrue(directExactAt >= 0 && genericMarkerAt > directExactAt)

        val exactRoute = functionBody(
            sessionSource,
            "private fun routeStrictAdjacentExactRehydrate(",
        )
        val pixelWindowGateAt = exactRoute.indexOf("!isInsideProtectedNumericBitmapWindow")
        val flightOwnerAt = exactRoute.indexOf("strictAdjacentRehydrateFlights.putIfAbsent")
        assertTrue(pixelWindowGateAt >= 0 && flightOwnerAt > pixelWindowGateAt)
        assertTrue(exactRoute.contains("pendingDeliveryWidths[index]"))
        assertTrue(exactRoute.contains("isPageAuthoritativeDrawableInstalled(index)"))
        assertTrue(exactRoute.contains("scheduleStrictAdjacentExactRehydrate"))

        val exactClassification = functionBody(
            sessionSource,
            "private fun isStrictAdjacentExactPage(",
        )
        assertTrue(exactClassification.contains("!strictExactColdRolling"))
        assertTrue(exactClassification.contains("Manga.sameEpisodeIdentity(ref.manga, manga)"))
        assertTrue(exactClassification.contains("adjacentStrictSourceClaims[path]"))
        assertTrue(exactClassification.contains("adjacentStrictRecoveryStates[path]"))
        assertTrue(exactClassification.contains("claim.manifestDigest == ref.manifestDigest"))

        val exactWorker = functionBody(
            sessionSource,
            "private fun runStrictAdjacentExactRehydrate(",
        )
        assertTrue(exactWorker.contains("strictAdjacentBodyDescriptor(currentPage)"))
        assertTrue(exactWorker.contains("strictAdjacentPublishedBody(currentPage)"))
        assertTrue(exactWorker.contains("allowCurrentPublishedStrictBodyForRehydrate = true"))
        val postAt = exactWorker.indexOf("postDecodeResult(delivery)")
        val completeAt = exactWorker.indexOf(
            "completeStrictAdjacentRehydrateDelivery(",
            startIndex = postAt,
        )
        assertTrue(postAt >= 0 && completeAt > postAt)
        assertFalse(exactWorker.contains("acquireForegroundByteFlight"))
        assertFalse(exactWorker.contains("getOrFetchFileForeground"))
        assertFalse(exactWorker.contains("postPageError("))

        val retry = functionBody(
            sessionSource,
            "private fun postStrictAdjacentExactRehydrateRetry(",
        )
        assertTrue(retry.contains("NTK_STRICT_ADJACENT_REHYDRATE_MAX_POLLS"))
        assertTrue(retry.contains("parkStrictAdjacentExactRehydrate("))
        val park = functionBody(
            sessionSource,
            "private fun parkStrictAdjacentExactRehydrate(",
        )
        val ownerReleaseAt = park.indexOf("flight.ownerScheduledOrRunning.set(false)")
        val wakeCheckAt = park.indexOf("flight.wakeVersion.get() != observedWakeVersion")
        assertTrue(ownerReleaseAt >= 0 && wakeCheckAt > ownerReleaseAt)
        assertTrue(park.contains("scheduleStrictAdjacentExactRehydrate(flight, visibleIntent)"))
        val wake = functionBody(
            sessionSource,
            "private fun wakeStrictAdjacentExactRehydrate(",
        )
        assertTrue(wake.contains("flight.wakeVersion.incrementAndGet()"))
        assertTrue(wake.contains("flight.retryCount.set(0)"))
        assertTrue(wake.contains("flight.parked.set(false)"))
        assertTrue(wake.contains("scheduleStrictAdjacentExactRehydrate"))

        val completion = functionBody(
            sessionSource,
            "private fun completeStrictAdjacentRehydrateDelivery(",
        )
        val mainTurnAt = completion.indexOf("main.post")
        val sourceReleaseAt = completion.indexOf(
            "strictAdjacentRehydrateFlights.remove(flight.identity, flight)",
            startIndex = mainTurnAt,
        )
        assertTrue(mainTurnAt >= 0 && sourceReleaseAt > mainTurnAt)
        assertTrue(completion.contains("autoSplitSiblingPage(currentIndex, currentPage)"))
        assertTrue(completion.contains("routeStrictAdjacentExactRehydrate("))

        val adjacentDelivery = functionBody(
            sessionSource,
            "private fun prepareAdjacentRunwayDelivery(",
        )
        assertTrue(
            adjacentDelivery.contains(
                "requireStrictDescriptor || allowCurrentPublishedStrictBodyForRehydrate",
            ),
        )

        val publishedBody = functionBody(
            sessionSource,
            "private fun strictAdjacentPublishedBody(",
        )
        assertTrue(publishedBody.contains("ref.manifestDigest != seal.digestSha256"))
        assertTrue(publishedBody.contains("ref.manifestPageCount != seal.pageCount"))
        assertTrue(publishedBody.contains("ref.canonicalAsset != seal.normalizedCanonicalAssets"))

        val identity = functionBody(
            sessionSource,
            "private fun strictAdjacentRehydrateIdentity(",
        )
        assertFalse(identity.contains("append(ref.side)"))

        val consumedCleanup = functionBody(
            sessionSource,
            "private fun retireConsumedStrictSources(",
        )
        assertTrue(consumedCleanup.contains("strictAdjacentRehydrateFlights.entries.removeIf"))
    }

    @Test
    fun adoptedExactOffscreenSuffixCannotFallIntoPeriodicGenericLeaseRetries() {
        val append = functionBody(
            sessionSource,
            "private fun appendRemainingAdjacentRunwayRefs(",
        )
        val park = functionBody(
            sessionSource,
            "private fun parkImmutableStrictOffscreenAdjacentRemainder(",
        )
        val identity = functionBody(
            sessionSource,
            "private fun hasImmutableStrictAdjacentManifestIdentity(",
        )

        assertTrue(append.contains("parkImmutableStrictOffscreenAdjacentRemainder("))
        assertTrue(append.indexOf("parkImmutableStrictOffscreenAdjacentRemainder(") <
            append.indexOf("prepareAndParkOffscreenAdjacentRemainderIfIdle("))
        assertTrue(park.contains("parkedAdjacentRemainderAppends.put(path, parked)"))
        assertTrue(park.contains("scheduledRemainingAdjacentRunwayRetries.cancelPath(path)"))
        assertTrue(park.contains("installedInitialSources < requiredInitialRunway"))
        assertFalse(park.contains("scheduleRemainingAdjacentRunwayAppend("))
        assertTrue(identity.contains("page.manifestDigest == digest"))
        assertTrue(identity.contains("page.manifestPageCount == pageCount"))
        assertTrue(identity.contains("!page.canonicalAsset.isNullOrBlank()"))
    }

    @Test
    fun hostGpuOffscreenSuffixKeepsEncodedAuthorityWithoutPredecodingPixels() {
        val prepare = functionBody(
            sessionSource,
            "private fun prepareAndParkOffscreenAdjacentRemainderIfIdle(",
        )
        val encodedPark = prepare.indexOf(
            "if (hostGpuEmulatorRuntime && !isViewportInsideEpisode(target))"
        )
        val decode = prepare.indexOf("decodePage(")

        assertTrue(encodedPark >= 0)
        assertTrue(decode > encodedPark)
        assertTrue(
            prepare.substring(encodedPark, decode)
                .contains("parkedAdjacentRemainderAppends[path]")
        )
        assertTrue(
            prepare.substring(encodedPark, decode)
                .contains("startRemainingAdjacentRunwayFileFetches(")
        )
        assertTrue(
            prepare.substring(encodedPark, decode)
                .contains("return true")
        )
    }

    @Test
    fun appendOnlyStateCleanupRunsAfterTheShortPageTableCommit() {
        val initial = functionBody(
            sessionSource,
            "private fun appendResolvedEpisodeInitialRunway(",
        )
        val remaining = functionBody(
            sessionSource,
            "private fun appendRemainingAdjacentRunwayRefs(",
        )
        val exactP0 = functionBody(
            sessionSource,
            "private fun publishDirectWifiAdjacentExactP0Head(",
        )

        assertTrue(initial.indexOf("pages.addAll(initialRefs)") <
            initial.indexOf("clearPageStateFromIndex(cardIndex, appendOnlyTail = true)"))
        assertTrue(remaining.indexOf("pages.addAll(appendable)") <
            remaining.indexOf("clearPageStateFromIndex(startIndex, appendOnlyTail = true)"))
        assertTrue(exactP0.indexOf("pages.addAll(initialRefs)") <
            exactP0.indexOf("clearPageStateFromIndex(cardIndex, appendOnlyTail = true)"))
    }

    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "Missing function: $signature" }
        val open = source.indexOf('{', start)
        check(open >= 0)
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        error("Unclosed function: $signature")
    }

    private fun assertProtectedWindowRevisionStartsInsidePagesLock(window: String) {
        val transactionSignature =
            "val protectedBitmapWindowTransaction = synchronized(pagesLock)"
        val transactionAt = window.indexOf(transactionSignature)
        assertTrue(transactionAt >= 0)
        val transaction = functionBody(
            window.substring(transactionAt),
            transactionSignature,
        )
        val revisionAt = transaction.indexOf(
            "protectedBitmapWindowRevision.incrementAndGet() to indexedStateGeneration.get()",
        )
        val ticketAt = window.indexOf(
            "val protectedBitmapWindowTicket = protectedBitmapWindowTransaction.first",
            transactionAt,
        )
        assertTrue(transactionAt >= 0 && revisionAt >= 0)
        assertTrue(ticketAt >= transactionAt + transaction.length)
    }

    private fun assertProtectedPixelWindowHasLegacyEmptyFallback(source: String) {
        val declarationAt = source.indexOf(
            "val protectedPixelWindow = if (usesProtectedNumericNtkPipeline())",
        )
        val protectedRangeAt = source.indexOf(
            "protectedNumericBitmapWindow(pages.size)",
            declarationAt,
        )
        val legacyFallbackAt = source.indexOf("IntRange.EMPTY", protectedRangeAt)
        assertTrue(
            declarationAt >= 0 && protectedRangeAt > declarationAt &&
                legacyFallbackAt > protectedRangeAt,
        )
    }

    private fun assertBitmapAndTileLoopsExcludeProtectedPixels(source: String) {
        val bitmapLoopAt = source.indexOf("deliveredBitmaps.entries.iterator()")
        val bitmapSkipAt = source.indexOf("entry.key in protectedPixelWindow", bitmapLoopAt)
        val tileLoopAt = source.indexOf("deliveredTiles.entries.iterator()", bitmapLoopAt)
        val tileSkipAt = source.indexOf("entry.key in protectedPixelWindow", tileLoopAt)
        assertTrue(
            bitmapLoopAt >= 0 && bitmapSkipAt > bitmapLoopAt &&
                tileLoopAt > bitmapSkipAt && tileSkipAt > tileLoopAt,
        )
    }

    private fun countOccurrences(source: String, needle: String): Int {
        var count = 0
        var cursor = 0
        while (true) {
            val match = source.indexOf(needle, cursor)
            if (match < 0) return count
            count++
            cursor = match + needle.length
        }
    }
}
