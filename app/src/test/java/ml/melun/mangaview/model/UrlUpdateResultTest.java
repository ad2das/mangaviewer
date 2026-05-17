package ml.melun.mangaview.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UrlUpdateResultTest {
    @Test
    public void exposesSuccessAndUrlForJavaObservers() {
        UrlUpdateResult result = new UrlUpdateResult(true, "https://example.test/cm");

        assertTrue(result.getSuccess());
        assertEquals("https://example.test/cm", result.getUrl());
    }
}
