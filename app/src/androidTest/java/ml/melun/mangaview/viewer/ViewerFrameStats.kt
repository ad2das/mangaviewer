package ml.melun.mangaview.viewer

import android.app.Instrumentation
import android.os.ParcelFileDescriptor
import java.io.File
import kotlin.math.ceil

internal data class FrameTimingSummary(
    val source: String,
    val sampleCount: Int,
    val refreshPeriodNanos: Long?,
    val p95Nanos: Long?,
    val maximumNanos: Long?,
    val missedFrameCount: Int,
    val freezeCount: Int,
    val interactionWindowCount: Int = 0,
    val coveredInteractionWindowCount: Int = 0,
    val responseSampleCount: Int = 0,
    val p95ResponseNanos: Long? = null,
    val maximumResponseNanos: Long? = null,
    val responseFreezeCount: Int = 0,
    val maximumTailNanos: Long? = null,
    val tailFreezeCount: Int = 0,
) {
    val p95Millis: Double? get() = p95Nanos?.div(NANOS_PER_MILLISECOND)
    val maximumMillis: Double? get() = maximumNanos?.div(NANOS_PER_MILLISECOND)
    val missedFrameRatio: Double
        get() = if (sampleCount + missedFrameCount == 0) {
            0.0
        } else {
            missedFrameCount.toDouble() / (sampleCount + missedFrameCount)
        }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000.0
    }
}

internal data class FrameStatsSnapshot(
    val gfx: FrameTimingSummary,
    val render: FrameTimingSummary,
    val motion: FrameTimingSummary,
    val surface: FrameTimingSummary?,
    val surfaceLayer: String?,
    val gfxRawFile: File,
    val surfaceRawFile: File?,
)

internal class ViewerFrameStats(
    private val instrumentation: Instrumentation,
    private val packageName: String,
    private val artifactDirectory: File,
) {
    fun capture(
        startedAtNanos: Long,
        interactionWindows: List<LongRange>,
        presentationNanos: LongArray,
        renderSamples: LongArray,
        motionFrameSamples: LongArray,
        refreshPeriodNanos: Long,
        windowFrameSamples: LongArray,
        motionApplicationTimestamps: LongArray,
        injectedGestureStarts: LongArray,
    ): FrameStatsSnapshot {
        val gfxRaw = shell("dumpsys gfxinfo $packageName framestats")
        val gfxFile = File(artifactDirectory, "gfxinfo-framestats.txt").apply {
            writeText(gfxRaw)
        }
        val surfaceRaw = buildPresentationRaw(presentationNanos, refreshPeriodNanos)
        File(artifactDirectory, "observed-input-windows.txt").writeText(
            interactionWindows.joinToString("\n") { "${it.first}\t${it.last}" },
        )
        val surfaceFile = File(artifactDirectory, "native-presentation-timestamps.txt").apply {
            writeText(surfaceRaw)
        }
        val windowFrameFile = File(artifactDirectory, "window-frame-metrics.txt").apply {
            writeText(windowFrameSamples.asSequence().chunked(2)
                .filter { it.size == 2 }
                .joinToString("\n") { "${it[0]}\t${it[1]}" })
        }
        File(artifactDirectory, "motion-frame-timestamps.txt").writeText(
            motionFrameSamples.asSequence().chunked(2)
                .filter { it.size == 2 }
                .joinToString("\n") { "${it[0]}\t${it[1]}" },
        )
        require(motionApplicationTimestamps.size * 2 == motionFrameSamples.size)
        File(artifactDirectory, "motion-input-application-timestamps.tsv").writeText(
            "motionSequence\tframeTimeNanos\tappliedAtNanos\n" +
                motionApplicationTimestamps.indices.joinToString("\n") { index ->
                    "${motionFrameSamples[index * 2]}\t${motionFrameSamples[index * 2 + 1]}\t" +
                        motionApplicationTimestamps[index]
                },
        )
        File(artifactDirectory, "injected-input-starts.txt").writeText(
            injectedGestureStarts.joinToString("\n"),
        )
        return FrameStatsSnapshot(
            gfx = ViewerFrameStatsParser.parseWindowFrames(
                windowFrameSamples, startedAtNanos, interactionWindows, refreshPeriodNanos,
            ),
            render = ViewerFrameStatsParser.parseRender(
                renderSamples,
                startedAtNanos,
                interactionWindows,
                refreshPeriodNanos,
            ),
            motion = ViewerFrameStatsParser.parseMotion(
                motionFrameSamples,
                startedAtNanos,
                interactionWindows,
                refreshPeriodNanos,
                motionApplicationTimestamps,
                injectedGestureStarts,
            ),
            surface = ViewerFrameStatsParser.parseSurface(
                surfaceRaw,
                startedAtNanos,
                interactionWindows,
            ),
            surfaceLayer = NATIVE_PRESENTATION_SOURCE,
            gfxRawFile = gfxFile,
            surfaceRawFile = surfaceFile,
        )
    }

    private fun buildPresentationRaw(timestamps: LongArray, refreshPeriodNanos: Long): String =
        buildString {
            appendLine(refreshPeriodNanos)
            timestamps.forEach { timestamp -> append("0\t").append(timestamp).appendLine("\t0") }
        }

    private fun shell(command: String): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            input.bufferedReader().readText()
        }
    }

    private companion object {
        const val NATIVE_PRESENTATION_SOURCE = "Viewer Surface buffer posts"
    }
}

