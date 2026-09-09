# Reader opening transition and upload-name preparation

The subsequent [direct-opening investigation](direct-opening-20260909.md) retains
these changes and defers the initial offscreen surface when a real window exists.

The reader now requests a zero-duration opening transition in its own activity.
The same-task home-card comparison previously kept the home window visible after
the renderer submitted its complete image. Android's window transition finished
8,895.69 ms after creation in the matched baseline; the modified activity finished
in 1,277.78 ms. The first post-submission screenshot then showed the selected comic.
System animation settings were already zero and were not changed.

The API 34+ receiver calls `overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)`
in `onCreate`; older versions use `overridePendingTransition(0, 0)`.
[Android's Activity reference](https://developer.android.com/reference/android/app/Activity.html#overrideActivityTransition(int,int,int))
documents this receiver-side API and the cross-task limitation. This result does
not establish the same gain for direct launches in a new task.

The existing common GL preparation also reserves the texture-name pool and unpack
buffer name before reader attachment. These calls allocate no image or buffer
storage. The claimed renderer keeps the same context and owner, and existing
cleanup deletes unused names. Original pixels, texture format, upload policy,
resource budgets, input processing and source coordinates are unchanged.

## Observations

All home observations selected fixed case 10, WFWF `webtoon:75104/2`, at page 16
and fractional source coordinate `10303931963000`; prediction was the different
case 6. The test preloads the selected page to establish the saved position.
These are diagnostic home tests with zero twelve-case qualification credit.

| Run | Click to complete submission | Interpretation |
| --- | ---: | --- |
| Animation-only candidate | 5,072.55 ms | Window transition 1,277.78 ms; comic visible in subsequent capture |
| Matched prior baseline | 4,415.26 ms | Window transition 8,895.69 ms; submission does not prove visibility |
| Transition plus upload-name preparation | 4,170.85 ms | Composition latch at 3,675.41 ms does not prove physical presentation |
| Final candidate with scheduled screenshot | 3,924.83 ms | Screenshot returned at 5,511.64 ms; four-second visibility unproven |

The last run requested its asynchronous screenshot at 3,700 ms. Capture began at
3,701.79 ms and returned at 5,511.64 ms. Its comic viewport `(0,66)-(1080,2274)`
is pixel-identical to the subsequent stable screenshot. This establishes the
captured content, but not acquisition before four seconds. Screenshot work can
also perturb GPU timing. The run's composition latch was at 4,211.19 ms; neither
latch nor swap completion is a verified physical presentation timestamp.

An additional cold-start preparation experiment was rejected and removed from
`EngineViewerRuntime`. Case 6's first direct open took 10,071.33 ms on the baseline
and 10,221.76 ms on that experimental candidate. The unchanged opening test's
four sequential results were:

| Direct / prepared sequence | Baseline | Experimental candidate |
| --- | ---: | ---: |
| Direct first | 10,071.33 ms | 10,221.76 ms |
| Prepared second | 5,901.61 ms | 5,689.64 ms |
| Prepared third | 8,769.89 ms | 9,310.22 ms |
| Direct fourth | 7,777.43 ms | 8,975.25 ms |

These runs preserved page-zero anchors exactly but did not support retaining the
extra direct-opening preparation. They are not measurements of the final APK.

## Validation and retained evidence

The final debug candidate is
`814fcceb73e6bab7ffb8cb2e5099b2e9998dacc3ebf217c595eadac583db9858`;
instrumentation is
`42d0072d403012984a5c969de2a7e43b391581a95ae175f5d925750a2e66d13c`.
All eleven native owner tests passed on these exact APKs, including repeated
preparation with no image storage, actual output pixels, renderer ownership and
resource retirement. The final home test verified exact anchor restoration and
persistence after closing the reader. Debug/test builds and architecture checks
passed. The preceding JVM run passed 127 app tests; the unchanged engine's 134
tests passed in the disabled-startup investigation.
Release build, debug and release lint also passed. The release APK hash is
`6a17661bfd57f4bf93fe1ac9311fe8047b010aa16183dbb870544ea3625917e3`;
it was built and linted, not device-tested. `installed-final.json` verifies the
debug/test installation and compares database/DataStore bytes and ownership
against the original pre-home-investigation state. The verified APKs are retained
in `verified-final-install` for subsequent diagnostic restoration.

Evidence resides in
`.artifacts/account-restore-20260908/reader-opening-transition-20260909/`:
`home-no-animation-01`, `home-matched-baseline`, `home-prepared-upload-01`,
`home-deadline-01`, both `opening-*` comparisons, and `native-final-confirmation`.
`deadline-capture.json` records capture timing;
`deadline-comparison.json` records the viewport equality check.

The same emulator boot was preserved, with system_server PID 21300. No AVD, RAM,
GPU, network, animation or security setting was changed. Each diagnostic restored
the original database and DataStore bytes and file ownership. Personal Google
account verification remains deferred.

First physical image within four seconds is not established. Direct opening is
still over budget, and long native submissions remain. Full fixed twelve-case
render P95, missed-frame, no-100-ms-stall, original-quality and input qualification
is incomplete. The overall performance goal remains active and unachieved.
