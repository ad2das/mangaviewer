package ml.melun.mangaview.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NtkAdjacentPhysicalBoundaryLivenessArchitectureTest {
    private val coordinator = File(
        "src/main/java/ml/melun/mangaview/reader/NtkStrictEpisodeDiscoveryCoordinator.kt",
    ).readText()
    private val session = File(
        "src/main/java/ml/melun/mangaview/reader/ReaderSession.kt",
    ).readText()
    private val pacer = File(
        "src/main/java/ml/melun/mangaview/reader/NtkReaderTransferPacer.kt",
    ).readText()
    private val surface = File(
        "src/main/java/ml/melun/mangaview/reader/ReaderSurfaceView.kt",
    ).readText()
    private val activity = File(
        "src/main/java/ml/melun/mangaview/activity/ReaderV2Activity.kt",
    ).readText()

    @Test
    fun realSurfaceBoundaryReleasesOnlyMotionDeferralNotNetworkOrStructure() {
        val release = functionBody(
            coordinator,
            "fun releaseAdjacentPhysicalBoundaryDemand(",
            "private fun enterForegroundNetworkIfNeeded(",
        )
        assertTrue(release.contains("ViewerTelemetry.isActiveViewer("))
        assertTrue(release.contains("physicalBoundaryAdjacentTargets"))
        assertTrue(release.contains("adjacentPhysicalBoundaryDemand.complete(Unit)"))
        assertFalse(release.contains("releaseAdjacentBodyGate("))
        assertFalse(release.contains("completedAdjacentTargets["))
        assertFalse(release.contains("startInternal("))

        val physicalBoundary = functionBody(
            session,
            "fun onPhysicalBoundaryReached(",
            "fun onBlockedForwardPageRequested(",
        )
        assertTrue(physicalBoundary.contains("latestPhysicalForwardBoundaryPage.set(evidence)"))
        assertTrue(physicalBoundary.contains("releaseAdjacentPhysicalBoundaryDemand("))
        assertTrue(physicalBoundary.indexOf("latestPhysicalForwardBoundaryPage.set(evidence)") <
            physicalBoundary.indexOf("releaseAdjacentPhysicalBoundaryDemand("))
        val completionProof = physicalBoundary.indexOf(
            "isEpisodeFullyDrawableForAdjacent(evidence.manga)",
        )
        val completedRelease = physicalBoundary.indexOf(
            ".releaseAdjacentBodiesAfterPredecessorComplete(",
        )
        assertTrue(completionProof > physicalBoundary.indexOf("releaseAdjacentPhysicalBoundaryDemand("))
        assertTrue(completedRelease > completionProof)
        assertTrue(physicalBoundary.contains("selectedTarget != null"))
    }

    @Test
    fun adjacentControlWaitsForIdleUntilExactPhysicalDemandBecomesMandatory() {
        assertTrue(pacer.contains("fun awaitMotionIdleUntilRequired("))
        assertTrue(pacer.contains("while (!requiredNow() && isDisplayPriorityActive())"))
        assertTrue(coordinator.contains(
            "requiredNow = flight.adjacentPhysicalBoundaryDemand::isDone",
        ))
        assertTrue(coordinator.contains(
            "NtkPreparedAdjacentAuthorityMotionPolicy.mayParseDocumentDuringMotion(",
        ))
        assertFalse(coordinator.contains(
            "NtkReaderTransferPacer.awaitMotionIdle {\n" +
                "                        requireDiscoveryOwnership(flight, \"document_parse_motion_wait\")",
        ))
    }

    @Test
    fun exactTargetEntryRunwayCannotRemainBehindMotionAfterRealBoundary() {
        val targetDemand = functionBody(
            session,
            "private fun isPhysicalBoundaryDemandingAdjacentTarget(",
            "private fun appendRemainingAdjacentRunwayRefs(",
        )
        assertTrue(targetDemand.contains("forwardAdjacentCompletionTargetHistory[targetPath]"))
        assertTrue(targetDemand.contains("currentForwardAdjacentCompletionTargetClaim("))
        assertTrue(targetDemand.contains("latestPhysicalForwardBoundaryPage.get()"))
        assertTrue(targetDemand.contains("current === boundary"))

        val structureGate = functionBody(
            session,
            "private fun shouldDeferDirectWifiAdjacentStructurePublication(",
            "private fun isPhysicalBoundaryDemandingAdjacentTarget(",
        )
        assertTrue(structureGate.contains(
            "if (isPhysicalBoundaryDemandingAdjacentTarget(target)) return false",
        ))
        assertTrue(session.contains(
            ") && !isPhysicalBoundaryDemandingAdjacentTarget(\n" +
                "                    indexedPages.firstOrNull()?.second?.manga,",
        ))
    }

    @Test
    fun physicalTravelPastTemporaryDocumentEdgeSurvivesSuccessorPublication() {
        val drag = functionBody(
            surface,
            "private fun applyPhysicalDragPositionLocked(",
            "private fun applyDragOffsetLocked(",
        )
        val remember = functionBody(
            surface,
            "private fun rememberBlockedForwardIntentLocked(",
            "private fun seedPreContentForwardIntentLocked(",
        )
        val outstanding = functionBody(
            surface,
            "private fun blockedForwardRequestOutstandingLocked()",
            "private fun shouldLogForwardCapLocked()",
        )

        assertTrue(drag.contains("if (isAtInputEdgeLocked(direction))"))
        assertTrue(drag.contains("physicalRequestedOffset > scrollOffset"))
        assertTrue(drag.contains("allowBeyondCurrentDocument = true"))
        assertTrue(remember.contains("max(currentMaximum, target)"))
        assertTrue(outstanding.contains("nextBoundaryAppendInFlight || boundaryDispatchInFlight"))
    }

    @Test
    fun firstExactTailPresentationReleasesTheSameBoundedGateBeforeOverscroll() {
        val acknowledgement = functionBody(
            surface,
            "fun acknowledgeCleanPhysicalEpisodeTail(",
            "fun isPhysicalAdjacentEpisodeAdoptionAllowed(",
        )
        assertTrue(acknowledgement.contains(
            "newlyAcknowledgedDisplayPage = tail.displayPageIndex",
        ))
        assertTrue(acknowledgement.contains("proof.physicalGestureRevision <= 0L"))
        assertTrue(acknowledgement.contains(
            "cleanPhysicalEpisodeTailGestureRevisions[key] = proof.physicalGestureRevision",
        ))
        assertFalse(acknowledgement.contains(
            "cleanPhysicalEpisodeTailGestureRevisions[key] = physicalGestureRevision",
        ))
        assertTrue(acknowledgement.contains(
            "listener?.onCleanPhysicalEpisodeTailAcknowledged(newlyAcknowledgedDisplayPage)",
        ))
        assertTrue(
            acknowledgement.indexOf("val acknowledged = synchronized(stateLock)") <
                acknowledgement.indexOf(
                    "listener?.onCleanPhysicalEpisodeTailAcknowledged(newlyAcknowledgedDisplayPage)",
                ),
        )

        val activityCallback = functionBody(
            activity,
            "override fun onCleanPhysicalEpisodeTailAcknowledged(",
            "override fun onBoundaryReached(",
        )
        assertTrue(activityCallback.contains(
            "session?.onCleanPhysicalEpisodeTailAcknowledged(displayPageIndex)",
        ))
        assertFalse(activityCallback.contains("startBoundaryAppend("))
        assertFalse(activityCallback.contains("currentManga ="))
        assertFalse(activityCallback.contains("scrollTo"))
        assertFalse(activityCallback.contains("scrollBy"))

        val sessionCallback = functionBody(
            session,
            "fun onCleanPhysicalEpisodeTailAcknowledged(",
            "fun onPhysicalBoundaryReached(",
        )
        assertTrue(sessionCallback.contains(
            "recordPhysicalForwardBoundaryEvidence(displayPageIndex)",
        ))
    }

    @Test
    fun transitionCardCannotTakeToolbarOrProgressEpisodeOwnership() {
        val update = functionBody(
            activity,
            "private fun updateCurrentEpisode(",
            "private fun setPageText(",
        )
        val transitionBranch = update.substring(
            update.indexOf("if (info.transitionCard)"),
            update.indexOf("val previousManga = currentManga"),
        )

        assertTrue(transitionBranch.contains("transition_card_visible"))
        assertTrue(transitionBranch.contains("setPageText(\"회차 전환\")"))
        assertTrue(transitionBranch.contains("return"))
        assertFalse(transitionBranch.contains("currentManga ="))
        assertFalse(transitionBranch.contains("updateResultEpisode("))
        assertFalse(transitionBranch.contains("updateAdjacentButtons("))
        assertFalse(transitionBranch.contains("scheduleSaveReadingProgress("))
        assertTrue(
            update.indexOf("if (info.transitionCard)") < update.indexOf("currentManga = info.manga"),
        )
    }

    @Test
    fun adjacentBulkReleaseAndMetadataFollowTheActuallyOwnedEpisodeViewport() {
        val accepted = functionBody(
            activity,
            "private fun handleAcceptedStrictRollingCompletedDraw(",
            "private fun reconcilePausedPhysicalViewportAnchor(",
        )
        val sessionSignal = functionBody(
            session,
            "fun onExactNtkAdjacentActualFramePresented(",
            "private fun recordExactAdjacentCompositorForwardWarm(",
        )

        assertTrue(accepted.contains("viewportOwnsEpisode = identities.all"))
        assertTrue(accepted.contains("currentOwnedPath = NtkStripDigests.normalizeEpisodePath("))
        assertTrue(accepted.contains("identity.normalizedEpisodePath == currentOwnedPath"))
        assertTrue(sessionSignal.contains("viewportOwnsEpisode: Boolean = true"))
        assertTrue(sessionSignal.contains("if (viewportOwnsEpisode && claim.viewportActivated.compareAndSet(false, true))"))
        assertTrue(sessionSignal.contains("append_adjacent_strict_source_viewport_release_wait_clean"))
    }

    @Test
    fun latePredecessorCompletionCannotRollCurrentEpisodeBackwards() {
        val accepted = functionBody(
            activity,
            "private fun handleAcceptedStrictRollingCompletedDraw(",
            "private fun recordStrictCleanPhysicalSourceSnapshot(",
        )
        val ledger = functionBody(
            activity,
            "private fun recordStrictCleanPhysicalSourceSnapshot(",
            "private fun adoptPhysicallyPresentedAdjacentEpisode(",
        )
        val cadenceReset = functionBody(
            activity,
            "private fun resetStrictPhysicalPresentationCadence()",
            "override fun onVisibleCoverageChanged(",
        )

        assertTrue(activity.contains("private var strictTelemetryNewestStateFrameToken = 0L"))
        assertTrue(accepted.contains("proof.frameToken <= strictTelemetryNewestStateFrameToken"))
        assertTrue(accepted.contains("strictTelemetryNewestStateFrameToken = proof.frameToken"))
        assertTrue(
            accepted.indexOf("renderView.acknowledgeCleanPhysicalEpisodeTail(proof)") <
                accepted.indexOf("proof.frameToken <= strictTelemetryNewestStateFrameToken"),
        )
        assertTrue(
            accepted.indexOf("proof.frameToken <= strictTelemetryNewestStateFrameToken") <
                accepted.indexOf("adoptPhysicallyPresentedAdjacentEpisode("),
        )
        assertTrue(accepted.contains("NtkVisibleIdentityPolicy.stateEpisodeIdentity("))
        assertFalse(cadenceReset.contains("strictTelemetryNewestStateFrameToken"))
        assertTrue(ledger.contains("frameToken > latestPresentation.frameToken"))
        assertTrue(ledger.contains("frameToken <= prior.frameToken"))
    }

    @Test
    fun completedAuthoritativeHandoffCannotMasqueradeAsResidentPixels() {
        val flush = functionBody(
            activity,
            "private fun flushStrictAuthoritativeInstallsNow()",
            "private fun pendingStrictAuthoritativeMatches(",
        )
        val pending = functionBody(
            activity,
            "private fun pendingStrictAuthoritativeMatches(",
            "private fun clearStrictAuthoritativeInstallQueue()",
        )

        assertTrue(flush.contains("pendingStrictAuthoritativeInstalls[index] === install"))
        assertTrue(flush.contains("pendingStrictAuthoritativeInstalls.remove(index)"))
        assertTrue(flush.contains("acceptedStrictAuthoritativeIdentities.remove(index)"))
        assertTrue(
            flush.indexOf("renderView.installAuthoritativeTileBatch(commands)") <
                flush.indexOf("pendingStrictAuthoritativeInstalls.remove(index)"),
        )
        assertTrue(pending.contains("val pending = pendingStrictAuthoritativeInstalls[index]"))
        assertTrue(pending.contains("?: return@synchronized false"))
        assertTrue(pending.contains("accepted.sameAs(pending.identity)"))
    }

    @Test
    fun pressureRetiredLaunchTailUsesCompositorPixelWindowNotBodyLifetimeWindow() {
        val request = functionBody(
            session,
            "private fun requestStrictExactSourcePage(",
            "private fun releaseStrictExactDecodeClaim(",
        )
        val helper = functionBody(
            session,
            "private fun isInsideStrictExactRollingPixelWindow",
            "private fun handOffStrictExactAuthoritativeTiles",
        )

        assertTrue(helper.contains("compositorProvenStrictExactPixelWindowLocked"))
        assertFalse(helper.contains("index in retainedFirstPage..retainedLastPage"))
        assertTrue(request.contains("isInsideStrictExactRollingPixelWindow(index)"))
        assertTrue(request.contains("strictExactAuthoritativeHandoffPages.contains(index)"))
    }

    @Test
    fun unchangedVisibleLoadingWindowHasABoundedProductLivenessEdge() {
        val retry = functionBody(
            surface,
            "private fun scheduleVisibleLoadingHoldRetryLocked()",
            "private fun maybeReleasePrependedReadyHoldLocked(",
        )
        val activityCallback = functionBody(
            activity,
            "override fun onVisibleLoadingWindowRequested(",
            "override fun onCompletedDraw(",
        )
        val sessionCallback = functionBody(
            session,
            "fun requestVisibleLoadingWindowAsync(",
            "/**\n     * Keeps the nearest forward body requested by real input",
        )

        assertTrue(retry.contains("VISIBLE_LOADING_WINDOW_REDRIVE_MS"))
        assertTrue(retry.contains("forceDispatch = true"))
        assertTrue(retry.contains("visibleLoadingRedrive = true"))
        assertTrue(activityCallback.contains("requestVisibleLoadingWindowAsync("))
        assertTrue(sessionCallback.contains("publishReportedWindow("))
        assertTrue(sessionCallback.contains("offerWindowAsync("))
        assertFalse(sessionCallback.contains("publishedWindowIngressGate.reserve("))
    }

    @Test
    fun enteredEpisodeCompletionAuditsOrphanedPendingPublicationClaims() {
        val completion = functionBody(
            session,
            "private fun drainEnteredExactEpisodePixelCompletion(",
            "private fun remainingAdjacentRunwayRetryKey(",
        )
        val audit = functionBody(
            session,
            "private fun scheduleBlockedForwardPendingDeliveryAudit(",
            "private fun isStrictAdjacentPageInReportedPhysicalIntent(",
        )

        assertTrue(completion.contains("enteredCompletionPath = path"))
        assertTrue(audit.contains("takeQueuedVisibleDelivery(currentIndex)"))
        assertTrue(audit.contains("pendingDeliveryWidths.remove(currentIndex, pending)"))
        assertTrue(audit.contains("enteredExactEpisodePixelCompletionPaths.remove(enteredCompletionPath)"))
        assertTrue(audit.contains("requestEnteredExactEpisodePixelCompletion(enteredCompletionPath)"))
        assertTrue(
            audit.indexOf("takeQueuedVisibleDelivery(currentIndex)") <
                audit.indexOf("pendingDeliveryWidths.remove(currentIndex, pending)"),
        )
    }

    private fun functionBody(source: String, start: String, end: String): String {
        val startIndex = source.indexOf(start)
        val endIndex = source.indexOf(end, startIndex + start.length)
        check(startIndex >= 0 && endIndex > startIndex) { "Unable to isolate $start" }
        return source.substring(startIndex, endIndex)
    }
}
