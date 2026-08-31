package ml.melun.mangaview.source

import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.PageSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val openPage = ContentSource::class.java.methods.single { it.name == "openPage" }

        assertEquals(
            true,
            manifest.genericParameterTypes.last().typeName.contains("EpisodeManifest"),
        )
        assertEquals(PageId::class.java, openPage.parameterTypes.first())
        assertFalse(
            ContentSource::class.java.methods
                .flatMap { it.parameterTypes.asIterable() + it.returnType }
                .any { it == SourceRequest::class.java },
        )
    }
}
