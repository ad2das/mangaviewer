package ml.melun.mangaview.mangaview;

import org.junit.Test;

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
    public void ntkWebViewFallbackOnlyHandlesPageAndApiMisses() {
        assertTrue(CustomHttpClient.shouldUseNtkWebViewFallbackForTest(true, true, "/api/manhwa-list"));
        assertTrue(CustomHttpClient.shouldUseNtkWebViewFallbackForTest(true, true, "/manhwa/1"));
        assertFalse(CustomHttpClient.shouldUseNtkWebViewFallbackForTest(false, true, "/api/manhwa-list"));
        assertFalse(CustomHttpClient.shouldUseNtkWebViewFallbackForTest(true, false, "/api/manhwa-list"));
        assertFalse(CustomHttpClient.shouldUseNtkWebViewFallbackForTest(true, true, "/_next/static/app.js"));
    }

    @Test
    public void ntkWebViewFallbackScriptUsesAsyncRequestBridge() {
        String script = CustomHttpClient.buildNtkWebViewFetchScriptForTest("/manhwa/1", "text/html");

        assertTrue(script.contains("window.NtkBridge.onResult"));
        assertTrue(script.contains("x.open('GET',\"/manhwa/1\",true)"));
        assertFalse(script.contains("x.open('GET',\"/manhwa/1\",false)"));
    }

    @Test
    public void ntkUrlDetectionHandlesResolvedHosts() {
        assertTrue(CustomHttpClient.isNtkUrlForTest("https://sbxh1.com/manhwa"));
        assertTrue(CustomHttpClient.isNtkUrlForTest("https://img.sbxh1.com/manhwa/1"));
        assertFalse(CustomHttpClient.isNtkUrlForTest("https://example.com/manhwa"));
    }
}
