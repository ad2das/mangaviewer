package ml.melun.mangaview.reader

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkBlockedForwardSourceDemandArchitectureTest {
    private val session = File(
        "src/main/java/ml/melun/mangaview/reader/ReaderSession.kt",
    ).readText()

    @Test
    fun exactPhysicalBlockerAdvancesTheSameBoundedSourceWindowBeforeDecodeRedrive() {
        val start = session.indexOf("fun onBlockedForwardPageRequested(")
        require(start >= 0)
        val end = session.indexOf("\n    private fun ", start + 1)
            .takeIf { it >= 0 } ?: session.length
        val blocked = session.substring(start, end)

        val demand = blocked.indexOf("applyAdjacentStrictViewportSourceDemand(")
        val rehydrate = blocked.indexOf("routeStrictAdjacentExactRehydrate(")
        assertTrue(demand >= 0)
        assertTrue(rehydrate > demand)
        assertTrue(blocked.contains("firstVisibleSourceIndex = page.sourceIndex"))
        assertTrue(blocked.contains("lastVisibleSourceIndex = page.sourceIndex"))
        assertTrue(blocked.contains("direction = ReaderSurfaceView.DIRECTION_NEXT"))
    }

    @Test
    fun currentLaunchBlockerBypassesTheBackloggedControlLaneWithoutBroadeningDemand() {
        val start = session.indexOf("fun onBlockedForwardPageRequested(")
        require(start >= 0)
        val end = session.indexOf("\n    private fun ", start + 1)
            .takeIf { it >= 0 } ?: session.length
        val blocked = session.substring(start, end)

        val identityGrant = blocked.indexOf("latestCurrentLaunchBlockedForwardPage.set(page)")
        val directRequest = blocked.indexOf("requestStrictExactSourcePage(", identityGrant)
        val fallbackQueue = blocked.indexOf("control.execute", directRequest)
        assertTrue(identityGrant >= 0)
        assertTrue(directRequest > identityGrant)
        assertTrue(fallbackQueue > directRequest)
        assertTrue(blocked.substring(identityGrant, directRequest).contains(
            "currentPageIndex(page, index)",
        ))
        assertTrue(blocked.substring(identityGrant, directRequest).contains(
            "admission.admitsSource(page.sourceIndex)",
        ))

        val requestStart = session.indexOf("private fun requestStrictExactSourcePage(")
        require(requestStart >= 0)
        val requestEnd = session.indexOf("\n    private fun ", requestStart + 1)
            .takeIf { it >= 0 } ?: session.length
        val request = session.substring(requestStart, requestEnd)
        assertTrue(request.contains(
            "val exactCurrentLaunchBlocker = isCurrentLaunchBlockedForwardPage(index, page)",
        ))
        assertTrue(request.contains(
            "!exactCurrentLaunchBlocker &&\n            hasDeliveredOrPendingDrawable(index)",
        ))
        assertTrue(
            request.indexOf("exactCurrentLaunchBlocker -> strictExactViewportBlockerDecode") <
                request.indexOf("strictExactRollingPixelResidency.get() -> strictExactRollingDecode"),
        )
        assertTrue(request.contains(
            "val exactViewportBlockerAtDecode =",
        ))
        assertTrue(request.contains(
            "bypassProtectedNumericSerialization =\n                                exactViewportBlockerAtDecode",
        ))
        assertTrue(request.contains(
            "allowTransientHostExactPoolOvercommit =\n                                exactViewportBlockerAtDecode",
        ))
        assertTrue(request.contains(
            "!promotedViewportBlocker &&\n" +
                "                        !isCurrentLaunchBlockedForwardPage(index, page) &&\n" +
                "                        !isPhysicalForwardWarmNow() &&\n" +
                "                        shouldSkipDecodeOutsideProtectedNumericWindow(",
        ))
        assertTrue(request.contains(
            "val exactViewportBlockerBeforeInitialRunwayTurn =",
        ))
        assertTrue(request.contains(
            "if (!exactViewportBlockerBeforeInitialRunwayTurn &&\n" +
                "                        !isPhysicalForwardWarmNow() &&\n" +
                "                        hostExactDirectWifiCurrentEpisode &&",
        ))
        assertTrue(session.contains("strictExactViewportBlockerDecode.execute(task)"))
        assertTrue(session.contains("strictExactViewportBlockerDecode.shutdownNow()"))
    }
}
