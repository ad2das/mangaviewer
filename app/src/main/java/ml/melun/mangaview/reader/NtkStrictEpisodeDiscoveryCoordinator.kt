package ml.melun.mangaview.reader

import android.os.SystemClock
import android.util.Log
import ml.melun.mangaview.mangaview.CustomHttpClient
import ml.melun.mangaview.mangaview.Manga
import ml.melun.mangaview.ntkack.NtkAckBrowserClient
import ml.melun.mangaview.runtime.PerfTrace
import ml.melun.mangaview.runtime.ViewerTelemetry
import java.io.InterruptedIOException
import java.util.ArrayList
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The only StrictFresh document + server grant -> exact image manifest coordinator.
 *
 * Calls from activities, warmup and the reader all join this path-level flight. The document GET
 * and grant prerequisite overlap, but there is exactly one logical document request, one challenge
 * request, one image-API request, one document parse and one API parse for a discovery generation.
 * Webtoon consumes the server's response-local trusted grant directly; manhwa and a real full
 * challenge retain the isolated browser/key-signing authority.
 */
object NtkStrictEpisodeDiscoveryCoordinator {
    private class AckRoute(
        val bootstrap: CustomHttpClient.NtkStrictAckBootstrap,
        val directTrustedTask: FutureTask<CustomHttpClient.NtkDirectTrustedGrant>?,
        val directTrustedThread: Thread?,
    ) {
        @Volatile
        var isolatedHandle: NtkAckBrowserClient.FlightHandle? = null
            private set

        @Synchronized
        fun attachIsolatedHandle(
            flight: Flight,
            handle: NtkAckBrowserClient.FlightHandle,
        ) {
            check(isolatedHandle == null) { "Strict isolated ACK owner already attached" }
            if (!flight.retirement.attachAckCancellation { handle.cancel() }) {
                handle.cancel()
                throw InterruptedIOException("Viewer ownership retired while ACK was starting")
            }
            isolatedHandle = handle
        }

        fun cancel() {
            directTrustedTask?.takeUnless { it.isDone }?.cancel(true)
            directTrustedThread?.takeIf { it.isAlive }?.interrupt()
            isolatedHandle?.takeUnless { it.isDone }?.cancel()
        }
    }

    private class Flight(
        val client: CustomHttpClient,
        val lease: NtkDiscoveryLease,
        val startedAtMs: Long,
        val viewerGeneration: Long,
        val episodePath: String,
        val rollingAdmission: Boolean,
        val completedRouteRecoveryAttempts: Int,
    ) {
        val retirement = NtkStrictDiscoveryRetirementFence(
            episodePath,
            viewerGeneration,
            lease.generation.value,
        )
        val physicalCalls = CustomHttpClient.NtkStrictCallRegistry(
            episodePath,
            viewerGeneration,
        )
        /** Exact authority is retained here until its viewer explicitly retires ownership. */
        val completed = AtomicBoolean(false)

        init {
            check(
                retirement.attachPhysicalCancellation {
                    physicalCalls.markCancelledAndDetachCalls()
                }
            )
        }
    }

    private val flights = ConcurrentHashMap<String, Flight>()
    private val flightLifecycleLocks = ConcurrentHashMap<String, Any>()

    @JvmStatic
    fun start(client: CustomHttpClient?, manga: Manga?): Boolean {
        return startInternal(
            client,
            manga,
            rollingAdmission = false,
            completedRouteRecoveryAttempts = 0,
        )
    }

    /** Exact cold-reader discovery whose physical image body admission starts at source 0/1. */
    @JvmStatic
    fun startColdRolling(client: CustomHttpClient?, manga: Manga?): Boolean {
        return startInternal(
            client,
            manga,
            rollingAdmission = true,
            completedRouteRecoveryAttempts = 0,
        )
    }

    private fun startInternal(
        client: CustomHttpClient?,
        manga: Manga?,
        rollingAdmission: Boolean,
        completedRouteRecoveryAttempts: Int,
    ): Boolean {
        if (client == null || manga == null) return false
        val path = normalizedPath(manga.ntkEpisodePath) ?: return false
        if (!ViewerTelemetry.hasActiveSession() || !ViewerTelemetry.isActiveEpisode(path)) {
            Log.d("ViewerPerf", "ntk_strict_exact_discovery_preclick_suppressed path=$path")
            return false
        }
        val viewerGeneration = ViewerTelemetry.activeGeneration()
        if (viewerGeneration <= 0L) return false
        val flight = synchronized(flightLifecycleLock(path)) {
            if (NtkSourceSpoolRegistry.currentAuthoritativeManifest(path) != null ||
                flights[path] != null
            ) return false
            val lease = if (rollingAdmission) {
                NtkSourceSpoolRegistry.beginColdRollingDiscovery(client.context, manga)
            } else {
                NtkSourceSpoolRegistry.beginDiscovery(client.context, manga)
            } ?: return false
            Flight(
                client,
                lease,
                SystemClock.elapsedRealtime(),
                viewerGeneration,
                path,
                rollingAdmission,
                completedRouteRecoveryAttempts,
            ).also {
                flights[path] = it
            }
        }

        val ackRoute = try {
            // The network-priority gate belongs to the exact discovery generation, not to the
            // Activity. Opening it only after the flight/lease is installed means every successful
            // enter has one deterministic retirement owner and an early start rejection cannot
            // strand a process-wide gate. This retires already-running compatibility calls but
            // starts no viewer request by itself.
            client.enterNtkStrictForegroundNetwork(path, viewerGeneration)
            // Discovery ownership is already fenced at this point. Retire any pre-cutover
            // main-process browser work before the isolated owner is allowed to navigate.
            client.cancelNtkWebViewFallbacks()
            // Bootstrap is local-only: it creates identity seeds but performs no network request.
            val bootstrap = client.prepareNtkStrictAckBootstrap(path)
            if (isDirectTrustedWebtoon(path)) {
                val task = FutureTask {
                    traceStage("NtkTrustedChallenge") {
                        client.fetchExactNtkTrustedChallengeGrant(
                            bootstrap,
                            flight.physicalCalls,
                        )
                    }
                }
                val thread = Thread(task, "ntk-strict-trusted-challenge").apply {
                    isDaemon = true
                    priority = (Thread.NORM_PRIORITY + 1).coerceAtMost(Thread.MAX_PRIORITY)
                }
                thread.start()
                AckRoute(bootstrap, task, thread)
            } else {
                // Numeric manhwa replica responses plus the fresh episode document can establish
                // exact source authority without spinning up an isolated WebView. Keep ACK fully
                // demand-driven: it is a compatibility fallback only if that observed path fails.
                AckRoute(bootstrap, null, null)
            }
        } catch (failure: Throwable) {
            synchronized(flightLifecycleLock(path)) {
                NtkSourceSpoolRegistry.retireDiscoveryForReplacement(
                    flight.lease,
                    "strict_exact_ack_start_${failure.javaClass.simpleName}"
                )
                flights.remove(path, flight)
            }
            Log.e(
                "ViewerPerf",
                "ntk_strict_exact_grant_start_failed path=$path," +
                    "generation=${flight.lease.generation.value}",
                failure
            )
            client.leaveNtkStrictForegroundNetwork(path, viewerGeneration)
            return false
        }
        try {
            val worker = Thread(
                { runFlight(client, manga, path, flight, ackRoute) },
                "ntk-strict-exact-discovery"
            ).apply {
                isDaemon = true
                priority = (Thread.NORM_PRIORITY + 1).coerceAtMost(Thread.MAX_PRIORITY)
            }
            if (!flight.retirement.attachWorker(worker)) {
                throw InterruptedIOException("Viewer ownership retired while worker was starting")
            }
            worker.start()
        } catch (failure: Throwable) {
            ackRoute.cancel()
            flight.physicalCalls.cancelAll()
            synchronized(flightLifecycleLock(path)) {
                NtkSourceSpoolRegistry.retireDiscoveryForReplacement(
                    flight.lease,
                    "strict_exact_worker_start_${failure.javaClass.simpleName}"
                )
                flights.remove(path, flight)
            }
            Log.e(
                "ViewerPerf",
                "ntk_strict_exact_worker_start_failed path=$path," +
                    "generation=${flight.lease.generation.value}",
                failure
            )
            client.leaveNtkStrictForegroundNetwork(path, viewerGeneration)
            return false
        }
        return true
    }

