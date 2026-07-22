package ml.melun.mangaview.reader

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class NtkSingleFlightDnsTest {
    @Test
    fun concurrentColdLookupsShareOnePhysicalResolverCallAndExpireNormally() {
        val calls = AtomicInteger()
        val release = CountDownLatch(1)
        val clock = AtomicLong(10L)
        val address = InetAddress.getByAddress("booktoki8.org", byteArrayOf(1, 2, 3, 4))
        val dns = NtkSingleFlightDns(
            delegate = object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    calls.incrementAndGet()
                    release.await(2L, TimeUnit.SECONDS)
                    return listOf(address)
                }
            },
            resultTtlMs = 1_000L,
            clockMs = clock::get,
        )
        val executor = Executors.newFixedThreadPool(16)
        try {
            val start = CountDownLatch(1)
            val results = (0 until 32).map {
                executor.submit<List<InetAddress>> {
                    start.await()
                    dns.lookup("booktoki8.org")
                }
            }
            start.countDown()
            while (calls.get() == 0) Thread.yield()
            release.countDown()
            results.forEach { assertEquals(listOf(address), it.get(2L, TimeUnit.SECONDS)) }
            assertEquals(1, calls.get())

            clock.addAndGet(1_001L)
            assertEquals(listOf(address), dns.lookup("booktoki8.org"))
            assertEquals(2, calls.get())
        } finally {
            executor.shutdownNow()
        }
    }
}
