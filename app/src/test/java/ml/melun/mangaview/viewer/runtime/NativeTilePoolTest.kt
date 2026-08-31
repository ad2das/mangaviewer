package ml.melun.mangaview.viewer.runtime

import java.io.File
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.viewer.PixelBandGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NativeTilePoolTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun safelyRetiredSlotsAreReusedWithANewContentVersion() {
        val bindings = RecordingPixelBindings()
        val pool = NativeTilePool(bindings, maximumBytes = 32L * 1_024L * 1_024L)
        val source = File(temporaryFolder.root, "page.webp").apply { writeBytes(byteArrayOf(1)) }
        val dimensions = PageDimensions(1_000, 5_000)
        val band = PixelBandGrid().bandsIntersecting(dimensions, 0, 1).single()

        val first = pool.decodeBand(source, dimensions, band)
        pool.recycle(first)
        val second = pool.decodeBand(source, dimensions, band)

        assertEquals(first.tiles.map { it.handle }, second.tiles.map { it.handle })
        assertNotEquals(first.tiles.map { it.contentVersion }, second.tiles.map { it.contentVersion })
        assertEquals(1, bindings.allocations)
        assertEquals(2, bindings.publications)
        assertEquals(listOf(1L, 2L), bindings.decodedVersions)
        assertEquals(listOf(1L, 2L), bindings.publishedVersions)
        pool.close()
        pool.close()
        assertEquals(1, bindings.releases)
    }

    @Test
    fun extremelyTallPageKeepsAConstantSingleBandAllocation() {
        val bindings = RecordingPixelBindings()
        val pool = NativeTilePool(bindings, maximumBytes = 8L * 1_024L * 1_024L)
        val source = File(temporaryFolder.root, "tall.png").apply { writeBytes(byteArrayOf(1)) }
        val dimensions = PageDimensions(800, 500_000)
        val bands = PixelBandGrid().bandsIntersecting(dimensions, 0, dimensions.heightPx)

        listOf(bands.first(), bands[bands.size / 2], bands.last()).forEach { band ->
            val pixel = pool.decodeBand(source, dimensions, band)
            assertEquals(1, pixel.tiles.size)
            pool.recycle(pixel)
        }

        assertEquals(1, bindings.allocations)
        assertEquals(3, bindings.publications)
        assertEquals(3, bindings.decodedBands.size)
        pool.close()
    }

    @Test
    fun decodePublishesTheExactVersionAndSourceRange() {
        val bindings = RecordingPixelBindings()
        val pool = NativeTilePool(bindings)
        val source = File(temporaryFolder.root, "range.webp").apply {
            writeBytes(byteArrayOf(1))
        }
        val dimensions = PageDimensions(1_200, 6_000)
        val band = PixelBandGrid().bandsIntersecting(dimensions, 0, dimensions.heightPx)[1]

        val pixel = pool.decodeBand(source, dimensions, band)

        assertEquals(listOf(band.sourceTopPx until band.sourceBottomPx), bindings.decodedBands)
        assertEquals(pixel.tiles.single().contentVersion, bindings.decodedVersions.single())
        assertEquals(bindings.decodedVersions, bindings.publishedVersions)
        assertEquals(listOf(band.displayWidthPx), bindings.decodedWidths)
        pool.close()
    }

    @Test
    fun decodeUsesTheViewportRequestedWidthInsteadOfAFixedRaster() {
        val bindings = RecordingPixelBindings()
        val pool = NativeTilePool(bindings)
        val source = File(temporaryFolder.root, "wide.webp").apply { writeBytes(byteArrayOf(1)) }
        val dimensions = PageDimensions(2_000, 6_000)
        val band = PixelBandGrid().bandsIntersecting(dimensions, 0, 1, 1_080).single()

        val pixel = pool.decodeBand(source, dimensions, band)

        assertEquals(1_080, bindings.decodedWidths.single())
        assertEquals(1_080, pixel.tiles.single().displayWidthPx)
        pool.close()
    }

    @Test
    fun staleNativePublicationIsRejectedAndTheReservationRemainsReusable() {
        val bindings = RecordingPixelBindings().apply { rejectNextPublication = true }
        val pool = NativeTilePool(bindings)
        val source = File(temporaryFolder.root, "stale.webp").apply {
            writeBytes(byteArrayOf(1))
        }
        val dimensions = PageDimensions(800, 2_000)
        val band = PixelBandGrid().bandsIntersecting(dimensions, 0, 1).single()

        assertThrows(IllegalStateException::class.java) {
            pool.decodeBand(source, dimensions, band)
        }
        val recovered = pool.decodeBand(source, dimensions, band)

        assertEquals(1, bindings.allocations)
        assertEquals(listOf(1L, 2L), bindings.decodedVersions)
        assertEquals(2L, recovered.tiles.single().contentVersion)
        pool.close()
    }

    @Test
    fun allocationBudgetUsesNativeStrideInsteadOfLogicalWidth() {
        val bindings = RecordingPixelBindings(strideAlignmentPixels = 64)
        val pool = NativeTilePool(bindings, maximumBytes = 8L * 1_024L * 1_024L)
        val source = File(temporaryFolder.root, "stride.png").apply {
            writeBytes(byteArrayOf(1))
        }
        val dimensions = PageDimensions(799, 3_000)
        val band = PixelBandGrid().bandsIntersecting(dimensions, 0, 1).single()

        val pixel = pool.decodeBand(source, dimensions, band)
        val tile = pixel.tiles.single()
        val expectedStride = 832L
        val expectedBytes = expectedStride * tile.displayHeightPx * 4L

        assertEquals(expectedBytes, tile.allocationBytes)
        assertEquals(expectedBytes, pixel.allocationBytes)
        pool.close()
    }

    @Test
    fun bandAllocationUsesTheWholePageProjectionWithoutRoundingOnePixelShort() {
        val bindings = RecordingPixelBindings()
        val pool = NativeTilePool(bindings)
        val source = File(temporaryFolder.root, "rounding.jpg").apply {
            writeBytes(byteArrayOf(1))
        }
        val dimensions = PageDimensions(1_158, 888)
        val secondBand = PixelBandGrid(maximumWholePageDisplayHeight = 0)
            .bandsIntersecting(dimensions, 0, dimensions.heightPx, 1_080)[1]

        val pixel = pool.decodeBand(source, dimensions, secondBand)

        assertEquals(512, secondBand.sourceTopPx)
        assertEquals(888, secondBand.sourceBottomPx)
        assertEquals(352, pixel.tiles.single().displayHeightPx)
        assertEquals(1_080 to 352, bindings.allocatedSizes.single())
        pool.close()
    }
}

