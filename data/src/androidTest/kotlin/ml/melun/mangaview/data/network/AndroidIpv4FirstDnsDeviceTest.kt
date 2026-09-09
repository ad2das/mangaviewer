package ml.melun.mangaview.data.network

import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.Inet4Address
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidIpv4FirstDnsDeviceTest {
    @Test fun systemLookupCompletesWhileMainLooperIsOccupied() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        try {
            Handler(Looper.getMainLooper()).post {
                entered.countDown()
                try { release.await(3, TimeUnit.SECONDS) } finally { finished.countDown() }
            }
            assertTrue("Main-thread gate did not start", entered.await(1, TimeUnit.SECONDS))
            // localhost uses the platform resolver without depending on an external DNS server.
            // DnsResolver's FD readiness callback would wait for the occupied main Looper.
            val lookup = worker.submit<List<java.net.InetAddress>> {
                AndroidIpv4FirstDns(fixedAddressOffset = 0).lookup("localhost")
            }
            val addresses = lookup.get(1, TimeUnit.SECONDS)
            assertTrue(addresses.isNotEmpty())
            assertTrue(addresses.all { it.isLoopbackAddress })
            if (addresses.any { it is Inet4Address }) assertTrue(addresses.first() is Inet4Address)
        } finally {
            release.countDown()
            worker.shutdownNow()
            assertTrue("Main-thread gate did not release", finished.await(2, TimeUnit.SECONDS))
            assertTrue("DNS worker did not terminate", worker.awaitTermination(2, TimeUnit.SECONDS))
        }
    }
}
