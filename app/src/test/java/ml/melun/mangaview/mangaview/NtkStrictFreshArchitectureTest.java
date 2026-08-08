package ml.melun.mangaview.mangaview;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Structural gates for the isolated StrictFresh production path. */
public final class NtkStrictFreshArchitectureTest {
    @Test
    public void strictAckWebViewIsOwnedOnlyByTheIsolatedServiceProcess() throws Exception {
        String manifest = read(projectPath("src", "main", "AndroidManifest.xml"));
        String client = read(ntkAckSourcePath("NtkAckBrowserClient.kt"));
        String engine = read(ntkAckSourcePath("NtkAckBrowserEngine.kt"));
        String ackPackage = readProductionDirectory(ntkAckSourcePath(""));

        assertTrue(manifest.contains("android:name=\".ntkack.NtkAckBrowserService\""));
        assertTrue(manifest.contains("android:process=\":ntk_ack\""));
        assertFalse(client.contains("android.webkit.WebView"));
        assertFalse(client.contains("WebView("));
        assertTrue(engine.contains("val created = WebView(context)"));
        assertTrue(engine.contains("check(created.parent == null)"));
        assertTrue(ackPackage.contains("NtkAckBrowserEngine"));
        assertFalse(ackPackage.replace(engine, "").contains("WebView(context)"));
    }

    @Test
    public void strictProductionOrderIsProofCookieQuiescenceThenOneIsolatedExactRequest()
            throws Exception {
        String coordinator = read(readerSourcePath("NtkStrictEpisodeDiscoveryCoordinator.kt"));
        String runFlight = method(coordinator,
                "private fun runFlight(",
                "private fun requireDiscoveryOwnership(");
        int document = runFlight.indexOf("client.fetchExactNtkEpisodeDocument(");
        int directBranch = runFlight.indexOf("if (directGrant != null)");
        int isolatedBranch = runFlight.indexOf(
                "val ackHandle = ensureIsolatedAck(client, flight, ackRoute)");
        String direct = runFlight.substring(directBranch, isolatedBranch);
        String isolated = runFlight.substring(isolatedBranch);
        String client = read(sourcePath("CustomHttpClient.java"));
        String trustedChallenge = method(client,
                "public NtkDirectTrustedGrant fetchExactNtkTrustedChallengeGrant(",
                "/**\n     * Converts the authoritative HTTP Date");
        String nvGrantExchange = method(client,
                "private NtkNvIssueGrant fetchExactNtkNvGrants(",
                "private static String strictNtkCookieHeader(");

        int directCookies = direct.indexOf("importVerifiedNtkAckCookieGrants(");
        int directUnsigned = direct.indexOf("buildUnsignedExactNtkViewerImageApiRequest(");
        int directExecute = direct.indexOf("executeUnsignedExactNtkWebtoonImageApi(");
        assertTrue(document >= 0 && directBranch > document);
        assertTrue(directCookies >= 0);
        assertTrue(directUnsigned > directCookies);
        assertTrue(directExecute > directUnsigned);
        int nvIssueRequest = trustedChallenge.indexOf("fetchExactNtkNvGrants(");
        int trustedChallengeRequest = trustedChallenge.indexOf(
                "NtkBoundHttpResponse exchange = executeStrictExactSameOriginRequest(");
        assertTrue(nvIssueRequest >= 0);
        assertTrue(trustedChallengeRequest > nvIssueRequest);
        assertTrue(nvGrantExchange.contains("\"/api/nv-issue\""));
        assertTrue(nvGrantExchange.contains("\"nv_issue\""));
        assertTrue(nvGrantExchange.contains("callRegistry"));
        assertTrue(nvGrantExchange.contains("NtkAckCookieBoundary.INSTANCE.validateGrants("));
        assertTrue(nvGrantExchange.contains("!shouldUseNtkCellularResilientTransport()"));
        assertTrue(nvGrantExchange.contains("allowExactBodyAuthority"));
        assertTrue(nvGrantExchange.contains("responseHasSingleJsonContentType"));
        assertTrue(nvGrantExchange.contains("\"ok\", \"session\", \"ttl\""));
        assertTrue(nvGrantExchange.contains("new ConnectionPool()"));
        assertTrue(nvGrantExchange.contains("\"nv_issue_cookie_recovery\""));

        int nvStart = isolated.indexOf("ensureExactNvSeed(client, flight, ackRoute)");
        int proof = isolated.indexOf("ackHandle.joinProof()");
        int cookies = isolated.indexOf("importVerifiedNtkAckCookieGrants(");
        int nvWait = isolated.indexOf("awaitFuture(nvSeedTask)");
        int unsigned = isolated.indexOf("buildUnsignedExactNtkViewerImageApiRequest(");
        int quiesce = isolated.indexOf("ackHandle.quiesce()");
        int execute = isolated.indexOf("ackHandle.executeExact(");
        int bind = isolated.indexOf("bindIsolatedExactNtkViewerImageApiResponse(");
        assertTrue(nvStart >= 0 && nvStart < proof);
        assertTrue(cookies > proof);
        assertTrue(nvWait > cookies);
        assertTrue(unsigned > nvWait);
        assertTrue(quiesce > unsigned);
        assertTrue(execute > quiesce);
        assertTrue(bind > execute);
    }

    @Test
    public void strictCoordinatorHasNoBrowserImageAuthorityOrFallbackImport() throws Exception {
        String coordinator = read(readerSourcePath("NtkStrictEpisodeDiscoveryCoordinator.kt"));

        assertFalse(coordinator.contains("NtkBrowserSessionBroker"));
        assertFalse(coordinator.contains("NtkWebViewFallbackManager"));
        assertFalse(coordinator.contains("rememberAuthoritativeNtkImageUrlsFromBrowser"));
        assertTrue(coordinator.contains("NtkManifestAuthorityFactory.installViewerImageApiEnvelope"));
    }

    @Test
    public void rollingPageTableOwnsAStableIdentityBeforeHwuiCommitQualification()
            throws Exception {
        String surface = read(readerSourcePath("ReaderSurfaceView.kt"));
        String setPageCount = method(surface,
                "fun setPageCount(count: Int, deferInitialEmptyDraw: Boolean = false)",
                "fun appendPageCount(count: Int, revealAppendedBoundary: Boolean = false)");
        String frameCommitted = method(surface,
                "private fun onFrameCommitted(",
                "private fun releasePostedAdmissionLocked(preserveDirty: Boolean)");

        assertTrue(setPageCount.contains("resetTraversalProofLocked(nextCount)"));
        assertTrue(setPageCount.contains("extendTraversalProofLocked(nextCount)"));
        assertTrue(frameCommitted.contains("pendingFrameCommits.remove(token)"));
        assertTrue(frameCommitted.contains("submission.callbackRegistered"));
        assertTrue(frameCommitted.contains("CompletedDrawProof("));
        assertTrue(frameCommitted.contains("listener?.onCompletedDraw(proof)"));
    }

    @Test
    public void anyRemainingSharedManagerRejectsStrictDocumentImageAndAckWork() throws Exception {
        String manager = read(sourcePath("NtkWebViewFallbackManager.java"));

        assertTrue(!manager.contains("prepareViewerAckForReaderLaunch(")
                || manager.contains("ntk_prepare_viewer_ack_rejected_by_strict_owner"));
        assertTrue(!manager.contains("fetchViewerImageUrls(")
                || manager.contains("ntk_webview_viewer_images_rejected_by_strict_owner"));
        assertTrue(!manager.contains("ensureWebView(task.userAgent)")
                || manager.contains("ntk_webview_begin_rejected_by_strict_owner"));
    }

