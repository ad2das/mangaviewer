package ml.melun.mangaview.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlUpdateResultTest {
    @Test
    fun exposesSuccessAndUrlForJavaObservers() {
        val result = UrlUpdateResult(true, "https://example.test/cm")

        assertTrue(result.success)
        assertEquals("https://example.test/cm", result.url)
    }
}
