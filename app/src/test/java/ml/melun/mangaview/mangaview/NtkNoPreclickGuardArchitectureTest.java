package ml.melun.mangaview.mangaview;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Structural regression gates for strict cold discovery guard ownership. */
public final class NtkNoPreclickGuardArchitectureTest {
    @Test
    public void titleRscAndViewerPayloadCallSitesAreGuardMetadataOnly() throws Exception {
        String client = read(sourcePath("CustomHttpClient.java"));

        assertTrue(client.contains(
                "rememberNtkGuardVersionMetadata(normalized, body, \"viewer-document\")"));
        assertTrue(client.contains(
                "rememberNtkGuardVersionMetadata(normalized, body, \"rsc\")"));
        assertTrue(client.contains(
                "rememberNtkGuardVersionMetadata(cookiePath, viewerBody, \"viewer-api-input\")"));
        assertFalse(client.contains("NtkWebViewFallbackManager.prefetchExactGuardPair("));

        String metadataOnly = method(client,
                "private String rememberNtkGuardVersionMetadata(",
                "public void rememberNtkViewerPageFromWebView(");
        assertTrue(metadataOnly.contains("rememberGuardVersionFromText(context, body)"));
        assertTrue(metadataOnly.contains("guardNetworkStarted=false"));
        assertFalse(metadataOnly.contains("NtkQuicBridge"));
        assertFalse(metadataOnly.contains("fetchNtkQuic"));
        assertFalse(metadataOnly.contains("prefetchExactGuardPair"));
    }

    @Test
    public void sharedManagerHasNoEagerGuardPairCallSite() throws Exception {
        String manager = read(sourcePath("NtkWebViewFallbackManager.java"));

        // One occurrence is the dormant declaration. Any second occurrence is a production call.
        assertEquals(1, occurrences(manager, "prefetchExactGuardPair("));
        assertTrue(manager.contains("ntk_webview_guard_version_metadata_only"));
        assertTrue(manager.contains("guardNetworkStarted=false"));
        assertFalse(method(manager,
                "private void fetchViewerImageUrlsOnMain(",
                "private static String ntkGuardVersionFromText(")
                .contains("prefetchExactGuardPair("));
    }

    @Test
    public void metadataExtractionRemainsAvailableToCommittedClickAck() throws Exception {
        String manager = read(sourcePath("NtkWebViewFallbackManager.java"));
        String parser = method(manager,
                "private static String ntkGuardVersionFromText(",
                "private void evaluateViewerImageFetchScript(");
        String remember = method(manager,
                "static String rememberGuardVersionFromText(",
                "static String guardVersionFromTextForTest(");

        assertTrue(parser.contains("wv="));
        assertTrue(parser.contains("URLDecoder.decode"));
        assertTrue(parser.contains("b\\\\d{13}-wasm-\\\\d{13}"));
        assertTrue(remember.contains("ntkGuardVersionFromText(text)"));
        assertTrue(remember.contains("rememberGuardVersion(context, version)"));
    }

    @Test
    public void strictGuardNetworkOwnerRemainsTheIsolatedAckTransport() throws Exception {
        String transport = read(ntkAckSourcePath("NtkAckTransport.kt"));
        String coordinator = read(readerSourcePath("NtkStrictEpisodeDiscoveryCoordinator.kt"));

        assertTrue(transport.contains(
                "getGuard(Purpose.GUARD_JS, \"/api/ad/guard-js\""));
        assertTrue(transport.contains(
                "getGuard(Purpose.GUARD_WASM, \"/api/ad/guard-wasm\""));
        String guard = method(transport,
                "private fun getGuard(",
                "private fun execute(");
        int directStatic = guard.indexOf("\"$origin$redirectPath?v=$encodedVersion\"");
        int legacyEndpoint = guard.indexOf("\"$origin$endpoint?v=$encodedVersion\"");
        assertTrue(directStatic >= 0);
        assertTrue(legacyEndpoint > directStatic);
        assertTrue(guard.substring(directStatic, legacyEndpoint)
                .contains("allowGuardStatic = true"));
        assertTrue(guard.contains("direct.status == 200 && direct.body.isNotEmpty()"));
        assertTrue(coordinator.contains(
                "NtkAckBrowserClient.get(client.context).startAck("));
        assertTrue(coordinator.contains("ackHandle.joinProof()"));
    }

