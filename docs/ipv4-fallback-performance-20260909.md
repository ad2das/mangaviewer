# IPv4 fallback ordering and remaining performance failures

This work follows the published `426d8b7` feature checkpoint. It fixes a measured
connection delay. It does not establish the requested four-second opening or
16 ms rendering targets across the fixed twelve cases.

The subsequent [startup dependency changes](startup-main-thread-dependencies-20260909.md)
replace the asynchronous A-record lookup and remove app-side main-thread waits.
The measurements below describe the earlier DNS-ordering-only candidate.

## Reproduced connection failure

Fixed case 6 is NTK `/webtoon/827902/nv-827902-64`. Several normal viewer captures
took approximately 12–13 seconds to receive its first document headers. A separate
connection-only test completed four requests in 766, 33, 66 and 62 ms, so that test
alone did not explain the viewer delay.

A temporary instrumentation observer then recorded OkHttp events inside the
actual viewer. The observer retained the existing DNS implementations, route
pools, dispatchers, request, timeouts and transport wrappers. It only replaced
event listeners on idle clients before opening the viewer. In the first bounded
attempt the delay recurred:

| Event | Seconds after call start |
| --- | ---: |
| DNS starts | 0.108 |
| DNS returns IPv6 followed by IPv4 | 1.926 |
| IPv6 connection begins | 1.989 |
| IPv6 connection times out | 11.992 |
| IPv4 connection begins | 11.993 |
| TLS completes | 12.065 |
| HTTP 200 headers arrive | 12.447 |
| Body completes | 12.512 |

`AndroidIpv4FirstDns` first attempts an IPv4-only lookup. If that lookup times out
or produces no addresses, its ordinary DNS fallback can return both families.
Previously the route-pool offset rotated the entire result. Offset 1 could put
IPv6 first despite the transport's IPv4-first policy. The trace records exactly
that ordering and a ten-second failed connection before a successful IPv4 route.

The fix partitions the resolved addresses by family and rotates within each
group. IPv4 remains first for every pool offset. IPv6 addresses remain available
after IPv4, and IPv6-only answers remain usable. No emulator networking, DNS
settings, TLS verification, original-image quality or account behavior changes.

Regression tests cover mixed answers, all three pool offsets, negative/overflow
offsets, the lookup fallback path, IPv6-only answers, singleton answers and empty
answers. Existing generic rotation coverage remains in place.

Three subsequent actual-viewer runs received document headers in 817, 1869 and
672 ms, without a failed connection. Their DNS answers contained IPv4 only, so
these runs demonstrate live compatibility but do not independently exercise the
mixed-family fallback; the regression tests cover that ordering deterministically.
The complete first viewport was submitted after 6.45, 6.42 and 5.95 seconds.
The four-second opening target therefore remains unmet, even with the ten-second
connection failure removed. These runs used the same observer and normal original
images, with the explicitly shorter startup/scroll diagnostic protocol.

## Rejected graphics alternatives

The same emulator boot was retained throughout. These are diagnostic comparisons,
not fixed-corpus performance qualification. Startup frames remain in raw files;
post-preparation and post-first-second summaries are explicitly separate.

Hiding system bars in the real viewer was tested in normal/hidden/hidden/normal
order on case 6. Native P95 after all launch originals were prepared was
105.45 / 83.14 / 123.51 / 105.29 ms. The two hidden-bar runs also failed endpoint
traversal checks. There was no repeatable sufficient improvement, and the
instrumentation change was removed.

A second debug-only prototype compared EGL with full-buffer CPU writes through
`ANativeWindow_lock`/`ANativeWindow_unlockAndPost`. Every frame wrote a 1080×2138
gray surface with a changing corner marker. It did not decode or qualify source
images. Both buffer acquisition and submission time were included. The order was
EGL/CPU/CPU/EGL, eight seconds per arm:

| Arm | Native P95 after one second | Submitted buffers | Unique SF latches |
| --- | ---: | ---: | ---: |
| EGL 1 | 79.38 ms | 171 | 62 |
| CPU 1 | 327.48 ms | 32 | 32 |
| CPU 2 | 296.76 ms | 34 | 34 |
| EGL 2 | 78.88 ms | 160 | 61 |

Every CPU frame, including startup, took at least 100 ms. Its whole-run latch-gap
P95 was 357.01/352.35 ms. CPU pixel filling was inexpensive relative to acquiring
and posting the buffer. This route was rejected before attempting source rendering.
The four screenshots showed the expected gray field and changing marker. The
trace reported no loss/error statistics. Buffer latches are not physical display
timestamps. The native prototype, activity, test and build entries were removed.

An additional candidate suppressed empty scene submissions while the renderer
was disabled before its first surface. Its regression test preserved later
texture retirement and input revisions. A normal/candidate/candidate/normal
comparison eliminated the two initial empty frames, but first complete viewport
times remained 6.32 / 6.17 / 5.87 / 6.15 seconds. Time from manifest readiness to
that viewport was approximately 1.13 / 1.19 / 1.24 / 1.16 seconds. The small total
variation did not establish a useful startup gain; complete-frame native P95
remained 114.06 / 108.61 / 97.97 / 92.21 ms. This candidate and its temporary test
were removed. All four short diagnostic protocols passed, with no performance
or physical-display qualification. Source, exact APK receipts and raw captures
remain in `initial-empty-comparison`.

## Final DNS-fix validation

The clean DNS-only change passed the project tests, debug and instrumented APK
builds, minified release build, release lint, and architecture gate. The test XML
contained 1,058 executions across variants, with no failures, errors or skips.
The pinned clean debug APK then passed all three existing Android source-network
tests, including live fragmented-TLS access to both NTK entrypoints and WFWF.
These device checks took 1.907 seconds. Original database and app settings were
restored and verified afterward.

`ipv4-fallback-final-candidate/hashes.json` identifies the clean artifacts:
debug APK SHA-256 `171d14f12c2cf44d78ec70877d25f51a157d6c34d36a51d234009444ce986b48`,
test APK `242fb7feb7daf6702591c3aa9dd047333973e02fb96e611f6c0da647a7c0f177`,
and release APK `cfbcf3be3ab98ba20f86d4c1e497657e4f5a5210f55338f58b986f9e2febb162`.
The release APK was built and linted, not device-tested in this follow-up.
These are local artifacts, distinct from the published feature-checkpoint APK.

## Evidence and limits

Artifacts are under `.artifacts/account-restore-20260908/`:

- `post-library-case6-trace`: unchanged feature-checkpoint viewer trace.
- `source-connection-probe`: isolated connection diagnostic and its archived source.
- `engine-connection-probe`: actual viewer connection failure and observer source.
- `ipv4-fallback-connection`: repeated actual viewer measurements of the DNS fix.
- `immersive-probe-02`: all four bar-visibility arms and `native-comparison.json`.
- `window-write-probe`: exact prototype APKs, source, images, trace and `comparison.json`.

The earlier `immersive-probe` attempt failed the test-APK identity check before a
viewer measurement, then stopped because its working directory was reused. It is
retained as a harness failure, not a performance sample. The corrected run used
separate database working copies and verified the installed test APK.

Each device wrapper restored the original database and verified its bytes and
metadata. The emulator boot identifier remained unchanged. Rejected prototypes
were replaced with the previously verified feature-checkpoint APKs. The DNS fix
still needs to be distinguished from broader rendering and startup acceptance:
removing a failed connection does not remove the measured graphics-driver stalls.