internal object ViewerFrameStatsParser {
    fun parseWindowFrames(
        packedSamples: LongArray,
        startedAtNanos: Long,
        interactionWindows: List<LongRange>,
        refreshPeriodNanos: Long,
    ): FrameTimingSummary {
        val windows = interactionWindows.validAfter(startedAtNanos)
        val samples = buildList {
            var index = 0
            while (index + 1 < packedSamples.size) {
                val intendedVsync = packedSamples[index]
                val totalDuration = packedSamples[index + 1]
                if (intendedVsync >= startedAtNanos && totalDuration >= 0L) {
                    add(WindowFrameSample(intendedVsync, totalDuration))
                }
                index += 2
            }
        }.distinctBy(WindowFrameSample::intendedVsync).sortedBy(WindowFrameSample::intendedVsync)
        val timestamps = samples.map(WindowFrameSample::intendedVsync)
        val inWindows = samples.filter { sample -> windows.any { sample.intendedVsync in it } }
        val response = presentationSamples(timestamps, windows)
        val cadence = response.cadence
        val cadenceMisses = cadence.sumOf {
            missedFrames(it, refreshPeriodNanos, MissedFrameCounting.CADENCE)
        }
        val summary = summarize(
            source = WINDOW_FRAME_SOURCE,
            durations = inWindows.map(WindowFrameSample::totalDuration),
            refreshPeriodNanos = refreshPeriodNanos,
            windows = windows,
            sampleTimestamps = timestamps,
            responses = response.firstResponses,
            missedFrameCounting = MissedFrameCounting.DEADLINE,
        )
        return summary.copy(
            // Emulator FrameMetrics TOTAL_DURATION includes queued RenderThread/GPU pipeline time
            // and normally exceeds one refresh period even when every intended VSYNC is present.
            // Lost display slots therefore come from the completed frames' intended-VSYNC cadence;
            // TOTAL_DURATION remains authoritative for p95 and >=100 ms freezes.
            missedFrameCount = cadenceMisses,
            freezeCount = maxOf(summary.freezeCount, cadence.count { it >= FREEZE_NANOS }),
        )
    }

