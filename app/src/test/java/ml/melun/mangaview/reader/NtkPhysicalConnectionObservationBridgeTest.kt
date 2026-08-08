package ml.melun.mangaview.reader

import okhttp3.Connection
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class NtkPhysicalConnectionObservationBridgeTest {
    @After
    fun tearDown() {
        NtkPhysicalConnectionObservationBridge.clear()
    }

    @Test
    fun `records a physical client once and terminal take removes it`() {
        val client = OkHttpClient()
        val connection = fakeConnection()

        NtkPhysicalConnectionObservationBridge.record(41L, connection, client)

        val observation = NtkPhysicalConnectionObservationBridge.take(41L)
        assertNotNull(observation)
        assertTrue(observation!!.connectionId.isNotBlank())
        assertTrue(observation.clientInstanceId.isNotBlank())
        assertFalse(observation.reused)
        assertNull(NtkPhysicalConnectionObservationBridge.take(41L))
    }

    @Test
    fun `reusing a connection keeps one client identity and marks subsequent operation reused`() {
        val client = OkHttpClient()
        val connection = fakeConnection()

        NtkPhysicalConnectionObservationBridge.record(1L, connection, client)
        NtkPhysicalConnectionObservationBridge.record(2L, connection, client)

        val first = checkNotNull(NtkPhysicalConnectionObservationBridge.take(1L))
        val second = checkNotNull(NtkPhysicalConnectionObservationBridge.take(2L))
        assertEquals(first.connectionId, second.connectionId)
        assertEquals(first.clientInstanceId, second.clientInstanceId)
        assertTrue(second.reused)
    }

    @Test
    fun `clear retires unconsumed observations`() {
        NtkPhysicalConnectionObservationBridge.record(7L, fakeConnection(), OkHttpClient())
        NtkPhysicalConnectionObservationBridge.clear()
        assertNull(NtkPhysicalConnectionObservationBridge.take(7L))
    }

    @Test
    fun `request headers end releases one registered adjacent callback exactly once`() {
        val calls = AtomicInteger(0)
        assertTrue(
            NtkPhysicalConnectionObservationBridge.registerAdjacentRequestHeadersEnd(11L) {
                calls.incrementAndGet()
            },
        )
        assertFalse(
            NtkPhysicalConnectionObservationBridge.registerAdjacentRequestHeadersEnd(11L) {
                calls.incrementAndGet()
            },
        )

        NtkPhysicalConnectionObservationBridge.signalAdjacentRequestHeadersEnd(11L)
        NtkPhysicalConnectionObservationBridge.signalAdjacentRequestHeadersEnd(11L)

        assertEquals(1, calls.get())
    }

    @Test
    fun `retired adjacent callback cannot fire on a late connection`() {
        val calls = AtomicInteger(0)
        assertTrue(
            NtkPhysicalConnectionObservationBridge.registerAdjacentRequestHeadersEnd(12L) {
                calls.incrementAndGet()
            },
        )
        NtkPhysicalConnectionObservationBridge.cancelAdjacentRequestHeadersEnd(12L)

        NtkPhysicalConnectionObservationBridge.signalAdjacentRequestHeadersEnd(12L)

        assertEquals(0, calls.get())
    }

    @Suppress("UNCHECKED_CAST")
    private fun fakeConnection(): Connection = java.lang.reflect.Proxy.newProxyInstance(
        Connection::class.java.classLoader,
        arrayOf(Connection::class.java),
    ) { _, _, _ -> null } as Connection
}
