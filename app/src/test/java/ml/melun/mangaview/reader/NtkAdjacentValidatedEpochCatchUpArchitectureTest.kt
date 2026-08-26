package ml.melun.mangaview.reader

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkAdjacentValidatedEpochCatchUpArchitectureTest {
    private val sessionSource = File(
        "src/main/java/ml/melun/mangaview/reader/ReaderSession.kt",
    ).readText()

    @Test
    fun validatedEdgeLatchesEveryExactManifestOwnerBeforeChoosingTheViewportPair() {
        val redrive = functionBody(
            sessionSource,
            "fun redriveCurrentForwardAdjacentExactManifestAfterValidated(",
        )
        val claimLatch = redrive.indexOf(
            "for (claim in forwardAdjacentCompletionTargetClaims.values)",
        )
        val tombstoneLatch = redrive.indexOf(
            "forwardAdjacentValidatedRecoveryTombstones.toList()",
        )
        val viewportSelection = redrive.indexOf(
            "val live = forwardAdjacentCompletionTargetClaims.values",
        )
        assertTrue(claimLatch >= 0)
        assertTrue(tombstoneLatch > claimLatch)
        assertTrue(viewportSelection > tombstoneLatch)
        assertTrue(redrive.contains("NtkAdjacentValidatedEpochCatchUpPolicy.latch("))
        assertTrue(redrive.contains("claim.pendingValidatedRedriveEpoch"))
        assertTrue(redrive.contains("tombstone.pendingValidatedRedriveEpoch"))
        assertTrue(redrive.contains("ViewerTelemetry.isActiveViewer(viewerGeneration, viewerOwnerPath)"))
    }

    @Test
    fun futureManifestBoundaryConsumesItsLatchedEpochIntoOneNewRevision() {
        val claim = functionBody(
            sessionSource,
            "private fun claimForwardAdjacentCompletionTarget(",
        )
        val tombstone = claim.indexOf(
            "val catchUpEpoch = NtkAdjacentValidatedEpochCatchUpPolicy.consume(",
        )
        val newRevision = claim.indexOf(
            "forwardAdjacentCompletionTargetClaimSequence.getAndIncrement()",
            tombstone,
        )
        val consume = claim.indexOf("lastValidatedRedriveEpoch = catchUpEpoch", tombstone)
        val remove = claim.indexOf(
            "forwardAdjacentValidatedRecoveryTombstones.remove(",
            tombstone,
        )
        assertTrue(tombstone >= 0)
        assertTrue(newRevision > tombstone)
        assertTrue(consume > newRevision)
        assertTrue(remove > consume)
        assertTrue(claim.contains("pendingValidatedRedriveEpoch = 0L"))
        assertTrue(claim.contains("existing.discoveryLaunchAttempts.set(0)"))
        assertTrue(claim.contains("existing.pendingValidatedRedriveEpoch = 0L"))
        assertTrue(claim.contains("ViewerTelemetry.isActiveViewer("))
        val boundarySnapshot = claim.indexOf("currentAdjacentValidatedBoundaryEvidence()")
        val claimLock = claim.indexOf("synchronized(forwardAdjacentCompletionTargetClaimLock)")
        assertTrue(boundarySnapshot >= 0 && claimLock > boundarySnapshot)
        assertTrue(claim.contains("NtkAdjacentValidatedEpochCatchUpPolicy.exactBoundaryMatches("))

        val retire = functionBody(
            sessionSource,
            "fun retireStalledForwardAdjacentExactManifestClaim(",
        )
        assertTrue(
            retire.contains(
                "pendingValidatedRedriveEpoch =\n" +
                    "                                selected.pendingValidatedRedriveEpoch",
            ),
        )
    }

    @Test
    fun validatedEdgeLatchesEveryNetworkBodyOwnerAndBoundaryConsumesOnlyItsExactState() {
        val redrive = functionBody(
            sessionSource,
            "fun redriveCurrentForwardAdjacentExactRecoveryAfterValidated(",
        )
        val latch = redrive.indexOf("for (state in adjacentStrictRecoveryStates.values)")
        val viewportSelection = redrive.indexOf("val entries = adjacentStrictRecoveryStates.entries")
        assertTrue(redrive.contains("ViewerTelemetry.isActiveViewer(viewerGeneration, viewerOwnerPath)"))
        assertTrue(latch >= 0 && viewportSelection > latch)
        assertTrue(redrive.contains("(!state.exhausted || state.networkRearmableTerminal)"))
        assertTrue(redrive.contains("NtkAdjacentValidatedEpochCatchUpPolicy.latch("))
        assertTrue(redrive.contains("val anchor = boundaryEvidence.pageIndex"))
        assertFalse(redrive.contains("val anchor = currentViewportAnchor"))

        val hold = functionBody(
            sessionSource,
            "private fun holdOrRecoverAdjacentStrictSource(",
        )
        val exactState = hold.indexOf("val recovery = adjacentStrictRecoveryStates[path]")
        val catchUp = hold.indexOf("NtkAdjacentValidatedEpochCatchUpPolicy.consume(")
        val boundedRequest = hold.indexOf("recovery.rediscoveryRequestCount = 1", catchUp)
        val consumed = hold.indexOf("recovery.pendingValidatedRedriveEpoch = 0L", catchUp)
        val publish = hold.indexOf("listener.onAdjacentExactManifestRequired(target, rediscoveryPredecessor)")
        assertTrue(exactState >= 0 && catchUp > exactState)
        assertTrue(boundedRequest > catchUp && consumed > boundedRequest)
        assertTrue(publish > consumed)
        assertTrue(hold.contains("recovery.networkRearmableTerminal"))
        assertTrue(hold.contains("val activeCatchUp = recovery != null && !recovery.exhausted"))
        assertTrue(hold.contains("recovery.awaitingReplacement"))
        assertTrue(hold.contains("NtkAdjacentValidatedEpochCatchUpPolicy.shouldResetRequestGeneration("))
        assertTrue(hold.contains("replacementInFlight"))
        assertTrue(hold.contains("if (resetRequestGeneration)"))
        assertTrue(hold.contains("ViewerTelemetry.isActiveViewer("))
        val bodyBoundarySnapshot = hold.indexOf("currentAdjacentValidatedBoundaryEvidence()")
        val bodyLock = hold.indexOf("synchronized(adjacentStrictSourceClaimLock)")
        assertTrue(bodyBoundarySnapshot >= 0 && bodyLock > bodyBoundarySnapshot)
        assertTrue(hold.contains("NtkAdjacentValidatedEpochCatchUpPolicy.exactBoundaryMatches("))
        assertTrue(hold.contains("recovery.predecessorEpisodePath"))
    }

    @Test
    fun localPublicationAndDigestFailuresDiscardRatherThanConsumeNetworkEpoch() {
        val localFailure = functionBody(
            sessionSource,
            "private fun terminateAdjacentStrictLocalPublicationFailure(",
        )
        assertTrue(localFailure.contains("state.networkRearmableTerminal = false"))
        assertTrue(localFailure.contains("state.pendingValidatedRedriveEpoch = 0L"))

        val ensure = functionBody(
            sessionSource,
            "private fun ensureAdjacentStrictSourceClaim(",
        )
        assertTrue(ensure.contains("recovery.networkRearmableTerminal = false"))
        assertTrue(ensure.contains("recovery.pendingValidatedRedriveEpoch = 0L"))
        assertTrue(ensure.contains("it.awaitingReplacement = false"))
        assertTrue(ensure.contains("it.pendingValidatedRedriveEpoch = 0L"))

        val policy = sessionSource.substring(
            sessionSource.indexOf("internal object NtkAdjacentValidatedEpochCatchUpPolicy"),
            sessionSource.indexOf("internal object NtkStrictDecodeReleasePolicy"),
        )
        assertFalse(policy.contains("while ("))
        assertFalse(policy.contains("incrementAndGet"))
    }

    @Test
    fun physicalBoundaryEvidenceUsesReportedReadingIdentityNotThePrefetchAnchor() {
        val boundary = functionBody(
            sessionSource,
            "private fun currentAdjacentValidatedBoundaryEvidence(",
        )
        assertTrue(boundary.contains("latestReportedReadingPage.get()"))
        assertTrue(boundary.contains("pageIndexLocked(readingPage, readingPage.pageIndex)"))
        assertTrue(boundary.contains("if (readingPage == null)"))
        assertTrue(boundary.contains("currentStartPage().takeIf { it in pages.indices }"))
        assertTrue(boundary.contains("it.transitionTitle == null"))
        assertTrue(boundary.contains("pageIndex = boundaryIndex.takeIf { normalizedPath.isNotEmpty() }"))
        assertFalse(boundary.contains("currentViewportAnchor"))
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
}
