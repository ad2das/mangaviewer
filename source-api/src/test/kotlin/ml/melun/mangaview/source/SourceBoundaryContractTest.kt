package ml.melun.mangaview.source

import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.PageSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceBoundaryContractTest {
    @Test
    fun viewerFacingPageMetadataCannotContainProviderRequests() {
        val fields = PageSpec::class.java.declaredFields
            .filterNot { it.isSynthetic }
            .map { it.name }
            .toSet()

        assertEquals(
            setOf("id", "ordinal", "dimensions", "encodedLength", "fingerprint"),
            fields,
        )
        assertFalse(fields.any { it.contains("url", ignoreCase = true) })
        assertFalse(fields.any { it.contains("header", ignoreCase = true) })
    }

    @Test
    fun contentSourceExposesCanonicalIdsInsteadOfProviderRequestTypes() {
        val manifest = ContentSource::class.java.methods.single { it.name == "manifest" }
        val openPages = ContentSource::class.java.methods.filter { it.name == "openPage" }

        assertEquals(
            true,
            manifest.genericParameterTypes.last().typeName.contains("EpisodeManifest"),
        )
        assertTrue(openPages.isNotEmpty())
        assertTrue(openPages.all { it.parameterTypes.firstOrNull() == PageId::class.java })
        assertFalse(
            ContentSource::class.java.methods
                .flatMap { it.parameterTypes.asIterable() + it.returnType }
                .any { it == SourceRequest::class.java },
        )
    }
}
