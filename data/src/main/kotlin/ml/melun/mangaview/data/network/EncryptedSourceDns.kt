package ml.melun.mangaview.data.network

import java.io.Closeable
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps

/** Used only by blocked-connection recovery; resolver TLS is authenticated with normal trust. */
internal class EncryptedSourceDns : Dns, Closeable {
    private val client = OkHttpClient.Builder().callTimeout(4, TimeUnit.SECONDS)
        .connectTimeout(3, TimeUnit.SECONDS).build()
    private val resolvers = listOf(
        resolver("https://cloudflare-dns.com/dns-query", "1.1.1.1", "1.0.0.1"),
        resolver("https://dns.google/dns-query", "8.8.8.8", "8.8.4.4"),
    )
    private val cache = ConcurrentHashMap<String, Pair<Long, List<InetAddress>>>()

    override fun lookup(hostname: String): List<InetAddress> {
        val now = System.nanoTime()
        cache[hostname]?.takeIf { now - it.first < TimeUnit.SECONDS.toNanos(60) }?.let { return it.second }
        var last: UnknownHostException? = null
        for (resolver in resolvers) {
            try {
                val addresses = resolver.lookup(hostname).sortedBy { it.address.size }
                if (cache.size >= 128) cache.clear()
                cache[hostname] = now to addresses
                return addresses
            } catch (failure: UnknownHostException) { last = failure }
        }
        throw last ?: UnknownHostException(hostname)
    }

    private fun resolver(url: String, vararg bootstrap: String) = DnsOverHttps.Builder()
        .client(client).url(url.toHttpUrl()).includeIPv6(true)
        .bootstrapDnsHosts(bootstrap.map(InetAddress::getByName)).build()

    override fun close() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdownNow()
        cache.clear()
    }
}
