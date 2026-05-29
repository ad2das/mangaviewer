package ml.melun.mangaview.mangaview;

import org.junit.Test;

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
    public void ntkWebViewFallbackRequiresSharedWebViewMode() {
        assertTrue(CustomHttpClient.shouldUseNtkWebViewFallbackForTest(true, true, "/manhwa/1/2",
                CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW));
        assertFalse(CustomHttpClient.shouldUseNtkWebViewFallbackForTest(true, true, "/manhwa/1/2",
                CustomHttpClient.FetchMode.DIRECT_ONLY));
        assertFalse(CustomHttpClient.shouldUseNtkWebViewFallbackForTest(true, true, "/manhwa/1/2",
                CustomHttpClient.FetchMode.CACHE_ONLY));
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
        assertFalse(CustomHttpClient.shouldUseFastNtkPageDirectForTest(true, "/_next/static/app.js",
                CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW));
        assertFalse(CustomHttpClient.shouldUseFastNtkPageDirectForTest(false, "/manhwa/1/1",
                CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW));
        assertFalse(CustomHttpClient.shouldUseFastNtkPageDirectForTest(true, "/manhwa/1/1",
                CustomHttpClient.FetchMode.CACHE_ONLY));
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

        String cloudflare522Page = "<html><head><title>newtoki469.com | 522: Connection timed out</title></head>"
                + "<body>Connection timed out Error code 522 Cloudflare Host Error</body></html>";

        assertFalse(CustomHttpClient.isCacheablePageBodyForTest(errorPage));
        assertFalse(CustomHttpClient.isCacheablePageBodyForTest(challengePage));
        assertFalse(CustomHttpClient.isCacheablePageBodyForTest(cloudflare522Page));
        assertTrue(CustomHttpClient.isCacheablePageBodyForTest("<html><body><a href=\"/webtoon/17801/1\">1화</a></body></html>"));
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
    public void ntkForbiddenResponsesTriggerOfficialAddressRefresh() {
        String challenge = "<!DOCTYPE html><html><head><title>Just a moment...</title></head>"
                + "<body><script src=\"https://challenges.cloudflare.com/turnstile/v0/api.js\"></script></body></html>";

        assertTrue(CustomHttpClient.shouldRetryNtkWithResolvedDomainForTest(403, challenge));
        assertTrue(CustomHttpClient.shouldRetryNtkWithResolvedDomainForTest(
                403,
                "<html><body>plain forbidden</body></html>"));
        assertTrue(CustomHttpClient.shouldRetryNtkWithResolvedDomainForTest(404, ""));
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
    public void ntkDnsFallbackReturnsDirectEdgeWhenSystemDnsFails() {
        assertEquals("104.16.219.55",
                CustomHttpClient.ntkFallbackAddressesForTest("sbxh1.com").get(0).getHostAddress());
        assertEquals("104.16.219.55",
                CustomHttpClient.ntkFallbackAddressesForTest("img.sbxh1.com").get(0).getHostAddress());
        assertTrue(CustomHttpClient.ntkFallbackAddressesForTest("example.com").isEmpty());
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
                new byte[] {(byte)104, (byte)16, (byte)219, (byte)55});

        List<InetAddress> merged = CustomHttpClient.mergeIpv4FirstForTest("sbxh1.com",
                Arrays.asList(preferred), null, Arrays.asList(fallback));

        assertEquals("104.16.220.55", merged.get(0).getHostAddress());
        assertEquals("104.16.219.55", merged.get(1).getHostAddress());
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
