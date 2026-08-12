package ml.melun.mangaview.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class NtkDirectWifiStrictLegacySpeculationPolicyTest {
    @Test
    fun skipsOnlyOwnedOrReservedDirectWifiWebtoonWork() {
        assertTrue(
            NtkDirectWifiStrictLegacySpeculationPolicy.shouldSkip(
                path = "/webtoon/12868/1348822",
                wifiTransportActive = true,
                cellularResilientTransportActive = false,
                strictSourceOwned = true,
            )
        )
        assertFalse(
            NtkDirectWifiStrictLegacySpeculationPolicy.shouldSkip(
                path = "/webtoon/12868/1348822",
                wifiTransportActive = true,
                cellularResilientTransportActive = false,
                strictSourceOwned = false,
            )
        )
    }

    @Test
    fun preservesMobileSniAndNonWebtoonBehavior() {
        assertFalse(
            NtkDirectWifiStrictLegacySpeculationPolicy.shouldSkip(
                path = "/webtoon/12868/1348822",
                wifiTransportActive = true,
                cellularResilientTransportActive = true,
                strictSourceOwned = true,
            )
        )
        assertFalse(
            NtkDirectWifiStrictLegacySpeculationPolicy.shouldSkip(
                path = "/webtoon/12868/1348822",
                wifiTransportActive = false,
                cellularResilientTransportActive = false,
                strictSourceOwned = true,
            )
        )
        assertFalse(
            NtkDirectWifiStrictLegacySpeculationPolicy.shouldSkip(
                path = "/manhwa/12868/1348822",
                wifiTransportActive = true,
                cellularResilientTransportActive = false,
                strictSourceOwned = true,
            )
        )
    }

    @Test
    fun coordinatorPermitMustMatchTheExactDisplaySlotAndImmutableId() {
        val permit = NtkImagePermit(
            sessionEpoch = 7L,
            pageIndex = 213,
            lane = NtkImageLane.FOLLOWING_VISIBLE,
            phaseAtGrant = NtkBootPhase.FIRST_DRAWABLE_COMMITTED,
            permitId = "7:213:FOLLOWING_VISIBLE",
        )

        assertTrue(
            NtkPermitlessInitialGeneratedForegroundPolicy.isCoordinatorAuthorized(
                permit,
                requestedPageIndex = 213,
            )
        )
        assertFalse(
            NtkPermitlessInitialGeneratedForegroundPolicy.isCoordinatorAuthorized(
                permit,
                requestedPageIndex = 214,
            )
        )
        assertFalse(
            NtkPermitlessInitialGeneratedForegroundPolicy.isCoordinatorAuthorized(
                permit.copy(permitId = "forged"),
                requestedPageIndex = 213,
            )
        )
        assertFalse(
            NtkPermitlessInitialGeneratedForegroundPolicy.isCoordinatorAuthorized(
                null,
                requestedPageIndex = 213,
            )
        )
    }

    @Test
    fun coordinatorPermitSurvivesAsyncAndCacheLayersWithoutOpeningPermitlessCalls() {
        val readerCache = readSource("reader", "ReaderImageCache.kt")
        val cancellationStart = readerCache.indexOf("class Cancellation private constructor(")
        val cancellationEnd = readerCache.indexOf("fun interface ByteFlightSubscriber", cancellationStart)
        val cancellation = readerCache.substring(cancellationStart, cancellationEnd)
        assertTrue(cancellation.contains("forCoordinatorGeneratedForegroundPermit("))
        assertTrue(cancellation.contains("coordinatorGeneratedForegroundPermit"))
        assertTrue(cancellation.contains("hasCoordinatorGeneratedForegroundPermit()"))
        assertTrue(cancellation.contains("childPreservingLegacySpeculation"))

        val streamStart = readerCache.indexOf("fun startForegroundStreamFetch(")
        val streamEnd = readerCache.indexOf("fun decodeForegroundBitmap(", streamStart)
        val stream = readerCache.substring(streamStart, streamEnd)
        assertTrue(
            stream.indexOf("forCoordinatorGeneratedForegroundPermit(permit, pageIndex)") <
                stream.indexOf(".forLegacySpeculation(")
        )

        val requestStart = readerCache.indexOf("private fun requestWithNtkGeneratedFallback(")
        val requestEnd = readerCache.indexOf("private fun shouldUseInitialGeneratedRangeFirst(", requestStart)
        val request = readerCache.substring(requestStart, requestEnd)
        assertTrue(request.contains("cancellation?.hasCoordinatorGeneratedForegroundPermit() == true"))
        assertTrue(request.contains("permitlessSuppressed && !coordinatorAuthorized"))

        val foregroundStart = readerCache.indexOf("private fun requestForForegroundMode(")
        val foregroundEnd = readerCache.indexOf("private fun requestDirectInitialGeneratedForeground(", foregroundStart)
        val foreground = readerCache.substring(foregroundStart, foregroundEnd)
        assertTrue(foreground.contains("cancellation?.hasCoordinatorGeneratedForegroundPermit() != true"))

        val session = readSource("reader", "ReaderSession.kt")
        val leaseStart = session.indexOf("private fun leaseImageFile(")
        val leaseEnd = session.indexOf("private fun prefetchImageFile(", leaseStart)
        val lease = session.substring(leaseStart, leaseEnd)
        assertTrue(lease.contains("foregroundPermit: NtkImagePermit? = null"))
        assertTrue(lease.contains("forCoordinatorGeneratedForegroundPermit(foregroundPermit, index)"))
        assertTrue(lease.indexOf("forCoordinatorGeneratedForegroundPermit") < lease.indexOf("ReaderImageCache.leaseFile("))

        val boundedStart = session.indexOf("private fun startBoundedForegroundStreamFetch(")
        val boundedEnd = session.indexOf("private fun startBoundedVisiblePreviewFetch(", boundedStart)
        val bounded = session.substring(boundedStart, boundedEnd)
        assertTrue(bounded.contains("forCoordinatorGeneratedForegroundPermit(permit, pageIndex)"))
        assertTrue(
            bounded.indexOf("forCoordinatorGeneratedForegroundPermit(permit, pageIndex)") <
                bounded.indexOf("ReaderImageCache.getOrFetchFileForeground(")
        )
        assertTrue(bounded.contains("producerCancellation,"))
    }

    @Test
    fun mangaRejectsLegacyWorkBeforeItCreatesProbeThreadsOrStreams() {
        val manga = readSource(
            "mangaview", "Manga.java"
        )
        val initialStart = manga.indexOf(
            "private void startSpeculativeNtkGeneratedInitialStreams(CustomHttpClient client, String segment,",
            manga.indexOf("private void startSpeculativeNtkGeneratedInitialStreams(CustomHttpClient client, String segment,") + 1,
        )
        val initialEnd = manga.indexOf(
            "private boolean shouldSkipUnverifiedCanonicalWebtoonPathWorkSpeculation(",
            initialStart,
        )
        val initial = manga.substring(initialStart, initialEnd)
        assertTrue(initial.contains("shouldSkipDirectWifiStrictLegacySpeculation(client, path, \"initial_stream\")"))
        assertTrue(
            initial.indexOf("shouldSkipDirectWifiStrictLegacySpeculation") <
                initial.indexOf("startNtkInitialForegroundStream(")
        )

        val slugStart = manga.indexOf("private String reachableNtkSlugWebtoonImageExtension(")
        val slugEnd = manga.indexOf("private boolean shouldSkipDirectWifiStrictLegacySpeculation(", slugStart)
        val slug = manga.substring(slugStart, slugEnd)
        assertTrue(slug.contains("\"slug_extension\""))
        assertTrue(slug.indexOf("\"slug_extension\"") < slug.indexOf("new Thread("))

        val pageFetchStart = manga.indexOf("private AsyncNtkPageFetch startAsyncNtkPageFetch(")
        val pageFetchEnd = manga.indexOf("private void cancelAsyncNtkPageFetch(", pageFetchStart)
        val pageFetch = manga.substring(pageFetchStart, pageFetchEnd)
        assertTrue(pageFetch.contains("\"page_fetch\""))
        assertTrue(
            pageFetch.indexOf("\"page_fetch\"") <
                pageFetch.indexOf("client.mgetNtkViewerPayloadPage(")
        )

        val fastStart = manga.indexOf("private boolean isNtkGeneratedImageReachableFast(")
        val fastEnd = manga.indexOf("private boolean isNtkGeneratedImageReachableRange(", fastStart)
        val fast = manga.substring(fastStart, fastEnd)
        assertTrue(fast.contains("\"reachability_fast\""))
        assertTrue(fast.contains("\"reachability_range_delayed\""))
        assertTrue(fast.contains("\"reachability_range_after_header\""))

        val legacyImageApiStart = manga.indexOf(
            "private static boolean shouldSkipCurrentNtkImageApiBecauseGeneratedInitialReady("
        )
        val legacyImageApiEnd = manga.indexOf(
            "private static boolean shouldSkipNtkPartialDirectImageHandoff(",
            legacyImageApiStart,
        )
        val legacyImageApi = manga.substring(legacyImageApiStart, legacyImageApiEnd)
        assertTrue(legacyImageApi.contains("ntk_viewer_api_skip_owned_direct_wifi_strict_source"))
        assertTrue(
            legacyImageApi.indexOf("shouldSkipDirectWifiStrictLegacySpeculation") <
                legacyImageApi.indexOf("hasReachableRecentEarlyNtkImageUrls(")
        )
    }

    @Test
    fun headerProbeRechecksOwnershipBeforeQuicFallback() {
        val client = readSource("mangaview", "CustomHttpClient.java")
        val overloadStart = client.indexOf(
            "public int ntkImageHeaderReachability(String url, Map<String, String> headers, long timeoutMs,"
        )
        val overloadEnd = client.indexOf("private int ntkImageHeaderReachabilityOkHttp(", overloadStart)
        val overload = client.substring(overloadStart, overloadEnd)
        assertTrue(
            Regex("shouldSkipOwnedDirectWifiStrictLegacyImageProbe\\(strictEpisodePath\\)")
                .findAll(overload)
                .count() >= 2
        )
        assertTrue(
            overload.lastIndexOf("shouldSkipOwnedDirectWifiStrictLegacyImageProbe") <
                overload.indexOf("getOrCreateNtkQuicEngine(")
        )
        assertTrue(
            Regex(
                "if\\(shouldSkipOwnedDirectWifiStrictLegacyImageProbe\\(strictEpisodePath\\)\\)\\s*" +
                    "return NTK_IMAGE_HEADER_REACHABILITY_SUPERSEDED;"
            )
                .findAll(overload)
                .count() >= 2
        )
    }

    @Test
    fun strictClickFencesWrongEpisodeGuessesWithoutRacingQuicEngineShutdown() {
        val client = readSource("mangaview", "CustomHttpClient.java")
        val enterStart = client.indexOf("public void enterNtkStrictForegroundNetwork(")
        val enterEnd = client.indexOf("public void leaveNtkStrictForegroundNetwork(", enterStart)
        val enter = client.substring(enterStart, enterEnd)
        assertTrue(enter.contains("normalized.startsWith(\"/webtoon/\")"))
        assertTrue(enter.contains("isNtkWifiTransportActive()"))
        assertTrue(enter.contains("!isNtkCellularResilientTransportActive()"))
        assertTrue(enter.contains("directWifiWebtoonFence="))
        assertFalse(enter.contains("shutdownNtkQuicEngines();"))

        val fenceStart = client.indexOf(
            "public boolean shouldSkipDirectWifiStrictLegacySpeculation(String path)",
        )
        val fenceEnd = client.indexOf(
            "private boolean shouldSkipOwnedDirectWifiStrictLegacyImageProbe(",
            fenceStart,
        )
        val fence = client.substring(fenceStart, fenceEnd)
        assertTrue(fence.contains("ntkStrictForegroundNetworkPath.startsWith(\"/webtoon/\")"))
        assertTrue(fence.contains("if(directWifiWebtoonOwner)"))

        val manga = readSource("mangaview", "Manga.java")
        assertTrue(manga.contains("client.shouldSkipDirectWifiStrictLegacySpeculation(path)"))
        assertTrue(manga.contains("cacheNtkGeneratedImageExtensionMissUnlessStrictCutover("))
        assertTrue(manga.contains("client.canPublishNtkStrictLegacySpeculation(speculationEpoch, path)"))
        assertTrue(manga.contains("initial_validation_header_miss"))
        assertTrue(manga.contains("initial_validation_extension_miss"))
        assertTrue(manga.contains("publish_validated_pages"))

        assertTrue(client.contains("ntkStrictLegacySpeculationCutoverEpoch.incrementAndGet()"))
        assertTrue(client.contains("captureNtkStrictLegacySpeculationEpoch()"))
        assertTrue(client.contains("canPublishNtkStrictLegacySpeculation("))
        assertTrue(enter.contains("synchronized(ntkStrictForegroundNetworkLock)"))
        assertTrue(enter.contains("if(directWifiWebtoonFence)"))
        assertTrue(enter.contains("cancelNtkLegacyForegroundStreamsForStrictCutover("))
        assertTrue(enter.contains("long cutoverEpoch ="))

        val commitStart = client.indexOf("public boolean commitNtkStrictLegacySpeculation(")
        val commitEnd = client.indexOf("private boolean shouldSkipOwnedDirectWifiStrictLegacyImageProbe(", commitStart)
        val commit = client.substring(commitStart, commitEnd)
        assertTrue(commit.contains("synchronized(ntkStrictForegroundNetworkLock)"))
        assertTrue(commit.contains("producerEpoch != ntkStrictLegacySpeculationCutoverEpoch.get()"))
        assertTrue(commit.contains("publication.run();"))

        val cacheStart = manga.indexOf("private boolean cacheNtkGeneratedImageExtensionIfCurrent(")
        val cacheEnd = manga.indexOf("private boolean shouldSkipDirectWifiStrictLegacySpeculation(", cacheStart)
        val cachePublication = manga.substring(cacheStart, cacheEnd)
        assertTrue(
            Regex("commitNtkStrictLegacySpeculation\\(")
                .findAll(cachePublication)
                .count() >= 2
        )
        val validatedPublisherStart = manga.indexOf("private void publishValidatedEarlyNtkGeneratedImages(")
        val validatedPublisherEnd = manga.indexOf("private int ntkGeneratedImageQuickHeaderReachability(", validatedPublisherStart)
        assertTrue(
            manga.substring(validatedPublisherStart, validatedPublisherEnd)
                .contains("commitNtkStrictLegacySpeculation(")
        )

        val partialStart = manga.indexOf("private List<String> earlyGeneratedNtkImageUrlsFromPartial(")
        val partialEnd = manga.indexOf("private List<String> earlySlugWebtoonImageUrlsFromPartial(", partialStart)
        val partialPublisher = manga.substring(partialStart, partialEnd)
        assertTrue(partialPublisher.contains("long speculationEpoch"))
        assertTrue(
            Regex("commitNtkStrictLegacySpeculation\\(")
                .findAll(partialPublisher)
                .count() >= 2
        )

        val earlyPartialCallerStart = manga.indexOf("private void startEarlyGeneratedNtkImageStreamFromPartial(")
        val earlyPartialCallerEnd = manga.indexOf("public void startNtkEarlyViewerApiPrefetch(", earlyPartialCallerStart)
        val earlyPartialCaller = manga.substring(earlyPartialCallerStart, earlyPartialCallerEnd)
        assertTrue(earlyPartialCaller.contains("captureNtkStrictLegacySpeculationEpoch()"))
        assertTrue(earlyPartialCaller.contains("commitNtkStrictLegacySpeculation("))

        val kpAckCallerStart = manga.indexOf("private boolean handleNtkKpAckReadyPayloadText(")
        val kpAckCallerEnd = manga.indexOf(
            "private void startNtkKpSlugUnsignedViewerApiPrefetch(",
            kpAckCallerStart
        )
        val kpAckCaller = manga.substring(kpAckCallerStart, kpAckCallerEnd)
        assertTrue(kpAckCaller.contains("captureNtkStrictLegacySpeculationEpoch()"))
        assertTrue(kpAckCaller.contains("commitNtkStrictLegacySpeculation("))

        val centralStreamStart = manga.indexOf(
            "private void startFirstNtkApiImageStream(CustomHttpClient client, String path, List<String> urls,"
        )
        val centralStreamEnd = manga.indexOf(
            "private static boolean isNtkGeneratedPageImageUrl(",
            centralStreamStart
        )
        val centralStream = manga.substring(centralStreamStart, centralStreamEnd)
        assertTrue(centralStream.contains("captureNtkStrictLegacySpeculationEpoch()"))
        assertTrue(centralStream.contains("long speculationEpoch"))
        assertTrue(
            Regex("commitNtkStrictLegacySpeculation\\(")
                .findAll(centralStream)
                .count() >= 2
        )
        assertTrue(centralStream.contains("initial_foreground_stream_enqueue"))

        val readerCache = readSource("reader", "ReaderImageCache.kt")
        val cacheStreamStart = readerCache.indexOf("fun startForegroundStreamFetch(")
        val cacheStreamEnd = readerCache.indexOf("fun decodeForegroundBitmap(", cacheStreamStart)
        val cacheStream = readerCache.substring(cacheStreamStart, cacheStreamEnd)
        assertTrue(cacheStream.contains("legacySpeculationEpoch: Long = Long.MIN_VALUE"))
        assertTrue(cacheStream.contains("canPublishNtkStrictLegacySpeculation("))
        assertTrue(cacheStream.contains("foreground_stream_executor_abort_strict_cutover_epoch"))
        assertTrue(cacheStream.contains("registerLegacyForegroundStream("))
        assertTrue(cacheStream.contains("foreground_stream_registration_abort_strict_cutover_fence"))
        assertTrue(cacheStream.contains("forLegacySpeculation("))
        assertTrue(
            Regex("foregroundStreams\\.putIfAbsent\\(")
                .findAll(readerCache)
                .count() == 1
        )
        val decodeStart = readerCache.indexOf("fun decodeForegroundBitmap(")
        val decodeEnd = readerCache.indexOf("private fun scheduleForegroundStreamHandoffExpiry(", decodeStart)
        val decode = readerCache.substring(decodeStart, decodeEnd)
        assertTrue(decode.contains("registerLegacyForegroundStream("))
        assertTrue(decode.contains("canPublishNtkStrictLegacySpeculation("))
        assertTrue(decode.contains("forLegacySpeculation("))
        val runwayStart = readerCache.indexOf("fun startUnpublishedInitialGeneratedRunwayRace(")
        val runwayEnd = readerCache.indexOf("private fun requestInitialGeneratedCompleteBytesRace(", runwayStart)
        val runway = readerCache.substring(runwayStart, runwayEnd)
        assertTrue(runway.contains("registerLegacyForegroundStream("))
        assertTrue(runway.contains("canPublishNtkStrictLegacySpeculation("))
        assertTrue(runway.contains("forLegacySpeculation("))

        val trackedCallStart = readerCache.indexOf("private fun newTrackedNtkEpisodeCall(")
        val trackedCallEnd = readerCache.indexOf("private fun imageTelemetrySourceKey(", trackedCallStart)
        val trackedCall = readerCache.substring(trackedCallStart, trackedCallEnd)
        assertTrue(trackedCall.contains("legacySpeculationEpochFor(path)"))
        assertTrue(trackedCall.contains("synchronized(legacyForegroundStreamRegistrationLock)"))
        assertTrue(trackedCall.contains("strictLegacyForegroundRegistrationFenceEpochs[fencePath]"))
        assertTrue(trackedCall.contains("producerEpoch < cutoverEpoch"))
        assertTrue(
            trackedCall.indexOf("activeNtkEpisodeCalls[callKey] = tracked") <
                trackedCall.indexOf("cancellation?.track(tracked)")
        )
        val cutoverCancelStart = readerCache.indexOf(
            "fun cancelNtkLegacyForegroundStreamsForStrictCutover("
        )
        val cutoverCancelEnd = readerCache.indexOf(
            "fun suppressPermitlessInitialGeneratedForeground(",
            cutoverCancelStart
        )
        val cutoverCancel = readerCache.substring(cutoverCancelStart, cutoverCancelEnd)
        assertTrue(readerCache.contains("strictLegacyForegroundRegistrationFenceEpochs"))
        assertTrue(cutoverCancel.contains("synchronized(legacyForegroundStreamRegistrationLock)"))
        assertTrue(cutoverCancel.contains("cutoverEpoch: Long"))
        assertTrue(cutoverCancel.contains("strictLegacyForegroundRegistrationFenceEpochs[episodePath]"))
        assertTrue(cutoverCancel.contains("cancelFutureTasks(foregroundStreams"))
        assertTrue(cutoverCancel.contains("cancelActiveNtkEpisodeCalls"))
    }

    private fun readSource(packageName: String, name: String): String =
        String(Files.readAllBytes(sourcePath(packageName, name)), StandardCharsets.UTF_8)

    private fun sourcePath(packageName: String, name: String): Path {
        val appRelative = Paths.get("src", "main", "java", "ml", "melun", "mangaview", packageName, name)
        if (Files.isRegularFile(appRelative)) return appRelative
        return Paths.get("app", "src", "main", "java", "ml", "melun", "mangaview", packageName, name)
    }
}