    @JvmStatic
    fun isInFlight(path: String?): Boolean =
        normalizedPath(path)?.let { key ->
            flights[key]?.let { !it.completed.get() && !it.retirement.isRetired() } == true
        } == true

    /**
     * Cancels only the coordinator flight owned by this exact viewer generation. An old Activity
     * can therefore never cancel a newer same-path viewer generation.
     */
    @JvmStatic
    fun retireViewerOwnership(
        path: String?,
        viewerGeneration: Long,
        reason: String?
    ): Boolean {
        val key = normalizedPath(path) ?: return false
        if (viewerGeneration <= 0L) return false
        val flight = synchronized(flightLifecycleLock(key)) {
            val owned = flights[key] ?: return false
            if (!owned.retirement.retire(key, viewerGeneration)) return false
            // Detach the terminal lease before releasing this path's flight slot. Its asynchronous
            // close barrier is generation-routed through a tombstone and cannot mutate a replacement.
            NtkSourceSpoolRegistry.retireDiscoveryForReplacement(
                owned.lease,
                "strict_exact_owner_retired_${safeReason(reason)}"
            )
            // Removing by identity lets a newer same-path viewer create its own generation now;
            // the retired worker's finally block cannot remove that newer entry.
            flights.remove(key, owned)
            owned
        }
        Log.d(
            "ViewerPerf",
            "ntk_strict_exact_owner_retired path=$key," +
                "viewerGeneration=$viewerGeneration," +
                "discoveryGeneration=${flight.lease.generation.value}," +
                "reason=${safeReason(reason)}"
        )
        flight.client.leaveNtkStrictForegroundNetwork(key, flight.viewerGeneration)
        return true
    }

    private fun startIsolatedAck(
        client: CustomHttpClient,
        flight: Flight,
        bootstrap: CustomHttpClient.NtkStrictAckBootstrap,
    ): NtkAckBrowserClient.FlightHandle =
        NtkAckBrowserClient.get(client.context).startAck(
            bootstrap.origin,
            bootstrap.episodePath,
            flight.lease.generation.value,
            bootstrap.userAgent,
            bootstrap.seedCookies,
        )

    private fun ensureIsolatedAck(
        client: CustomHttpClient,
        flight: Flight,
        ackRoute: AckRoute,
    ): NtkAckBrowserClient.FlightHandle = synchronized(ackRoute) {
        ackRoute.isolatedHandle ?: startIsolatedAck(
            client,
            flight,
            ackRoute.bootstrap,
        ).also { handle -> ackRoute.attachIsolatedHandle(flight, handle) }
    }

    /**
     * Returns a validated direct grant, or installs the isolated owner only after the server has
     * explicitly selected its full-challenge branch. No image request is started by this join.
     */
    private fun awaitDirectTrustedGrantOrStartIsolated(
        client: CustomHttpClient,
        flight: Flight,
        ackRoute: AckRoute,
    ): CustomHttpClient.NtkDirectTrustedGrant? {
        val task = ackRoute.directTrustedTask ?: return null
        return try {
            traceStage("NtkTrustedChallengeWait") { task.get() }
        } catch (wrapped: ExecutionException) {
            val cause = wrapped.cause ?: wrapped
            if (cause is CustomHttpClient.NtkStrictFullChallengeRequiredException) {
                requireDiscoveryOwnership(flight, "full_challenge_fallback_start")
                val handle = ensureIsolatedAck(client, flight, ackRoute)
                Log.d(
                    "ViewerPerf",
                    "ntk_strict_full_challenge_isolated_start path=${flight.episodePath}," +
                        "generation=${flight.lease.generation.value}," +
                        "elapsedMs=${SystemClock.elapsedRealtime() - flight.startedAtMs}"
                )
                null
            } else {
                throw cause
            }
        }
    }