private class RecordingPixelBindings(
    private val strideAlignmentPixels: Int = 1,
) : NativePixelBindings {
    var allocations = 0
    var publications = 0
    var releases = 0
    var rejectNextPublication = false
    val decodedBands = mutableListOf<IntRange>()
    val decodedVersions = mutableListOf<Long>()
    val decodedWidths = mutableListOf<Int>()
    val allocatedSizes = mutableListOf<Pair<Int, Int>>()
    val publishedVersions = mutableListOf<Long>()
    private val sizes = mutableMapOf<Long, Pair<Int, Int>>()
    private val decodedVersionByHandle = mutableMapOf<Long, Long>()

    override fun allocate(width: Int, height: Int): Long = (++allocations).toLong().also {
        sizes[it] = width to height
        allocatedSizes += width to height
    }

    override fun allocationBytes(handle: Long): Long {
        val (width, height) = requireNotNull(sizes[handle])
        val stride = ((width + strideAlignmentPixels - 1) / strideAlignmentPixels) *
            strideAlignmentPixels
        return stride.toLong() * height * 4L
    }

    override fun release(handle: Long) {
        check(sizes.remove(handle) != null) { "Unknown or already released handle" }
        releases += 1
    }

    override fun decodeBand(
        encodedPath: String,
        handle: Long,
        contentVersion: Long,
        sourceWidth: Int,
        sourceHeight: Int,
        sourceTop: Int,
        sourceBottom: Int,
        displayWidth: Int,
    ): Boolean {
        decodedBands += sourceTop until sourceBottom
        decodedVersions += contentVersion
        decodedWidths += displayWidth
        decodedVersionByHandle[handle] = contentVersion
        return true
    }

    override fun publish(handle: Long, contentVersion: Long): Boolean {
        publications += 1
        publishedVersions += contentVersion
        if (rejectNextPublication) {
            rejectNextPublication = false
            return false
        }
        return decodedVersionByHandle[handle] == contentVersion
    }
}
