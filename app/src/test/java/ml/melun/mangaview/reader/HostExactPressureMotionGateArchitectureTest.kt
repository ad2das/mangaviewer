package ml.melun.mangaview.reader

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HostExactPressureMotionGateArchitectureTest {
    private val source = File(
        "src/main/java/ml/melun/mangaview/reader/ReaderSession.kt",
    ).readText()
    private val poolSource = File(
        "src/main/java/ml/melun/mangaview/reader/HostExactHardwareTilePool.kt",
    ).readText()

    @Test
    fun pressureRetirementChecksMotionBeforeAndAfterTakingPageTableLock() {
        val start = source.indexOf(
            "private fun scheduleHostExactPoolPressureTrim(minimumRetirementBytes: Long)",
        )
        val end = source.indexOf(
            "private fun isHostExactPoolPressureTrimBlockedByPhysicalMotion()",
            start,
        )
        assertTrue(start >= 0)
        assertTrue(end > start)
        val body = source.substring(start, end)

        val firstGate = body.indexOf(
            "if (isHostExactPoolPressureTrimBlockedByPhysicalMotion())",
        )
        val pageLock = body.indexOf("synchronized(pagesLock)")
        val secondGate = body.indexOf(
            "if (isHostExactPoolPressureTrimBlockedByPhysicalMotion())",
            firstGate + 1,
        )
        val mutation = body.indexOf("evictDeliveredBitmaps(")
        assertTrue(firstGate >= 0)
        assertTrue(pageLock > firstGate)
        assertTrue(secondGate > pageLock)
        assertTrue(mutation > secondGate)
        assertTrue(body.contains("deferredForPhysicalMotion = true"))
        assertTrue(body.contains("NTK_HOST_EXACT_PRESSURE_MOTION_RECHECK_MS"))
    }

    @Test
    fun motionGateProtectsPointerAndItsQuietFenceButAllowsOffscreenFlingRetirement() {
        val start = source.indexOf(
            "private fun isHostExactPoolPressureTrimBlockedByPhysicalMotion()",
        )
        val end = source.indexOf("private fun bitmapReleaseLocked(", start)
        assertTrue(start >= 0)
        assertTrue(end > start)
        val body = source.substring(start, end)

        assertTrue(body.contains("isHostExactOffscreenDecodeInputProtected()"))
        assertTrue(body.contains("!hasMissingHostExactPhysicalSurfaceBlocker()"))
        assertTrue(body.contains("reportedPhysicalWindowLocked("))
        assertTrue(body.contains("!listener.isPageDrawableInstalled(index)"))
    }

    @Test
    fun physicallyVisibleRehydratedPagePromotesItsMirrorDuringContinuousInput() {
        val start = source.indexOf(
            "private fun isStrictExactMirrorPublicationRequiredNow(",
        )
        val end = source.indexOf("private fun awaitStrictInitialDecodeRunwayTurn(", start)
        assertTrue(start >= 0)
        assertTrue(end > start)
        val policy = source.substring(start, end)

        assertTrue(policy.contains("isCurrentLaunchBlockedForwardPage(index, page)"))
        assertTrue(policy.contains("latestPhysicalForwardIntentPage.get() === page"))
        assertTrue(policy.contains("isStrictAdjacentPageInReportedPhysicalIntent(index, page)"))
        assertTrue(policy.contains("(pendingDeliveryWidths[currentIndex] ?: 0) > 0"))
        assertTrue(policy.contains("hasDeliveredBitmap(currentIndex)"))
        assertTrue(
            Regex("mirrorPublicationRequiredNow = \\{\\s*" +
                "exactLaunchCompositorForwardWarmIntent \\|\\|\\s*" +
                "isStrictExactMirrorPublicationRequiredNow\\(index, page\\)\\s*}")
                .findAll(source)
                .count() >= 2,
        )
    }

    @Test
    fun decodedPageTokenCannotReachSurfaceBeforeItsNativeMirrorIsReady() {
        assertTrue(
            Regex("awaitCompletion = true").findAll(poolSource).count() >= 3,
        )
        assertTrue(!poolSource.contains("awaitCompletion = prioritizeMirrorPublication"))
        assertTrue(poolSource.contains("if (index < pageSlots.lastIndex)"))
        assertTrue(poolSource.contains("SystemClock.sleep(MIRROR_INTER_TILE_YIELD_MS)"))
    }

    @Test
    fun pressureTrimRetainsPendingExactAdjacentRunwayUntilAtomicCommit() {
        val trimStart = source.indexOf(
            "private fun trimPendingBitmapDeliveriesOutside(",
        )
        val trimEnd = source.indexOf(
            "private fun trimPendingProtectedNumericBitmaps(",
            trimStart,
        )
        assertTrue(trimStart >= 0)
        assertTrue(trimEnd > trimStart)
        val trim = source.substring(trimStart, trimEnd)
        assertTrue(trim.contains("protectedDisplayIndexes: Set<Int> = emptySet()"))
        assertTrue(trim.contains("index in first..last || index in protectedDisplayIndexes"))

        val pressureStart = source.indexOf(
            "private fun scheduleHostExactPoolPressureTrim(minimumRetirementBytes: Long)",
        )
        val pressureEnd = source.indexOf(
            "private fun isHostExactPoolPressureTrimBlockedByPhysicalMotion()",
            pressureStart,
        )
        assertTrue(pressureStart >= 0)
        assertTrue(pressureEnd > pressureStart)
        val pressure = source.substring(pressureStart, pressureEnd)
        assertTrue(
            pressure.contains(
                "protectedExactOffscreenRunwayDisplayIndexesLocked()",
            ),
        )
        assertTrue(
            Regex(
                "trimPendingBitmapDeliveriesOutside\\(\\s*keep\\[0],\\s*" +
                    "keep\\[1],\\s*protectedPendingRunwayIndexes,",
            ).containsMatchIn(pressure),
        )

        val runwayStart = source.indexOf(
            "private fun protectedExactOffscreenRunwayDisplayIndexesLocked()",
        )
        val runwayEnd = source.indexOf(
            "private fun shouldProtectDeliveredPixelFromClear(",
            runwayStart,
        )
        assertTrue(runwayStart >= 0)
        assertTrue(runwayEnd > runwayStart)
        val runway = source.substring(runwayStart, runwayEnd)
        assertTrue(runway.contains("publishedExactOffscreenRunwayPaths"))
        assertTrue(runway.contains("activeRemainingAdjacentRunwayTargetPaths"))
        assertTrue(
            runway.contains(
                "if (physicalViewportHasInstalledEpisodeBodyLocked(page.manga)) continue",
            ),
        )
        assertTrue(
            runway.contains(
                "page.sourceIndex < requiredInitialAdjacentRunwayPages(page.manga)",
            ),
        )
        assertTrue(runway.contains("listener.isPageDrawableInstalled(index)"))
        assertTrue(runway.contains("candidate.transitionTitle == null"))
    }

    @Test
    fun ordinaryBitmapBudgetCannotRetireTheReportedPhysicalViewport() {
        val trimStart = source.indexOf("private fun trimDeliveredBudgetLocked(")
        val trimEnd = source.indexOf("private fun trimDeliveredBitmapsToBudget()", trimStart)
        assertTrue(trimStart >= 0)
        assertTrue(trimEnd > trimStart)
        val trim = source.substring(trimStart, trimEnd)
        assertTrue(trim.contains("check(Thread.holdsLock(pagesLock))"))
        assertTrue(trim.contains("reportedPhysicalDecodeProtectionWindowLocked("))
        assertTrue(
            trim.split("entry.key in protectedPhysicalWindow").size - 1 >= 2,
        )
        assertTrue(trim.contains("protectedPhysicalWindow,"))

        val retainedStart = source.indexOf(
            "private fun trimRetainedBitmapUnderPressureLocked(",
        )
        val retainedEnd = source.indexOf(
            "private fun strictExactLaunchDisplayIndexesLocked()",
            retainedStart,
        )
        assertTrue(retainedStart >= 0)
        assertTrue(retainedEnd > retainedStart)
        val retained = source.substring(retainedStart, retainedEnd)
        assertTrue(
            retained.split(".filter { it !in protectedPhysicalWindow }").size - 1 >= 2,
        )
    }

    @Test
    fun adaptivePressureCannotRetireTheColdResumeAnchorBeforeItsFirstFrame() {
        val start = source.indexOf(
            "private fun evictDeliveredBitmapsWithinHostPressureWindow(",
        )
        val end = source.indexOf(
            "private fun trimDirectWifiLaunchPixelsOutsideWindow()",
            start,
        )
        assertTrue(start >= 0)
        assertTrue(end > start)
        val adaptive = source.substring(start, end)

        assertTrue(adaptive.contains("val startupAnchor = currentStartPage().coerceIn("))
        assertTrue(
            adaptive.split("index == startupAnchor ||").size - 1 >= 2,
        )
    }
}
