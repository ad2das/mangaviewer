package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class NtkDirectWifiAdjacentHeadInstallGatePolicyTest {
    @Test
    fun p0IsTheOnlyPreAckPhysicalBody() {
        assertEquals(
            1,
            NtkDirectWifiAdjacentHeadInstallGatePolicy.usableLaneCount(
                progressiveLaneCount = 4,
                preAnchorGateOperations = 1,
                webtoon = true,
                requiresHeadInstall = true,
                anchorBodyPublished = false,
                anchorRequestHeadersSent = false,
                headPixelsInstalled = false,
            ),
        )
        assertEquals(
            0,
            NtkDirectWifiAdjacentHeadInstallGatePolicy.usableLaneCount(
                progressiveLaneCount = 4,
                preAnchorGateOperations = 1,
                webtoon = true,
                requiresHeadInstall = true,
                anchorBodyPublished = true,
                anchorRequestHeadersSent = true,
                headPixelsInstalled = false,
            ),
        )
        assertEquals(
            3,
            NtkDirectWifiAdjacentHeadInstallGatePolicy.usableLaneCount(
                progressiveLaneCount = 4,
                preAnchorGateOperations = 1,
                webtoon = true,
                requiresHeadInstall = true,
                anchorBodyPublished = true,
                anchorRequestHeadersSent = true,
                headPixelsInstalled = true,
            ),
        )
    }

    @Test
    fun sentP0RequestHeadersReleaseExactlyTheFourPageAdjacentRunway() {
        assertEquals(
            4,
            NtkDirectWifiAdjacentHeadInstallGatePolicy.usableLaneCount(
                progressiveLaneCount = 120,
                preAnchorGateOperations = 1,
                webtoon = true,
                requiresHeadInstall = true,
                anchorBodyPublished = false,
                anchorRequestHeadersSent = true,
                headPixelsInstalled = false,
            ),
        )
    }

    @Test
    fun hostGpuEmulatorKeepsP0ExclusiveUntilItsBodyReachesEof() {
        assertEquals(
            1,
            NtkDirectWifiAdjacentHeadInstallGatePolicy.usableLaneCount(
                progressiveLaneCount = 120,
                preAnchorGateOperations = 1,
                webtoon = true,
                requiresHeadInstall = true,
                anchorBodyPublished = false,
                anchorRequestHeadersSent = true,
                headPixelsInstalled = false,
                prioritizeAnchorUntilEof = true,
            ),
        )
        assertEquals(
            0,
            NtkDirectWifiAdjacentHeadInstallGatePolicy.usableLaneCount(
                progressiveLaneCount = 120,
                preAnchorGateOperations = 1,
                webtoon = true,
                requiresHeadInstall = true,
                anchorBodyPublished = true,
                anchorRequestHeadersSent = true,
                headPixelsInstalled = false,
                prioritizeAnchorUntilEof = true,
            ),
        )
        assertEquals(
            3,
            NtkDirectWifiAdjacentHeadInstallGatePolicy.usableLaneCount(
                progressiveLaneCount = 120,
                preAnchorGateOperations = 1,
                webtoon = true,
                requiresHeadInstall = true,
                anchorBodyPublished = true,
                anchorRequestHeadersSent = true,
                headPixelsInstalled = true,
                prioritizeAnchorUntilEof = true,
            ),
        )
    }

    @Test
    fun installedHeadReleasesOnlyTheDirectWifiAdjacentWebtoonTail() {
        assertEquals(
            3,
            NtkDirectWifiAdjacentHeadInstallGatePolicy.usableLaneCount(
                progressiveLaneCount = 120,
                preAnchorGateOperations = 1,
                webtoon = true,
                requiresHeadInstall = true,
                anchorBodyPublished = true,
                anchorRequestHeadersSent = true,
                headPixelsInstalled = true,
            ),
        )
        assertEquals(
            4,
            NtkDirectWifiAdjacentHeadInstallGatePolicy.usableLaneCount(
                progressiveLaneCount = 4,
                preAnchorGateOperations = 1,
                webtoon = true,
                requiresHeadInstall = false,
                anchorBodyPublished = true,
                anchorRequestHeadersSent = true,
                headPixelsInstalled = true,
            ),
        )
    }

    @Test
    fun cellularSniGenericAndManhwaAreNoOps() {
        assertEquals(
            4,
            NtkDirectWifiAdjacentHeadInstallGatePolicy.usableLaneCount(
                progressiveLaneCount = 4,
                preAnchorGateOperations = 1,
                webtoon = true,
                requiresHeadInstall = false,
                anchorBodyPublished = true,
                anchorRequestHeadersSent = false,
                headPixelsInstalled = false,
            ),
        )
        assertEquals(
            4,
            NtkDirectWifiAdjacentHeadInstallGatePolicy.usableLaneCount(
                progressiveLaneCount = 4,
                preAnchorGateOperations = 1,
                webtoon = false,
                requiresHeadInstall = false,
                anchorBodyPublished = true,
                anchorRequestHeadersSent = false,
                headPixelsInstalled = false,
            ),
        )
    }
}
