package ml.melun.mangaview.reader

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkDirectWifiIdleForwardWarmPumpArchitectureTest {
    @Test
    fun successfulExactHandoffKeepsOnlyTwoImmediateFollowersWarmDuringReading() {
        val source = File("src/main/java/ml/melun/mangaview/reader/ReaderSession.kt").readText()
        val start = source.indexOf("private fun scheduleDirectWifiIdleForwardWarmPump()")
        val end = source.indexOf("private fun requestPage(", start)
        val pump = source.substring(start, end)

        assertTrue(source.contains("markDecodedDrawableReady(index, page, result.width)\n" +
            "        scheduleDirectWifiIdleForwardWarmPump()"))
        assertTrue(pump.contains("visibleLast + DIRECT_WIFI_FORWARD_WARM_AHEAD_PAGES"))
        assertTrue(pump.contains("retainedLast = warmLast"))
        assertTrue(!pump.contains("viewportBusy.get()"))
        assertTrue(!pump.contains("isHostExactOffscreenDecodeInputProtected()"))
        assertTrue(pump.contains("nextIdleForwardWarmPage("))
        assertTrue(pump.contains("physicalForwardWarmIntent = true"))
        assertTrue(source.contains("promoteQueuedStrictExactPhysicalForwardWarm(index, page)"))
        assertTrue(pump.contains("strictExactBodyDescriptors[page.sourceIndex] == null"))

        val demandStart = source.indexOf("val order = buildList {")
        val demandEnd = source.indexOf("trimDecodedWidth(safeAnchor, busy)", demandStart)
        val demand = source.substring(demandStart, demandEnd)
        assertTrue(demand.contains("val physicalForwardWarm = index == directWifiForwardWarmPage"))
        assertTrue(demand.contains("if (!physicallyVisible && !physicalForwardWarm) continue"))
        assertTrue(demand.contains("physicalForwardWarmIntent = physicalForwardWarm"))

        val requestStart = source.indexOf("private fun requestStrictExactSourcePage(")
        val requestEnd = source.indexOf("private fun promoteQueuedStrictExactViewportBlocker(", requestStart)
        val request = source.substring(requestStart, requestEnd)
        assertTrue(request.contains(
            "!isCurrentLaunchBlockedForwardPage(index, page) &&\n" +
                "                        !isPhysicalForwardWarmNow() &&\n" +
                "                        NtkStrictActiveScrollDecodePolicy",
        ))
        assertTrue(request.contains(
            "if (isCurrentLaunchBlockedForwardPage(index, page) ||\n" +
                "                                isPhysicalForwardWarmNow()",
        ))
        assertTrue(request.contains(
            "if (!exactViewportBlockerBeforeInitialRunwayTurn &&\n" +
                "                        !isPhysicalForwardWarmNow() &&\n" +
                "                        hostExactDirectWifiCurrentEpisode",
        ))
        val warmPressureClaim = request.indexOf(
            "if (isPhysicalForwardWarmNow() &&\n" +
                "            hostExactPoolPressureRetiredPages.contains(index)",
        )
        val installedFence = request.indexOf(
            "if (listener.isPageAuthoritativeDrawableInstalled(index)) return",
            warmPressureClaim,
        )
        val pressureClaim = request.indexOf(
            "if (!hostExactPoolPressureRetiredPages.remove(index)) return",
            installedFence,
        )
        assertTrue(warmPressureClaim >= 0)
        assertTrue(installedFence > warmPressureClaim)
        assertTrue(pressureClaim > installedFence)
        assertTrue(request.contains(
            "!exactCurrentLaunchBlocker &&\n" +
                "            !isPhysicalForwardWarmNow()",
        ))
    }
}
