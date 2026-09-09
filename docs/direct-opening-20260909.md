# Defer the offscreen EGL surface when a reader already has a window

A cold attachment previously initialized a one-pixel EGL pbuffer, made it current,
then attached the actual reader window. The renderer now initializes directly on
the supplied window. Preparation or upload without a window still creates the
same pbuffer; a later detach creates it if necessary to retain the context.
Original image storage, RGBA8 format, upload policy, source geometry and input
handling are unchanged. Some offscreen allocation can therefore move to detach;
this is not a claim that every GPU allocation was eliminated.

The preceding trace recorded a 4,613.88 ms initial GL-owner task, including
context creation, pbuffer host-surface/color-buffer creation, binding and actual
window allocation. In parallel, the ordinary UI RenderThread initialized its own
context and buffers. The trace had 128 event-sorting errors and is diagnostic,
not physical-presentation or twelve-case qualification evidence.

## Measured scope

The unchanged direct-opening comparison uses fixed case 6, NTK webtoon
`827902/nv-827902-64`, with its original page-zero anchor. Its four entries are
direct, after real MainActivity preparation, prepared again, and direct again.
These diagnostic sequences preserve cached originals and restore the original
database/settings after each run; they are not a fresh-network twelve-case run.

| Run | First direct | Prepared 1 | Prepared 2 | Last direct |
| --- | ---: | ---: | ---: | ---: |
| Prior version, matched test APK | 10,824.03 ms | 6,575.00 ms | 8,867.75 ms | 8,302.66 ms |
| Deferred pbuffer, first run | 10,510.39 ms | 9,279.23 ms | 5,102.39 ms | 5,486.02 ms |
| Deferred pbuffer, stage diagnostics | 9,512.52 ms | 12,666.17 ms | 5,581.63 ms | 7,341.80 ms |
| Deferred pbuffer, plan diagnostics | 10,699.47 ms | 5,861.57 ms | 8,951.15 ms | 6,325.44 ms |

These values are launch to complete native submission, not verified physical
presentation. The first matched pair used identical instrumentation. Later test
versions export existing stage timestamps after the first complete submission;
the last also observes plan resolution on the work thread. They are diagnostic
runs with timing variation, not a statistical attribution of every difference to
the EGL change. Repeated direct opening improved in these observations, while the
initial direct opening remains near ten seconds and is not solved.

In the last run's first entry, plan resolution completed at 9,062.28 ms and the
session accepted it at 9,068.89 ms. Page zero was verified at 10,016.10 ms; final
native submission took 520.65 ms. Thus this run's initial delay occurred mostly
before plan resolution, not while delivering an already-resolved plan to the
main thread. Resolution includes source document, browser authorization and
parsing work; it does not measure download time alone. In the last direct entry,
resolution was at 2,404.12 ms but session acceptance was at 4,680.39 ms, so UI-thread
delivery was also a significant delay. Prepared entries have pre-click resolution
times; those intervals must not be counted as post-click delivery latency.

## Validation

The production debug APK is
`48a6f05f310405c20435aff98841db4b9cdf6be46e0a57511b3d74b91db37767`.
The final diagnostic test APK is
`9bd304b0d56215995a4a66084011381f1df1010b6115acfa8de8f4fb64e1fef7`.
All eleven native-owner tests passed, including deferred surface creation during
detach/rebind, resource retirement and fractional original-pixel output. Four
additional native lifecycle/pixel tests passed on the production APK with the
final instrumentation: injected upload/draw context loss and recovery, exact
source rows after replacement/reversal/context recreation, and full-viewport
static-versus-streaming pixel equality. All three candidate opening sequences
passed their exact-anchor and closure checks.
The commit validation passed all 528 JVM tests (127 app, 103 data, 134 engine and
164 NTK source), release build, release lint and architecture checks. Release APK
`3e44adf79324380321c00bd118e3fb7ba59faefeb91352a3189892f7b8fc28e9`
was built and linted, not device-tested. `installed-final.json` verifies the final
debug/test installation against original database and DataStore bytes and file
ownership from before the home-renderer investigation.

Evidence is under `.artifacts/account-restore-20260908/direct-opening-20260909/`.
The previous trace is in
`../reader-opening-transition-20260909/opening-display-trace/`. Original database
and DataStore bytes, ownership, emulator boot and settings remain preserved.
The first-image, frame-cadence and fixed twelve-case performance goal remains
unachieved. Personal Google-account verification remains deferred.