    fun parseMotion(
        packedSamples: LongArray,
        startedAtNanos: Long,
        interactionWindows: List<LongRange>,
        refreshPeriodNanos: Long,
        applicationTimestamps: LongArray,
        injectedGestureStarts: LongArray,
    ): FrameTimingSummary {
        require(applicationTimestamps.size * 2 == packedSamples.size) {
            "Every motion frame needs its actual application timestamp"
        }
        require(injectedGestureStarts.size == interactionWindows.size) {
            "Every observed gesture needs its injected start timestamp"
        }
        val windows = interactionWindows.validAfter(startedAtNanos)
        // Sequence identifies a drained motion batch, not an entire gesture. A new batch can
        // arrive on every VSYNC; gesture windows define the independent cadence intervals.
        val frames = motionSamples(packedSamples, startedAtNanos)
            .map(MotionFrameSample::timestamp).distinct().sorted()
        val applied = applicationTimestamps.filter { it >= startedAtNanos }.distinct().sorted()
        val responses = interactionWindows.mapIndexedNotNull { index, window ->
            val injectedAt = injectedGestureStarts[index]
            require(injectedAt > 0L && injectedAt <= window.first)
            applied.firstOrNull { it in window }?.minus(injectedAt)
        }
        val cadence = presentationSamples(frames, windows).cadence
        return summarize(
            source = MOTION_FRAME_SOURCE,
            durations = cadence,
            refreshPeriodNanos = refreshPeriodNanos,
            windows = windows,
            sampleTimestamps = applied,
            responses = responses,
            missedFrameCounting = MissedFrameCounting.CADENCE,
        )
    }

    private fun motionSamples(
        packed: LongArray,
        startedAtNanos: Long,
    ): List<MotionFrameSample> = buildList {
        var index = 0
        while (index + 1 < packed.size) {
            val sequence = packed[index]
            val timestamp = packed[index + 1]
            if (sequence > 0L && timestamp >= startedAtNanos) {
                add(MotionFrameSample(sequence, timestamp))
            }
            index += 2
        }
    }

    fun parseRender(
        packedSamples: LongArray,
        startedAtNanos: Long,
        interactionWindows: List<LongRange>,
        refreshPeriodNanos: Long,
    ): FrameTimingSummary {
        val windows = interactionWindows.validAfter(startedAtNanos)
        val timestamps = mutableListOf<Long>()
        val durations = mutableListOf<Long>()
        var index = 0
        while (index + 1 < packedSamples.size) {
            val timestamp = packedSamples[index]
            val duration = packedSamples[index + 1]
            if (timestamp >= startedAtNanos && duration >= 0L &&
                windows.any { timestamp in it }
            ) {
                timestamps += timestamp
                durations += duration
            }
            index += 2
        }
        return summarize(
            source = NATIVE_RENDER_SOURCE,
            durations = durations,
            refreshPeriodNanos = refreshPeriodNanos,
            windows = windows,
            sampleTimestamps = timestamps,
        )
    }

    fun parseGfx(
        raw: String,
        startedAtNanos: Long,
        interactionWindows: List<LongRange>,
    ): FrameTimingSummary {
        var header: GfxHeader? = null
        val windows = interactionWindows.validAfter(startedAtNanos)
        val durations = mutableListOf<Long>()
        val intendedVsyncs = mutableListOf<Long>()
        val uniqueFrames = mutableSetOf<Pair<Long, Long>>()
        raw.lineSequence().forEach { sourceLine ->
            val line = sourceLine.trim()
            if (line.startsWith("Flags,")) {
                header = GfxHeader.from(line.split(','))
                return@forEach
            }
            val activeHeader = header ?: return@forEach
            if (line.isEmpty() || line.startsWith("---")) return@forEach
            val values = line.split(',')
            if (values.size <= activeHeader.maximumIndex) return@forEach
            val flags = values[activeHeader.flags].toLongOrNull() ?: return@forEach
            val intended = values[activeHeader.intendedVsync].toLongOrNull() ?: return@forEach
            val completed = values[activeHeader.frameCompleted].toLongOrNull() ?: return@forEach
            if (flags != VALID_FRAME_FLAGS || !isCompletedFrame(intended, completed)) return@forEach
            if (intended < startedAtNanos || windows.none { intended in it }) return@forEach
            if (!uniqueFrames.add(intended to completed)) return@forEach
            intendedVsyncs += intended
            durations += completed - intended
        }
        return summarize(
            source = APP_FRAME_SOURCE,
            durations = durations,
            refreshPeriodNanos = DEFAULT_REFRESH_PERIOD_NANOS,
            windows = windows,
            sampleTimestamps = intendedVsyncs,
        )
    }