    private fun runFlight(
        client: CustomHttpClient,
        manga: Manga,
        path: String,
        flight: Flight,
        ackRoute: AckRoute,
    ) {
        var exactInstalled = false
        var clickOwnedAnchor: NtkClickOwnedAnchorQuarantine? = null
        var clickOwnedManhwaProbe: NtkClickOwnedManhwaProbeFrontier? = null
        var streamingDocumentThread: Thread? = null
        var routeRecoveryRequested = false
        try {
            requireDiscoveryOwnership(flight, "worker_start")
            val directWebtoon = isDirectTrustedWebtoon(path)
            if (!directWebtoon) {
                // Resolve a four-page format sample at the committed click. It downloads no image
                // body and lets uncommon-format pages join the same bounded body race. Every body
                // still validates response headers and encoded magic before private quarantine.
                clickOwnedManhwaProbe = NtkClickOwnedManhwaProbeFrontier.start(manga, path)
                clickOwnedAnchor = NtkClickOwnedAnchorQuarantine.startFromTrustedPayloadHint(
                    client.context,
                    manga,
                    path,
                    flight.lease.generation.value,
                    clickOwnedManhwaProbe,
                )
                if (clickOwnedAnchor != null) {
                    clickOwnedManhwaProbe = null
                    if (manga.getExactNtkClickPayloadImageCount(path) > 0) {
                        clickOwnedAnchor.releaseForTrustedClickPayloadCount()
                    }
                } else {
                    // No exact click payload count is available on a true cold launch. Bind the
                    // metadata-only 120-page candidate frontier to a private maximum-bound owner
                    // now, after the committed click. The independent fresh document trims
                    // non-existent tail pages before source authority or decode can be published.
                    clickOwnedAnchor =
                        NtkClickOwnedAnchorQuarantine.startFromBoundedNumericCandidates(
                            client.context,
                            manga,
                            path,
                            flight.lease.generation.value,
                            clickOwnedManhwaProbe,
                        )
                    if (clickOwnedAnchor != null) {
                        clickOwnedManhwaProbe = null
                    }
                }
            }
            // Some current episode documents intentionally omit the optional streaming seed.
            // A missing seed only removes the document/API overlap optimization; the complete
            // document still carries the request identity needed to build the exact API request.
            // Model that as a normal nullable result so those episodes take the authoritative
            // post-document path instead of being rejected as malformed.
            val requestSeedFuture = CompletableFuture<NtkViewerImageRequestSeed?>()
            if (directWebtoon) {
                // The episode list was fetched to render the user-visible detail screen in this
                // same cold navigation. If that click payload carries the exact viewer token,
                // use it only after the committed click to overlap the image-list POST with the
                // fresh RSC document. The complete document below remains mandatory and must
                // match this request-only identity before any manifest or pixel is published.
                val clickPayloadSeed = NtkViewerImageRequestSeedParser.parseIfPresent(
                    flight.lease,
                    path,
                    manga.ntkViewerPayloadHint.toByteArray(Charsets.UTF_8),
                )
                if (clickPayloadSeed != null) {
                    requestSeedFuture.complete(clickPayloadSeed)
                    Log.d(
                        "ViewerPerf",
                        "ntk_strict_click_payload_request_seed_ready path=$path," +
                            "generation=${flight.lease.generation.value}," +
                            "elapsedMs=${SystemClock.elapsedRealtime() - flight.startedAtMs}",
                    )
                }
            }
            val documentCookiesPublished = AtomicBoolean(false)
            var streamedRequestSeed: NtkViewerImageRequestSeed? = null
            var overlappedApiResponse: CustomHttpClient.NtkBoundHttpResponse? = null
            var directGrantResolvedByOverlap = false
            val documentResponse = if (directWebtoon) {
                val documentTask = FutureTask {
                    try {
                        val response = traceStage("NtkExactDocument") {
                            tracePageListPhysicalRequest(flight) {
                                client.fetchExactNtkEpisodeDocument(
                                    path,
                                    flight.physicalCalls,
                                    object : CustomHttpClient.NtkStrictDocumentStreamObserver {
                                        override fun onResponseHeaders(
                                            responseHead: CustomHttpClient.NtkBoundHttpResponse,
                                        ) {
                                            requireDiscoveryOwnership(
                                                flight,
                                                "document_response_headers",
                                            )
                                            if (documentCookiesPublished.compareAndSet(false, true)) {
                                                withBoundedDiscoveryOwnership(
                                                    flight,
                                                    "streaming_document_cookie_publication",
                                                ) {
                                                    check(
                                                        client.publishExactNtkEpisodeResponseCookies(
                                                            responseHead,
                                                            flight.physicalCalls,
                                                        )
                                                    ) {
                                                        "Streaming document cookie publication lost ownership"
                                                    }
                                                }
                                                logStage(flight, "document_headers_ready")
                                            }
                                        }

                                        override fun onBodyPrefix(bodyPrefix: ByteArray): Boolean {
                                            if (requestSeedFuture.isDone) return true
                                            val seed =
                                                NtkViewerImageRequestSeedParser.parseIfPresent(
                                                    flight.lease,
                                                    path,
                                                    bodyPrefix,
                                                ) ?: return false
                                            if (requestSeedFuture.complete(seed)) {
                                                Log.d(
                                                    "ViewerPerf",
                                                    "ntk_strict_document_request_seed_ready path=$path," +
                                                        "generation=${flight.lease.generation.value}," +
                                                        "bytes=${bodyPrefix.size}," +
                                                        "elapsedMs=${SystemClock.elapsedRealtime() - flight.startedAtMs}",
                                                )
                                            }
                                            return true
                                        }
                                    },
                                )
                            }
                        }
                        if (!requestSeedFuture.isDone) {
                            val seed = NtkViewerImageRequestSeedParser.parseIfPresent(
                                flight.lease,
                                path,
                                response.bodyBytes,
                            )
                            if (seed != null) {
                                requestSeedFuture.complete(seed)
                            } else {
                                requestSeedFuture.complete(null)
                                Log.d(
                                    "ViewerPerf",
                                    "ntk_strict_document_request_seed_absent path=$path," +
                                        "generation=${flight.lease.generation.value}," +
                                        "fallback=complete_document_request_identity," +
                                        "elapsedMs=${SystemClock.elapsedRealtime() - flight.startedAtMs}",
                                )
                            }
                        }
                        response
                    } catch (failure: Throwable) {
                        // Without this hand-off a failed streaming document can leave the worker
                        // waiting forever for a request seed that can no longer be produced.
                        requestSeedFuture.completeExceptionally(failure)
                        throw failure
                    }
                }
                streamingDocumentThread = Thread(
                    documentTask,
                    "ntk-strict-streaming-document",
                ).apply {
                    isDaemon = true
                    priority = (Thread.NORM_PRIORITY + 1).coerceAtMost(Thread.MAX_PRIORITY)
                    start()
                }

                streamedRequestSeed = awaitFuture(requestSeedFuture)
                val directGrant = streamedRequestSeed?.let {
                    awaitDirectTrustedGrantOrStartIsolated(
                        client,
                        flight,
                        ackRoute,
                    )
                }
                directGrantResolvedByOverlap = streamedRequestSeed != null
                if (directGrant != null) {
                    requireDiscoveryOwnership(flight, "streaming_trusted_challenge_response")
                    logStage(flight, "trusted_challenge_ready")
                    withBoundedDiscoveryOwnership(flight, "streaming_trusted_grant_import") {
                        client.importVerifiedNtkAckCookieGrants(
                            directGrant.origin,
                            directGrant.episodePath,
                            directGrant.cookieGrants,
                        )
                    }
                    val unsignedRequest = traceStage("NtkStreamingExactRequestBuild") {
                        withDiscoveryOwnership(flight, "streaming_exact_webtoon_request_build") {
                            client.buildUnsignedExactNtkViewerImageApiRequest(
                                checkNotNull(streamedRequestSeed),
                                "",
                            )
                        }
                    }
                    logStage(flight, "streaming_trusted_request_ready")
                    overlappedApiResponse = traceStage("NtkStreamingTrustedImageApi") {
                        tracePageListPhysicalRequest(flight) {
                            client.executeUnsignedExactNtkWebtoonImageApi(
                                unsignedRequest,
                                flight.physicalCalls,
                            )
                        }
                    }
                }
                awaitFuture(documentTask)
            } else {
                traceStage("NtkExactDocument") {
                    tracePageListPhysicalRequest(flight) {
                        client.fetchExactNtkEpisodeDocument(
                            path,
                            flight.physicalCalls,
                        )
                    }
                }
            }
            // A physical response carries no authority after its viewer generation retires.
            requireDiscoveryOwnership(flight, "document_response")
            if (documentCookiesPublished.compareAndSet(false, true)) {
                withBoundedDiscoveryOwnership(flight, "document_cookie_publication") {
                    check(
                        client.publishExactNtkEpisodeResponseCookies(
                            documentResponse,
                            flight.physicalCalls,
                        )
                    ) { "Strict document cookie publication lost flight ownership" }
                }
            }
            clickOwnedAnchor?.let { anchor ->
                NtkEpisodeDocumentPlanParser.completeNumericPageCountHint(
                    flight.lease,
                    path,
                    documentResponse,
                )?.let(anchor::releaseForCompleteDocumentPageCount)
            }
            val draft = traceStage("NtkDocumentPlanParse") {
                NtkEpisodeDocumentPlanParser.parse(
                    flight.lease,
                    path,
                    documentResponse
                )
            }
            requireDiscoveryOwnership(flight, "document_parse_complete")
            streamedRequestSeed?.let { seed ->
                check(seed.matches(draft)) {
                    "Streaming request seed does not match the complete strict document"
                }
            }
            logStage(flight, "document_plan_ready")
            clickOwnedAnchor?.let { anchor ->
                check(anchor.validateDocumentDraft(draft)) {
                    "Click payload image count differs from the fresh episode document"
                }
                anchor.releaseAfterDocumentValidation()
            }
            var plan = NtkSourceSpoolRegistry.currentQuarantineAssetEvidence(flight.lease)
                ?.takeIf { evidence ->
                    evidence.viewerRequestIdentityDigest ==
                        draft.requestIdentity.identityDigestSha256 &&
                        evidence.normalizedOrderedCanonicalAssets.size == draft.pageCount
                }
                ?.let(draft::bind)
            var tokenBoundBodies: Map<Int, ReaderImageCache.NtkStrictPublishedBody> = emptyMap()
            var tokenBoundManifestDigest = ""
            val tokenBoundAuthority = if (plan == null) {
                NtkManifestAuthorityFactory
                    .createTokenBoundGeneratedManhwaDocumentAuthority(flight.lease, draft)
            } else {
                null
            }
            if (tokenBoundAuthority != null) {
                // The fresh document proves the finite work/episode/page table. Replica suffixes
                // are aliases of that logical pNNN page, while every selected body still needs its
                // own image header, EOF, digest and exact adoption proof. Promote this production
                // route immediately so completed pages decode/install progressively instead of
                // holding the entire scene behind the slowest response header.
                if (clickOwnedAnchor == null) {
                    clickOwnedAnchor = NtkClickOwnedAnchorQuarantine.start(
                        client.context,
                        manga,
                        draft,
                        clickOwnedManhwaProbe,
                    )
                    if (clickOwnedAnchor != null) clickOwnedManhwaProbe = null
                }
                clickOwnedAnchor?.let { anchor ->
                    anchor.releaseForTokenBoundDocumentAuthority(tokenBoundAuthority.manifest)
                    val tokenBoundStream = anchor.streamIfExact(tokenBoundAuthority.manifest)
                    if (tokenBoundStream != null) {
                        // Some valid numeric documents expose only a finite page count. Their
                        // generated pNNN names are virtual placeholders and every extension/replica
                        // returns 404; the signed image API below is the only physical authority.
                        // Do not publish generated authority until the already-running click probe
                        // and its exact page-zero body have proved real bytes. This waits for no
                        // extra request and does not delay a drawable: page zero could not render
                        // before the same body completion.
                        val sampledCandidate = tokenBoundStream.sampledAnchorCandidate?.let {
                            runCatching { awaitFuture(it) }
                        }
                        val sampledRouteMissing =
                            sampledCandidate?.isSuccess == true &&
                                sampledCandidate.getOrNull() == null
                        val exactAnchorBody = if (sampledRouteMissing) {
                            null
                        } else {
                            runCatching {
                                awaitFuture(checkNotNull(tokenBoundStream.bodyFutures[0]) {
                                    "Token-bound numeric stream omitted page zero"
                                })
                            }.getOrNull()
                        }
                        requireDiscoveryOwnership(
                            flight,
                            "token_bound_numeric_anchor_validation",
                        )
                        if (exactAnchorBody == null) {
                            tokenBoundStream.close()
                            clickOwnedAnchor = null
                            Log.d(
                                "ViewerPerf",
                                "ntk_token_bound_generated_route_rejected path=$path," +
                                    "sampledRouteMissing=$sampledRouteMissing," +
                                    "fallback=signed_image_api",
                            )
                        } else {
                            val planResult = withDiscoveryOwnership(
                                flight,
                                "token_bound_numeric_plan_reserve",
                            ) {
                                NtkSourceSpoolRegistry.reserveTokenBoundGeneratedDocumentPlan(
                                    client.context,
                                    manga,
                                    flight.lease,
                                    tokenBoundAuthority.plan,
                                    tokenBoundAuthority.manifest,
                                    streamedExactBodies = tokenBoundStream,
                                )
                            }
                            if (!planResult.accepted) tokenBoundStream.close()
                            check(planResult.accepted) {
                                "Token-bound numeric plan rejected: ${planResult.status}"
                            }
                            val authority = withDiscoveryOwnership(
                                flight,
                                "token_bound_numeric_manifest_install",
                            ) {
                                val install = NtkManifestAuthorityFactory
                                    .installTokenBoundGeneratedManhwaDocumentAuthority(
                                        client.context,
                                        manga,
                                        flight.lease,
                                        tokenBoundAuthority,
                                    )
                                check(install.accepted) {
                                    "Token-bound numeric manifest rejected: ${install.status}"
                                }
                                checkNotNull(install.authoritativeManifest) {
                                    "Token-bound numeric install omitted exact authority"
                                }.also { exactInstalled = true }
                            }
                            clickOwnedAnchor = null
                            ackRoute.cancel()
                            completeOwnedFlight(
                                manga,
                                path,
                                flight,
                                authority,
                                tokenBoundAuthority.plan,
                                "token_bound_numeric_document",
                            )
                            return
                        }
                    }
                }
            }
            if (plan == null && tokenBoundAuthority == null) {
                // Numeric manhwa paths deterministically name immutable replica assets. Start the
                // complete bounded forward wave only after this viewer click's document has proved
                // the episode and page count. Bodies stay private and undecoded until the fresh
                // signed API table validates every page identity below.
                if (clickOwnedAnchor == null) {
                    clickOwnedAnchor = NtkClickOwnedAnchorQuarantine.start(
                        client.context,
                        manga,
                        draft,
                        clickOwnedManhwaProbe,
                    )
                    if (clickOwnedAnchor != null) clickOwnedManhwaProbe = null
                    clickOwnedAnchor?.let { anchor ->
                        ensureIsolatedAck(client, flight, ackRoute).whenNetworkPrerequisitesReady {
                            anchor.releaseAfterAckNetworkPrerequisites()
                        }
                    }
                }
            }

            // Numeric manhwa does not need ACK when the complete click-owned document and every
            // physical replica response prove the finite mixed-extension table. Join only that
            // observed authority on the normal path; an isolated browser is created below solely
            // if observation genuinely fails and the signed-API compatibility path is required.
            val observedAuthorityFuture = if (plan == null) {
                clickOwnedAnchor?.observedDocumentAuthorityFuture(flight.lease, draft)
            } else {
                null
            }
            if (observedAuthorityFuture != null) {
                val value = runCatching { observedAuthorityFuture.join() }
                    .onFailure { failure ->
                        Log.d(
                            "ViewerPerf",
                            "ntk_observed_numeric_authority_failed path=$path," +
                                "error=${failure.javaClass.simpleName}",
                        )
                    }
                    .getOrNull()
                if (value != null) {
                    requireDiscoveryOwnership(flight, "observed_numeric_authority")
                    val observedStream = checkNotNull(
                        clickOwnedAnchor?.streamIfExact(value.manifest)
                    ) { "Observed numeric authority could not bind click-owned body stream" }
                    val planResult = withDiscoveryOwnership(
                        flight,
                        "observed_numeric_plan_reserve",
                    ) {
                        NtkSourceSpoolRegistry.reserveObservedNumericReplicaDocumentPlan(
                            client.context,
                            manga,
                            flight.lease,
                            value.plan,
                            value.manifest,
                            observedStream,
                        )
                    }
                    if (!planResult.accepted) observedStream.close()
                    check(planResult.accepted) {
                        "Observed numeric plan rejected: ${planResult.status}"
                    }
                    val authority = withDiscoveryOwnership(
                        flight,
                        "observed_numeric_manifest_install",
                    ) {
                        val install =
                            NtkManifestAuthorityFactory
                                .installObservedNumericReplicaDocumentAuthority(
                                    client.context,
                                    manga,
                                    flight.lease,
                                    value,
                                )
                        check(install.accepted) {
                            "Observed numeric manifest rejected: ${install.status}"
                        }
                        checkNotNull(install.authoritativeManifest) {
                            "Observed numeric install omitted exact authority"
                        }.also { exactInstalled = true }
                    }
                    clickOwnedAnchor = null
                    ackRoute.cancel()
                    completeOwnedFlight(
                        manga,
                        path,
                        flight,
                        authority,
                        value.plan,
                        "observed_numeric_replica",
                    )
                    return
                }
            }
            var planReserved = false
            if (plan != null) {
                val planResult = withDiscoveryOwnership(flight, "document_plan_reserve") {
                    NtkSourceSpoolRegistry.reserveDocumentPlan(
                        client.context,
                        manga,
                        flight.lease,
                        plan,
                    )
                }
                check(planResult.accepted) {
                    "Document plan rejected: ${planResult.status}"
                }
                planReserved = true
            }

            val directGrant = if (directGrantResolvedByOverlap) {
                null
            } else {
                awaitDirectTrustedGrantOrStartIsolated(client, flight, ackRoute)
            }
            val apiResponse = overlappedApiResponse ?: if (directGrant != null) {
                requireDiscoveryOwnership(flight, "trusted_challenge_response")
                logStage(flight, "trusted_challenge_ready")
                withBoundedDiscoveryOwnership(flight, "trusted_grant_import") {
                    client.importVerifiedNtkAckCookieGrants(
                        directGrant.origin,
                        directGrant.episodePath,
                        directGrant.cookieGrants,
                    )
                }
                val unsignedRequest = traceStage("NtkExactRequestBuild") {
                    withDiscoveryOwnership(flight, "exact_webtoon_request_build") {
                        client.buildUnsignedExactNtkViewerImageApiRequest(draft, "")
                    }
                }
                logStage(flight, "trusted_request_ready")
                traceStage("NtkTrustedImageApi") {
                    tracePageListPhysicalRequest(flight) {
                        client.executeUnsignedExactNtkWebtoonImageApi(
                            unsignedRequest,
                            flight.physicalCalls,
                        )
                    }
                }
            } else {
                val ackHandle = ensureIsolatedAck(client, flight, ackRoute)
                val ackProof = traceStage("NtkAckProofWait") { ackHandle.joinProof() }
                requireDiscoveryOwnership(flight, "ack_proof_response")
                logStage(flight, "ack_proof_ready")
                withBoundedDiscoveryOwnership(flight, "ack_grant_import") {
                    client.importVerifiedNtkAckCookieGrants(
                        ackProof.origin,
                        ackProof.episodePath,
                        ackProof.cookieGrants,
                    )
                }
                val unsignedRequest = traceStage("NtkExactRequestBuild") {
                    withDiscoveryOwnership(flight, "exact_request_build") {
                        client.buildUnsignedExactNtkViewerImageApiRequest(
                            draft,
                            ackProof.requestKeyId,
                        )
                    }
                }
                requireDiscoveryOwnership(flight, "ack_quiesce")
                traceStage("NtkAckQuiesce") { ackHandle.quiesce() }
                requireDiscoveryOwnership(flight, "ack_quiesce_response")
                traceStage("NtkSignedImageApi") {
                    tracePageListPhysicalRequest(flight) {
                        requireDiscoveryOwnership(flight, "exact_request_execute")
                        val exchange = ackHandle.executeExact(
                            unsignedRequest.endpoint,
                            unsignedRequest.requestIdentityDigestSha256,
                            unsignedRequest.imagesTokenDigestSha256,
                            unsignedRequest.request.bodyBytes,
                            unsignedRequest.request.headers,
                        )
                        requireDiscoveryOwnership(flight, "isolated_exact_response")
                        client.bindIsolatedExactNtkViewerImageApiResponse(
                            unsignedRequest,
                            exchange,
                        )
                    }
                }
            }
            // This check is intentionally the first operation after the physical API response.
            requireDiscoveryOwnership(flight, "signed_api_response")
            val envelope = NtkViewerImageApiAuthorityParser.parse(
                draft,
                apiResponse.request,
                apiResponse
            )
            requireDiscoveryOwnership(flight, "signed_api_parse_complete")
            val boundPlan: NtkProvisionalEpisodePlan
            val authority: NtkAuthoritativeManifest
            if (plan == null) {
                // A numeric manhwa document proves the page count and signing identity, not the
                // physical image URLs. Its generated /manhwa/.../pNNN.jpg names can be virtual
                // placeholders (and return 404) while the signed API supplies the actual CDN
                // assets. Never start or promote body work until that authoritative table exists.
                val exactEvidence = NtkQuarantineAssetEvidence.create(
                    path,
                    flight.lease.generation.value,
                    draft.requestIdentity.identityDigestSha256,
                    envelope.orderedAssets,
                    apiResponse.bodyBytes
                )
                withDiscoveryOwnership(flight, "quarantine_evidence_observe") {
                    check(
                        NtkSourceSpoolRegistry.observeQuarantineAssetEvidence(
                            flight.lease,
                            exactEvidence
                        )
                    ) { "Exact API evidence could not bind quarantine plan" }
                }
                plan = draft.bind(exactEvidence)
            }
            boundPlan = checkNotNull(plan)
            val exactManifestPreview = checkNotNull(
                NtkManifestAuthorityFactory.createViewerImageApiManifest(
                    flight.lease,
                    boundPlan,
                    envelope,
                )
            ) { "Exact API envelope could not build immutable manifest" }
            val clickOwnedExactStream = clickOwnedAnchor?.streamIfExact(exactManifestPreview)
            val clickOwnedExactBodies = when {
                clickOwnedExactStream != null -> emptyMap()
                tokenBoundBodies.isNotEmpty() &&
                    tokenBoundManifestDigest == exactManifestPreview.seal.digestSha256 ->
                    tokenBoundBodies
                else -> emptyMap()
            }
            clickOwnedAnchor = null
            if (!planReserved) {
                val planResult = withDiscoveryOwnership(flight, "exact_plan_reserve") {
                    NtkSourceSpoolRegistry.reserveDocumentPlan(
                        client.context,
                        manga,
                        flight.lease,
                        boundPlan,
                        clickOwnedExactBodies,
                        clickOwnedExactStream,
                    )
                }
                if (!planResult.accepted) clickOwnedExactStream?.close()
                check(planResult.accepted) {
                    "Exact-backed document plan rejected: ${planResult.status}"
                }
                planReserved = true
            }
            authority = withDiscoveryOwnership(flight, "exact_manifest_install") {
                val exactResult = NtkManifestAuthorityFactory.installViewerImageApiEnvelope(
                    client.context,
                    manga,
                    flight.lease,
                    boundPlan,
                    envelope
                )
                check(exactResult.accepted) {
                    "Exact manifest promotion rejected: ${exactResult.status}"
                }
                checkNotNull(exactResult.authoritativeManifest) {
                    "Accepted exact manifest omitted authority"
                }.also {
                    exactInstalled = true
                }
            }
            completeOwnedFlight(
                manga,
                path,
                flight,
                authority,
                boundPlan,
                "viewer_image_api",
            )
        } catch (failure: Throwable) {
            routeRecoveryRequested = !exactInstalled &&
                NtkSourceSpoolRegistry.currentAuthoritativeManifest(path) == null &&
                NtkStrictRouteRecoveryPolicy.shouldRecover(
                    failure,
                    flight.completedRouteRecoveryAttempts,
                ) &&
                ViewerTelemetry.hasActiveSession() &&
                ViewerTelemetry.activeGeneration() == flight.viewerGeneration &&
                ViewerTelemetry.isActiveEpisode(path)
            if (routeRecoveryRequested) {
                // Keep the old lease/flight as a path reservation until domain recovery finishes.
                // This prevents UI watchdogs from starting a competing flight in the gap.
                ackRoute.cancel()
                flight.physicalCalls.cancelAll()
                Log.w(
                    "ViewerPerf",
                    "ntk_strict_route_recovery_scheduled path=$path," +
                        "generation=${flight.lease.generation.value}," +
                        "attempt=${flight.completedRouteRecoveryAttempts + 1}," +
                        "failure=${failure.javaClass.simpleName}," +
                        "ms=${SystemClock.elapsedRealtime() - flight.startedAtMs}",
                )
            } else if (!exactInstalled &&
                NtkSourceSpoolRegistry.currentAuthoritativeManifest(path) == null
            ) {
                ackRoute.cancel()
                NtkSourceSpoolRegistry.failDiscovery(
                    flight.lease,
                    "strict_exact_discovery_${failure.javaClass.simpleName}"
                )
            } else if (exactInstalled && !flight.completed.get()) {
                // Promotion succeeded but final viewer ownership did not. Never leave the claimed
                // source reachable without a lifecycle retirement handle.
                synchronized(flightLifecycleLock(path)) {
                    if (flights[path] === flight) {
                        flight.retirement.retire(path, flight.viewerGeneration)
                        NtkSourceSpoolRegistry.retireDiscoveryForReplacement(
                            flight.lease,
                            "strict_exact_post_install_${failure.javaClass.simpleName}"
                        )
                        flights.remove(path, flight)
                    }
                }
            }
            Log.e(
                "ViewerPerf",
                "ntk_strict_exact_discovery_failed path=$path," +
                    "generation=${flight.lease.generation.value}," +
                    "ms=${SystemClock.elapsedRealtime() - flight.startedAtMs}",
                failure
            )
        } finally {
            streamingDocumentThread?.takeIf(Thread::isAlive)?.interrupt()
            clickOwnedManhwaProbe?.close()
            clickOwnedAnchor?.close()
            flight.retirement.detachWorker(Thread.currentThread())
            if (routeRecoveryRequested) {
                // The resolver is intentionally demand-driven and may run only after the failed
                // strict owner releases its network gate. The path slot stays reserved above.
                client.leaveNtkStrictForegroundNetwork(path, flight.viewerGeneration)
            } else if (!flight.completed.get() || flight.retirement.isRetired()) {
                flights.remove(path, flight)
                client.leaveNtkStrictForegroundNetwork(path, flight.viewerGeneration)
            }
        }
        if (routeRecoveryRequested) {
            recoverStrictRouteAndRestart(client, manga, path, flight)
        }
    }

