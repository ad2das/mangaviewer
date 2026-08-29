package ml.melun.mangaview.reader

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkAdjacentTailMotionReadArchitectureTest {
    @Test
    fun clickOwnedAdjacentTailPausesBytesButKeepsPrefixPrompt() {
        val quarantine = File(
            "src/main/java/ml/melun/mangaview/reader/NtkClickOwnedAnchorQuarantine.kt",
        ).readText()
        val cache = File(
            "src/main/java/ml/melun/mangaview/reader/ReaderImageCache.kt",
        ).readText()

        assertTrue(
            quarantine.contains(
                "deferBodyReadsWhilePhysicalMotion =\n" +
                    "                    NtkClickOwnedManhwaWavePolicy." +
                    "shouldBoundHostGpuAdjacentTailTransfers(",
            ),
        )
        assertTrue(cache.contains("NtkReaderTransferPacer.readOptionalChunk("))
        assertTrue(cache.contains("NtkReaderTransferPacer.readOptionalByte("))
        assertTrue(cache.contains("!NtkReaderTransferPacer.isPhysicalForegroundEpisode("))
    }

    @Test
    fun promotedStrictOwnerKeepsOnlyOneViewportDemandGate() {
        val session = File(
            "src/main/java/ml/melun/mangaview/reader/NtkStrictSourceSession.kt",
        ).readText()
        val cache = File(
            "src/main/java/ml/melun/mangaview/reader/ReaderImageCache.kt",
        ).readText()
        val strictStart = cache.indexOf("internal fun spoolStrictPublishedBody(")
        val strictEnd = cache.indexOf("private fun fileStartsWith(", strictStart)
        assertTrue(strictStart >= 0 && strictEnd > strictStart)
        val strictSpool = cache.substring(strictStart, strictEnd)

        assertTrue(
            session.windowed("NtkAdjacentBodyStoragePolicy.deferOffscreenTailDuringPhysicalMotion(".length)
                .count { it == "NtkAdjacentBodyStoragePolicy.deferOffscreenTailDuringPhysicalMotion(" } >= 2,
        )
        assertTrue(
            session.windowed("viewportDemandBoundsSuffix = demandBoundedAdjacentSuffix".length)
                .count { it == "viewportDemandBoundsSuffix = demandBoundedAdjacentSuffix" } >= 2,
        )
        assertTrue(strictSpool.contains("deferBodyReadsWhilePhysicalMotion: Boolean = false"))
        assertTrue(strictSpool.contains("NtkReaderTransferPacer.readOptionalChunk("))
        assertTrue(strictSpool.contains("NtkReaderTransferPacer.readOptionalByte("))
        assertTrue(strictSpool.contains("shouldRemainDeferred = ::shouldDeferStrictBodyRead"))
    }

    @Test
    fun demandBoundedLookaheadDoesNotParkItsOwnedSocketTwice() {
        assertTrue(
            !NtkAdjacentBodyStoragePolicy.deferOffscreenTailDuringPhysicalMotion(
                hostGpuEmulatorRuntime = true,
                adjacentPrefetch = true,
                pageIndex = 4,
                initialPageIndex = 0,
                adjacentInitialRunwayBodyCount = 4,
                viewportDemandBoundsSuffix = true,
            ),
        )
        assertTrue(
            NtkAdjacentBodyStoragePolicy.deferOffscreenTailDuringPhysicalMotion(
                hostGpuEmulatorRuntime = true,
                adjacentPrefetch = true,
                pageIndex = 4,
                initialPageIndex = 0,
                adjacentInitialRunwayBodyCount = 4,
                viewportDemandBoundsSuffix = false,
            ),
        )
        assertTrue(
            !NtkAdjacentBodyStoragePolicy.deferOffscreenTailDuringPhysicalMotion(
                hostGpuEmulatorRuntime = true,
                adjacentPrefetch = true,
                pageIndex = 3,
                initialPageIndex = 0,
                adjacentInitialRunwayBodyCount = 4,
                viewportDemandBoundsSuffix = false,
            ),
        )
    }

    @Test
    fun hostAdjacentSuffixStartsOnlyFromCompositorBoundedSourceDemand() {
        val strict = File(
            "src/main/java/ml/melun/mangaview/reader/NtkStrictSourceSession.kt",
        ).readText()
        val session = File(
            "src/main/java/ml/melun/mangaview/reader/ReaderSession.kt",
        ).readText()
        val cache = File(
            "src/main/java/ml/melun/mangaview/reader/ReaderImageCache.kt",
        ).readText()
        val activity = File(
            "src/main/java/ml/melun/mangaview/activity/ReaderV2Activity.kt",
        ).readText()

        assertTrue(strict.contains("demandBoundedAdjacentSuffix"))
        assertTrue(strict.contains("explicitViewportDemand?.let"))
        assertTrue(strict.contains("startReleasedAdjacentRoutePreparationsActor(newlyAdmitted)"))
        assertTrue(session.contains("applyAdjacentStrictViewportSourceDemand("))
        assertTrue(session.contains("NTK_ADJACENT_VIEWPORT_SOURCE_LOOKAHEAD"))
        assertTrue(session.contains("NTK_ADJACENT_VIEWPORT_SOURCE_LOOKAHEAD = 8"))
        assertTrue(session.contains("backgroundPages = background.toIntArray()"))
        assertTrue(activity.contains("candidate.sourcePageIndex"))
        assertTrue(activity.contains("onExactNtkAdjacentActualFramePresented("))
    }

    @Test
    fun failedClickOwnedRunwayBodyDoesNotWaitOnItsOwnCleanFrameCycle() {
        val strict = File(
            "src/main/java/ml/melun/mangaview/reader/NtkStrictSourceSession.kt",
        ).readText()
        val fallbackStart = strict.indexOf("private fun prepareFallbackRouteForStreamedPage(")
        val fallbackEnd = strict.indexOf("private fun isRoutePreparationAdmitted(", fallbackStart)
        assertTrue(fallbackStart >= 0 && fallbackEnd > fallbackStart)
        val fallback = strict.substring(fallbackStart, fallbackEnd)

        assertTrue(fallback.contains("waitForStreamedSourceRoute = false"))
        assertTrue(strict.contains("if (waitForStreamedSourceRoute)"))
        assertTrue(strict.contains("CompletableFuture.completedFuture(Unit)"))
    }

    @Test
    fun hostAdjacentClickStreamCedesSuffixBeforeAnyCallCanOwnIt() {
        val click = File(
            "src/main/java/ml/melun/mangaview/reader/NtkClickOwnedAnchorQuarantine.kt",
        ).readText()

        assertTrue(
            click.contains(
                "val viewportDemandOwnsSuffix = directWifiAdjacentOwned && hostGpuEmulatorRuntime",
            ),
        )
        assertTrue(click.contains("!viewportDemandOwnsSuffix || pageIndex < clickOwnedEndExclusive"))
        assertTrue(click.contains("if (viewportDemandOwnsSuffix) return"))
        assertTrue(click.contains("viewportDemandOwnsSuffix = viewportDemandOwnsSuffix"))
    }

    @Test
    fun demandedAdmissionPublishesBeforeNewRoutePreparationRevalidatesIt() {
        val strict = File(
            "src/main/java/ml/melun/mangaview/reader/NtkStrictSourceSession.kt",
        ).readText()
        val demandStart = strict.indexOf("private fun enqueueSourceDemandDelivery(")
        val demandEnd = strict.indexOf("fun onGeometrySealed(", demandStart)
        assertTrue(demandStart >= 0 && demandEnd > demandStart)
        val demand = strict.substring(demandStart, demandEnd)

        val publish = demand.indexOf("rollingAdmittedPages = effectiveAdmission")
        val prepare = demand.indexOf("startReleasedAdjacentRoutePreparationsActor(newlyAdmitted)")
        assertTrue(publish >= 0)
        assertTrue(prepare > publish)
    }

    @Test
    fun physicalBlockerCanRepairAnAlreadyAdmittedButUnfinishedRoute() {
        val contract = File(
            "src/main/java/ml/melun/mangaview/reader/NtkStrictSourceTransport.kt",
        ).readText()
        val strict = File(
            "src/main/java/ml/melun/mangaview/reader/NtkStrictSourceSession.kt",
        ).readText()
        val session = File(
            "src/main/java/ml/melun/mangaview/reader/ReaderSession.kt",
        ).readText()
        val cache = File(
            "src/main/java/ml/melun/mangaview/reader/ReaderImageCache.kt",
        ).readText()

        assertTrue(contract.contains("fun onPhysicalBlockedPageRequested("))
        assertTrue(session.contains("claim.transport.onPhysicalBlockedPageRequested("))
        val blockerStart = session.indexOf("fun onBlockedForwardPageRequested(")
        val blockerEnd = session.indexOf("\n    private fun ", blockerStart + 1)
        assertTrue(blockerStart >= 0 && blockerEnd > blockerStart)
        val blocker = session.substring(blockerStart, blockerEnd)
        val foregroundReassert = blocker.indexOf(
            "NtkReaderTransferPacer.notePhysicalForegroundEpisode(this, normalizedPath)",
        )
        val exactRedrive = blocker.indexOf(
            "claim.transport.onPhysicalBlockedPageRequested(",
        )
        assertTrue(foregroundReassert >= 0)
        assertTrue(exactRedrive > foregroundReassert)
        val start = strict.indexOf("fun onPhysicalBlockedPageRequested(")
        val end = strict.indexOf("fun unresolvedStreamedExactBodyCount()", start)
        assertTrue(start >= 0 && end > start)
        val redrive = strict.substring(start, end)
        assertTrue(redrive.contains("rollingAdmittedPages = rollingAdmittedPages + pageIndex"))
        assertTrue(redrive.contains("manhwaWaveRecoveryState?.markPhysicalBlockedBody(pageIndex)"))
        assertTrue(redrive.contains("prepareFallbackRouteForStreamedPage(pageIndex)"))
        assertTrue(redrive.contains("refillLanesActor()"))
        assertTrue(redrive.contains("PHYSICAL_BLOCKED_SOURCE_REDRIVE_MIN_INTERVAL_MS"))
        assertTrue(cache.contains("ntkManhwaPhysicalBlockedTailPermits"))
        assertTrue(cache.contains("waveRecoveryState?.isPhysicalBlockedBody(pageIndex) == true"))
        assertTrue(cache.contains("tryClaimPhysicalBlockedTail(pageIndex)"))
    }
}