    fun parseSurface(
        raw: String,
        startedAtNanos: Long,
        interactionWindows: List<LongRange>,
    ): FrameTimingSummary {
        val refreshPeriod = raw.lineSequence()
            .map(String::trim)
            .firstOrNull(String::isNotEmpty)
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
        val windows = interactionWindows.validAfter(startedAtNanos)
        val presented = surfacePresentations(raw).filter { it >= startedAtNanos }
        val firstPresentation = presented.firstOrNull()
        val presentableWindows = if (firstPresentation == null) {
            emptyList()
        } else {
            windows.filter { it.last >= firstPresentation }
        }
        val samples = presentationSamples(presented, presentableWindows)
        return summarize(
            source = SURFACE_FRAME_SOURCE,
            durations = samples.cadence,
            refreshPeriodNanos = refreshPeriod,
            windows = presentableWindows,
            sampleTimestamps = presented,
            responses = samples.firstResponses,
            missedFrameCounting = MissedFrameCounting.CADENCE,
        )
    }

    fun surfacePresentations(raw: String): List<Long> = raw.lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .drop(1)
        .mapNotNull { line ->
            val columns = line.split(WHITESPACE)
            columns.getOrNull(ACTUAL_PRESENT_COLUMN)?.toLongOrNull()
        }
        .filter(::isValidTimestamp)
        .distinct()
        .sorted()
        .toList()

    private fun presentationSamples(
        presented: List<Long>,
        windows: List<LongRange>,
    ): SurfaceSamples {
        val cadence = mutableListOf<Long>()
        val responses = mutableListOf<Long>()
        windows.mergeOverlaps().forEach { window ->
            val inWindow = presented.filter { it in window }
            if (inWindow.isNotEmpty()) {
                responses += inWindow.first() - window.first
                inWindow.zipWithNext { previous, current -> cadence += current - previous }
            }
        }
        return SurfaceSamples(cadence.filter { it > 0L }, responses.filter { it >= 0L })
    }

    private fun summarize(
        source: String,
        durations: List<Long>,
        refreshPeriodNanos: Long?,
        windows: List<LongRange>,
        sampleTimestamps: List<Long>,
        responses: List<Long> = emptyList(),
        missedFrameCounting: MissedFrameCounting = MissedFrameCounting.DEADLINE,
    ): FrameTimingSummary {
        val sorted = durations.sorted()
        val sortedResponses = responses.sorted()
        val budget = refreshPeriodNanos ?: DEFAULT_REFRESH_PERIOD_NANOS
        val tails = if (missedFrameCounting == MissedFrameCounting.CADENCE) {
            windows.mergeOverlaps().map { window ->
                window.last - (sampleTimestamps.lastOrNull { it in window } ?: window.first)
            }
        } else emptyList()
        return FrameTimingSummary(
            source = source,
            sampleCount = sorted.size,
            refreshPeriodNanos = refreshPeriodNanos,
            p95Nanos = sorted.percentile(95),
            maximumNanos = sorted.lastOrNull(),
            missedFrameCount = (sorted + tails).fold(0L) { total, duration ->
                (total + missedFrames(duration, budget, missedFrameCounting))
                    .coerceAtMost(Int.MAX_VALUE.toLong())
            }.toInt(),
            freezeCount = sorted.count { it >= FREEZE_NANOS },
            interactionWindowCount = windows.size,
            coveredInteractionWindowCount = windows.count { window -> sampleTimestamps.any { it in window } },
            responseSampleCount = sortedResponses.size,
            p95ResponseNanos = sortedResponses.percentile(95),
            maximumResponseNanos = sortedResponses.lastOrNull(),
            responseFreezeCount = sortedResponses.count { it >= FREEZE_NANOS },
            maximumTailNanos = tails.maxOrNull(),
            tailFreezeCount = tails.count { it >= FREEZE_NANOS },
        )
    }

