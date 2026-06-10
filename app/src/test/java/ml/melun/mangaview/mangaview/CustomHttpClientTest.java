package ml.melun.mangaview.mangaview;

import org.junit.Test;

import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import okhttp3.Protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CustomHttpClientTest {
    @Test
    public void interruptedRequestsAreExpectedCancellation() {
        assertTrue(CustomHttpClient.isInterruptedRequestForTest(new InterruptedException()));
        assertTrue(CustomHttpClient.isInterruptedRequestForTest(new InterruptedIOException()));
        assertTrue(CustomHttpClient.isInterruptedRequestForTest(new Exception("Canceled")));
        assertFalse(CustomHttpClient.isInterruptedRequestForTest(new Exception("Request failed")));
    }

    @Test
    public void ntkAckStartsProactiveCanaryWhenChallengeHasImpressions() {
        assertFalse(CustomHttpClient.shouldStartProactiveNtkAckCanaryForTest(0));
        assertTrue(CustomHttpClient.shouldStartProactiveNtkAckCanaryForTest(1));
        assertTrue(CustomHttpClient.shouldStartProactiveNtkAckCanaryForTest(4));
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
        assertTrue(CustomHttpClient.clientHintUa(mobile).contains("Android WebView"));
    }

    @Test
    public void pageAndImageClientsShareConnectionPool() {
        assertTrue(CustomHttpClient.clientsShareConnectionPoolForTest());
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
    public void ntkWebViewFallbackCoversPageApiAndSearchMisses() {
        assertTrue(CustomHttpClient.shouldUseNtkWebViewFallbackForTest(true, true, "/api/manhwa-list"));
        assertTrue(CustomHttpClient.shouldUseNtkWebViewFallbackForTest(true, true, "/search?q=onepiece"));
        assertTrue(CustomHttpClient.shouldUseNtkWebViewFallbackForTest(true, true, "/manhwa/1"));
        assertTrue(CustomHttpClient.shouldUseNtkWebViewFallbackForTest(true, true, "/manhwa/1/2"));
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
    public void ntkNextErrorFallbackCanUseWebViewForEpisode() {
        String body = "<html><body><div id=\"__next\"></div>"
                + "<script src=\"/_next/static/chunks/app/manhwa/%5BsourceWorkId%5D/%5BviewId%5D/page-abcd.js\"></script>"
                + "<script>self.__next_f.push([\"NEXT_HTTP_ERROR_FALLBACK\",404])</script></body></html>";

        assertTrue(CustomHttpClient.looksLikeNtkRecoverableErrorFallbackDocumentForTest("/manhwa/8252/64225", 200, body));
        assertFalse(CustomHttpClient.looksLikeNtkRecoverableErrorFallbackDocumentForTest("/api/manhwa-list", 200, body));
        assertFalse(CustomHttpClient.looksLikeNtkRecoverableErrorFallbackDocumentForTest("/manhwa/8252/64225", 404, body));
    }

    @Test
    public void ntkWebViewFallbackRequiresSharedWebViewMode() {
        assertTrue(CustomHttpClient.shouldUseNtkWebViewFallbackForTest(true, true, "/manhwa/1/2",
                CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW));
        assertFalse(CustomHttpClient.shouldUseNtkWebViewFallbackForTest(true, true, "/manhwa/1/2",
                CustomHttpClient.FetchMode.DIRECT_ONLY));
        assertFalse(CustomHttpClient.shouldUseNtkWebViewFallbackForTest(true, true, "/manhwa/1/2",
                CustomHttpClient.FetchMode.CACHE_ONLY));
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
    public void ntkNativeAckPageRecoveryOnlyCoversRecoverableMisses() {
        assertTrue(CustomHttpClient.shouldAttemptNtkNativeAckPageRecoveryForTest(
                true, true, 0, "/webtoon/18768/1586501", CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW));
        assertTrue(CustomHttpClient.shouldAttemptNtkNativeAckPageRecoveryForTest(
                true, false, 403, "/manhwa/4127/251114", CustomHttpClient.FetchMode.DIRECT_ONLY));
        assertTrue(CustomHttpClient.shouldAttemptNtkNativeAckPageRecoveryForTest(
                true, false, 503, "/manhwa/8044/u-mp9phqym-9fo4", CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW));

        assertFalse(CustomHttpClient.shouldAttemptNtkNativeAckPageRecoveryForTest(
                true, false, 404, "/api/manhwa-list?page=1", CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW));
        assertFalse(CustomHttpClient.shouldAttemptNtkNativeAckPageRecoveryForTest(
                true, false, 403, "/manhwa?g=%EC%95%A1%EC%85%98", CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW));
        assertFalse(CustomHttpClient.shouldAttemptNtkNativeAckPageRecoveryForTest(
                true, false, 503, "/search?q=hero", CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW));
        assertFalse(CustomHttpClient.shouldAttemptNtkNativeAckPageRecoveryForTest(
                true, true, 0, "/api/ad/ack", CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW));
        assertFalse(CustomHttpClient.shouldAttemptNtkNativeAckPageRecoveryForTest(
                true, true, 0, "/webtoon/18768/1586501", CustomHttpClient.FetchMode.CACHE_ONLY));
        assertFalse(CustomHttpClient.shouldAttemptNtkNativeAckPageRecoveryForTest(
                false, true, 0, "/webtoon/18768/1586501", CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW));
    }

    @Test
    public void ntkRscNativeAckRecoveryCoversNavigableRscChallenges() {
        assertTrue(CustomHttpClient.shouldAttemptNtkRscNativeAckRecoveryForTest(
                true, true, "/manhwa/21701", CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW));
        assertTrue(CustomHttpClient.shouldAttemptNtkRscNativeAckRecoveryForTest(
                true, true, "/manhwa?g=%EC%86%8C%EB%85%84&page=6",
                CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW));
        assertTrue(CustomHttpClient.shouldAttemptNtkRscNativeAckRecoveryForTest(
                true, true, "/webtoon/18768/1586501", CustomHttpClient.FetchMode.DIRECT_ONLY));

        assertFalse(CustomHttpClient.shouldAttemptNtkRscNativeAckRecoveryForTest(
                true, false, "/manhwa/21701", CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW));
        assertFalse(CustomHttpClient.shouldAttemptNtkRscNativeAckRecoveryForTest(
                true, true, "/api/manhwa-list?page=1", CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW));
        assertFalse(CustomHttpClient.shouldAttemptNtkRscNativeAckRecoveryForTest(
                true, true, "/api/ad/ack", CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW));
        assertFalse(CustomHttpClient.shouldAttemptNtkRscNativeAckRecoveryForTest(
                true, true, "/manhwa/21701", CustomHttpClient.FetchMode.CACHE_ONLY));
        assertFalse(CustomHttpClient.shouldAttemptNtkRscNativeAckRecoveryForTest(
                false, true, "/manhwa/21701", CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW));
    }

    @Test
    public void ntkEpisodePriorityStartsWebViewFallbackInSharedMode() {
        CustomHttpClient.RequestGroup priority = new CustomHttpClient.RequestGroup().prioritizeWebViewFallback();
        CustomHttpClient.RequestGroup regular = new CustomHttpClient.RequestGroup();

        assertTrue(CustomHttpClient.shouldPrioritizeNtkEpisodeWebViewForTest(true, "/webtoon/work/episode",
                CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW, priority));
        assertFalse(CustomHttpClient.shouldPrioritizeNtkEpisodeWebViewForTest(true, "/webtoon/work",
                CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW, priority));
        assertFalse(CustomHttpClient.shouldPrioritizeNtkEpisodeWebViewForTest(true, "/webtoon/work/episode",
                CustomHttpClient.FetchMode.DIRECT_ONLY, priority));
        assertFalse(CustomHttpClient.shouldPrioritizeNtkEpisodeWebViewForTest(true, "/webtoon/work/episode",
                CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW, regular));
        assertFalse(CustomHttpClient.shouldPrioritizeNtkEpisodeWebViewForTest(false, "/webtoon/work/episode",
                CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW, priority));
    }

    @Test
    public void ntkViewerImageForbiddenRefreshesAckBeforeWebViewFallback() {
        assertFalse(CustomHttpClient.ntkViewerImagesNeedsAckRefreshForTest(403, "{\"ok\":false}"));
        assertTrue(CustomHttpClient.ntkViewerImagesNeedsAckRefreshForTest(200, "{\"ad_ack_required\":true}"));
        assertTrue(CustomHttpClient.ntkViewerImagesNeedsAckRefreshForTest(200, "{\"error\":\"ad_ack_required\"}"));
        assertFalse(CustomHttpClient.ntkViewerImagesNeedsAckRefreshForTest(200, "{\"ok\":false}"));
    }

    @Test
    public void ntkViewerImagesWaitForAckBeforeNumericWebtoonApi() {
        assertFalse(CustomHttpClient.shouldTryNtkViewerImagesBeforeAckForTest(
                "webtoon", "/webtoon/840894/1073395", false, false));

        assertFalse(CustomHttpClient.shouldTryNtkViewerImagesBeforeAckForTest(
                "webtoon", "/webtoon/840894/1073395", true, false));
        assertFalse(CustomHttpClient.shouldTryNtkViewerImagesBeforeAckForTest(
                "webtoon", "/webtoon/840894/1073395", false, true));
        assertFalse(CustomHttpClient.shouldTryNtkViewerImagesBeforeAckForTest(
                "webtoon", "/webtoon/840894/u-slug-1073395", false, false));
        assertFalse(CustomHttpClient.shouldTryNtkViewerImagesBeforeAckForTest(
                "manhwa", "/manhwa/840894/1073395", false, false));
    }

    @Test
    public void ntkViewerImageApiValidatesFirstVisiblePages() {
        assertEquals(1, CustomHttpClient.ntkViewerImageInitialValidationCountForTest(1));
        assertEquals(2, CustomHttpClient.ntkViewerImageInitialValidationCountForTest(2));
        assertEquals(2, CustomHttpClient.ntkViewerImageInitialValidationCountForTest(64));
    }

    @Test
    public void ntkHeaderBuildSyncsWebViewCookiesWhenClearanceIsMissing() {
        assertFalse(CustomHttpClient.shouldSkipWebViewCookieSyncForTest(true, false, false, false,
                CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW));
        assertTrue(CustomHttpClient.shouldSkipWebViewCookieSyncForTest(true, true, false, true,
                CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW));
        assertTrue(CustomHttpClient.shouldSkipWebViewCookieSyncForTest(true, false, true, false,
                CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW));
        assertTrue(CustomHttpClient.shouldSkipWebViewCookieSyncForTest(true, false, false, false,
                CustomHttpClient.FetchMode.DIRECT_ONLY));
        assertFalse(CustomHttpClient.shouldSkipWebViewCookieSyncForTest(false, false, false, false,
                CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW));
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
        assertFalse(NtkWebViewFallbackManager.shouldNavigateDocumentForTest("/search?q=onepiece"));
        assertFalse(NtkWebViewFallbackManager.shouldNavigateDocumentForTest("/cm?type1=genre"));
    }

    @Test
    public void ntkPageDirectUsesFastTimeoutForEpisodePagesAndApi() {
        assertTrue(CustomHttpClient.shouldUseFastNtkPageDirectForTest(true, "/manhwa/1/1",
                CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW));
        assertTrue(CustomHttpClient.shouldUseFastNtkPageDirectForTest(true, "/webtoon/1/1",
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
        assertTrue(CustomHttpClient.shouldUseNtkQuicPrimaryUrlForTest(
                "https://sbxh4.com/search?q=hero"));
        assertTrue(CustomHttpClient.shouldUseNtkQuicPrimaryUrlForTest(
                "https://img.sbxh4.com/images/1.jpg"));
        assertTrue(CustomHttpClient.shouldUseNtkDirectClientUrlForTest(
                "https://sbxh4.com/manhwa?page=2"));
        assertTrue(CustomHttpClient.shouldUseNtkDirectClientUrlForTest(
                "https://img.sbxh4.com/images/1.jpg"));
        assertFalse(CustomHttpClient.shouldUseNtkQuicPrimaryUrlForTest(
                "https://example.com/images/1.jpg"));
        assertFalse(CustomHttpClient.shouldUseNtkDirectClientUrlForTest(
                "https://example.com/images/1.jpg"));
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
    public void ntkPageResponseRejectsBlockedOkDocuments() {
        String challengePage = "<html lang=\"en-US\" dir=\"ltr\"><head></head>"
                + "<body>Verifying you are human. Cloudflare security service.</body></html>";
        String devtoolsBlocked = "<html><head><title>개발자 도구 차단</title></head>"
                + "<body>developer tools blocked</body></html>";

        assertTrue(CustomHttpClient.shouldRejectNtkPageResponseForTest(
                "/webtoon/18768/1586501", 200, challengePage));
        assertTrue(CustomHttpClient.shouldRejectNtkPageResponseForTest(
                "/manhwa/8044/u-mp9phqym-9fo4", 200, devtoolsBlocked));
        assertFalse(CustomHttpClient.shouldRejectNtkPageResponseForTest(
                "/init/theme.js", 200, challengePage));
    }

    @Test
    public void ntkNginxForbiddenIsHardBlockedForProtectedPaths() {
        String nginx403 = "<html><head><title>403 Forbidden</title></head>"
                + "<body><center><h1>403 Forbidden</h1></center>"
                + "<hr><center>nginx/1.24.0 (Ubuntu)</center></body></html>";

        assertTrue(CustomHttpClient.isNtkHardBlockedResponseForTest(
                "/manhwa/37043/1816201", 403, nginx403));
        assertTrue(CustomHttpClient.isNtkHardBlockedResponseForTest(
                "/api/manhwa-list?page=1", 403, nginx403));
        assertTrue(CustomHttpClient.isNtkHardBlockedResponseForTest(
                "/webtoon/840540/1546170", 403, "<html><body>trash0607</body></html>"));
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
        assertTrue(CustomHttpClient.isNtkUrlForTest("https://newto03.com/manhwa"));
        assertTrue(CustomHttpClient.isNtkUrlForTest("https://toonflix.app/manhwa"));
        assertFalse(CustomHttpClient.isNtkUrlForTest("https://example.com/manhwa"));
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

        assertFalse(CustomHttpClient.shouldRetryNtkWithResolvedDomainForTest(403, challenge));
        assertTrue(CustomHttpClient.shouldRetryNtkWithResolvedDomainForTest(
                403,
                "<html><body>plain forbidden</body></html>"));
        assertTrue(CustomHttpClient.shouldRetryNtkWithResolvedDomainForTest(404, ""));
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
        assertTrue(CustomHttpClient.fastNtkApiDirectTimeoutMsForTest() <= 1200L);
    }

    @Test
    public void ntkProbeDoesNotTreatCloudflareChallengeAsReachable() {
        String challenge = "<!DOCTYPE html><html><head><title>Just a moment...</title></head>"
                + "<body>checking your browser</body></html>";

        assertFalse(CustomHttpClient.isReachableNtkProbeResponseForTest(403, "", challenge));
        assertFalse(CustomHttpClient.isReachableNtkProbeResponseForTest(302, "https://t.me/newtoki_url", ""));
        assertTrue(CustomHttpClient.isReachableNtkProbeResponseForTest(200, "", "<html></html>"));
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
        assertTrue(CustomHttpClient.isNtkDnsProtectedHostForTest("toonflix.app"));
        assertTrue(CustomHttpClient.isNtkDnsProtectedHostForTest("img.toonflix.app"));
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
