package ml.melun.mangaview.reader

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkStrictAdjacentSuffixEventWaitTest {
    private val source = File(
        "src/main/java/ml/melun/mangaview/reader/ReaderSession.kt",
    ).readText()
    private val digest = NtkStripDigests.sha256Tokens("strict-suffix")
    private val assets = (0 until 16).map { index ->
        "https://images.example/p${index + 1}.png"
    }

    @Test
    fun exactManhwaAndWebtoonClaimsOwnDescriptorWaits() {
        assertTrue(ownsWait("/manhwa/2/episode"))
        assertTrue(ownsWait("/webtoon/2/episode"))
        assertTrue("readiness ownership is transport-neutral", ownsWait("/manhwa/2/episode"))
        assertFalse(ownsWait("/manhwa/2/episode", sourceEventChannelBound = false))
        assertTrue(
            NtkStrictRemainingAdjacentDescriptorWaitPolicy.requiresNativeExactBatch(
                descriptorWaitOwned = true,
                normalizedEpisodePath = "/manhwa/2/episode",
            ),
        )
        assertTrue(
            NtkStrictRemainingAdjacentDescriptorWaitPolicy.requiresNativeExactBatch(
                descriptorWaitOwned = true,
                normalizedEpisodePath = "/webtoon/2/episode",
            ),
        )
    }

    @Test
    fun deadForeignOrMutatedIdentityCannotOwnTheWait() {
        assertFalse(ownsWait("/comic/2/episode"))
        assertFalse(ownsWait("/manhwa/2/episode", claimLive = false))
        assertFalse(
            ownsWait(
                "/manhwa/2/episode",
                pageIdentities = pages("/manhwa/2/episode").mapIndexed { index, page ->
                    if (index == 0) page.copy(manifestDigest = NtkStripDigests.sha256Tokens("other"))
                    else page
                },
            ),
        )
        assertFalse(
            ownsWait(
                "/manhwa/2/episode",
                pageIdentities = pages("/manhwa/2/episode").mapIndexed { index, page ->
                    if (index == 0) page.copy(canonicalAsset = "https://foreign.example/p6.png")
                    else page
                },
            ),
        )
    }

    @Test
    fun liveStrictBranchIsEventOwnedWhileGenericFallbackKeepsItsRetry() {
        val source = File(
            "src/main/java/ml/melun/mangaview/reader/ReaderSession.kt",
        ).readText()
        val identityGate = block(
            "private fun requiresStrictExactRemainingAdjacentRunway(",
            source,
        )
        val waiting = block("waitingCandidates?.let { candidates ->", source)
        val strict = block("if (strictExactDescriptorWaitOwned) {", waiting)
        val generic = waiting.substring(waiting.indexOf(strict) + strict.length)
        val registrationRecheck = block(
            "private fun shouldWakeStrictExactDescriptorWaitAfterRegistration(",
            source,
        )
        val firstMissingIdentity = block(
            "private fun firstMissingStrictAdjacentDescriptorIdentity(",
            source,
        )
        val deferredRecheck = block(
            "private fun deferStrictRemainingAdjacentWakeAfterRegistration(",
            source,
        )
        val claim = block("private data class AdjacentStrictSourceClaim(", source)
        val waiter = block("private data class ParkedAdjacentRemainderAppend(", source)
        val claimBinding = block("private fun ensureAdjacentStrictSourceClaim(", source)
        val descriptorInstall = block(
            "private fun acceptAdjacentStrictBodyDescriptor(",
            source,
        )
        val append = block("private fun appendRemainingAdjacentRunwayRefs(", source)

        assertTrue(identityGate.contains("adjacentStrictSourceClaims[path]"))
        assertTrue(identityGate.contains("isAdjacentStrictSourceClaimLive(path, claim)"))
        assertTrue(identityGate.contains("NtkStrictRemainingAdjacentDescriptorWaitPolicy"))
        assertTrue(strict.contains("startRemainingAdjacentRunwayFileFetches("))
        assertTrue(strict.contains("waitingStrictRemainingAdjacentAppends[waitingPath] = waiting"))
        assertTrue(strict.contains("exactDescriptorWait = true"))
        assertFalse(strict.contains("scheduleRemainingAdjacentRunwayAppend("))
        assertTrue(generic.contains("scheduleRemainingAdjacentRunwayAppend("))
        assertTrue(registrationRecheck.contains("expectedFirstMissingPageIdentity ?: return true"))
        assertTrue(registrationRecheck.contains("runwayPageRefIdentity(it) == expectedIdentity"))
        assertTrue(registrationRecheck.contains("strictAdjacentBodyDescriptor(expected) != null"))
        assertFalse(registrationRecheck.contains("strictAdjacentBodyDescriptor(candidates.first())"))
        assertTrue(registrationRecheck.contains("!isAdjacentStrictSourceClaimLive(path, claim)"))
        assertTrue(firstMissingIdentity.contains("firstOrNull { strictAdjacentBodyDescriptor(it) == null }"))
        assertTrue(firstMissingIdentity.contains("?.let(::runwayPageRefIdentity)"))
        assertTrue(
            deferredRecheck.contains("waiting.expectedFirstMissingPageIdentity"),
        )
        assertTrue(claim.contains("sourceEventBinding: Closeable"))
        assertTrue(claim.contains("descriptorBindingToken: Any"))
        assertTrue(claim.contains("terminalFailureObserved: AtomicBoolean"))
        assertTrue(waiter.contains("identityToken: Any = Any()"))
        assertTrue(waiter.contains("expectedFirstMissingPageIdentity: String? = null"))
        assertTrue(claimBinding.contains("is SourceEvent.BodyPublished"))
        assertTrue(claimBinding.contains("acceptAdjacentStrictBodyDescriptor("))
        assertTrue(claimBinding.contains("is SourceEvent.TerminalFailure"))
        assertTrue(claimBinding.contains("invalidateAdjacentStrictDescriptorBinding("))
        assertTrue(claimBinding.contains("val claimPublished = synchronized(terminalFailureObserved)"))
        assertTrue(claimBinding.contains("adjacentStrictSourceClaims[path] = claim"))
        assertTrue(claimBinding.contains("append_adjacent_strict_source_claim_replay_rejected"))
        assertTrue(claimBinding.contains("releaseAdjacentStrictClaimAfterPredecessorComplete("))
        val releaseGate = block(
            "if (!releaseAdjacentStrictClaimAfterPredecessorComplete(path, claim))",
            claimBinding,
        )
        assertTrue(releaseGate.contains("return false"))
        assertFalse(releaseGate.contains("return true"))
        assertTrue(descriptorInstall.contains("adjacentStrictBodyDescriptors.putIfAbsent("))
        assertTrue(
            descriptorInstall.contains(
                "adjacentStrictDescriptorBindingTokens[path] !== descriptorBindingToken",
            ),
        )
        assertTrue(descriptorInstall.contains("if (previous != null)"))
        assertFalse(descriptorInstall.contains("check(previous.sourceKey"))
        assertTrue(descriptorInstall.contains("terminalFailureObserved.get()"))
        assertTrue(descriptorInstall.contains("descriptor_duplicate_mutated"))
        assertTrue(
            descriptorInstall.indexOf("if (previous != null)") <
                descriptorInstall.indexOf("wakeStrictRemainingAdjacentAppend(path)"),
        )
        assertTrue(append.contains("requiresNativeExactBatch("))
        assertTrue(append.contains("pendingRemainingAdjacentRunwayAppends.remove(activePath)"))
        assertTrue(append.contains("scheduledRemainingAdjacentRunwayRetries.cancelPath(activePath)"))
        assertTrue(append.contains("descriptor_wait_identity_rejected"))
        val rejectedExactOwner = block("if (!strictExactDescriptorWaitOwned)", append)
        assertTrue(rejectedExactOwner.contains("if (rejectedClaim != null)"))
        assertTrue(rejectedExactOwner.contains("descriptor_wait_dead_claim"))
        assertTrue(rejectedExactOwner.contains("recoverAdjacentStrictDescriptorFailure("))
        assertFalse(
            rejectedExactOwner.contains(
                "rejectedClaim != null && isAdjacentStrictSourceClaimLive",
            ),
        )
        assertTrue(append.contains("val strictExactDescriptorOnly = strictExactDescriptorWaitOwned"))
        assertTrue(append.contains("val directWifiNativeExactRendering ="))
        assertTrue(append.contains("if (nativeExactBatchRequired && exactRunwayPublication == null)"))
        val missingBatch = block("if (drawableBatch == null)", append)
        val strictMissingBatch = block("if (strictExactDescriptorWaitOwned)", missingBatch)
        assertTrue(strictMissingBatch.contains("recoverAdjacentStrictDescriptorFailure("))
        assertFalse(strictMissingBatch.contains("scheduleRemainingAdjacentRunwayAppend("))
        val watchdog = block("private fun armAdjacentStrictDescriptorWaitWatchdog(", source)
        assertTrue(watchdog.contains("claim.viewportActivated.get()"))
        assertTrue(watchdog.contains("NTK_STRICT_SUFFIX_EVENT_NO_PROGRESS_MS"))
        assertTrue(watchdog.contains("waitingStrictRemainingAdjacentAppends[path] !== waiter"))
        val watchdogFire = block(
            "private fun fireAdjacentStrictDescriptorWaitWatchdog(",
            source,
        )
        assertTrue(watchdogFire.contains("waiter.expectedFirstMissingPageIdentity"))
        val prepare = block("private fun prepareAdjacentRunwayDrawableBatch(", source)
        assertTrue(prepare.contains("failureSink: AtomicReference<Throwable?>? = null"))
        assertFalse(
            prepare.contains(
                "requireStrictDescriptor && !isDirectWifiStrictAdjacentTransportActive()",
            ),
        )
        val recovery = block("private fun recoverAdjacentStrictDescriptorFailure(", source)
        assertTrue(recovery.contains("ensureAdjacentStrictRecoveryManifestSubscription()"))
        assertFalse(recovery.contains("scheduleRemainingAdjacentRunwayAppend("))
        assertTrue(
            recovery.contains("firstMissingStrictAdjacentDescriptorIdentity(target, refs)"),
        )
        assertTrue(
            append.contains(".getOrNull(readyCount)"),
        )
        assertTrue(append.contains("?.let(::runwayPageRefIdentity)"))
        val sourceGap = block("if (candidateSourceGapFirst >= 0)", append)
        val exactSourceGap = block("if (strictRecoveryKnown)", sourceGap)
        assertTrue(exactSourceGap.contains("recoverAdjacentStrictDescriptorFailure("))
        assertFalse(exactSourceGap.contains("scheduleRemainingAdjacentRunwayAppend("))
        val awaitingRecovery = block(
            "private fun parkAdjacentStrictRecoveryAwaitingReplacement(",
            source,
        )
        assertTrue(awaitingRecovery.contains("adjacentStrictSourceClaims[path] == null"))
        assertTrue(
            awaitingRecovery.contains("waitingStrictRemainingAdjacentAppends[path] = waiter"),
        )
        assertTrue(awaitingRecovery.contains("ensureAdjacentStrictRecoveryManifestSubscription()"))
        assertTrue(awaitingRecovery.contains("armAdjacentStrictRecoveryBindDeadline(path, waiter)"))
        assertTrue(
            awaitingRecovery.indexOf("armAdjacentStrictRecoveryBindDeadline(path, waiter)") <
                awaitingRecovery.indexOf("currentAuthoritativeManifest(path)"),
        )
        assertFalse(awaitingRecovery.contains("scheduleRemainingAdjacentRunwayAppend("))
        assertTrue(
            append.indexOf("parkAdjacentStrictRecoveryAwaitingReplacement(") <
                append.indexOf("val readyCount ="),
        )
        assertTrue(
            append.indexOf("if (isAdjacentStrictRecoveryExhausted(target))") <
                append.indexOf("parkStrictOwnedOffscreenAdjacentRemainder("),
        )
        assertTrue(
            append.indexOf("val entryStrictClaim =") <
                append.indexOf("parkStrictOwnedOffscreenAdjacentRemainder("),
        )
        assertTrue(
            append.substringBefore("parkStrictOwnedOffscreenAdjacentRemainder(")
                .contains("recoverAdjacentStrictDescriptorFailure("),
        )
        assertTrue(
            append.indexOf("if (isAdjacentStrictRecoveryExhausted(target))") <
                append.indexOf("val readyCount ="),
        )
        val recoveryManifest = block(
            "private fun onAdjacentStrictRecoveryManifestInstalled(",
            source,
        )
        assertTrue(recoveryManifest.contains("armAdjacentStrictRecoveryBindDeadline(path, waiter)"))
        val recoveryDeadline = block(
            "private fun fireAdjacentStrictRecoveryBindDeadline(",
            source,
        )
        assertTrue(recoveryDeadline.contains("holdOrRecoverAdjacentStrictSource(waiter.target)"))
        assertTrue(recoveryDeadline.contains("observedRevision is deliberately diagnostic only"))
    }

    @Test
    fun watchdogRearmsOnlyForSourceProgressAndOtherwiseTerminatesTheExactWait() {
        fun decision(
            cancelled: Boolean = false,
            bindingCurrent: Boolean = true,
            waiterPresent: Boolean = true,
            claimLive: Boolean = true,
            descriptorReady: Boolean = false,
            sourceProgressed: Boolean = false,
            sourceCallsActive: Boolean = false,
        ) = NtkStrictRemainingAdjacentWatchdogPolicy.decide(
            cancelled = cancelled,
            bindingCurrent = bindingCurrent,
            waiterPresent = waiterPresent,
            claimLive = claimLive,
            descriptorReadyOrIdentityChanged = descriptorReady,
            sourceProgressed = sourceProgressed,
            sourceCallsActive = sourceCallsActive,
        )

        assertEquals(
            NtkStrictRemainingAdjacentWatchdogPolicy.Decision.INVALIDATE_AND_WAKE,
            decision(),
        )
        assertEquals(
            NtkStrictRemainingAdjacentWatchdogPolicy.Decision.REARM_AFTER_PROGRESS,
            decision(sourceProgressed = true),
        )
        assertEquals(
            NtkStrictRemainingAdjacentWatchdogPolicy.Decision.REARM_AFTER_PROGRESS,
            decision(sourceCallsActive = true),
        )
        assertEquals(
            NtkStrictRemainingAdjacentWatchdogPolicy.Decision.WAKE,
            decision(descriptorReady = true),
        )
        assertEquals(
            NtkStrictRemainingAdjacentWatchdogPolicy.Decision.WAKE,
            decision(claimLive = false),
        )
        assertEquals(
            NtkStrictRemainingAdjacentWatchdogPolicy.Decision.NONE,
            decision(bindingCurrent = false),
        )
    }

    @Test
    fun liveQuarantineOwnershipOverridesAStaleActorDebugSnapshot() {
        assertEquals(
            1,
            NtkStrictDescriptorWaitProgressPolicy.activeCalls(
                cachedQuarantineCalls = 0,
                liveQuarantineCalls = 1,
                exactCalls = 0,
                unresolvedStreamedExactBodies = 0,
            ),
        )
        assertEquals(
            3,
            NtkStrictDescriptorWaitProgressPolicy.activeCalls(
                cachedQuarantineCalls = 2,
                liveQuarantineCalls = null,
                exactCalls = 1,
                unresolvedStreamedExactBodies = 0,
            ),
        )
        assertEquals(
            4,
            NtkStrictDescriptorWaitProgressPolicy.activeCalls(
                cachedQuarantineCalls = 9,
                liveQuarantineCalls = 3,
                exactCalls = 1,
                unresolvedStreamedExactBodies = 0,
            ),
        )
        assertEquals(
            7,
            NtkStrictDescriptorWaitProgressPolicy.activeCalls(
                cachedQuarantineCalls = 0,
                liveQuarantineCalls = 0,
                exactCalls = 0,
                unresolvedStreamedExactBodies = 7,
            ),
        )
    }

    @Test
    fun watchdogProgressIncludesPostPromotionExactBodyOperations() {
        val progress = block(
            "private fun adjacentStrictDescriptorWaitProgress(",
            source,
        )
        assertTrue(progress.contains("NtkStrictSourceOwnershipRegistry.activeOperationCount("))
        assertTrue(progress.contains("NtkQuarantineSourceOwnershipRegistry.snapshot("))
        assertTrue(progress.contains("NtkStrictDescriptorWaitProgressPolicy.activeCalls("))
        assertTrue(progress.contains("liveQuarantine?.activeCalls"))
        assertTrue(progress.contains("claim.transport.unresolvedStreamedExactBodyCount()"))
    }

    @Test
    fun replacementBindDeadlineInheritsRecoveryProgressEpoch() {
        fun remaining(
            now: Long,
            requestAt: Long,
            deadlineAt: Long = 0L,
        ) = NtkStrictRecoveryBindDeadlinePolicy.remainingDelayMs(
            nowMs = now,
            lastRecoveryRequestAtMs = requestAt,
            lastDeadlineFireAtMs = deadlineAt,
        )

        assertEquals(800L, remaining(now = 1_200L, requestAt = 1_000L))
        assertEquals(500L, remaining(now = 1_500L, requestAt = 1_000L))
        assertEquals(1L, remaining(now = 2_500L, requestAt = 1_000L))
        assertEquals(900L, remaining(now = 2_100L, requestAt = 1_000L, deadlineAt = 2_000L))
        assertEquals(1_000L, remaining(now = 0L, requestAt = 0L))
    }

    private fun ownsWait(
        path: String,
        claimLive: Boolean = true,
        sourceEventChannelBound: Boolean = true,
        pageIdentities: List<NtkStrictRemainingAdjacentDescriptorWaitPolicy.PageIdentity> =
            pages(path),
    ): Boolean = NtkStrictRemainingAdjacentDescriptorWaitPolicy.shouldOwnWait(
        sourceEventChannelBound = sourceEventChannelBound,
        claimLive = claimLive,
        claimPath = path,
        claimManifestDigest = digest,
        claimRevision = 7L,
        authoritativePath = path,
        authoritativeManifestDigest = digest,
        authoritativeRevision = 7L,
        authoritativeAssets = assets,
        pages = pageIdentities,
    )

    private fun pages(path: String) = (5 until assets.size).map { sourceIndex ->
        NtkStrictRemainingAdjacentDescriptorWaitPolicy.PageIdentity(
            normalizedEpisodePath = path,
            sourceIndex = sourceIndex,
            manifestDigest = digest,
            manifestPageCount = assets.size,
            canonicalAsset = assets[sourceIndex],
        )
    }

    private fun block(signature: String, source: String): String {
        val start = source.indexOf(signature)
        require(start >= 0) { "Missing source signature: $signature" }
        val brace = source.indexOf('{', start)
        require(brace >= 0) { "Missing opening brace: $signature" }
        var depth = 0
        for (index in brace until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        error("Missing closing brace: $signature")
    }
}
