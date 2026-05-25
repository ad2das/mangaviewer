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
        assertFalse(NtkWebViewFallbackManager.shouldNavigateDocumentForTest("/api/manhwa-list"));
    }

    @Test
    public void documentFallbackIgnoresStaleBlankPageFinishes() {
        assertTrue(NtkWebViewFallbackManager.isFinishedDocumentUrlForTest(
                "https://sbxh1.com/webtoon/1/2", "https://sbxh1.com", "/webtoon/1/2"));
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
        assertTrue(NtkWebViewFallbackManager.webViewLoadTimeoutMsForTest()
                > NtkWebViewFallbackManager.documentReadyWaitMsForTest());
        assertTrue(NtkWebViewFallbackManager.callerWaitTimeoutMsForTest()
                > NtkWebViewFallbackManager.webViewLoadTimeoutMsForTest());
    }
}
