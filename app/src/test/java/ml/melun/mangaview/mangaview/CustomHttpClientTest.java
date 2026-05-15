package ml.melun.mangaview.mangaview;

import org.junit.Test;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;

import okhttp3.Protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
    public void ntkWebViewFallbackOnlyHandlesPageMisses() {
        assertFalse(CustomHttpClient.shouldUseNtkWebViewFallbackForTest(true, true, "/api/manhwa-list"));
        assertTrue(CustomHttpClient.shouldUseNtkWebViewFallbackForTest(true, true, "/manhwa/1"));
        assertFalse(CustomHttpClient.shouldUseNtkWebViewFallbackForTest(false, true, "/api/manhwa-list"));
        assertFalse(CustomHttpClient.shouldUseNtkWebViewFallbackForTest(true, false, "/api/manhwa-list"));
        assertFalse(CustomHttpClient.shouldUseNtkWebViewFallbackForTest(true, true, "/_next/static/app.js"));
    }

    @Test
    public void ntkWebViewFallbackRequiresSharedWebViewMode() {
        assertTrue(CustomHttpClient.shouldUseNtkWebViewFallbackForTest(true, true, "/manhwa/1",
                CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW));
        assertFalse(CustomHttpClient.shouldUseNtkWebViewFallbackForTest(true, true, "/manhwa/1",
                CustomHttpClient.FetchMode.DIRECT_ONLY));
        assertFalse(CustomHttpClient.shouldUseNtkWebViewFallbackForTest(true, true, "/manhwa/1",
                CustomHttpClient.FetchMode.CACHE_ONLY));
    }

    @Test
    public void ntkPageDirectUsesFastTimeoutOnlyForEpisodePages() {
        assertTrue(CustomHttpClient.shouldUseFastNtkPageDirectForTest(true, "/manhwa/1/1",
                CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW));
        assertTrue(CustomHttpClient.shouldUseFastNtkPageDirectForTest(true, "/webtoon/1/1",
                CustomHttpClient.FetchMode.DIRECT_ONLY));
        assertFalse(CustomHttpClient.shouldUseFastNtkPageDirectForTest(true, "/api/manhwa-list",
                CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW));
        assertFalse(CustomHttpClient.shouldUseFastNtkPageDirectForTest(true, "/_next/static/app.js",
                CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW));
        assertFalse(CustomHttpClient.shouldUseFastNtkPageDirectForTest(false, "/manhwa/1/1",
                CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW));
        assertFalse(CustomHttpClient.shouldUseFastNtkPageDirectForTest(true, "/manhwa/1/1",
                CustomHttpClient.FetchMode.CACHE_ONLY));
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
    public void ntkColdStartStalePageCacheServesImmediately() {
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
    public void ntkUrlDetectionHandlesResolvedHosts() {
        assertTrue(CustomHttpClient.isNtkUrlForTest("https://sbxh1.com/manhwa"));
        assertTrue(CustomHttpClient.isNtkUrlForTest("https://img.sbxh1.com/manhwa/1"));
        assertFalse(CustomHttpClient.isNtkUrlForTest("https://example.com/manhwa"));
    }

    @Test
    public void ntkDnsProtectionCoversRootAndImageSubdomains() {
        assertTrue(CustomHttpClient.isNtkDnsProtectedHostForTest("sbxh1.com"));
        assertTrue(CustomHttpClient.isNtkDnsProtectedHostForTest("www.sbxh1.com"));
        assertTrue(CustomHttpClient.isNtkDnsProtectedHostForTest("img.sbxh1.com"));
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
    public void wfwfNumberedDnsDropsIpv6WhenIpv4Exists() throws Exception {
        InetAddress ipv4 = InetAddress.getByAddress("wfwf450.com",
                new byte[] {(byte)104, (byte)26, (byte)10, (byte)250});
        InetAddress ipv6 = InetAddress.getByAddress("wfwf450.com",
                new byte[] {
                        (byte)0x26, (byte)0x06, (byte)0x47, (byte)0x00,
                        (byte)0x00, (byte)0x20, (byte)0x00, (byte)0x00,
                        (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
                        (byte)0x68, (byte)0x1a, (byte)0x0b, (byte)0xfa});

        List<InetAddress> filtered = CustomHttpClient.wfwfIpv4OnlyOrOriginalForTest(Arrays.asList(ipv6, ipv4));

        assertEquals(1, filtered.size());
        assertEquals("104.26.10.250", filtered.get(0).getHostAddress());
    }

    @Test
    public void wfwfIpv4OnlyHostOnlyMatchesNumberedWfwfDomains() {
        assertTrue(CustomHttpClient.isWfwfDnsIpv4OnlyHostForTest("wfwf450.com"));
        assertTrue(CustomHttpClient.isWfwfDnsIpv4OnlyHostForTest("www.wfwf450.com"));
        assertFalse(CustomHttpClient.isWfwfDnsIpv4OnlyHostForTest("sbxh1.com"));
        assertFalse(CustomHttpClient.isWfwfDnsIpv4OnlyHostForTest("example.com"));
    }
}