    @Test
    public void oldMainProcessStrictAckOwnerAndLocalExactSignerAreGone() throws Exception {
        String client = read(sourcePath("CustomHttpClient.java"));
        String coordinator = read(readerSourcePath("NtkStrictEpisodeDiscoveryCoordinator.kt"));
        String production = readProductionDirectory(projectPath(
                "src", "main", "java", "ml", "melun", "mangaview"));

        assertFalse(client.contains("class NtkAckFlightHandle"));
        assertFalse(client.contains("getOrStartNtkDiscoveryAckFlight"));
        assertFalse(client.contains("fetchExactNtkViewerImageApi"));
        assertFalse(coordinator.contains("CustomHttpClient.NtkAckProof"));
        assertFalse(production.contains("performNtkWebViewAckPreflight"));
        assertFalse(production.contains("viewerNtkWebViewAuthoritative"));
        assertFalse(production.contains("NtkBrowserSessionBroker.attach("));
        assertFalse(production.contains("NtkBrowserSessionBroker.INSTANCE.attach("));
        assertFalse(production.contains(".installBrowserManifestAndRequestAll("));
    }

    @Test
    public void exactApiTransportIsOneCallWithoutRetryHedgeOrFallback() throws Exception {
        String source = read(sourcePath("CustomHttpClient.java"));
        String builder = method(source,
                "private static OkHttpClient.Builder strictNtkExactImageApiClient(",
                "private static OkHttpClient.Builder fastExternalViewerPageClient(");
        String execute = method(source,
                "public NtkBoundHttpResponse executeSignedExactNtkViewerImageApi(",
                "public long strictDocumentLogicalRequestCount()");
        String exactTransport = method(source,
                "private NtkBoundHttpResponse executeStrictExactSameOriginRequest(",
                "private byte[] readStrictExactDocumentBody(");

        assertTrue(builder.contains(".retryOnConnectionFailure(false)"));
        assertTrue(builder.contains(".followRedirects(false)"));
        assertFalse(builder.contains("SNI_FRAGMENTING_SOCKET_FACTORY"));
        assertTrue(builder.contains("return baseClient(builder)"));
        assertTrue(execute.contains("executeStrictExactSameOriginRequest("));
        assertFalse(execute.contains("newCall("));
        int cachedEngine = exactTransport.indexOf("getCachedNtkQuicEngine(baseUrl)");
        int engineCall = exactTransport.indexOf("fetchWithEngineExactOwned(");
        int engineFailure = exactTransport.indexOf(
                "throw new IOException(\"Strict \" + stage + \" HttpEngine request failed\"");
        int createCall = exactTransport.indexOf("fallbackClient.newCall(");
        int registerCall = exactTransport.indexOf("callRegistry.register(physicalCall)");
        int executeCall = exactTransport.indexOf("physicalCall.execute()");
        int unregisterCall = exactTransport.indexOf("callRegistry.unregister(physicalCall)");
        assertTrue(cachedEngine >= 0 && engineCall > cachedEngine);
        assertFalse(exactTransport.contains("getOrCreateNtkQuicEngine("));
        assertTrue(engineFailure > engineCall);
        assertTrue(createCall > engineFailure);
        assertTrue(registerCall > createCall);
        assertTrue(executeCall > registerCall);
        assertTrue(unregisterCall > executeCall);
        assertTrue(occurrences(exactTransport, "fetchWithEngineExactOwned(") == 1);
        assertTrue(occurrences(exactTransport, "fallbackClient.newCall(") == 1);
        assertTrue(occurrences(exactTransport, "physicalCall.execute()") == 1);
        assertFalse(exactTransport.contains("while("));
        assertFalse(exactTransport.contains("for(;;"));
        assertFalse(exactTransport.toLowerCase().contains("hedge"));
        assertFalse(execute.contains("applyNtkViewerImagesSignature"));
    }

    @Test
    public void strictExactWireContractSplitsWebtoonNvProofFromManhwaRequestKeySigning()
            throws Exception {
        String source = read(sourcePath("CustomHttpClient.java"));
        String coordinator = read(readerSourcePath("NtkStrictEpisodeDiscoveryCoordinator.kt"));
        String unsigned = method(source,
                "public NtkUnsignedExactImageRequest buildUnsignedExactNtkViewerImageApiRequest(",
                "/** Executes the already signed exact request exactly once");
        String execute = method(source,
                "public NtkBoundHttpResponse executeSignedExactNtkViewerImageApi(",
                "public long strictDocumentLogicalRequestCount()");

        int webtoonPayload = unsigned.indexOf(
                "payload = strictWebtoonViewerImagesPayload(");
        int manhwaBranch = unsigned.indexOf("} else {", webtoonPayload);
        int bindManhwaBodyKey = unsigned.indexOf(
                "payload.put(\"requestKeyId\", normalizedRequestKeyId);");
        int serializeBody = unsigned.indexOf(
                "byte[] requestBytes = payload.toString().getBytes(StandardCharsets.UTF_8);");
        int bindNvSession = unsigned.indexOf(
                "headers.put(\"x-nv-session\", nv);");
        int validateManhwaBodyKey = execute.indexOf(
                "signature.getRequestKeyId().equals(bodyRequestKeyId)");
        int signedWireBranch = execute.indexOf("if(!webtoonWireContract)");
        int attachKeyHeader = execute.indexOf(
                "headers.put(\"x-ntk-key-id\", signature.getRequestKeyId());");
        int physicalPost = execute.indexOf("executeStrictExactSameOriginRequest(");

        assertTrue(coordinator.contains("ackProof.requestKeyId"));
        assertTrue(coordinator.contains("ackHandle.executeExact("));
        assertTrue(coordinator.contains("bindIsolatedExactNtkViewerImageApiResponse("));
        assertTrue(webtoonPayload >= 0 && manhwaBranch > webtoonPayload);
        assertTrue(bindManhwaBodyKey > manhwaBranch && serializeBody > bindManhwaBodyKey);
        assertTrue(bindNvSession > serializeBody);
        assertTrue(execute.contains(
                "\"workId\", \"episodeId\", \"token\", \"nonce\", \"proof\""));
        assertTrue(validateManhwaBodyKey >= 0 && signedWireBranch > validateManhwaBodyKey);
        assertTrue(attachKeyHeader > signedWireBranch && physicalPost > attachKeyHeader);
        assertTrue(execute.contains(
                "strictWebtoonProofMessage(token, nonce)).equals(proof)"));
    }

    @Test
    public void strictPhysicalCallsAreFlightOwnedAndDocumentCookiesPublishAfterRevalidation()
            throws Exception {
        String source = read(sourcePath("CustomHttpClient.java"));
        String document = method(source,
                "public NtkBoundHttpResponse fetchExactNtkEpisodeDocument(\n"
                        + "            String episodePath,",
                "/**\n     * Source-compatible fail-closed overload");
        assertTrue(document.contains("executeStrictExactSameOriginRequest("));
        assertFalse(document.contains("newCall("));
        assertFalse(document.contains("storeResponseCookies("));
        assertTrue(document.contains("headers.put(\"accept\", \"text/x-component\")"));
        assertTrue(document.contains("headers.put(\"rsc\", \"1\")"));
        assertTrue(document.contains("headers.put(\"next-url\", encodedNormalized)"));
        assertTrue(document.contains("if(compactAdjacentRsc)"));
        assertTrue(document.contains("headers.put(\"next-router-state-tree\", routerState)"));
        assertTrue(document.contains("ntkCompactAdjacentRscHash(routerState, encodedNormalized)"));
        assertTrue(document.contains("headers.put(\"Sec-Fetch-Dest\", \"empty\")"));
        assertFalse(document.contains("text/html,application/xhtml+xml"));

        String exactTransport = method(source,
                "private NtkBoundHttpResponse executeStrictExactSameOriginRequest(",
                "public NtkBoundHttpResponse fetchExactNtkEpisodeDocument(");
        int fallbackCreate = exactTransport.indexOf("fallbackClient.newCall(");
        int fallbackRegister = exactTransport.indexOf("callRegistry.register(physicalCall)");
        int fallbackExecute = exactTransport.indexOf("physicalCall.execute()");
        int fallbackUnregister = exactTransport.indexOf("callRegistry.unregister(physicalCall)");
        assertTrue(exactTransport.contains("getCachedNtkQuicEngine(baseUrl)"));
        assertTrue(exactTransport.contains("fetchWithEngineExactOwned("));
        assertTrue(exactTransport.contains("callRegistry"));
        assertTrue(fallbackCreate >= 0);
        assertTrue(fallbackRegister > fallbackCreate);
        assertTrue(fallbackExecute > fallbackRegister);
        assertTrue(fallbackUnregister > fallbackExecute);

        String publication = method(source,
                "public boolean publishExactNtkEpisodeResponseCookies(",
                "private static void requireStrictCallRegistry(");
        assertTrue(publication.contains("callRegistry.publishIfActive("));
        assertTrue(publication.contains("storeResponseCookies(response.responseHeaders)"));

        String registry = method(source,
                "public static final class NtkStrictCallRegistry implements NtkQuicFetcher.RequestOwner {",
                "/** Main-process data that may cross into the isolated ACK process.");
        assertTrue(registry.contains("private final LinkedHashSet<Call> activeCalls"));
        assertTrue(registry.contains("private final LinkedHashSet<UrlRequest> activeEngineRequests"));
        assertTrue(registry.contains("synchronized(gate)"));
        assertTrue(registry.contains("call.cancel()"));
        assertTrue(registry.contains("request.cancel()"));
        assertTrue(registry.contains("boolean publishIfActive(Runnable publication)"));
    }

