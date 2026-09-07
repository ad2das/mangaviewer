package ml.melun.mangaview.source.ntk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkBrowserAuthorizationPolicyTest {
    @Test
    fun `document-start installation never evaluates the authorization bundle twice`() {
        assertFalse(
            shouldEvaluateAuthorizationFallback(
                browserDocumentStarted = true,
                authorizationStarted = false,
                captureInstalledAtDocumentStart = true,
            ),
        )
    }

    @Test
    fun `unsupported document-start API evaluates once after the exact document begins`() {
        assertFalse(
            shouldEvaluateAuthorizationFallback(
                browserDocumentStarted = false,
                authorizationStarted = false,
                captureInstalledAtDocumentStart = false,
            ),
        )
        assertTrue(
            shouldEvaluateAuthorizationFallback(
                browserDocumentStarted = true,
                authorizationStarted = false,
                captureInstalledAtDocumentStart = false,
            ),
        )
        assertFalse(
            shouldEvaluateAuthorizationFallback(
                browserDocumentStarted = true,
                authorizationStarted = true,
                captureInstalledAtDocumentStart = false,
            ),
        )
    }
}
