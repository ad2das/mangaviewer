# Readback producer binding evidence

Status: eight diagnostic frames verified; zero real-episode qualification credit.

The successful report is
`.artifacts/engine-rewrite-20260906/readback-binder-release-20260906T052500Z/surface-binder-credential-verification.json`.
Its detached collection SHA-256 is
`7d3a51f614b36860b6d8539da4575ed451e8125385d05d73f923ca784e097cd1`;
the trace SHA-256 is
`08652a94122ac4743d0d27978f2a30f668d2f9243cb647faf400ca33a6a75c59`.
The earlier failed report is retained alongside it.

The verifier compares original pixels to eight raw framebuffer readbacks, then joins
each native token and EGL frame to its nested queue/acquire call. An exact kernel Binder
flow links that producer's send to SurfaceFlinger's receive. The same Binder debug ID
must have one buffer-release event on the receiving thread, and exactly one
`setTransactionState` handler within that dispatch. Transaction post time must be inside
that server handler, and its buffer frame, dimensions, caller UID and transaction PID
must match. The resulting layer/frame pair must have Queue and subsequent Latch evidence.
Surface recreation produces layers 1367 and 1375, with four verified frames each.

Caller UID comes from the credential SurfaceFlinger obtains for the actual Binder call.
It is not inferred from an app marker or accepted from a stale process-start snapshot.
[Android 15 SurfaceFlinger](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/android15-release/services/surfaceflinger/SurfaceFlinger.cpp)
captures `originUid` using `getCallingUid()` inside `setTransactionState`, and
[TransactionProtoParser](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/android15-release/services/surfaceflinger/Tracing/TransactionProtoParser.cpp)
serializes that value into the trace UID field. The kernel flow independently supplies
the sending process/thread. The report retains process metadata UID separately so its
pre-setuid value is visible rather than rewritten. A mismatched transaction UID fails.

Twenty-one normalized fault tests pass, including missing/wrong Binder identity,
unrelated ancestry, missing/early release, ambiguous handlers, wrong UID/PID/layer,
stale surface epoch and missing latch. Physical scanout is unmeasured: the report keeps
`physicalPresentationVerified=false` and `physicalPresentationTimeNanos=null`.
Latch and optional composition fence timestamps do not prove physical presentation.
