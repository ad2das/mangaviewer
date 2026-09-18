package ml.melun.mangaview.source.ntk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NtkArtworkUrlTest {
    @Test fun relativeValuesResolveAgainstTheOrigin() {
        assertEquals(
            "https://sbxh9.com/black/thumbs/12706.jpg?v2",
            NtkArtworkUrl.resolve("https://sbxh9.com/", "/black/thumbs/12706.jpg?v2"),
        )
    }

    @Test fun absoluteAsciiValuesStayUnchanged() {
        val value = "https://aws-cdn9.site/webtoon_uploads/79f508aae99bd4cad04af285f0e4e734.jpg?v2"
        assertEquals(value, NtkArtworkUrl.resolve("https://sbxh9.com/", value))
    }

    @Test fun hangulFileNamesArePercentEncodedBeforeParsing() {
        assertEquals(
            "https://aws-cdn9.site/wt/thumbs/%EC%A0%84%EC%83%9D%EC%9E%90(%EC%B9%B4%EC%B9%B4%EC%98%A4).jpg?v2",
            NtkArtworkUrl.resolve(
                "https://sbxh9.com/",
                "https://aws-cdn9.site/wt/thumbs/전생자(카카오).jpg?v2",
            ),
        )
    }

    @Test fun supplementaryCharactersEncodeAsTheirFullUtf8Bytes() {
        assertEquals("a%F0%9F%98%80b", NtkArtworkUrl.percentEncode("a😀b"))
    }

    @Test fun malformedValuesStillFailClosed() {
        assertNull(NtkArtworkUrl.resolve("https://sbxh9.com/", "http://["))
    }
}
