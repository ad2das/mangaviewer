WITH active_bounds AS (
    SELECT id AS bound_id, ts AS start_ts, ts + dur AS end_ts
    FROM slice
    WHERE name = 'ViewerPhysicalScrollMotion' AND dur > 0
),
submissions AS (
    SELECT active_bounds.bound_id,
           slice.ts AS submit_ts,
           slice.dur AS submit_dur
    FROM active_bounds
    JOIN slice
      ON slice.ts >= active_bounds.start_ts
     AND slice.ts <= active_bounds.end_ts
    WHERE slice.name = 'ViewerSurfaceQueueSubmission'
),
ordered AS (
    SELECT bound_id, submit_ts, submit_dur,
           submit_ts - LAG(submit_ts) OVER (
               PARTITION BY bound_id ORDER BY submit_ts
           ) AS gap_ns
    FROM submissions
),
intervals AS (
    SELECT gap_ns
    FROM ordered
    WHERE gap_ns > 0
)
SELECT (SELECT COUNT(*) FROM submissions) AS submission_count,
       COUNT(*) AS interval_count,
       ROUND(COUNT(*) * 1000000000.0 / SUM(gap_ns), 4) AS submission_fps,
       ROUND(MAX(gap_ns) / 1000000.0, 3) AS max_submission_gap_ms,
       ROUND(MAX(submit_dur) / 1000000.0, 3) AS max_submission_work_ms
FROM ordered
WHERE gap_ns > 0;
