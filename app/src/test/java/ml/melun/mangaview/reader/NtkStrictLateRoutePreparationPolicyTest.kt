package ml.melun.mangaview.reader

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkStrictLateRoutePreparationPolicyTest {
    @Test
    fun exactOwnedSuffixWithoutStreamedBodyDoesNotWaitForClickProbe() {
        assertFalse(
            NtkStrictLateRoutePreparationPolicy.shouldWaitForStreamedSourceRoute(
                exactManifestOwned = true,
                streamedExactBodyPending = false,
            )
        )
    }

    @Test
    fun clickOwnedBodyStillWaitsForItsStreamedRoute() {
        assertTrue(
            NtkStrictLateRoutePreparationPolicy.shouldWaitForStreamedSourceRoute(
                exactManifestOwned = true,
                streamedExactBodyPending = true,
            )
        )
    }

    @Test
    fun quarantinePreparationStillWaitsForClickProbe() {
        assertTrue(
            NtkStrictLateRoutePreparationPolicy.shouldWaitForStreamedSourceRoute(
                exactManifestOwned = false,
                streamedExactBodyPending = false,
            )
        )
    }

    @Test
    fun failedStreamedSuffixPublishesAdmissionBeforeFallbackRouteRevalidation() {
        val source = File(
            "src/main/java/ml/melun/mangaview/reader/NtkStrictSourceSession.kt",
        ).readText()
        val start = source.indexOf("private fun acceptStreamedExactBodyCompletionActor(")
        val end = source.indexOf("private fun validateStreamedExactBody(", start)
        val method = source.substring(start, end)
        val failureBranch = method.indexOf("if (failure != null || body == null)")
        val admission = method.indexOf(
            "rollingAdmittedPages = rollingAdmittedPages + pageIndex",
            failureBranch,
        )
        val fallback = method.indexOf("prepareFallbackRouteForStreamedPage(pageIndex)", failureBranch)

        assertTrue(failureBranch >= 0)
        assertTrue(admission > failureBranch)
        assertTrue(fallback > admission)
    }

    @Test
    fun adjacentReleaseReconcilesRoutesForPreviouslyAdmittedFailedBodies() {
        val source = File(
            "src/main/java/ml/melun/mangaview/reader/NtkStrictSourceSession.kt",
        ).readText()
        val start = source.indexOf("private fun releaseAdjacentPrefetchActor(")
        val end = source.indexOf("fun applyPreGeometryPlan(", start)
        val method = source.substring(start, end)

        assertTrue(method.contains("startReleasedAdjacentRoutePreparationsActor(effectiveAdmission)"))
        assertFalse(method.contains("startReleasedAdjacentRoutePreparationsActor(newlyAdmitted)"))
    }
}
