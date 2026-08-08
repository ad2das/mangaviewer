package ml.melun.mangaview.macrobenchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.Metric
import androidx.benchmark.macro.TraceMetric
import androidx.benchmark.traceprocessor.ExperimentalTraceProcessorApi
import androidx.benchmark.traceprocessor.TraceProcessor

/**
 * Process CPU and longest uninterrupted main-thread run during the first real viewer scroll
 * session. The bounds come from target-process tracing, not host sleeps or test-only app state.
 */
@OptIn(ExperimentalMetricApi::class, ExperimentalTraceProcessorApi::class)
class ViewerScrollTraceMetric : TraceMetric() {
    override fun getMeasurements(
        captureInfo: Metric.CaptureInfo,
        traceSession: TraceProcessor.Session
    ): List<Metric.Measurement> {
        val packageNameSql = captureInfo.targetPackageName.replace("'", "''")
        val measurements = ArrayList<Metric.Measurement>(48)
        val row = traceSession.query(
            """
            WITH bounds AS (
                SELECT ts AS start_ts, ts + dur AS end_ts, dur
                FROM slice
                WHERE name = 'ViewerScrollSession' AND dur > 0
                ORDER BY ts
                LIMIT 1
            ),
            target_threads AS (
                SELECT thread.utid AS utid,
                       CASE WHEN thread.tid = process.pid THEN 1 ELSE 0 END AS is_main
                FROM thread
                JOIN process USING (upid)
                WHERE process.name = '$packageNameSql'
            ),
            clipped AS (
                SELECT target_threads.is_main AS is_main,
                       MAX(
                           0,
                           MIN(sched_slice.ts + sched_slice.dur, bounds.end_ts) -
                               MAX(sched_slice.ts, bounds.start_ts)
                       ) AS clipped_dur
                FROM sched_slice
                JOIN target_threads USING (utid)
                CROSS JOIN bounds
                WHERE sched_slice.ts < bounds.end_ts
                  AND sched_slice.ts + sched_slice.dur > bounds.start_ts
            )
            SELECT bounds.dur AS interval_ns,
                   COALESCE(SUM(clipped.clipped_dur), 0) AS cpu_ns,
                   COALESCE(MAX(CASE WHEN clipped.is_main = 1
                                     THEN clipped.clipped_dur ELSE 0 END), 0) AS main_run_ns
            FROM bounds
            LEFT JOIN clipped ON 1 = 1
            GROUP BY bounds.dur
            """.trimIndent()
        ).firstOrNull()

        if (row != null) {
            val intervalNs = row.long("interval_ns")
            val cpuNs = row.long("cpu_ns")
            val mainRunNs = row.long("main_run_ns")
            if (intervalNs > 0L && cpuNs >= 0L && mainRunNs >= 0L) {
                measurements += Metric.Measurement(
                    "viewerScrollDurationMs",
                    intervalNs / 1_000_000.0
                )
                measurements += Metric.Measurement(
                    "viewerScrollCpuTimeMs",
                    cpuNs / 1_000_000.0
                )
                measurements += Metric.Measurement(
                    "viewerScrollCpuPercent",
                    cpuNs.toDouble() * 100.0 / intervalNs.toDouble()
                )
                measurements += Metric.Measurement(
                    "viewerScrollMainThreadRunningMaxMs",
                    mainRunNs / 1_000_000.0
                )
            }
        }

        // The rolling reader is a separate Surface/BufferQueue producer, so the parent Activity's
        // HWUI frame timeline is not authoritative. Map each changed viewport's native submission
        // to the exact BufferQueue frame number and then to SurfaceFlinger's present fence.
        val presentation = traceSession.query(
            ViewerSurfacePresentationQuery.build(packageNameSql)
        ).firstOrNull()
        var emittedAuthoritativePresentation = false
        if (presentation != null) {
            val counts = SurfacePresentationParseCounts(
                activeBounds = presentation.long("active_bound_count"),
                attemptedBounds = presentation.long("attempted_bound_count"),
                submissionAttempts = presentation.long("submission_attempt_count"),
                coalescedSubmissionAttempts =
                    presentation.long("coalesced_submission_attempt_count"),
                mappedBounds = presentation.long("mapped_bound_count"),
                mappedQueues = presentation.long("mapped_queue_count"),
                uniqueQueueFrames = presentation.long("unique_queue_frame_count"),
                surfaceFenceRows = presentation.long("surface_fence_row_count"),
                presentedFrames = presentation.long("frame_count"),
                supersededQueueFrames = presentation.long("superseded_queue_frame_count"),
                inputEvents = presentation.long("input_event_count"),
                inputSegmentCount = presentation.long("input_segment_count"),
                candidateIntervals = presentation.long("candidate_interval_count"),
                intervals = presentation.long("interval_count"),
            )
            val intervalSumNs = presentation.long("interval_sum_ns")
            val maxGapNs = presentation.long("max_gap_ns")
            val secondGapNs = presentation.long("second_gap_ns")
            val thirdGapNs = presentation.long("third_gap_ns")
            val slowCount = presentation.long("slow_count")
            val refreshPeriodNs = presentation.long("refresh_period_ns")
            val activeDurationNs = presentation.long("active_duration_ns")
            val activeCpuNs = presentation.long("active_cpu_ns")
            val activeMainRunNs = presentation.long("active_main_run_ns")
            val parserValid = SurfacePresentationParserPolicy.isValid(counts) &&
                intervalSumNs > 0L && maxGapNs > 0L && slowCount >= 0L &&
                refreshPeriodNs > 0L && activeDurationNs > 0L &&
                activeCpuNs >= 0L && activeMainRunNs >= 0L

            // These diagnostics make "fences existed but the parser produced no interval"
            // distinguishable from a trace image that genuinely omitted SurfaceFlinger rows.
            measurements += Metric.Measurement(
                "viewerActivePresentationParserValid",
                if (parserValid) 1.0 else 0.0
            )
            measurements += Metric.Measurement(
                "viewerActivePresentationFenceRowCount",
                counts.surfaceFenceRows.toDouble()
            )
            measurements += Metric.Measurement(
                "viewerActivePresentationMappedQueueCount",
                counts.mappedQueues.toDouble()
            )
            measurements += Metric.Measurement(
                "viewerActivePresentationCandidateIntervalCount",
                counts.candidateIntervals.toDouble()
            )
            measurements += Metric.Measurement(
                "viewerActivePresentationCoalescedSubmissionCount",
                counts.coalescedSubmissionAttempts.toDouble()
            )
            measurements += Metric.Measurement(
                "viewerActivePresentationSupersededQueueCount",
                counts.supersededQueueFrames.toDouble()
            )

            if (parserValid) {
                measurements += Metric.Measurement(
                    "viewerActivePresentedFrameCount",
                    counts.presentedFrames.toDouble()
                )
                measurements += Metric.Measurement(
                    "viewerActivePresentationIntervalCount",
                    counts.intervals.toDouble()
                )
                measurements += Metric.Measurement(
                    "viewerActivePresentationFps",
                    counts.intervals.toDouble() * 1_000_000_000.0 /
                        intervalSumNs.toDouble()
                )
                measurements += Metric.Measurement(
                    "viewerActivePresentationJankPercent",
                    slowCount.toDouble() * 100.0 / counts.intervals.toDouble()
                )
                measurements += Metric.Measurement(
                    "viewerActivePresentationGapMaxMs",
                    maxGapNs / 1_000_000.0
                )
                measurements += Metric.Measurement(
                    "viewerActivePresentationGapSecondMs",
                    secondGapNs / 1_000_000.0
                )
                measurements += Metric.Measurement(
                    "viewerActivePresentationGapThirdMs",
                    thirdGapNs / 1_000_000.0
                )
                measurements += Metric.Measurement(
                    "viewerActivePresentationJankCount",
                    slowCount.toDouble()
                )
                measurements += Metric.Measurement(
                    "viewerActiveRefreshPeriodMs",
                    refreshPeriodNs / 1_000_000.0
                )
                measurements += Metric.Measurement(
                    "viewerActiveCpuPercent",
                    activeCpuNs.toDouble() * 100.0 / activeDurationNs.toDouble()
                )
                measurements += Metric.Measurement(
                    "viewerActiveMainThreadRunningMaxMs",
                    activeMainRunNs / 1_000_000.0
                )
                measurements += Metric.Measurement(
                    "viewerActivePresentationSystemFence",
                    1.0
                )
                for (ordinal in 1..MAX_SLOW_PRESENTATION_DIAGNOSTICS) {
                    val endNs = presentation.long("slow_${ordinal}_end_ns")
                    val durationNs = presentation.long("slow_${ordinal}_duration_ns")
                    if (endNs > 0L && durationNs > 25_000_000L) {
                        measurements += Metric.Measurement(
                            "viewerActivePresentationSlow${ordinal}EndNanos",
                            endNs.toDouble()
                        )
                        measurements += Metric.Measurement(
                            "viewerActivePresentationSlow${ordinal}DurationMs",
                            durationNs / 1_000_000.0
                        )
                    }
                }
                emittedAuthoritativePresentation = true
            } else {
                // A present-fence parser failure is not permission to relabel queueBuffer as a
                // physical display. Qualification remains fail-closed.
                measurements += Metric.Measurement(
                    "viewerActivePresentationSystemFence",
                    0.0
                )
            }
        } else {
            measurements += Metric.Measurement("viewerActivePresentationParserValid", 0.0)
            measurements += Metric.Measurement("viewerActivePresentationSystemFence", 0.0)
        }

        if (!emittedAuthoritativePresentation) {
            // App-side successful-buffer callbacks are retained only as explicitly named
            // diagnostics. They must never populate viewerActivePresentation* qualification keys.
            val native = traceSession.query(
                """
                WITH values_by_name AS (
                    SELECT counter_track.name AS name, MAX(counter.value) AS value
                    FROM counter
                    JOIN counter_track ON counter.track_id = counter_track.id
                    WHERE counter_track.name IN (
                        'ViewerNativeScrollIntervalCount',
                        'ViewerNativeScrollIntervalMicros',
                        'ViewerNativeScrollSlowIntervals',
                        'ViewerNativeScrollWorstIntervalNanos',
                        'ViewerNativeScrollRefreshPeriodNanos'
                    )
                    GROUP BY counter_track.name
                )
                SELECT CAST(MAX(CASE WHEN name = 'ViewerNativeScrollIntervalCount'
                                     THEN value END) AS INTEGER) AS interval_count,
                       CAST(MAX(CASE WHEN name = 'ViewerNativeScrollIntervalMicros'
                                     THEN value END) AS INTEGER) AS interval_sum_us,
                       CAST(MAX(CASE WHEN name = 'ViewerNativeScrollSlowIntervals'
                                     THEN value END) AS INTEGER) AS slow_count,
                       CAST(MAX(CASE WHEN name = 'ViewerNativeScrollWorstIntervalNanos'
                                     THEN value END) AS INTEGER) AS max_gap_ns,
                       CAST(MAX(CASE WHEN name = 'ViewerNativeScrollRefreshPeriodNanos'
                                     THEN value END) AS INTEGER) AS refresh_period_ns
                FROM values_by_name
                """.trimIndent()
            ).firstOrNull()
            if (native != null) {
                val intervalCount = runCatching {
                    native.long("interval_count")
                }.getOrDefault(0L)
                val intervalSumUs = runCatching {
                    native.long("interval_sum_us")
                }.getOrDefault(0L)
                val slowCount = runCatching { native.long("slow_count") }.getOrDefault(0L)
                val maxGapNs = runCatching { native.long("max_gap_ns") }.getOrDefault(0L)
                val refreshPeriodNs = runCatching {
                    native.long("refresh_period_ns")
                }.getOrDefault(0L)
                if (intervalCount > 0L && intervalSumUs > 0L && slowCount >= 0L &&
                    maxGapNs > 0L && refreshPeriodNs > 0L
                ) {
                    measurements += Metric.Measurement(
                        "viewerQueueDiagnosticFrameCount",
                        (intervalCount + 1L).toDouble()
                    )
                    measurements += Metric.Measurement(
                        "viewerQueueDiagnosticIntervalCount",
                        intervalCount.toDouble()
                    )
                    measurements += Metric.Measurement(
                        "viewerQueueDiagnosticFps",
                        intervalCount.toDouble() * 1_000_000.0 / intervalSumUs.toDouble()
                    )
                    measurements += Metric.Measurement(
                        "viewerQueueDiagnosticSlowPercent",
                        slowCount.toDouble() * 100.0 / intervalCount.toDouble()
                    )
                    measurements += Metric.Measurement(
                        "viewerQueueDiagnosticGapMaxMs",
                        maxGapNs / 1_000_000.0
                    )
                    measurements += Metric.Measurement(
                        "viewerQueueDiagnosticRefreshPeriodMs",
                        refreshPeriodNs / 1_000_000.0
                    )
                }
            }
        }
        return measurements
    }
}
