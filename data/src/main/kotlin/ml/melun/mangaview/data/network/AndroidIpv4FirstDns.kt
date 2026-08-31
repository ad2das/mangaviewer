package ml.melun.mangaview.data.network

import android.net.DnsResolver
import android.os.Build
import android.os.CancellationSignal
import androidx.annotation.RequiresApi
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Dns

internal class AndroidIpv4FirstDns(
    private val fallback: Dns = Dns.SYSTEM,
) : Dns {
    private val rotations = ConcurrentHashMap<String, AtomicInteger>()

    override fun lookup(hostname: String): List<InetAddress> {
        if (hostname.isBlank()) throw UnknownHostException("hostname == null")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return fallback.lookup(hostname)
        val ipv4 = try {
            queryIpv4(hostname)
        } catch (_: RuntimeException) {
            emptyList()
        }
        val resolved = ipv4.ifEmpty { fallback.lookup(hostname) }
        val cursor = rotations.computeIfAbsent(hostname.lowercase()) { AtomicInteger() }
            .getAndIncrement()
        if (rotations.size > MAX_TRACKED_HOSTS) rotations.clear()
        return rotatedAddressOrder(resolved, cursor)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun queryIpv4(hostname: String): List<InetAddress> {
        val answer = AtomicReference<List<InetAddress>>(emptyList())
        val completed = CountDownLatch(1)
        val cancellation = CancellationSignal()
        DnsResolver.getInstance().query(
            null,
            hostname,
            DnsResolver.TYPE_A,
            DnsResolver.FLAG_EMPTY,
            DIRECT_EXECUTOR,
            cancellation,
            object : DnsResolver.Callback<List<InetAddress>> {
                override fun onAnswer(result: List<InetAddress>, rcode: Int) {
                    if (rcode == 0) answer.set(result)
                    completed.countDown()
                }

                override fun onError(error: DnsResolver.DnsException) {
                    completed.countDown()
                }
            },
        )
        val finished = try {
            completed.await(QUERY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) cancellation.cancel()
        return answer.get()
    }

    private companion object {
        const val QUERY_TIMEOUT_MILLIS = 1_500L
        const val MAX_TRACKED_HOSTS = 128
        val DIRECT_EXECUTOR = Executor(Runnable::run)
    }
}

internal fun <T> rotatedAddressOrder(addresses: List<T>, cursor: Int): List<T> {
    if (addresses.size < 2) return addresses
    val offset = Math.floorMod(cursor, addresses.size)
    if (offset == 0) return addresses
    return addresses.drop(offset) + addresses.take(offset)
}
