package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class NtkStripDigestsTest {
    @Test
    fun fixedHexEncoderPreservesUnsignedLowercaseBytes() {
        assertEquals(
            "00010f107f80ff",
            NtkStripDigests.bytesToLowerHex(
                byteArrayOf(0, 1, 15, 16, 127, -128, -1),
            ),
        )
    }

    @Test
    fun sha256BytesRetainsCanonicalDigest() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb924" +
                "27ae41e4649b934ca495991b7852b855",
            NtkStripDigests.sha256Bytes(byteArrayOf()),
        )
    }
}