    private fun recoverStrictRouteAndRestart(
        client: CustomHttpClient,
        manga: Manga,
        path: String,
        failedFlight: Flight,
    ) {
        val originBefore = client.getUrl(path)
        val changed = client.resolveNtkDomainAfterRouteFailure()
        val originAfter = client.getUrl(path)
        val stillOwned = ViewerTelemetry.hasActiveSession() &&
            ViewerTelemetry.activeGeneration() == failedFlight.viewerGeneration &&
            ViewerTelemetry.isActiveEpisode(path)
        var releasedForReplacement = false
        synchronized(flightLifecycleLock(path)) {
            if (flights[path] === failedFlight) {
                NtkSourceSpoolRegistry.retireDiscoveryForReplacement(
                    failedFlight.lease,
                    "strict_route_recovery_${failedFlight.completedRouteRecoveryAttempts + 1}",
                )
                flights.remove(path, failedFlight)
                releasedForReplacement = true
            }
        }
        if (!stillOwned || !releasedForReplacement) {
            Log.d(
                "ViewerPerf",
                "ntk_strict_route_recovery_abandoned path=$path," +
                    "viewerGeneration=${failedFlight.viewerGeneration}," +
                    "stillOwned=$stillOwned,released=$releasedForReplacement",
            )
            return
        }
        val restarted = startInternal(
            client,
            manga,
            failedFlight.rollingAdmission,
            failedFlight.completedRouteRecoveryAttempts + 1,
        )
        val joined = restarted ||
            isInFlight(path) ||
            NtkSourceSpoolRegistry.currentAuthoritativeManifest(path) != null
        Log.d(
            "ViewerPerf",
            "ntk_strict_route_recovery_result path=$path," +
                "viewerGeneration=${failedFlight.viewerGeneration}," +
                "attempt=${failedFlight.completedRouteRecoveryAttempts + 1}," +
                "changed=$changed,restarted=$restarted,joined=$joined," +
                "originBefore=$originBefore,originAfter=$originAfter",
        )
    }

