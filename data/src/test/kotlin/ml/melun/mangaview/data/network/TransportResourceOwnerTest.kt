package ml.melun.mangaview.data.network

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportResourceOwnerTest {
    @Test
    fun closeReturnsEveryResourceAndRejectsLateRegistration() {
        val owner = TransportResourceOwner<Any>()
        val resources = List(100) { Any() }
        resources.forEach { assertTrue(owner.register(it)) }

        val closing = owner.closeAndSnapshot()

        assertEquals(resources.toSet(), closing.toSet())
        assertFalse(owner.register(Any()))
        closing.forEach(owner::complete)
        assertEquals(0, owner.size())
    }

    @Test
    fun registrationAndCloseRaceCannotLoseAnAcceptedResource() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(500) {
                val owner = TransportResourceOwner<Any>()
                val resource = Any()
                val gate = CountDownLatch(1)
                val registered = executor.submit(Callable {
                    gate.await()
                    owner.register(resource)
                })
                val closing = executor.submit(Callable {
                    gate.await()
                    owner.closeAndSnapshot()
                })
                gate.countDown()

                val accepted = registered.get()
                val snapshot = closing.get()
                assertTrue(!accepted || resource in snapshot)
            }
        } finally {
            executor.shutdownNow()
        }
    }
}
