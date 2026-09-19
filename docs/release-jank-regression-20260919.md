# Release jank regression check (2026-09-19)

After the 2026-09-18/19 UI cycles (header compact, search fade, artwork
placeholder/retry, tag chips, failure copy, favorite-status clip, subtitle
dedupe) the R8 release build was re-measured on emulator 5558 to look for a
frame-time regression against the documented floor
(`docs/ui-ux-overhaul-20260918.md`).

## Method

- `:app:assembleRelease` — R8, `app-release.apk` 10,558,576 B.
- Installed over the debug build (same debug signing, app data kept).
- 5558, host GPU, 1080x2340 (current density override 587).
- Opened 외모지상주의 detail from 보관함; the 624-episode list renders from
  the cached snapshot. Per sample: `dumpsys gfxinfo ml.melun.mangaview reset`
  -> one 200 ms fling -> read the summary.

## Result

| sample | janky frames |
| --- | --- |
| 1 | 4 (6.35%) |
| 2 | 4 (6.35%) |
| 3 | 5 (8.20%) |
| 4 | 4 (6.45%) |
| 5 | 2 (3.08%) |

Median 6.35% (3.08–8.20%).

Profile of the last sample (the others are the same shape):

```
Total frames rendered: 65
Janky frames: 2 (3.08%)
Number Missed Vsync: 0
Number Slow UI thread: 0
Number Slow bitmap uploads: 0
Number Slow issue draw commands: 2
50th / 90th / 99th percentile: 20 / 24 / 28 ms
```

## Reading

- The jank profile matches the documented floor exactly: every janky frame is
  `Slow issue draw commands`, with zero missed vsync and zero slow-UI-thread
  frames. The cost sits in the emulator's GL draw translation, not in app
  code — none of this window's changes touch the measured UI thread path.
- The percentage is not directly comparable to the 1.6% clean-condition
  median: this run had a degraded network (the release build does not trust
  the debug mitmproxy CA, so the detail's refresh and artwork requests fail
  and retry in the background) and a shorter sample window (~65 frames).
  Treat it as a profile check, not a new floor.
- Debug build reinstalled afterwards (`adb install -r -d`); app pid alive,
  crash buffer empty.