    @Test
    public void compactAdjacentRscUsesNextNavigationCacheKey() {
        assertEquals(
                "28hdg",
                CustomHttpClient.ntkCompactAdjacentRscHash(
                        "%5B%22%22%2C%7B%7D%5D",
                        "/webtoon/395442/1440648"));
    }

    @Test
    public void rendererQuiescenceHasAnEventBoundedFailClosedTransition() throws Exception {
        String engine = read(ntkAckSourcePath("NtkAckBrowserEngine.kt"));

        assertTrue(engine.contains("renderer.terminate()"));
        assertTrue(engine.contains("QUIESCENCE_RENDERER_TIMEOUT_MS"));
        assertTrue(engine.contains("quiescence_renderer_timeout"));
        assertTrue(engine.contains("NtkAckProtocol.FAILURE_QUIESCENCE"));
        assertTrue(engine.contains("rendererGone || quiesceRendererGone"));
    }

    @Test
    public void binderFailureIsTerminalAndNeverResubmitsTheSameFlight() throws Exception {
        String client = read(ntkAckSourcePath("NtkAckBrowserClient.kt"));
        String dispatch = method(client,
                "private fun dispatchAckIfReady(pending: PendingFlight)",
                "private fun quiesce(pending: PendingFlight)");

        assertTrue(dispatch.contains("pending.ackSubmitted.compareAndSet(false, true)"));
        assertTrue(dispatch.contains("remote.startAck(pending.request, pending.callback)"));
        assertFalse(dispatch.contains("bindAndWarm()"));
        assertTrue(client.contains("pending.terminal.compareAndSet(false, true)"));
        assertFalse(client.contains("Thread.sleep"));
    }

    @Test
    public void coldAckNetworkOverlapsServiceWarmWithoutWeakeningProofVerification()
            throws Exception {
        String client = read(ntkAckSourcePath("NtkAckBrowserClient.kt"));
        String connected = method(client,
                "override fun onServiceConnected(name: ComponentName?, binder: IBinder?)",
                "override fun onServiceDisconnected(");
        int warm = connected.indexOf("requestWarm(remote)");
        int dispatch = connected.indexOf("flights.values.forEach(::dispatchAckIfReady)");
        assertTrue(warm >= 0);
        assertTrue(dispatch >= 0);
        assertTrue(dispatch < warm);

        String bind = method(client,
                "fun bindAndWarm()",
                "/** Creates or joins the one exact flight");
        assertFalse(bind.contains("service?.let(::requestWarm)"));

        String dispatchMethod = method(client,
                "private fun dispatchAckIfReady(pending: PendingFlight)",
                "private fun quiesce(pending: PendingFlight)");
        assertTrue(dispatchMethod.contains("remote.startAck(pending.request, pending.callback)"));
        assertFalse(dispatchMethod.contains("verifiedService"));
        assertTrue(client.contains("verifyPendingProofIfReady(pending)"));
        assertTrue(client.contains("NtkAckProofVerifier.verifyOrThrow("));

        String engine = read(ntkAckSourcePath("NtkAckBrowserEngine.kt"));
        String start = method(engine,
                "fun startAck(",
                "fun cancel(");
        int beginFlight = start.indexOf("requestKeyStore.beginFlight(created.identity)");
        int prerequisites = start.indexOf("startNetworkPrerequisites(created)");
        int browser = start.indexOf("ensureFullChallengeBrowser(created)");
        assertTrue(beginFlight >= 0 && prerequisites > beginFlight);
        assertTrue(browser > prerequisites);
        assertFalse(start.contains("createWebView("));

        String pageReady = method(engine,
                "private fun pageReady(created: WebView)",
                "private fun startNetworkPrerequisites(");
        assertTrue(pageReady.contains("markFlightShellReady(current.request.flightId"));
        assertFalse(pageReady.contains("workers.submit { runNetworkPrerequisites"));
        String shellReady = method(engine,
                "private fun markFlightShellReady(",
                "private fun startNetworkPrerequisites(");
        assertTrue(shellReady.contains("current.shellReady.compareAndSet(false, true)"));
        assertTrue(shellReady.contains("maybeRunGuardInWebView(current)"));

        String guard = method(engine,
                "private fun maybeRunGuardInWebView(current: Flight)",
                "private inner class FlightBridge");
        assertTrue(guard.contains("current.shellReady.get()"));
        assertTrue(guard.contains("current.guardProgramReady.get()"));
        assertTrue(guard.contains("current.guardStarted.compareAndSet(false, true)"));
    }

    @Test
    public void strictAckCancellationAndFirstWaveGuardHaveFailClosedOwnership() throws Exception {
        String transport = read(ntkAckSourcePath("NtkAckTransport.kt"));
        String execute = method(transport,
                "private fun execute(",
                "private fun validateRequest(");
        assertTrue(execute.indexOf("calls.register(call)") < execute.indexOf("call.execute().use"));
        assertTrue(transport.contains("private val calls = NtkAckCancellationRegistry<Call>"));
        assertTrue(transport.contains("calls.cancelAll()"));

        String engine = read(ntkAckSourcePath("NtkAckBrowserEngine.kt"));
        String prerequisites = method(engine,
                "private fun runNetworkPrerequisites(current: Flight)",
                "private fun ensureRequestKey(");
        int transportInstall = prerequisites.indexOf("current.transport = transport");
        int installRecheck = prerequisites.indexOf("checkActive(current)", transportInstall);
        int keyFuture = prerequisites.indexOf("val keyFuture:");
        int bundledGuard = prerequisites.indexOf("val executableGuard = loadBundledGuardPair()");
        int challengeJoin = prerequisites.indexOf("challengeFuture.get()");
        int trustedDecision = prerequisites.indexOf("if (challengeObject == null)");
        int canaryFuture = prerequisites.indexOf(
                "val canaryFuture: Future<NtkAckTransport.Result>");
        int metricJoin = prerequisites.indexOf("metricFutures.forEach");
        int canaryJoin = prerequisites.indexOf("canaryFuture.get()");
        assertTrue(transportInstall >= 0);
        assertTrue(installRecheck > transportInstall);
        assertTrue(keyFuture > installRecheck);
        assertTrue(bundledGuard > keyFuture);
        assertTrue(challengeJoin > bundledGuard);
        assertTrue(trustedDecision > challengeJoin);
        assertTrue(canaryFuture > trustedDecision);
        assertTrue(metricJoin > canaryFuture);
        assertTrue(canaryJoin > metricJoin);
        assertFalse(prerequisites.substring(canaryFuture).contains(
                "val canary = transport.postCanary(canaryBody)"));
        assertFalse(prerequisites.contains("transport.getGuardJavascript("));
        assertFalse(prerequisites.contains("transport.getGuardWasm("));
        assertTrue(prerequisites.contains("current.tasks.track("));

        String cancel = method(engine,
                "private fun cancelFlight(current: Flight, reasonCode: Int, stage: String)",
                "private fun failFlight(");
        assertTrue(cancel.contains("current.tasks.cancel()"));
        assertTrue(cancel.contains("requestKeyStore.invalidateFlight(current.identity)"));
        assertTrue(cancel.contains("quiesceCallback = null"));
        assertTrue(cancel.contains("state = State.EMPTY"));

        String keyStore = read(ntkAckSourcePath("NtkAckRequestKeyStore.kt"));
        assertTrue(keyStore.contains("private var activeIdentity: NtkAckFlightIdentity?"));
        assertTrue(keyStore.contains("check(activeIdentity == identity)"));
        assertTrue(keyStore.contains("check(activeIdentity == proofIdentity)"));
        assertTrue(keyStore.contains("fun invalidateFlight(identity: NtkAckFlightIdentity)"));
        assertTrue(keyStore.contains("SystemClock::elapsedRealtimeNanos"));
        assertFalse(keyStore.contains("System.nanoTime()"));
    }