    private fun requireDiscoveryOwnership(flight: Flight, boundary: String) {
        withDiscoveryOwnership(flight, boundary) { Unit }
    }

    /**
     * Serializes every parse/publication boundary with explicit retirement. ViewerTelemetry is
     * checked inside the fence so a generation switch cannot race an authority installation.
     */
    private fun <T> withDiscoveryOwnership(
        flight: Flight,
        boundary: String,
        action: () -> T
    ): T = flight.retirement.withActiveOwnership(
        flight.episodePath,
        flight.viewerGeneration,
        boundary
    ) {
        requireFlightIdentity(flight, boundary)
        action()
    }

    /** Only local cookie-map commits may use this state-lock-linearized boundary. */
    private fun <T> withBoundedDiscoveryOwnership(
        flight: Flight,
        boundary: String,
        action: () -> T,
    ): T = flight.retirement.withBoundedActiveOwnership(
        flight.episodePath,
        flight.viewerGeneration,
        boundary,
    ) {
        requireFlightIdentity(flight, boundary)
        action()
    }

    private fun requireFlightIdentity(flight: Flight, boundary: String) {
        if (flights[flight.episodePath] !== flight ||
            !ViewerTelemetry.hasActiveSession() ||
            ViewerTelemetry.activeGeneration() != flight.viewerGeneration ||
            !ViewerTelemetry.isActiveEpisode(flight.episodePath)
        ) {
            throw InterruptedIOException(
                "Viewer ownership retired before $boundary path=${flight.episodePath}," +
                    "viewerGeneration=${flight.viewerGeneration}"
            )
        }
    }

