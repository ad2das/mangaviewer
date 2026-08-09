package ml.melun.mangaview.reader

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkAdjacentExactP0ArchitectureTest {
    private val reader = source("ReaderSession.kt")
    private val surface = source("ReaderSurfaceView.kt")
    private val strict = source("NtkStrictSourceSession.kt")
    private val transport = source("NtkStrictSourceTransport.kt")
    private val cacheTransport = source("NtkCacheSourceTransport.kt")
    private val registry = source("NtkSourceSpoolRegistry.kt")
    private val listenerGate = source("ReaderSessionListenerGate.kt")
    private val activity = File(
        "src/main/java/ml/melun/mangaview/activity/ReaderV2Activity.kt",
    ).readText()

    @Test
    fun p1ToP3OpenOnlyAfterRendererHeadAckAcrossEveryTransportBoundary() {
        val publish = block("private fun publishDirectWifiAdjacentExactP0Head(", reader)
        val receive = block("fun onAdjacentHeadPixelsInstalled(", strict)
        assertTrue(publish.contains("listener.onAdjacentExactP0HeadReady(publication)"))
        assertTrue(publish.contains("if (!accepted)"))
        assertTrue(publish.indexOf("listener.onAdjacentExactP0HeadReady(publication)") <
            publish.indexOf("signalDirectWifiAdjacentHeadPixelsInstalled(target, flight.owner)"))
        assertTrue(receive.contains("adjacentHeadPixelsInstalled = true"))
        assertTrue(receive.contains("refillLanesActor()"))
        assertTrue(transport.contains("fun onAdjacentHeadPixelsInstalled("))
        assertTrue(cacheTransport.contains("strictSession.onAdjacentHeadPixelsInstalled(episode)"))
        assertTrue(registry.contains("transport.onAdjacentHeadPixelsInstalled(episode)"))
    }

    @Test
    fun headPublicationIsFrameAtomicAndFailureRollsBackBothModels() {
        val callback = block("override fun onAdjacentExactP0HeadReady(", activity)
        val publish = block("private fun publishDirectWifiAdjacentExactP0Head(", reader)
        assertTrue(callback.contains("setFrameSchedulingSuppressed(true)"))
        assertTrue(callback.contains("onPagesAppended(publication.totalPageCount)"))
        assertTrue(callback.contains("setPageCard(publication.cardIndex"))
        assertTrue(callback.contains("installAdjacentExactP0Delta(publication.delta)"))
        assertTrue(callback.contains("setFrameSchedulingSuppressed(false)"))
        assertTrue(callback.contains(
            "requestDirectWifiAdjacentExactP0ContentCatchup(publication.delta.owner)"
        ))
        assertTrue(callback.contains("catchupEligible = result.accepted && result.firstInstall"))
        assertTrue(
            callback.indexOf("setFrameSchedulingSuppressed(false)") <
                callback.indexOf("requestDirectWifiAdjacentExactP0ContentCatchup")
        )
        val catchup = block("fun requestDirectWifiAdjacentExactP0ContentCatchup(", surface)
        assertTrue(catchup.contains("directWifiExpandedNativeTextureEpisodePaths"))
        assertTrue(catchup.contains("NtkAdjacentExactP0FrameCatchupPolicy.shouldPost"))
        assertTrue(catchup.contains("inFlightEpoch"))
        assertTrue(catchup.contains("inFlightToken"))
        assertTrue(catchup.contains("postAtFrontOfQueue(directAdjacentExactP0Catchup)"))
        val catchupRunnable = block("private val directAdjacentExactP0Catchup:", surface)
        assertTrue(catchupRunnable.contains("directWifiExpandedNativeTextureRunway"))
        assertTrue(catchupRunnable.contains(
            "pages.any { it.adjacentExactOwner == expectedOwner }"
        ))
        assertTrue(catchupRunnable.contains("inFlightEpoch == expectedEpoch"))
        assertTrue(catchupRunnable.contains("inFlightToken == expectedToken"))
        assertTrue(catchupRunnable.contains("rollingNativeAttachEpoch == expectedAttachEpoch"))
        assertTrue(catchupRunnable.contains("removeCallbacks(directFramePostRunnable)"))
        assertTrue(publish.contains("rollbackAdjacentRunwayStructure(cardIndex, initialRefs)"))
        assertTrue(publish.contains("recycleAdjacentExactP0Delta(flight.head)"))
    }

    @Test
    fun sparseHeadNeverClaimsWholePageReadinessAndScrollUsesContinuousRows() {
        val install = block("fun installAdjacentExactP0Delta(", surface)
        val complete = block("private fun pageHasCompleteDrawableContentLocked(", surface)
        val scroll = block("private fun forwardScrollLimitLocked(", surface)
        val readiness = block("fun hasAuthoritativeOriginalPage(", surface)
        assertTrue(install.contains("if (delta.complete != full)"))
        assertTrue(install.contains("contiguousAdjacentExactSourceBottom"))
        assertTrue(complete.contains("plan.sourceHeight"))
        assertTrue(scroll.contains("adjacentExactPrefixBottomLocked(index)"))
        assertTrue(readiness.contains("usableAuthoritativeOriginalTilePage"))
        assertFalse(readiness.contains("adjacentExactPrefixBottomLocked"))
    }

    @Test
    fun hostGpuDrawStateRequalifiesTheExactCombinedResumeViewport() {
        val drawState = block("private fun buildDrawStateLocked(", surface)
        val qualifier = block(
            "private fun qualifyDirectWifiForwardOnlyInitialResumeRevealLocked(",
            surface,
        )
        assertTrue(drawState.contains("emulatorNativeSurfaceRuntime"))
        assertTrue(drawState.contains("qualifyDirectWifiForwardOnlyInitialResumeRevealLocked()"))
        assertTrue(drawState.contains(
            "directWifiForwardOnlyInitialResumeRevealQualified &&"
        ))
        assertTrue(qualifier.contains("directWifiForwardOnlyInitialResumeViewportOpaqueLocked()"))
        assertFalse(qualifier.contains("emulatorNativeSurfaceRuntime"))
    }

    @Test
    fun terminalTailActualFlagIsImmutableAndDoesNotRelaxTheCombinedViewportProof() {
        val terminalTail = block(
            "private fun directWifiForwardOnlyTerminalTailActualLocked(",
            surface,
        )
        val drawState = block("private fun buildDrawStateLocked(", surface)
        val combined = block(
            "private fun directWifiForwardOnlyInitialResumeViewportOpaqueLocked(",
            surface,
        )
        assertTrue(terminalTail.contains("emulatorNativeSurfaceRuntime"))
        assertFalse(terminalTail.contains("target != pages.lastIndex"))
        assertTrue(terminalTail.contains("for (index in target until pages.size)"))
        assertTrue(terminalTail.contains("identity.normalizedEpisodePath != episodePath"))
        assertTrue(terminalTail.contains("identity.sourcePageIndex > previousSource + 1"))
        assertTrue(terminalTail.contains("previousSource == manifestPageCount - 1"))
        assertTrue(terminalTail.contains("terminalBottom < viewportBottom"))
        assertTrue(drawState.contains("forwardOnlyTerminalTailActual"))
        assertTrue(combined.contains("sawTransition"))
        assertTrue(combined.contains("sawAdjacentActual"))
        assertFalse(combined.contains("directWifiForwardOnlyTerminalTailActual"))
    }

    @Test
    fun adjacentP0CanCoexistWithTheCurrentEpisodesStripAuthority() {
        val install = block("fun installAdjacentExactP0Delta(", surface)
        assertFalse(install.contains("stripAuthorityToken != 0L"))
        assertTrue(install.contains("identity.normalizedEpisodePath == delta.owner.normalizedEpisodePath"))
        assertTrue(install.contains("identity.sourcePageIndex == 0"))
        assertTrue(install.contains("identity.canonicalAsset == delta.owner.canonicalAsset"))
        assertTrue(install.contains("identity.manifestDigest == delta.owner.manifestDigest"))
        assertTrue(install.contains("page.adjacentExactOwner != owner"))
        assertTrue(install.contains("page.stripAuthority != 0L"))
        assertTrue(install.contains("page.stripSlots.isNotEmpty()"))
        assertTrue(install.contains("page.cardBitmap != null"))
    }

    @Test
    fun exactSparseRouteIsDirectWifiAdjacentWebtoonP0Only() {
        val candidate = block("private fun isDirectWifiAdjacentExactP0HeadCandidate(", reader)
        val prepare = block("private fun prepareDirectWifiAdjacentExactP0Head(", reader)
        val publish = block("private fun appendResolvedEpisodeInitialRunway(", reader)
        val construction = block("internal class NtkStrictSourceSession(", strict)
        assertTrue(candidate.contains("isDirectWifiStrictAdjacentTransportActive()"))
        assertTrue(candidate.contains("path.startsWith(\"/webtoon/\")"))
        assertTrue(candidate.contains("p0.sourceIndex == 0"))
        assertTrue(prepare.contains("decodeAdjacentExactP0Slots(lease, plan, headSlots, parallel = true)"))
        assertTrue(publish.contains("adjacentExactP0PreparePaths.add(exactPath)"))
        assertTrue(publish.contains("append_adjacent_exact_p0_prepare_join"))
        assertTrue(publish.contains("adjacentExactP0PreparePaths.remove(exactPath)"))
        assertTrue(publish.contains("val publishedFallbackReady = !descriptorReady"))
        assertTrue(construction.contains("adjacentPrefetch && directWifiTransport"))
        assertTrue(construction.contains("!cellularResilientTransport"))
        assertTrue(construction.contains("planBinding.episodePath.startsWith(\"/webtoon/\")"))
    }

    @Test
    fun exactP0ResidentBodyWakesTheInitialAppendWithoutChangingOtherTransports() {
        val bind = block("private fun ensureAdjacentStrictSourceClaim(", reader)
        val wake = block("private fun wakeInitialAdjacentExactP0Append(", reader)
        val schedule = block("private fun scheduleInitialAdjacentRunwayAppendRetry(", reader)
        val scope = block("private fun initialAdjacentExactP0WakePath(", reader)

        assertTrue(reader.contains("waitingInitialAdjacentExactP0Appends"))
        assertTrue(bind.contains("seal.digestSha256"))
        assertTrue(bind.contains("wakeInitialAdjacentExactP0Append("))
        assertTrue(
            bind.indexOf("adjacentStrictBodyDescriptors.putIfAbsent") <
                bind.indexOf("wakeInitialAdjacentExactP0Append("),
        )
        assertTrue(wake.contains("sourceIndex != 0"))
        assertTrue(wake.contains("path.startsWith(\"/webtoon/\")"))
        assertTrue(wake.contains("!isDirectWifiStrictAdjacentTransportActive()"))
        assertTrue(wake.contains("adjacentExactP0WakeKey(path, manifestDigest)"))
        assertTrue(wake.contains("waitingInitialAdjacentExactP0Appends.wake"))
        assertTrue(scope.contains("Manga.sameEpisodeIdentity(manga, target)"))
        assertTrue(scope.contains("isDirectWifiAdjacentExactP0HeadCandidate(target, runwayRefs)"))
        assertTrue(schedule.contains("val strictAdjacentAuthority = exactViewerApiAdjacentAuthority(target)"))
        assertTrue(schedule.contains("strictAdjacentAuthority?.takeIf"))
        assertTrue(schedule.contains("exactP0WakePath.isNotEmpty()"))
        assertTrue(schedule.contains("authority.seal.digestSha256"))
        assertTrue(schedule.contains("exactWakeRegistry.register"))
        assertTrue(schedule.contains("adjacentStrictBodyDescriptors.containsKey(descriptorKey)"))
        assertTrue(schedule.contains("initialAdjacentRunwayAppendRetryDelayMs(target)"))
        assertTrue(schedule.contains("initialAdjacentWebtoonP0EventWakeLane.get() == true"))
        assertTrue(schedule.contains("if (directWebtoonEventWake || directManhwaEventWake)"))
        assertTrue(reader.contains("waitingInitialAdjacentExactP0Appends.cancelAll()"))
        assertTrue(reader.contains("initialAdjacentWebtoonP0EventWakeLane.set(true)"))
        assertTrue(reader.contains("initialAdjacentWebtoonP0EventWakeLane.remove()"))
    }

    @Test
    fun completeResidentManhwaRunwayWakesTheSameRaceSafeInitialAppendRegistry() {
        val bind = block("private fun ensureAdjacentStrictSourceClaim(", reader)
        val wake = block("private fun wakeInitialAdjacentManhwaRunwayAppend(", reader)
        val scope = block("private fun initialAdjacentExactP0WakePath(", reader)
        val schedule = block("private fun scheduleInitialAdjacentRunwayAppendRetry(", reader)

        assertTrue(bind.contains("wakeInitialAdjacentManhwaRunwayAppend("))
        assertTrue(wake.contains("path.startsWith(\"/manhwa/\")"))
        assertTrue(wake.contains("isDirectWifiStrictAdjacentTransportActive()"))
        assertTrue(wake.contains("minOf(initialAdjacentRunwayPageLimit(path), pageCount)"))
        assertTrue(wake.contains("(0 until requiredRunwayPages).all"))
        assertTrue(wake.contains("adjacentStrictBodyDescriptors.containsKey("))
        assertTrue(wake.contains("waitingInitialAdjacentManhwaRunwayAppends.wake("))
        assertTrue(wake.contains("initialAdjacentManhwaRunwayEventWakeKeys.add(wakeKey)"))
        assertTrue(scope.contains("path.startsWith(\"/manhwa/\")"))
        assertTrue(scope.contains("refs.firstOrNull { it.transitionTitle == null }?.sourceIndex == 0"))
        assertTrue(schedule.contains("wakeInitialAdjacentManhwaRunwayAppend("))
        assertTrue(schedule.contains("waitingInitialAdjacentManhwaRunwayAppends"))
        assertTrue(schedule.contains(
            "initialAdjacentManhwaRunwayEventWakeKeys.remove(exactP0WakeKey)"
        ))
        assertTrue(schedule.contains("if (directWebtoonEventWake || directManhwaEventWake)"))
        assertTrue(schedule.contains("appendWork.run()"))
        assertTrue(reader.contains(
            "postImmediate = { runnable -> initialAdjacentRunwayNetwork.execute(runnable) }"
        ))
        assertTrue(reader.contains("waitingInitialAdjacentManhwaRunwayAppends.cancelAll()"))
    }

    @Test
    fun exactP0WakeDuringTimeoutInstallationRunsOnceAndLeavesNoCallback() {
        val immediate = ArrayDeque<Runnable>()
        val delayed = ArrayDeque<Runnable>()
        lateinit var wakeRegistry: NtkAdjacentExactP0WakeRegistry
        var runs = 0
        wakeRegistry = NtkAdjacentExactP0WakeRegistry(
            postImmediate = immediate::addLast,
            postDelayed = { runnable, _ ->
                delayed.addLast(runnable)
                assertTrue(wakeRegistry.wake("/webtoon/next|digest-a"))
            },
            removeCallbacks = delayed::remove,
        )

        assertTrue(
            wakeRegistry.register(
                "/webtoon/next|digest-a",
                Runnable { runs++ },
                32L,
            ),
        )
        while (immediate.isNotEmpty()) immediate.removeFirst().run()
        while (delayed.isNotEmpty()) delayed.removeFirst().run()

        assertEquals(1, runs)
        assertEquals(0, wakeRegistry.sizeForTests())
    }

    @Test
    fun exactP0TimeoutAndLateEventStillRunExactlyOnce() {
        val immediate = ArrayDeque<Runnable>()
        val delayed = ArrayDeque<Runnable>()
        var runs = 0
        val wakeRegistry = NtkAdjacentExactP0WakeRegistry(
            postImmediate = immediate::addLast,
            postDelayed = { runnable, _ -> delayed.addLast(runnable) },
            removeCallbacks = delayed::remove,
        )

        assertTrue(
            wakeRegistry.register(
                "/webtoon/next|digest-a",
                Runnable { runs++ },
                32L,
            ),
        )
        delayed.removeFirst().run()
        assertFalse(wakeRegistry.wake("/webtoon/next|digest-a"))
        while (immediate.isNotEmpty()) immediate.removeFirst().run()

        assertEquals(1, runs)
        assertEquals(0, wakeRegistry.sizeForTests())
    }

    @Test
    fun exactP0CancelDuringTimeoutInstallationLeavesNothingRunnable() {
        val immediate = ArrayDeque<Runnable>()
        val delayed = ArrayDeque<Runnable>()
        lateinit var wakeRegistry: NtkAdjacentExactP0WakeRegistry
        var runs = 0
        wakeRegistry = NtkAdjacentExactP0WakeRegistry(
            postImmediate = immediate::addLast,
            postDelayed = { runnable, _ ->
                delayed.addLast(runnable)
                wakeRegistry.cancelAll()
            },
            removeCallbacks = delayed::remove,
        )

        assertTrue(
            wakeRegistry.register(
                "/webtoon/next|digest-a",
                Runnable { runs++ },
                32L,
            ),
        )
        while (delayed.isNotEmpty()) delayed.removeFirst().run()

        assertEquals(0, runs)
        assertEquals(0, wakeRegistry.sizeForTests())
        assertFalse(wakeRegistry.wake("/webtoon/next|digest-a"))
    }

    @Test
    fun exactP0WakeCannotCrossManifestDigest() {
        val immediate = ArrayDeque<Runnable>()
        val delayed = ArrayDeque<Runnable>()
        var runs = 0
        val wakeRegistry = NtkAdjacentExactP0WakeRegistry(
            postImmediate = immediate::addLast,
            postDelayed = { runnable, _ -> delayed.addLast(runnable) },
            removeCallbacks = delayed::remove,
        )

        assertTrue(
            wakeRegistry.register(
                "/webtoon/next|digest-new",
                Runnable { runs++ },
                32L,
            ),
        )
        assertFalse(wakeRegistry.wake("/webtoon/next|digest-old"))
        assertTrue(wakeRegistry.wake("/webtoon/next|digest-new"))
        while (immediate.isNotEmpty()) immediate.removeFirst().run()

        assertEquals(1, runs)
        assertEquals(0, wakeRegistry.sizeForTests())
    }

    @Test
    fun residentP0PreparationHandsOffToSparsePublisherAsSuccess() {
        val prepare = block("private fun prepareInitialTailAdjacentRunway(", reader)
        val sparseHandoff = prepare.indexOf(
            "append_adjacent_exact_p0_prefetch_deferred_to_sparse_head",
        )
        assertTrue(sparseHandoff >= 0)
        assertTrue(prepare.indexOf("strictBodiesReady", 0) in 0 until sparseHandoff)
        assertTrue(prepare.indexOf("isDirectWifiStrictAdjacentTransportActive()", 0) in 0 until sparseHandoff)
        assertTrue(prepare.indexOf("path).startsWith(\"/webtoon/\")", 0) in 0 until sparseHandoff)
        assertTrue(prepare.indexOf("refs.singleOrNull()?.sourceIndex == 0", 0) in 0 until sparseHandoff)

        val success = prepare.indexOf("return true", sparseHandoff)
        val failure = prepare.indexOf("return false", sparseHandoff)
        assertTrue(success > sparseHandoff)
        assertTrue(failure < 0 || success < failure)

        val consume = block("private fun prefetchResolvedMetadataAdjacent(", reader)
        val preparation = consume.indexOf("prepareInitialTailAdjacentRunway(candidate, publishUrls)")
        val publication = consume.indexOf("appendResolvedEpisode(candidate, publishUrls, direction)")
        assertTrue(preparation >= 0)
        assertTrue(publication > preparation)
    }

    @Test
    fun generationGateForwardsBothSparseP0Callbacks() {
        val head = block("override fun onAdjacentExactP0HeadReady(", listenerGate)
        val tail = block("override fun onAdjacentExactP0TailReady(", listenerGate)
        assertTrue(head.contains("active() && downstream.onAdjacentExactP0HeadReady(publication)"))
        assertTrue(tail.contains("active() && downstream.onAdjacentExactP0TailReady(delta)"))
    }

    @Test
    fun entireForwardTailUsesProofAwareAtomicBatchesInsteadOfLegacyTileSetter() {
        val publish = block("private fun appendRemainingAdjacentRunwayRefs(", reader)
        val build = block("private fun directWifiAdjacentExactRunwayPublication(", reader)
        val commit = block("private fun commitAdjacentExactRunwayDrawableBatch(", reader)
        val gate = block("override fun onAdjacentExactRunwayBatchReady(", listenerGate)
        val install = block("fun installAdjacentExactRunwayBatch(", surface)
        val callback = block("override fun onAdjacentExactRunwayBatchReady(", activity)
        assertTrue(publish.contains("listener.onAdjacentExactRunwayBatchReady("))
        assertTrue(publish.contains("commitAdjacentExactRunwayDrawableBatch(drawableBatch)"))
        assertTrue(build.contains("result.originalProof ?: return null"))
        assertTrue(build.contains("page.sourceIndex < 1"))
        assertFalse(build.contains("page.sourceIndex !in 1..3"))
        assertTrue(gate.contains("downstream.onAdjacentExactRunwayBatchReady(publication)"))
        assertTrue(gate.contains("replaceWithCurrentAuthoritative"))
        assertTrue(callback.contains("installAdjacentExactRunwayBatch(publication)"))
        assertTrue(callback.contains("rollbackAdjacentExactAppendedTail("))
        assertTrue(install.contains("identity.normalizedEpisodePath == command.normalizedEpisodePath"))
        assertTrue(install.contains("page.stripAuthority == 0L"))
        assertTrue(install.contains("usableAuthoritativeOriginalTilePage("))
        assertFalse(install.contains("stripAuthorityToken != 0L"))
        assertFalse(commit.contains("listener.onPageTilesReady"))
    }

    private fun source(name: String): String = File(
        "src/main/java/ml/melun/mangaview/reader/$name",
    ).readText()

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
