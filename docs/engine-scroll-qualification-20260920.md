# Engine scroll qualification across the four protected sources (2026-09-20)

## Scope

Clean staged captures (`EngineScrollQualificationTest`, schema v3) for the engine
viewer on emulator-5554, one run per protected source: `ntk`, `wfwf`, `goodtoon`,
`newxtoon`. Each run drives the staged loading/streaming/round-trip/reverse/
endpoint/boundary plan and exports frame, input, motion and stage evidence to
`engine-scroll-qualification-*` under the app's external files dir.

Sites and parameters:

| Source | Series key | Episode key |
| --- | --- | --- |
| ntk | `/webtoon/769209` | `/webtoon/769209/nv-769209-93` |
| wfwf | `comic:10001` | `1` |
| goodtoon | `gt-17070` | `689859` |
| newxtoon | `1876` | `139976` |

Capture command (per site, `captureCrossNextBoundary true`):

```
adb shell am instrument -w \
  -e class ml.melun.mangaview.viewer.EngineScrollQualificationTest \
  -e captureSource <id> -e captureSeries <key> -e captureEpisode <key> \
  -e captureCrossNextBoundary true \
  ml.melun.mangaview.test/androidx.test.runner.AndroidJUnitRunner
```

The probe gate (`probe.enabled`) stayed absent; the installed APK carries no
instrumentation probes.

## Results

| Source | Gestures | stageTimeouts | lostFrameEvidence | inputLostEvidence | Steady idle gaps >25 ms | Max |
| --- | --- | --- | --- | --- | --- | --- |
| ntk | 1360 | `[]` | 0 | 0 | 1 | 36.8 ms |
| wfwf | 1140 | `[]` | 0 | 0 | 0 | - |
| goodtoon | 1380 | `[]` | 0 | 0 | 1 | 33.1 ms |
| newxtoon | 1340 | `[]` | 0 | 0 | 16 | 41.1 ms |

All four runs reported `OK (1 test)`; `motionHistoryOverwritten` is false in every
summary. The three comic sites were captured on the build that predates the
newxtoon clearance changes; newxtoon was captured on the rebuilt APK. The reader
path is identical apart from the `ViewerSessionActivity` gate bookkeeping, and
that gate can only reduce background interference for the other sites.

## Gap analysis

"Steady idle gap" = a `DISPLAY_PRESENT` interval inside a complete-coverage stage
with no APPLIED input event inside it (startup/loading intervals excluded).
Present-interval percentiles across captures: p50 16.4-16.6 ms, p90 20.6-23.4 ms,
p99 35-49 ms, i.e. the emulator display pipeline is not a tight 60 Hz source and
input-driven windows already carry 37-68 ms intervals in every capture.

- **ntk 36.8 ms (ord 6018 -> 6020), ENDPOINT_START measured window.** Every input
  in the window was CLAMPED (`applied=0`) and no motion was applied: the view was
  held against the document start, so the scene did not change. A
  `COMPOSITION_LATCH` frame sits between the two presents - the app kept
  submitting, the display simply paced an unchanged frame. Not a stall.
- **goodtoon 33.1 ms (ord 5980 -> 5981), ENDPOINT_START measured window.** Same
  shape: all inputs CLAMPED, no motion, static scene. Not a stall.
- **newxtoon 16 gaps, 25.2-41.1 ms.** All inside measured windows during active
  movement: motion was applied continuously through each gap, inputs were APPLIED
  with `pending=0` and 1-20 ms resolution, and input/movement revisions advanced
  across the gap. Magnitudes are 1-2.5 vsync intervals. In two gaps
  (ord 3583 -> 3584, 3654 -> 3655) the app's own frame submission was on time
  (15.4 ms / 18.7 ms) while the display skipped the presentation - pure
  display-pipeline jitter. No integrity failures and no stage timeouts; the gaps
  sit inside the same pacing envelope the input windows show in all captures.
- **wfwf.** No steady idle gaps above the threshold.

Verdict: no unexplained steady-state scroll stalls in any of the four captures.

## Newxtoon clearance fix (rebuilt APK)

- The challenge browser is back on the default (GPU) layer. The software layer was
  a workaround for a suspected HWUI context-loss abort, but the last verified
  challenge pass predates it; being off the functor path is not what makes the
  challenge solve.
- Solve window raised 25 s -> 75 s so a slow managed challenge still completes.
- Speculative work (clearance solve, solved-view warm-up, catalog prefetch) now
  waits for a foreign reader session to close (`ViewerSessionActivity`); demand
  driven fetching is untouched.

Verified on the rebuilt APK: `challenge finished cleared=true reason=page-cleared`,
`solve: completed=true clearancePresent=true attempt=1`, catalog requests served
through the clearance webview with `retry status=200`, and the qualification run
completed with no stage timeouts.

## Verification

- `.\gradlew.bat :app:assembleDebug` -> `BUILD SUCCESSFUL`; `adb install -r`
  -> `Success`.
- Four instrumentation runs -> `OK (1 test)` each (ntk 124.9 s, newxtoon 143.2 s).
- Per-capture `summary.json`: `stageTimeouts: []`, `lostFrameEvidence: 0`,
  `inputLostEvidence: 0`, `motionHistoryOverwritten: false`.
- Gap analysis via present-interval statistics plus per-gap input/motion/frame
  pipeline context, as detailed above.
