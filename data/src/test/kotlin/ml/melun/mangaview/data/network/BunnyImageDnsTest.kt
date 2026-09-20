package ml.melun.mangaview.data.network

import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Test

class BunnyImageDnsTest {
    private val v4 = InetAddress.getByAddress(byteArrayOf(143.toByte(), 244.toByte(), 50, 212.toByte()))
    private val v6 = InetAddress.getByAddress(ByteArray(16).apply { this[15] = 0x7c })

    @Test
    fun zoneHostsResolveTheMatchingBunnyPullZone() {
        val asked = mutableListOf<String>()
        val dns = BunnyImageDns(resolver = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                asked += hostname
                return listOf(v4)
            }
        })
        assertEquals(listOf(v4), dns.lookup("user281.quicksharefiles.top"))
        assertEquals(listOf("user281.b-cdn.net"), asked)
    }

    @Test
    fun pullZoneFailureFallsBackToTheEdgeApex() {
        val asked = mutableListOf<String>()
        val dns = BunnyImageDns(resolver = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                asked += hostname
                if (hostname == "user281.b-cdn.net") throw UnknownHostException(hostname)
                return listOf(v4)
            }
        })
        assertEquals(listOf(v4), dns.lookup("user281.quicksharefiles.top"))
        assertEquals(listOf("user281.b-cdn.net", "b-cdn.net"), asked)
    }

    @Test
    fun hostsOutsideTheZoneKeepTheSystemLookup() {
        val asked = mutableListOf<String>()
        val dns = BunnyImageDns(resolver = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                asked += hostname
                return listOf(v4, v6)
            }
        })
        assertEquals(listOf(v4, v6), dns.lookup("newxtoon1.com"))
        assertEquals(listOf("newxtoon1.com"), asked)
    }

    @Test
    fun dualStackEdgeAnswersPreferIpv4() {
        val dns = BunnyImageDns(resolver = object : Dns {
            override fun lookup(hostname: String) = listOf(v6, v4)
        })
        assertEquals(listOf(v4), dns.lookup("user281.quicksharefiles.top"))
    }
}