    private fun <T> withOwnedAuthority(
        flight: Flight,
        authority: NtkAuthoritativeManifest,
        boundary: String,
        action: () -> T
    ): T = withDiscoveryOwnership(flight, boundary) {
        check(authority.seal.normalizedEpisodePath.equals(
            flight.episodePath,
            ignoreCase = true
        )) { "Owned authority episode mismatch" }
        action()
    }

    private fun completeOwnedFlight(
        manga: Manga,
        path: String,
        flight: Flight,
        authority: NtkAuthoritativeManifest,
        plan: NtkProvisionalEpisodePlan,
        source: String,
    ) {
        runCatching {
            withOwnedAuthority(flight, authority, "compatibility_mirror") {
                mirrorOwnedAuthority(manga, path, authority)
            }
        }.onFailure {
            Log.e(
                "ViewerPerf",
                "ntk_strict_exact_compatibility_mirror_failed path=$path",
                it,
            )
        }
        requireDiscoveryOwnership(flight, "discovery_complete")
        // Keep the flight as the completed source-lifetime owner only after the final viewer
        // identity check. Activity teardown or adjacent navigation then detaches its generation.
        flight.completed.set(true)
        Log.d(
            "ViewerPerf",
            "ntk_strict_exact_discovery_owned path=$path," +
                "generation=${flight.lease.generation.value}," +
                "source=$source," +
                "pages=${authority.seal.pageCount}," +
                "planProof=${plan.proof.proofDigestSha256}," +
                "manifestDigest=${authority.seal.digestSha256}," +
                "proofDigest=${authority.proof.proofDigestSha256}," +
                "ms=${SystemClock.elapsedRealtime() - flight.startedAtMs}",
        )
    }

