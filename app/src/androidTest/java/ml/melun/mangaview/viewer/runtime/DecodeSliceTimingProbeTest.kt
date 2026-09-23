package ml.melun.mangaview.viewer.runtime

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Diagnostic only: how much of a full-page decode's wall time parallel slices can remove.
 * Decodes one full region, each half alone, and both halves on two threads, against real
 * cached pages. Writes decode-slice-probe.txt to the app's external files dir.
 */
@RunWith(AndroidJUnit4::class)
class DecodeSliceTimingProbeTest {
    @Test fun slicesOfRealPages() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.applicationInfo.dataDir, "app_engine_pages_v1/pages")
        val pages = dir.walkTopDown().filter { it.isFile && it.length() > 50_000 }
            .sortedByDescending { it.length() }.take(6).toList()
        val report = StringBuilder()
        for (page in pages) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(page.absolutePath, bounds)
            val width = bounds.outWidth
            val height = bounds.outHeight
            if (width <= 0 || height <= 0) continue
            val middle = height / 2
            repeat(2) { decode(page, width, height, 0, height) }
            val full = bestOf { decode(page, width, height, 0, height) }
            val top = bestOf { decode(page, width, height, 0, middle) }
            val bottom = bestOf { decode(page, width, height, middle, height) }
            val parallel = bestOf { parallelHalves(page, width, height, middle) }
            val line = "${page.name} ${width}x${height} bytes=${page.length()} " +
                "full=$full top=$top bottom=$bottom parallel=$parallel"
            report.appendLine(line)
            Log.i("DecodeSliceProbe", line)
        }
        val text = report.toString().ifEmpty { "no cached pages found under ${dir.absolutePath}\n" }
        File(context.getExternalFilesDir(null), "decode-slice-probe.txt").writeText(text)
        Log.i("DecodeSliceProbe", "written; pages=${pages.size}")
    }

    private inline fun bestOf(crossinline decode: () -> Long?): Long? {
        var best: Long? = null
        repeat(5) {
            val at = decode() ?: return@repeat
            if (best == null || at < best!!) best = at
        }
        return best
    }

    private fun decode(page: File, width: Int, height: Int, top: Int, bottom: Int): Long? {
        val startedAt = System.nanoTime()
        val handle = NativeCpuDecodeBridge.nativeDecode(page.absolutePath, width, height, top, bottom, width, 0, width)
        val elapsed = System.nanoTime() - startedAt
        if (handle == 0L) return null
        NativeCpuDecodeBridge.nativeRelease(handle)
        return elapsed / 1_000_000L
    }

    private fun parallelHalves(page: File, width: Int, height: Int, middle: Int): Long {
        val latch = CountDownLatch(2)
        val handles = LongArray(2)
        val startedAt = System.nanoTime()
        val upper = Thread {
            handles[0] = NativeCpuDecodeBridge.nativeDecode(page.absolutePath, width, height, 0, middle, width, 0, width)
            latch.countDown()
        }
        val lower = Thread {
            handles[1] = NativeCpuDecodeBridge.nativeDecode(page.absolutePath, width, height, middle, height, width, 0, width)
            latch.countDown()
        }
        upper.start(); lower.start()
        latch.await()
        val elapsed = System.nanoTime() - startedAt
        handles.forEach { if (it != 0L) NativeCpuDecodeBridge.nativeRelease(it) }
        return elapsed / 1_000_000L
    }
}