    @Test
    public void strictDiscoveryStartsOnlyAfterTheCommittedEpisodeClick() throws Exception {
        String activity = read(activitySourcePath("EpisodeActivity.java"));
        String onCreate = method(activity,
                "protected void onCreate(Bundle savedInstanceState)",
                "private void ensureEpisodeFabControls(");
        assertFalse(onCreate.contains("startNtkEarlyViewerApiPrefetch("));
        assertFalse(onCreate.contains("bindAndWarm("));

        String click = method(activity,
                "private void enterPressedNtkEpisode(int adapterPosition, Manga selected)",
                "private String ntkTelemetryWorkId(");
        int telemetry = click.indexOf("ViewerTelemetry.viewerOpen(");
        int cancelRefresh = click.indexOf("cancelEpisodeRefresh();", telemetry);
        int cancelLoad = click.indexOf("episodeViewModel.cancelActiveLoad();", cancelRefresh);
        int discovery = click.indexOf("noteNtkForegroundViewer(selected);");
        int enter = click.indexOf("Utils.openColdExactNtkViewer(");
        assertTrue(telemetry >= 0);
        assertTrue(cancelRefresh > telemetry);
        assertTrue(cancelLoad > cancelRefresh);
        assertTrue(discovery > cancelLoad);
        assertTrue(enter > discovery);
        assertFalse(click.contains("enterNtkStrictForegroundNetwork("));
        int failedRetire = click.indexOf(
                "NtkStrictEpisodeDiscoveryCoordinator.retireViewerOwnership(", enter);
        int failedClose = click.indexOf(
                "ViewerTelemetry.viewerClosed(\"cold_activity_launch_failed\")", enter);
        assertTrue(failedRetire > enter);
        assertTrue(failedClose > failedRetire);
        assertFalse(click.contains("enterProgressiveRunway("));
        assertFalse(click.contains("ReaderPreparedStore"));

        String reader = read(activitySourcePath("ReaderV2Activity.kt"));
        assertFalse(reader.contains("viewerUseReaderCreateAsLaunchStartForTest"));
        String completed = method(reader,
                "private fun handleStrictRollingCompletedDraw(proof: ReaderSurfaceView.CompletedDrawProof)",
                "private fun prepareDeferredNtkAckChallenge(");
        assertTrue(completed.indexOf("strictTelemetryOwned") <
                completed.indexOf("val launchSeal = strictExactLaunchSeal"));
        assertTrue(completed.contains("strictTelemetryInitialBlankFrames++"));
        assertTrue(reader.contains("ViewerTelemetry.coverageSummary("));
        assertTrue(reader.contains("ViewerTelemetry.traversalSummary("));
        assertTrue(completed.contains("ViewerTelemetry.actualImageDrawCommittedForEpisode("));
        assertFalse(completed.contains("ViewerTelemetry.actualFramePresented("));

        String foreground = method(activity,
                "private void noteNtkForegroundViewer(Manga selected)",
                "private void warmupLikelyWfwfViewerPage(");
        int activeDemand = foreground.indexOf("ViewerTelemetry.isActiveEpisode(path)");
        int coldRolling = foreground.indexOf(".startColdRolling(getHttpClient(), selected)");
        int legacyPrefetch = foreground.indexOf("startNtkEarlyViewerApiPrefetch(");
        assertTrue(activeDemand >= 0);
        assertTrue(coldRolling > activeDemand);
        assertTrue(legacyPrefetch > coldRolling);
        assertTrue(foreground.substring(coldRolling, legacyPrefetch).contains("return;"));

        String preContent = method(activity,
                "private void startResumeNtkDiscoveryBeforeContent()",
                "private void startProvidedResumeNtkPayloadPrefetch(");
        int demandGuard = preContent.indexOf("isNtkUserDemandAuthorized(resumePath)");
        int forbiddenStart = preContent.indexOf("startNtkEarlyViewerApiPrefetch(");
        assertTrue(demandGuard >= 0);
        assertTrue(forbiddenStart > demandGuard);

        String coordinator = read(readerSourcePath("NtkStrictEpisodeDiscoveryCoordinator.kt"));
        String startInternal = method(coordinator,
                "private fun startInternal(",
                "private fun startIsolatedAck(");
        int committedDemandGuard = startInternal.indexOf("ViewerTelemetry.isActiveEpisode(ownerPath)");
        int networkAdmission = startInternal.indexOf(
                "enterForegroundNetworkIfNeeded(flight)", committedDemandGuard);
        int ackNetworkStart = startInternal.indexOf(
                "startAckNetworkPrerequisites(client, flight, path, route)", networkAdmission);
        int documentWorker = startInternal.indexOf("val worker = Thread(");
        assertTrue(committedDemandGuard >= 0 && networkAdmission > committedDemandGuard);
        assertTrue(ackNetworkStart > networkAdmission);
        assertTrue(documentWorker > ackNetworkStart);
        assertFalse(startInternal.contains("startIsolatedAck("));
        String ackPrerequisites = method(coordinator,
                "private fun startAckNetworkPrerequisites(",
                "private fun ensureIsolatedAck(");
        assertTrue(ackPrerequisites.contains("val thread = Thread(task"));
        assertTrue(ackPrerequisites.contains("ackRoute.attachDirectTrustedTask(task, thread)"));
        String foregroundEntry = method(coordinator,
                "private fun enterForegroundNetworkIfNeeded(",
                "private fun releaseAdjacentBodyGate(");
        int networkGate = foregroundEntry.indexOf("flight.client.enterNtkStrictForegroundNetwork(");
        int compatibilityCancel = foregroundEntry.indexOf(
                "flight.client.cancelNtkWebViewFallbacks()", networkGate);
        assertTrue(networkGate >= 0);
        assertTrue(compatibilityCancel > networkGate);
        String challengeFallback = method(coordinator,
                "private fun awaitDirectTrustedGrantOrStartIsolated(",
                "private fun runFlight(");
        int serverDecision = challengeFallback.indexOf(
                "cause is CustomHttpClient.NtkStrictFullChallengeRequiredException");
        int isolatedOwner = challengeFallback.indexOf(
                "ensureIsolatedAck(client, flight, ackRoute)");
        assertTrue(serverDecision >= 0 && isolatedOwner > serverDecision);
        assertTrue(coordinator.contains(
                "client.leaveNtkStrictForegroundNetwork(path, flight.viewerGeneration)"));

        String spool = read(readerSourcePath("NtkSourceSpoolRegistry.kt"));
        String begin = method(spool,
                "private fun beginDiscoveryInternal(",
                "fun observeQuarantineAssetEvidence(");
        int existingEntry = begin.indexOf("val current = entries[path]");
        int existingLeaseReturn = begin.indexOf("return@synchronized current.lease");
        int admissionChoice = begin.indexOf("val initialPage = if (rollingAdmission)");
        assertTrue(existingEntry >= 0);
        assertTrue(existingLeaseReturn > existingEntry);
        assertTrue(admissionChoice > existingLeaseReturn);
        assertTrue(begin.contains("rollingInitialPageIndexHint.coerceAtLeast(0)"));
    }

