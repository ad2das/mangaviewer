package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

class NtkClickOwnedExactBodyStreamTest {

    @Test
    fun closeRetiresTheOwningNetworkWaveExactlyOnce() {
        val closes = AtomicInteger()
        val stream = NtkClickOwnedExactBodyStream(
            mapOf(0 to CompletableFuture.completedFuture(null)),
            Closeable { closes.incrementAndGet() },
        )

        stream.close()
        stream.close()

        assertEquals(1, closes.get())
    }

    @Test
    fun productionHandoffDoesNotWaitForEveryBodyBeforePlanReservation() {
        val quarantine = readSource("NtkClickOwnedAnchorQuarantine.kt")
        val coordinator = readSource("NtkStrictEpisodeDiscoveryCoordinator.kt")
        val session = readSource("NtkStrictSourceSession.kt")
        val streamStart = quarantine.indexOf("fun streamIfExact(")
        val streamEnd = quarantine.indexOf("private fun adoptHeldBody(", streamStart)
        val streamBody = quarantine.substring(streamStart, streamEnd)

        assertTrue(streamBody.contains("future.handle"))
        assertFalse(streamBody.contains("future.get(remainingNanos"))
        assertTrue(streamBody.contains("val completeEpisodeStream ="))
        assertTrue(streamBody.contains("exactFutures.size == effectivePageCount.get()"))
        assertTrue(streamBody.contains("published.size == exactFutures.size"))
        assertTrue(streamBody.contains("if (completeEpisodeStream) close()"))
        assertTrue(quarantine.contains("probeLanes="))
        assertTrue(quarantine.contains("(0 until pageLimit).associateWith"))
        assertTrue(quarantine.contains("candidateFuture.thenCompose"))
        assertTrue(coordinator.contains("streamIfExact(exactManifestPreview)"))
        assertTrue(coordinator.contains("clickOwnedExactStream,"))
        assertTrue(session.contains("streamedExactBodyPending"))
        assertTrue(session.contains("acceptStreamedExactBodyCompletionActor"))
        assertTrue(session.contains("!it.streamedExactBodyPending"))
    }

    @Test
    fun finiteTailReleaseWaitsForTheActualCommittedFrameWithoutATimer() {
        val quarantine = readSource("NtkClickOwnedAnchorQuarantine.kt")
        val releaseStart = quarantine.indexOf("private fun releaseWave(reason: String)")
        val releaseEnd = quarantine.indexOf(
            "private fun completeNetworkRelease(",
            releaseStart,
        )
        val release = quarantine.substring(releaseStart, releaseEnd)

        assertTrue(release.contains("wave?.futures?.get(0)"))
        assertTrue(release.contains("anchor.whenComplete"))
        assertTrue(release.contains("firstActualFramePresented.whenComplete"))
        assertTrue(release.contains("\"first_actual_frame_presented\""))
        assertTrue(release.contains("completeNetworkRelease(reason, \"anchor_failed\")"))
        assertFalse(release.contains("sleep("))
        assertFalse(release.contains("delay("))
        assertFalse(release.contains("schedule("))
    }

    @Test
    fun uncommonViewportExtensionCannotQueueBehindUnadmittedTailWorkers() {
        val quarantine = readSource("NtkClickOwnedAnchorQuarantine.kt")
        val raceStart = quarantine.indexOf("private fun startClickPrimaryCandidateRace(")
        val raceEnd = quarantine.indexOf(
            "private fun startResolvedCandidate(",
            raceStart,
        )
        val race = quarantine.substring(raceStart, raceEnd)

        assertTrue(
            race.contains("primaryAdmissionFuture(pageIndex, alternativeCancellation)")
        )
        assertTrue(race.contains("fallbackBodyExecutor(pageIndex)"))
        val cancel = race.indexOf("primaryCancellation.cancel()")
        val alternativeAdmission = race.indexOf(
            "primaryAdmissionFuture(pageIndex, alternativeCancellation)"
        )
        val alternativeFetch = race.indexOf(
            "fetchOwnedCandidate(",
            alternativeAdmission,
        )
        assertTrue(cancel >= 0)
        assertTrue(cancel < alternativeAdmission)
        assertTrue(cancel < alternativeFetch)
        assertFalse(race.contains("awaitRollingNumericAdmission"))
        assertFalse(quarantine.contains("private fun awaitRollingNumericAdmission("))
        assertTrue(quarantine.contains("NtkClickOwnedManhwaWavePolicy.SPECULATION_DEBT_LIMIT"))
    }

    @Test
    fun resolvedNonJpgBodiesCanFillTheMeasuredPhysicalConnectionRing() {
        val quarantine = readSource("NtkClickOwnedAnchorQuarantine.kt")
        val fallbackExecutor = quarantine.substringAfter(
            "private val FALLBACK_BODY_EXECUTOR ="
        ).substringBefore("private val ANCHOR_FALLBACK_BODY_EXECUTOR")

        assertTrue(
            fallbackExecutor.contains(
                "NtkClickOwnedManhwaWavePolicy.ACTIVE_BODY_TRANSFERS"
            )
        )
        assertFalse(fallbackExecutor.contains("newFixedThreadPool(8)"))
    }

