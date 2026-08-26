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
}
