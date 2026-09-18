package ml.melun.mangaview.data.network

import java.net.InetAddress
import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderImageDnsTest {
    private val v4 = InetAddress.getByAddress(byteArrayOf(104, 21, 77, 124))
    private val v6 = InetAddress.getByAddress(ByteArray(16).apply { this[15] = 0x7c })

    @Test
    fun dualStackAnswersKeepOnlyTheIpv4Family() {
        val dns = ProviderImageDns(resolver = object : Dns {
            override fun lookup(hostname: String) = listOf(v6, v4)
        })
        assertEquals(listOf(v4), dns.lookup("aws-cdn9.site"))
    }

    @Test
    fun ipv6OnlyAnswersPassThroughUnchanged() {
        val dns = ProviderImageDns(resolver = object : Dns {
            override fun lookup(hostname: String) = listOf(v6)
        })
        assertEquals(listOf(v6), dns.lookup("aws-cdn9.site"))
    }
}