    @Test
    fun virtualGeneratedManhwaRouteFallsThroughToSignedAuthority() {
        val quarantine = readSource("NtkClickOwnedAnchorQuarantine.kt")
        val coordinator = readSource("NtkStrictEpisodeDiscoveryCoordinator.kt")
        val branch = coordinator.substringAfter(
            "val tokenBoundStream = anchor.streamIfExact(tokenBoundAuthority.manifest)"
        ).substringBefore("if (plan == null && tokenBoundAuthority == null)")

        assertTrue(quarantine.contains("val sampledAnchorCandidate: CompletableFuture<String?>?"))
        assertTrue(quarantine.contains("sampledAnchorCandidate = earlyJpgCandidates[0]"))
        assertTrue(branch.contains("tokenBoundStream.sampledAnchorCandidate"))
        assertTrue(branch.contains("tokenBoundStream.bodyFutures[0]"))
        assertTrue(branch.contains("tokenBoundStream.close()"))
        assertTrue(branch.contains("clickOwnedAnchor = null"))
        assertTrue(branch.contains("fallback=signed_image_api"))
        assertTrue(
            branch.indexOf("token_bound_numeric_anchor_validation") <
                branch.indexOf("reserveTokenBoundGeneratedDocumentPlan(")
        )
    }

    @Test
    fun everyExactPageBelongsToOneClickOwnedStreamWithBoundedFallback() {
        val quarantine = readSource("NtkClickOwnedAnchorQuarantine.kt")
        val cache = readSource("ReaderImageCache.kt")
        val waveStart = quarantine.indexOf("private fun startForwardWave()")
        val waveEnd = quarantine.indexOf("private fun discardHeldBody(", waveStart)
        val wave = quarantine.substring(waveStart, waveEnd)
        val raceStart = quarantine.indexOf("private fun startClickPrimaryCandidateRace(")
        val raceEnd = quarantine.indexOf("private fun startResolvedCandidate(", raceStart)
        val race = quarantine.substring(raceStart, raceEnd)

        assertTrue(quarantine.contains("rememberNtkGeneratedEpisodeExtensionHint("))
        assertTrue(quarantine.contains("val sourceRoutePreparationReady = preferredExtension.thenApply"))
        assertTrue(quarantine.contains("val bulkRouteReady = extensionRouteReady.thenCombine(firstActualFramePresented)"))
        assertFalse(quarantine.contains("val latePlaceholders ="))
        assertTrue(wave.contains("val initialPageLimit = pageLimit"))
        assertTrue(wave.contains("(0 until initialPageLimit).associateWith"))
        assertFalse(
            wave.contains(
                "minOf(pageLimit, NtkClickOwnedManhwaWavePolicy.SPECULATION_DEBT_LIMIT)"
            )
        )
        assertTrue(wave.contains("return Wave(preparedInitial)"))
        assertTrue(race.contains("val canonicalCandidate = candidateAsset("))
        assertTrue(race.contains("startCompletedHeadMissCandidate(pageIndex)"))
        assertTrue(cache.contains("val hintedTransportAsset = hintedNtkGeneratedImageUrl(asset) ?: asset"))
        assertTrue(cache.contains("stripeStrictManhwaTransportAsset("))
        assertTrue(cache.contains("requestFor(manga, transportAsset, foregroundPriority = true)"))
        val session = readSource("NtkStrictSourceSession.kt")
        assertTrue(session.contains("streamedExactBodies?.sourceRoutePreparationReady"))
        assertTrue(session.contains("bulkSourcePhysicalAdmissionReady"))
        assertTrue(session.contains("isBulkSourcePhysicalAdmissionReady(pageIndex)"))
        assertTrue(session.contains("streamedExactBodyFutures.isNotEmpty() -> 0"))
        assertTrue(session.contains("pageIndex == initialPageIndex"))
        assertFalse(session.contains("anchorSourceRoutePreparationReady"))
    }

    @Test
    fun transferAdmissionPrecedesPhysicalCallCreationAndHeaders() {
        val cache = readSource("ReaderImageCache.kt")
        val spoolStart = cache.indexOf("internal fun spoolQuarantinedEncodedOriginal(")
        val spoolEnd = cache.indexOf(
            "fun predecodeQuarantinedOriginalAsync(",
            spoolStart,
        )
        val spool = cache.substring(spoolStart, spoolEnd)

        val admission = spool.indexOf("bodyReadAdmission?.invoke()")
        val callCreation = spool.indexOf("newTrackedNtkEpisodeCall(")
        val execute = spool.indexOf("call.execute().use")
        assertTrue(admission >= 0)
        assertTrue(admission < callCreation)
        assertTrue(callCreation < execute)
        assertEquals(
            1,
            Regex(Regex.escape("bodyReadAdmission?.invoke()")).findAll(spool).count(),
        )
    }

    @Test
    fun privateFullBitmapDecodeIsBoundedToTheEntryRunway() {
        val quarantine = readSource("NtkClickOwnedAnchorQuarantine.kt")
        val attachStart = quarantine.indexOf("private fun attachPrivatePredecodes(")
        val attachEnd = quarantine.indexOf("private fun startClickPrimaryCandidateRace(", attachStart)
        val attach = quarantine.substring(attachStart, attachEnd)

        assertTrue(
            quarantine.contains(
                "private const val PRIVATE_PREDECODE_RUNWAY_PAGES = SPECULATIVE_CLICK_PAGES"
            )
        )
        assertTrue(attach.contains("pageIndex >= PRIVATE_PREDECODE_RUNWAY_PAGES"))
        assertTrue(attach.contains("retained[pageIndex] = held"))
        assertTrue(attach.contains("ReaderImageCache.predecodeQuarantinedOriginalAsync("))
    }

    private fun readSource(name: String): String {
        var cursor = File(checkNotNull(System.getProperty("user.dir"))).canonicalFile
        repeat(8) {
            val candidate = File(
                cursor,
                "app/src/main/java/ml/melun/mangaview/reader/$name",
            )
            if (candidate.isFile) return candidate.readText()
            cursor = cursor.parentFile ?: return@repeat
        }
        error("Repository source not found: $name")
    }
}
