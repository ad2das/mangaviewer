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
      AND surface_frame.layer_name LIKE 'SurfaceView%ReaderV2Activity%(BLAST)#%'
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
    WHERE process.name = 'ml.melun.mangaview'
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
),
presentation AS (
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
)
SELECT frame_count,
       interval_count,
       ROUND(interval_count * 1000000000.0 / interval_sum_ns, 4) AS fps,
       slow_count,
       ROUND(slow_count * 100.0 / interval_count, 4) AS jank_percent,
       ROUND(max_gap_ns / 1000000.0, 3) AS max_gap_ms,
       ROUND(second_gap_ns / 1000000.0, 3) AS second_gap_ms,
       ROUND(third_gap_ns / 1000000.0, 3) AS third_gap_ms,
       ROUND(refresh_period_ns / 1000000.0, 3) AS refresh_period_ms,
       ROUND(active_duration_ns / 1000000.0, 3) AS active_duration_ms,
       ROUND(active_cpu_ns * 100.0 / active_duration_ns, 3) AS active_cpu_percent,
       ROUND(active_main_run_ns / 1000000.0, 3) AS active_main_run_max_ms
FROM presentation;