    @Test
    public void strictForegroundGateOwnsAndCancelsEveryPhysicalDomainProbe() throws Exception {
        String client = read(sourcePath("CustomHttpClient.java"));
        assertTrue(client.contains("private final Set<Call> activeNtkDomainProbeCalls"));

        String enter = method(client,
                "public void enterNtkStrictForegroundNetwork(",
                "public void leaveNtkStrictForegroundNetwork(");
        int publishOwner = enter.indexOf("ntkStrictForegroundNetworkGeneration = viewerGeneration;");
        int snapshot = enter.indexOf("new ArrayList<>(activeNtkDomainProbeCalls)");
        int cancel = enter.indexOf("probe.cancel();", snapshot);
        assertTrue(publishOwner >= 0 && snapshot > publishOwner && cancel > snapshot);

        String execute = method(client,
                "private Response executeNtkDomainProbe(Call call) throws IOException",
                "public static final class NtkImageTransportSnapshot");
        int firstOwnerCheck = execute.indexOf("hasNtkStrictForegroundNetworkOwner()");
        int register = execute.indexOf("activeNtkDomainProbeCalls.add(call)");
        int secondOwnerCheck = execute.indexOf(
                "hasNtkStrictForegroundNetworkOwner()", firstOwnerCheck + 1);
        int physicalExecute = execute.indexOf("return call.execute()");
        int unregister = execute.indexOf("activeNtkDomainProbeCalls.remove(call)");
        assertTrue(firstOwnerCheck >= 0 && register > firstOwnerCheck);
        assertTrue(secondOwnerCheck > register && physicalExecute > secondOwnerCheck);
        assertTrue(unregister > physicalExecute);

        String probes = method(client,
                "private boolean canReachNtkRoot(",
                "private void rememberReachableNtkRedirectRoot(");
        assertFalse(probes.contains("call.execute()"));
        assertFalse(probes.contains("unsafeCall.execute()"));
        assertTrue(probes.contains("executeNtkDomainProbe(call)"));
        assertTrue(occurrences(probes, "executeNtkDomainProbe(call)") >= 2);

        String fragmented = method(client,
                "private boolean canReachNtkDocumentTransportFragmented(",
                "private boolean canReachNtkDocumentTransportWithClient(");
        String withClient = method(client,
                "private boolean canReachNtkDocumentTransportWithClient(",
                "private void rememberReachableNtkRedirectRoot(");
        assertTrue(fragmented.indexOf("if(hasNtkStrictForegroundNetworkOwner())") >= 0);
        assertTrue(fragmented.lastIndexOf("if(hasNtkStrictForegroundNetworkOwner())") >
                fragmented.indexOf("catch(Exception e)"));
        assertTrue(withClient.lastIndexOf("if(hasNtkStrictForegroundNetworkOwner())") >
                withClient.indexOf("catch(Exception e)"));
    }

    @Test
    public void coldExactHandoffDropsPreparedImageAndPageHints() throws Exception {
        String store = read(activitySourcePath("ReaderLaunchPayloadStore.java"));
        String compact = method(store,
                "public static void attachCompactReaderPayload(",
                "public static void attachColdExactReaderPayload(");
        String cold = method(store,
                "public static void attachColdExactReaderPayload(",
                "/** Restores the compact payload");
        String restore = method(store,
                "public static Entry restoreCompactReaderPayload(",
                "private static String compactAuthoritativeNtkEpisodeMetadata(");
        int discardProcessEntry = cold.indexOf("intent.removeExtra(EXTRA_READER_KEY);");
        int dropImageHint = cold.indexOf("intent.removeExtra(EXTRA_MANGA_NTK_PAYLOAD_HINT);");
        int dropPageHint = cold.indexOf("intent.removeExtra(EXTRA_MANGA_NTK_IMAGE_COUNT);");
        assertTrue(discardProcessEntry >= 0);
        assertTrue(dropImageHint > discardProcessEntry);
        assertTrue(dropPageHint > dropImageHint);
        // The cold handoff drops prepared image content, but it must retain the one durable
        // current->next identity used only after the current native runway is complete.
        assertTrue(compact.contains("EXTRA_TITLE_RESUME_NEXT_PATH"));
        assertTrue(compact.contains("EXTRA_TITLE_RESUME_NEXT_ID"));
        assertTrue(compact.contains("EXTRA_TITLE_RESUME_NEXT_IMAGE_WORK_ID"));
        assertTrue(compact.contains("EXTRA_TITLE_RESUME_NEXT_IMAGE_EPISODE_ID"));
        assertTrue(compact.contains("EXTRA_TITLE_RESUME_NEXT_IMAGE_COUNT"));
        assertTrue(restore.contains("title.setResumeNtkNextEpisodeIdentity("));
        assertFalse(cold.contains("removeExtra(EXTRA_TITLE_RESUME_NEXT"));

        String telemetry = read(projectPath("src", "main", "java", "ml", "melun",
                "mangaview", "runtime", "ViewerTelemetry.java"));
        String committedDraw = method(telemetry,
                "public static void actualImageDrawCommitted(",
                "/** Called only when the native SurfaceControl path");
        assertTrue(committedDraw.contains("\"actual_image_draw_commit\""));
        assertTrue(committedDraw.contains("\"hwui_frame_commit\""));
    }

    @Test
    public void coldMacroUsesOneForwardOnlyColdLauncherStartWithoutResumeWarmPath()
            throws Exception {
        String macro = read(repositoryPath("macrobenchmark", "src", "main", "java", "ml",
                "melun", "mangaview", "macrobenchmark", "NtkColdViewerMacrobenchmark.kt"));
        assertTrue(macro.contains("startupMode = StartupMode.COLD"));
        assertTrue(occurrences(macro, "startActivityAndWait()") == 1);
        assertFalse(macro.contains("resumeExistingTaskFromLauncher("));
        assertFalse(macro.contains("FLAG_ACTIVITY_CLEAR_TASK"));
    }

    @Test
    public void adjacentRunwayPublishesSurfaceStructureBeforeDrawableCallbacks()
            throws Exception {
        String session = read(readerSourcePath("ReaderSession.kt"));
        String initial = method(session,
                "private fun appendResolvedEpisodeInitialRunway(",
                "private fun scheduleInitialAdjacentRunwayAppendRetry(");
        String remaining = method(session,
                "private fun appendRemainingAdjacentRunwayRefs(",
                "private fun refreshRemainingAdjacentRunwayRefs(");

        assertTrue(initial.indexOf("finishStructurePublish()") <
                initial.indexOf("listener.onPagesAppended(total)"));
        assertTrue(initial.indexOf("listener.onPagesAppended(total)") <
                initial.indexOf("commitAdjacentRunwayDrawableBatch(drawableBatch)"));
        assertTrue(remaining.indexOf("finishStructurePublish()") <
                remaining.indexOf("listener.onPagesAppended(total)"));
        assertTrue(remaining.indexOf("listener.onPagesAppended(total)") <
                remaining.indexOf("commitAdjacentRunwayDrawableBatch(drawableBatch)"));
    }

    @Test
    public void adjacentDrawablePublicationFencesForwardHistoryCompaction()
            throws Exception {
        String session = read(readerSourcePath("ReaderSession.kt"));
        String initial = method(session,
                "private fun appendResolvedEpisodeInitialRunway(",
                "private fun scheduleInitialAdjacentRunwayAppendRetry(");
        String remaining = method(session,
                "private fun appendRemainingAdjacentRunwayRefs(",
                "private fun refreshRemainingAdjacentRunwayRefs(");
        String trim = method(session,
                "private fun trimConsumedForwardHistory(",
                "private data class ForwardHistoryTrimCandidate(");

        assertTrue(initial.indexOf("beginAdjacentDrawableBatchPublication()") <
                initial.indexOf("commitAdjacentRunwayDrawableBatch(drawableBatch)"));
        assertTrue(initial.lastIndexOf("finishAdjacentDrawableBatchPublication()") >
                initial.indexOf("commitAdjacentRunwayDrawableBatch(drawableBatch)"));
        assertTrue(remaining.indexOf("beginAdjacentDrawableBatchPublication()") <
                remaining.indexOf("commitAdjacentRunwayDrawableBatch(drawableBatch)"));
        assertTrue(remaining.lastIndexOf("finishAdjacentDrawableBatchPublication()") >
                remaining.indexOf("commitAdjacentRunwayDrawableBatch(drawableBatch)"));
        assertTrue(trim.contains("if (isAdjacentDrawableBatchPublicationPending())"));
        assertTrue(occurrences(trim, "if (isAdjacentDrawableBatchPublicationPending())") >= 2);
        assertTrue(trim.indexOf("if (isAdjacentDrawableBatchPublicationPending())") <
                trim.indexOf("pages.subList(0, candidate.removeCount).clear()"));
    }

