package ml.melun.mangaview.mangaview;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NtkWebViewFallbackManagerTest {
    @Test
    public void strictEpisodeAuthorityPathsNeverNavigateTheSharedDocumentWebView() {
        String[] strictEpisodePaths = {
                "/manhwa/33727/1692251",
                "/manhwa/33727/1692251/",
                "/webtoon/850236/nv-850236-11",
                "/webtoon/68630031/kp-68630031-69262979",
                "/webtoon/work/episode?from=reader#top",
                "https://sbxh9.com/manhwa/work-slug/episode-slug?from=reader#top",
                "HTTPS://SBXH9.COM/WEBTOON/68630031/KP-68630031-69262979/"
        };

        for(String path : strictEpisodePaths) {
            assertTrue(path, NtkWebViewFallbackManager.isStrictNtkEpisodeAuthorityPathForTest(path));
            assertFalse(path, NtkWebViewFallbackManager.shouldNavigateDocumentForTest(path));
        }
        assertFalse(NtkWebViewFallbackManager.isStrictNtkEpisodeAuthorityPathForTest(
                "/webtoon/850236"));
        assertFalse(NtkWebViewFallbackManager.isStrictNtkEpisodeAuthorityPathForTest(
                "/api/webtoon-images"));
    }

    @Test
    public void fetchKeyCombinesBaseUrlAndPathForSingleFlight() {
        assertEquals("https://sbxh1.com/manhwa/1",
                NtkWebViewFallbackManager.fetchKeyForTest("https://sbxh1.com", "/manhwa/1"));
    }

    @Test
    public void documentFallbackNavigatesOnlyTitleCategorySearchAndWolfPages() {
        assertTrue(NtkWebViewFallbackManager.shouldNavigateDocumentForTest("/manhwa/1"));
        assertFalse(NtkWebViewFallbackManager.shouldNavigateDocumentForTest("/webtoon/1/2"));
        assertTrue(NtkWebViewFallbackManager.shouldNavigateDocumentForTest("/ing?page=2"));
        assertTrue(NtkWebViewFallbackManager.shouldNavigateDocumentForTest("/end?sort=hot"));
        assertTrue(NtkWebViewFallbackManager.shouldNavigateDocumentForTest("/manhwa?page=2"));
        assertTrue(NtkWebViewFallbackManager.shouldNavigateDocumentForTest("/manhwa-end?g=%EC%95%A1%EC%85%98"));
        assertTrue(NtkWebViewFallbackManager.shouldNavigateDocumentForTest("/search?q=%EB%91%98%EC%A7%B8"));
        assertFalse(NtkWebViewFallbackManager.shouldNavigateDocumentForTest("/api/manhwa-list"));
    }

    @Test
    public void documentFallbackResetsWebViewAfterHtmlCapture() {
        assertTrue(NtkWebViewFallbackManager.shouldResetWebViewAfterFetchForTest("/manhwa/1", 200));
        assertTrue(NtkWebViewFallbackManager.shouldResetWebViewAfterFetchForTest("/manhwa?page=2", 200));
        assertFalse(NtkWebViewFallbackManager.shouldResetWebViewAfterFetchForTest("/webtoon/1/2", 200));
        assertTrue(NtkWebViewFallbackManager.shouldResetWebViewAfterFetchForTest("/api/manhwa-list", 0));
        assertFalse(NtkWebViewFallbackManager.shouldResetWebViewAfterFetchForTest("/api/manhwa-list", 200));
    }

    @Test
    public void documentFallbackIgnoresStaleBlankPageFinishes() {
        assertTrue(NtkWebViewFallbackManager.isFinishedDocumentUrlForTest(
                "https://sbxh1.com/webtoon/1", "https://sbxh1.com", "/webtoon/1"));
        assertTrue(NtkWebViewFallbackManager.isFinishedDocumentUrlForTest(
                "https://sbxh1.com/webtoon/1/", "https://sbxh1.com", "/webtoon/1"));
        assertFalse(NtkWebViewFallbackManager.isFinishedDocumentUrlForTest(
                "about:blank", "https://sbxh1.com", "/webtoon/1"));
        assertFalse(NtkWebViewFallbackManager.isFinishedDocumentUrlForTest(
                "https://sbxh1.com/", "https://sbxh1.com", "/webtoon/1"));
        assertTrue(NtkWebViewFallbackManager.isFinishedDocumentUrlForTest(
                "https://sbxh1.com/search?q=%EB%91%98%EC%A7%B8&kind=webtoon",
                "https://sbxh1.com", "/search?q=%EB%91%98%EC%A7%B8&kind=webtoon"));
        assertFalse(NtkWebViewFallbackManager.isFinishedDocumentUrlForTest(
                "https://sbxh1.com/search", "https://sbxh1.com",
                "/search?q=%EB%91%98%EC%A7%B8&kind=webtoon"));
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
        assertFalse(NtkWebViewFallbackManager.isBlockedNtkDocumentBodyForTest(
                "<html><body><a class=\"ep-row-v2-link\" href=\"/webtoon/850236/nv-850236-11\">11화</a>"
                        + "<script>const dormant = '개발자 도구 차단';</script></body></html>"));
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

}
