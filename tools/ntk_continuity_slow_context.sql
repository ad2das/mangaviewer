WITH active_bounds AS (
    SELECT id AS bound_id, ts AS start_ts, ts + dur AS end_ts
    FROM slice
    WHERE name = 'ViewerPhysicalScrollMotion' AND dur > 0
),
presented AS (
    SELECT active_bounds.bound_id, active_bounds.start_ts, active_bounds.end_ts,
           surface_frame.ts AS present_ts
    FROM active_bounds
    JOIN frame_slice AS surface_frame
      ON surface_frame.ts >= active_bounds.start_ts
     AND surface_frame.ts <= active_bounds.end_ts
    WHERE surface_frame.name = 'PresentFenceSignaled'
      AND surface_frame.layer_name LIKE 'SurfaceView%ReaderV2Activity%(BLAST)#%'
),
with_gaps AS (
    SELECT bound_id, start_ts, end_ts,
           present_ts - LAG(present_ts) OVER (
               PARTITION BY bound_id ORDER BY present_ts
           ) AS gap_ns
    FROM presented
),
slow_bound AS (
    SELECT bound_id, start_ts, end_ts
    FROM with_gaps
    WHERE gap_ns > 25000000
    ORDER BY gap_ns DESC
    LIMIT 1
),
window AS (
    SELECT start_ts - 20000000 AS start_ts, end_ts + 20000000 AS end_ts
    FROM slow_bound
),
target_threads AS (
    SELECT thread.utid, thread.tid, thread.name AS thread_name,
           process.pid, process.name AS process_name
    FROM thread
    JOIN process USING (upid)
    WHERE process.name = 'ml.melun.mangaview'
)
SELECT 'slice' AS row_kind,
       target_threads.thread_name,
       target_threads.tid,
       ROUND(slice.ts / 1000000.0, 3) AS start_ms,
       ROUND(slice.dur / 1000000.0, 3) AS dur_ms,
       slice.name AS detail,
       '' AS state
FROM slice
JOIN thread_track ON slice.track_id = thread_track.id
JOIN target_threads ON thread_track.utid = target_threads.utid
CROSS JOIN window
WHERE slice.ts < window.end_ts
  AND slice.ts + slice.dur > window.start_ts
UNION ALL
SELECT 'state' AS row_kind,
       target_threads.thread_name,
       target_threads.tid,
       ROUND(thread_state.ts / 1000000.0, 3) AS start_ms,
       ROUND(thread_state.dur / 1000000.0, 3) AS dur_ms,
       COALESCE(thread_state.blocked_function, '') AS detail,
       thread_state.state AS state
FROM thread_state
JOIN target_threads USING (utid)
CROSS JOIN window
WHERE thread_state.ts < window.end_ts
  AND thread_state.ts + thread_state.dur > window.start_ts
  AND target_threads.thread_name IN (
      'main',
      'ReaderSurfaceProducer',
      'ntk-rolling-egl'
  )
ORDER BY start_ms, row_kind, tid;
