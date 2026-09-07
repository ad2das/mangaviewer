package ml.melun.mangaview.source.ntk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkDirectPageOwnershipTest {
    @Test fun numberedPagesKeepAlternativesAndIgnoreNestedArtwork() {
        val result = manifest(""""images":[
            {"page":2,"src":"$CDN/p2.jpg","srcCandidates":["$CDN/p2-alt.jpg"],
             "artwork":{"url":"$CDN/cover.jpg"}},
            {"page":1,"src":"$CDN/p1.jpg","alternateUrls":["$CDN/p1-alt.jpg"]}]
        """)
        assertEquals(listOf("$CDN/p1.jpg", "$CDN/p2.jpg"), result.directPages.map { it.url })
        assertEquals(listOf("$CDN/p1-alt.jpg"), result.directPages[0].alternateUrls)
        assertEquals(listOf("$CDN/p2-alt.jpg"), result.directPages[1].alternateUrls)
        assertTrue(result.directPagesOwnedByViewer)
    }

    @Test fun domAlternativesCannotMasqueradeAsTwoProtectedPages() {
        val result = manifest(""""imageCount":2""",
            """<img data-src="$CDN/p1.jpg" src="$CDN/p1-small.jpg">""")
        assertTrue(result.directPages.isEmpty())
        assertFalse(result.directPagesOwnedByViewer)
    }

    @Test fun ownedJsonSequenceWinsOverUnrelatedDomImages() {
        val result = manifest(""""images":["$CDN/p1.jpg","$CDN/p2.jpg"]""",
            """<img src="$CDN/unrelated.jpg"><img src="$CDN/p2.jpg">""")
        assertEquals(listOf("$CDN/p1.jpg", "$CDN/p2.jpg"), result.directPages.map { it.url })
    }

    @Test fun sparseOrPartialDirectPagesRequireTheProtectedApi() {
        listOf(
            """{"page":1,"src":"$CDN/p1.jpg"},{"page":3,"src":"$CDN/p3.jpg"}""",
            """{"page":1,"src":"$CDN/p1.jpg"},{"page":2}""",
        ).forEach { rows ->
            val result = manifest(""""images":[$rows]""")
            assertTrue(result.directPages.isEmpty())
            assertFalse(result.directPagesOwnedByViewer)
        }
    }

    @Test fun duplicatePageNumbersAndConflictingRepresentationsAreRejected() {
        listOf(
            """"images":[{"page":1,"src":"$CDN/p1.jpg"},{"page":1,"src":"$CDN/p2.jpg"}]""",
            """"images":["$CDN/p1.jpg"],"pages":["$CDN/other.jpg"]""",
        ).forEach { fields -> assertThrows(IllegalArgumentException::class.java) { manifest(fields) } }
    }

    private fun manifest(fields: String, body: String = "") = NtkDocumentParser().manifest(
        NtkEpisodeDocument("https://provider.test", "/webtoon/work/episode", """
            <script>{"sourceWorkId":"work","episodeId":"episode","imagesToken":"token",
              "imageApiPath":"/api/webtoon-images",$fields}</script>$body
        """.trimIndent()),
    )

    private companion object { const val CDN = "https://cdn.test/webtoon_uploads" }
}
