package ml.melun.mangaview.reader

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkAdjacentBodyStoragePolicyTest {
    @Test
    fun onlyHostAdjacentPagesBeyondTheInitialRunwayDeferDuringPhysicalMotion() {
        assertFalse(
            NtkAdjacentBodyStoragePolicy.deferOffscreenTailDuringPhysicalMotion(
                hostGpuEmulatorRuntime = true,
                adjacentPrefetch = true,
                pageIndex = 4,
                initialPageIndex = 0,
                adjacentInitialRunwayBodyCount = 5,
            ),
        )
        assertTrue(
            NtkAdjacentBodyStoragePolicy.deferOffscreenTailDuringPhysicalMotion(
                hostGpuEmulatorRuntime = true,
                adjacentPrefetch = true,
                pageIndex = 5,
                initialPageIndex = 0,
                adjacentInitialRunwayBodyCount = 5,
            ),
        )
        assertFalse(
            NtkAdjacentBodyStoragePolicy.deferOffscreenTailDuringPhysicalMotion(
                hostGpuEmulatorRuntime = false,
                adjacentPrefetch = true,
                pageIndex = 5,
                initialPageIndex = 0,
                adjacentInitialRunwayBodyCount = 5,
            ),
        )
        assertFalse(
            NtkAdjacentBodyStoragePolicy.deferOffscreenTailDuringPhysicalMotion(
                hostGpuEmulatorRuntime = true,
                adjacentPrefetch = false,
                pageIndex = 5,
                initialPageIndex = 0,
                adjacentInitialRunwayBodyCount = 5,
            ),
        )
    }

    @Test
    fun hostGpuEpisodesUseTheSealedBodyAcrossRouteAndLifecycleClassification() {
        listOf("/manhwa/2/1816636", "/webtoon/2/1816636").forEach { episodePath ->
            listOf(true, false).forEach { adjacentPrefetch ->
                assertTrue(
                    NtkAdjacentBodyStoragePolicy.useFileBackedQuarantine(
                        hostGpuEmulatorRuntime = true,
                        directWifiTransport = true,
                        cellularResilientTransport = false,
                        adjacentPrefetch = adjacentPrefetch,
                        episodePath = episodePath,
                    ),
                )
            }
        }
        assertTrue(
            NtkAdjacentBodyStoragePolicy.useFileBackedQuarantine(
                true, false, false, true, "/manhwa/2/1816636",
            ),
        )
        assertTrue(
            NtkAdjacentBodyStoragePolicy.useFileBackedQuarantine(
                true, true, true, true, "/manhwa/2/1816636",
            ),
        )
        val rejected = listOf(
            NtkAdjacentBodyStoragePolicy.useFileBackedQuarantine(
                false, true, false, true, "/manhwa/2/1816636",
            ),
            NtkAdjacentBodyStoragePolicy.useFileBackedQuarantine(
                true, true, false, true, "not-an-episode-path",
            ),
        )
        rejected.forEach(::assertFalse)
    }

    @Test
    fun strictSourceFilesTheInitialRunwayAndSuffixWithoutChangingRouteGuards() {
        assertTrue(
            NtkAdjacentBodyStoragePolicy.useFileBackedStrictSource(
                hostGpuEmulatorRuntime = true,
                directWifiTransport = true,
                cellularResilientTransport = false,
                adjacentPrefetch = true,
                episodePath = "/manhwa/2/1816636",
                pageIndex = 4,
                initialPageIndex = 0,
                adjacentInitialRunwayBodyCount = 5,
            ),
        )
        assertTrue(
            NtkAdjacentBodyStoragePolicy.useFileBackedStrictSource(
                hostGpuEmulatorRuntime = true,
                directWifiTransport = true,
                cellularResilientTransport = false,
                adjacentPrefetch = true,
                episodePath = "/manhwa/2/1816636",
                pageIndex = 5,
                initialPageIndex = 0,
                adjacentInitialRunwayBodyCount = 5,
            ),
        )
        assertTrue(
            NtkAdjacentBodyStoragePolicy.useFileBackedStrictSource(
                hostGpuEmulatorRuntime = true,
                directWifiTransport = true,
                cellularResilientTransport = false,
                adjacentPrefetch = false,
                episodePath = "/manhwa/2/1816636",
                pageIndex = 5,
                initialPageIndex = 0,
                adjacentInitialRunwayBodyCount = 5,
            ),
        )
    }

    @Test
    fun hostExactDecodeSkipsTheRedundantPrivateBitmapForEitherOwnedSourceForm() {
        assertTrue(
            NtkAdjacentBodyStoragePolicy.useNativeFileDecodeInsteadOfPrivateBitmap(
                hostGpuEmulatorRuntime = true,
                directWifiAdjacentOwned = true,
                encodedBytesAvailable = false,
                sealedFileAvailable = true,
            ),
        )
        assertFalse(
            NtkAdjacentBodyStoragePolicy.useNativeFileDecodeInsteadOfPrivateBitmap(
                hostGpuEmulatorRuntime = false,
                directWifiAdjacentOwned = true,
                encodedBytesAvailable = false,
                sealedFileAvailable = true,
            ),
        )
        assertTrue(
            NtkAdjacentBodyStoragePolicy.useNativeFileDecodeInsteadOfPrivateBitmap(
                hostGpuEmulatorRuntime = true,
                directWifiAdjacentOwned = true,
                encodedBytesAvailable = true,
                sealedFileAvailable = true,
            ),
        )
        assertTrue(
            NtkAdjacentBodyStoragePolicy.useNativeFileDecodeInsteadOfPrivateBitmap(
                hostGpuEmulatorRuntime = true,
                directWifiAdjacentOwned = true,
                encodedBytesAvailable = true,
                sealedFileAvailable = false,
            ),
        )
        assertTrue(
            NtkAdjacentBodyStoragePolicy.useNativeFileDecodeInsteadOfPrivateBitmap(
                hostGpuEmulatorRuntime = true,
                directWifiAdjacentOwned = false,
                encodedBytesAvailable = false,
                sealedFileAvailable = true,
            ),
        )
    }

    @Test
    fun fileBackedSpoolPreservesTheSameEofDigestAndSealedLeaseBoundary() {
        val cache = File("src/main/java/ml/melun/mangaview/reader/ReaderImageCache.kt").readText()
        val session = File("src/main/java/ml/melun/mangaview/reader/NtkStrictSourceSession.kt")
            .readText()
        val click = File(
            "src/main/java/ml/melun/mangaview/reader/NtkClickOwnedAnchorQuarantine.kt",
        ).readText()
        val start = cache.indexOf("internal fun spoolQuarantinedEncodedOriginal(")
        val end = cache.indexOf("fun predecodeQuarantinedOriginalAsync(", start)
        assertTrue(start >= 0 && end > start)
        val spool = cache.substring(start, end)

        assertTrue(spool.contains("preferFileBackedBody: Boolean = false"))
        assertTrue(spool.contains("FileOutputStream(tempLease.partFile)"))
        assertTrue(spool.contains("fullDigest.update(buffer, 0, count)"))
        assertTrue(spool.contains("parser.feed(buffer, 0, count)"))
        assertTrue(spool.contains("tempLease.seal(encodedLength)"))
        assertTrue(spool.indexOf("tempLease.seal(encodedLength)") <
            spool.indexOf("return NtkQuarantinedBody("))
        assertTrue(session.contains("preferFileBackedBody ="))
        assertTrue(click.contains("preferFileBackedBody ="))
        assertTrue(cache.contains("internal fun spoolStrictPublishedBody("))
        assertTrue(cache.contains("preferFileBackedBody: Boolean = false"))
        assertTrue(session.contains("useFileBackedStrictSource("))
    }

    @Test
    fun queuedFileDecodeCapturesAStableDescriptorBeforeAdoptionCanMoveTheBody() {
        val cache = File("src/main/java/ml/melun/mangaview/reader/ReaderImageCache.kt").readText()
        val start = cache.indexOf("fun predecodeQuarantinedOriginalAsync(")
        val end = cache.indexOf("fun adoptQuarantinedEncodedOriginal(", start)
        assertTrue(start >= 0 && end > start)
        val predecode = cache.substring(start, end)

        assertTrue(predecode.contains("FileInputStream(quarantinedBody.sealedFile)"))
        assertTrue(predecode.contains("queuedFileInput.getAndSet(null)"))
        assertTrue(predecode.contains("BitmapFactory.decodeFileDescriptor("))
        assertFalse(predecode.contains("BitmapFactory.decodeFile("))
        assertTrue(predecode.contains("releaseQueuedSource ="))
    }
}
