package ml.melun.mangaview.reader

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLPeerUnverifiedException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkStrictRouteRecoveryPolicyTest {
    @Test
    fun recoversFromNestedCertificateHostnameFailureOnlyOnce() {
        val failure = IOException(
            "Strict document HttpEngine request failed",
            SSLPeerUnverifiedException("Hostname sbxh9.com not verified"),
        )

        assertTrue(NtkStrictRouteRecoveryPolicy.shouldRecover(failure, 0))
        assertFalse(NtkStrictRouteRecoveryPolicy.shouldRecover(failure, 1))
    }

    @Test
    fun recognizesCronetCertificateAndDnsRouteFailures() {
        assertTrue(
            NtkStrictRouteRecoveryPolicy.shouldRecover(
                IOException("net::ERR_CERT_COMMON_NAME_INVALID"),
                0,
            ),
        )
        assertTrue(
            NtkStrictRouteRecoveryPolicy.shouldRecover(
                IOException("wrapped", UnknownHostException("retired.example")),
                0,
            ),
        )
    }

    @Test
    fun recoversFromServerRouteFailureButNotContentStatus() {
        assertTrue(
            NtkStrictRouteRecoveryPolicy.shouldRecover(
                NtkDocumentRouteResponseException(502),
                0,
            ),
        )
        assertFalse(
            NtkStrictRouteRecoveryPolicy.shouldRecover(
                NtkDocumentRouteResponseException(403),
                0,
            ),
        )
        assertFalse(
            NtkStrictRouteRecoveryPolicy.shouldRecover(
                NtkDocumentRouteResponseException(404),
                0,
            ),
        )
    }

    @Test
    fun doesNotRetryMalformedOrRejectedContent() {
        assertFalse(
            NtkStrictRouteRecoveryPolicy.shouldRecover(
                IllegalStateException("Exact manifest page count differs from document"),
                0,
            ),
        )
    }

    @Test
    fun retriesTerminalStrictHttpEngineTransportFailureOnlyOnce() {
        val failure = IOException(
            "Strict unsigned_webtoon_image_api HttpEngine request failed",
            IOException("net::ERR_CONNECTION_RESET"),
        )

        assertTrue(NtkStrictRouteRecoveryPolicy.shouldRecover(failure, 0))
        assertFalse(NtkStrictRouteRecoveryPolicy.shouldRecover(failure, 1))
    }

    @Test
    fun doesNotRetryCancellationOrUnclassifiedIoFailure() {
        assertFalse(
            NtkStrictRouteRecoveryPolicy.shouldRecover(
                InterruptedIOException(
                    "Strict unsigned_webtoon_image_api HttpEngine request cancelled",
                ),
                0,
            ),
        )
        assertFalse(
            NtkStrictRouteRecoveryPolicy.shouldRecover(
                IOException("Unexpected image-list failure"),
                0,
            ),
        )
    }

    @Test
    fun currentDirectWifiQuicDocumentTimeoutRestartsOnSameOriginOnlyOnce() {
        val failure = IOException(
            "Strict document HttpEngine request failed",
            SocketTimeoutException("QUIC fetch timed out"),
        )

        assertTrue(
            NtkStrictRouteRecoveryPolicy.shouldRestartSameOriginWithoutResolver(
                failure,
                0,
                directWifiCurrentViewer = true,
                sameOriginFallbackConsumed = false,
            ),
        )
        assertFalse(
            NtkStrictRouteRecoveryPolicy.shouldRestartSameOriginWithoutResolver(
                failure,
                1,
                directWifiCurrentViewer = true,
                sameOriginFallbackConsumed = false,
            ),
        )
    }

    @Test
    fun currentDirectWifiBareControlPlaneTimeoutRestartsOnSameOriginOnlyOnce() {
        val failure = SocketTimeoutException("timeout")

        assertTrue(
            NtkStrictRouteRecoveryPolicy.shouldRestartSameOriginWithoutResolver(
                failure,
                0,
                directWifiCurrentViewer = true,
                sameOriginFallbackConsumed = false,
            ),
        )
        assertFalse(
            NtkStrictRouteRecoveryPolicy.shouldRestartSameOriginWithoutResolver(
                failure,
                0,
                directWifiCurrentViewer = false,
                sameOriginFallbackConsumed = false,
            ),
        )
        assertFalse(
            NtkStrictRouteRecoveryPolicy.shouldRestartSameOriginWithoutResolver(
                failure,
                0,
                directWifiCurrentViewer = true,
                sameOriginFallbackConsumed = true,
            ),
        )
    }

    @Test
    fun currentCellularSocketAbortRestartsSameOriginButAdjacentDoesNot() {
        val failure = SocketException("Software caused connection abort")

        assertTrue(
            NtkStrictRouteRecoveryPolicy.shouldRestartSameOriginWithoutResolver(
                failure,
                completedRecoveryAttempts = 0,
                directWifiCurrentViewer = false,
                sameOriginFallbackConsumed = false,
                currentViewer = true,
            ),
        )
        assertFalse(
            NtkStrictRouteRecoveryPolicy.shouldRestartSameOriginWithoutResolver(
                failure,
                completedRecoveryAttempts = 0,
                directWifiCurrentViewer = false,
                sameOriginFallbackConsumed = false,
                currentViewer = false,
            ),
        )
        assertFalse(
            NtkStrictRouteRecoveryPolicy.shouldRestartSameOriginWithoutResolver(
                failure,
                completedRecoveryAttempts = 1,
                directWifiCurrentViewer = false,
                sameOriginFallbackConsumed = false,
                currentViewer = true,
            ),
        )
    }

    @Test
    fun secondCurrentDirectWifiTimeoutResolvesANewOriginOnlyAfterSameOriginFallback() {
        val failure = SocketTimeoutException("timeout")

        assertTrue(
            NtkStrictRouteRecoveryPolicy.shouldResolveAfterSameOriginFallback(
                failure,
                completedRecoveryAttempts = 0,
                directWifiCurrentViewer = true,
                sameOriginFallbackConsumed = true,
            ),
        )
        assertFalse(
            NtkStrictRouteRecoveryPolicy.shouldResolveAfterSameOriginFallback(
                failure,
                completedRecoveryAttempts = 0,
                directWifiCurrentViewer = true,
                sameOriginFallbackConsumed = false,
            ),
        )
        assertFalse(
            NtkStrictRouteRecoveryPolicy.shouldResolveAfterSameOriginFallback(
                failure,
                completedRecoveryAttempts = 1,
                directWifiCurrentViewer = true,
                sameOriginFallbackConsumed = true,
            ),
        )
        assertFalse(
            NtkStrictRouteRecoveryPolicy.shouldResolveAfterSameOriginFallback(
                failure,
                completedRecoveryAttempts = 0,
                directWifiCurrentViewer = false,
                sameOriginFallbackConsumed = true,
            ),
        )
        assertFalse(
            NtkStrictRouteRecoveryPolicy.shouldResolveAfterSameOriginFallback(
                IllegalStateException("identity mismatch"),
                completedRecoveryAttempts = 0,
                directWifiCurrentViewer = true,
                sameOriginFallbackConsumed = true,
            ),
        )
    }

    @Test
    fun sameOriginFastFailoverExcludesAdjacentCarrierAndOtherFailures() {
        val timeout = IOException(
            "Strict document HttpEngine request failed",
            SocketTimeoutException("QUIC fetch timed out"),
        )
        assertFalse(
            NtkStrictRouteRecoveryPolicy.shouldRestartSameOriginWithoutResolver(
                timeout,
                0,
                directWifiCurrentViewer = false,
                sameOriginFallbackConsumed = false,
            ),
        )
        assertFalse(
            NtkStrictRouteRecoveryPolicy.shouldRestartSameOriginWithoutResolver(
                IOException(
                    "Strict trusted_challenge HttpEngine request failed",
                    SocketTimeoutException("QUIC fetch timed out"),
                ),
                0,
                directWifiCurrentViewer = true,
                sameOriginFallbackConsumed = false,
            ),
        )
        assertFalse(
            NtkStrictRouteRecoveryPolicy.shouldRestartSameOriginWithoutResolver(
                IOException(
                    "Strict document HttpEngine request failed",
                    UnknownHostException("retired.example"),
                ),
                0,
                directWifiCurrentViewer = true,
                sameOriginFallbackConsumed = false,
            ),
        )
        assertFalse(
            NtkStrictRouteRecoveryPolicy.shouldRestartSameOriginWithoutResolver(
                timeout,
                0,
                directWifiCurrentViewer = true,
                sameOriginFallbackConsumed = true,
            ),
        )
    }

    @Test
    fun hostGpuEmulatorAdjacentApiTimeoutRestartsOnSameOriginBeforeResolver() {
        val timeout = IOException(
            "Strict unsigned_webtoon_image_api HttpEngine request failed",
            SocketTimeoutException("timeout"),
        )

        assertTrue(
            NtkStrictRouteRecoveryPolicy.shouldRestartSameOriginWithoutResolver(
                timeout,
                completedRecoveryAttempts = 0,
                directWifiCurrentViewer = false,
                directWifiAdjacentViewer = true,
                hostGpuEmulatorRuntime = true,
                sameOriginFallbackConsumed = false,
            ),
        )
        assertFalse(
            NtkStrictRouteRecoveryPolicy.shouldRestartSameOriginWithoutResolver(
                timeout,
                completedRecoveryAttempts = 0,
                directWifiCurrentViewer = false,
                directWifiAdjacentViewer = true,
                hostGpuEmulatorRuntime = false,
                sameOriginFallbackConsumed = false,
            ),
        )
        assertFalse(
            NtkStrictRouteRecoveryPolicy.shouldRestartSameOriginWithoutResolver(
                timeout,
                completedRecoveryAttempts = 0,
                directWifiCurrentViewer = false,
                directWifiAdjacentViewer = false,
                hostGpuEmulatorRuntime = true,
                sameOriginFallbackConsumed = false,
            ),
        )
        assertFalse(
            NtkStrictRouteRecoveryPolicy.shouldRestartSameOriginWithoutResolver(
                timeout,
                completedRecoveryAttempts = 0,
                directWifiCurrentViewer = false,
                directWifiAdjacentViewer = true,
                hostGpuEmulatorRuntime = true,
                sameOriginFallbackConsumed = true,
            ),
        )
    }

    @Test
    fun hostGpuEmulatorAdjacentSameOriginFallbackRejectsNonTimeoutFailures() {
        assertFalse(
            NtkStrictRouteRecoveryPolicy.shouldRestartSameOriginWithoutResolver(
                IOException(
                    "Strict unsigned_webtoon_image_api HttpEngine request failed",
                    IOException("malformed response"),
                ),
                completedRecoveryAttempts = 0,
                directWifiCurrentViewer = false,
                directWifiAdjacentViewer = true,
                hostGpuEmulatorRuntime = true,
                sameOriginFallbackConsumed = false,
            ),
        )
    }
}
