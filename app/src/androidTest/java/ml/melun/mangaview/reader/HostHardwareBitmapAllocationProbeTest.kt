package ml.melun.mangaview.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies the host-GPU storage candidate before wiring it into exact reader authority. */
@RunWith(AndroidJUnit4::class)
class HostHardwareBitmapAllocationProbeTest {
    @Test
    fun sealedFileDecodesDirectlyIntoExactHardwareTiles() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        val sourceWidth = 1_403
        val sourceHeight = 2_200
        val source = Bitmap.createBitmap(sourceWidth, sourceHeight, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.rgb(29, 113, 197))
        val encoded = ByteArrayOutputStream().use { output ->
            assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, output))
            output.toByteArray()
        }
        source.recycle()
        val file = File.createTempFile(
            "host-exact-direct-",
            ".png",
            ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir,
        )
        try {
            file.writeBytes(encoded)
            fun decodeAndRetire() {
                val decoded = checkNotNull(
                    HostExactHardwareTilePool.decodePage(
                        file,
                        sourceWidth,
                        sourceHeight,
                        2_048,
                    ),
                )
                assertEquals(2, decoded.size)
                assertTrue(decoded.all(HostExactHardwareTilePool::isActiveToken))
                assertEquals(2, HostExactHardwareTilePool.retireAll(decoded).size)
            }
            decodeAndRetire()
            Runtime.getRuntime().gc()
            SystemClock.sleep(500L)
            val before = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull()
            assertNotNull("ART must expose its GC counter", before)
            repeat(24) { decodeAndRetire() }
            val after = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull()
            assertNotNull("ART must expose its final GC counter", after)
            val delta = checkNotNull(after) - checkNotNull(before)
            Log.i("HostHardwareBitmapProbe", "directFilePages=24,tiles=48,gcDelta=$delta")
            assertTrue("Direct file decode churned ART GC: delta=$delta", delta <= 2L)
        } finally {
            file.delete()
        }
    }

    @Test
    fun preallocatedHardwareStorageCanBeRewrappedWithoutArtGcChurn() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        val buffers = List(4) {
            HardwareBuffer.create(
                1_403,
                2_048,
                    HardwareBuffer.RGBA_8888,
                    1,
                    HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
                        HardwareBuffer.USAGE_CPU_READ_RARELY or
                        HardwareBuffer.USAGE_CPU_WRITE_OFTEN,
            )
        }
        val wrappers = arrayOfNulls<Bitmap>(buffers.size)
        try {
            Runtime.getRuntime().gc()
            SystemClock.sleep(500L)
            val before = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull()
            assertNotNull("ART must expose its GC counter", before)
            repeat(48) { ordinal ->
                val index = ordinal % buffers.size
                wrappers[index]?.recycle()
                wrappers[index] = Bitmap.wrapHardwareBuffer(
                    buffers[index],
                    ColorSpace.get(ColorSpace.Named.SRGB),
                )
                assertNotNull("HardwareBuffer must produce an immutable Bitmap", wrappers[index])
                assertEquals(Bitmap.Config.HARDWARE, wrappers[index]!!.config)
            }
            val after = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull()
            assertNotNull("ART must expose its final GC counter", after)
            val delta = checkNotNull(after) - checkNotNull(before)
            Log.i("HostHardwareBitmapProbe", "rewraps=48,storage=4,gcDelta=$delta")
            assertTrue("Preallocated hardware storage still churned ART GC: delta=$delta", delta <= 2L)
        } finally {
            wrappers.filterNotNull().forEach(Bitmap::recycle)
            buffers.forEach(HardwareBuffer::close)
        }
    }

    @Test
    @Suppress("DEPRECATION")
    fun reusableRegionDecodeScratchAvoidsPerPageArtGc() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        val sourceWidth = 1_403
        val sourceHeight = 2_200
        val sourceTop = sourceHeight - 2_048
        val source = Bitmap.createBitmap(sourceWidth, sourceHeight, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.rgb(37, 89, 173))
        val encoded = ByteArrayOutputStream().use { output ->
            assertTrue(source.compress(Bitmap.CompressFormat.JPEG, 94, output))
            output.toByteArray()
        }
        source.recycle()
        val scratch = Bitmap.createBitmap(1_536, 2_048, Bitmap.Config.ARGB_8888)
        fun decodeOnce() {
            scratch.reconfigure(
                sourceWidth,
                sourceHeight - sourceTop,
                Bitmap.Config.ARGB_8888,
            )
            val decoder = checkNotNull(
                BitmapRegionDecoder.newInstance(encoded, 0, encoded.size, false),
            )
            try {
                val decoded = checkNotNull(
                    decoder.decodeRegion(
                        Rect(0, sourceTop, sourceWidth, sourceHeight),
                        BitmapFactory.Options().apply {
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                            inSampleSize = 1
                            inScaled = false
                            inMutable = true
                            inBitmap = scratch
                        },
                    ),
                )
                assertTrue("Region decoder did not reuse the exact scratch identity", decoded === scratch)
                assertEquals(sourceWidth, decoded.width)
                assertEquals(sourceHeight - sourceTop, decoded.height)
            } finally {
                decoder.recycle()
            }
        }
        decodeOnce()
        Runtime.getRuntime().gc()
        SystemClock.sleep(500L)
        val before = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull()
        assertNotNull("ART must expose its GC counter", before)
        repeat(24) { decodeOnce() }
        val after = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull()
        assertNotNull("ART must expose its final GC counter", after)
        val delta = checkNotNull(after) - checkNotNull(before)
        Log.i("HostHardwareBitmapProbe", "scratchDecodes=24,resident=1,gcDelta=$delta")
        scratch.recycle()
        assertTrue("Reusable region scratch still churned ART GC: delta=$delta", delta <= 2L)
    }

    @Test
    fun reusableExactHardwareTilePoolAvoidsPerPageArtGc() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        val sourceWidth = 1_403
        val sourceHeight = 2_200
        val source = Bitmap.createBitmap(sourceWidth, sourceHeight, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.rgb(71, 131, 43))
        val encoded = ByteArrayOutputStream().use { output ->
            assertTrue(source.compress(Bitmap.CompressFormat.JPEG, 94, output))
            output.toByteArray()
        }
        source.recycle()

        fun decodeAndRetire() {
            val decoded = checkNotNull(
                HostExactHardwareTilePool.decodePage(
                    encoded,
                    sourceWidth,
                    sourceHeight,
                    2_048,
                ),
            )
            assertEquals(2, decoded.size)
            var top = 0
            decoded.forEach { bitmap ->
                val bottom = minOf(sourceHeight, top + 2_048)
                assertEquals(Bitmap.Config.ARGB_8888, bitmap.config)
                assertEquals(1, bitmap.width)
                assertEquals(1, bitmap.height)
                assertTrue(HostExactHardwareTilePool.isActiveToken(bitmap))
                assertTrue(
                    HostExactHardwareTilePool.nativeHandle(bitmap) != 0L,
                )
                assertTrue(
                    ReaderTile(top, bottom, sourceWidth, sourceHeight, bitmap)
                        .hasExactSourcePixelStorage(),
                )
                assertTrue(HostExactHardwareTilePool.retire(bitmap))
                top = bottom
            }
            assertEquals(sourceHeight, top)
        }

        decodeAndRetire()
        Runtime.getRuntime().gc()
        SystemClock.sleep(500L)
        val before = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull()
        assertNotNull("ART must expose its GC counter", before)
        repeat(24) { decodeAndRetire() }
        val after = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull()
        assertNotNull("ART must expose its final GC counter", after)
        val delta = checkNotNull(after) - checkNotNull(before)
        Log.i("HostHardwareBitmapProbe", "poolPages=24,tiles=48,gcDelta=$delta")
        assertTrue("Reusable exact hardware tile pool churned ART GC: delta=$delta", delta <= 2L)
    }

    @Test
    fun repeatedNativeCopyIntoOneHardwareBufferDoesNotChurnArtGc() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        val sourceWidth = 1_403
        val sourceHeight = 2_048
        val scratch = Bitmap.createBitmap(sourceWidth, sourceHeight, Bitmap.Config.ARGB_8888)
        scratch.eraseColor(Color.rgb(13, 173, 211))
        val nativeHandle = NtkRollingNativeBridge.nativeAllocateExactHardwareBuffer(
            1_536,
            sourceHeight,
        )
        assertTrue(nativeHandle != 0L)
        try {
            assertTrue(
                NtkRollingNativeBridge.nativeCopyExactBitmapToHardwareTile(
                    scratch,
                    nativeHandle,
                    sourceWidth,
                    0,
                    sourceHeight,
                    sourceWidth,
                ),
            )
            Runtime.getRuntime().gc()
            SystemClock.sleep(500L)
            val before = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull()
            assertNotNull("ART must expose its GC counter", before)
            repeat(48) {
                assertTrue(
                    NtkRollingNativeBridge.nativeCopyExactBitmapToHardwareTile(
                        scratch,
                        nativeHandle,
                        sourceWidth,
                        0,
                        sourceHeight,
                        sourceWidth,
                    ),
                )
            }
            val after = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull()
            assertNotNull("ART must expose its final GC counter", after)
            val delta = checkNotNull(after) - checkNotNull(before)
            Log.i("HostHardwareBitmapProbe", "nativeCopies=48,gcDelta=$delta")
            assertTrue("Repeated native hardware copy churned ART GC: delta=$delta", delta <= 2L)
        } finally {
            NtkRollingNativeBridge.nativeReleaseExactHardwareBuffer(nativeHandle)
            scratch.recycle()
        }
    }

    @Test
    fun nativeAllocatedStorageDoesNotEnterArtNativeAllocationAccounting() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        val nativeHandles = LongArray(4) {
            NtkRollingNativeBridge.nativeAllocateExactHardwareBuffer(1_536, 2_048)
        }
        assertTrue(nativeHandles.all { it != 0L })
        try {
            Runtime.getRuntime().gc()
            SystemClock.sleep(500L)
            val before = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull()
            assertNotNull("ART must expose its GC counter", before)
            repeat(48) { assertTrue(nativeHandles[it % nativeHandles.size] != 0L) }
            val after = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull()
            assertNotNull("ART must expose its final GC counter", after)
            val delta = checkNotNull(after) - checkNotNull(before)
            Log.i("HostHardwareBitmapProbe", "rawNativeHandles=4,gcDelta=$delta")
            assertTrue("Raw native storage churned ART GC: delta=$delta", delta <= 1L)
        } finally {
            nativeHandles.filter { it != 0L }.forEach(
                NtkRollingNativeBridge::nativeReleaseExactHardwareBuffer,
            )
        }
    }

    @Test
    @Suppress("DEPRECATION")
    fun combinedSingleTilePipelineDoesNotChurnArtGc() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        val sourceWidth = 1_403
        val sourceHeight = 512
        val source = Bitmap.createBitmap(sourceWidth, sourceHeight, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.rgb(217, 91, 19))
        val encoded = ByteArrayOutputStream().use { output ->
            assertTrue(source.compress(Bitmap.CompressFormat.JPEG, 94, output))
            output.toByteArray()
        }
        source.recycle()
        val scratch = Bitmap.createBitmap(2_048, sourceHeight, Bitmap.Config.ARGB_8888)
        val nativeHandle = NtkRollingNativeBridge.nativeAllocateExactHardwareBuffer(
            1_536,
            sourceHeight,
        )
        assertTrue(nativeHandle != 0L)

        fun decodeCopyWrap() {
            scratch.reconfigure(sourceWidth, sourceHeight, Bitmap.Config.ARGB_8888)
            try {
                val decoded = checkNotNull(
                    BitmapFactory.decodeByteArray(
                        encoded,
                        0,
                        encoded.size,
                        BitmapFactory.Options().apply {
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                            inSampleSize = 1
                            inScaled = false
                            inMutable = true
                            inBitmap = scratch
                        },
                    ),
                )
                assertTrue(decoded === scratch)
                assertTrue(
                    NtkRollingNativeBridge.nativeCopyExactBitmapToHardwareTile(
                        scratch,
                        nativeHandle,
                        sourceWidth,
                        0,
                        sourceHeight,
                        sourceWidth,
                    ),
                )
            } finally {
                // Scratch and raw native storage remain reusable across every iteration.
            }
        }

        try {
            decodeCopyWrap()
            Runtime.getRuntime().gc()
            SystemClock.sleep(500L)
            val before = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull()
            assertNotNull("ART must expose its GC counter", before)
            repeat(24) { decodeCopyWrap() }
            val after = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull()
            assertNotNull("ART must expose its final GC counter", after)
            val delta = checkNotNull(after) - checkNotNull(before)
            Log.i("HostHardwareBitmapProbe", "combinedPages=24,gcDelta=$delta")
            assertTrue("Combined exact tile pipeline churned ART GC: delta=$delta", delta <= 2L)
        } finally {
            NtkRollingNativeBridge.nativeReleaseExactHardwareBuffer(nativeHandle)
            scratch.recycle()
        }
    }
}
