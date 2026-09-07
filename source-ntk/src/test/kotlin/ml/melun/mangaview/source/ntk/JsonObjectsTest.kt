package ml.melun.mangaview.source.ntk

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class JsonObjectsTest {
    @Test
    fun unchangedHtmlRequiresNoReplacement() {
        val html = "<html>한글 & pages / next</html>".repeat(10_000)
        assertSame(html, JsonObjects.normalizeEscapes(html))
    }

    @Test
    fun supportedEscapesRetainExistingMeaningAcrossArbitraryCombinations() {
        val random = Random(71851L)
        val fragments = listOf(
            "\\", "\\u003c", "\\u003E", "\\U0026", "\\/", "\\\"", "\\n", "\\t", "\\u1234",
            "\\\\u003c", "abc", "한글", "<script>", "'", "\"", "?x=1&y=2", "003e",
        )
        repeat(2_000) {
            val text = buildString {
                repeat(random.nextInt(100)) { append(fragments[random.nextInt(fragments.size)]) }
            }
            assertEquals(reference(text), JsonObjects.normalizeEscapes(text))
        }
    }

    @Test
    fun nestedMetadataAndUnknownEscapesRemainIntact() {
        val encoded = """<script>{\"episodeId\":\"slug\",\"data\":{\"url\":\"https:\/\/example.test\/page.webp\",\"title\":\"한글\"}}</script>"""
        val root = JsonObjects.embedded(encoded).single()
        assertEquals("slug", root.getString("episodeId"))
        assertEquals("https://example.test/page.webp", root.getJSONObject("data").getString("url"))
        assertEquals("한글", root.getJSONObject("data").getString("title"))
    }

    private fun reference(text: String): String = text
        .replace("\\u003c", "<", ignoreCase = true)
        .replace("\\u003e", ">", ignoreCase = true)
        .replace("\\u0026", "&", ignoreCase = true)
        .replace("\\/", "/")
        .replace("\\\"", "\"")
}
