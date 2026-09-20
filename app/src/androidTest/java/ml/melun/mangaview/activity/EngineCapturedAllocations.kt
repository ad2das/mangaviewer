package ml.melun.mangaview.activity

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Light, in-process memory sampling for one engine capture run.
 *
 * `Debug.getGlobalAllocSize/Count` are legacy Java-heap allocation counters and stay 0 on this
 * ART build (verified live), so allocation churn is quantified host-side from GC logcat totals.
 * This sampler records the live footprint columns that do move (Java heap used, native heap
 * allocated, own-process PSS) together with the latest frame token; a checkpoint window's
 * per-frame value divides its footprint delta by its frame-token delta.
 */
internal class EngineCapturedAllocations(private val context: Context, private val directory: File) {
    private data class Sample(
        val label: String,
        val atNanos: Long,
        val frameToken: Long?,
        val globalAllocBytes: Long,
        val globalAllocCount: Long,
        val javaHeapUsedBytes: Long,
        val nativeHeapAllocatedBytes: Long,
        val pssKib: Long?,
    )

    private val samples = mutableListOf<Sample>()
    private var checkpointOrdinal = 0

    fun sample(label: String, frameToken: Long?) {
        val runtime = Runtime.getRuntime()
        samples += Sample(
            label = label,
            atNanos = System.nanoTime(),
            frameToken = frameToken,
            globalAllocBytes = Debug.getGlobalAllocSize().toLong(),
            globalAllocCount = Debug.getGlobalAllocCount().toLong(),
            javaHeapUsedBytes = runtime.totalMemory() - runtime.freeMemory(),
            nativeHeapAllocatedBytes = Debug.getNativeHeapAllocatedSize(),
            pssKib = ownPssKib(),
        )
    }

    /** One sample per traversal checkpoint; the ordinal keeps repeated labels distinguishable. */
    fun sampleCheckpoint(frameToken: Long?) {
        checkpointOrdinal += 1
        sample("checkpoint-$checkpointOrdinal", frameToken)
    }

    /**
     * Writes `allocation.json` once the renderer close proof (submitted frame count) is available.
     * Never throws: measurement must not mask the capture's own failure.
     */
    fun finish(framesSubmitted: Long, output: File) {
        runCatching {
            val anchor = samples.firstOrNull { it.label == "before-viewer" } ?: samples.firstOrNull()
            val last = samples.lastOrNull()
            val frames = framesSubmitted.coerceAtLeast(1L)
            val windowSamples = mutableListOf<JSONObject>()
            var previous: Sample? = null
            samples.forEach { current ->
                val before = previous
                if (before != null) {
                    val windowFrames = frameDelta(current, before)
                    windowSamples += windowJson(before, current, windowFrames)
                }
                previous = current
            }
            output.resolve("allocation.json").writeText(JSONObject()
                .put("schema", 2)
                .put("globalAllocCounters", "android.os.Debug.getGlobalAllocSize/Count returned 0 for " +
                    "every sample on this ART build (legacy counters disabled); kept as measured evidence")
                .put("javaHeapMetric", "Runtime.totalMemory - freeMemory: current Java heap used in bytes")
                .put("nativeHeapMetric", "Debug.getNativeHeapAllocatedSize: current native heap allocated bytes")
                .put("pssMetric", "ActivityManager.getProcessMemoryInfo(own pid).totalPss KiB; includes native")
                .put("churnMetric", "Host-side GC logcat 'freed' totals over the run window; lower bound")
                .put("framesSubmitted", framesSubmitted)
                .put("checkpoints", checkpointOrdinal)
                .put("sessionJavaHeapDeltaBytes", delta(anchor, last) { it.javaHeapUsedBytes })
                .put("sessionNativeHeapDeltaBytes", delta(anchor, last) { it.nativeHeapAllocatedBytes })
                .put("sessionPssDeltaKib", delta(anchor, last) { it.pssKib })
                .put("perFrameJavaHeapBytes", rate(anchor, last, frames) { it.javaHeapUsedBytes })
                .put("perFrameNativeHeapBytes", rate(anchor, last, frames) { it.nativeHeapAllocatedBytes })
                .put("perFramePssKib", rate(anchor, last, frames) { it.pssKib })
                .put("pssKibBeforeViewer", anchor?.pssKib ?: JSONObject.NULL)
                .put("pssKibAfterClose", last?.pssKib ?: JSONObject.NULL)
                .put("pssKibPeak", samples.mapNotNull { it.pssKib }.maxOrNull() ?: JSONObject.NULL)
                .put("pssKibFirst", samples.firstOrNull()?.pssKib ?: JSONObject.NULL)
                .put("samples", JSONArray(samples.map { sampleJson(it) }))
                .put("windows", JSONArray(windowSamples))
                .toString(2))
        }.onFailure { failure ->
            runCatching {
                output.resolve("allocation-failure.txt").writeText(failure.stackTraceToString())
            }
        }
    }