    private inline fun <T> tracePageListPhysicalRequest(flight: Flight, request: () -> T): T {
        requireDiscoveryOwnership(flight, "page_list_request")
        val operationId = ViewerTelemetry.pageListRequestStarted()
        return try {
            val result = request()
            // A request admitted by the old generation may finish after Activity teardown. Its
            // bytes must be discarded before parsing, reservation or manifest publication.
            requireDiscoveryOwnership(flight, "page_list_response")
            ViewerTelemetry.pageListRequestFinished(operationId, "success")
            result
        } catch (failure: Throwable) {
            ViewerTelemetry.pageListRequestFinished(operationId, pageListFailureOutcome(failure))
            throw failure
        }
    }

    private inline fun <T> traceStage(name: String, action: () -> T): T {
        PerfTrace.begin(name)
        return try {
            action()
        } finally {
            PerfTrace.end()
        }
    }

    private fun <T> awaitFuture(future: Future<T>): T = try {
        future.get()
    } catch (wrapped: ExecutionException) {
        throw (wrapped.cause ?: wrapped)
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        throw InterruptedIOException("Strict discovery future was interrupted").also {
            it.initCause(interrupted)
        }
    }

    private fun logStage(flight: Flight, stage: String) {
        Log.d(
            "ViewerPerf",
            "ntk_strict_cold_stage path=${flight.episodePath}," +
                "generation=${flight.lease.generation.value},stage=$stage," +
                "elapsedMs=${SystemClock.elapsedRealtime() - flight.startedAtMs}"
        )
    }

