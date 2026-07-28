package ml.melun.mangaview.reader

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLProtocolException

/**
 * Identifies a carrier route failure that is safe to recover with one exact QUIC GET.
 *
 * This deliberately excludes response/header deadlines and owner cancellation. Those failures
 * may happen after a valid request reached the origin; racing them through another transport
 * would duplicate successful image bytes. Carrier SNI resets instead fail while DNS, connect or
 * the TLS ClientHello is still establishing the route.
 */
internal object NtkCarrierManhwaImageRecoveryPolicy {
    private val directHosts = setOf(
        "booktoki8.org",
        "booktoki9.org",
        "mana.apihost93.com",
        "aws-cdn1.site",
    )
    private val numberedBooktokiHost = Regex("^booktoki\\d+\\.org$")
    private val numberedAwsCdnHost = Regex("^aws-cdn\\d+\\.site$")
    private val resetMessages = listOf(
        "connection reset",
        "reset by peer",
        "connection aborted",
        "software caused connection abort",
        "broken pipe",
        "network is unreachable",
    )

    fun isManhwaImageHost(host: String): Boolean {
        val normalized = host.trim().lowercase()
        return normalized in directHosts ||
            numberedBooktokiHost.matches(normalized) ||
            numberedAwsCdnHost.matches(normalized)
    }

    fun shouldRecover(
        cellularTransport: Boolean,
        host: String,
        failure: Throwable,
    ): Boolean {
        if (!cellularTransport || !isManhwaImageHost(host)) return false
        var current: Throwable? = failure
        var depth = 0
        while (current != null && depth++ < 16) {
            // A synthesized response-header deadline is intentionally excluded even though it is
            // an IOException. It may have cancelled a request that was already accepted.
            if (current is SocketTimeoutException) return false
            if (current is SSLHandshakeException ||
                current is SSLProtocolException ||
                current is UnknownHostException ||
                current is ConnectException ||
                current is NoRouteToHostException
            ) {
                return true
            }
            if (current is SocketException) {
                val message = current.message.orEmpty().lowercase()
                if (resetMessages.any(message::contains)) return true
            }
            val next = current.cause
            if (next === current) break
            current = next
        }
        return false
    }
}