    @Test
    public void imageOriginPreparationIsAbsentAndOnlyDemandedImagesOpenCdnConnections() throws Exception {
        String client = read(sourcePath("CustomHttpClient.java"));
        String coordinator = read(readerSourcePath("NtkStrictEpisodeDiscoveryCoordinator.kt"));

        assertTrue(coordinator.contains(
                "requireDiscoveryOwnership(flight, \"worker_start\")"));
        assertFalse(client.contains("prepareNtkImageOriginsAfterViewerClick"));
        assertFalse(client.contains("ntk_viewer_click_origin_prepare"));
        assertFalse(coordinator.contains("prepareNtkImageOriginsAfterViewerClick"));
    }

    @Test
    public void guardFuturesExistOnlyAfterAValidatedFullChallengeDecision() throws Exception {
        String engine = read(ntkAckSourcePath("NtkAckBrowserEngine.kt"));
        String prerequisites = method(engine,
                "private fun runNetworkPrerequisites(",
                "private fun startFullChallengeGuardPair(");

        int trustedDecision = prerequisites.indexOf("if (challengeObject == null)");
        int trustedReturn = prerequisites.indexOf("return", trustedDecision);
        int fullPairStart = prerequisites.indexOf(
                "startFullChallengeGuardPair(current, transport, version)");
        assertTrue(trustedDecision >= 0);
        assertTrue(trustedReturn > trustedDecision);
        assertTrue(fullPairStart > trustedReturn);

        // The pre-decision path may create only key/challenge work. In particular, a trusted
        // response cannot construct a guard future or call either guard transport operation.
        String beforeFullDecision = prerequisites.substring(0, fullPairStart);
        assertFalse(beforeFullDecision.contains("getGuardJavascript("));
        assertFalse(beforeFullDecision.contains("getGuardWasm("));
        assertFalse(beforeFullDecision.contains("FullChallengeGuardFutures("));

        String fullPair = method(engine,
                "private fun startFullChallengeGuardPair(",
                "private fun completeTrustedServerGrant(");
        assertEquals(1, occurrences(fullPair, "transport.getGuardJavascript(version)"));
        assertEquals(1, occurrences(fullPair, "transport.getGuardWasm(version)"));
        assertEquals(2, occurrences(fullPair, "workers.submit<NtkAckTransport.Result>"));
        assertEquals(2, occurrences(fullPair, "current.tasks.track("));
        assertTrue(prerequisites.contains("guardFutures.javascript.get()"));
        assertTrue(prerequisites.contains("guardFutures.wasm.get()"));
    }

    @Test
    public void trustedGrantProofKeepsCumulativeResponseBackedControlCookies() throws Exception {
        String engine = read(ntkAckSourcePath("NtkAckBrowserEngine.kt"));
        String trusted = method(engine,
                "private fun completeTrustedServerGrant(",
                "private fun ensureRequestKey(");

        assertTrue(trusted.contains("cumulativeResponseGrants = transport.cookieGrants()"));
        assertFalse(trusted.contains(
                "cumulativeResponseGrants = confirmationResponse.responseGrantCookies"));
    }

    private static int occurrences(String source, String token) {
        int count = 0;
        int offset = 0;
        while((offset = source.indexOf(token, offset)) >= 0) {
            count++;
            offset += token.length();
        }
        return count;
    }

    private static String method(String source, String startToken, String endToken) {
        int start = source.indexOf(startToken);
        int end = source.indexOf(endToken, start + startToken.length());
        assertTrue(start >= 0);
        assertTrue(end > start);
        return source.substring(start, end);
    }

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
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
