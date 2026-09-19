# Newxtoon chapter loading speed + viewer replay — 2026-09-19

## Problem

With the app-only Cloudflare path (no host relay, see
`newxtoon-app-only-20260919.md`), three defects slowed or broke newxtoon:

1. The 회차 list (624 chapters) loaded its feed pages through
   `NewxtoonDocumentClient` at a 400 ms adaptive floor, so a cold load paid
   ~13.5 s for 31 feed pages.
2. The viewer built its episode list by walking `next_page` one request at a
   time in `EngineNewxtoonSessionWork.mergeChapterPages` (~8-13 s per open).
3. Every engine request failed with `403`: `EngineAppGraph` built
   `NewxtoonClearanceTransport` without the fifth `fetchPage` argument, so the
   WebView replay fast-nulled and the viewer showed
   `페이지를 불러오지 못했습니다 (HTTP 403)`.
4. Clearance solves could stall: `awaitClearance`'s `checkClearance` guard was
   sticky and only reset inside the `evaluateJavascript` callback. A callback
   lost to a navigation wedged the guard, and the attempt died to the 25 s
   timeout even though the page had already cleared (baseline: 3 attempts,
   ~72 s).
5. `NewxtoonClearanceTransport.execute` ran the full retry ladder (immediate +
   1.5 s + 3 s + fresh solve) before trying the solved WebView, which can
   serve the same request in about a second.

## Changes

- `NewxtoonDocumentClient`: adaptive floor 400 -> 200 ms, decay 250 -> 100 ms.
- `NewxtoonContentSource.chapters()`: per-series merged chapter cache (TTL
  5 min, LRU 4, mutex-guarded); a cache hit fires `onPartial` with the full
  list immediately. The fetch/merge body moved to `fetchChapters()`.
- `EngineNewxtoonSessionWork.mergeChapterPages`: parses the advertised total
  and feed page size from the series document and fetches `(2..lastPage)` in
  parallel windows of 4, falling back to the sequential `next_page` walk on
  `IOException` (which covers `PageHttpException`).
- `EngineAppGraph`: passes `newxtoonClearance::fetchPage` so engine requests
  replay through the solved WebView, matching the legacy catalog transport.
- `NewxtoonClearance.awaitClearance`: the clearance-probe guard is time-bound
  (1 s) instead of sticky, so a lost callback cannot wedge the attempt.
- `NewxtoonClearanceTransport.execute`: after `solve()` one plain retry and a
  WebView replay come first; the challenge retry ladder is the last resort.

## Verification (emulator 5558, app-only path, native 1080x2340)

Baseline build 84105a9a2 vs new build, same device, same series
(`/comics/8067`, 624 chapters, 32 feed pages):

| Step | Baseline | New |
| --- | --- | --- |
| Clearance solve | 3 attempts, ~72 s | attempt 1, cleared `page-cleared`, ~15 s |
| Detail 회차 feed walk (source path) | 13.48 s (page 2 request -> page 32 request) | 9.04 s request window, 9.62 s to last serve |
| Viewer episode walk (engine path) | sequential, ~8-13 s | 6.56 s, parallel windows of 4 |
| Reopen detail (stale DB snapshot) | full re-walk | 0 feed requests (cache hit) |

- Viewer renders chapter content again (warning splash + first webtoon panel)
  instead of the 403 error.
- No `429`/`SourceThrottledException` lines during any of the walks; only the
  first request of a session pays the challenge dance.
- No crashes in the crash buffer after the full pass.
- The 회차 list shows `624개` and starts at `624화`.

Environment: emulator `MangaViewerApi35` (5558), debug build, dark theme,
app-only (no mitmproxy/relay involved). Numbers are emulator-bound; request
spacing on device is dominated by the WebView replay path, not the pacing
floor.