    @Test
    public void pixelMutationGapRateLimitDoesNotDiscardFrameMetricSamples() throws Exception {
        String surface = read(readerSourcePath("ReaderSurfaceView.kt"));
        String recorder = method(surface,
                "private fun recordPixelMutationFrameStats(",
                "private fun logActiveFrameStatsIfReady(");

        int firstAggregate = recorder.indexOf("statsMutationOldestToCallbackMs.add(");
        int rateLimit = recorder.indexOf(
                "lastPixelMutationGapLogMs < PIXEL_MUTATION_GAP_LOG_INTERVAL_MS");
        int suppressedReturn = recorder.indexOf("suppressedPixelMutationGapLogs++", rateLimit);
        assertTrue(firstAggregate >= 0);
        assertTrue(rateLimit > firstAggregate);
        assertTrue(suppressedReturn > rateLimit);
        assertTrue(recorder.substring(firstAggregate, rateLimit)
                .contains("statsMutationOldestToPostMs.add("));
    }

    @Test
    public void strictActualTelemetryRequiresExactlyOnePhysicalSubmissionProvenance()
            throws Exception {
        String surface = read(readerSourcePath("ReaderSurfaceView.kt"));
        String frameCommitted = method(surface,
                "private fun onFrameCommitted(",
                "private fun releasePostedAdmissionLocked(preserveDirty: Boolean)");
        assertTrue(surface.contains(
                "val registeredHwuiFrameCommitCallbackObserved: Boolean = false"));
        assertTrue(surface.contains(
                "val surfaceQueueSubmissionObserved: Boolean = false"));
        assertTrue(surface.contains(
                "val surfaceControlLatchObserved: Boolean = false"));
        assertTrue(frameCommitted.contains(
                "registeredHwuiFrameCommitCallbackObserved ="));
        assertTrue(frameCommitted.contains("surfaceQueueSubmissionObserved ="));
        assertTrue(frameCommitted.contains("surfaceControlLatchObserved ="));
        assertTrue(frameCommitted.contains("submission.callbackRegistered"));
        assertTrue(frameCommitted.contains("submission.surfaceQueueSubmission"));
        assertTrue(surface.contains("val surfaceLifecycleEpoch: Long = 0L"));
        assertTrue(frameCommitted.contains("surfaceLifecycleEpoch = epoch"));
        int lifecycleGuard = frameCommitted.indexOf(
                "isCompletedDrawProofLifecycleCurrent(");
        int listenerDelivery = frameCommitted.indexOf("listener?.onCompletedDraw(proof)");
        assertTrue(lifecycleGuard >= 0);
        assertTrue(listenerDelivery > lifecycleGuard);

        String policy = read(readerSourcePath("ReaderPipelinePolicy.kt"));
        String admission = method(policy,
                "fun isStrictCommittedFrameValid(",
                "/** Stable, fail-closed classification");
        assertTrue(admission.contains(
                "registeredHwuiFrameCommitCallbackObserved: Boolean"));
        assertTrue(admission.contains(
                "surfaceQueueSubmissionObserved: Boolean"));
        assertTrue(admission.contains(
                "surfaceControlLatchObserved: Boolean"));
        assertTrue(admission.contains(
                "hardwareAccelerated && registeredHwuiFrameCommitCallbackObserved"));
        assertTrue(admission.contains("surfaceQueueSubmissionObserved"));
        assertTrue(admission.contains("surfaceControlLatchObserved"));
        assertTrue(admission.contains(".count { it } == 1"));

        String reader = read(activitySourcePath("ReaderV2Activity.kt"));
        String completed = method(reader,
                "private fun handleStrictRollingCompletedDraw(proof: ReaderSurfaceView.CompletedDrawProof)",
                "private fun prepareDeferredNtkAckChallenge(");
        int provenance = completed.indexOf(
                "proof.registeredHwuiFrameCommitCallbackObserved");
        int surfaceProvenance = completed.indexOf(
                "proof.surfaceQueueSubmissionObserved");
        int surfaceControlProvenance = completed.indexOf(
                "proof.surfaceControlLatchObserved");
        int actualEvent = completed.indexOf(
                "ViewerTelemetry.actualImageDrawCommittedForEpisode(");
        assertTrue(provenance >= 0);
        assertTrue(surfaceProvenance > provenance);
        assertTrue(surfaceControlProvenance > surfaceProvenance);
        assertTrue(actualEvent > surfaceControlProvenance);
    }

    @Test
    public void nativeSurfaceFailureRetiresItsHandleBeforeDemandReattach() throws Exception {
        String surface = read(readerSourcePath("ReaderSurfaceView.kt"));
        String failure = method(surface,
                "fun onNtkRollingRendererFatal(reason: String)",
                "private fun completeRollingNativeRecovery(reason: String)");
        String completion = method(surface,
                "private fun completeRollingNativeRecovery(reason: String)",
                "private fun recoverDirectSurfaceSubmission(");

        int capture = failure.indexOf("retiredHandle = rollingNativeHandle");
        int retire = failure.indexOf("rollingNativeHandle = 0L", capture);
        int invalidate = failure.indexOf("clearFramePipeLocked(preserveDirty = true)", retire);
        int destroy = failure.indexOf("NtkRollingNativeBridge.nativeDestroy(retiredHandle)");
        assertTrue(capture >= 0 && retire > capture);
        assertTrue(invalidate > retire && destroy > invalidate);
        assertTrue(failure.contains("rollingNativeRecoveryPending = true"));
        assertTrue(completion.contains("rollingNativeRecoveryPending = false"));
        assertTrue(completion.contains("rollingNativeFatal = false"));
        assertTrue(completion.contains(
                "isAttachedToWindow && renderRunning && directSurfaceReady"));
        assertTrue(completion.contains(
                "attachRollingNativeSurface(reattach.first, reattach.second, reattach.third)"));
    }

    @Test
    public void demandedExactImageUsesOneOwnedTransportAndOriginalRelativeQuality()
            throws Exception {
        String client = read(sourcePath("CustomHttpClient.java"));
        String exactCall = method(client,
                "private final class NtkDemandBoundExactImageCall",
                "public OkHttpClient ntkForegroundImageFastClient()");
        assertTrue(exactCall.contains("implements Call, NtkQuicFetcher.RequestOwner"));
        assertTrue(exactCall.contains("getOrCreateNtkQuicEngine(baseUrl)"));
        assertTrue(exactCall.contains("fetchWithEngineExactOwned("));
        assertTrue(exactCall.contains("removeHeader(NTK_NO_QUIC_HEADER)"));
        assertTrue(client.contains("ntkDemandBoundExactImageFallbackClient ="));
        assertTrue(client.contains(".retryOnConnectionFailure(false)"));
        assertTrue(client.contains(".followRedirects(false)"));
        assertFalse(exactCall.contains("fetchNtkForegroundImageRace("));
        assertFalse(exactCall.contains("anchorHedge"));
        int engineSelection = exactCall.indexOf("getOrCreateNtkQuicEngine(baseUrl)");
        int fallbackSelection = exactCall.indexOf("if(engine == null || executor == null)");
        int engineStart = exactCall.indexOf("fetchWithEngineExactOwned(");
        assertTrue(engineSelection >= 0 && fallbackSelection > engineSelection);
        assertTrue(engineStart > fallbackSelection);
        assertFalse(exactCall.substring(engineStart).contains(
                "ntkDemandBoundExactImageFallbackClient.newCall"));

        String cache = read(readerSourcePath("ReaderImageCache.kt"));
        String route = method(cache,
                "fun resolveStrictSourceRoute(",
                "private fun strictInstrumentedClient(");
        assertTrue(route.contains("httpClient.ntkDemandBoundExactImageFactory()"));
        assertTrue(route.contains("ntk-demand-bound-exact-image"));

        String surface = read(readerSourcePath("ReaderSurfaceView.kt"));
        String quality = method(surface,
                "private fun minimumReadableSourceWidth(",
                "private fun usablePreparedTilePage(");
        assertTrue(quality.contains("Full quality is relative to the encoded original"));
        assertTrue(quality.contains("return if (viewportWidth > 0) 1 else Int.MAX_VALUE"));
        assertFalse(quality.contains("viewportWidth * MIN_READABLE_SOURCE_WIDTH_PERMILLE"));
    }

