package ml.melun.mangaview.data.network

import java.util.concurrent.ConcurrentHashMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedNetworkHintsTest {
    @Test
    fun routeObservationsResetWholesaleWhenTheHintMapIsFull() {
        val observations = ConcurrentHashMap<String, RouteObservation>()
        (0 until 128).forEach { observations["host-$it"] = RouteObservation(it % 3, it.toLong()) }
        resetRouteObservationsWhenFull(observations, "new-host", 128)
        assertTrue(observations.isEmpty())
        observations["new-host"] = RouteObservation(1, 1L)
        resetRouteObservationsWhenFull(observations, "new-host", 128)
        assertEquals(1, observations.size)
    }

    @Test
    fun workingMirrorsResetWholesaleOnlyForANewHost() {
        val mirrors = ConcurrentHashMap<String, String>()
        (0 until 128).forEach { mirrors["host-$it"] = "mirror-$it" }
        recordWorkingMirror(mirrors, "host-5", "mirror-5b", 128)
        assertEquals(128, mirrors.size)
        assertEquals("mirror-5b", mirrors["host-5"])
        recordWorkingMirror(mirrors, "new-host", "mirror-new", 128)
        assertEquals(1, mirrors.size)
        assertEquals("mirror-new", mirrors["new-host"])
    }
}
