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
}
