package ml.melun.mangaview.activity

import java.io.File
import org.json.JSONObject

/** Drains the production recorder without changing scheduling or its bounded rings. */
internal class EngineCapturedMotion {
    private val batches = mutableListOf<ViewerMotionBatch>()
    private val windows = linkedMapOf<Long, LongRange>()
    private var cursor = 0L
    private var initialized = false

    fun capture(viewer: EngineViewerScreen) {
        val batch = viewer.motionFramesSince(cursor)
        check(!batch.dropped) { "Motion observation history was overwritten" }
        check(batch.packed.size == batch.applicationTimestamps.size * 2)
        check(batch.nextSequence - cursor == batch.applicationTimestamps.size.toLong()) {
            "Motion observation ordinal has a gap"
        }
        check(batch.nextSequence <= 100_000L) { "Motion capture exceeded its evidence bound" }
        if (batch.applicationTimestamps.isNotEmpty()) batches += batch
        cursor = batch.nextSequence
        val current = viewer.gestureWindowsSnapshot()
        if (!initialized || windows.isEmpty()) {
            check(current.size < 64) { "Initial gesture window history may be truncated" }
        } else {
            check(current.contains(windows.values.last())) { "Gesture window history was overwritten" }
        }
        for (window in current) {
            val previous = windows.putIfAbsent(window.first, window)
            check(previous == null || previous == window) { "Closed gesture window changed" }
        }
        check(windows.size <= 10_000) { "Gesture capture exceeded its evidence bound" }
        initialized = true
    }

    fun close(viewer: EngineViewerScreen, output: File) {
        capture(viewer)
        val closedAt = System.nanoTime()
        File(output, "motion.jsonl").bufferedWriter().use { writer ->
            var ordinal = 0L
            for (batch in batches) for (index in batch.applicationTimestamps.indices) {
                writer.append(JSONObject().apply {
                    put("ordinal", ++ordinal)
                    put("sequence", batch.packed[index * 2])
                    put("frameTimeNanos", batch.packed[index * 2 + 1])
                    put("appliedAtNanos", batch.applicationTimestamps[index])
                }.toString()).append('\n')
            }
            check(ordinal == cursor)
        }
        File(output, "motion-windows.jsonl").bufferedWriter().use { writer ->
            windows.values.forEachIndexed { index, window ->
                writer.append(JSONObject().apply {
                    put("ordinal", index + 1); put("startNanos", window.first); put("endNanos", window.last)
                }.toString()).append('\n')
            }
        }
        File(output, "motion-close.json").writeText(JSONObject().apply {
            put("observationCount", cursor); put("windowCount", windows.size)
            put("closedAtNanos", closedAt); put("refreshPeriodNanos", viewer.presentationRefreshPeriodNanos())
            put("historyOverwritten", false); put("physicalPresentationVerified", false)
            put("clock", "System.nanoTime / Choreographer.frameTimeNanos")
        }.toString(2))
    }
}
