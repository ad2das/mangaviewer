package ml.melun.mangaview.reader

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkStrictCachedResidentPublicationTest {
    @Test
    fun readinessPollingReusesStrictAuthorityAndCompletedDecoderValidation() {
        val source = File(
            "src/main/java/ml/melun/mangaview/reader/ReaderImageCache.kt",
        ).readText()
        val lookup = block("private fun cachedImageFile(", source)
        val validation = block("private fun isUsableImage(", source)

        assertTrue(lookup.contains("hasResidentStrictUsableImage(manga, candidate, file)"))
        assertTrue(validation.contains("validatedUsableImageFiles[file.absolutePath]"))
        assertTrue(validation.contains("rememberValidatedUsableImageFile("))
        assertTrue(
            validation.indexOf("validatedUsableImageFiles[file.absolutePath]") <
                validation.indexOf("BitmapFactory.decodeFile("),
        )
    }

    @Test
    fun cachedBodiesPublishOnlyForAnExplicitResidentRenderOwner() {
        assertFalse(
            NtkStrictCachedBodyRenderPublicationPolicy.shouldPublishResidentDescriptor(
                adjacentPrefetch = false,
                adjacentRenderPublication = false,
            ),
        )
        assertTrue(
            NtkStrictCachedBodyRenderPublicationPolicy.shouldPublishResidentDescriptor(
                adjacentPrefetch = true,
                adjacentRenderPublication = false,
            ),
        )
        assertTrue(
            NtkStrictCachedBodyRenderPublicationPolicy.shouldPublishResidentDescriptor(
                adjacentPrefetch = false,
                adjacentRenderPublication = true,
            ),
        )
        assertTrue(
            NtkStrictCachedBodyRenderPublicationPolicy.shouldPublishResidentDescriptor(
                adjacentPrefetch = true,
                adjacentRenderPublication = true,
            ),
        )
    }

    @Test
    fun everyCachedAcceptUsesOneActorOrderedResidentPublicationHelper() {
        val source = File(
            "src/main/java/ml/melun/mangaview/reader/NtkStrictSourceSession.kt",
        ).readText()
        val helper = block("private fun acceptCachedExactBodyActor(", source)

        assertEquals(2, source.windowed("strictCachedPublishedBody(".length)
            .count { it == "strictCachedPublishedBody(" })
        assertEquals(2, source.windowed("acceptCachedExactBodyActor(page, cached)".length)
            .count { it == "acceptCachedExactBodyActor(page, cached)" })
        assertTrue(helper.contains("assertActorThread()"))
        assertTrue(helper.contains("check(!page.primaryStarted && page.publishedBody == null)"))
        assertTrue(helper.contains("NtkStrictCachedBodyRenderPublicationPolicy"))
        assertTrue(helper.contains("publishResidentBodyForRender(cached)"))
        assertTrue(helper.contains("acceptExactBody(page, cached)"))
        assertTrue(
            helper.indexOf("page.primaryStarted = true") <
                helper.indexOf("publishResidentBodyForRender(cached)"),
        )
        assertTrue(
            helper.indexOf("publishResidentBodyForRender(cached)") <
                helper.indexOf("acceptExactBody(page, cached)"),
        )
    }

    private fun block(signature: String, source: String): String {
        val start = source.indexOf(signature)
        require(start >= 0) { "Missing source signature: $signature" }
        val brace = source.indexOf('{', start)
        require(brace >= 0) { "Missing opening brace: $signature" }
        var depth = 0
        for (index in brace until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        error("Missing closing brace: $signature")
    }
}
