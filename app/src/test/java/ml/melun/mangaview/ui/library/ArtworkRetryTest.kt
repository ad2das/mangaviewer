package ml.melun.mangaview.ui.library

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtworkRetryTest {
    @Test fun retriesUntilLoadSucceeds() = runBlocking {
        var attempts = 0
        val result = retryArtworkLoad(4, 1L) {
            attempts++
            if (attempts < 3) null else "cover"
        }
        assertEquals("cover", result)
        assertEquals(3, attempts)
    }

    @Test fun givesUpAfterBoundedAttempts() = runBlocking {
        var attempts = 0
        val result = retryArtworkLoad(3, 1L) {
            attempts++
            null
        }
        assertNull(result)
        assertEquals(3, attempts)
    }
}
