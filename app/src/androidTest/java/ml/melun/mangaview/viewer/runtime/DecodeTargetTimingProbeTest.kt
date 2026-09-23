package ml.melun.mangaview.viewer.runtime

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Diagnostic only: which decode target width is cheapest for the wfwf-style full pages the gate
 * scores. The viewer decodes a full page at min(displayWidth, sourceWidth); the platform's JPEG
 * codec picks its own sample size for that target and resamples when the sampled size differs.
 * Timing the same real page at the current target, at a clean half scale, at a quarter scale and
 * at native size shows whether that resample or the IDCT itself owns the decode stage.
 *
 * Writes decode-target-probe.txt to the app's external files dir.
 */
@RunWith(AndroidJUnit4::class)
class DecodeTargetTimingProbeTest {
    @Test fun targetsOfRealPages() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.applicationInfo.dataDir, "app_engine_pages_v1/pages")
        val report = StringBuilder()
        val candidates = dir.listFiles().orEmpty().filter { it.isFile && it.length() in 60_000..200_000 }
            .sortedByDescending { it.length() }
        var probed = 0
        for (page in candidates) {
            if (probed >= 10) break
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(page.absolutePath, bounds)
            val width = bounds.outWidth
            val height = bounds.outHeight
            // The gate's wfwf pages are 900-wide full pages; anything else here is a different source.
            if (width != 900 || height !in 600..1100) continue
            probed += 1
            val line = StringBuilder()
            line.append(page.name.takeLast(68)).append(" ${width}x$height bytes=${page.length()}")
            for (target in intArrayOf(320, 450, 225, 900)) {
                repeat(2) { decode(page, width, height, target) }
                val samples = LongArray(9) { decode(page, width, height, target) }
                val sorted = samples.sorted()
                line.append(" t$target:min=${sorted.first()}med=${sorted[4]}max=${sorted.last()}")
            }
            report.appendLine(line)
            Log.i("DecodeTargetProbe", line.toString())
        }
        val text = report.toString().ifEmpty { "no 900-wide cached pages found under ${dir.absolutePath}\n" }
        File(context.getExternalFilesDir(null), "decode-target-probe.txt").writeText(text)
        Log.i("DecodeTargetProbe", "written; pages=$probed")
    }

    private fun decode(page: File, width: Int, height: Int, target: Int): Long {
        val startedAt = System.nanoTime()
        val handle = NativeCpuDecodeBridge.nativeDecode(
            page.absolutePath, width, height, 0, height, minOf(target, width), 0, width)
        val elapsed = System.nanoTime() - startedAt
        if (handle != 0L) NativeCpuDecodeBridge.nativeRelease(handle)
        return elapsed / 1_000_000L
    }
}
