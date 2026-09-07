package ml.melun.mangaview.engine.api

import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class SourceDocumentTest {
    @Test fun responseSnapshotsHeadersAndBytesWithoutLoggingCookies() {
        val cookies = mutableListOf("session=secret; Path=/", "proof=other; HttpOnly")
        val headers = linkedMapOf("Set-Cookie" to cookies)
        val body = "document".toByteArray()
        val document = SourceDocument(URI("https://provider.test/chapter"), body, headers)
        cookies.clear()
        headers.clear()
        body.fill(0)
        assertEquals(listOf("session=secret; Path=/", "proof=other; HttpOnly"), document.responseHeaders["Set-Cookie"])
        assertEquals("document", document.openBody().bufferedReader().use { it.readText() })
        assertFalse(document.toString().contains("secret"))
        assertThrows(UnsupportedOperationException::class.java) {
            (document.responseHeaders as MutableMap<String, List<String>>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (document.responseHeaders.getValue("Set-Cookie") as MutableList<String>).clear()
        }
    }
}
