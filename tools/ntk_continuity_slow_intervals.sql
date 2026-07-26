WITH active_bounds AS (
    SELECT id AS bound_id, ts AS start_ts, ts + dur AS end_ts, dur
    FROM slice
    WHERE name = 'ViewerPhysicalScrollMotion' AND dur > 0
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
           active_bounds.start_ts AS bound_start_ts,
           active_bounds.dur AS bound_dur,
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
    SELECT bound_id, bound_start_ts, bound_dur, frame_token, present_ts,
           present_ts - LAG(present_ts) OVER (
               PARTITION BY bound_id ORDER BY present_ts, frame_token
           ) AS gap_ns
    FROM presented
)
SELECT bound_id,
       ROUND(bound_start_ts / 1000000.0, 3) AS bound_start_ms,
       ROUND(bound_dur / 1000000.0, 3) AS bound_duration_ms,
       frame_token,
       ROUND(present_ts / 1000000.0, 3) AS present_ms,
       ROUND(gap_ns / 1000000.0, 3) AS gap_ms
FROM ordered
CROSS JOIN refresh
WHERE gap_ns > refresh.period_ns * 1.5
ORDER BY present_ts;
