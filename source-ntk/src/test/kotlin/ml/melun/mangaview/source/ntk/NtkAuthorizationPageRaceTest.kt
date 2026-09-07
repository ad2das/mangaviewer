package ml.melun.mangaview.source.ntk

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkAuthorizationPageRaceTest {
    @Test
    fun aSelectedPageIsReleasedIfTheCallerCancelsDuringAuthorizationCleanup() = runTest {
        val cleanup = CompletableDeferred<Unit>()
        val page = Page()
        val opened = async {
            raceNtkAuthorizationAndPage(
                awaitAuthorization = {
                    try { awaitCancellation() } finally { withContext(NonCancellable) { cleanup.await() } }
                },
                attempt = { Result.success(page) }, release = Page::close,
            )
        }
        testScheduler.runCurrent()
        assertTrue(!opened.isCompleted)
        opened.cancel()
        cleanup.complete(Unit)
        opened.join()
        assertEquals(1, page.closes)
    }

    @Test
    fun simultaneousAuthorizationAndPageCompletionTransfersOrClosesEveryPageOnce() = runTest {
        repeat(100) { iteration ->
            val authorization = CompletableDeferred<Boolean>()
            val created = mutableListOf<Page>()
            val winner = raceNtkAuthorizationAndPage(
                awaitAuthorization = { authorization.await() },
                attempt = {
                    if (created.isEmpty()) {
                        authorization.complete(true)
                        if (iteration % 2 == 0) yield()
                    }
                    Result.success(Page().also(created::add))
                },
                release = Page::close,
            )
            assertEquals(0, winner.closes)
            winner.close()
            assertTrue(created.all { it.closes == 1 })
        }
    }

    @Test
    fun authorizationRejectionDoesNotCreateAnotherImageAttempt() = runTest {
        var attempts = 0
        val failure = IOException("provider rejected page")
        val result = runCatching {
            raceNtkAuthorizationAndPage<Page>(
                awaitAuthorization = { yield(); false },
                attempt = { attempts++; Result.failure(failure) }, release = Page::close,
            )
        }
        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals(failure.message, result.exceptionOrNull()?.message)
        assertEquals(1, attempts)
    }

    private class Page {
        var closes = 0
        fun close() { closes++ }
    }
}