    private fun frameDelta(current: Sample, previous: Sample): Long? {
        val now = current.frameToken ?: return null
        val before = previous.frameToken ?: return null
        return (now - before).takeIf { it > 0L }
    }

    private fun windowJson(before: Sample, current: Sample, frames: Long?): JSONObject {
        val javaDelta = current.javaHeapUsedBytes - before.javaHeapUsedBytes
        val nativeDelta = current.nativeHeapAllocatedBytes - before.nativeHeapAllocatedBytes
        val pssDelta = pssDelta(current, before)
        return JSONObject()
            .put("label", "${before.label}->${current.label}")
            .put("atNanos", current.atNanos)
            .put("windowFrames", frames ?: JSONObject.NULL)
            .put("javaHeapDeltaBytes", javaDelta)
            .put("nativeHeapDeltaBytes", nativeDelta)
            .put("pssDeltaKib", pssDelta ?: JSONObject.NULL)
            .put("perFrameJavaHeapBytes", frames?.takeIf { it > 0L }?.let { javaDelta.toDouble() / it } ?: JSONObject.NULL)
            .put("perFrameNativeHeapBytes", frames?.takeIf { it > 0L }?.let { nativeDelta.toDouble() / it } ?: JSONObject.NULL)
            .put("perFramePssKib", frames?.takeIf { it > 0L }?.let { pssDelta?.toDouble()?.div(it) } ?: JSONObject.NULL)
    }

    private fun pssDelta(current: Sample, previous: Sample): Long? {
        val now = current.pssKib ?: return null
        val before = previous.pssKib ?: return null
        return now - before
    }

    private fun delta(from: Sample?, to: Sample?, value: (Sample) -> Long?): Long? {
        val start = from?.let(value) ?: return null
        val end = to?.let(value) ?: return null
        return end - start
    }

    private fun rate(from: Sample?, to: Sample?, frames: Long, value: (Sample) -> Long?): Double? {
        val difference = delta(from, to, value) ?: return null
        return difference.toDouble() / frames
    }

    private fun ownPssKib(): Long? = runCatching {
        val manager = context.getSystemService(ActivityManager::class.java)
        manager.getProcessMemoryInfo(intArrayOf(android.os.Process.myPid()))
            .singleOrNull()?.totalPss?.toLong()
    }.getOrNull()

    private fun sampleJson(sample: Sample) = JSONObject()
        .put("label", sample.label)
        .put("atNanos", sample.atNanos)
        .put("frameToken", sample.frameToken ?: JSONObject.NULL)
        .put("globalAllocBytes", sample.globalAllocBytes)
        .put("globalAllocCount", sample.globalAllocCount)
        .put("javaHeapUsedBytes", sample.javaHeapUsedBytes)
        .put("nativeHeapAllocatedBytes", sample.nativeHeapAllocatedBytes)
        .put("pssKib", sample.pssKib ?: JSONObject.NULL)
}
