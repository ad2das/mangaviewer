package ml.melun.mangaview.app

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import ml.melun.mangaview.source.SourceTransport
import org.junit.Assert.assertEquals
import org.junit.Test

class SeededProviderOriginTest {
    private class FakeOrigins(
        private val stored: String? = null,
        private val failure: Throwable? = null,
    ) : ProviderOrigins {
        override fun provider(url: String): String? = null

        override suspend fun current(provider: String, fallback: String): String {
            failure?.let { throw it }
            return stored ?: fallback
        }

        override suspend fun recover(provider: String, failed: String, transport: SourceTransport): String? = null

        override suspend fun observeRedirect(provider: String, finalOrigin: String, transport: SourceTransport) = Unit

        override fun remember(provider: String, origin: String) = Unit
    }

    @Test
    fun persistedOriginWins() = runBlocking {
        val origins = FakeOrigins(stored = "https://wfwf505.com")
        assertEquals("https://wfwf505.com", seededProviderOrigin(origins, "wfwf", "https://wfwf494.com"))
    }

    @Test
    fun fallbackUsedWhenNothingStored() = runBlocking {
        val origins = FakeOrigins()
        assertEquals("https://wfwf494.com", seededProviderOrigin(origins, "wfwf", "https://wfwf494.com"))
    }

    @Test
    fun storageFailureFallsBack() = runBlocking {
        val origins = FakeOrigins(failure = IOException("prefs unavailable"))
        assertEquals("https://wfwf494.com", seededProviderOrigin(origins, "wfwf", "https://wfwf494.com"))
    }

    @Test
    fun cancellationIsNotSwallowed() {
        val origins = FakeOrigins(failure = CancellationException("stop"))
        var thrown = false
        try {
            runBlocking { seededProviderOrigin(origins, "wfwf", "https://wfwf494.com") }
        } catch (expected: CancellationException) {
            thrown = true
        }
        assertEquals(true, thrown)
    }
}
