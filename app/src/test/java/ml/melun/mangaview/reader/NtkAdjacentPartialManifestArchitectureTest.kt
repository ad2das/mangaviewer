package ml.melun.mangaview.reader

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkAdjacentPartialManifestArchitectureTest {
    @Test
    fun pageRefsAndCompletionBothUseFailClosedPartialManifestPolicy() {
        val source = File(
            "src/main/java/ml/melun/mangaview/reader/ReaderSession.kt"
        ).readText()

        assertTrue(source.contains("NtkAdjacentPartialManifestPolicy.canPublishCompleteManifestIdentity("))
        assertTrue(source.contains("exactAuthorityAssetsMatch = exactAuthorityAssetsMatch"))
        assertTrue(source.contains("trustedExactAssetsMatch = trustedExactAssetsMatch"))
        assertTrue(source.contains("NtkAdjacentPartialManifestPolicy.canonicalCompletionPageCount("))
    }
}