    private fun pageListFailureOutcome(failure: Throwable): String {
        var current: Throwable? = failure
        repeat(6) {
            when (current) {
                is InterruptedException,
                is InterruptedIOException,
                is CancellationException -> return "cancelled_${failure.javaClass.simpleName}"
            }
            if (current?.message?.equals("canceled", ignoreCase = true) == true) {
                return "cancelled_${failure.javaClass.simpleName}"
            }
            current = current?.cause
        }
        return "failed_${failure.javaClass.simpleName.ifBlank { "unknown" }}"
    }

    private fun safeReason(reason: String?): String = reason.orEmpty()
        .lowercase()
        .replace(Regex("[^a-z0-9_]+"), "_")
        .trim('_')
        .take(48)
        .ifBlank { "unspecified" }

    /**
     * Compatibility consumers receive exact URLs only after the registry already owns the source.
     * They are mirrors, never inputs to the strict source or controller authority.
     */
    private fun mirrorOwnedAuthority(
        manga: Manga,
        path: String,
        authority: NtkAuthoritativeManifest
    ) {
        val current = NtkSourceSpoolRegistry.currentAuthoritativeManifest(path)
        val snapshot = NtkSourceSpoolRegistry.currentSnapshot(path)
        val sourceOwner = NtkStrictSourceOwnershipRegistry.owner(path)
        val registryOwnsAuthority = current?.seal?.hasSameAuthority(authority.seal) == true &&
            snapshot != null &&
            snapshot.manifestDigest == authority.seal.digestSha256 &&
            snapshot.state.ordinal >= NtkSourceState.OWNED_PRECLAIM.ordinal &&
            snapshot.state.ordinal < NtkSourceState.TERMINAL_CLOSING.ordinal
        val uiOwnsAuthority = sourceOwner?.state == NtkStrictSourceOwnershipRegistry.State.OWNED &&
            sourceOwner.manifestDigest == authority.seal.digestSha256 &&
            sourceOwner.discoveryGeneration == authority.proof.discoveryGeneration
        check(registryOwnsAuthority || uiOwnsAuthority) {
            "Compatibility publication preceded exact source ownership"
        }

        val exactUrls = ArrayList(authority.seal.normalizedCanonicalAssets)
        manga.setNtkImageCount(exactUrls.size)
        manga.setImgs(exactUrls)
        ReaderImageCache.rememberEarlyNtkImageUrls(
            path,
            exactUrls,
            strictExactOwner = true
        )
    }

    /**
     * HTTP path segments are case-sensitive. Keep the exact catalog/click spelling for slug
     * works while matching only the fixed route prefix without case sensitivity.
     *
     * Lowercasing the whole value made a real click such as
     * `/webtoon/u-bt-I_killed-863ce912/...` differ from ViewerTelemetry's click authority. The
     * coordinator then treated the already-committed click as a pre-click call and suppressed the
     * only discovery flight, leaving the reader permanently empty.
     */
    internal fun normalizedPath(path: String?): String? {
        val normalized = NtkStripDigests.normalizeEpisodePath(path.orEmpty())
        return normalized.takeIf {
            it.matches(
                Regex(
                    """^/(?:manhwa|webtoon)/[^/?#]+/[^/?#]+$""",
                    RegexOption.IGNORE_CASE,
                ),
            )
        }
    }

    private fun isDirectTrustedWebtoon(path: String): Boolean =
        path.startsWith("/webtoon/", ignoreCase = true)

    private fun flightLifecycleLock(path: String): Any =
        flightLifecycleLocks.computeIfAbsent(path) { Any() }
}
