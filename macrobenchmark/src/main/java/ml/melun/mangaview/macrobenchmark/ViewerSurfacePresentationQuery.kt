package ml.melun.mangaview.macrobenchmark

internal const val MAX_SLOW_PRESENTATION_DIAGNOSTICS = 8

internal data class SurfacePresentationParseCounts(
    val activeBounds: Long,
    val attemptedBounds: Long,
    val submissionAttempts: Long,
    val coalescedSubmissionAttempts: Long,
    val mappedBounds: Long,
    val mappedQueues: Long,
    val uniqueQueueFrames: Long,
    val surfaceFenceRows: Long,
    val presentedFrames: Long,
    val supersededQueueFrames: Long,
    val inputEvents: Long,
    val inputSegmentCount: Long,
    val candidateIntervals: Long,
    val intervals: Long,
)

/**
 * Fail-closed validation for the system-fence cadence parser. Missing producer attempts are
 * accepted only when a later submission explicitly superseded them; queued frames without an
 * exact present fence are accepted only when a later same-layer present proves trace coverage.
 */
internal object SurfacePresentationParserPolicy {
    fun isValid(counts: SurfacePresentationParseCounts): Boolean =
        counts.activeBounds > 0L &&
            counts.attemptedBounds > 0L &&
            counts.attemptedBounds <= counts.activeBounds &&
            counts.submissionAttempts >= counts.attemptedBounds &&
            counts.submissionAttempts >= counts.mappedQueues &&
            counts.coalescedSubmissionAttempts >= 0L &&
            counts.submissionAttempts - counts.mappedQueues ==
                counts.coalescedSubmissionAttempts &&
            counts.mappedBounds > 0L &&
            counts.mappedBounds <= counts.attemptedBounds &&
            counts.mappedQueues >= counts.mappedBounds &&
            counts.mappedQueues == counts.uniqueQueueFrames &&
            counts.presentedFrames > 0L &&
            counts.presentedFrames <= counts.uniqueQueueFrames &&
            counts.supersededQueueFrames >= 0L &&
            counts.uniqueQueueFrames - counts.presentedFrames ==
                counts.supersededQueueFrames &&
            counts.surfaceFenceRows >= counts.presentedFrames &&
            counts.inputEvents > 0L &&
            counts.inputSegmentCount > 0L &&
            counts.candidateIntervals ==
                counts.presentedFrames - counts.inputSegmentCount &&
            counts.intervals <= counts.candidateIntervals &&
            counts.intervals > 0L
}

/**
 * Maps each changed physical viewport submission to its BufferQueue frame number, then joins that
 * exact frame to SurfaceFlinger's present fence. A motion slice closes at the app-side queue
 * callback, before SurfaceFlinger normally signals the fence, so a queue/fence must never be
 * searched *inside* one short motion slice or partitioned by that slice. BufferQueue may coalesce
 * an overwritten attempt, a short physical bound may retire without a surface submission, and one
 * motion slice may contain multiple successfully queued frames. SurfaceFlinger may also latch and
 * replace a queued frame without presenting it; a later same-layer present is required before that
 * queue is classified as superseded instead of an unexplained missing fence.
 *
 * Presentation gaps qualify only while changed physical viewports remain continuous: the next
 * changed bound must begin no more than one physical refresh after the prior bound ended. A longer
 * stationary/input-batching gap had no requested viewport to present and is not display jank.
 * Both endpoints must also be consecutive changed-viewport submissions and consecutive physical
 * presents; a coalesced intermediate request or an otherwise visible intermediate fence makes the
 * outer pair ineligible. Every input DOWN/UP/CANCEL transition starts a hard segment: drag and
 * post-UP settling are measured independently, while a presentation pair crossing a transition is
 * rejected. This preserves observable post-UP motion without inferring continuity across input
 * phases. The slow threshold itself remains the strict, absolute `gap_ns > 25_000_000` requirement.
 */
