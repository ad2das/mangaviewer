# Remove main-thread dependencies from viewer startup

The viewer's document download and browser IPC no longer wait for its initial
window drawing. This improves startup on the unchanged API 35 emulator, but does
not satisfy the four-second opening, sub-16 ms native P95, or no-stall targets.
The fixed case 6 comparison below is a startup and short-scroll diagnostic, not
qualification of all twelve cases or proof of physical display presentation.

The subsequent [system bar sampling control](navigation-sampling-performance-20260909.md)
removes that measured compositor workload but still misses the rendering target.

## Causes and changes

The NTK episode work previously awaited a main-thread service binding before
starting its independent HTTP document request. The document and preparation now
run concurrently inside the same coordinator-owned CONTROL operation. Both
`useDependency` scopes remain alive through authorization, and structured
cancellation closes the document and preparation before the parent returns.

The Android asynchronous A-record resolver introduced another main-thread wait.
Its file-descriptor readiness listener uses the main Looper before delivering
the supplied executor callback. Consequently, passing a direct executor did not
make it independent of window initialization. The existing blocking adapter could
wait for its 1.5-second timeout before reaching the system resolver. The adapter
now directly uses `Dns.SYSTEM`, followed by IPv4-first ordering and rotation
within each family. IPv6-only answers and IPv6 fallback routes remain available.
The platform DNS configuration and certificate verification remain unchanged.
The readiness mechanism is visible in the Android 15
[DnsResolver source](https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/tags/android-15.0.0_r1/framework/src/android/net/DnsResolver.java).

Finally, the app-side browser exchange previously ran on the main Looper. Each
already-admitted exchange now owns a callback thread, receives Binder messages
there, and joins that thread before returning. API 29 and later use the public
executor-based service binding; older Android versions keep the platform's main
thread connection callback and forward it to the exchange owner. Process-only
preparation binds on IO and closes its exact binding under synchronization.

The isolated service still owns the WebView on its own main thread. Document
identity, browser identity, challenge, ACK, manifest validation, document
retirement, image decoding, image quality and input processing are unchanged.

## Controlled measurements

The same case, emulator boot, APK verification and database-preserving wrapper
were used in baseline/candidate/candidate/baseline order. All four diagnostic
captures completed successfully for each comparison.

| Candidate stage | Baseline first full viewport (s) | Candidate first full viewport (s) | What the trace established |
| --- | --- | --- | --- |
| Concurrent document and preparation | 6.28, 6.06 | 5.89, 6.20 | Request start moved from 1.98–2.39 s to 0.18–0.21 s; the DNS wait remained |
| Plus synchronous system DNS | 6.04, 6.21 | 5.87, 6.08 | Document completion moved from 2.75–3.02 s to 1.12–1.51 s; browser IPC still waited |
| Plus independent browser IPC | 6.65, 6.75 | 5.73, 5.48 | Request start was 0.055–0.058 s and manifest readiness was 4.23–4.57 s |

These are separate comparisons with network and runtime variation, not additive
estimates of individual savings. For the last comparison, complete-frame native
P95 was 101.30 / 117.45 / 99.02 / 100.39 ms, and each run still contained at least
eight complete frames taking 100 ms or longer. First viewport timestamps refer
to submission, not physical display presentation.

A separate experiment disabled hardware acceleration only for the viewer's
ordinary UI window, retaining the original native GL image surface. The two
software-window runs took 5.91 and 5.93 seconds. This did not improve startup and
the debug manifest override was removed.

## Validation and evidence

Existing JVM suites passed: 118 app tests, 103 data tests, 164 NTK source tests,
and 32 focused coordinator/dependency tests (417 total). An Android test confirmed that system lookup of
`localhost` completes while the main Looper is occupied. Android IPC tests cover
successful exchange, cancellation, thread joining, preparation cancellation,
rejected binding and exact binding release. The real service retirement test
continues to reject retirement of a different request.

The final debug candidate (`935a1aa4a32d3386b4a23cb71e4c05326ee7f9ebe6d4c230306033e2bde3a50d`)
passed the eight Android DNS/IPC tests. Debug and release builds, release lint,
and the architecture gate passed. The release APK was built and linted, not
device-validated.

Further fixed-case checks on this debug candidate produced:

| Case | First source submission | Originals prepared | Diagnostic result |
| --- | ---: | ---: | --- |
| 1, NTK comic | 6.51 s | 60 / 202 | 15-second preparation deadline failed |
| 6, NTK webtoon | 5.89 s | 33 / 33 | Passed |
| 7, WFWF comic | 3.67 s | 16 / 16 | Passed |
| 10, WFWF webtoon | 3.47 s | 36 / 36 | Passed |

