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
        val measurements = ArrayList<Metric.Measurement>(10)
        var viewerScrollDurationNs = 0L
        var viewerScrollCpuNs = 0L
        var viewerScrollMainRunNs = 0L
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
                viewerScrollDurationNs = intervalNs
                viewerScrollCpuNs = cpuNs
                viewerScrollMainRunNs = mainRunNs
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

        // AndroidX FrameTimingMetric is intentionally inapplicable here: the rolling reader is a
        // separate Surface/BufferQueue producer, so its buffers are absent from the parent
        // Activity's actual_frame_timeline_slice rows and that metric aborts with no expect/actual
        // slices. Measure the system-side SurfaceFlinger present fence for every buffer of the
        // ReaderV2Activity SurfaceView. Bounds begin with the first changed
        // physical viewport and close after its last submitted drag/fling frame; stationary
        // finger holds and automation gaps are therefore outside the cadence denominator.
        // App-side onDraw/callback spacing is not accepted as this evidence.
        var emittedActivePresentation = false
        val presentation = traceSession.query(
            """
            WITH active_bounds AS (
                SELECT id AS bound_id, ts AS start_ts, ts + dur AS end_ts
                FROM slice
                WHERE name = 'ViewerPhysicalScrollMotion' AND dur > 0
            ),
            active_duration AS (
                SELECT COALESCE(SUM(end_ts - start_ts), 0) AS duration_ns
                FROM active_bounds
            ),
            refresh AS (
                SELECT CAST(counter.value AS INTEGER) AS period_ns
                FROM counter
                JOIN counter_track ON counter.track_id = counter_track.id
                WHERE counter_track.name = 'ViewerPhysicalScrollRefreshPeriodNs'
                  AND counter.value > 0
                ORDER BY counter.ts DESC
                LIMIT 1
            ),
            presented AS (
                SELECT active_bounds.bound_id AS bound_id,
                       surface_frame.frame_number AS frame_token,
                       surface_frame.ts AS present_ts
                FROM active_bounds
                JOIN frame_slice AS surface_frame
                  ON surface_frame.ts >= active_bounds.start_ts
                 AND surface_frame.ts <= active_bounds.end_ts
                WHERE surface_frame.name = 'PresentFenceSignaled'
                  AND surface_frame.layer_name LIKE
                    'SurfaceView%ReaderV2Activity%(BLAST)#%'
            ),
            ordered AS (
                SELECT bound_id, frame_token, present_ts,
                       present_ts - LAG(present_ts) OVER (
                           PARTITION BY bound_id ORDER BY present_ts, frame_token
                       ) AS gap_ns
                FROM presented
            ),
            intervals AS (
                SELECT gap_ns,
                       ROW_NUMBER() OVER (ORDER BY gap_ns DESC) AS gap_rank
                FROM ordered
                WHERE gap_ns > 0
            ),
            target_threads AS (
                SELECT thread.utid AS utid,
                       CASE WHEN thread.tid = process.pid THEN 1 ELSE 0 END AS is_main
                FROM thread
                JOIN process USING (upid)
                WHERE process.name = '$packageNameSql'
            ),
            active_sched AS (
                SELECT target_threads.is_main AS is_main,
                       MAX(
                           0,
                           MIN(sched_slice.ts + sched_slice.dur, active_bounds.end_ts) -
                               MAX(sched_slice.ts, active_bounds.start_ts)
                       ) AS clipped_dur
                FROM sched_slice
                JOIN target_threads USING (utid)
                JOIN active_bounds
                  ON sched_slice.ts < active_bounds.end_ts
                 AND sched_slice.ts + sched_slice.dur > active_bounds.start_ts
            ),
            active_cpu AS (
                SELECT COALESCE(SUM(clipped_dur), 0) AS cpu_ns,
                       COALESCE(MAX(CASE WHEN is_main = 1 THEN clipped_dur ELSE 0 END), 0)
                           AS main_run_ns
                FROM active_sched
            )
            SELECT (SELECT COUNT(*) FROM presented) AS frame_count,
                   COUNT(*) AS interval_count,
                   COALESCE(SUM(gap_ns), 0) AS interval_sum_ns,
                   COALESCE(MAX(gap_ns), 0) AS max_gap_ns,
                   COALESCE(MAX(CASE WHEN gap_rank = 2 THEN gap_ns ELSE 0 END), 0)
                       AS second_gap_ns,
                   COALESCE(MAX(CASE WHEN gap_rank = 3 THEN gap_ns ELSE 0 END), 0)
                       AS third_gap_ns,
                   COALESCE(SUM(CASE WHEN gap_ns > refresh.period_ns * 1.5
                                     THEN 1 ELSE 0 END), 0) AS slow_count,
                   refresh.period_ns AS refresh_period_ns,
                   active_duration.duration_ns AS active_duration_ns,
                   active_cpu.cpu_ns AS active_cpu_ns,
                   active_cpu.main_run_ns AS active_main_run_ns
            FROM intervals
            CROSS JOIN refresh
            CROSS JOIN active_duration
            CROSS JOIN active_cpu
            GROUP BY refresh.period_ns, active_duration.duration_ns,
                     active_cpu.cpu_ns, active_cpu.main_run_ns
            """.trimIndent()
        ).firstOrNull()
        if (presentation != null) {
            val frameCount = presentation.long("frame_count")
            val intervalCount = presentation.long("interval_count")
            val intervalSumNs = presentation.long("interval_sum_ns")
            val maxGapNs = presentation.long("max_gap_ns")
            val secondGapNs = presentation.long("second_gap_ns")
            val thirdGapNs = presentation.long("third_gap_ns")
            val slowCount = presentation.long("slow_count")
            val refreshPeriodNs = presentation.long("refresh_period_ns")
            val activeDurationNs = presentation.long("active_duration_ns")
            val activeCpuNs = presentation.long("active_cpu_ns")
            val activeMainRunNs = presentation.long("active_main_run_ns")
            if (frameCount > 0L && intervalCount > 0L && intervalSumNs > 0L &&
                maxGapNs > 0L && slowCount >= 0L && refreshPeriodNs > 0L &&
                activeDurationNs > 0L && activeCpuNs >= 0L && activeMainRunNs >= 0L
            ) {
                measurements += Metric.Measurement(
                    "viewerActivePresentedFrameCount",
                    frameCount.toDouble()
                )
                measurements += Metric.Measurement(
                    "viewerActivePresentationIntervalCount",
                    intervalCount.toDouble()
                )
                measurements += Metric.Measurement(
                    "viewerActivePresentationFps",
                    intervalCount.toDouble() * 1_000_000_000.0 / intervalSumNs.toDouble()
                )
                measurements += Metric.Measurement(
                    "viewerActivePresentationJankPercent",
                    slowCount.toDouble() * 100.0 / intervalCount.toDouble()
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
                // One means these cadence samples came from SurfaceFlinger's
                // PresentFenceSignaled rows for the reader BLAST child layer. Keep the evidence
                // kind explicit so qualification never mistakes queueBuffer completion for a
                // display presentation.
                measurements += Metric.Measurement(
                    "viewerActivePresentationSystemFence",
                    1.0
                )
                emittedActivePresentation = true
            }
        }
        if (!emittedActivePresentation) {
            // Host-GPU emulators can omit the SurfaceFlinger child-layer name even though the
            // app receives every real buffer commit. ReaderTelemetry publishes those production
            // callback intervals as trace counters at viewer retirement. Combine them with the
            // target-process scheduler slices bounded by the physical-motion trace.
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
            val active = traceSession.query(
                """
                WITH active_bounds AS (
                    SELECT ts AS start_ts, ts + dur AS end_ts
                    FROM slice
                    WHERE name = 'ViewerPhysicalScrollMotion' AND dur > 0
                ),
                active_duration AS (
                    SELECT COALESCE(SUM(end_ts - start_ts), 0) AS duration_ns
                    FROM active_bounds
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
                           MAX(0, MIN(sched_slice.ts + sched_slice.dur, active_bounds.end_ts) -
                               MAX(sched_slice.ts, active_bounds.start_ts)) AS clipped_dur
                    FROM sched_slice
                    JOIN target_threads USING (utid)
                    JOIN active_bounds
                      ON sched_slice.ts < active_bounds.end_ts
                     AND sched_slice.ts + sched_slice.dur > active_bounds.start_ts
                )
                SELECT active_duration.duration_ns AS duration_ns,
                       COALESCE(SUM(clipped.clipped_dur), 0) AS cpu_ns,
                       COALESCE(MAX(CASE WHEN clipped.is_main = 1
                                         THEN clipped.clipped_dur ELSE 0 END), 0) AS main_run_ns
                FROM active_duration
                LEFT JOIN clipped ON 1 = 1
                GROUP BY active_duration.duration_ns
                """.trimIndent()
            ).firstOrNull()
            if (native != null) {
                // Aggregate SQL returns one row even when every counter expression is NULL. Row.long
                // casts that NULL and used to abort the entire Macrobenchmark after the real UI run,
                // hiding the actual SLA result. Missing optional diagnostics are zero, never a test
                // control-flow failure.
                val intervalCount = runCatching { native.long("interval_count") }.getOrDefault(0L)
                val intervalSumUs = runCatching { native.long("interval_sum_us") }.getOrDefault(0L)
                val slowCount = runCatching { native.long("slow_count") }.getOrDefault(0L)
                val maxGapNs = runCatching { native.long("max_gap_ns") }.getOrDefault(0L)
                val refreshPeriodNs = runCatching {
                    native.long("refresh_period_ns")
                }.getOrDefault(0L)
                var activeDurationNs = active?.let {
                    runCatching { it.long("duration_ns") }.getOrDefault(0L)
                } ?: 0L
                var activeCpuNs = active?.let {
                    runCatching { it.long("cpu_ns") }.getOrDefault(0L)
                } ?: 0L
                var activeMainRunNs = active?.let {
                    runCatching { it.long("main_run_ns") }.getOrDefault(0L)
                } ?: 0L
                if (activeDurationNs <= 0L) {
                    activeDurationNs = viewerScrollDurationNs
                    activeCpuNs = viewerScrollCpuNs
                    activeMainRunNs = viewerScrollMainRunNs
                }
                if (intervalCount > 0L && intervalSumUs > 0L && slowCount >= 0L &&
                    maxGapNs > 0L && refreshPeriodNs > 0L && activeDurationNs > 0L &&
                    activeCpuNs >= 0L && activeMainRunNs >= 0L
                ) {
                    measurements += Metric.Measurement(
                        "viewerActivePresentedFrameCount", (intervalCount + 1L).toDouble()
                    )
                    measurements += Metric.Measurement(
                        "viewerActivePresentationIntervalCount", intervalCount.toDouble()
                    )
                    measurements += Metric.Measurement(
                        "viewerActivePresentationFps",
                        intervalCount.toDouble() * 1_000_000.0 / intervalSumUs.toDouble()
                    )
                    measurements += Metric.Measurement(
                        "viewerActivePresentationJankPercent",
                        slowCount.toDouble() * 100.0 / intervalCount.toDouble()
                    )
                    measurements += Metric.Measurement(
                        "viewerActivePresentationGapMaxMs", maxGapNs / 1_000_000.0
                    )
                    measurements += Metric.Measurement(
                        "viewerActiveRefreshPeriodMs", refreshPeriodNs / 1_000_000.0
                    )
                    measurements += Metric.Measurement(
                        "viewerActiveCpuPercent",
                        activeCpuNs.toDouble() * 100.0 / activeDurationNs.toDouble()
                    )
                    measurements += Metric.Measurement(
                        "viewerActiveMainThreadRunningMaxMs", activeMainRunNs / 1_000_000.0
                    )
                    // Zero identifies the app-side successful-buffer-submission fallback used
                    // only when an emulator image omits child-layer system-fence rows.
                    measurements += Metric.Measurement(
                        "viewerActivePresentationSystemFence",
                        0.0
                    )
                }
            }
        }
        return measurements
    }
}
