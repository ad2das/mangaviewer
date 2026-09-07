package ml.melun.mangaview.source.ntk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test

class NtkDocumentJsonReaderTest {
    @Test
    fun jsonEscapesAndLiteralBackslashesAreDecodedExactlyOnce() {
        val title = "따옴표 \"와\" 역슬래시 \\u003c 실제 < 😀"
        val html = "<script>${viewer(title)}</script>"
        assertEquals(title, manifest(html).viewer?.title)
    }

    @Test
    fun flightChunksMaySplitAnywhereIncludingInsideAnEscapeOrSurrogatePair() {
        val title = "한글 \"인용\" 😀 \\u0026"
        val row = "a:[\"$\",\"viewer\",null,${viewer(title)}]\n"
        for (split in 0..row.length) {
            val html = script(row.substring(0, split)) + script(row.substring(split))
            assertEquals("split=$split", title, manifest(html).viewer?.title)
        }
    }

    @Test
    fun multiplePushesInOneScriptStillFormOneCompleteRecord() {
        val row = "a:${viewer("multi")}\n"
        val first = JSONArray().put(1).put(row.take(20))
        val second = JSONArray().put(1).put(row.drop(20))
        assertEquals("multi", manifest(
            "<script>self.__next_f.push($first);self.__next_f.push($second);</script>",
        ).viewer?.title)
    }

    @Test
    fun rawTextRecordsCannotInjectFakeViewerMetadata() {
        val content = "한글😀\n0:${viewer("fake").put("sourceWorkId", "other")}\n"
        val length = content.toByteArray(Charsets.UTF_8).size.toString(16)
        val rows = "1:T$length,$content" + "2:${viewer("real")}\n"
        assertEquals("real", manifest(script(rows)).viewer?.title)
    }

    @Test
    fun incompleteRawTextRecordIsRejectedInsteadOfReadingItsContentsAsJson() {
        assertThrows(IllegalArgumentException::class.java) {
            manifest(script("1:Tffff,${viewer("fake")}"))
        }
    }

    @Test
    fun aQuotedClosingBraceCannotTruncateTheViewerObject() {
        val html = "<script>window.payload=${viewer("본문 } ] \\\" 끝")};</script>"
        assertNotNull(manifest(html).viewer)
    }

    @Test
    fun unrelatedJsonImagesCannotBecomePagesOfTheVerifiedViewer() {
        val actual = "https://cdn.test/webtoon_uploads/current-page.jpg?literal=%5Cu0026&value=1"
        val wrapper = JSONObject().put("recommendation", "https://cdn.test/0123456789abcdef.jpg")
            .put("viewer", viewer("real").put("images", JSONArray().put(actual)))
        val result = manifest("<script>$wrapper</script>")
        assertEquals(listOf(actual), result.directPages.map { it.url })
    }

    @Test
    fun conflictingFlightViewerIdentitiesAreStillRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            manifest(script("1:${viewer("one")}\n2:${viewer("two")}\n"))
        }
    }

    private fun viewer(title: String) = JSONObject()
        .put("sourceWorkId", "work").put("episodeId", "episode")
        .put("imagesToken", "test-token").put("imageApiPath", "/api/webtoon-images")
        .put("epTitle", title).put("imageCount", 2)

    private fun script(chunk: String) = "<script>self.__next_f.push(${JSONArray().put(1).put(chunk)})</script>"

    private fun manifest(html: String) = NtkDocumentParser().manifest(
        NtkEpisodeDocument("https://ntk.test", "/webtoon/work/episode", html),
    )
}
