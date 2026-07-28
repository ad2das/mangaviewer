package ml.melun.mangaview.reader

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ml.melun.mangaview.MainApplication
import okhttp3.Protocol
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Live, opt-in carrier-route diagnostic. Supply `ntkExactImageUrl` with an existing immutable
 * manhwa image, then block TCP/443 for the app uid while leaving UDP/443 open. A successful QUIC
 * response proves the exact recovery transport without embedding a work id in production code.
 */
@RunWith(AndroidJUnit4::class)
class NtkCarrierManhwaImageQuicRecoveryInstrumentedTest {
    @Test
    fun exactImageRemainsReachableWithTcp443Unavailable() {
        val imageUrl = InstrumentationRegistry.getArguments()
            .getString("ntkExactImageUrl")
            .orEmpty()
            .trim()
        assumeTrue("ntkExactImageUrl is required for the live diagnostic", imageUrl.isNotEmpty())

        val client = MainApplication.getHttpClient()
        assumeTrue(
            "The route-learning assertion requires an active cellular-backed transport",
            client.isNtkCellularResilientTransportActive(),
        )
        val request = Request.Builder()
            .url(imageUrl)
            .header("User-Agent", client.agent)
            .get()
            .build()
        assertExactQuicImage(client.ntkCarrierExactImageQuicRecoveryCall(request))

        // A successful failure-path recovery should teach the ordinary demand-bound factory to
        // avoid the known-broken TCP route for the remaining pages of the current episode.
        assertExactQuicImage(client.ntkDemandBoundExactImageFactory().newCall(request))
    }

    private fun assertExactQuicImage(call: okhttp3.Call) {
        call.execute().use { response ->
            val bytes = response.body?.bytes() ?: ByteArray(0)
            assertTrue("Expected a successful exact image response, code=${response.code}",
                response.isSuccessful)
            assertEquals("TCP is blocked, so HTTP/2 fallback must not be accepted",
                Protocol.QUIC, response.protocol)
            assertTrue("Exact image body is empty", bytes.isNotEmpty())
            assertTrue(
                "Response does not have a supported image signature",
                looksLikeSupportedImage(bytes),
            )
        }
    }

    private fun looksLikeSupportedImage(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        val jpeg = bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte()
        val png = bytes.copyOfRange(0, 8).contentEquals(
            byteArrayOf(
                0x89.toByte(), 0x50, 0x4e, 0x47,
                0x0d, 0x0a, 0x1a, 0x0a,
            ),
        )
        val gif = bytes.copyOfRange(0, 4).decodeToString() == "GIF8"
        val webp = bytes.copyOfRange(0, 4).decodeToString() == "RIFF" &&
            bytes.copyOfRange(8, 12).decodeToString() == "WEBP"
        return jpeg || png || gif || webp
    }
}
