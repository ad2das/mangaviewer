package ml.melun.mangaview.data.network

import java.net.InetAddress
import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidIpv4FirstDnsTest {
    @Test
    fun mixedFallbackKeepsIpv4FirstAcrossAllRoutePools() {
        val v4a = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val v4b = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 2))
        val v6a = InetAddress.getByAddress(ByteArray(16).apply { this[15] = 1 })
        val v6b = InetAddress.getByAddress(ByteArray(16).apply { this[15] = 2 })
        val mixed = listOf(v6a, v4a, v6b, v4b)
        for (offset in listOf(0, 1, 2, -1, Int.MIN_VALUE)) {
            val expected = if (Math.floorMod(offset, 2) == 0) listOf(v4a, v4b, v6a, v6b)
                else listOf(v4b, v4a, v6b, v6a)
            assertEquals(expected, ipv4FirstAddressOrder(mixed, offset))
            val resolver = object : Dns { override fun lookup(hostname: String) = mixed }
            val dns = AndroidIpv4FirstDns(resolver = resolver, fixedAddressOffset = offset)
            assertEquals(expected, dns.lookup("fixture.invalid"))
        }
    }

    @Test
    fun singleFamilyAnswersRemainUsableAndRotated() {
        val v6a = InetAddress.getByAddress(ByteArray(16).apply { this[15] = 1 })
        val v6b = InetAddress.getByAddress(ByteArray(16).apply { this[15] = 2 })
        assertEquals(listOf(v6b, v6a), ipv4FirstAddressOrder(listOf(v6a, v6b), 1))
        val v4 = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        assertEquals(listOf(v4), ipv4FirstAddressOrder(listOf(v4), 2))
        assertEquals(emptyList<InetAddress>(), ipv4FirstAddressOrder(emptyList(), 1))
    }

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
