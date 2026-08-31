package ml.melun.mangaview.data.network

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidIpv4FirstDnsTest {
    @Test
    fun retriesRotateEveryResolvedAddressToTheFront() {
        val addresses = listOf("edge-a", "edge-b", "edge-c")

        assertEquals(listOf("edge-a", "edge-b", "edge-c"), rotatedAddressOrder(addresses, 0))
        assertEquals(listOf("edge-b", "edge-c", "edge-a"), rotatedAddressOrder(addresses, 1))
        assertEquals(listOf("edge-c", "edge-a", "edge-b"), rotatedAddressOrder(addresses, 2))
        assertEquals(listOf("edge-a", "edge-b", "edge-c"), rotatedAddressOrder(addresses, 3))
        assertEquals(listOf("edge-c", "edge-a", "edge-b"), rotatedAddressOrder(addresses, -1))
    }
}