    @Test
    public void rollingActualProofIsRetiredAcrossPauseAndConfigurationChange() throws Exception {
        String reader = read(activitySourcePath("ReaderV2Activity.kt"));
        String pause = method(reader,
                "override fun onPause()",
                "override fun onResume()");
        assertTrue(pause.contains("strictTelemetryActualInLifecycle = false"));
        assertTrue(pause.contains("renderView.invalidateCommittedPresentationProof()"));
        assertTrue(pause.contains("renderView.contentDescription = null"));

        String rotate = method(reader,
                "override fun onConfigurationChanged(newConfig: Configuration)",
                "override fun onDestroy()");
        assertTrue(rotate.contains("strictTelemetryActualInLifecycle = false"));
        assertTrue(rotate.contains("renderView.invalidateCommittedPresentationProof()"));
        assertTrue(rotate.contains("renderView.contentDescription = null"));
    }

    @Test
    public void strictAdjacentEpisodeRotatesEveryAuthorityBeforeDiscovery() throws Exception {
        String reader = read(activitySourcePath("ReaderV2Activity.kt"));
        String launchAdjacent = method(reader,
                "private fun launchAdjacent(source: Manga, target: Manga, title: Title?, preparedKey: String? = null)",
                "private fun showEpisodePicker()");
        int rotate = launchAdjacent.indexOf("beginStrictAdjacentEpisodeTransition(");
        int discovery = launchAdjacent.indexOf("startStrictNtkDiscovery(target, \"adjacent_episode\")");
        assertTrue(rotate >= 0);
        assertTrue(discovery > rotate);
        assertTrue(launchAdjacent.contains("clearViewImmediately = true"));

        String transition = method(reader,
                "private fun beginStrictAdjacentEpisodeTransition(",
                "private fun handleStrictRollingCompletedDraw(");
        int oldSummary = transition.indexOf("publishStrictTelemetryBeforeClose()");
        int oldRetire = transition.indexOf(
                "NtkStrictEpisodeDiscoveryCoordinator.retireViewerOwnership(");
        int newGeneration = transition.indexOf("ViewerTelemetry.viewerOpen(");
        assertTrue(oldSummary >= 0 && oldRetire > oldSummary && newGeneration > oldRetire);
        assertTrue(transition.contains("activeReaderSessionGeneration.incrementAndGet()"));
        assertTrue(transition.contains("strictExactLaunchSeal = null"));
        assertTrue(transition.contains("session?.cancel()"));
        assertTrue(transition.contains("renderView.invalidateCommittedPresentationProof()"));
        assertTrue(transition.contains("renderView.setPageCount(0)"));
        assertTrue(transition.contains("strictTelemetryObservedSources = BooleanArray(0)"));

        String completed = method(reader,
                "private fun handleStrictRollingCompletedDraw(proof: ReaderSurfaceView.CompletedDrawProof)",
                "private fun prepareDeferredNtkAckChallenge(");
        assertTrue(completed.contains("launchSeal.matchesEpisodePath(strictTelemetryEpisodePath)"));
        assertFalse(completed.contains(
                "ViewerTelemetry.isActiveEpisode(strictTelemetryEpisodePath) ||"));
    }

    @Test
    public void strictColdSessionKeepsThePersistedResumeAnchor() throws Exception {
        String session = read(readerSourcePath("ReaderSession.kt"));
        String strictStart = method(session,
                "private fun startStrictExactColdSession()",
                "private fun failStrictExactColdSession(");

        assertTrue(strictStart.contains("requestedStartPage()"));
        assertTrue(strictStart.contains("StrictRollingAdmission.initial("));
        assertTrue(strictStart.contains("strictForwardSourceFloor"));
        assertTrue(strictStart.contains("currentViewportAnchor.set(installed)"));
        assertTrue(strictStart.contains("anchor = sourceIndex == installedSource"));
        assertFalse(strictStart.contains(
                "installImages(launchSeal.canonicalAssets, 0"));
    }

    @Test
    public void strictDiscoveryRetirementFencesEveryResponseAndPublication() throws Exception {
        String coordinator = read(readerSourcePath("NtkStrictEpisodeDiscoveryCoordinator.kt"));
        assertTrue(coordinator.contains("fun retireViewerOwnership("));
        assertTrue(coordinator.contains("flight.retirement.attachAckCancellation"));
        assertTrue(coordinator.contains("retirement.attachPhysicalCancellation"));
        assertTrue(coordinator.contains("physicalCalls.markCancelledAndDetachCalls()"));
        assertTrue(coordinator.contains("flight.retirement.attachWorker(worker)"));
        assertTrue(occurrences(coordinator, "client.fetchExactNtkEpisodeDocument(") >= 2);
        assertTrue(coordinator.contains("path,\n                                    flight.physicalCalls,"));
        assertTrue(coordinator.contains("path,\n                            flight.physicalCalls,"));
        assertTrue(coordinator.contains("requireDiscoveryOwnership(flight, \"document_response\")"));
        assertTrue(coordinator.contains("publishExactNtkEpisodeResponseCookies("));
        assertTrue(coordinator.contains("flight.physicalCalls"));
        assertTrue(coordinator.contains("requireDiscoveryOwnership(flight, \"signed_api_response\")"));
        assertTrue(coordinator.contains("withDiscoveryOwnership(flight, \"exact_manifest_install\")"));
        assertTrue(coordinator.contains("withOwnedAuthority(flight, authority, \"compatibility_mirror\")"));

        String retirement = method(coordinator,
                "fun retireViewerOwnership(",
                "private fun runFlight(");
        int detachLease = retirement.indexOf("retireDiscoveryForReplacement(");
        int removeFlight = retirement.indexOf("flights.remove(ownedPath, owned)");
        assertTrue(detachLease >= 0 && removeFlight > detachLease);

        String fence = read(readerSourcePath("NtkStrictDiscoveryRetirementFence.kt"));
        String retireFence = method(fence,
                "fun retire(expectedPath: String, expectedViewerGeneration: Long): Boolean",
                "private fun requireActive(");
        assertTrue(retireFence.contains("retired.set(true)"));
        assertTrue(retireFence.contains("physical?.invoke()"));
        assertTrue(retireFence.contains("worker?.interrupt()"));
        assertFalse(retireFence.contains("publicationLock"));

        String ackAidl = read(projectPath("src", "main", "aidl", "ml", "melun",
                "mangaview", "ntkack", "INtkAckBrowserService.aidl"));
        assertTrue(ackAidl.contains("oneway void cancel("));

        String spool = read(readerSourcePath("NtkSourceSpoolRegistry.kt"));
        assertTrue(spool.contains(
                "private val retiredEntries = ConcurrentHashMap<NtkDiscoveryLease, Entry>()"));
        assertTrue(spool.contains("fun retireDiscoveryForReplacement("));
        assertTrue(spool.contains("retiredEntries[lease] = entry"));
        assertTrue(spool.contains(
                "active?.takeIf { it.lease == lease } ?: retiredEntries[lease]"));

        String ownership = read(readerSourcePath("NtkStrictSourceOwnershipRegistry.kt"));
        assertTrue(ownership.contains("private data class RecordKey("));
        assertTrue(ownership.contains("private val records = LinkedHashMap<RecordKey, Record>()"));

        String reader = read(activitySourcePath("ReaderV2Activity.kt"));
        String destroy = method(reader, "override fun onDestroy()",
                "private fun publishStrictTelemetryBeforeClose()");
        int retire = destroy.indexOf("retireViewerOwnership(");
        int publish = destroy.indexOf("publishStrictTelemetryBeforeClose()");
        assertTrue(retire >= 0 && publish > retire);
    }

