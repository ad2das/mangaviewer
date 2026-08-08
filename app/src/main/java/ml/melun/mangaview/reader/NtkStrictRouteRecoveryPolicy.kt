package ml.melun.mangaview.reader

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Classifies failures that mean the configured NTK origin is no longer a usable route.
 *
 * Recovery never relaxes TLS verification. It lets the production domain resolver select a
 * different HTTPS origin and permits one completely fresh strict discovery flight.
 */
object NtkStrictRouteRecoveryPolicy {
    const val MAX_RECOVERY_ATTEMPTS = 1

    /*
     * executeStrictExactSameOriginRequest emits these messages only after its single physical
     * HttpEngine call has terminally failed. The transport is put into cooldown before the
     * exception is thrown, so the one fresh discovery flight uses the existing OkHttp fallback.
     * Exact matching keeps content rejection, parser failures and owner cancellation fail-closed.
     */
    private val retryableHttpEngineTransportFailures = setOf(
        "Strict document HttpEngine request failed",
        "Strict trusted_challenge HttpEngine request failed",
        "Strict signed_image_api HttpEngine request failed",
        "Strict unsigned_webtoon_image_api HttpEngine request failed",
    )

    @JvmStatic
    fun shouldRecover(failure: Throwable, completedRecoveryAttempts: Int): Boolean {
        if (completedRecoveryAttempts >= MAX_RECOVERY_ATTEMPTS) return false
        var current: Throwable? = failure
        var depth = 0
        while (current != null && depth++ < 16) {
            if ((current is NtkDocumentRouteResponseException &&
                    current.status in 500..599) ||
                current is SSLPeerUnverifiedException ||
                current is SSLHandshakeException ||
                current is CertificateException ||
                current is UnknownHostException ||
                current is ConnectException ||
                current is NoRouteToHostException
            ) {
                return true
            }
            if (current is IOException &&
                current.message in retryableHttpEngineTransportFailures
            ) {
                return true
            }
            val message = current.message.orEmpty().lowercase()
            if (message.contains("err_cert_common_name_invalid") ||
                message.contains("hostname") && message.contains("not verified") ||
                message.contains("certificate") && message.contains("host")
            ) {
                return true
            }
            val next = current.cause
            if (next === current) break
            current = next
        }
        return false
    }

    /**
     * A terminal QUIC timeout does not prove that the already-selected HTTPS origin is bad.
     * The strict transport marks that exact host unhealthy before throwing, so an immediate fresh
     * flight uses the existing OkHttp/H2 fallback. This exception is deliberately narrower than
     * general route recovery: current direct Wi-Fi only, document only, and only the first attempt.
     */
    @JvmStatic
    fun shouldRestartSameOriginWithoutResolver(
        failure: Throwable,
        completedRecoveryAttempts: Int,
        directWifiCurrentViewer: Boolean,
        sameOriginFallbackConsumed: Boolean,
    ): Boolean {
        if (!directWifiCurrentViewer || sameOriginFallbackConsumed ||
            completedRecoveryAttempts >= MAX_RECOVERY_ATTEMPTS
        ) {
            return false
        }
        if (failure !is IOException ||
            failure.message != "Strict document HttpEngine request failed"
        ) return false
        var current: Throwable? = failure.cause
        var depth = 0
        while (current != null && depth++ < 16) {
            if (current is SocketTimeoutException &&
                current.message == "QUIC fetch timed out"
            ) return true
            val next = current.cause
            if (next === current) break
            current = next
        }
        return false
    }
}