The earlier DNS-ordering-only APK also failed case 1's preparation deadline
(64 / 202 originals; first source at 6.92 s). No HTTP error status was observed
in the candidate case 1 capture. All 1,841 accepted adapter inputs across the
four candidate captures had complete ordered terminal receipts, with zero
cancelled inputs. Raw MotionEvent correspondence and display presentation are
separate verification boundaries.

Initial pixel verification **failed**. A follow-up run exported original bodies
before the next database restoration/cache reconciliation. Its first screenshot
in each of cases 6, 7 and 10 changed scene during acquisition. The second
screenshots had stable reported metadata, but comparison against the declared
source geometry also failed. The corresponding RGB mean absolute errors were
5.66, 15.53 and 5.91. The previous DNS-only APK also failed the same check
(case 7 RGB MAE 12.27), so this was not specific to the startup IPC change.
All failed reports and screenshots remain in the artifacts.

Investigation found an out-of-order diagnostic callback bug. In candidate case 7,
frame 221/input 549 had a recorded EGL composition latch before either screenshot.
Older frame 219/input 545 subsequently timed out and overwrote the diagnostic
`frame` property. Its anchor was 1.8125 display pixels behind the newer scene.
The corresponding differences in baseline case 7 and candidate cases 6/10 were
1.3184, 3.3975 and 2.8330 pixels. Independent image-offset analysis agreed with
these differences; no offsets were applied to any acceptance comparison.

`EngineViewerDiagnostics` now keeps the scene with the latest submission time,
while retaining every callback in its original observation order. A later failed
submission remains visible, and renderer token resets do not break time ordering.
The renderer, saved reading position and screenshot comparison are unchanged.
Two regression tests cover late timeouts and a later failed frame after a renderer
change; all six diagnostics tests and the full app JVM suite passed. New device
pixel checks are recorded separately under `startup-pixel-diagnostic-order`.
EGL latch metadata alone is not proof of a SurfaceFlinger buffer binding.

The corrected debug APK
(`4ac85f2074551f893909ea90fc04fb07503ded7cc49cff2385121c93032a6f86`)
passed all six unmodified compositor pixel comparisons, two screenshots each in
cases 6, 7 and 10. Both screenshots in each case reported the same submission and
had identical screenshot hashes. Maximum per-row RGB MAE was 0.417, 0.479 and
0.569 respectively, below the unchanged threshold of 4.0. This establishes pixel
agreement for those observed viewport captures, not full-episode source coverage
or exact SurfaceFlinger buffer/presentation timing.

Their first source submissions were 5.79, 3.07 and 3.37 seconds; complete-frame
native P95 was 104.52, 113.39 and 89.49 ms, with 9, 16 and 9 frames at least
100 ms long. None qualifies for the full performance goal. The corrected build
passed all 120 app JVM tests, release build, release lint and the architecture
gate. The corresponding release APK has not been device-tested. Database restore
verification and the unchanged boot identifier are recorded with these captures.

## Further parser experiment (not retained)

An expanded WebView trace and temporary client phase logs separated browser
construction from native HTML parsing. In one case 6 run, the first native parse
took 640 ms (398 ms DOM and 197 ms JSON), versus 70 ms for the next episode.
Payload staging and resolve dispatch took about 40 ms. Those temporary logs were
removed after capture.

A second experiment initialized the empty HTML parser on the existing parsing
dispatcher during the same opened operation's document request. All actual
documents still underwent the full normal parse and authorization. Its ABBA
first-source times were 7.84 / 5.28 / 5.98 / 6.08 seconds, but document completion
also varied: 2.40 / 1.59 / 1.97 / 2.17 seconds. First actual DOM parsing was
177 / 107 / 86 / 167 ms. All eight screenshots passed the unchanged pixel check.
This shows reduced cold DOM work but does not isolate a reliable end-to-end
startup gain from network/authorization variation. The extra parser preparation
was removed; source snapshots, APK receipts and results are retained under
`parser-startup-overlap`. Native P95 remained 103.85 / 111.80 / 125.14 / 112.07 ms.

Raw evidence is under `.artifacts/account-restore-20260908/`:

- `document-preparation-overlap/`: first comparison and Android 15 resolver source.
- `system-dns-overlap-comparison/`: second comparison.
- `browser-ipc-startup/`: third comparison, test/build output and browser stage log.
- `software-root-startup/`: rejected UI-window experiment.
- Each comparison contains `comparison.json`, installed APK receipts, raw HTTP
  phases, frames, startup timing and database restoration evidence.

All comparison wrappers restored and verified the original database and retained
the emulator boot identifier. No emulator, GPU, RAM, security, account or saved
reading-position setting was changed. The performance goal remains unmet.