    @Test
    public void strictDecodeTelemetryIncludesSlugEpisodesNotOnlyNumericPaths() throws Exception {
        String session = read(readerSourcePath("ReaderSession.kt"));
        String telemetry = method(session,
                "private inline fun withStrictDecodeTelemetry(",
                "private fun decodePageBytesUnlocked(");
        assertTrue(telemetry.contains("strictRollingMatch"));
        assertTrue(telemetry.contains("activeTelemetryMatch"));
        assertTrue(telemetry.contains("ViewerTelemetry.imageDecodeStarted("));
        assertFalse(telemetry.contains("isProtectedNumericNtkPathShape("));

        String activity = read(activitySourcePath("ReaderV2Activity.kt"));
        String strictPath = method(activity,
                "private fun isStrictNtkEpisodePath(path: String?)",
                "private fun strictTelemetryWorkId(");
        assertTrue(strictPath.contains("[^/?#]+/[^/?#]+"));
    }

    @Test
    public void ntkLibraryRowsWarmOnlyVisibleContinueAndOneForwardEpisodeBeforeClick() throws Exception {
        String adapter = read(projectPath("src", "main", "java", "ml", "melun", "mangaview",
                "adapter", "TitleAdapter.java"));
        String ntkRow = method(adapter, "if(ntk) {", "int page = p.getViewerBookmark(manga);");
        assertFalse(ntkRow.contains("ReaderWarmupCoordinator"));
        assertFalse(ntkRow.contains("primeAuthoritativeNtkEpisode"));
        assertFalse(ntkRow.contains("claimAuthoritativeNtkEpisode"));

        String readiness = read(projectPath("src", "main", "java", "ml", "melun", "mangaview",
                "runtime", "ContinueReadinessCoordinator.java"));
        assertFalse(readiness.contains("primeAuthoritativeNtkEpisode"));
        String readinessPrime = method(readiness,
                "private static void prime(Context context, Manga manga, Title title, boolean visible, boolean force)",
                "private static Manga resumeManga(");
        assertTrue(readinessPrime.contains("ReaderWarmupCoordinator.primeVisible("));
        assertTrue(readinessPrime.contains("if(!isNtkContinue("));

        String warmup = read(readerSourcePath("ReaderWarmupCoordinator.kt"));
        String visibleForward = method(warmup,
                "private fun scheduleVisibleContinueWithForward(",
                "private fun forwardNextEpisode(");
        assertTrue(visibleForward.contains("prepareEntry(appContext, entry"));
        assertTrue(visibleForward.contains("forwardNextEpisode(entry.manga, entry.title)"));
        String forwardSelection = method(warmup,
                "private fun forwardNextEpisode(",
                "    fun primeImmediate(");
        assertTrue(forwardSelection.contains("current.nextEp()"));
        assertFalse(forwardSelection.contains("prevEp()"));

        String title = read(sourcePath("Title.java"));
        String parsedMetadata = method(title,
                "private void attachResumeNtkKpMetadataFromParsed(",
                "private static List<String> ntkTitleNextChunkPaths(");
        assertFalse(parsedMetadata.contains("startNtkEarlyViewerApiPrefetch("));

        String utils = read(projectPath("src", "main", "java", "ml", "melun", "mangaview",
                "Utils.java"));
        String episodeIntentMetadata = method(utils,
                "private static void rememberNtkResumeViewerMetadata(",
                "public static String primeNtkGeneratedPreparedHead(");
        assertFalse(episodeIntentMetadata.contains("getHttpClient("));
        assertFalse(episodeIntentMetadata.contains("startNtkEarlyViewerApiPrefetch("));
        assertFalse(episodeIntentMetadata.contains("primeNtkGeneratedPreparedHead("));

        String recycler = read(projectPath("src", "main", "java", "ml", "melun", "mangaview",
                "fragment", "RecyclerFragment.java"));
        String search = read(projectPath("src", "main", "java", "ml", "melun", "mangaview",
                "fragment", "MainSearch.java"));
        assertFalse(recycler.contains("claimNtkResumePress("));
        assertFalse(search.contains("claimNtkResumePress("));
    }

    @Test
    public void startupAndNativeExitNeverTriggerHiddenNtkWarmup() throws Exception {
        String ackClient = read(ntkAckSourcePath("NtkAckBrowserClient.kt"));
        String nativeExit = method(ackClient,
                "fun allowWarmAfterNativeExit()",
                "fun verifiedHello()");
        assertFalse(nativeExit.contains("bindAndWarm()"));
        assertFalse(nativeExit.contains("requestWarm("));
        assertTrue(nativeExit.contains("unbindService(connection)"));

        String manga = read(sourcePath("Manga.java"));
        String startupImageWarmup = method(manga,
                "public void startNtkStartupImagePrewarm(",
                "public static List<String> ntkViewerPayloadImageUrls(");
        assertFalse(startupImageWarmup.contains("startNtkEarlyViewerApiPrefetch("));
        assertFalse(startupImageWarmup.contains("startForegroundStreamFetch("));

        String utils = read(projectPath("src", "main", "java", "ml", "melun", "mangaview",
                "Utils.java"));
        String runwayPrewarm = method(utils,
                "public static String startNtkGeneratedInitialRunwayPrewarm(",
                "private static String startImmediateNtkGeneratedInitialPrime(");
        assertFalse(runwayPrewarm.contains("startImmediateNtkGeneratedInitialPrime("));
        assertFalse(runwayPrewarm.contains("startForegroundStreamFetch("));
    }

    private static String method(String source, String startToken, String endToken) {
        int start = source.indexOf(startToken);
        int end = source.indexOf(endToken, start + startToken.length());
        assertTrue(start >= 0);
        assertTrue(end > start);
        return source.substring(start, end);
    }

    private static int occurrences(String source, String token) {
        int count = 0;
        int cursor = 0;
        while((cursor = source.indexOf(token, cursor)) >= 0) {
            count++;
            cursor += token.length();
        }
        return count;
    }

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String readProductionDirectory(Path root) throws Exception {
        StringBuilder all = new StringBuilder();
        try(Stream<Path> paths = Files.walk(root)) {
            Iterator<Path> sources = paths
                    .filter(Files::isRegularFile)
                    .filter(value -> value.toString().endsWith(".java")
                            || value.toString().endsWith(".kt"))
                    .iterator();
            while(sources.hasNext()) {
                Path path = sources.next();
                all.append('\n').append(path).append('\n').append(read(path));
            }
        }
        return all.toString();
    }

    private static Path sourcePath(String name) {
        return projectPath("src", "main", "java", "ml", "melun", "mangaview",
                "mangaview", name);
    }

    private static Path readerSourcePath(String name) {
        return projectPath("src", "main", "java", "ml", "melun", "mangaview",
                "reader", name);
    }

    private static Path ntkAckSourcePath(String name) {
        return projectPath("src", "main", "java", "ml", "melun", "mangaview",
                "ntkack", name);
    }

    private static Path activitySourcePath(String name) {
        return projectPath("src", "main", "java", "ml", "melun", "mangaview",
                "activity", name);
    }

    private static Path repositoryPath(String... parts) {
        Path direct = Paths.get("");
        for(String part : parts)
            direct = direct.resolve(part);
        if(Files.exists(direct))
            return direct;
        Path parent = Paths.get("..");
        for(String part : parts)
            parent = parent.resolve(part);
        return parent;
    }

    private static Path projectPath(String... parts) {
        Path appRelative = Paths.get("app");
        for(String part : parts)
            appRelative = appRelative.resolve(part);
        if(Files.exists(appRelative))
            return appRelative;
        Path direct = Paths.get("");
        for(String part : parts)
            direct = direct.resolve(part);
        return direct;
    }
}
