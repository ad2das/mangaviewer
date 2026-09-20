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

/**
 * Provider image CDNs publish AAAA records that some emulator and carrier networks black-hole;
 * OkHttp then burns the full connect timeout on IPv6 before the IPv4 route is tried. Dropping the
 * AAAA family whenever an A record exists keeps a blocked host's failure fast, which is what lets
 * the mirror fallback engage immediately. IPv6-only answers still pass through unchanged.
 */
internal class ProviderImageDns(private val resolver: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        if (hostname.isBlank()) throw UnknownHostException("hostname == null")
        val resolved = resolver.lookup(hostname)
        val ipv4 = resolved.filterIsInstance<Inet4Address>()
        return ipv4.ifEmpty { resolved }
    }
}

/**
 * The newxtoon artwork zone is fronted by Cloudflare under its public hostname, but the same
 * pull zone is reachable straight through Bunny's edge network. Only the address lookup is
 * remapped to the pull zone's b-cdn.net hostname; Host and SNI keep the public hostname, so the
 * edge routes exactly as it would for the fronted name while no Cloudflare hop is involved.
 */
internal class BunnyImageDns(
    private val resolver: Dns = Dns.SYSTEM,
    private val publicSuffix: String = "quicksharefiles.top",
    private val edgeSuffix: String = "b-cdn.net",
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        if (hostname.isBlank()) throw UnknownHostException("hostname == null")
        val publicHost = hostname.lowercase()
        if (!publicHost.endsWith(".$publicSuffix")) return resolver.lookup(hostname)
        val zone = publicHost.removeSuffix(".$publicSuffix")
        // A pull zone whose own edge hostname has no record still shares the edge network with
        // the apex, so the apex is the fallback address source rather than the fronted host.
        val resolved = try {
            resolver.lookup("$zone.$edgeSuffix")
        } catch (failure: UnknownHostException) {
            resolver.lookup(edgeSuffix)
        }
        val ipv4 = resolved.filterIsInstance<Inet4Address>()
        return ipv4.ifEmpty { resolved }
    }
}

/** Fallback DNS may return both families. Route rotation must retain the IPv4-first contract. */
internal fun ipv4FirstAddressOrder(addresses: List<InetAddress>, cursor: Int): List<InetAddress> {
    val (ipv4, remaining) = addresses.partition { it is Inet4Address }
    return rotatedAddressOrder(ipv4, cursor) + rotatedAddressOrder(remaining, cursor)
}
