package ml.melun.mangaview.mangaview;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.Protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CustomHttpClientTest {
    @Test
    public void ntkCellularAlwaysUsesResilientTlsTransport() {
        assertTrue(CustomHttpClient.shouldUseNtkCellularResilientTransportForTest(
                true, false));
        assertTrue(CustomHttpClient.shouldUseNtkCellularResilientTransportForTest(
                true, true));
    }

    @Test
    public void ntkNonCellularRoutesKeepNormalFastTlsTransport() {
        assertFalse(CustomHttpClient.shouldUseNtkCellularResilientTransportForTest(
                false, false));
        assertFalse(CustomHttpClient.shouldUseNtkCellularResilientTransportForTest(
                false, true));
    }

    @Test
    public void ntkCellularPrefersDohOnlyForProtectedHosts() {
        assertTrue(CustomHttpClient.shouldPreferNtkDohBeforeSystemForTest(
                "sbxh5.com", true, false));
        assertFalse(CustomHttpClient.shouldPreferNtkDohBeforeSystemForTest(
                "sbxh5.com", false, false));
        assertTrue(CustomHttpClient.shouldPreferNtkDohBeforeSystemForTest(
                "sbxh5.com", true, true));
        assertFalse(CustomHttpClient.shouldPreferNtkDohBeforeSystemForTest(
                "example.com", true, false));
    }

    @Test
    public void ntkCellularFreshCacheTrustsOnlyDohAnswers() {
        assertTrue(CustomHttpClient.isCellularTrustedNtkDnsSourceForTest("doh"));
        assertFalse(CustomHttpClient.isCellularTrustedNtkDnsSourceForTest("system"));
        assertFalse(CustomHttpClient.isCellularTrustedNtkDnsSourceForTest(null));
    }

    @Test
    public void ntkStrictHttpEngineIsSkippedForCellularWithOrWithoutVpn() {
        assertFalse(CustomHttpClient.shouldUseSharedHttpEngineForStrictNetworkForTest(
                "document", true, false));
        assertFalse(CustomHttpClient.shouldUseSharedHttpEngineForStrictNetworkForTest(
                "signed_image_api", true, false));
        assertTrue(CustomHttpClient.shouldUseSharedHttpEngineForStrictNetworkForTest(
                "document", false, false));
        assertFalse(CustomHttpClient.shouldUseSharedHttpEngineForStrictNetworkForTest(
                "document", true, true));
    }

    @Test
    public void ntkExactImagesSkipHttpEngineForCellularWithOrWithoutVpn() {
        assertFalse(CustomHttpClient.shouldUseNtkExactImageHttpEngineForNetworkForTest(
                false, true, false));
        assertFalse(CustomHttpClient.shouldUseNtkExactImageHttpEngineForNetworkForTest(
                false, true, true));
        assertTrue(CustomHttpClient.shouldUseNtkExactImageHttpEngineForNetworkForTest(
                false, false, false));
        assertFalse(CustomHttpClient.shouldUseNtkExactImageHttpEngineForNetworkForTest(
                true, false, false));
    }

    @Test
    public void targetedTlsFragmentationFindsExactSniHostname() throws Exception {
        byte[] hello = clientHelloForHost("sbxh5.com");
        assertEquals("sbxh5.com", CustomHttpClient.tlsClientHelloSniHostForTest(hello));
        hello[0] = 0x17;
        assertNull(CustomHttpClient.tlsClientHelloSniHostForTest(hello));
    }

    @Test
    public void transportFailuresAreNotInventedCloudflareChallenges() {
        assertFalse(CustomHttpClient.shouldMarkNtkChallengeForTransportFailureForTest(
                new javax.net.ssl.SSLPeerUnverifiedException("hostname not verified")));
        assertFalse(CustomHttpClient.shouldMarkNtkChallengeForTransportFailureForTest(
                new java.net.ConnectException("connection refused")));
        assertFalse(CustomHttpClient.shouldMarkNtkChallengeForTransportFailureForTest(
                new java.net.SocketTimeoutException("timeout")));
        assertFalse(CustomHttpClient.shouldMarkNtkChallengeForTransportFailureForTest(
                new Exception("ERR_CERT_COMMON_NAME_INVALID")));
    }

    private static byte[] clientHelloForHost(String hostname) throws Exception {
        byte[] host = hostname.getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream extensions = new ByteArrayOutputStream();
        writeU16(extensions, 0);
        writeU16(extensions, 5 + host.length);
        writeU16(extensions, 3 + host.length);
        extensions.write(0);
        writeU16(extensions, host.length);
        extensions.write(host);

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(new byte[]{0x03, 0x03});
        body.write(new byte[32]);
        body.write(0);
        writeU16(body, 2);
        body.write(new byte[]{0x13, 0x01});
        body.write(1);
        body.write(0);
        writeU16(body, extensions.size());
        extensions.writeTo(body);

        ByteArrayOutputStream handshake = new ByteArrayOutputStream();
        handshake.write(1);
        writeU24(handshake, body.size());
        body.writeTo(handshake);

        ByteArrayOutputStream record = new ByteArrayOutputStream();
        record.write(new byte[]{0x16, 0x03, 0x01});
        writeU16(record, handshake.size());
        handshake.writeTo(record);
        return record.toByteArray();
    }

    private static void writeU16(ByteArrayOutputStream out, int value) {
        out.write((value >>> 8) & 0xff);
        out.write(value & 0xff);
    }

    private static void writeU24(ByteArrayOutputStream out, int value) {
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write(value & 0xff);
    }

    @Test
    public void routeRecoveryClearsOnlyChallengeFromFailedOrigin() {
        assertTrue(CustomHttpClient.sameOriginForRouteRecoveryForTest(
                "https://sbxh9.com", "https://sbxh9.com/manhwa"));
        assertTrue(CustomHttpClient.sameOriginForRouteRecoveryForTest(
                "https://www.sbxh9.com/", "https://sbxh9.com/api/manhwa-list"));
        assertFalse(CustomHttpClient.sameOriginForRouteRecoveryForTest(
                "https://sbxh9.com", "https://newtoki1.org/manhwa"));
        assertFalse(CustomHttpClient.sameOriginForRouteRecoveryForTest(
                "http://sbxh9.com", "https://sbxh9.com/manhwa"));
    }

    @Test
    public void rscServerErrorsAreRouteFailuresNotCaptchaChallenges() {
        assertTrue(CustomHttpClient.isNtkRscRouteFailureForTest(502, null));
        assertTrue(CustomHttpClient.isNtkRscRouteFailureForTest(503, null));
        assertFalse(CustomHttpClient.isNtkRscRouteFailureForTest(403, null));
        assertFalse(CustomHttpClient.isNtkRscRouteFailureForTest(404, null));
        assertFalse(CustomHttpClient.isNtkRscRouteFailureForTest(200, null));
        assertFalse(CustomHttpClient.isCloudflareChallengeForTest(
                502, "<title>502 Bad Gateway | cloudflare</title>"));
    }

    @Test
    public void interruptedRequestsAreExpectedCancellation() {
        assertTrue(CustomHttpClient.isInterruptedRequestForTest(new InterruptedException()));
        assertTrue(CustomHttpClient.isInterruptedRequestForTest(new InterruptedIOException()));
        assertTrue(CustomHttpClient.isInterruptedRequestForTest(new Exception("Canceled")));
        assertFalse(CustomHttpClient.isInterruptedRequestForTest(new Exception("Request failed")));
    }

    @Test
    public void activePageLoadWaitsOnlyWithoutStaleCache() {
        assertTrue(CustomHttpClient.shouldWaitForActivePageLoadForTest(false));
        assertFalse(CustomHttpClient.shouldWaitForActivePageLoadForTest(true));
    }

    @Test
    public void pageCacheFreshnessRejectsExpiredAndFutureEntries() {
        long now = 10_000L;
        long ttl = 1_000L;

        assertTrue(CustomHttpClient.isPageCacheFreshForTest(now - 999L, now, ttl));
        assertFalse(CustomHttpClient.isPageCacheFreshForTest(now - 1001L, now, ttl));
        assertFalse(CustomHttpClient.isPageCacheFreshForTest(now + 1L, now, ttl));
    }

    @Test
    public void ntkTlsFallbackUsesHttp1Only() {
        assertEquals(1, CustomHttpClient.ntkTlsFallbackProtocolsForTest().size());
        assertEquals(Protocol.HTTP_1_1, CustomHttpClient.ntkTlsFallbackProtocolsForTest().get(0));
    }

    @Test
    public void imageClientUsesWiderDispatcherThanPageClient() {
        assertTrue(CustomHttpClient.imageDispatcherIsWiderForTest());
        assertEquals(16, CustomHttpClient.imageDispatcherMaxRequestsForTest());
        assertEquals(8, CustomHttpClient.imageDispatcherMaxRequestsPerHostForTest());
        assertTrue(CustomHttpClient.imageClientsShareDispatcherForTest());
    }

    @Test
    public void imageSingleFlightKeyIsStableWithoutRetainingCookieOrder() {
        String url = "https://cdn.example.test/webtoon/1/2/p001.webp";
        String first = CustomHttpClient.ntkImageFlightKeyForTest(url, "b=2; a=1");
        String reordered = CustomHttpClient.ntkImageFlightKeyForTest(url, "a=1;b=2");

        assertEquals(first, reordered);
        assertEquals(64, first.length());
        assertFalse(first.contains(url));
    }

    @Test
    public void strictDocumentKeepsFastInactivityBoundsWithoutAProgressKillingCallDeadline() {
        assertEquals(3_500, CustomHttpClient.strictNtkDocumentConnectTimeoutMsForTest());
        assertEquals(3_500, CustomHttpClient.strictNtkDocumentReadTimeoutMsForTest());
        assertEquals(0, CustomHttpClient.strictNtkDocumentCallTimeoutMsForTest());
    }

    @Test
    public void clientHintsFollowDesktopUserAgentShape() {
        String desktop = CustomHttpClient.NTK_DESKTOP_DOCUMENT_UA;

        assertTrue(CustomHttpClient.isDesktopUserAgent(desktop));
        assertEquals("?0", CustomHttpClient.clientHintMobile(desktop));
        assertEquals("\"Windows\"", CustomHttpClient.clientHintPlatform(desktop));
        assertTrue(CustomHttpClient.clientHintUa(desktop).contains("Google Chrome"));
        assertFalse(CustomHttpClient.clientHintUa(desktop).contains("Android WebView"));
    }

    @Test
    public void clientHintsKeepAndroidShapeForMobileUserAgent() {
        String mobile = "Mozilla/5.0 (Linux; Android 15; Pixel) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

        assertFalse(CustomHttpClient.isDesktopUserAgent(mobile));
        assertEquals("?1", CustomHttpClient.clientHintMobile(mobile));
        assertEquals("\"Android\"", CustomHttpClient.clientHintPlatform(mobile));
        assertTrue(CustomHttpClient.clientHintUa(mobile).contains("Google Chrome"));
        assertFalse(CustomHttpClient.clientHintUa(mobile).contains("Android WebView"));
    }

    @Test
    public void pageAndImageClientsShareConnectionPool() {
        assertTrue(CustomHttpClient.clientsShareConnectionPoolForTest());
    }

    @Test
    public void strictControlPlaneReusesExistingSameOriginHttpEngineSession() {
        assertTrue(CustomHttpClient.shouldUseSharedHttpEngineForStrictStageForTest(
                "nv_issue"));
        assertTrue(CustomHttpClient.shouldUseSharedHttpEngineForStrictStageForTest(
                "signed_image_api"));
        assertTrue(CustomHttpClient.shouldUseSharedHttpEngineForStrictStageForTest(
                "document"));
        assertTrue(CustomHttpClient.shouldUseSharedHttpEngineForStrictStageForTest(
                "unsigned_webtoon_image_api"));
        assertTrue(CustomHttpClient.shouldUseSharedHttpEngineForStrictStageForTest(
                "trusted_challenge"));
    }

    @Test
    public void strictControlPlanePercentEncodesUnicodeEpisodePathHeaders() {
        assertEquals(
                "/webtoon/%EA%BB%8D%EB%8D%B0%EA%B8%B0-%EB%84%A4%EC%9D%B4%EB%B2%84/1040593",
                CustomHttpClient.ntkNativeAckScopePathForTest(
                        "/webtoon/껍데기-네이버/1040593"));
    }

    @Test
    public void restoredClearanceIsAppliedOnlyWhenFreshAndChanged() {
        long now = 10_000L;

        assertFalse(CustomHttpClient.shouldApplyRestoredClearanceForTest("token", "token", now + 1_000L, now));
        assertFalse(CustomHttpClient.shouldApplyRestoredClearanceForTest("", "token", now, now));
        assertFalse(CustomHttpClient.shouldApplyRestoredClearanceForTest("", "", now + 1_000L, now));
        assertTrue(CustomHttpClient.shouldApplyRestoredClearanceForTest("", "token", now + 1_000L, now));
        assertTrue(CustomHttpClient.shouldApplyRestoredClearanceForTest("old", "token", now + 1_000L, now));
    }

    @Test
    public void webViewCookieHeaderParserAvoidsRegexSplitting() {
        Map<String, String> cookies = CustomHttpClient.parseCookieHeaderForTest(
                " session=one ; cf_clearance=abc=def\n theme=dark\r invalid ");

        assertEquals("one", cookies.get("session"));
        assertEquals("abc=def", cookies.get("cf_clearance"));
        assertEquals("dark", cookies.get("theme"));
        assertFalse(cookies.containsKey("invalid"));
    }

    @Test
    public void wolfDocumentDomainResolveRunsOnlyBeforeNetworkMiss() {
        assertTrue(CustomHttpClient.shouldResolveWolfDocumentBeforeNetworkForTest(true, false,
                CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW));
        assertTrue(CustomHttpClient.shouldResolveWolfDocumentBeforeNetworkForTest(true, false,
                CustomHttpClient.FetchMode.DIRECT_ONLY));
        assertFalse(CustomHttpClient.shouldResolveWolfDocumentBeforeNetworkForTest(true, true,
                CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW));
        assertFalse(CustomHttpClient.shouldResolveWolfDocumentBeforeNetworkForTest(true, false,
                CustomHttpClient.FetchMode.CACHE_ONLY));
        assertFalse(CustomHttpClient.shouldResolveWolfDocumentBeforeNetworkForTest(false, false,
                CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW));
    }

    @Test
    public void wfwfDocumentsResolveBeforeNetworkMiss() {
        assertTrue(CustomHttpClient.shouldResolveWfwfBeforeCachedPageForTest("/search.html?q=onepunch", false,
                CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW));
        assertTrue(CustomHttpClient.shouldResolveWfwfBeforeCachedPageForTest("/cm?type1=genre", false,
                CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW));
        assertTrue(CustomHttpClient.shouldResolveWfwfBeforeCachedPageForTest("/cl?toon=10007", false,
                CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW));
        assertFalse(CustomHttpClient.shouldResolveWfwfBeforeCachedPageForTest("/cl?toon=10007", true,
                CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW));
        assertTrue(CustomHttpClient.shouldResolveWfwfBeforeCachedPageForTest("/api/unknown", false,
                CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW));
    }

    @Test
    public void wfwfForcedDomainRetryCoversEpisodesAndSearch() {
        assertTrue(CustomHttpClient.shouldForceResolveWfwfOnRetryForTest("/cl?toon=10007"));
        assertTrue(CustomHttpClient.shouldForceResolveWfwfOnRetryForTest("/cv?toon=10007&num=1"));
        assertTrue(CustomHttpClient.shouldForceResolveWfwfOnRetryForTest("/search.html?q=onepunch"));
        assertTrue(CustomHttpClient.shouldForceResolveWfwfOnRetryForTest("/cm?type1=genre"));
    }

    @Test
    public void wfwfSearchRetriesAfterDomainResolve() {
        assertEquals(2, CustomHttpClient.pageNetworkAttemptsForTest(false, "/search.html?q=onepunch"));
        assertEquals(1, CustomHttpClient.pageNetworkAttemptsForTest(false, "/cm?type1=genre"));
        assertEquals(2, CustomHttpClient.pageNetworkAttemptsForTest(false, "/cl?toon=10007"));
        assertEquals(1, CustomHttpClient.pageNetworkAttemptsForTest(true, "/api/manhwa-list"));
    }

    @Test
    public void ntkChallengeAbortsPageRetryImmediately() {
        assertTrue(CustomHttpClient.shouldAbortNtkPageRetryForTest(
                true, new Exception("Request failed"), true));
        assertTrue(CustomHttpClient.shouldAbortNtkPageRetryForTest(
                true, new Exception("Cloudflare challenge"), false));
        assertFalse(CustomHttpClient.shouldAbortNtkPageRetryForTest(
                false, new Exception("Cloudflare challenge"), true));
        assertFalse(CustomHttpClient.shouldAbortNtkPageRetryForTest(
                true, new ConnectException("timeout"), false));
    }

    @Test
    public void ntkDiagnosticsOfferWarpWhenSniBlockedWithoutVpn() {
        String report = "network: cellular,validated=true,internet=true\n"
                + "vpn_active: false\n"
                + "active_site: NTK\n"
                + "app_dns_sbxh8.com: ok 12ms 138.199.46.65\n"
                + "ntk_quic_sni: code=0,ms=501,error=NetworkExceptionWrapper(net_error=-100)\n"
                + "ntk_api_direct: fail 4001ms SocketException(Connection reset)\n";

        assertTrue(CustomHttpClient.shouldOfferWarpAssistForDiagnosticReportForTest(report));
    }

    @Test
    public void ntkDiagnosticsDoNotOfferWarpWhenVpnAlreadyActive() {
        String report = "network: cellular,vpn,validated=true,internet=true\n"
                + "vpn_active: true\n"
                + "active_site: NTK\n"
                + "app_dns_sbxh8.com: ok 12ms 138.199.46.65\n"
                + "ntk_quic_sni: code=0,ms=501,error=NetworkExceptionWrapper(net_error=-100)\n";

        assertFalse(CustomHttpClient.shouldOfferWarpAssistForDiagnosticReportForTest(report));
    }

    @Test
    public void ntkMovedApiFallbackSkipsBlockedRetryResponses() {
        assertTrue(CustomHttpClient.isBlockedNtkMovedApiRetryResultForTest(451, ""));
        assertTrue(CustomHttpClient.isBlockedNtkMovedApiRetryResultForTest(200,
                "<html>warninge.kcopa.or.kr 접속차단 안내 문화체육관광</html>"));
        assertTrue(CustomHttpClient.isBlockedNtkMovedApiRetryResultForTest(403,
                "<html>Verifying you are human. Cloudflare security service.</html>"));
        assertFalse(CustomHttpClient.isBlockedNtkMovedApiRetryResultForTest(404,
                "{\"error\":\"not_found\"}"));
    }

    @Test
    public void ntkMovedApiRedirectFollowsOnlyNtkApiLocations() {
        assertEquals("https://sbxh8.com/api/ad/challenge/",
                CustomHttpClient.resolveNtkMovedApiRedirectUrlForTest(301,
                        "/api/ad/challenge/", "https://sbxh8.com/api/ad/challenge"));
        assertEquals("https://sbxh9.com/api/ad/challenge",
                CustomHttpClient.resolveNtkMovedApiRedirectUrlForTest(302,
                        "https://sbxh9.com/api/ad/challenge",
                        "https://sbxh8.com/api/ad/challenge"));
        assertNull(CustomHttpClient.resolveNtkMovedApiRedirectUrlForTest(200,
                "/api/ad/challenge/", "https://sbxh8.com/api/ad/challenge"));
        assertNull(CustomHttpClient.resolveNtkMovedApiRedirectUrlForTest(301,
                "https://example.com/api/ad/challenge",
                "https://sbxh8.com/api/ad/challenge"));
        assertNull(CustomHttpClient.resolveNtkMovedApiRedirectUrlForTest(301,
                "/login", "https://sbxh8.com/api/ad/challenge"));
    }

    @Test
    public void ntkOfficialAddressRedirectIsRecognized() {
        assertTrue(CustomHttpClient.isNtkOfficialAddressRedirectForTest("https://t.me/s/newtoki_url"));
        assertTrue(CustomHttpClient.isNtkOfficialAddressRedirectForTest("https://t.me/newtoki_url?before=1"));
        assertFalse(CustomHttpClient.isNtkOfficialAddressRedirectForTest("https://t.me/s/other_channel"));
        assertFalse(CustomHttpClient.isNtkOfficialAddressRedirectForTest("https://sbxh8.com/api/ad/challenge/"));
        assertFalse(CustomHttpClient.isNtkOfficialAddressRedirectForTest(null));
    }

    @Test
    public void ntkMovedApiRedirectCanTryTrailingSlashWhenLocationMissing() {
        assertEquals("https://sbxh8.com/api/ad/challenge/",
                CustomHttpClient.resolveNtkMovedApiTrailingSlashUrlForTest(301,
                        "https://sbxh8.com/api/ad/challenge"));
        assertEquals("https://sbxh8.com/api/ad/challenge/?x=1",
                CustomHttpClient.resolveNtkMovedApiTrailingSlashUrlForTest(302,
                        "https://sbxh8.com/api/ad/challenge?x=1"));
        assertNull(CustomHttpClient.resolveNtkMovedApiTrailingSlashUrlForTest(200,
                "https://sbxh8.com/api/ad/challenge"));
        assertNull(CustomHttpClient.resolveNtkMovedApiTrailingSlashUrlForTest(301,
                "https://sbxh8.com/api/ad/challenge/"));
        assertNull(CustomHttpClient.resolveNtkMovedApiTrailingSlashUrlForTest(301,
                "https://sbxh8.com/manhwa/36525/1807424"));
        assertNull(CustomHttpClient.resolveNtkMovedApiTrailingSlashUrlForTest(301,
                "https://example.com/api/ad/challenge"));
    }

    @Test
    public void ntkSharedWebViewFallbackStaysOnNonEpisodeDocuments() {
        assertTrue(CustomHttpClient.shouldUseNtkWebViewFallbackForTest(true, true, "/api/manhwa-list"));
        assertTrue(CustomHttpClient.shouldUseNtkWebViewFallbackForTest(true, true, "/search?q=onepiece"));
        assertTrue(CustomHttpClient.shouldUseNtkWebViewFallbackForTest(true, true, "/manhwa/1"));
        assertTrue(CustomHttpClient.shouldUseNtkWebViewFallbackForTest(true, true, "/manhwa?page=2"));
        String[] strictEpisodePaths = {
                "/manhwa/33727/1692251",
                "/webtoon/850236/nv-850236-11",
                "/webtoon/68630031/kp-68630031-69262979",
                "/webtoon/work/episode?from=reader#top"
        };
        for(String path : strictEpisodePaths) {
            assertFalse(path, CustomHttpClient.shouldUseNtkWebViewFallbackForTest(
                    true, true, path, CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW));
            assertFalse(path, CustomHttpClient.shouldUseSharedWebViewFallbackForTest(
                    true, true, path, CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW));
        }
        assertFalse(CustomHttpClient.shouldUseNtkWebViewFallbackForTest(false, true, "/api/manhwa-list"));
        assertFalse(CustomHttpClient.shouldUseNtkWebViewFallbackForTest(true, false, "/api/manhwa-list"));
        assertFalse(CustomHttpClient.shouldUseNtkWebViewFallbackForTest(true, true, "/_next/static/app.js"));
    }

    @Test
    public void ntkNextAppShellIsNotCacheableUntilRendered() {
        String shell = "<html><body><div id=\"__next\"></div>"
                + "<script src=\"/_next/static/chunks/app/manhwa/%5BsourceWorkId%5D/page-abcd.js\"></script>"
                + "<next-route-announcer></next-route-announcer></body></html>";
        String renderedTitle = shell + "<a href=\"/manhwa/7843/79\">79화</a>";
        String renderedViewer = shell + "<main class=\"viewer\"><div class=\"vw-main\"><img src=\"https://pl1.com/a/1/2/p001.jpg\"></div></main>";

        assertTrue(CustomHttpClient.looksLikeUnrenderedNtkDocumentForTest("/manhwa/7843", 200, shell));
        assertFalse(CustomHttpClient.isCacheablePageBodyForTest(shell));
        assertFalse(CustomHttpClient.looksLikeUnrenderedNtkDocumentForTest("/manhwa/7843", 200, renderedTitle));
        assertFalse(CustomHttpClient.looksLikeUnrenderedNtkDocumentForTest("/manhwa/7843/79", 200, renderedViewer));
        assertTrue(CustomHttpClient.isCacheablePageBodyForTest(renderedTitle));
    }

    @Test
    public void ntkTokenizedViewerPayloadIsUsableEvenInsideNextShell() {
        String body = "<html><body><div id=\"__next\"></div>"
                + "<script src=\"/_next/static/chunks/app/webtoon/%5BsourceWorkId%5D/%5BviewId%5D/page-abcd.js\"></script>"
                + "<script>self.__next_f.push([1,\"{\\\"imagesToken\\\":\\\"abc123\\\",\\\"imageMetas\\\":[{\\\"page\\\":1}]}\" ])</script>"
                + "<title>媛쒕컻???꾧뎄 李⑤떒</title>"
                + "<next-route-announcer></next-route-announcer></body></html>";

        assertFalse(CustomHttpClient.looksLikeUnrenderedNtkDocumentForTest("/webtoon/12756/1135174", 200, body));
        assertFalse(CustomHttpClient.shouldRejectNtkPageResponseForTest("/webtoon/12756/1135174", 200, body));
    }

    @Test
    public void ntkViewerShellDataIsNotRejectedAsDevtoolsBlocker() {
        String body = "<html><body><div id=\"__next\"></div>"
                + "<script src=\"/_next/static/chunks/app/webtoon/%5BsourceWorkId%5D/%5BviewId%5D/page-abcd.js\"></script>"
                + "<script>self.__next_f.push([1,\"{\\\"sourceWorkId\\\":\\\"17247\\\",\\\"thumbnailUrl\\\":\\\"/thumbs/17247.jpg\\\"}\"])</script>"
                + "<title>媛쒕컻???꾧뎄 李⑤떒</title>"
                + "<next-route-announcer></next-route-announcer></body></html>";

        assertFalse(CustomHttpClient.looksLikeUnrenderedNtkDocumentForTest("/webtoon/68630031/kp-68630031-69262979", 200, body));
        assertFalse(CustomHttpClient.shouldRejectNtkPageResponseForTest("/webtoon/68630031/kp-68630031-69262979", 200, body));
    }

    @Test
    public void ntkRecentChallengeWithoutProofSkipsHiddenWebViewRecovery() {
        assertTrue(CustomHttpClient.shouldFastFailNtkPageForCaptchaForTest(true, "/manhwa/1",
                CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW, false, false, true));
        assertTrue(CustomHttpClient.shouldFastFailNtkPageForCaptchaForTest(true, "/api/works",
                CustomHttpClient.FetchMode.SEARCH_NO_WEBVIEW, false, false, true));
        assertTrue(CustomHttpClient.shouldSkipNtkHiddenWebViewFallbackAfterPageErrorForTest(true, "/manhwa/1",
                CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW, false, false, false,
                new Exception("Cloudflare challenge")));
        assertFalse(CustomHttpClient.shouldFastFailNtkPageForCaptchaForTest(true, "/manhwa/1",
                CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW, true, false, true));
        assertFalse(CustomHttpClient.shouldSkipNtkHiddenWebViewFallbackAfterPageErrorForTest(true, "/manhwa/1",
                CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW, false, true, true,
                new Exception("Cloudflare challenge")));
        assertFalse(CustomHttpClient.shouldSkipNtkHiddenWebViewFallbackAfterPageErrorForTest(true, "/manhwa/1",
                CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW, true, false, true,
                new Exception("Unusable NTK page: /manhwa/1 code=403")));
    }

    @Test
    public void ntkChallengeKeepsFreshClearanceBeyondRecentVerificationWindow() {
        assertTrue(CustomHttpClient.shouldKeepNtkClearanceOnChallengeForTest(
                true, false, true, true));
        assertTrue(CustomHttpClient.shouldKeepNtkClearanceOnChallengeForTest(
                true, true, false, true));

        assertFalse(CustomHttpClient.shouldKeepNtkClearanceOnChallengeForTest(
                true, false, true, false));
        assertFalse(CustomHttpClient.shouldKeepNtkClearanceOnChallengeForTest(
                true, false, false, true));
        assertFalse(CustomHttpClient.shouldKeepNtkClearanceOnChallengeForTest(
                false, true, true, true));
    }

    @Test
    public void requestGroupCancellationPropagatesToChildFetchGroups() {
        CustomHttpClient.RequestGroup parent = new CustomHttpClient.RequestGroup()
                .prioritizeWebViewFallback()
                .userVisible();
        CustomHttpClient.RequestGroup child = parent.child();

        assertTrue(child.prioritizesWebViewFallback());
        assertTrue(child.isUserVisible());
        assertFalse(child.isCancelled());

        parent.cancel();

        assertTrue(child.isCancelled());
    }

    @Test
    public void sharedWebViewFallbackCoversForegroundWolfEpisodePagesOnly() {
        assertTrue(CustomHttpClient.shouldUseSharedWebViewFallbackForTest(false, true, "/cl?toon=10007",
                CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW));
        assertTrue(CustomHttpClient.shouldUseSharedWebViewFallbackForTest(false, true, "/list?toon=10007",
                CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW));
        assertTrue(CustomHttpClient.shouldUseSharedWebViewFallbackForTest(false, true, "/cv?toon=10007&num=1",
                CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW));
        assertTrue(CustomHttpClient.shouldUseSharedWebViewFallbackForTest(false, true, "/view?toon=10007&num=1",
                CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW));
        assertFalse(CustomHttpClient.shouldUseSharedWebViewFallbackForTest(false, true, "/cm?type1=genre",
                CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW));
        assertFalse(CustomHttpClient.shouldUseSharedWebViewFallbackForTest(false, true, "/cl?toon=10007",
                CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW, false));
        assertFalse(CustomHttpClient.shouldUseSharedWebViewFallbackForTest(false, true, "/cl?toon=10007",
                CustomHttpClient.FetchMode.DIRECT_ONLY));
        assertFalse(CustomHttpClient.shouldUseSharedWebViewFallbackForTest(false, false, "/cl?toon=10007",
                CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW));
    }

    @Test
    public void wfwfEpisodeMissResolvesDomainBeforeWebViewFallback() {
        assertTrue(CustomHttpClient.shouldUseSharedWebViewFallbackForTest(false, true, "/cl?toon=10007",
                CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW));
        assertTrue(CustomHttpClient.shouldUseSharedWebViewFallbackForTest(false, true, "/cv?toon=10007&num=1",
                CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW));
    }

    @Test
    public void sharedWebViewNavigatesWolfEpisodeDocuments() {
        assertTrue(NtkWebViewFallbackManager.shouldNavigateDocumentForTest("/cl?toon=10007"));
        assertTrue(NtkWebViewFallbackManager.shouldNavigateDocumentForTest("/cv?toon=10007&num=1"));
        assertTrue(NtkWebViewFallbackManager.shouldNavigateDocumentForTest("/ing?page=2"));
        assertTrue(NtkWebViewFallbackManager.shouldNavigateDocumentForTest("/manhwa-end?sort=hot"));
        assertFalse(NtkWebViewFallbackManager.shouldNavigateDocumentForTest("/api/manhwa-list"));
        assertTrue(NtkWebViewFallbackManager.shouldNavigateDocumentForTest("/search?q=onepiece"));
        assertFalse(NtkWebViewFallbackManager.shouldNavigateDocumentForTest("/cm?type1=genre"));
    }

    @Test
    public void ntkFastPageDirectExcludesStrictEpisodesButCoversDiscoveryPaths() {
        assertFalse(CustomHttpClient.shouldUseFastNtkPageDirectForTest(true, "/manhwa/1/1",
                CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW));
        assertFalse(CustomHttpClient.shouldUseFastNtkPageDirectForTest(true, "/webtoon/1/1",
                CustomHttpClient.FetchMode.DIRECT_ONLY));
        assertTrue(CustomHttpClient.shouldUseFastNtkPageDirectForTest(true, "/api/manhwa-list",
                CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW));
        assertTrue(CustomHttpClient.shouldUseFastNtkPageDirectForTest(true, "/search?q=hero",
                CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW));
        assertTrue(CustomHttpClient.shouldUseFastNtkPageDirectForTest(true, "/manhwa?page=2",
                CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW));
        assertFalse(CustomHttpClient.shouldUseFastNtkPageDirectForTest(true, "/_next/static/app.js",
                CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW));
        assertFalse(CustomHttpClient.shouldUseFastNtkPageDirectForTest(false, "/manhwa/1/1",
                CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW));
        assertFalse(CustomHttpClient.shouldUseFastNtkPageDirectForTest(true, "/manhwa/1/1",
                CustomHttpClient.FetchMode.CACHE_ONLY));
    }

    @Test
    public void ntkApiDirectTimeoutOnlyCoversDiscoveryRequests() {
        assertTrue(CustomHttpClient.shouldUseFastNtkApiDirectUrlForTest(
                "https://sbxh4.com/api/works?page=1"));
        assertTrue(CustomHttpClient.shouldUseFastNtkApiDirectUrlForTest(
                "https://sbxh4.com/search?q=hero"));
        assertFalse(CustomHttpClient.shouldUseFastNtkApiDirectUrlForTest(
                "https://sbxh4.com/webtoon/18768"));
        assertFalse(CustomHttpClient.shouldUseFastNtkApiDirectUrlForTest(
                "https://sbxh4.com/webtoon/18768/1"));
        assertFalse(CustomHttpClient.shouldUseFastNtkApiDirectUrlForTest(
                "https://example.com/api/works?page=1"));
    }

    @Test
    public void ntkQuicPrimaryAndDirectClientCoverProtectedHosts() {
        assertFalse(CustomHttpClient.shouldUseNtkQuicPrimaryUrlForTest(
                "https://sbxh4.com/search?q=hero"));
        assertTrue(CustomHttpClient.shouldUseNtkQuicPrimaryUrlForTest(
                "https://img.sbxh4.com/webtoon_uploads/17801/1.jpg"));
        assertTrue(CustomHttpClient.shouldUseNtkDirectClientUrlForTest(
                "https://sbxh4.com/search?q=hero"));
        assertTrue(CustomHttpClient.shouldUseNtkDirectClientUrlForTest(
                "https://sbxh4.com/manhwa?page=2"));
        assertTrue(CustomHttpClient.shouldUseNtkDirectClientUrlForTest(
                "https://img.sbxh4.com/webtoon_uploads/17801/1.jpg"));
        assertFalse(CustomHttpClient.shouldUseNtkQuicPrimaryUrlForTest(
                "https://example.com/images/1.jpg"));
        assertFalse(CustomHttpClient.shouldUseNtkDirectClientUrlForTest(
                "https://example.com/images/1.jpg"));
    }

    @Test
    public void ntkViewerApiImagesCanonicalizeLegacyBlackEpisodes() {
        assertEquals("https://moamoabon.com/blacktoon/episodes/16968/1463195/p001.jpg",
                CustomHttpClient.normalizeNtkViewerApiImageSrcForTest(
                        "https://moamoabon.com/black/episodes/16968/1463195/p001.jpg",
                        "webtoon", "16968", "1463195"));
    }

    @Test
    public void ntkImageHardBlockDetectsCloudflareTosHtmlOnly() {
        String tos = "<!doctype html><html><title>Website Access Blocked</title>"
                + "<body>Cloudflare Terms of Service violation</body></html>";
        String challenge = "<!doctype html><html><title>Just a moment...</title>"
                + "<body>Cloudflare security check</body></html>";
        String nginx = "<html><body><h1>403 Forbidden</h1><center>nginx</center></body></html>";

        assertEquals("cloudflare-tos", CustomHttpClient.ntkImageHardBlockReasonForTest(
                403, "text/html", tos));
        assertEquals("", CustomHttpClient.ntkImageHardBlockReasonForTest(
                403, "text/html", challenge));
        assertEquals("", CustomHttpClient.ntkImageHardBlockReasonForTest(
                403, "text/html", nginx));
        assertEquals("", CustomHttpClient.ntkImageHardBlockReasonForTest(
                200, "text/html", tos));
        assertEquals("", CustomHttpClient.ntkImageHardBlockReasonForTest(
                403, "image/jpeg", tos));

        Map<String, List<String>> cloudflareHtmlHeaders = new HashMap<>();
        cloudflareHtmlHeaders.put("content-type", Collections.singletonList("text/html"));
        cloudflareHtmlHeaders.put("server", Collections.singletonList("cloudflare"));
        assertEquals("cloudflare-html-403", CustomHttpClient.ntkImageHardBlockReasonForTest(
                403, cloudflareHtmlHeaders, "<html></html>"));

        Map<String, List<String>> nginxHtmlHeaders = new HashMap<>();
        nginxHtmlHeaders.put("content-type", Collections.singletonList("text/html"));
        nginxHtmlHeaders.put("server", Collections.singletonList("nginx"));
        assertEquals("", CustomHttpClient.ntkImageHardBlockReasonForTest(
                403, nginxHtmlHeaders, "<html></html>"));
    }

    @Test
    public void ntkNetworkMissesAreExpectedRequestFailures() {
        assertFalse(CustomHttpClient.shouldRecordRequestFailureForTest(
                "https://sbxh1.com/api/manhwa-list?page=1",
                new ConnectException("Network is unreachable"),
                false,
                true));
        assertFalse(CustomHttpClient.shouldRecordRequestFailureForTest(
                "https://ntk01.com/manhwa/1",
                new java.io.InterruptedIOException("timeout"),
                false,
                true));
        assertFalse(CustomHttpClient.shouldRecordRequestFailureForTest(
                "https://sbxh1.com/api/manhwa-list?page=1",
                new ConnectException("Network is unreachable"),
                true,
                true));
        assertTrue(CustomHttpClient.shouldRecordRequestFailureForTest(
                "https://example.com/api/manhwa-list?page=1",
                new ConnectException("Network is unreachable"),
                false,
                false));
    }

    @Test
    public void ntkApiWorksJsonIsCacheable() {
        assertTrue(CustomHttpClient.looksCacheableForTest(
                "{\"works\":[{\"sourceWorkId\":\"u-moo205z1-yvf4\",\"thumbnailUrl\":\"/cover.jpg\"}]}"));
    }

    @Test
    public void wolfDocumentsUseFastClientForViewerListAndSearchPages() {
        assertTrue(CustomHttpClient.shouldUseFastWolfPageDirectUrlForTest("https://wfwf451.com/cv?toon=1&num=2"));
        assertTrue(CustomHttpClient.shouldUseFastWolfPageDirectUrlForTest("https://wolf.example/cl?toon=1"));
        assertTrue(CustomHttpClient.shouldUseFastWolfPageDirectUrlForTest("https://wfwf451.com/cm?type1=genre"));
        assertTrue(CustomHttpClient.shouldUseFastWolfPageDirectUrlForTest("https://wfwf451.com/search.html?q=onepunch"));
        assertFalse(CustomHttpClient.shouldUseFastWolfPageDirectUrlForTest("https://i1.imgcloud18.com/1/a.jpg"));
    }

    @Test
    public void wolfSearchUsesDedicatedFastTimeout() {
        assertTrue(CustomHttpClient.shouldUseFastWolfSearchDirectUrlForTest("https://wfwf451.com/search.html?q=onepunch"));
        assertTrue(CustomHttpClient.shouldUseFastWolfSearchDirectUrlForTest("https://wolf.example/search.html?q=onepunch"));
        assertFalse(CustomHttpClient.shouldUseFastWolfSearchDirectUrlForTest("https://wfwf451.com/cm?type1=genre"));
        assertFalse(CustomHttpClient.shouldUseFastWolfSearchDirectUrlForTest("https://i1.imgcloud18.com/search.html?q=onepunch"));
        assertTrue(CustomHttpClient.fastWolfSearchCallTimeoutMsForTest() < 3_000L);
    }

    @Test
    public void unsafeTlsFallbackMatchesScrapeHostOnly() {
        assertTrue(CustomHttpClient.allowUnsafeFallbackForTest("https://wfwf451.com/cm"));
        assertTrue(CustomHttpClient.allowUnsafeFallbackForTest("https://sbxh1.com/manhwa/1"));
        assertFalse(CustomHttpClient.allowUnsafeFallbackForTest("https://example.com/path/wfwf451.com/cm"));
        assertFalse(CustomHttpClient.allowUnsafeFallbackForTest("not a url with wfwf"));
    }

    @Test
    public void ntkApiDirectSkipsUnsafeTlsFallback() {
        assertFalse(CustomHttpClient.shouldUseUnsafeFallbackForUrlForTest(
                "https://sbxh4.com/api/works?page=1", true));
        assertTrue(CustomHttpClient.shouldUseUnsafeFallbackForUrlForTest(
                "https://sbxh4.com/webtoon/18768", false));
        assertFalse(CustomHttpClient.shouldUseUnsafeFallbackForUrlForTest(
                "https://example.com/api/works?page=1", false));
    }

    @Test
    public void ntkWebViewFallbackScriptUsesAsyncRequestBridge() {
        String script = CustomHttpClient.buildNtkWebViewFetchScriptForTest("/manhwa/1", "text/html");

        assertTrue(script.contains("window.NtkBridge.onResult"));
        assertTrue(script.contains("x.open('GET',\"/manhwa/1\",true)"));
        assertFalse(script.contains("x.open('GET',\"/manhwa/1\",false)"));
    }

    @Test
    public void ntkWebViewFallbackScriptCanCarrySingleFlightToken() {
        String script = CustomHttpClient.buildNtkWebViewFetchScript("/manhwa/1", null, "42");

        assertTrue(script.contains("window.NtkBridge.onFetchResult(\"42\""));
        assertFalse(script.contains("catch(e){window.NtkBridge.onResult"));
    }

    @Test
    public void ntkEpisodeDocumentDetectionRequiresConcreteEpisodePath() {
        assertFalse(CustomHttpClient.isNtkEpisodeDocumentPathForTest("/manhwa/1"));
        assertTrue(CustomHttpClient.isNtkEpisodeDocumentPathForTest("/manhwa/1/2"));
        assertTrue(CustomHttpClient.isNtkEpisodeDocumentPathForTest("/webtoon/abc/ep-1"));
        assertFalse(CustomHttpClient.isNtkEpisodeDocumentPathForTest("/api/manhwa-list"));
        assertTrue(CustomHttpClient.isNtkTitleDocumentPathForTest("/manhwa/1"));
        assertTrue(CustomHttpClient.isNtkTitleDocumentPathForTest("/webtoon/abc"));
        assertFalse(CustomHttpClient.isNtkTitleDocumentPathForTest("/manhwa/1/2"));
    }

    @Test
    public void ntkRedirectRootUsesTelegramOfficialRootInsteadOfRedirectHost() {
        List<String> officialRoots = Arrays.asList("https://sbxh3.com/");

        assertEquals("https://nicelink53.com",
                CustomHttpClient.ntkRedirectRootForTest("https://nicelink53.com"));
        assertEquals("https://nicelink53.com",
                CustomHttpClient.ntkRedirectRootForTest("https://nicelink53.com/manhwa/7843"));
        assertEquals("https://sbxh3.com", CustomHttpClient.officialNtkRootForRedirectForTest(
                "https://sbxh2.com", "https://nicelink53.com/manhwa/7843", officialRoots));
        assertEquals("https://sbxh3.com", CustomHttpClient.officialNtkRootForRedirectForTest(
                "https://sbxh3.com", "https://nicelink53.com/manhwa/7843", officialRoots));
        assertNull(CustomHttpClient.officialNtkRootForRedirectForTest(
                "https://sbxh1.com", "https://nicelink53.com/manhwa/7843", Arrays.<String>asList()));
        assertNull(CustomHttpClient.ntkRedirectRootForTest("https://t.me/something"));
        assertNull(CustomHttpClient.ntkRedirectRootForTest("/manhwa/7843"));
    }

    @Test
    public void ntkMovedSearchResponseIsRejectedForDomainRecovery() {
        String moved = "<head><title>Document Moved</title></head>"
                + "<body><h1>Object Moved</h1>This document may be found "
                + "<a HREF=\"https://a15c.com\">here</a></body>";

        assertTrue(CustomHttpClient.shouldRejectNtkPageResponseForTest("/search?q=hero", 302, moved));
        assertTrue(CustomHttpClient.shouldRejectNtkPageResponseForTest("/webtoon/1", 302, moved));
        assertFalse(CustomHttpClient.shouldRejectNtkPageResponseForTest("/init/theme.js", 302, moved));
    }

    @Test
    public void ntkPageDiskCacheAllowsColdStartStaleEntries() {
        long now = 7L * 24L * 60L * 60L * 1000L;

        assertFalse(CustomHttpClient.isPageCacheFreshForTest(now - 30L * 60L * 1000L, now, 10L * 60L * 1000L));
        assertTrue(CustomHttpClient.isPageCacheUsableForColdStartForTest(now - 30L * 60L * 1000L, now));
        assertFalse(CustomHttpClient.isPageCacheUsableForColdStartForTest(now - 8L * 24L * 60L * 60L * 1000L, now));
    }

    @Test
    public void ntkPageCacheRejectsWebViewErrorPages() {
        String errorPage = "<html><head><title>Webpage not available</title></head>"
                + "<body>The webpage at <strong>https://sbxh1.com/webtoon/17801</strong> could not be loaded"
                + "<p>net::ERR_CONNECTION_RESET</p></body></html>";
        String challengePage = "<html><head><title>Just a moment...</title></head>"
                + "<body><script src=\"https://challenges.cloudflare.com/cdn-cgi/challenge-platform/h/b/orchestrate/chl_page/v1\"></script></body></html>";
        String verifyingPage = "<html lang=\"en-US\"><body>Verifying you are human. "
                + "This site is protected by a Cloudflare security service. <span>Ray ID</span></body></html>";

        String cloudflare522Page = "<html><head><title>newtoki469.com | 522: Connection timed out</title></head>"
                + "<body>Connection timed out Error code 522 Cloudflare Host Error</body></html>";

        assertFalse(CustomHttpClient.isCacheablePageBodyForTest(errorPage));
        assertFalse(CustomHttpClient.isCacheablePageBodyForTest(challengePage));
        assertFalse(CustomHttpClient.isCacheablePageBodyForTest(verifyingPage));
        assertFalse(CustomHttpClient.isCacheablePageBodyForTest(cloudflare522Page));
        assertTrue(CustomHttpClient.isCacheablePageBodyForTest(
                "<html><body><main class=\"viewer-content\">"
                        + "<img src=\"/webtoon_uploads/17801/1.jpg\">"
                        + "<p>normal rendered viewer content with enough text to exceed the empty document guard.</p>"
                        + "</main></body></html>"));
    }

    @Test
    public void ntkLegacyPageResponseGuardExcludesStrictEpisodesButCoversDiscoveryDocuments() {
        String challengePage = "<html lang=\"en-US\" dir=\"ltr\"><head></head>"
                + "<body>Verifying you are human. Cloudflare security service.</body></html>";
        String devtoolsBlocked = "<html><head><title>개발자 도구 차단</title></head>"
                + "<body>developer tools blocked</body></html>";

        assertFalse(CustomHttpClient.shouldRejectNtkPageResponseForTest(
                "/webtoon/18768/1586501", 200, challengePage));
        assertFalse(CustomHttpClient.shouldRejectNtkPageResponseForTest(
                "/manhwa/8044/u-mp9phqym-9fo4", 200, devtoolsBlocked));
        assertTrue(CustomHttpClient.shouldRejectNtkPageResponseForTest(
                "/api/manhwa-list?page=1", 200, challengePage));
        assertTrue(CustomHttpClient.shouldRejectNtkPageResponseForTest(
                "/search?q=hero", 200, devtoolsBlocked));
        assertFalse(CustomHttpClient.shouldRejectNtkPageResponseForTest(
                "/init/theme.js", 200, challengePage));
    }

    @Test
    public void ntkRenderedTitleDocumentIgnoresDormantDevtoolsBlockSource() {
        String renderedTitle = "<html><head><title>Live title | 뉴토끼</title></head><body>"
                + "<section class=\"ep-list\"><a class=\"ep-row-v2-link\" "
                + "href=\"/webtoon/850236/nv-850236-11\"><strong>11화</strong></a></section>"
                + "<script>const dormant = '<h1>개발자 도구 차단</h1>';</script>"
                + "</body></html>";
        String blockedOnly = "<html><head><title>개발자 도구 차단</title></head>"
                + "<body><h1>개발자 도구 차단</h1></body></html>";

        assertFalse(CustomHttpClient.shouldRejectNtkPageResponseForTest(
                "/webtoon/850236", 200, renderedTitle));
        assertTrue(CustomHttpClient.isCacheablePageBodyForTest(renderedTitle));
        assertTrue(CustomHttpClient.looksCacheableForTest(renderedTitle));
        assertTrue(CustomHttpClient.shouldStoreNetworkPageBodyForTest(
                "/webtoon/850236", renderedTitle));
        assertTrue(CustomHttpClient.shouldRejectNtkPageResponseForTest(
                "/webtoon/850236", 200, blockedOnly));
        assertFalse(CustomHttpClient.isCacheablePageBodyForTest(blockedOnly));
    }

    @Test
    public void ntkRenderedFlightSearchCardIgnoresDormantDevtoolsBlockSource() {
        String renderedSearch = "<html><body><div id=\"__next\"></div>"
                + "<script>self.__next_f.push([1,\"4e:[\\\"$\\\",\\\"div\\\",null,{"
                + "\\\"children\\\":[[\\\"$\\\",\\\"a\\\",\\\"w-4492\\\",{"
                + "\\\"children\\\":[[\\\"$\\\",\\\"p\\\",null,{"
                + "\\\"className\\\":\\\"subject\\\",\\\"children\\\":\\\"사우러스\\\"}]],"
                + "\\\"href\\\":\\\"/webtoon/726211\\\","
                + "\\\"data-ntk-soft-link\\\":\\\"\\\"}]]}]\\n\"])</script>"
                + "<script>const dormant = '<h1>개발자 도구 차단</h1>';</script>"
                + "</body></html>";
        String blockedTemplateOnly = "<html><body><div id=\"__next\"></div>"
                + "<script>const route = '/webtoon/' + sourceWorkId;"
                + "const className = 'subject'; const dormant = '개발자 도구 차단';</script>"
                + "<next-route-announcer></next-route-announcer></body></html>";

        assertTrue(CustomHttpClient.hasRenderedNtkDocumentContent(
                renderedSearch.toLowerCase(java.util.Locale.ROOT)));
        assertFalse(CustomHttpClient.shouldRejectNtkPageResponseForTest(
                "/search?q=사우러스&field=title&match=contains&kind=webtoon", 200, renderedSearch));
        assertTrue(CustomHttpClient.isCacheablePageBodyForTest(renderedSearch));
        assertTrue(CustomHttpClient.looksCacheableForTest(renderedSearch));
        assertTrue(CustomHttpClient.shouldStoreNetworkPageBodyForTest(
                "/search?q=사우러스&field=title&match=contains&kind=webtoon", renderedSearch));

        assertFalse(CustomHttpClient.hasRenderedNtkDocumentContent(
                blockedTemplateOnly.toLowerCase(java.util.Locale.ROOT)));
        assertTrue(CustomHttpClient.shouldRejectNtkPageResponseForTest(
                "/search?q=hero", 200, blockedTemplateOnly));
        assertFalse(CustomHttpClient.isCacheablePageBodyForTest(blockedTemplateOnly));
    }

    @Test
    public void ntkHardBlockGuardExcludesStrictEpisodesButCoversDiscoveryPaths() {
        String nginx403 = "<html><head><title>403 Forbidden</title></head>"
                + "<body><center><h1>403 Forbidden</h1></center>"
                + "<hr><center>nginx/1.24.0 (Ubuntu)</center></body></html>";

        assertFalse(CustomHttpClient.isNtkHardBlockedResponseForTest(
                "/manhwa/37043/1816201", 403, nginx403));
        assertTrue(CustomHttpClient.isNtkHardBlockedResponseForTest(
                "/api/manhwa-list?page=1", 403, nginx403));
        assertTrue(CustomHttpClient.isNtkHardBlockedResponseForTest(
                "/search?q=hero", 403, "<html><body>trash0607</body></html>"));
        assertFalse(CustomHttpClient.isNtkHardBlockedResponseForTest(
                "/manhwa", 403, nginx403));
        assertFalse(CustomHttpClient.isNtkHardBlockedResponseForTest(
                "/manhwa/37043/1816201", 200, nginx403));
    }

    @Test
    public void coldStartStalePageCacheServesImmediatelyWhenAllowed() {
        assertTrue(CustomHttpClient.shouldServeColdStartCachedPageImmediatelyForTest(true,
                CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW, true, false));
        assertTrue(CustomHttpClient.shouldServeColdStartCachedPageImmediatelyForTest(true,
                CustomHttpClient.FetchMode.DIRECT_ONLY, true, false));
        assertFalse(CustomHttpClient.shouldServeColdStartCachedPageImmediatelyForTest(false,
                CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW, true, false));
        assertFalse(CustomHttpClient.shouldServeColdStartCachedPageImmediatelyForTest(true,
                CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW, true, true));
    }

    @Test
    public void wfwfPageDiskCachePersistsOnlyUsableEpisodePages() {
        String episodePage = "<html><body><a href=\"/cv?toon=10007&num=1\">episode</a></body></html>";

        assertTrue(CustomHttpClient.shouldPersistDiskCachedPageForTest(false,
                "https://wfwf451.com/cl?toon=10007", episodePage));
        assertFalse(CustomHttpClient.shouldPersistDiskCachedPageForTest(false,
                "https://example.com/cl?toon=10007", episodePage));
        assertFalse(CustomHttpClient.shouldPersistDiskCachedPageForTest(false,
                "https://wfwf451.com/cl?toon=10007", "<html>warninge.kcopa.or.kr</html>"));
        assertFalse(CustomHttpClient.shouldPersistDiskCachedPageForTest(false,
                "https://wfwf451.com/cl?toon=10007", "<script>window.location.href=\"/lander?toon=10007\"</script>"));
    }

    @Test
    public void wfwfLanderPagesAreRejectedForRetry() {
        String lander = "<html><head><script>window.location.href=\"/lander?toon=10007\"</script></head></html>";
        String episode = "<html><body><a href=\"/cv?toon=10007&num=1\">episode</a></body></html>";

        assertTrue(CustomHttpClient.shouldRejectWfwfPageBodyForTest("/cl?toon=10007", 200, lander));
        assertFalse(CustomHttpClient.shouldRejectWfwfPageBodyForTest("/cl?toon=10007", 200, episode));
        assertFalse(CustomHttpClient.shouldRejectWfwfPageBodyForTest("/api/manhwa-list", 200, lander));
    }

    @Test
    public void wfwfSearchAllowsEmptyResultPages() {
        String emptySearch = "<html><head><title>Search</title></head><body>no result</body></html>";
        String errorPage = "<html><head><title>Webpage not available</title></head><body>net::ERR_CONNECTION_RESET</body></html>";

        assertFalse(CustomHttpClient.shouldRejectWfwfPageBodyForTest("/search.html?q=missing", 200, emptySearch));
        assertTrue(CustomHttpClient.shouldRejectWfwfPageBodyForTest("/search.html?q=missing", 200, errorPage));
        assertTrue(CustomHttpClient.shouldStoreNetworkPageBodyForTest("/search.html?q=missing", emptySearch));
        assertFalse(CustomHttpClient.shouldStoreNetworkPageBodyForTest("/search.html?q=missing", errorPage));
    }

    @Test
    public void ntkUrlDetectionHandlesResolvedHosts() {
        assertTrue(CustomHttpClient.isNtkUrlForTest("https://sbxh1.com/manhwa"));
        assertTrue(CustomHttpClient.isNtkUrlForTest("https://img.sbxh1.com/manhwa/1"));
        assertTrue(CustomHttpClient.isNtkUrlForTest("https://toki30.com/webtoon"));
        assertTrue(CustomHttpClient.isNtkUrlForTest("https://img.toki30.com/manhwa/1"));
        assertTrue(CustomHttpClient.isNtkUrlForTest("https://newto03.com/manhwa"));
        assertTrue(CustomHttpClient.isNtkUrlForTest("https://toonflix.app/manhwa"));
        assertFalse(CustomHttpClient.isNtkUrlForTest("https://toki30.com.evil.example/manhwa"));
        assertFalse(CustomHttpClient.isNtkUrlForTest("https://toki.example/manhwa"));
        assertFalse(CustomHttpClient.isNtkUrlForTest("https://example.com/manhwa"));
    }

    @Test
    public void numberedTokiRootUsesCurrentGuardAndMobileIdentityPolicy() {
        assertTrue(CustomHttpClient.isModernNtkGuardRootForTest("https://toki30.com"));
        assertTrue(CustomHttpClient.isModernNtkGuardRootForTest("https://www.toki30.com/manhwa/33727"));
        assertFalse(CustomHttpClient.isModernNtkGuardRootForTest("https://toki.example"));
    }

    @Test
    public void ntkDomainResolverTrustsOfficialRootBeforeProbe() {
        List<String> officialRoots = Arrays.asList("https://sbxh3.com/");
        List<String> unusualRoots = Arrays.asList("https://odd-address.example/");

        assertEquals("https://sbxh3.com", CustomHttpClient.firstTrustedResolvedNtkRootForTest(officialRoots));
        assertTrue(CustomHttpClient.shouldApplyResolvedNtkRootForTest(
                "https://sbxh2.com", "https://sbxh3.com", officialRoots));
        assertTrue(CustomHttpClient.shouldApplyResolvedNtkRootForTest(
                "https://sbxh1.com", "https://odd-address.example", unusualRoots));
        assertFalse(CustomHttpClient.shouldApplyResolvedNtkRootForTest(
                "https://sbxh1.com", "https://odd-address.example", officialRoots));
        assertFalse(CustomHttpClient.shouldApplyResolvedNtkRootForTest(
                "https://sbxh3.com", "https://sbxh3.com", officialRoots));
    }

    @Test
    public void ntkReachabilityRequiresApiJsonRoot() {
        String challenge = "<!DOCTYPE html><html><body>"
                + "<script src=\"https://challenges.cloudflare.com/turnstile/v0/api.js\"></script>"
                + "Verify you are human</body></html>";
        String apiJson = "{\"works\":[{\"sourceWorkId\":\"1\",\"title\":\"sample\"}]}";

        assertTrue(CustomHttpClient.isReachableNtkProbeResponseForTest(200, "", apiJson));
        assertFalse(CustomHttpClient.isReachableNtkProbeResponseForTest(403, "", challenge));
        assertFalse(CustomHttpClient.isReachableNtkProbeResponseForTest(
                403, "", "<html><body>plain forbidden</body></html>"));
        assertFalse(CustomHttpClient.isReachableNtkProbeResponseForTest(
                200, "", "<html><body>뉴토끼 공식 주소안내</body></html>"));
    }

    @Test
    public void ntkOfficialRootOverridesStaleDefaultRoot() {
        String staleDefaultRoot = "https://sbxh2.com";
        List<String> officialRoots = Arrays.asList("https://sbxh3.com/");

        assertEquals("https://sbxh3.com", CustomHttpClient.firstTrustedResolvedNtkRootForTest(officialRoots));
        assertTrue(CustomHttpClient.shouldApplyResolvedNtkRootForTest(
                staleDefaultRoot, "https://sbxh3.com", officialRoots));
    }

    @Test
    public void ntkAddressRefreshSeparatesDomainErrorsFromCloudflareChallenges() {
        String challenge = "<!DOCTYPE html><html><head><title>Just a moment...</title></head>"
                + "<body><script src=\"https://challenges.cloudflare.com/turnstile/v0/api.js\"></script></body></html>";

        assertFalse(CustomHttpClient.shouldRetryNtkWithResolvedDomainForTest(
                403, challenge, true));
        assertTrue(CustomHttpClient.shouldRetryNtkWithResolvedDomainForTest(
                451,
                "{\"title\":\"Error 1026: Cloudflare Error\",\"status\":451}",
                true));
        assertFalse(CustomHttpClient.shouldRetryNtkWithResolvedDomainForTest(
                451,
                "{\"title\":\"Error 1026: Cloudflare Error\",\"status\":451}",
                false));
        assertTrue(CustomHttpClient.shouldRetryNtkWithResolvedDomainForTest(
                403,
                "<html><body>plain forbidden</body></html>",
                false));
        assertTrue(CustomHttpClient.shouldRetryNtkWithResolvedDomainForTest(
                404, "", false));
        assertFalse(CustomHttpClient.shouldRetryNtkWithResolvedDomainForTest(
                200,
                "{\"works\":[]}",
                false));
    }

    @Test
    public void ntkMissingApiResponseAfterChallengeSkipsAddressRetry() {
        assertTrue(CustomHttpClient.shouldSkipNtkResolvedDomainRetryAfterChallengeForTest(
                CustomHttpClient.FetchMode.SEARCH_NO_WEBVIEW, true, true, "/api/manhwa-list"));
        assertTrue(CustomHttpClient.shouldSkipNtkResolvedDomainRetryAfterChallengeForTest(
                CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW, true, true, "/manhwa/1"));
        assertFalse(CustomHttpClient.shouldSkipNtkResolvedDomainRetryAfterChallengeForTest(
                CustomHttpClient.FetchMode.DIRECT_ONLY, true, true, "/api/manhwa-list"));
        assertFalse(CustomHttpClient.shouldSkipNtkResolvedDomainRetryAfterChallengeForTest(
                CustomHttpClient.FetchMode.SEARCH_NO_WEBVIEW, false, true, "/api/manhwa-list"));
        assertFalse(CustomHttpClient.shouldSkipNtkResolvedDomainRetryAfterChallengeForTest(
                CustomHttpClient.FetchMode.SEARCH_NO_WEBVIEW, true, false, "/api/manhwa-list"));
        assertFalse(CustomHttpClient.shouldSkipNtkResolvedDomainRetryAfterChallengeForTest(
                CustomHttpClient.FetchMode.SEARCH_NO_WEBVIEW, true, true, "/_next/static/app.js"));
    }

    @Test
    public void ntkApiFastClientKeepsBlockedSearchFailureShort() {
        assertTrue(CustomHttpClient.fastNtkApiDirectTimeoutMsForTest() <= 2500L);
    }

    @Test
    public void ntkProbeRejectsNonApiJsonRoots() {
        String challenge = "<!DOCTYPE html><html><head><title>Just a moment...</title></head>"
                + "<body>checking your browser</body></html>";
        String nginx403 = "<html><head><title>403 Forbidden</title></head>"
                + "<body><h1>403 Forbidden</h1><hr><center>nginx/1.24</center></body></html>";
        String apiJson = "{\"works\":[],\"total\":0}";

        assertTrue(CustomHttpClient.isReachableNtkProbeResponseForTest(200, "", apiJson));
        assertFalse(CustomHttpClient.isReachableNtkProbeResponseForTest(403, "", challenge));
        assertFalse(CustomHttpClient.isReachableNtkProbeResponseForTest(403, "", nginx403));
        assertFalse(CustomHttpClient.isReachableNtkProbeResponseForTest(302, "https://t.me/newtoki_url", ""));
        assertFalse(CustomHttpClient.isReachableNtkProbeResponseForTest(200, "", "<html></html>"));
    }

    @Test
    public void ntkChallengeProbeRejectsGenericJsonRoots() {
        assertTrue(CustomHttpClient.isReachableNtkChallengeTransportResponseForTest(
                200, "", "application/json",
                "{\"ok\":true,\"challenge\":{\"token\":\"abc\"}}"));
        assertTrue(CustomHttpClient.isReachableNtkChallengeTransportResponseForTest(
                200, "", "application/json",
                "{\"ok\":true,\"trusted\":true}"));
        assertFalse(CustomHttpClient.isReachableNtkChallengeTransportResponseForTest(
                405, "", "application/json",
                "{\"message\":\"method not allowed\"}"));
        assertFalse(CustomHttpClient.isReachableNtkChallengeTransportResponseForTest(
                200, "", "application/json",
                "{\"status\":\"ok\"}"));
        assertFalse(CustomHttpClient.isReachableNtkChallengeTransportResponseForTest(
                200, "", "application/json",
                "{\"ok\":true,\"message\":\"parking\"}"));
        assertFalse(CustomHttpClient.isReachableNtkChallengeTransportResponseForTest(
                302, "https://t.me/newtoki_url", "application/json",
                "{\"ok\":true,\"challenge\":{\"token\":\"abc\"}}"));
    }

    @Test
    public void ntkDomainThrottleDoesNotHidePresetRootChanges() {
        long now = 10_000L;

        assertTrue(CustomHttpClient.shouldSkipRecentNtkDomainCheckForTest(
                false, "https://sbxh1.com", "https://sbxh1.com", now - 1_000L, now));
        assertFalse(CustomHttpClient.shouldSkipRecentNtkDomainCheckForTest(
                false, "https://sbxh2.com", "https://sbxh1.com", now - 1_000L, now));
        assertFalse(CustomHttpClient.shouldSkipRecentNtkDomainCheckForTest(
                true, "https://sbxh1.com", "https://sbxh1.com", now - 1_000L, now));
    }

    @Test
    public void wfwfDomainThrottleDoesNotHidePresetRootChanges() {
        long now = 10_000L;

        assertTrue(CustomHttpClient.shouldSkipRecentWfwfDomainCheckForTest(
                false, "https://wfwf453.com", "https://wfwf453.com", now - 1_000L, now));
        assertFalse(CustomHttpClient.shouldSkipRecentWfwfDomainCheckForTest(
                false, "https://wfwf454.com", "https://wfwf453.com", now - 1_000L, now));
        assertFalse(CustomHttpClient.shouldSkipRecentWfwfDomainCheckForTest(
                true, "https://wfwf453.com", "https://wfwf453.com", now - 1_000L, now));
    }

    @Test
    public void wfwfRecentFailureBypassesDomainThrottle() {
        long now = 10_000L;

        assertFalse(CustomHttpClient.shouldSkipRecentWfwfDomainCheckForTest(
                false, "https://wfwf454.com", "https://wfwf454.com", now - 1_000L,
                "https://wfwf454.com", now - 500L, now));
        assertTrue(CustomHttpClient.shouldSkipRecentWfwfDomainCheckForTest(
                false, "https://wfwf454.com", "https://wfwf454.com", now - 1_000L,
                "https://wfwf453.com", now - 500L, now));
    }

    @Test
    public void wfwfSslFailureMarksNumberedRootStale() {
        assertTrue(CustomHttpClient.isLikelyStaleWfwfRootFailureForTest(
                "https://wfwf454.com/cl?toon=18714",
                new javax.net.ssl.SSLException("failed")));
        assertFalse(CustomHttpClient.isLikelyStaleWfwfRootFailureForTest(
                "https://example.com/cl?toon=18714",
                new javax.net.ssl.SSLException("failed")));
    }

    @Test
    public void ntkDnsProtectionCoversRootAndImageSubdomains() {
        assertTrue(CustomHttpClient.isNtkDnsProtectedHostForTest("sbxh1.com"));
        assertTrue(CustomHttpClient.isNtkDnsProtectedHostForTest("www.sbxh1.com"));
        assertTrue(CustomHttpClient.isNtkDnsProtectedHostForTest("img.sbxh1.com"));
        assertTrue(CustomHttpClient.isNtkDnsProtectedHostForTest("toki30.com"));
        assertTrue(CustomHttpClient.isNtkDnsProtectedHostForTest("www.toki30.com"));
        assertTrue(CustomHttpClient.isNtkDnsProtectedHostForTest("img.toki30.com"));
        assertTrue(CustomHttpClient.isNtkDnsProtectedHostForTest("toonflix.app"));
        assertTrue(CustomHttpClient.isNtkDnsProtectedHostForTest("img.toonflix.app"));
        assertTrue(CustomHttpClient.isNtkDnsProtectedHostForTest("booktoki8.org"));
        assertTrue(CustomHttpClient.isNtkDnsProtectedHostForTest("mana.apihost93.com"));
        assertTrue(CustomHttpClient.isNtkDnsProtectedHostForTest("aws-cdn1.site"));
        assertTrue(CustomHttpClient.isNtkDnsProtectedHostForTest("f1spard.site"));
        assertTrue(CustomHttpClient.isNtkDnsProtectedHostForTest("shaomoi.org"));
        assertTrue(CustomHttpClient.isNtkDnsProtectedHostForTest("xiaomichina.com"));
        assertFalse(CustomHttpClient.isNtkDnsProtectedHostForTest("toki30.com.evil.example"));
        assertFalse(CustomHttpClient.isNtkDnsProtectedHostForTest("example.com"));
    }

    @Test
    public void ntkPersistedDnsAllowsColdStartBootstrapStaleOnlyWithinLimit() {
        long now = 8L * 24L * 60L * 60L * 1000L;

        assertTrue(CustomHttpClient.isPersistedNtkDnsUsableForTest(now - 60_000L, now + 60_000L, now, false));
        assertTrue(CustomHttpClient.isPersistedNtkDnsUsableForTest(now - 2L * 24L * 60L * 60L * 1000L,
                now - 60_000L, now, true));
        assertTrue(CustomHttpClient.isPersistedNtkDnsStaleForTest(now - 2L * 24L * 60L * 60L * 1000L,
                now - 60_000L, now));
        assertFalse(CustomHttpClient.isPersistedNtkDnsUsableForTest(now - 8L * 24L * 60L * 60L * 1000L,
                now - 60_000L, now, true));
        assertFalse(CustomHttpClient.isPersistedNtkDnsUsableForTest(now + 1L, now - 60_000L, now, true));
    }

    @Test
    public void ntkDohWarmBacksOffAfterFailure() {
        long now = 1_000L;
        long retryAfter = CustomHttpClient.nextNtkDohRetryAfterForTest(false, now);

        assertTrue(retryAfter > now);
        assertFalse(CustomHttpClient.shouldStartNtkDohWarmForTest(retryAfter, retryAfter - 1L));
        assertTrue(CustomHttpClient.shouldStartNtkDohWarmForTest(retryAfter, retryAfter));
        assertEquals(0L, CustomHttpClient.nextNtkDohRetryAfterForTest(true, now));
    }

    @Test
    public void ntkDnsMergeKeepsPreferredIpv4FirstThenFallback() throws Exception {
        InetAddress preferred = InetAddress.getByAddress("sbxh1.com",
                new byte[] {(byte)104, (byte)16, (byte)220, (byte)55});
        InetAddress fallback = InetAddress.getByAddress("sbxh1.com",
                new byte[] {(byte)203, (byte)0, (byte)113, (byte)10});

        List<InetAddress> merged = CustomHttpClient.mergeIpv4FirstForTest("sbxh1.com",
                Arrays.asList(preferred), null, Arrays.asList(fallback));

        assertEquals("104.16.220.55", merged.get(0).getHostAddress());
        assertEquals("203.0.113.10", merged.get(1).getHostAddress());
    }

    @Test
    public void ntkDiagnosticInterpretsClosedSniRouteAsTunnelRequired() {
        String report = "active_site: ntk\n"
                + "network: cellular,validated=true,internet=true\n"
                + "system_dns_sbxh5.com: ok 104.21.48.220,172.67.156.176\n"
                + "app_dns_sbxh5.com: ok 104.21.48.220,172.67.156.176\n"
                + "ntk_quic_sni: code=0,ms=102,error=NetworkExceptionWrapper(net::ERR_CONNECTION_CLOSED, ErrorCode=5, InternalErrorCode=-100)\n"
                + "ntk_api_direct: fail 501ms SocketException(Connection reset)";

        String interpretation = CustomHttpClient.diagnosticInterpretationForTest(report);

        assertTrue(interpretation.contains("DNS bypass works"));
        assertTrue(interpretation.contains("VPN/WARP-style tunnel"));
    }

    @Test
    public void ntkDiagnosticKeepsCaptchaInterpretationWhenChallengeIsReached() {
        String report = "active_site: ntk\n"
                + "network: cellular+vpn,validated=true,internet=true\n"
                + "app_dns_sbxh5.com: ok 104.21.48.220,172.67.156.176\n"
                + "ntk_quic_sni: code=403,ms=110,body_len=2048,challenge=true,error=\n"
                + "ntk_api_direct: code=403,ms=130,body_len=2048,challenge=true";

        assertEquals("Cloudflare challenge/cookie issue. Open NTK captcha once.",
                CustomHttpClient.diagnosticInterpretationForTest(report));
    }

    @Test
    public void appDnsDropsIpv6WhenIpv4Exists() throws Exception {
        InetAddress ipv4 = InetAddress.getByAddress("example.com",
                new byte[] {(byte)104, (byte)26, (byte)10, (byte)250});
        InetAddress ipv6 = InetAddress.getByAddress("example.com",
                new byte[] {
                        (byte)0x26, (byte)0x06, (byte)0x47, (byte)0x00,
                        (byte)0x00, (byte)0x20, (byte)0x00, (byte)0x00,
                        (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
                        (byte)0x68, (byte)0x1a, (byte)0x0b, (byte)0xfa});

        List<InetAddress> filtered = CustomHttpClient.ipv4OnlyOrThrowForTest("example.com", Arrays.asList(ipv6, ipv4));

        assertEquals(1, filtered.size());
        assertEquals("104.26.10.250", filtered.get(0).getHostAddress());
    }

    @Test
    public void wfwfDnsDropsIpv6AndKeepsIpv4() throws Exception {
        InetAddress ipv4 = InetAddress.getByAddress("wfwf451.com",
                new byte[] {(byte)104, (byte)26, (byte)14, (byte)114});
        InetAddress ipv6 = InetAddress.getByAddress("wfwf451.com",
                new byte[] {
                        (byte)0x26, (byte)0x06, (byte)0x47, (byte)0x00,
                        (byte)0x00, (byte)0x20, (byte)0x00, (byte)0x00,
                        (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
                        (byte)0x68, (byte)0x1a, (byte)0x0e, (byte)0x72});

        List<InetAddress> selected = CustomHttpClient.selectNetworkResilientAddressesForTest("wfwf451.com",
                Arrays.asList(ipv4, ipv6));

        assertEquals(1, selected.size());
        assertEquals("104.26.14.114", selected.get(0).getHostAddress());
    }

    @Test
    public void wfwfImageCdnAlsoStaysIpv4Only() throws Exception {
        assertFalse(CustomHttpClient.prefersIpv6ForWfwfHostForTest("i1.imgcloud18.com"));
        assertFalse(CustomHttpClient.prefersIpv6ForWfwfHostForTest("v12st.com"));
        assertFalse(CustomHttpClient.prefersIpv6ForWfwfHostForTest("sbxh1.com"));
    }

    @Test(expected = java.net.UnknownHostException.class)
    public void appDnsRejectsIpv6OnlyAnswers() throws Exception {
        InetAddress ipv6 = InetAddress.getByAddress("sbxh1.com",
                new byte[] {
                        (byte)0x26, (byte)0x06, (byte)0x47, (byte)0x00,
                        (byte)0x00, (byte)0x20, (byte)0x00, (byte)0x00,
                        (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
                        (byte)0x68, (byte)0x1a, (byte)0x0b, (byte)0xfa});

        CustomHttpClient.ipv4OnlyOrThrowForTest("sbxh1.com", Arrays.asList(ipv6));
    }

    @Test
    public void generalAppDnsAllowsIpv6OnlyAnswers() throws Exception {
        InetAddress ipv6 = InetAddress.getByAddress("example.com",
                new byte[] {
                        (byte)0x26, (byte)0x06, (byte)0x47, (byte)0x00,
                        (byte)0x00, (byte)0x20, (byte)0x00, (byte)0x00,
                        (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
                        (byte)0x68, (byte)0x1a, (byte)0x0b, (byte)0xfa});

        List<InetAddress> selected = CustomHttpClient.selectNetworkResilientAddressesForTest(
                "example.com", Arrays.asList(ipv6));

        assertEquals(1, selected.size());
        assertEquals(ipv6, selected.get(0));
    }
}
