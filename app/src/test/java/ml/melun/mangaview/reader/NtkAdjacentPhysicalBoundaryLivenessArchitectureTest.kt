package ml.melun.mangaview.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NtkAdjacentPhysicalBoundaryLivenessArchitectureTest {
    private val coordinator = File(
        "src/main/java/ml/melun/mangaview/reader/NtkStrictEpisodeDiscoveryCoordinator.kt",
    ).readText()
    private val session = File(
        "src/main/java/ml/melun/mangaview/reader/ReaderSession.kt",
    ).readText()
    private val pacer = File(
        "src/main/java/ml/melun/mangaview/reader/NtkReaderTransferPacer.kt",
    ).readText()

    @Test
    fun realSurfaceBoundaryReleasesOnlyMotionDeferralNotNetworkOrStructure() {
        val release = functionBody(
            coordinator,
            "fun releaseAdjacentPhysicalBoundaryDemand(",
            "private fun enterForegroundNetworkIfNeeded(",
        )
        assertTrue(release.contains("ViewerTelemetry.isActiveViewer("))
        assertTrue(release.contains("physicalBoundaryAdjacentTargets"))
        assertTrue(release.contains("adjacentPhysicalBoundaryDemand.complete(Unit)"))
        assertFalse(release.contains("releaseAdjacentBodyGate("))
        assertFalse(release.contains("completedAdjacentTargets["))
        assertFalse(release.contains("startInternal("))

        val physicalBoundary = functionBody(
            session,
            "fun onPhysicalBoundaryReached(",
            "fun onBlockedForwardPageRequested(",
        )
        assertTrue(physicalBoundary.contains("latestPhysicalForwardBoundaryPage.set(evidence)"))
        assertTrue(physicalBoundary.contains("releaseAdjacentPhysicalBoundaryDemand("))
        assertTrue(physicalBoundary.indexOf("latestPhysicalForwardBoundaryPage.set(evidence)") <
            physicalBoundary.indexOf("releaseAdjacentPhysicalBoundaryDemand("))
    }

    @Test
    fun adjacentControlWaitsForIdleUntilExactPhysicalDemandBecomesMandatory() {
        assertTrue(pacer.contains("fun awaitMotionIdleUntilRequired("))
        assertTrue(pacer.contains("while (!requiredNow() && isDisplayPriorityActive())"))
        assertTrue(coordinator.contains(
            "requiredNow = flight.adjacentPhysicalBoundaryDemand::isDone",
        ))
        assertFalse(coordinator.contains(
            "NtkReaderTransferPacer.awaitMotionIdle {\n" +
                "                        requireDiscoveryOwnership(flight, \"document_parse_motion_wait\")",
        ))
    }

    @Test
    fun exactTargetEntryRunwayCannotRemainBehindMotionAfterRealBoundary() {
        val targetDemand = functionBody(
            session,
            "private fun isPhysicalBoundaryDemandingAdjacentTarget(",
            "private fun appendRemainingAdjacentRunwayRefs(",
        )
        assertTrue(targetDemand.contains("forwardAdjacentCompletionTargetHistory[targetPath]"))
        assertTrue(targetDemand.contains("currentForwardAdjacentCompletionTargetClaim("))
        assertTrue(targetDemand.contains("latestPhysicalForwardBoundaryPage.get()"))
        assertTrue(targetDemand.contains("current === boundary"))

        val structureGate = functionBody(
            session,
            "private fun shouldDeferDirectWifiAdjacentStructurePublication(",
            "private fun isPhysicalBoundaryDemandingAdjacentTarget(",
        )
        assertTrue(structureGate.contains(
            "if (isPhysicalBoundaryDemandingAdjacentTarget(target)) return false",
        ))
        assertTrue(session.contains(
            ") && !isPhysicalBoundaryDemandingAdjacentTarget(\n" +
                "                    indexedPages.firstOrNull()?.second?.manga,",
        ))
    }

    private fun functionBody(source: String, start: String, end: String): String {
        val startIndex = source.indexOf(start)
        val endIndex = source.indexOf(end, startIndex + start.length)
        check(startIndex >= 0 && endIndex > startIndex) { "Unable to isolate $start" }
        return source.substring(startIndex, endIndex)
    }
}