    private fun isCompletedFrame(intended: Long, completed: Long): Boolean =
        isValidTimestamp(intended) && isValidTimestamp(completed) && completed > intended

    private fun missedFrames(
        duration: Long,
        frameBudget: Long,
        counting: MissedFrameCounting,
    ): Int {
        if (duration <= 0L || frameBudget <= 0L) return 0
        val occupiedFramePeriods = when (counting) {
            MissedFrameCounting.DEADLINE -> 1L + (duration - 1L) / frameBudget
            MissedFrameCounting.CADENCE -> {
                val whole = duration / frameBudget
                val remainder = duration % frameBudget
                whole + if (remainder >= (frameBudget + 1L) / 2L) 1L else 0L
            }
        }
        return (occupiedFramePeriods - 1L)
            .coerceAtLeast(0L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    private fun isValidTimestamp(value: Long): Boolean = value > 0L && value != Long.MAX_VALUE

    private fun List<LongRange>.validAfter(startedAtNanos: Long): List<LongRange> = mapNotNull { window ->
        val first = maxOf(window.first, startedAtNanos)
        if (first <= window.last) first..window.last else null
    }

    private fun List<LongRange>.mergeOverlaps(): List<LongRange> {
        if (isEmpty()) return emptyList()
        val merged = mutableListOf<LongRange>()
        sortedBy(LongRange::first).forEach { window ->
            val previous = merged.lastOrNull()
            if (previous == null || window.first > previous.last + 1L) {
                merged += window
            } else {
                merged[merged.lastIndex] = previous.first..maxOf(previous.last, window.last)
            }
        }
        return merged
    }

    private fun List<Long>.percentile(percentile: Int): Long? {
        if (isEmpty()) return null
        val index = ceil(size * percentile / 100.0).toInt().coerceIn(1, size) - 1
        return this[index]
    }

    private data class GfxHeader(
        val flags: Int,
        val intendedVsync: Int,
        val frameCompleted: Int,
    ) {
        val maximumIndex: Int = maxOf(flags, intendedVsync, frameCompleted)

        companion object {
            fun from(columns: List<String>): GfxHeader? {
                val flags = columns.indexOf("Flags")
                val intendedVsync = columns.indexOf("IntendedVsync")
                val frameCompleted = columns.indexOf("FrameCompleted")
                return if (flags >= 0 && intendedVsync >= 0 && frameCompleted >= 0) {
                    GfxHeader(flags, intendedVsync, frameCompleted)
                } else {
                    null
                }
            }
        }
    }

    private data class SurfaceSamples(
        val cadence: List<Long>,
        val firstResponses: List<Long>,
    )

    private data class MotionFrameSample(val sequence: Long, val timestamp: Long)
    private data class WindowFrameSample(val intendedVsync: Long, val totalDuration: Long)

    private enum class MissedFrameCounting { DEADLINE, CADENCE }

    private const val APP_FRAME_SOURCE = "app-gfxinfo"
    private const val NATIVE_RENDER_SOURCE = "hwui-draw-cpu"
    private const val MOTION_FRAME_SOURCE = "choreographer-motion"
    private const val SURFACE_FRAME_SOURCE = "viewer-surface-presentation"
    private const val WINDOW_FRAME_SOURCE = "hwui-window-frame-metrics"
    private const val ACTUAL_PRESENT_COLUMN = 1
    private const val VALID_FRAME_FLAGS = 0L
    private const val DEFAULT_REFRESH_PERIOD_NANOS = 16_666_667L
    private const val FREEZE_NANOS = 100_000_000L
    private val WHITESPACE = Regex("\\s+")
}
