package ml.melun.mangaview.reader

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkHostPresentationGateTest {
    @Test
    fun disabledGateRejectsEveryLaterPublication() {
        val gate = NtkHostPresentationGate()
        val publications = AtomicInteger()

        assertTrue(gate.runIfEnabled { publications.incrementAndGet() })
        gate.setEnabled(false)

        repeat(10_000) {
            assertFalse(gate.runIfEnabled { publications.incrementAndGet() })
        }
        assertTrue(publications.get() == 1)
    }

    @Test
    fun disablingWaitsForInFlightPublicationAndFormsAClosedBoundary() {
        val gate = NtkHostPresentationGate()
        val publicationEntered = CountDownLatch(1)
        val releasePublication = CountDownLatch(1)
        val disableStarted = CountDownLatch(1)
        val disableCompleted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val publication = executor.submit<Boolean> {
                gate.runIfEnabled {
                    publicationEntered.countDown()
                    assertTrue(releasePublication.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(publicationEntered.await(5, TimeUnit.SECONDS))

            val disable = executor.submit {
                disableStarted.countDown()
                gate.setEnabled(false)
                disableCompleted.countDown()
            }
            assertTrue(disableStarted.await(5, TimeUnit.SECONDS))
            assertFalse(disableCompleted.await(100, TimeUnit.MILLISECONDS))

            releasePublication.countDown()
            assertTrue(publication.get(5, TimeUnit.SECONDS))
            disable.get(5, TimeUnit.SECONDS)
            assertTrue(disableCompleted.await(5, TimeUnit.SECONDS))
            assertFalse(gate.runIfEnabled { error("publication crossed closed boundary") })
        } finally {
            executor.shutdownNow()
        }
    }
}
