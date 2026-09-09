# Resume from home and prepare a renderer for any selection

Home now shows up to five recently read works above the catalog controls. Each
card opens its episode directly at the current stored source anchor. The cards
come from local history, so catalog loading, catalog errors and the selected
provider do not determine their availability. The section's all-items action
opens the library's recent-reading tab. Empty history does not show empty cards.

The new `ResumeEpisode` action does not pass a reconstructed legacy offset as an
explicit position override. The viewer loads its exact source anchor, retaining
the existing legacy-history fallback. Bookmark-specific position actions remain
unchanged.

MainActivity hosts both home and library. On resume it now prepares one renderer
independently of the predicted episode. Preparation initializes the existing
one-pixel EGL pbuffer and common shader/quad on the renderer's own thread. It
does not download an episode, upload a texture, create an activity window, submit
a frame or change a device setting. Any selected episode can claim that same
owner, thread, renderer identity and context; a selection before preparation
starts follows the existing renderer creation path.

The bounded owner transfers even during preparation. Its callbacks are replaced
as one immutable set before the reader attaches its window. Cancellation drains
unclaimed work before another owner is created. Returning to the library defers
replacement until readers close, and memory-pressure cancellation clears that
request. Existing original-quality pixels, texture budgets, source coordinates,
input processing and Google/account code are unchanged.

## Validation and limits

All 127 app JVM tests passed, including six ownership, cancellation, preparation
failure and close-acknowledgement cases. All eleven native surface-owner device
tests passed, including preparation with zero textures/bytes, callback transfer,
unchanged renderer identity and a first real submission token of one. Debug and
instrumentation builds, release build, release lint and architecture checks
passed. The release APK was built and linted, not tested on the device.

The home integration test selects fixed case 10 (WFWF) from the actual home card
while the most-recent prediction is fixed case 6 (NTK). It verifies that the
selected work uses the common prepared renderer, preserves an exact fractional
source coordinate on page 16, and persists that coordinate on close. The final
run verifies the active reader window and includes a screenshot visibly showing
the comic. The image appeared later than the submission observation: the first
screenshot still showed home, while the later screenshot showed the selected
comic. Its click-to-complete-submission observation was 6,492.80 ms. This is a
functional pass and a performance failure, not evidence of a four-second opening.
Earlier functional observations of 1,693.59 and 1,808.09 ms were also submission
timings and did not establish first visible-image timing.

An unchanged opening comparison completed the baseline and candidate case-6
sequences before a later system failure interrupted case 10:

| APK | Unprepared submissions, ms | Prepared submissions, ms |
| --- | --- | --- |
| Previous 9f873f3 | 5,255.81 / 3,690.79 | 2,518.28 / 3,262.41 |
| Candidate b81093f | 5,884.07 / 2,717.27 | 2,606.26 / 2,322.63 |

These limited, variable observations do not establish a dramatic speedup, a
physical presentation time, or a completed twelve-case qualification. The
remaining display delay requires investigation separate from preparation.

## System interruption and retained state

During the comparison Android's system_server watchdog reported 67 seconds
blocked on the window-manager display path, including a synchronous layer
capture for a task snapshot. Activity/package services temporarily disappeared;
Android restarted its own framework. The emulator process and kernel boot ID
remained unchanged. This was not a requested emulator restart, and no AVD, RAM,
GPU, network, animation or security setting was changed. Measurements spanning
that framework recovery must not be treated as one uninterrupted comparison.

Recovery restored and verified the previous APKs, original database and original
DataStore settings. A lingering Settings-app ANR dialog was closed without
editing its settings. The initial home selector failures after recovery were
also investigated: an automatic-update dialog could cover the home navigation,
so the integration test now dismisses that real dialog before finding home.
The production automatic-update behavior was not disabled.

After validation, candidate debug APK `b81093f3bfd10ca72f43852f2a4deaefc6ec5b1230e2e08a167666adb07ea3e7`
and instrumentation APK `38f352c418452a6f7702d978a84e0fe7c6a00a1cb8aaf1ed3653c4baa873f03a`
were installed and verified. The database, WAL, SHM and DataStore settings match
their pre-task bytes and original ownership/modes. Boot ID remains
`d8e6c21f-09fb-4185-9eda-e16c3e03ebeb`; the app is stopped for the user's next open.
Personal Google-account verification remains deferred.

Evidence is under `.artifacts/account-restore-20260908/common-renderer-preparation/`,
especially `native-01`, `home-05`, `comparison/watchdog.txt`, `comparison/recovery.json`
and `installed-final.json`. The overall twelve-case performance goal remains unmet.

A subsequent [disabled-startup scene update](disabled-startup-scenes-20260909.md)
removes initial empty submissions and supersedes the installed APK above. It
retains home continuation and common renderer preparation; display delay and the
full performance criteria remain unresolved.
