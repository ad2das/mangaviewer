package ml.melun.mangaview.source.ntk

import java.nio.charset.StandardCharsets
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NtkBrowserManifestParserTest {
    private val parser = NtkBrowserManifestParser()

    @Test
    fun keepsOnlyContentImagesFromPollutedImageApiResources() {
        val payload = envelope(
            """
            {"page":1,"src":"https://static.example/fonts/regular.woff2","contentType":"font/woff2"},
            {"page":1,"src":"https://cdn.example/webtoon_uploads/work/p001.jpg"},
            {"page":2,"src":"https://static.example/chunks/viewer.js"},
            {"page":3,"src":"https://media.example/ads/top-banner.jpg","mimeType":"image/jpeg"},
            {"page":5,"src":"https://static.example/font.woff2",
             "srcCandidates":["https://cdn.example/episodes/work/p002.webp"]},
            {"page":6,"src":"https://cdn.example/token/signed-resource","mime":"image/png"},
            {"page":7,"src":"https://cdn.example/random/resource"},
            {"page":8,"src":"https://cdn.example/pages/deceptive.jpg","contentType":"text/css"}
            """.trimIndent(),
        )

        val pages = parser.parse(payload)

        assertEquals(
            listOf(
                "https://cdn.example/webtoon_uploads/work/p001.jpg",
                "https://cdn.example/episodes/work/p002.webp",
                "https://cdn.example/token/signed-resource",
            ),
            pages.map(NtkPageRequest::url),
        )
    }

    @Test
    fun acceptsSupportedQueryFormatWithoutDependingOnAProviderHost() {
        val pages = parser.parse(
            envelope(
                """{"page":9,"src":"https://opaque.invalid/object?id=abc&format=webp"}""",
            ),
        )

        assertEquals("https://opaque.invalid/object?id=abc&format=webp", pages.single().url)
    }

    @Test
    fun rejectsPayloadWithoutExactImageApiIdentity() {
        val payload = """
            {"ok":true,"endpoint":"/api/ad/challenge","responseContentType":"application/json",
             "images":[{"page":1,"src":"https://cdn.example/pages/p001.jpg"}]}
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) { parser.parse(payload) }
    }

    @Test
    fun rejectsRedirectedNonApiResponseIdentity() {
        val payload = """
            {"ok":true,"endpoint":"/api/manhwa-images",
             "responseUrl":"https://example.invalid/assets/config.json",
             "responseContentType":"application/json",
             "images":[{"page":1,"src":"https://cdn.example/pages/p001.jpg"}]}
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) { parser.parse(payload) }
    }

    @Test
    fun exactDocumentSequenceTreatsCapabilityPathsAsOpaque() {
        val pages = parser.parse(
            envelope(
                """
                {"page":2,"src":"https://opaque.invalid/signed/b.woff2?token=two"},
                {"page":1,"src":"https://opaque.invalid/signed/a.woff2?token=one"}
                """.trimIndent(),
            ),
            expectedPageCount = 2,
        )

        assertEquals(
            listOf(
                "https://opaque.invalid/signed/a.woff2?token=one",
                "https://opaque.invalid/signed/b.woff2?token=two",
            ),
            pages.map(NtkPageRequest::url),
        )
    }

    @Test
    fun exactDocumentSequenceRejectsDuplicatesAndCountMismatch() {
        val duplicate = envelope(
            """
            {"page":1,"src":"https://opaque.invalid/a"},
            {"page":1,"src":"https://opaque.invalid/b"}
            """.trimIndent(),
        )
        val short = envelope(
            """{"page":1,"src":"https://opaque.invalid/a"}""",
        )

        assertThrows(IllegalArgumentException::class.java) {
            parser.parse(duplicate, expectedPageCount = 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            parser.parse(short, expectedPageCount = 2)
        }
    }

    @Test
    fun productionCaptureRequiresExactRequestAndResponseIdentity() {
        val document = NtkEpisodeDocument(
            origin = "https://reader.invalid",
            path = "/webtoon/work/current",
            html = "",
        )
        val requestToken = token("work", "current", "webtoon")
        val descriptor = NtkViewerDescriptor(
            workId = "work",
            episodeId = "current",
            token = requestToken,
            apiPath = "/api/webtoon-images",
            expectedPageCount = 1,
        )
        val valid = exactEnvelope(requestToken)

        assertEquals(1, parser.parse(valid, document, descriptor).size)
        assertEquals(
            1,
            parser.parse(
                exactEnvelope(token("work", "current", "webtoon", 2)),
                document,
                descriptor,
            ).size,
        )
        listOf(
            valid.replace("\"requestWorkId\":\"work\"", "\"requestWorkId\":\"other\""),
            valid.replace("\"requestEpisodeId\":\"current\"", "\"requestEpisodeId\":\"other\""),
            exactEnvelope(token("work", "other", "webtoon")),
            exactEnvelope(token("work", "current", "manhwa")),
            exactEnvelope(""),
            valid.replace("\"requestMethod\":\"POST\"", "\"requestMethod\":\"GET\""),
            valid.replace("https://reader.invalid/api/", "https://other.invalid/api/"),
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                parser.parse(invalid, document, descriptor)
            }
        }
    }

    private fun envelope(images: String): String = """
        {"ok":true,"endpoint":"/api/webtoon-images",
         "responseUrl":"https://reader.invalid/api/webtoon-images",
         "responseContentType":"application/json; charset=utf-8",
         "images":[$images]}
    """.trimIndent()

    private fun exactEnvelope(requestToken: String): String = """
        {"ok":true,"endpoint":"/api/webtoon-images",
         "responseUrl":"https://reader.invalid/api/webtoon-images",
         "responseContentType":"application/json","requestMethod":"POST",
         "requestContentType":"application/json","requestWorkId":"work",
         "requestEpisodeId":"current","requestToken":"$requestToken",
         "images":[{"page":1,"src":"https://opaque.invalid/page.woff2"}]}
    """.trimIndent()

    private fun token(workId: String, episodeId: String, kind: String, version: Int = 1): String {
        val payload = """{"w":"$workId","e":"$episodeId","t":"$kind","v":$version}"""
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.toByteArray(StandardCharsets.UTF_8)) + ".signature-$version"
    }
}
