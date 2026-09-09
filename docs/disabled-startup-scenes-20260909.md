# Do not queue blank scenes before the renderer's first attachment

The subsequent [reader opening investigation](reader-opening-transition-20260909.md)
retains this runtime fix, addresses the home window transition, and supersedes
the APK installation recorded below.

`EngineRenderRuntime` previously submitted empty scenes for metadata updates while
rendering was disabled. The GL owner retained the latest one and drew it during
attachment; more disabled updates could queue another empty buffer before the
first image. These buffers contained no source pixels and were not first-image
evidence.

The runtime now waits to submit while disabled until it has previously submitted
a scene. Once it has submitted anything, the existing clearing behavior remains,
including when a partially populated scene never reached complete coverage.
Attachment, GL ownership, original image decoding, input handling and source
coordinates are unchanged. This replaces an intermediate owner-side duplicate
blank filter; that filter and its extra helper are not in the final source.

## What the investigation established

A home-card test selects fixed case 10, WFWF episode `webtoon:75104/2`, at the
saved fractional source position on page 16, while case 6 is the predicted work.
All observations below use the same emulator boot and restored original database
and settings. This test preloads the selected page to establish its saved anchor;
it is not a cold-network twelve-case qualification.

An initial 100 ms main-thread sampler observed two `HardwareRenderer.nSetStopped`
intervals spanning 633–1,773 ms and 2,649–4,297 ms after the click, plus
`nSyncAndDrawFrame` at 1,918–2,547 ms. These are sampled spans, not exact call
durations. No `SurfaceView.waitForTransaction` stack was sampled. The
[Android 15 RenderProxy implementation](https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r1/libs/hwui/renderthread/RenderProxy.cpp)
confirms that `setStopped` waits synchronously for RenderThread work. The earlier
Callback2 hypothesis was therefore not adopted as the cause of this run.

A separate Perfetto diagnostic identified window-buffer allocation, window/context
creation, emulator command responses and swaps on RenderThread and the GL owner.
Before the first image, that run submitted two empty buffers, taking 584.93 and
1,333.65 ms respectively. The second included a 940.51 ms `rcCreateSyncKHR encode`
interval and 389.34 ms buffer dequeue. First content was token 3. Trace processing
reported 65 event-ordering errors; these diagnostics are not qualification data.
The captured frame-timeline entries do not prove presentation of the identified
source buffer. A direct native-stack attempt reported `debuggerd: root is required`;
no root or security-setting change was made.

## Measurements and validation

| App / run | Click to complete image submission | Empty buffers before content |
| --- | ---: | ---: |
| Initial baseline diagnostic | 6,466.69 ms | Not exported in this first sampler build |
| Intermediate duplicate-only filter | 4,838.75 ms | 1 |
| Final disabled-startup change, first run | 5,411.60 ms | 0 |
| Baseline with the final test APK | 5,839.16 ms | 3 |
| Final disabled-startup change, second run | 5,245.05 ms | 0 |

The last three runs used the same instrumentation APK. Each final-candidate run
had one successful image submission, with three original-image placements and the
exact expected anchor. There was no blank buffer to remove from its timing.
Its native submission still took 1,645.20 / 1,259.07 ms. The isolated intermediate
filter observation is not evidence that it is reliably faster than the final
change. The final change removes the disabled startup submissions at their source
and leaves the established GL owner policy intact.

The first post-submission screenshot still showed home; the later screenshot
showed the correct selected comic. Submission and composition-latch observations
therefore must not be reported as first visible-image times. Actual display delay
remains unresolved, and Android still logged transition commit timeouts.

All 134 engine JVM tests and 127 app JVM tests passed. New tests cover disabled
startup, first real coverage, and clearing a partial scene on disable. All eleven
native owner device tests passed on the final candidate, including fractional
pixel output and resource retirement. Both home runs verified the common renderer
identity, exact source coordinate `10303931963000`, and persistence after close.
Debug/test builds, release build, release lint and the architecture gate passed.
The release APK was built and linted, not device-tested.

No AVD, RAM, GPU, network, animation or security setting was changed. Kernel boot
ID remains `d8e6c21f-09fb-4185-9eda-e16c3e03ebeb`, with system_server PID 21300
throughout this investigation. The earlier framework restart remains documented
in the preceding home-preparation report; no new restart was observed here.

The final debug APK is
`ea1e2c7f0bdde34ed7544fd6278f9dfd5163bab5988ae6acf77a4a37b16051e3`;
the instrumentation APK is
`77e16ac62cf14eccddcca162ede84d112539a272627c6acbef262bb5778af7db`.
Installation and original database/DataStore byte-and-ownership verification are
recorded in `installed-final.json`. Google-account verification remains deferred.

Evidence is under
`.artifacts/account-restore-20260908/surface-redraw-20260909/`, including
`home-display-trace`, `opening-comparison.json`, `home-disabled-startup-base`,
`home-disabled-startup-01`, `home-disabled-startup-02` and
`native-disabled-startup-final`.

The first-image four-second criterion is not established. The observed long
submissions do not meet the no-100-ms-stall requirement. Full twelve-case render
P95, missed-frame, input and original-quality qualification remains incomplete.
The overall performance goal is not complete.
