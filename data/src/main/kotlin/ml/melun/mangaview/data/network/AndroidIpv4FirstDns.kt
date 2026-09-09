package ml.melun.mangaview.data.network

import java.net.InetAddress
import java.net.Inet4Address
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Dns

internal class AndroidIpv4FirstDns(
    private val resolver: Dns = Dns.SYSTEM,
    private val fixedAddressOffset: Int? = null,
) : Dns {
    private val rotations = ConcurrentHashMap<String, AtomicInteger>()

    override fun lookup(hostname: String): List<InetAddress> {
        if (hostname.isBlank()) throw UnknownHostException("hostname == null")
        // Android DnsResolver dispatches readiness through the main Looper even with a
        // direct executor. Blocking OkHttp workers must resolve independently of UI startup.
        val resolved = resolver.lookup(hostname)
        fixedAddressOffset?.let { return ipv4FirstAddressOrder(resolved, it) }
        val cursor = rotations.computeIfAbsent(hostname.lowercase()) { AtomicInteger() }
            .getAndIncrement()
        if (rotations.size > MAX_TRACKED_HOSTS) rotations.clear()
        return ipv4FirstAddressOrder(resolved, cursor)
    }

    private companion object {
        const val MAX_TRACKED_HOSTS = 128
    }
}

internal fun <T> rotatedAddressOrder(addresses: List<T>, cursor: Int): List<T> {
    if (addresses.size < 2) return addresses
    val offset = Math.floorMod(cursor, addresses.size)
    if (offset == 0) return addresses
    return addresses.drop(offset) + addresses.take(offset)
}

/** Fallback DNS may return both families. Route rotation must retain the IPv4-first contract. */
internal fun ipv4FirstAddressOrder(addresses: List<InetAddress>, cursor: Int): List<InetAddress> {
    val (ipv4, remaining) = addresses.partition { it is Inet4Address }
    return rotatedAddressOrder(ipv4, cursor) + rotatedAddressOrder(remaining, cursor)
}
