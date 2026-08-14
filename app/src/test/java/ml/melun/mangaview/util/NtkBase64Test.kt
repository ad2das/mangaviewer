package ml.melun.mangaview.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

class NtkBase64Test {
    @Test
    fun standardCodecMatchesJavaReference() {
        val bytes = byteArrayOf(0, 1, 2, 3, 0x7f, 0x80.toByte(), 0xff.toByte())
        val encoded = NtkBase64.encode(bytes)
        assertEquals(Base64.getEncoder().encodeToString(bytes), encoded)
        assertArrayEquals(bytes, NtkBase64.decode(encoded))
    }

    @Test
    fun urlCodecIsCanonicalAndUnpadded() {
        val bytes = byteArrayOf(0xfb.toByte(), 0xff.toByte(), 0xef.toByte(), 1)
        val encoded = NtkBase64.encodeUrlWithoutPadding(bytes)
        assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), encoded)
        assertArrayEquals(bytes, NtkBase64.decodeUrl(encoded))
    }

    @Test
    fun malformedInputFailsClosed() {
        assertThrows(IllegalArgumentException::class.java) { NtkBase64.decode("not base64 %") }
        assertThrows(IllegalArgumentException::class.java) { NtkBase64.decodeUrl("not base64url %") }
    }
}
