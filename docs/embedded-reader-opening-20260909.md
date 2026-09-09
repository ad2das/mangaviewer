# Reuse the library window and complete original-image snapshots

Ordinary library navigation now attaches `EngineViewerScreen` to the existing
MainActivity window. The library composition and its scroll state survive while
its lifecycle collectors pause. Direct viewer intents retain their activity
entry point and use the same reader implementation. The reader restores system
bars on return and carries its exact source anchor through activity recreation.

Complete original-image episodes can also reopen from a saved access plan.
Metadata alone does not qualify: every original must pass the storage layer's
hash, length and dimension checks. A missing or corrupt original selects a fresh
online plan before presentation. A valid snapshot pins its originals until
navigation or close, including background suspension, and its page requests
cannot fall back to HTTP. Metadata is checksummed, atomically published and
bounded to 128 plans. Original image bytes, dimensions and source order remain
unchanged.

An experiment started NTK's local browser service while browsing. It moved
WebView startup before the tap but did not demonstrate an opening improvement.
That experiment was removed. The retained implementation already overlaps
browser initialization with the source document request for each online episode.

## Measurements and limits

The matched home-card test opens fixed case 10, WFWF webtoon `75104`, episode `2`,
at page `p0016` and exact Q32 source row `10303931963000`. Its predicted opening
belongs to a different work, NTK webtoon `827902`, episode `64`.

| Diagnostic | Tap to complete native submission |
| --- | ---: |
| Complete-cache implementation with separate viewer window | 4,251.00 ms |
| Same cache implementation with existing library window | 2,450.53 ms |
| Existing window plus later browser preparation | 2,454.81 ms |

The first matched pair reduced submission time by approximately 42%. Their
1080×2208 reader regions match pixel for pixel. This is one diagnostic pair,
not a statistical estimate across the twelve-case corpus.

The later run captured the physical display immediately after observing a
complete submission. Screenshot acquisition returned 3,983.763 ms after the
actual home-card tap. The entire reader rectangle `(0,66)-(1080,2274)` matches
the earlier matched baseline exactly; only the system navigation indicator
differs from the later steady screenshot. This establishes a conservative
four-second visibility bound for that single saved-position home opening.
The later navigation/lifecycle extension of that run timed out; its opening
evidence is retained separately from the failed test result. The test incorrectly
expected per-episode reading history. The application stores one last-read
position per series. After correcting that expectation, the extended lifecycle
and navigation test passed in 141.31 seconds on the same production APK. In that
repeat, native submission took 2,290.05 ms and the matching physical screenshot
returned at 4,691.91 ms, so it does not prove another four-second opening.

The separate NTK test opens case 5, webtoon `843194`, episode `38`, while the
predicted work is WFWF `75104/2`. It preserves original bodies and backs up only
the target's access-plan file to exercise online metadata resolution. It asserts
that neither the target plan nor its authorization exists before the actual
home-card tap. The existing implementation resolved the plan at 3,155.38 ms,
submitted a complete frame at 4,933.74 ms and returned matching screen pixels at
5,860.15 ms. The local-browser experiment measured 3,299.28 ms, 5,196.07 ms and
6,492.51 ms respectively. Reader pixels matched between implementations.

Normal cache maintenance changed the body-file inventory during both runs, so
this pair cannot isolate a statistical browser-preparation effect. It demonstrates
no achieved speed gain. Browser admission was already at 1,046.30 ms without the
extra browsing binding, versus 1,145.40 ms with it. The subsequent challenge,
ACK and manifest phases still occupied roughly two seconds in both runs.
These phases include provider/browser work and must not be described as network
transfer time alone. Evidence is in `ntk-local-preparation-20260909`.

An earlier scheduled capture returned at 3,195.68 ms but did not match the full
reader pixels. It does not establish early visibility. Native submission and
composition-latch timestamps remain distinct from physical screenshot evidence.

## Functional evidence

The pinned-cache candidate passed the actual bookmark control, system Back,
same-window library return, home-card reopening, background/resume with a new
frame, activity recreation and final exact-anchor persistence. Its recreated
reader pixels matched the pre-recreation view. The cache tests cover partial
episodes, same-length corruption, malformed metadata, wrong identities,
cancellation, publication failures, prevention of HTTP fallback and ownership
through the real session's acceptance/background/close lifecycle.
The extended test also uses the actual Next button and episode-picker selection,
verifies their shared window and requested episode, and checks that the exact
bookmark survives later reading progress. Reading a later episode replaces the
series' last-read position; selecting the earlier episode then starts at page
zero, matching the existing behavior.

Evidence is under `.artifacts/account-restore-20260908/` in
`complete-cache-opening-20260909`, `embedded-window-opening-20260909`,
`embedded-window-lifecycle-20260909`, `embedded-window-pinned-20260909` and
`embedded-browser-navigation-20260909`. The last directory contains
`home-navigation-01/physical-opening-pixel-proof.json` and the failed navigation
test result. The corrected pass is in
`embedded-reader-verified-20260909/home-navigation-01`. Each device wrapper
restores original database and DataStore bytes,
permissions and ownership, and checks the unchanged emulator boot.

The full first-image, P95 rendering, missed-frame and long-stall criteria across
the unchanged twelve cases remain unachieved.

## Final candidate validation

Production debug APK SHA-256:
`4f52a3cba21260eee8288a92e15781494f7d4e0f4d8bd86c6fb4eb0e046f6231`.
This candidate removes the unsuccessful browser-preparation experiment and
preserves a pending episode request when activity state is saved during reader
replacement. Cleanup failures return to the library with an error message.

The final release build, release lint and architecture checks passed. JVM tests
passed with no failures or skips: app 127, data 113, engine-v2 134 and source-ntk
164 (538 total). The surface-verifier tests passed all 38 cases, including exact
MainActivity producer binding and rejection of mismatched direct-entry hosts.

Device evidence for the final production APK is in
`embedded-reader-final-20260909`:

- `ui-ntk-first-01`: actual search and episode-row tap for fixed NTK case 1,
  immediate swipe, complete native frame and 90 accepted input deltas. The sealed
  input history contains 180 observations, with no cancellation or clamping.
- `home-navigation-01`: exact Q32 bookmark, Back, home-card reopen,
  background/resume, recreation with identical reader pixels, Next, episode
  picker, and state saving during pending episode replacement all passed.
- `smoke-direct-01`: the retained direct-intent entry passed immediate input,
  complete-frame presentation, the actual episode picker and clean shutdown.

These functional checks do not establish the twelve-case performance targets.

The final trace test APK is
`d0a1ac6af8c66c4bf538b832320c40d9788ce522b73f0baf427f7663ffda4a27`.
`embedded-reader-trace-20260909/trace-ui-actual-main-01/trace` contains the actual
catalog-entry recording, original raw-monotonic trace, native capture and three
exported originals. The device test passed in 98.231 seconds. Independent
verification passed the sealed HTTP, renderer and input histories, document and
catalog ordering, exact MainActivity surface producer binding, and captured
pixels. The pixel comparison uses the existing rasterization tolerance (maximum
row RGB mean absolute error 4; observed 3.281), not an exact decoded-pixel claim.
The bundle correctly fails complete source-response binding and whole-episode
inventory because this opening capture does not export and observe every page
in both recorded episode plans. Physical display timing remains unknown and
corpus credit remains zero.
