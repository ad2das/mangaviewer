package ml.melun.mangaview.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLProtocolException

class NtkCarrierManhwaImageRecoveryPolicyTest {
    @Test
    fun recoversCarrierTlsAndConnectFailuresForManhwaImageCdnOnly() {
        assertTrue(
            NtkCarrierManhwaImageRecoveryPolicy.shouldRecover(
                true,
                "booktoki9.org",
                SSLHandshakeException("Connection reset by peer"),
            ),
        )
        assertTrue(
            NtkCarrierManhwaImageRecoveryPolicy.shouldRecover(
                true,
                "booktoki312.org",
                SSLProtocolException("handshake aborted"),
            ),
        )
        assertTrue(
            NtkCarrierManhwaImageRecoveryPolicy.shouldRecover(
                true,
                "aws-cdn7.site",
                ConnectException("Network is unreachable"),
            ),
        )
        assertTrue(
            NtkCarrierManhwaImageRecoveryPolicy.shouldRecover(
                true,
                "mana.apihost93.com",
                SocketException("Connection reset"),
            ),
        )
    }

    @Test
    fun neverChangesTransportOnWifiOrUnrelatedHosts() {
        assertFalse(
            NtkCarrierManhwaImageRecoveryPolicy.shouldRecover(
                false,
                "booktoki9.org",
                SSLHandshakeException("Connection reset"),
            ),
        )
        assertFalse(
            NtkCarrierManhwaImageRecoveryPolicy.shouldRecover(
                true,
                "example.com",
                SSLHandshakeException("Connection reset"),
            ),
        )
    }

    @Test
    fun excludesHeaderTimeoutCancellationAndContentFailures() {
        assertFalse(
            NtkCarrierManhwaImageRecoveryPolicy.shouldRecover(
                true,
                "booktoki9.org",
                SocketTimeoutException("Replica response headers exceeded 900ms"),
            ),
        )
        assertFalse(
            NtkCarrierManhwaImageRecoveryPolicy.shouldRecover(
                true,
                "booktoki9.org",
                IOException("image digest mismatch"),
            ),
        )
    }

    @Test
    fun nestedTlsResetIsRecognizedButOuterTimeoutRemainsFailClosed() {
        assertTrue(
            NtkCarrierManhwaImageRecoveryPolicy.shouldRecover(
                true,
                "booktoki8.org",
                IOException("route failed", SSLProtocolException("Connection reset")),
            ),
        )
        assertFalse(
            NtkCarrierManhwaImageRecoveryPolicy.shouldRecover(
                true,
                "booktoki8.org",
                SocketTimeoutException("deadline").apply {
                    initCause(SSLProtocolException("Connection reset"))
                },
            ),
        )
    }
}