internal object ViewerSurfacePresentationQuery {
    fun build(packageNameSql: String): String =
        """
        WITH refresh AS MATERIALIZED (
            SELECT CAST(counter.value AS INTEGER) AS period_ns
            FROM counter
            JOIN counter_track ON counter.track_id = counter_track.id
            WHERE counter_track.name = 'ViewerPhysicalScrollRefreshPeriodNs'
              AND counter.value > 0
            ORDER BY counter.ts DESC
            LIMIT 1
        ),
        target_input_events AS MATERIALIZED (
            SELECT input_slice.ts AS event_ts,
                   input_slice.name AS event_name
            FROM slice AS input_slice
            JOIN thread_track ON input_slice.track_id = thread_track.id
            JOIN thread ON thread_track.utid = thread.utid
            JOIN process ON thread.upid = process.upid
            WHERE process.name = '$packageNameSql'
              AND (
                  input_slice.name LIKE
                      'dispatchInputEvent MotionEvent ACTION_DOWN%'
                  OR input_slice.name LIKE
                      'dispatchInputEvent MotionEvent ACTION_UP%'
                  OR input_slice.name LIKE
                      'dispatchInputEvent MotionEvent ACTION_CANCEL%'
              )
        ),
        active_bounds AS MATERIALIZED (
            SELECT id AS bound_id, ts AS start_ts, ts + dur AS end_ts
            FROM slice
            WHERE name = 'ViewerPhysicalScrollMotion' AND dur > 0
        ),
        active_duration AS MATERIALIZED (
            SELECT COALESCE(SUM(end_ts - start_ts), 0) AS duration_ns
            FROM active_bounds
        ),
        physical_submission_attempts AS MATERIALIZED (
            SELECT submission.id AS submission_id,
                   active_bounds.bound_id,
                   active_bounds.start_ts,
                   active_bounds.end_ts,
                   COALESCE(
                       (
                           SELECT MAX(target_input_events.event_ts)
                           FROM target_input_events
                           WHERE target_input_events.event_ts <= submission.ts
                       ),
                       0
                   ) AS input_segment_start_ts,
                   submission.ts AS submit_ts
            FROM active_bounds
            JOIN slice AS submission
              ON submission.name = 'ViewerSurfaceControlSubmission'
             AND submission.ts >= active_bounds.start_ts
             AND submission.ts <= active_bounds.end_ts
        ),
        physical_submissions AS MATERIALIZED (
            SELECT physical_submission_attempts.*,
                   LEAD(submit_ts) OVER (
                       ORDER BY submit_ts, submission_id
                   ) AS next_submit_ts
            FROM physical_submission_attempts
        ),
        surface_queues AS MATERIALIZED (
            SELECT layer_name, frame_number, ts AS queue_ts
            FROM frame_slice
            WHERE name = 'Queue'
              AND layer_name LIKE 'SurfaceView%ReaderV2Activity%(BLAST)#%'
        ),
        queue_candidates AS MATERIALIZED (
            SELECT physical_submissions.*,
                   surface_queues.layer_name,
                   surface_queues.frame_number,
                   surface_queues.queue_ts,
                   ROW_NUMBER() OVER (
                       PARTITION BY physical_submissions.submission_id
                       ORDER BY surface_queues.queue_ts, surface_queues.frame_number
                   ) AS candidate_rank
            FROM physical_submissions
            JOIN surface_queues
              ON surface_queues.queue_ts >= physical_submissions.submit_ts
             AND (physical_submissions.next_submit_ts IS NULL OR
                  surface_queues.queue_ts < physical_submissions.next_submit_ts)
        ),
        mapped_queues AS MATERIALIZED (
            SELECT * FROM queue_candidates WHERE candidate_rank = 1
        ),
        surface_presents AS MATERIALIZED (
            SELECT layer_name, frame_number, ts AS present_ts
            FROM frame_slice
            WHERE name = 'PresentFenceSignaled'
              AND layer_name LIKE 'SurfaceView%ReaderV2Activity%(BLAST)#%'
        ),
        present_candidates AS MATERIALIZED (
            SELECT mapped_queues.*,
                   surface_presents.present_ts,
                   ROW_NUMBER() OVER (
                       PARTITION BY mapped_queues.submission_id
                       ORDER BY surface_presents.present_ts
                   ) AS present_candidate_rank
            FROM mapped_queues
            JOIN surface_presents
              ON surface_presents.layer_name = mapped_queues.layer_name
             AND surface_presents.frame_number = mapped_queues.frame_number
             AND surface_presents.present_ts >= mapped_queues.queue_ts
        ),
        presented AS MATERIALIZED (
            SELECT * FROM present_candidates WHERE present_candidate_rank = 1
        ),
        coalesced_submission_attempts AS MATERIALIZED (
            SELECT physical_submissions.submission_id
            FROM physical_submissions
            WHERE physical_submissions.next_submit_ts IS NOT NULL
              AND NOT EXISTS (
                  SELECT 1
                  FROM mapped_queues
                  WHERE mapped_queues.submission_id =
                      physical_submissions.submission_id
              )
        ),
        superseded_queues AS MATERIALIZED (
            SELECT mapped_queues.submission_id
            FROM mapped_queues
            WHERE NOT EXISTS (
                      SELECT 1
                      FROM presented
                      WHERE presented.submission_id = mapped_queues.submission_id
                  )
              AND EXISTS (
                      SELECT 1
                      FROM surface_presents AS later_present
                      WHERE later_present.layer_name = mapped_queues.layer_name
                        AND later_present.present_ts > mapped_queues.queue_ts
                  )
        ),
        ordered_presentations AS MATERIALIZED (
            SELECT presented.*,
                   present_ts - LAG(present_ts) OVER (
                       PARTITION BY input_segment_start_ts
                       ORDER BY present_ts, frame_number
                   ) AS gap_ns,
                   LAG(end_ts) OVER (
                       PARTITION BY input_segment_start_ts
                       ORDER BY present_ts, frame_number
                   ) AS previous_bound_end_ts,
                   LAG(submit_ts) OVER (
                       PARTITION BY input_segment_start_ts
                       ORDER BY present_ts, frame_number
                   ) AS previous_submit_ts
            FROM presented
        ),
        candidate_intervals AS MATERIALIZED (
            SELECT *
            FROM ordered_presentations
            WHERE gap_ns > 0
        ),
        intervals_unranked AS MATERIALIZED (
            SELECT candidate_intervals.*
            FROM candidate_intervals
            CROSS JOIN refresh
            WHERE start_ts - previous_bound_end_ts <= refresh.period_ns
              AND NOT EXISTS (
                  SELECT 1
                  FROM target_input_events AS input_boundary
                  WHERE input_boundary.event_ts >
                            candidate_intervals.present_ts - candidate_intervals.gap_ns
                    AND input_boundary.event_ts < candidate_intervals.present_ts
              )
              AND NOT EXISTS (
                  SELECT 1
                  FROM physical_submissions AS intermediate_submission
                  WHERE intermediate_submission.submit_ts >
                            candidate_intervals.previous_submit_ts
                    AND intermediate_submission.submit_ts <
                            candidate_intervals.submit_ts
              )
              AND NOT EXISTS (
                  SELECT 1
                  FROM surface_presents AS intermediate_present
                  WHERE intermediate_present.present_ts >
                            candidate_intervals.present_ts - candidate_intervals.gap_ns
                    AND intermediate_present.present_ts <
                            candidate_intervals.present_ts
              )
        ),
        intervals AS MATERIALIZED (
            SELECT *,
                   ROW_NUMBER() OVER (
                       ORDER BY gap_ns DESC, present_ts
                   ) AS gap_rank
            FROM intervals_unranked
        ),
        slow_intervals AS MATERIALIZED (
            SELECT present_ts AS slow_end_ns,
                   gap_ns AS slow_duration_ns,
                   ROW_NUMBER() OVER (
                       ORDER BY present_ts, frame_number
                   ) AS slow_ordinal
            FROM intervals
            WHERE gap_ns > 25000000
        ),
        target_threads AS MATERIALIZED (
            SELECT thread.utid AS utid,
                   CASE WHEN thread.tid = process.pid THEN 1 ELSE 0 END AS is_main
            FROM thread
            JOIN process USING (upid)
            WHERE process.name = '$packageNameSql'
        ),
        active_sched AS MATERIALIZED (
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
        active_cpu AS MATERIALIZED (
            SELECT COALESCE(SUM(clipped_dur), 0) AS cpu_ns,
                   COALESCE(MAX(CASE WHEN is_main = 1 THEN clipped_dur ELSE 0 END), 0)
                       AS main_run_ns
            FROM active_sched
        )
        SELECT (SELECT COUNT(*) FROM active_bounds) AS active_bound_count,
               (SELECT COUNT(DISTINCT bound_id) FROM physical_submissions)
                   AS attempted_bound_count,
               (SELECT COUNT(*) FROM physical_submissions) AS submission_attempt_count,
               (SELECT COUNT(*) FROM coalesced_submission_attempts)
                   AS coalesced_submission_attempt_count,
               (SELECT COUNT(DISTINCT bound_id) FROM mapped_queues) AS mapped_bound_count,
               (SELECT COUNT(*) FROM mapped_queues) AS mapped_queue_count,
               (SELECT COUNT(*) FROM (
                    SELECT layer_name, frame_number
                    FROM mapped_queues
                    GROUP BY layer_name, frame_number
                )) AS unique_queue_frame_count,
               (SELECT COUNT(*) FROM surface_presents) AS surface_fence_row_count,
               (SELECT COUNT(*) FROM presented) AS frame_count,
               (SELECT COUNT(*) FROM superseded_queues) AS superseded_queue_frame_count,
               (SELECT COUNT(*) FROM target_input_events) AS input_event_count,
               (SELECT COUNT(DISTINCT input_segment_start_ts) FROM presented)
                   AS input_segment_count,
               (SELECT COUNT(*) FROM candidate_intervals) AS candidate_interval_count,
               (SELECT COUNT(*) FROM intervals) AS interval_count,
               COALESCE((SELECT SUM(gap_ns) FROM intervals), 0) AS interval_sum_ns,
               COALESCE((SELECT MAX(gap_ns) FROM intervals), 0) AS max_gap_ns,
               COALESCE((SELECT MAX(CASE WHEN gap_rank = 2 THEN gap_ns ELSE 0 END)
                         FROM intervals), 0) AS second_gap_ns,
               COALESCE((SELECT MAX(CASE WHEN gap_rank = 3 THEN gap_ns ELSE 0 END)
                         FROM intervals), 0) AS third_gap_ns,
               (SELECT COUNT(*) FROM slow_intervals) AS slow_count,
               COALESCE((SELECT period_ns FROM refresh), 0) AS refresh_period_ns,
               (SELECT duration_ns FROM active_duration) AS active_duration_ns,
               (SELECT cpu_ns FROM active_cpu) AS active_cpu_ns,
               (SELECT main_run_ns FROM active_cpu) AS active_main_run_ns,
               COALESCE((SELECT slow_end_ns FROM slow_intervals WHERE slow_ordinal = 1), 0)
                   AS slow_1_end_ns,
               COALESCE((SELECT slow_duration_ns FROM slow_intervals WHERE slow_ordinal = 1), 0)
                   AS slow_1_duration_ns,
               COALESCE((SELECT slow_end_ns FROM slow_intervals WHERE slow_ordinal = 2), 0)
                   AS slow_2_end_ns,
               COALESCE((SELECT slow_duration_ns FROM slow_intervals WHERE slow_ordinal = 2), 0)
                   AS slow_2_duration_ns,
               COALESCE((SELECT slow_end_ns FROM slow_intervals WHERE slow_ordinal = 3), 0)
                   AS slow_3_end_ns,
               COALESCE((SELECT slow_duration_ns FROM slow_intervals WHERE slow_ordinal = 3), 0)
                   AS slow_3_duration_ns,
               COALESCE((SELECT slow_end_ns FROM slow_intervals WHERE slow_ordinal = 4), 0)
                   AS slow_4_end_ns,
               COALESCE((SELECT slow_duration_ns FROM slow_intervals WHERE slow_ordinal = 4), 0)
                   AS slow_4_duration_ns,
               COALESCE((SELECT slow_end_ns FROM slow_intervals WHERE slow_ordinal = 5), 0)
                   AS slow_5_end_ns,
               COALESCE((SELECT slow_duration_ns FROM slow_intervals WHERE slow_ordinal = 5), 0)
                   AS slow_5_duration_ns,
               COALESCE((SELECT slow_end_ns FROM slow_intervals WHERE slow_ordinal = 6), 0)
                   AS slow_6_end_ns,
               COALESCE((SELECT slow_duration_ns FROM slow_intervals WHERE slow_ordinal = 6), 0)
                   AS slow_6_duration_ns,
               COALESCE((SELECT slow_end_ns FROM slow_intervals WHERE slow_ordinal = 7), 0)
                   AS slow_7_end_ns,
               COALESCE((SELECT slow_duration_ns FROM slow_intervals WHERE slow_ordinal = 7), 0)
                   AS slow_7_duration_ns,
               COALESCE((SELECT slow_end_ns FROM slow_intervals WHERE slow_ordinal = 8), 0)
                   AS slow_8_end_ns,
               COALESCE((SELECT slow_duration_ns FROM slow_intervals WHERE slow_ordinal = 8), 0)
                   AS slow_8_duration_ns
        """.trimIndent()
}
