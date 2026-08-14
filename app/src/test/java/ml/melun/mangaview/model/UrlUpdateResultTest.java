package ml.melun.mangaview.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UrlUpdateResultTest {
    @Test
    public void exposesSuccessAndUrlForJavaObservers() {
        UrlUpdateResult result = new UrlUpdateResult(
                true,
                "https://example.test/cm",
                "https://request.example.test");

        assertTrue(result.getSuccess());
        assertEquals("https://example.test/cm", result.getUrl());
        assertEquals("https://request.example.test", result.getRequestUrl());
        assertTrue(result.isForRequest("https://request.example.test"));
        assertFalse(result.isForRequest("https://stale.example.test"));
        assertFalse(result.isForRequest(null));
    }
}
