package ml.melun.mangaview.mangaview;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NtkWebViewFallbackManagerTest {
    @Test
    public void fetchKeyCombinesBaseUrlAndPathForSingleFlight() {
        assertEquals("https://sbxh1.com/manhwa/1",
                NtkWebViewFallbackManager.fetchKeyForTest("https://sbxh1.com", "/manhwa/1"));
    }

    @Test
    public void documentFallbackNavigatesViewerPagesDirectly() {
        assertTrue(NtkWebViewFallbackManager.shouldNavigateDocumentForTest("/manhwa/1"));
        assertTrue(NtkWebViewFallbackManager.shouldNavigateDocumentForTest("/webtoon/1/2"));
        assertTrue(NtkWebViewFallbackManager.shouldNavigateDocumentForTest("/ing?page=2"));
        assertTrue(NtkWebViewFallbackManager.shouldNavigateDocumentForTest("/end?sort=hot"));
        assertTrue(NtkWebViewFallbackManager.shouldNavigateDocumentForTest("/manhwa?page=2"));
        assertTrue(NtkWebViewFallbackManager.shouldNavigateDocumentForTest("/manhwa-end?g=%EC%95%A1%EC%85%98"));
        assertFalse(NtkWebViewFallbackManager.shouldNavigateDocumentForTest("/api/manhwa-list"));
    }

    @Test
    public void documentFallbackResetsWebViewAfterHtmlCapture() {
        assertTrue(NtkWebViewFallbackManager.shouldResetWebViewAfterFetchForTest("/manhwa/1", 200));
        assertTrue(NtkWebViewFallbackManager.shouldResetWebViewAfterFetchForTest("/manhwa?page=2", 200));
        assertTrue(NtkWebViewFallbackManager.shouldResetWebViewAfterFetchForTest("/webtoon/1/2", 200));
        assertTrue(NtkWebViewFallbackManager.shouldResetWebViewAfterFetchForTest("/api/manhwa-list", 0));
        assertFalse(NtkWebViewFallbackManager.shouldResetWebViewAfterFetchForTest("/api/manhwa-list", 200));
    }

    @Test
    public void documentFallbackIgnoresStaleBlankPageFinishes() {
        assertTrue(NtkWebViewFallbackManager.isFinishedDocumentUrlForTest(
                "https://sbxh1.com/webtoon/1/2", "https://sbxh1.com", "/webtoon/1/2"));
        assertTrue(NtkWebViewFallbackManager.isFinishedDocumentUrlForTest(
                "https://sbxh1.com/webtoon/1/2/", "https://sbxh1.com", "/webtoon/1/2"));
        assertFalse(NtkWebViewFallbackManager.isFinishedDocumentUrlForTest(
                "about:blank", "https://sbxh1.com", "/webtoon/1/2"));
        assertFalse(NtkWebViewFallbackManager.isFinishedDocumentUrlForTest(
                "https://sbxh1.com/", "https://sbxh1.com", "/webtoon/1/2"));
    }

    @Test
    public void callerWaitStopsOnCancellationOrTimeout() {
        assertTrue(NtkWebViewFallbackManager.shouldStopWaitingForCallerForTest(true, 1000L, 2000L));
        assertTrue(NtkWebViewFallbackManager.shouldStopWaitingForCallerForTest(false, 2000L, 2000L));
        assertFalse(NtkWebViewFallbackManager.shouldStopWaitingForCallerForTest(false, 1999L, 2000L));
    }

    @Test
    public void documentFallbackLeavesTimeForWebViewStartupAndRenderedHtmlExtraction() {
        assertTrue(NtkWebViewFallbackManager.webViewLoadTimeoutMsForTest() >= 22_000L);
        assertTrue(NtkWebViewFallbackManager.documentReadyWaitMsForTest() >= 18_000L);
        assertTrue(NtkWebViewFallbackManager.hiddenChallengeWaitMsForTest()
                < NtkWebViewFallbackManager.documentReadyWaitMsForTest());
        assertTrue(NtkWebViewFallbackManager.webViewLoadTimeoutMsForTest()
                > NtkWebViewFallbackManager.documentReadyWaitMsForTest());
        assertTrue(NtkWebViewFallbackManager.callerWaitTimeoutMsForTest()
                > NtkWebViewFallbackManager.webViewLoadTimeoutMsForTest());
    }

    @Test
    public void hiddenDocumentFallbackRecognizesTerminalBlockedPages() {
        assertTrue(NtkWebViewFallbackManager.isBlockedNtkDocumentBodyForTest(
                "<html><body>Verifying you are human. Cloudflare security service.</body></html>"));
        assertTrue(NtkWebViewFallbackManager.isBlockedNtkDocumentBodyForTest(
                "<html><head><title>개발자 도구 차단</title></head></html>"));
        assertTrue(NtkWebViewFallbackManager.isBlockedNtkDocumentBodyForTest(
                "<html><head><title>403 Forbidden</title></head><body><center><h1>403 Forbidden</h1></center><hr><center>nginx/1.24.0</center></body></html>"));
        assertFalse(NtkWebViewFallbackManager.isBlockedNtkDocumentBodyForTest(
                "<html><body><img src=\"/webtoon_uploads/1.jpg\"></body></html>"));
    }

    @Test
    public void priorityWolfDocumentFallbackFailsFastOnBlankWebView() {
        assertTrue(NtkWebViewFallbackManager.documentReadyWaitMsForTest(true, true)
                < NtkWebViewFallbackManager.documentReadyWaitMsForTest());
        assertTrue(NtkWebViewFallbackManager.webViewLoadTimeoutMsForTest(true, true)
                < NtkWebViewFallbackManager.webViewLoadTimeoutMsForTest());
        assertEquals(NtkWebViewFallbackManager.documentReadyWaitMsForTest(),
                NtkWebViewFallbackManager.documentReadyWaitMsForTest(false, true));
    }

    @Test
    public void viewerQuicBridgeDoesNotHijackCloudflareChallengePosts() {
        String script = NtkWebViewFallbackManager.ntkQuicBridgeJavascriptForTest();

        assertTrue(script.contains("if(x.pathname.indexOf('/cdn-cgi/challenge-platform/')===0)return false;"));
        assertTrue(script.contains("return String(m||'GET').toUpperCase()!=='GET';"));
    }

    @Test
    public void strictAckProofRequiresActualAdAckPost() {
        assertTrue(NtkWebViewFallbackManager.isStrictAdAckSuccessSourceForTest("native-fetch-ack-200"));
        assertTrue(NtkWebViewFallbackManager.isStrictAdAckSuccessSourceForTest("guard-bridge-ack-200"));
        assertTrue(NtkWebViewFallbackManager.isStrictAdAckSuccessSourceForTest("captcha-webview-ack-200"));

        assertFalse(NtkWebViewFallbackManager.isStrictAdAckSuccessSourceForTest(
                "native-challenge-ad-ack-cookie-200"));
        assertFalse(NtkWebViewFallbackManager.isStrictAdAckSuccessSourceForTest(
                "native-prepare-challenge-ad-ack-cookie-200"));
        assertFalse(NtkWebViewFallbackManager.isStrictAdAckSuccessSourceForTest(
                "bridge-challenge-ad-ack-cookie-200"));
        assertFalse(NtkWebViewFallbackManager.isStrictAdAckSuccessSourceForTest(
                "captcha-bridge-challenge-ad-ack-cookie-200"));
    }

    @Test
    public void viewerImageApiNormalizesRootPageImagesToEpisodePath() {
        assertEquals("https://moamoabon.com/blacktoon/episodes/16968/1463195/p001.jpg",
                NtkWebViewFallbackManager.normalizeViewerImageApiSrcForTest(
                        "https://moamoabon.com/p001.jpg", "webtoon", "16968", "1463195"));
        assertEquals("https://moamoabon.com/blacktoon/episodes/16968/1463195/p002.webp",
                NtkWebViewFallbackManager.normalizeViewerImageApiSrcForTest(
                        "moamoabon.com/p002.webp", "webtoon", "16968", "1463195"));
        assertEquals("https://moamoabon.com/manhwa/36525/1807424/p001.jpg",
                NtkWebViewFallbackManager.normalizeViewerImageApiSrcForTest(
                        "/p001.jpg", "manhwa", "36525", "1807424"));
        assertEquals("https://moamoabon.com/blacktoon/episodes/16968/1463195/p001.jpg",
                NtkWebViewFallbackManager.normalizeViewerImageApiSrcForTest(
                        "https://moamoabon.com/black/episodes/16968/1463195/p001.jpg",
                        "webtoon", "16968", "1463195"));
    }
}
