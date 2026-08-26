package ml.melun.mangaview.reader

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkStrictReverseDecodeRetentionTest {
    @Test
    fun blockedForwardIdleFallbackCannotFlipTheStrictSourceDirection() {
        val sessionSource =
            File("src/main/java/ml/melun/mangaview/reader/ReaderSession.kt").readText()
        val strictStart = sessionSource.indexOf("private fun requestStrictExactColdWindow(")
        val strictEnd = sessionSource.indexOf(
            "private fun requestActiveGeneratedScrollRunway(",
            strictStart,
        )
        val strictWindow = sessionSource.substring(strictStart, strictEnd)
        val resolverStart = sessionSource.indexOf("private fun resolveWindowDirection(")
        val resolverEnd = sessionSource.indexOf(
            "fun preparedRunwayDecodeColdForTest(",
            resolverStart,
        )
        val resolver = sessionSource.substring(resolverStart, resolverEnd)
        val surfaceSource =
            File("src/main/java/ml/melun/mangaview/reader/ReaderSurfaceView.kt").readText()
        val blockedStart = surfaceSource.indexOf(
            "private fun scheduleBlockedForwardWindowRequestLocked()",
        )
        val blockedEnd = surfaceSource.indexOf("private fun ", blockedStart + 16)
        val blockedRequest = surfaceSource.substring(blockedStart, blockedEnd)

        assertTrue(strictWindow.contains("resolveWindowDirection("))
        assertTrue(strictWindow.contains("busy = busy"))
        assertFalse(strictWindow.contains("safeAnchor < lastWindowAnchor -> -1"))
        assertTrue(resolver.contains("currentAnchor > previousAnchor -> 1"))
        assertTrue(resolver.contains("busy && currentAnchor < previousAnchor -> -1"))
        assertTrue(blockedRequest.contains("anchorPage = firstBlocked"))
        assertTrue(blockedRequest.contains("busy = true"))
    }

    @Test
    fun clearedExternalPixelsTransferToTheNativeRetirementOwner() {
        val source = File("src/main/java/ml/melun/mangaview/reader/ReaderSession.kt").readText()
        val releases = functionBody(source, "private fun postBitmapReleases(")
        val transfer = functionBody(source, "private fun transferSessionOwnedBitmapRetirement(")

        assertTrue(source.contains("WeakHashMap<Bitmap, Boolean>()"))
        assertTrue(releases.contains("transferSessionOwnedBitmapRetirement(ownedBitmaps)"))
        assertTrue(releases.contains("postSessionOwnedBitmapRetirement(ownedBitmaps"))
        assertFalse(releases.contains("recycleBitmapAfterPressureDelay(bitmap)"))
        assertFalse(releases.contains("recycleBitmapAfterDelay(bitmap)"))
        assertTrue(transfer.contains("surfaceRetirementTransferredBitmaps.add(bitmap)"))
        assertTrue(transfer.contains("listener.onSessionOwnedBitmapRetirement(transferable)"))
        assertFalse(transfer.contains("bitmap.recycle()"))

        val deliveryStart = source.indexOf("private fun deliverDecodeResultOnMain(")
        val deliveryEnd = source.indexOf("private fun deliverInitialPagesReady", deliveryStart)
        val delivery = source.substring(deliveryStart, deliveryEnd)
        val externalMark = delivery.indexOf(
            "claimDecodeResultsExternallyOwned(",
        )
        val listenerDispatch = delivery.indexOf("listener.onPageAuthoritativeTilesReady(")
        val tracked = delivery.indexOf("trackDeliveredResult(", listenerDispatch)
        assertTrue(externalMark >= 0 && listenerDispatch > externalMark && tracked > listenerDispatch)

        val workerHandoff = functionBody(
            source,
            "private fun handOffStrictExactAuthoritativeTiles(",
        )
        val workerMark = workerHandoff.indexOf("claimDecodeResultsExternallyOwned(")
        val workerListener = workerHandoff.indexOf("listener.onPageAuthoritativeTilesReady(")
        assertTrue(workerMark >= 0 && workerListener > workerMark)
        assertFalse(source.contains("releaseExternalBitmapOwnershipClaim("))
        assertFalse(source.contains("commitExternalBitmapOwnershipClaim("))

        val claim = functionBody(source, "private fun claimBitmapIdentitiesExternallyOwned(")
        assertTrue(claim.contains("bitmap.isRecycled"))
        assertTrue(claim.contains("bitmap in surfaceRetirementTransferredBitmaps"))
        assertTrue(claim.contains("externallyOwnedBitmaps.addAll(unique)"))
        val cleanup = functionBody(source, "private fun releaseBitmapToPoolOrRecycle(")
        assertTrue(cleanup.contains("synchronized(externallyOwnedBitmaps)"))
        assertTrue(cleanup.contains("bitmap.recycle()"))
        assertFalse(cleanup.contains("bitmapPool.put"))

        val append = functionBody(source, "private fun appendRemainingAdjacentRunwayRefs(")
        val batchClaim = append.indexOf("claimDecodeResultsExternallyOwned(")
        val batchListener = append.indexOf("listener.onAdjacentExactRunwayBatchReady(")
        assertTrue(batchClaim >= 0 && batchListener > batchClaim)

        val retire = functionBody(source, "private fun retireConsumedForwardHistoryPixels(")
        val postClear = retire.indexOf("postBitmapReleases(releases) {")
        assertTrue(postClear >= 0)
        assertFalse(retire.contains("externallyOwnedBitmaps"))
        assertFalse(source.contains("externallyOwnedBitmaps.clear()"))
        assertFalse(source.contains("externallyOwnedBitmaps.remove("))
    }

    @Test
    fun forwardHistoryPixelRetirementIsOwnedOncePerExactActiveManifest() {
        val source = File("src/main/java/ml/melun/mangaview/reader/ReaderSession.kt").readText()
        val trimStart = source.indexOf("private fun trimConsumedForwardHistory(")
        val trimEnd = source.indexOf("private fun retireConsumedStrictSources(", trimStart)
        val trim = source.substring(trimStart, trimEnd)
        val consumedStart = trimEnd
        val consumedEnd = source.indexOf("private data class ForwardHistoryTrimCandidate(", consumedStart)
        val consumed = source.substring(consumedStart, consumedEnd)
        val cancelStart = source.indexOf("private fun cancelInternal(")
        val cancelEnd = source.indexOf("private fun releaseStrictRequiredEpisodePath(", cancelStart)
        val cancel = source.substring(cancelStart, cancelEnd)

        val claim = trim.indexOf("forwardPixelRetirementLedger::tryClaim")
        val firstRetire = trim.indexOf("retireConsumedForwardHistoryPixels(")
        assertTrue(claim >= 0 && firstRetire > claim)
        assertTrue(trim.split("retireConsumedForwardHistoryPixels(").size - 1 == 2)
        assertTrue(trim.contains("else if (ownsPixelRetirement)"))
        assertFalse(trim.contains("else if (pixelCandidate.removeCount > 0)"))
        assertTrue(trim.contains("if (!released) forwardHistoryPixelClearPending.set(false)"))
        assertTrue(consumed.contains("forwardPixelRetirementLedger.removeEpisodePaths(paths)"))
        assertTrue(cancel.contains("forwardPixelRetirementLedger.clear()"))
    }

    @Test
    fun exactSuccessorPhysicalFrameReleasesOnlyLaunchPixelProtection() {
        val source = File("src/main/java/ml/melun/mangaview/reader/ReaderSession.kt").readText()
        val callbackStart = source.indexOf("fun onExactNtkAdjacentActualFramePresented(")
        val callbackEnd = source.indexOf("fun onExactNtkPhysicalDrawPresented(", callbackStart)
        val callbackAndRelease = source.substring(callbackStart, callbackEnd)
        val releaseCall = callbackAndRelease.indexOf(
            "releaseStrictLaunchPixelProtectionAfterSuccessorPresented()",
        )
        val transportGate = callbackAndRelease.indexOf(
            "claim.firstActualFramePresented.compareAndSet(false, true)",
        )
        val protectionStart = source.indexOf(
            "private fun protectedStrictExactLaunchDisplayIndexes(",
        )
        val protectionEnd = source.indexOf(
            "private fun shouldProtectDeliveredPixelFromClear(",
            protectionStart,
        )
        val protection = source.substring(protectionStart, protectionEnd)

        assertTrue(releaseCall >= 0 && transportGate > releaseCall)
        assertTrue(
            callbackAndRelease.contains(
                "strictExactSuccessorPhysicallyPresented.compareAndSet(false, true)",
            ),
        )
        assertTrue(callbackAndRelease.contains("control.execute"))
        assertTrue(callbackAndRelease.contains("trimDeliveredBitmapsToBudget()"))
        assertFalse(callbackAndRelease.contains("strictExactBodyDescriptors.clear()"))
        assertFalse(callbackAndRelease.contains("pages.clear()"))
        assertTrue(
            protection.contains(
                "successorPhysicallyPresented = strictExactSuccessorPhysicallyPresented.get()",
            ),
        )
        assertTrue(protection.contains("activeRemainingAdjacentRunwayTargetPaths"))
        assertTrue(protection.contains("requiredInitialAdjacentRunwayPages(page.manga)"))
    }

    @Test
    fun genericAdjacentWindowsShareTheStrictBudgetedEvictionPolicy() {
        val source = File("src/main/java/ml/melun/mangaview/reader/ReaderSession.kt").readText()
        val requestStart = source.indexOf("private fun requestWindow(")
        val requestEnd = source.indexOf("private fun requestStrictExactColdWindow(", requestStart)
        val requestWindow = source.substring(requestStart, requestEnd)
        val helperStart = source.indexOf("private fun trimDeliveredPixelsForRetainedWindow(")
        val helperEnd = source.indexOf("private fun activeBitmapBudgetBytes(", helperStart)
        val helper = source.substring(helperStart, helperEnd)

        assertTrue(
            requestWindow.split("trimDeliveredPixelsForRetainedWindow(retainFirst, retainLast)")
                .size - 1 == 2,
        )
        assertFalse(requestWindow.contains("evictDeliveredBitmaps(retainFirst, retainLast)"))
        assertTrue(requestWindow.contains("publishProtectedBitmapWindowSnapshot("))
        assertTrue(requestWindow.contains("trimPendingProtectedNumericBitmaps(retainFirst, retainLast)"))
        assertTrue(helper.contains("shouldHardEvictOutsideRetainedWindow("))
        assertTrue(helper.contains("evictDeliveredBitmaps(first, last)"))
        assertTrue(helper.contains("trimDeliveredBitmapsToBudget()"))
    }

    @Test
    fun rollingSourceDemandUsesBudgetedLruInsteadOfHardViewportEviction() {
        val source = File("src/main/java/ml/melun/mangaview/reader/ReaderSession.kt").readText()
        val start = source.indexOf("private fun requestStrictExactColdWindow(")
        val end = source.indexOf("private fun requestActiveGeneratedScrollRunway(", start)
        val method = source.substring(start, end)

        assertTrue(method.contains("trimDeliveredBitmapsToBudget()"))
        assertTrue(method.contains("trimPendingProtectedNumericBitmaps(retainedFirst, retainedLast)"))
        assertFalse(method.contains("evictDeliveredBitmaps(demandFirst, demandLast)"))
        assertTrue(source.contains("LinkedHashMap<Int, Bitmap>(32, 0.75f, true)"))
        assertTrue(
            source.contains("trimDeliveredBudgetLocked(") &&
                source.contains("protectedLaunchIndexes,") &&
                source.contains("strictLaunchIndexes,") &&
                source.contains("protectedPixelWindow,"),
        )
        assertTrue(source.contains("shouldProtectDeliveredPixelFromClear("))
    }

    @Test
    fun boundedNumericWindowIsCheckedBeforeEitherColdDecodeRoute() {
        val source = File("src/main/java/ml/melun/mangaview/reader/ReaderSession.kt").readText()
        val strictStart = source.indexOf("private fun requestStrictExactSourcePage(")
        val strictEnd = source.indexOf("private fun requestPage(", strictStart)
        val strictWorker = source.substring(strictStart, strictEnd)
        val visibleStart = source.indexOf("val activeGeneratedProofGate =")
        val visibleEnd = source.indexOf("private fun releaseActiveGeneratedProofDecodeGate", visibleStart)
        val visibleWorker = source.substring(visibleStart, visibleEnd)
        val requestStart = source.indexOf("val networkExecutor = when")
        val requestEnd = source.indexOf("private fun shouldHedgeForegroundPrime", requestStart)
        val requestWorker = source.substring(requestStart, requestEnd)

        val visibleGate = visibleWorker.indexOf(
            "shouldSkipDecodeOutsideProtectedNumericWindow("
        )
        val visibleDecode = visibleWorker.indexOf("decodePage(index, page, cached")
        assertTrue(visibleGate >= 0 && visibleDecode > visibleGate)

        val requestGate = requestWorker.indexOf(
            "shouldSkipDecodeOutsideProtectedNumericWindow("
        )
        val requestDecode = requestWorker.indexOf("decodePageWithLease(")
        assertTrue(requestGate >= 0 && requestDecode > requestGate)

        val strictRequestGate = strictWorker.indexOf(
            "\"strict_exact_request\""
        )
        val strictWorkerGate = strictWorker.indexOf(
            "\"strict_exact_worker\""
        )
        val strictDecode = strictWorker.indexOf("val result = opened.predecodedOriginal")
        assertTrue(strictRequestGate >= 0)
        assertTrue(strictWorkerGate > strictRequestGate && strictDecode > strictWorkerGate)

        assertTrue(source.contains("decode_skip_outside_bounded_window"))
        assertTrue(source.contains("decode_drop_outside_bounded_window"))
    }

    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "Missing function: $signature" }
        val open = source.indexOf('{', start)
        check(open >= 0) { "Missing opening brace: $signature" }
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        error("Unclosed function: $signature")
    }
}
