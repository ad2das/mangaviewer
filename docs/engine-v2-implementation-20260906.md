# Engine replacement implementation contract

Status: IMPLEMENTING; final qualification remains 0/200. This implements the user-approved
UI-preserving replacement. It is not a qualification result. The immutable working-tree
backup is `.artifacts/engine-rewrite-20260906/baseline-20260906T024821Z`.

The completed Astra Pro review is Oracle session `design-review-request-user-explicitly-3`.
The coordinator accepted the ownership/replacement findings; its suggested RAM/16 GL
budget and arbitrary 30-second page cutoff were rejected in favor of the approved policy.

## Contracts and ownership

`engine-api` contains Android-free contracts; `engine-v2` contains newly implemented
session and global work ownership. Neither depends on the previous `viewer` or
`viewer-content` implementation. Existing IDs and mature platform/HTTP/database
dependencies retain compatibility. Old owners are removed after the live vertical slice
and full feature migration, before final qualification. One engine runs per APK.

Root alone owns design, implementation, review, integration, builds, ADB, performance
windows, and final judgment. The user's September 6 instruction prohibits sub-agents.

## Design review before integration

The user's renewed requirement is to design the replacement from first principles,
avoid tangled ownership, and demonstrate optimization. Existing replacement files
are candidates, not approved merely because they compile or pass unit tests. This
contract describes required behavior; integration and qualification remain incomplete.

Each mutable resource has one authoritative owner. Ports communicate immutable values,
ordered commands, or explicit leases; they do not expose mutable registries. Splitting
a class across helper files is not architectural separation when all helpers mutate
the same state. Such helpers remain one ownership boundary and must share one transition
protocol. Introduce a separate component only for an independently specified responsibility.

| Owner | Authoritative state | Permitted effects | Must not own |
| --- | --- | --- | --- |
| Session reducer | document order, source anchor, input receipts, geometry revision | none; returns demand and visible regions | HTTP, files, coroutines, textures |
| Session runtime | session subscriptions, generation, surface attachment and close acknowledgement | reconciles reducer demand against work subscriptions | global retries, provider parsing, cache publication |
| Application work coordinator | logical keys, live subscribers, attempt identity, admission and cancellation | runs registered operations; disposes final results | document position, SQL, provider-specific fallback choices |
| Provider adapter | parsing and explicit access plans | transport/browser calls admitted through the coordinator | private executor queues, global permits, viewer state |
| Raw storage | journal, immutable publications, durable references, file leases and pins | filesystem and database transactions | downloads, retry timing, UI readiness |
| GL runtime | EGL context/surface generation, textures, upload fences and readbacks | GL calls on its sole owner thread | source lookup, disk eviction, session navigation |
| App graph | construction and application lifetimes | connects ports and closes application owners | mutable business policy or feature execution |

The feature flow is UI command -> reducer -> runtime demand reconciliation -> logical
work -> immutable file lease -> decode -> GL upload -> frame. Completions re-enter the
session only with the matching session generation. Every cross-boundary asynchronous
completion also carries the applicable auth, attempt, renderer and surface identity;
stale results release their resources without changing current state.

### Work transition contract

| State | Owns physical permit | Allowed next states |
| --- | --- | --- |
| QUEUED | no | RUNNING, DONE after last subscriber cancellation |
| RUNNING | yes for its declared physical domain; CONTROL has no physical allocation | RETRY_WAIT, READY, RETIRING, DONE after executor completion |
| RETRY_WAIT | no | RUNNING after deadline and admission, DONE after cancellation acknowledgement |
| READY | no | RETIRING after final lease release |
| RETIRING | until the underlying operation's cleanup finishes, if still held | DONE after disposer/fence acknowledgement |
| DONE | no | terminal; a later request may create a new record only if its auth epoch is valid |

State transitions occur under the registry mutex; user callbacks, I/O and disposer
execution occur outside it. Effects carry captured immutable data, never a mutable
record field reread after unlocking. Cancellation closes admission first, signals the
executor second, and removes ownership only after acknowledgement. Authentication
invalidation records a monotonic per-principal retired epoch before cancelling work,
so a delayed old request cannot recreate work after its former record disappears.

Dependency handling belongs to the coordinator, not to nested provider queues.
CONTROL parents may await prerequisites without holding a network/body/decode permit.
`WorkContext.dependency` now registers child ownership atomically with cycle checking.
Only CONTROL parents may call it, and principal/auth epoch must match. Promotion follows
the dependency graph. An attempt retains its children until its result disposer finishes;
failed attempts release them before retrying. Finished contexts reject new ownership,
and an execution returning with unfinished dependency calls fails and releases its children.
Scoped `useDependency` borrows release before the parent result becomes ready; if child
cleanup or delivery fails, an explicit abandoned-result disposer handles independently
created resources. Fourteen dependency tests currently pass.

`EnginePageWork` constructs a cache lookup -> prerequisite -> body -> publication graph
without another executor or queue. Complete cached files do not activate provider access.
Open response streams receive priority promotion. Only HTTP 200 can publish a full page;
authentication/throttling responses do not trigger an image-mirror walk. A storage failure
does not initiate another image transfer. Cancellation across body preparation retains
cleanup responsibility until storage acknowledges it. The WFWF parser, coordinator and
actual file storage are connected in local integration tests; transport is still a fixture.
This is not an AppGraph/UI integration or real-content performance result.

`EngineEpisodeWork` shares immutable source documents across independently requested
navigation plans and parses them on an explicitly supplied dispatcher. Advertised and
actual document size are bounded; an incomplete Content-Length response cannot produce
a partial manifest. Returned plans must match the requested episode/epoch and fetched
document identity. Thirteen local integration tests cover episode/page execution, including
the complete document request -> WFWF plan -> original body -> actual file publication
path. The existing 14 storage cases also pass. Production still uses the previous app
runtime; these results provide no real-episode corpus credit or latency claim.

### Session and graphics integration in progress

`EngineSessionRuntime` now translates reducer requirements into global coordinator
subscriptions. Its owner-thread work set retains cancelled entries until actual cleanup,
so same-key foreground returns cannot overlap retiring operations. Generation checks
reject stale completions. Failed requests remain failed until explicit retry; retry during
a failure callback waits for its original subscription to finish. The runtime preserves
deferred input receipts and emits file-free content metadata for a renderer to acquire
through its own work graph. It performs one-page lookahead/reversal prefetch as ordinary
session demand. This policy has not been qualified for latency or memory on real content.

Seven runtime tests pass, covering cold input conservation, shared-consumer survival,
generation replacement, foreground retirement, repeated close, and retry behavior.
`EngineTileWork` borrows immutable files and CPU pixels only through upload completion;
the independently retained upload dependency owns the GPU result. Three graph tests pass,
including cancellation while an owner still reads pixels and cancellation on decoder
dispatcher return. Three tile arithmetic tests verify the NDK's full-image raster/crop
dimensions and checked allocation arithmetic.

`NativeEngineImageDecoder` connects this contract to the existing NDK decoder. Two
instrumented tests passed on emulator-5554/MangaViewerApi35 using synthetic images.
The debug app and test APKs built and were installed; prior installed APK is preserved in
`.artifacts/engine-rewrite-20260906/session-graphics-root`. That checkpoint covers 98 JVM
tests plus these two Android tests, with architecture verification over 263 production
files. No GPU budget, seam/pixel accuracy, live provider throughput or corpus completion
follows from those tests.

`EngineSurfaceOwner` now implements the new uploader on its sole GL thread. It borrows
native CPU pixels until the native upload finishes, tracks scene-retained texture
retirement, waits for allocation capacity without polling sleeps, and acknowledges release
only after the native texture is absent. Surface attachments have separate identities;
context recreation invalidates the renderer epoch. Repeated close destroys native ownership
and joins the owner thread. C++ enforces a configurable texture-only allocation limit and
reports live/retiring texture counts and bytes. Upload/readback buffers and measured PSS
remain outside that texture-only limit and require further accounting and qualification.

Five Android GL fixtures passed on emulator-5554/MangaViewerApi35, covering allocation
wait/cancellation, borrowed pixels, scene retention, same-size Surface replacement and
repeated close. They use synthetic SurfaceTexture consumers, not UI composition. Native
swap success is recorded independently from presentation timestamps; missing or dropped
timestamp evidence remains missing/dropped, even after a successful swap. The initial
timestamp-kind assertion failure log is retained alongside the final passing log. These
fixtures establish no SurfaceFlinger display proof or corpus credit. Debug app/test builds
and the 267-file architecture gate passed. Evidence and exact APKs are in
`.artifacts/engine-rewrite-20260906/gl-owner-root`.

`EngineTilePlanner` now selects all visible original-row bands before bounded neighboring
speculation. Its allocation estimate matches actual full-raster crop bytes. It projects
the decoder's raster crop back through the exact source aspect ratio, retaining signed
1/1024-pixel screen coordinates. Insufficient visible capacity is an explicit failure;
resolution is not reduced to fit. `EngineRenderRuntime` reconciles GPU work, replaces
obsolete scene references, rejects generation/renderer mismatches and flushes scene
ownership before close. Five planner and three render-lifetime tests passed; the engine
suite now contains 81 passing tests.

The new native submission path carries coordinate units explicitly (1 for existing
submissions, 1024 for new engine scenes). Visibility and vertex calculations retain that
scale. The owner now supports bounded native readback receipts polled on Choreographer;
native context destruction settles pending readback owners. The scene-taking capture API
is for explicit isolated verification, not for replacing a newer interactive scene with
an earlier snapshot during real-content qualification.

Six Android owner tests passed, including an actual GPU readback of a 0.75-pixel rectangle:
the first screen row is background and the next is the original color at every column.
This distinguishes fractional placement from integer truncation. Two existing renderer
JNI/deferred-presentation regression tests also passed. The build and 271-file architecture
gate passed. Exact APKs, logs and source hashes are under
`.artifacts/engine-rewrite-20260906/tile-render-root`.

These results do not establish arbitrary-image tile-seam accuracy, real-content throughput
or physical presentation. Full GPU/PSS accounting, live next-frame readback/SF binding and
the normal viewer switch remain incomplete.

### First live WFWF vertical slice

`EngineAppGraph` now constructs the application work coordinator, journaled storage,
position store and direct WFWF work factories. The new pure episode-catalog planner supplies
pagination and ordered rows without the previous provider executor/cache. Missing requested
episodes fail navigation instead of becoming a fabricated terminal boundary. The Android
`EngineViewerRuntime` connects the real SurfaceView/input host to both new runtimes and the
GL owner. Source anchor dimensions remain available after resize/close so progress can be
saved as both source coordinates and compatible legacy screen offsets. Writes are serialized
within the session and close waits for content, graphics and position effects.

A debug-only live entry hosted the existing mandatory `comic:10001 / 1` regression. Its
test injects a real UI swipe immediately after entry, before awaiting document/image
readiness. The first run produced an image submission at 3159.54 ms but had an unfilled
viewport region and failed during close: EGL observed a discarded Surface before the Java
callback arrived. Native result zero (absent/bad Surface/window) now has a separate surface
loss transition, clears scene references and notifies the host to reattach only when its
Surface is valid. It remains an unsuccessful frame, not successful presentation evidence.
Seven Android GL tests, including explicit window abandonment, passed after the fix.

The same live regression then passed full viewport coverage and close. Its observation
record reports first image submission at 2137.9478 ms, full viewport submission at
2678.5065 ms, 95 accepted input revisions and zero pending input. The screenshot was
inspected and showed filled content. Post-close coordinator ownership and file leases,
prepared bodies and pending publications were all zero. This repeat used normally retained
cache bytes and reading progress from the prior run; it is not cold-start performance
evidence. Timestamp evidence remained UNAVAILABLE, physical presentation was not proved,
and corpus credit remains zero. Logs/screenshots and exact passing APKs are under
`.artifacts/engine-rewrite-20260906/live-ui-root`.

The ordinary ViewerActivity still uses the previous runtime. NTK, normal toolbar/bookmark
wiring, complete legacy-cache import, application-wide shutdown, memory-pressure response,
complete gesture/frame telemetry and all-source background work migration remain pending.
The live debug host is a functional integration checkpoint, not the final UI replacement.

### Storage and shutdown protocol

Storage publication progresses through staged -> durable file -> committed database
reference. Recovery is idempotent at every crash boundary. Leases/pins are reserved
before validation that suspends or releases the storage lock. No consumer sees a
partially written file; no eviction unlinks a live leased publication. A failed new
publication leaves the previous committed publication usable.

Session shutdown first rejects new commands, then closes its work subscriptions,
awaits their release and releases GL ownership on the GL thread. It acknowledges close
only after real I/O/decode cancellation and GPU cleanup. Shared work remains alive for
other sessions or offline consumers. Application shutdown additionally closes global
admission and awaits all remaining work owners. Repeated close returns the same result,
including cleanup failure; it must not fabricate a successful zero-resource snapshot.

### Optimization and implementation gates

Measure input processing, admission wait, provider authorization, first-byte/body time,
storage publication, decode, upload and composition separately. Queueing time and
execution time must remain distinguishable. No arbitrary sleep, prewarm, fabricated
geometry or test-only cache state may improve a reported result.

Before optimizing a path, establish its workload, correctness invariant and baseline.
Then compare latency distributions, bytes, allocations, peak ownership and post-close
residuals on the same inputs. Prefer eliminating duplicate operations/copies and
unbounded retention before adding concurrency or caching. Bound queues and retained
resources; overload and missing geometry are explicit outcomes. A data-structure
optimization must preserve priority and input order and show a measurable benefit.

Integration gates, in order: ownership/state-machine review; concurrency and crash
boundary tests; one complete WFWF live UI flow; other providers and offline contention;
removal of old owners; exact signed-release qualification. Diagnostic pixel/Binder
fixtures are supporting evidence with zero corpus credit. Missing identity evidence
fails verification; it is not repaired with permissive UID or timestamp heuristics.

The session owner is its construction thread. Screen coordinates use existing 1/1024
pixel units; source anchors use Q32 original-image coordinates. Unknown geometry is
unavailable, never invented. A normal content arrival does not reset the source anchor.
Every ordered input gets an acceptance receipt. Pending input remains ordered across
direction changes and resolves using the original acceptance timestamp. Actual start/end
clamps require a known document boundary. A close cancels remaining deferred receipts.

The work coordinator owns all logical requests, subscribers, promotion, retry schedules,
and cancellation acknowledgement. CONTROL work holds no physical permit. BODY work
counts toward both network and body limits. NETWORK counts only network; lower-priority
network work also consumes the background allowance. Initial limits are 6/4/2 network,
body and background, 2 decodes, 1 storage writer and 1 GL submission lane. Resource
cleanup is included in retirement: cancellation does not decrement active ownership
until the executor and final disposer really finish. A key has at most one executor;
auth epochs invalidate that owner instead of creating a parallel key. Immutable raw-file
leases, not consumable streams, are shared. A result is disposed exactly once when the
last consumer releases it. Repeated close awaits the same completion. Retry delays are
explicit provider policy, do not reset on promotion, and consume no physical permits.

## First live integration

First prove two unique images across 8-12 frames and same-size Surface recreation.
Capture newly exposed strips from the exact default framebuffer before its swap using
asynchronous PBO readback. Independently compare actual bytes against original pixels;
bind each capture to the frame token and surface epoch. The primary AOSP EGL frame-ID
mapping must be checked for the actual environment, and exact owned layer/producer
identity is required. Timestamp proximity or an application identity Boolean is not
proof. Missing physical timestamps remain unavailable under OBSERVABLE_RENDER_V1.

Then connect existing UI -> WFWF comic10001/1 -> real document/image -> new engine ->
all rows in both directions -> bookmark -> acknowledged close -> exact normal-cache
deep resume. No cache injection, prewarming, readiness pause, or slowed gestures.
Expand to WFWF webtoons, NTK both categories and concurrent offline/catalog/artwork.

## Storage and release completion

The root-authored storage implementation now lives in `data/engine`: `EngineRawStorage`
owns publication and lease admission; `EnginePageFiles` owns byte validation, confined
paths and filesystem durability; `EnginePublicationIndex` supplies atomic database
operations, implemented by Room. Position persistence has its own port. The previous
unintegrated storage draft under `.artifacts` is not production code.

JVM checks currently cover 14 storage cases, including injected failures at all five
publication boundaries, repeated recovery, immutable revision conflict, same-length
corruption and repair, leased-file protection, stream/return cancellation, uncertain
journal writes, staging tampering and foreign paths. Three Android tests passed on
emulator-5554/MangaViewerApi35: actual fsync/rename with Room reopen at each boundary,
transaction rollback when journal deletion is aborted by a test SQLite trigger, and
schema-one migration/legacy position preservation. These are isolated fixtures; they
do not establish real-content throughput, process-kill/power-loss survival, or corpus
completion. The APK SHA and test log are under
`.artifacts/engine-rewrite-20260906/storage-publication-root`.

Failed transfer cleanup retains explicit ownership until recovery retries deletion and
directory synchronization; a failed cleanup cannot silently become an unreachable file.

Prepared bodies and committed reads are checked against actual SHA-256 bytes. No
unbounded "already validated" cache bypasses integrity checks. `trimTo` serializes
deletion with lease admission and returns retained bytes when active pins prevent the
target from being met. Recovery protects active transfers, prepared bodies, committed
paths and leases when collecting private orphan files. Durability precedes DB publication.
The extra validation I/O must be measured in the live workload before claiming optimal
throughput; runtime consumers retain leases across frames instead of reopening per frame.

Preserve schema/IDs/formats and add source-anchor/publication records through migrations.
Do not reinterpret missing legacy viewport metadata as known source coordinates.
Publish durable staged files before final DB references, retain the previous complete
version until commit, and recover interrupted publications. Remove destructive startup
legacy cleanup. Pins and open leases prevent physical eviction. Validate a schema-compatible
rollback artifact and preserve package/update compatibility.

Release must be optimized and signed with a private production key outside Git. Final
measurement uses the exact release APK to be published; no post-verification manifest
rewrites or debug APK substitution. Tests, lint, debug/release builds, architecture checks,
historical regressions, fresh 4x10x5 corpus, main push and CI all remain required.

GL memory stays min(RAM/32,384MiB), owned-process PSS increase min(RAM/16,768MiB),
fallback128/256MiB. Same-cache residual increase <=64MiB and ended-session ownership0
remain gates. Exact source rows, original pixels, input order, raw latency attribution
and the frozen policy remain mandatory; a partial rewrite or green unit suite is not
completion.

NTK access and IPC ownership checkpoint (2026-09-06): NtkAccessPlanner now parses native source documents into immutable engine access plans and validates protected manifests against the exact descriptor. Its eight cases are included in the 156 passing NTK JVM tests. This planner is not yet connected to the normal viewer.

The browser service now optionally replies with MSG_REQUEST_DETACHED after cancellation removes the request subscription and its matching completed delivery. Shared subscribers no longer quiesce each other's active document. Missing subscriptions are idempotent; invalid IDs receive an error. The reply explicitly does not establish Chromium destruction, stopped scripts, physical network cancellation, or reclaimed process memory: NtkBrowserHost.park only changes rendering policy and removes the document-start script. A full new-engine browser owner is still required.

Evidence: .artifacts/engine-rewrite-20260906/ntk-cancellation-root contains the source archive/hashes, 156 passing JVM results, and the five passing Android service/cancellation checks on emulator-5554 / MangaViewerApi35 (0.624 seconds). Archived library test APK SHA256: 63b52a63951f76c5ba744634756574804e5c78bf6d901eca159c9d0d260d8a8d. Architecture gate passes for 277 files; source-ntk diff whitespace check passes. An initial structure check failed at 406 service class lines; extracting the cancellation owner resolved it. Main viewer APK was not replaced, no provider content was loaded, and the final corpus remains 0/200.

NTK new-engine UI checkpoint (2026-09-06)

The new engine now opens protected NTK content through EngineNtkSessionWork. Its graph fetches a shared native document, parses the exact viewer descriptor, runs NtkEngineBrowserClient in the sole global BROWSER slot, validates the captured protected image sequence, then permits native image work. NtkEngineBrowserService reuses the browser parsing/platform primitives without invoking the legacy page service, replica race, static-resource prefetch executor, warmup, adjacent preflight or delivery replay cache. It owns one request, supplies a document-ready handshake after identity cookie application, emits provider ACK and manifest messages independently, and acknowledges retirement only after closing its document, returning from WebView.destroy(), and draining its own startup/cookie callbacks. The client awaits both messages and retirement on success; cancellation/error also await retirement before unbinding. The retirement reply is not proof of Chromium PSS reclamation or physical display time.

SourceDocument now retains deep immutable response headers. Previously the new engine dropped Set-Cookie before browser replay. A separate replay digest includes final URL, headers and body SHA; browser proof and global work identity bind that complete response plus episode/epoch. The original body SHA remains available as the document hash. Only a matching completed browser proof removes the access prerequisite from the page plan.

The independent NTK catalog planner requires an exact API total or reads all linked series-document pages without the legacy silent pagination clamp. Current HTTP/JSON API errors still fail the operation; unsupported-API fallback beyond an incomplete valid response and origin migration remain to be addressed. Catalog parser ownership and complete live navigation need broader validation during the full source migration.

Checks: engine-api 13, engine-v2 83, source-ntk 160 JVM tests passed (256 total, zero failures/errors). The new browser admission regression holds the next browser until cancellation cleanup completes while native body work continues. Exact document/epoch/header proof and catalog completeness/pagination regressions pass. One Android IPC test on emulator-5554 / MangaViewerApi35 verifies idempotent retirement and rejects a previous request's retirement while the next request is owned. The library test creates no provider document and does not run in the viewer application package. Architecture gate passes for 281 files; selected code diff whitespace check passes. Debug app and app-test APK builds pass; this is not the final whole-project/lint/release gate.

Actual live UI probe: the first recorded mandatory NTK comic regression /manhwa/25273/1785440 (prior-failures.json, sample 0, episode 0) was opened in EngineViewerProbeActivity. A real UI swipe began before content readiness was awaited. The test passed after image rendering, full viewport coverage, close, and zero new coordinator/storage ownership. The captured screenshot was inspected and shows the source manga filling the content viewport. This proves one working vertical slice, not completion of that episode or a source-row sweep. The ordinary ViewerActivity still uses the old runtime.

Observed latency: first image submission 5327.9463 ms; full viewport submission 5936.5545 ms. Input revision 82; pending input 0. The first image exceeds the 4-second target. Browser logs show manifest-end at request age 2792 ms, but the full shared timeline and paired counterfactual attribution are not established. These results are not performance acceptance. The final frame record after close is CANCELLED and is not a physical presentation timestamp. No cold-state or memory qualification is claimed; content caches/positions were not reset, replaced or preloaded. Corpus credit remains 0/200.

Evidence: .artifacts/engine-rewrite-20260906/ntk-engine-root contains 22 source hashes/archive, all 45 JVM XML suites, service-ipc-first.txt, live-first.txt, live-first-logcat.txt, live-first-device/report.json and the inspected screen.png. The logcat file includes older unrelated entries; the current browser PID is 8009 at 08:32:20-08:32:23. Archived app APK SHA256 da71bdd862b9d3f65db240106f75c151906563b5c9c72af49fd3b29eda5cb467 and app-test SHA256 df2ad4f4f130f4393127c9ca432cb45a81559119c9c3eb9e2c5bb2de81c2cc50 match the installed APK files. No commit or push was performed. Remaining scope includes main UI migration, cancellation with an in-flight provider document, the complete tracing/display/memory accounting, whole-episode and continuous navigation checks, all final builds/tests, and 200/200 qualification.

Normal viewer migration checkpoint (2026-09-06)

ViewerActivity now constructs EngineViewerRuntime directly from EngineAppGraph for both NTK and WFWF. It no longer constructs the old SessionViewerRuntime or its separate warm/upload executors. Existing touch-root and toolbar interaction remain; chrome derives the current title, page number and neighbors from the source-anchor snapshot. Foreground/background behavior and asynchronous close use the new owners. The activity exposes exact engine snapshots and frame identities for the upcoming verifier migration. Old global-coordinate telemetry and old region/presentation arrays are not fabricated: they remain absent/empty pending that migration. EngineViewerDiagnostics records observed manifest readiness and first submission, leaving response/decode and physical-display fields unknown. Its new unit test verifies that an EGL composition latch and cancelled close do not become physical-display timestamps.

EngineViewerWork adds immutable, series-bound episode catalog work. Both source implementations share that catalog between navigation and the toolbar picker under the global coordinator. Bookmark writes now call EnginePositionStore through a global STORAGE request, saving the canonical source anchor alongside the legacy bookmark. The actual bookmark button was not used in live tests to avoid modifying existing user bookmarks; exact source-anchor restoration for explicit bookmark launch and lifecycle write ordering still need verification.

WFWF comic:10001 episode 1 passed the new normal-activity functional test (6.055 seconds including input, toolbar, real picker and close). NTK /manhwa/25273/1785440 initially rendered and showed its toolbar but failed to open the picker (37.659-second failed test, preserved). A diagnostic request to its native episode API returned HTTP 404 with a 10-byte response. The source now handles 404/405/410 by reading that same series' provider HTML catalog; authentication/throttling and other HTTP failures remain failures. The exact NTK case then passed the normal-activity test in 11.522 seconds. Both passing tests assert immediate input before content readiness, full viewport rendering, actual picker display, and zero new work/storage ownership after close. They do not traverse complete episodes, verify every source row, establish display timing, or qualify performance. Content/toolbar/picker screenshots were inspected. No cache or progress reset was performed, and neither repeat is a cold performance comparison.

Evidence is in .artifacts/engine-rewrite-20260906/main-ui-root: the first failed and corrected APKs, both source test outputs, failed NTK evidence, the HTTP diagnostic response, passing device reports/screenshots, nine source hashes/archive and the diagnostics unit XML. First APK SHA256 b83fc9ca92ea4cafe66378cef25df31c669d18bf34d9bbbe4efee46bd946f783; corrected APK SHA256 9401c0e4f646446810201df870855ab0d9041cd4b0a657f15e99335e82033855, corrected test APK a9037e9a1950f5c5ca90b880e4ed86401e47567b51d223764637995f24e55332. Debug/app-test builds, architecture gate (283 files), the new diagnostics unit test and selected diff whitespace checks passed. WFWF passed on the first APK; it was not repeated solely for the NTK-specific fallback change. No complete final-candidate qualification is claimed. The corpus remains 0/200; no commit or push.

Natural submitted-frame capture and close-race checkpoint (2026-09-06)

EngineSurfaceOwner.captureNextFrame now arms the next natural submission without setting latest, injecting input, or forcing a redraw. It returns the actual frame/scene metadata together with the native strip readback. One unbound request is allowed; cancellation removes it, surface/context termination invalidates it, and an already-bound native receipt is settled before cancellation returns. Readbacks validate token, session, renderer epoch, surface epoch and strip bounds before delivery. Failed submissions also schedule readback settlement. The original explicit-scene diagnostic capture remains separate. All nine EngineSurfaceOwner Android tests passed, including actual pixels for an offered input revision and cancellation before submission/detach. These tests establish GL ownership/readback behavior, not display time.

The first normal-UI capture correctly returned a black frame (token/EGL frame 4, input revision 1, no source placements); the test failed because it incorrectly expected image content in the first capture. That failure and its raw strip were preserved. The observer now records every sampled frame, including black ones, and requires a later input-associated source region to intersect the captured strip. A second attempt captured content but failed the strict post-close storage ownership assertion (one remaining owner). No completion was credited.

A deterministic storage test reproduced a prepared-page leak when cancellation occurs while the transferred stream is closing. The old final NonCancellable+IO block could finish successfully and then throw on dispatcher return before returning the prepared handle, outside the cleanup catch. EngineRawStorage.prepare now completes IO closure inside the caller's NonCancellable context, explicitly checks the original caller's cancellation before handoff, and discards the unreturned body. The new test failed with preparedPages=1 before the fix and passed after it; the full EngineRawStorageTest suite passed. Error and cleanup evidence remain in storage-cancel-before/after XML/text artifacts.

The same WFWF comic:10001 episode 1 normal-activity immediate-gesture capture then passed (4.258 seconds for the whole functional test). It recorded four sampled frames: black strips at tokens 4/34/68 and input revisions 1/31/65, followed by a source strip at token/EGL frame 103, input revision 96, geometry revision 5. The final strict check reports queued/active/retiring/subscribers/retainedResults all zero and fileLeases/preparedPages/pendingPublications all zero. Original cache and reading progress were preserved between attempts; this is not a cold latency comparison or a first-image timing qualification.

After the viewer closed, the original cached source for the last strip was exported read-only. Its SHA256 exactly matches the recorded page source (266027ee8e03feabaa64eaf4acb6d0d2fc59a4184fb543e24c5b82f2acbe0282, WFWF p0008, 650x918). Independent Pillow decoding/resizing plus interpolation from the recorded quad was compared with the 1080x32 captured strip: RGB mean absolute error 0.32804/255, maximum 1.57732, p99 1.30723. The strip is nonuniform (RGB standard deviation 47.50); alpha is opaque. The three sampled empty-scene strips are entirely opaque black. No acceptance threshold was selected from this sample, no full-page source-row coverage was proved, and EGL frame identity has not yet been joined to SurfaceFlinger physical display evidence.

Evidence: .artifacts/engine-rewrite-20260906/natural-capture-root contains all failed/passing test outputs and APKs, first-blank-device, storage-failure-device, storage-fixed-device (four frame JSONs/strips and exact ownership), nine source hashes/archive, the independent comparator and result, and the exported original. Current installed app APK SHA256 2a389c47dcfaf2f5147d4015537f843f15556f276eea2305b8abe2a6a0f8eb08 and test APK 4b603ee435f836802004820afaa32cd5a8e3dc5e07c135cd1af9d5644d04135f match the archived files by on-device sha256sum. Debug/app-test builds, architecture gate (284 files) and selected whitespace checks pass. Native C++ code was not changed in this checkpoint. No PSS/performance/whole-episode/200-episode qualification or commit/push occurred. Next work is the full input/frame trace and SurfaceFlinger join, source-row traversal, remaining memory/lifecycle checks and final gates.

## Normal viewer SurfaceFlinger binding (diagnostic, no corpus credit)

The normal ViewerActivity capture now includes viewport height, coverage/anchor observations and process PID/UID. `collect_engine_live_trace.py` validates the designated emulator and exact installed app/test APK hashes, starts Perfetto before activity entry, records immediate ordinary UI gestures, stops its own trace process, and exports the new capture directory. First collection failure (CRLF directory parsing) and subsequent failed verifier reports remain preserved under `.artifacts/engine-rewrite-20260906/live-sf-root`. Main app APK remains 2a389c47dcfaf2f5147d4015537f843f15556f276eea2305b8abe2a6a0f8eb08; the latest test APK is 9ccb60335bcb00e00956861d111a97d19dd27fd2345de5191a6542bc8f27097d.

The `monotonic` trace (SHA256 92aa5b47a58fb4f2bfd567cc9c51c710e2bca3bb6633d8ea5d00840309655f1d) initially failed strict analysis with six clock_sync_failure_no_path errors. Raw clock snapshots show TSC clock 9 regressing by 49,532,364 ticks between packets 3435 and 3989; every error argument refers to clock 9. Perfetto v56.1 reports those failed snapshot conversions as errors. The official v58.2 processor handles snapshot-table conversion failure through the snapshot's own trace-time reading. This behavior is described in [upstream ClockTracker](https://github.com/google/perfetto/blob/add693d8b338ba9599dbcbc3e300b1ab8c000897/src/trace_processor/importers/common/clock_tracker.cc). The official binary from the [Perfetto distribution](https://get.perfetto.dev/trace_processor) is archived with SHA256 adfa6bad3d72be3ba9b83fa2b17b69fa13b3ab1cad0f42e52b86188bd5f0f997. On the unchanged trace, v58.2 reports zero error/data_loss statistics. Frame events, transactions, Binder flows and releases are identical across versions; `clock-root-cause.json` and `slice-version-diff.json` record the slice comparison. No trace packets were removed or rewritten, no offsets were supplied, and no error filter was added. Analysis uses explicit full sorting and records processor version/hash.

The causal Binder dispatch supplies the transaction time boundary: exact send/receive message identity, receiver thread, receiver buffer release, and exactly one enclosed setTransactionState handler. The transaction must fall within this kernel dispatch and match caller UID/PID, EGL frame number, buffer dimensions and exact layer. A narrower ATRACE handler-entry bound was incorrect for this recording: frame 4's postTime precedes the normalized handler marker by 4,900 ns while remaining inside the exact receive/release interval. The verifier uses the dispatch boundary without any time tolerance or nearest-time matching. Tests reject times before receipt and at/after release; missing/ambiguous handlers and transactions still fail. All 28 binder tests pass, including five normal-viewer cases; direct test execution now includes the live test class.

`monotonic/surface-v58.json` binds all eight captured frames (tokens 4, 34, 69, 101, 133, 166, 199, 233; input revisions 1, 31, 63, 94, 125, 156, 187, 217) to layer 2047, producer PID 10005/UID 10236 and their exact producer buffers. Every swap also has its exact session/renderer/surface/token/input/geometry engine-frame ancestor. Physical presentation remains false/null. This is sampled-strip and observable-latch evidence only: independent source pixels for these eight captures, complete viewport/source-row traversal, performance, PSS and 200-episode qualification remain unproved. Existing strict fixture shape (eight frames, two surface epochs) is retained. No app rebuild, additional UI rerun, cache manipulation, commit or push occurred during this offline analysis.

## Native bytes, input receipts and independent source strips

EngineViewerRuntime now records the actual reducer receipts previously discarded by its Android callback. A bounded 512-entry journal retains only receipt/session/anchor values, includes deferred updates and close cancellation receipts, and reports overwritten evidence explicitly to cursor readers. It retains no source bodies, bitmaps, textures or runtime plan maps. ViewerActivity keeps the journal accessible after runtime close. The normal capture test exports the journal after each gesture/capture and after close, failing on lost observations. These observations do not establish raw MotionEvent completeness or physical input-to-display timing.

EngineReadbackPacket preserves its exact original native header and a payload digest. Export requires a parsed original, checks the payload is unchanged, and cannot be performed on a copied/model-created packet. No second full RGBA buffer is retained. The test exports `native-N.packet` alongside the existing JSON/strip files and structured page/anchor/raster identities. The live SF verifier now requires the native bytes and checks every header identity, timestamp, geometry and payload against the sidecars before binding; earlier JSON-only capture reports remain historical diagnostic evidence.

Evidence is under `.artifacts/engine-rewrite-20260906/input-wire-root`. Installed/archived app SHA256 d026a3ff4b07e127e084a9b74b6baa3c355b3ba82f4ef3f679cdc9c245c80af6 and test SHA256 76bdd84d8eeb9716bad2536dceff87ba487a9d99d1a160af6460af328edcd3ef were checked by the collector. One actual normal-viewer immediate-gesture test passed in 3.211 seconds (functional duration, not qualified first-image latency). Its three sampled frames, tokens 4/35/70, all passed native-header/payload and exact SF binding. All eight reported coordinator/storage ownership counters are zero after close. The input file has 148 observations for sequences 1 through 110: 38 deferred observations and 110 terminal APPLIED receipts, with no terminal cancellations or clamps. The input verifier checks replay identity, cumulative distance, receipt order, resolution clocks and terminal state. It explicitly does not prove an untruncated full-session history without an independent final session/input count.

The first two 1080x32 strips are entirely opaque black and have no source coverage. The third matches WFWF comic:10001 episode 1 page p0001, source digest 767bfeb65dba5cc171a5b96dd5034b641da6d4e512f266359db56e64d38d2cb4. Two original cache bodies were exported read-only after close, with exact digest checks and no network fetch. Independent Pillow full-raster bilinear resize/crop and GL pixel-center interpolation yielded third-strip RGB MAE 0.151639/255, max 1.592248 and maximum per-row MAE 0.193207. The comparison applies the existing 4/255 RGB tolerance per row; it reports empty rows separately and derives rational source-band endpoints. Only source interval [279055782/520633, 289082790/520633) was observed in that strip. It provides no full-page/viewport/episode coverage claim. Transparent and ICC-profile sources currently fail explicitly pending independent reference support. Source-file server/manifest provenance still needs the separate manifest chain.

The prior run naturally advanced into episode 2; the latest diagnostic explicitly opened episode 1, so its null initial anchor is consistent with the per-series saved episode mismatch rule. No cache or saved position reset was performed. Five focused JVM tests, 40 Python verifier tests, Android test APK/debug APK builds and the 285-file architecture gate passed. Whole-session input completeness, full source-row traversal, lifecycle/bookmark qualification, memory/PSS/performance gates, full build/lint/release verification and the final 200/200 run remain outstanding. No commit/push occurred.

## Closed input count, exact coverage accounting and build gates

After successful content/graphics/persistence/native close, the input journal is now sealed against both the runtime adapter's independently maintained inputSequence and the reducer's final inputRevision. Its close proof also records the total observation count, session/generation and actual close timestamp. The external input verifier compares those values with the exported sequence, catching a truncated suffix even if the remaining prefix is internally valid. Raw MotionEvent-to-adapter correspondence remains separate. Five EngineCaptureEvidence JVM tests and six input-verifier Python tests pass, including suffix truncation, independent count mismatch, overflow and post-close record rejection.

One new normal UI capture with this close proof passed in 4.163 seconds of functional test time. `input-close-root/live/inputs-verification.json` reports 169 receipt observations, 121 accepted adapter inputs, 48 deferred observations and 121 terminal applied inputs, with no cancellation/clamp. The closed-session counts match exactly and all three captured frames pass the original native-packet/SF binding. This run used archived/installed app fa3c821c43cced70405a43ea96024e79c79242edeced24e9c9530a904b226835 and test bc836b585982b3e8199adeba06f0426cde3cceaf3b1accc9530ff54c178acf57. These are diagnostic captures, not a new qualification sample.

`engine_source_row_coverage.py` unions exact rational intervals and reports every missing fraction and incomplete original row. Duplicate/overlapping captures cannot inflate coverage; a one-billionth-row gap remains a failure. Five tests pass. Applied to the previous independently compared strip, it reports only 19 fully observed rows of p0001 and zero of p0002 (1,836 declared rows total). The report is `input-wire-root/live/source-row-coverage.json`. Its declared set consists only of capture-referenced pages; an independently complete episode manifest, ordered traversal and final stop are still mandatory before any whole-episode claim.

The full 10-module unit-test gate initially passed with 550 results. Lint then exposed an actual API-30 incompatibility: BigInteger.longValueExact is unavailable on the minimum supported Android version. All production calls in app, engine-v2, viewer and viewer-content now use the core checked narrowing helper built from bitLength/toLong, preserving overflow rejection. Tests cover signed boundaries and 1,000 deterministic signed values across bit widths against the JDK reference. After this shared change the full 10-module gate passed again: 552 tests, zero failures/errors/skips. Some tasks legitimately reused unchanged Gradle results; logs and XML files preserve the task/result distinction.

Debug lint and explicit full release lint pass with zero fatal/errors (47 debug warnings, 33 release warnings remain). Debug APK, release APK and Android test APK builds pass, as does the 286-file architecture gate. Both APK workflows now include engine-api and engine-v2 in their sparse checkouts; all 10 settings.gradle modules are accounted for. This fixes the remote checkout omission but is not a CI run or push.

Logs, per-module XMLs, lint reports, checkout validation and APKs are archived under `.artifacts/engine-rewrite-20260906/input-close-root`. The latest built API-30-compatible APKs are in `api30-apks`: debug 964453091f055f3a6c1d795100a0ecbfd56bbc562608660170dde36dece7a82d, release 68d5dd6f1969cf7346b0fabc4405c0bc9481f46ce2b92e94634ab3d1a1bb55c0. Those newest APKs have not yet been installed or used for device qualification; the last installed app remains fa3c821c43cced70405a43ea96024e79c79242edeced24e9c9530a904b226835. Release uses the repository's existing debug signing configuration, not a new private production signer. Full viewport/source provenance, final-stop/input/display checks, memory/performance, lifecycle/bookmark checks and final identical-candidate 200/200 qualification remain outstanding. No commit/push or corpus credit was issued.

## Full viewport native capture

The owner can now arm a full viewport readback on the next natural submission. Its bottom bound is resolved from that submitted scene, while exact strip capture remains available. This path neither supplies a scene nor forces a redraw. The normal-viewer capture test uses this full viewport path and records every sampled frame, including empty ones. The native implementation retains its existing 16 MiB total live-PBO admission limit and two-slot limit; a 1080x2138 capture is 9,236,160 bytes. This is not yet combined texture/PBO/transient budget qualification.

All 10 EngineSurfaceOwner Android tests passed, including the new whole-frame actual-pixel test and the existing cancellation/lifecycle/ownership cases. The debug/app-test builds and 286-file architecture gate passed. One normal UI immediate-gesture capture then passed in 3.289 seconds of functional test time. Installed and archived APKs under `.artifacts/engine-rewrite-20260906/full-viewport-root` are app 0e85e4b34c98e74c30dd52b7dfc664fdd3ba0c5311e886dd0c7cd6c782ea0b7d and test ee1f7b05a53e189b710837b61ad387faed25d446d9b58b5b8d7c952fa11c3fd5. This app includes the checked API-30 arithmetic conversion. These versions supersede the installed APK mentioned in the previous checkpoint; the release build has not been requalified after the full-capture API change.

The new original trace digest is b7bde0d038bc9d72435ab8af7677f73fa291bef038f84432a9f0e9926d40711f. Full-frame tokens 4/37/75 (input revisions 1/35/69) all pass native-byte and exact SurfaceFlinger buffer/latch binding. Frames 4 and 37 are entirely opaque black, with 2,138 uncovered rows each. Frame 75 has complete viewport geometry and independently matches original WFWF comic:10001 episode 1 pages p0007/p0008: RGB MAE 0.279724/255, max 1.765392, maximum row MAE 0.422134, with zero uncovered viewport rows. Both originals were exported read-only after close and verified by SHA256. The underlying source-band union confirms 782 complete rows of p0007 and 503 of p0008: 1,285/1,836 rows for those two declared pages, not an episode-wide manifest or completion claim.

The sealed input history verifies 120 adapter inputs, 170 observations and 50 deferred observations, all ending applied. Coordinator/storage ownership counters are all zero after close. Readback issue-to-ready times are approximately 29.68, 30.52 and 28.77 ms; these diagnostic capture costs must not be presented as normal render latency or a qualified performance result. Physical presentation is still false/null. Final stop, complete ordered source manifest/traversal, full input/display correspondence, memory/PSS/performance and final 200-episode qualification remain outstanding. No preparation wait/cache manipulation/commit/push occurred.

## Original document and complete page inventory

An optional EpisodePlanObserver now observes the exact validated SourceDocument/EpisodeAccessPlan pair before the document dependency is released. WFWF uses the episode-work boundary; NTK observes the completed authorized plan against its original document. The app graph defaults to no observer. The instrumentation-only recorder retains bounded immutable references (16 entries/32 MiB maximum), performs no I/O in the content callback, detaches after the test, exports after closure and clears references. Response headers are not exported. Capacity failure fails the diagnostic rather than silently dropping documents.

The latest normal capture used app 0d4607f07e70dcd44f333f95257e7b867eeefe21650af7f7457d6b4ec648a154 and test ba60c1098cafb00fecc57b37fbabaefe9b405c5065ace827a212df5b73d64aeb, installed/archived under `.artifacts/engine-rewrite-20260906/document-provenance-root`. Debug/app-test builds, engine-api/engine-v2 tests and the 287-file architecture gate passed. The functional UI test passed in 3.629 seconds. One actual WFWF episode document (28,271 bytes, declared EUC-KR) was exported; SHA256 1c2742dcbc5e305abb29630e21808f77d7a2a23f7b3f309f30b8e0f90250d94a matches the plan's original document digest.

The host verifier independently parses the raw HTML using Python's HTML parser, not the Android parser or the plan's chosen image indices. For the supported WFWF `div#vimg-area` layout, it derives all page records in DOM order, excludes images outside the content container, preserves duplicate image addresses as separate records and compares each identity/source record/candidate list. It also checks document bytes/digest and episode identity against the final URL. All 19 pages p0000 through p0018 agree with the observed plan. Five tests cover omitted/reordered pages, wrong addresses/episode, changed document bytes, duplicate image addresses, unknown containers and declared Korean encoding. Unknown layouts/encodings and independent NTK parsing remain explicit unsupported failures, not partial successes.

Three full viewport frames also pass native-byte/SF and independent pixel checks for this run. The first two are entirely black. The last, token 85, matches p0010/p0011 with RGB MAE 0.192371, max 1.749987, maximum row MAE 0.491009 and zero uncovered viewport rows. The sealed adapter history verifies 132 inputs, 185 observations and 53 deferred observations, all ending applied. `live/episode-inventory.json` binds the two exported source bodies to their page-cache keys and the captured plan revision; it reports 17 of the 19 expected page bodies still missing from this diagnostic. It does not infer their row dimensions or mark the episode complete. Actual image-response request/body provenance and independent series-catalog episode order remain separate unfinished gates. All raw documents, plans, original bodies, captures, input/SF/pixel/document reports and 10 source-file hashes/copies are retained locally. No episode/corpus credit or commit/push occurred.

## Complete native frame history and observed submission delays

EngineViewerDiagnostics now retains a bounded 512-entry value-only frame history with explicit overwrite counts. Empty scenes, failed swaps and unavailable/cancelled timestamp observations remain in the history. Each record carries its actual renderer/frame/input/geometry identity, native submission start/duration, timestamp kind/value and viewport/source-presence metadata. It retains no native leases. EngineSurfaceOwner records its independent final submitted token count after native destruction; runtime close drains the same ordinary main Handler queue used by renderer callbacks before sealing the delivery count. This avoids treating callbacks blocked by a main-loop sync barrier as missing or late. Instrumentation exports the frame history and independent renderer close proof. Capture placement JSON also now includes the actual tile contentRevision.

Three JVM diagnostics tests and four Python history-verifier tests pass, including lost suffixes, duplicate/wrong-owner tokens, out-of-order delivery, failed swaps, overwritten evidence and immutable close counts. All 10 EngineSurfaceOwner Android tests pass, including final native-count checks. Debug/app-test builds and the architecture gate pass. Current installed/archived app is 9a1d6e6986b0440d3bb21a97d7a517d92751519cc26dfcbe980105456e3c5b35 and test f9083c8adabb889fd6c3c4177e7b7cba88fc08751d8075278fc8483b02e13220 under `.artifacts/engine-rewrite-20260906/frame-history-root`.

The real normal-viewer diagnostic records exactly 124 submitted/delivered frames, matching all 124 exact engine-frame trace identities. Native submission duration has nearest-rank p95 17.3302 ms and maximum 80.0853 ms. No submission call reaches 100 ms and no swap reports failure, but these facts do not prove the end-to-end pause or missed-display-frame targets. There are 74 zero-source scenes and 81 scenes with incomplete viewport coverage; timestamp kinds are 114 COMPOSITION_LATCH, 6 UNAVAILABLE and 4 CANCELLED. The adapter input history independently verifies 125 inputs/192 observations/67 deferred observations, all ending applied. All three sampled readbacks also pass exact native/SF binding. Pixel and SF binding of every unsampled frame are not inferred from the metadata history.

`slow-submission-breakdown.json` identifies the actual nested trace costs. Initial frames 1/2/3 spend approximately 58.38/29.16/35.14 ms in allocateHelper inside dequeueBuffer/eglSwapBuffers, with most of that interval sleeping in the guest. Frame 117 spends 47.06 ms in the emulator's rcCreateSyncKHR encode inside eglSwapBuffers; frame 119 spends 26.33 ms in the same section. The full readback frames 38/84 have native durations 22.22/17.33 ms despite only 0.83/0.61 ms inside eglSwapBuffers, demonstrating a separate capture-path cost. This is attribution to observed trace sections, not proof that the costs are unavoidable or outside app control. No five/ten-pair comparison or performance acceptance was issued. Lifecycle/active-view timing scope, complete source traversal/final stop, source response provenance and memory/PSS qualification remain outstanding; corpus credit remains zero.

## Whole-episode traversal and display-queue failures

`EngineEpisodeTraversal` now drives real gestures independently of a bounded natural-frame capture reader. It immediately scrolls, returns from the saved position to the document's first page, traverses to the document's last page and records a fixed stopped interval. Endpoint detection uses the observed original document plan; it is not a substitute for independent source-row coverage. Entry still uses the normal ViewerActivity intent for WFWF comic:10001 episode 1, not catalog UI selection or the final random corpus. `verify_engine_capture_bundle.py` runs sealed frame/input history, independent original-document parsing, source export, native/SF binding, pixel comparison and complete-document inventory together. It reports failures and remains at zero corpus credit.

Original exports now bind each page and content revision to its exact immutable cache filename. If multiple pages contain identical bytes, every corresponding cache object is read and hashed before host bytes are deduplicated. The inventory verifier rejects unseen document pages, changed revisions, mismatched original dimensions/digests and even fractional source-row gaps. Separate tests cover these cases and failed archive hash checks.

The first whole traversal (`whole-traversal-root/live`) collected 109 full frames with 43 gestures in 29.782 seconds. Its 652 sealed submissions had p95 72.7638 ms, maximum 128.3792 ms and 11 calls of at least 100 ms. Frame 360 was submitted but has no consumer acquire or SF Queue/Latch, while adjacent frames do; `failure-360.json` preserves this failure with no trace-loss stats. The final submitted frame 638 was also newer than the last captured frame 637. Neither failure was waived.

The renderer now schedules its latest accumulated scene with its GL-thread Choreographer. Input processing remains independent. The subsequent single-payload run collected 219 full frames in 24.947 seconds. Its 788 sealed submissions had p95 13.4461 ms and maximum 214.615 ms, including one call over 100 ms. These runs also differ in capture I/O and initial saved state, so they are diagnostic observations, not a controlled performance comparison or final acceptance. The strict SF verifier rejected a transaction postTime 2,100 ns earlier than the converted kernel receive timestamp. Live trace configuration now requests the supported raw monotonic ftrace clock while retaining MONOTONIC trace time; no verifier time tolerance was added. Perfetto documents its snapshot-based conversion in https://perfetto.dev/docs/concepts/clock-sync.

Full raw captures exhausted the 6 GB emulator data partition twice. All device diagnostic files were subsequently SHA-256 matched to their host archives before removing only the device diagnostic copies. Application originals, caches, database and bookmarks were not manually cleared. Collection now performs this verified archive step automatically after the test and trace finish. The Android test losslessly compresses the exact original native packet at gzip level 1, records its uncompressed SHA-256 and avoids writing a duplicate RGBA file on device. The collector verifies and expands the archived packet and derives its RGBA payload on the host after measurement ends. Original wire bytes and image quality are preserved. Primary capture errors now retain cleanup errors as suppressed exceptions instead of being replaced by a document-export error.

`whole-traversal-composited-stop-root/live` completed collection in 33.968 seconds with 193 packets. Native submission p95 was 12.4933 ms, maximum 192.8241 ms, with one call over 100 ms, zero failed swaps and 74 unavailable timestamp results among 1,208 submissions. Its first new SF failure is a missing consumer acquire for frame 493. Inspection found `eglSwapInterval(display_, 0)`, which enables Android's asynchronous buffer queue. The next candidate changes this to interval 1 to prevent replacement of unacquired buffers; no improvement is claimed until measured. Android implementation reference: https://android.googlesource.com/platform/frameworks/native/+/bbc01a95b1535df3f7376b9211aa2bfa0c034735/libs/gui/Surface.cpp.

Two real compositor screenshots now supplement native captures during the final stop. They record the actual SurfaceView rectangle, full scene metadata before/after acquisition and separate monotonic screenshot clocks. They are explicitly not native readbacks and do not independently establish an SF buffer binding. In the first run the immediate screenshot overlapped the ordinary fling tail (reported input 1570 to 1571), while the later screenshot stayed at frame 1208/input 1639; the combined stop comparison therefore failed. The next candidate observes a fixed two-second fling tail followed by the one-second screenshot interval without probing content readiness. Four screenshot-verifier tests reject changed scenes, wrong screen regions and invalid clocks. All 34 selected Python evidence tests pass, and both cadence/interval candidates pass the ten real GL-owner instrumentation tests. Final source-row coverage, stable SF screenshot binding, causal response bytes, memory, paired timings, catalog UI/corpus execution and final release gates remain unfinished.

## 2026-09-07 HTTP evidence and surface lifecycle fixes

Added a host verifier for the sealed HTTP observation ledger and integrated it into the capture bundle. Six focused tests verify actual document bytes, incomplete streams, ordinal/identity/time changes, missing closure, and incorrect page URL/body bindings. A cache export alone deliberately cannot establish an HTTP response binding.

The first real catalog run with observation enabled failed because capture was armed before the initial Surface callback. The GL capture now reserves initial surface epoch 1 immediately, with viewport validation at frame binding; gestures are not delayed for content readiness. The subsequent real catalog traversal completed (26 gestures, 84 captures). Its 82 HTTP requests were closed with no observation overflow; 75 bodies reached EOF. Episode HTML matched an actual completed HTTP response. All 19 first-episode page bodies were cache hits with no matching HTTP evidence in this run and remain unverified for origin provenance.

That traversal still failed qualification: native submission p95 43.5252 ms, maximum 216.1104 ms, three submissions at least 100 ms, and one failed swap during shutdown. SurfaceFlinger trace import also retained unaudited errors. Independent stopped-screen pixel comparison passed, but this does not establish the full display/stop interval. Evidence: `.artifacts/engine-rewrite-20260906/http-catalog-attach-root/live`.

The failed final swap exposed an asynchronous Surface teardown: `surfaceUnavailable` returned after scheduling GL detach while Android could destroy the buffer queue. It now waits for the GL detach barrier before returning. Runtime close also enters background before marking itself closing. GL detach does not wait for a main-thread callback. Debug and instrumentation APK builds passed; a new real catalog traversal is validating this change.

Original trace clock investigation isolated all 54 `clock_sync_failure_no_path` errors to unused TSC snapshot entries. The TSC counter reversed twice; removing only those entries in a clearly nonqualifying diagnostic copy eliminated those errors. The original trace remains unchanged, and its five invalid near-maximum FrameEnd timestamps belong to MainActivity, so the narrowly scoped foreign-launcher exception does not apply. No qualification waiver was added.

Final qualified corpus remains 0/200. No main push or completion claim.

## 2026-09-07 episode-scoped trace and cold catalog search

The original surface-detach trace contains exactly two invalid MainActivity FrameEnds whose actual starts precede the real episode tap by seconds. Added an explicit episode-scope audit: the exact MainActivity layer, PID/UID, trusted SurfaceFlinger sender, cookie and clock must match; all raw BOOTTIME/MONOTONIC snapshots establish a conservative pre-tap cutoff with 1 ms allowance. ViewerActivity, unknown layers, overlapping starts and other trace errors still fail. Only diagnostic copies omit these records; buffer binding reads the unchanged original trace. Six audit tests pass.

On the preserved surface-detach run, all 79 captures now bind to original SurfaceFlinger buffers and pass independent pixel comparison. All rows of the 19-page first episode were observed. The final buffer 425 remained unchanged across compositor screenshots at 49582038000400..49583669020300 ns and passed the >=1 second stopped interval check. The independently parsed actual HTTP catalog has 41 episodes and confirms the plans for episodes 1 and 2 have correct adjacency. Three catalog parser tests pass. The second episode was only partly displayed and receives no whole-episode credit. Performance, cache-origin HTTP bindings and the full 200 corpus remain incomplete.

The capture collector can now accept already discovered identity/title metadata with `--catalog-entry`; actual search, series and episode row selection still verify the current identities. Removing redundant full-catalog discovery exposed a real cold comic-search timeout at 30 seconds. A trial of the site's `/sh` search route returned no matching comic and was reverted. WFWF comic search instead fetches at most four actually advertised catalog links concurrently after the user search, returns results in original page order, and cancels remaining work before return. It does not prefetch image/episode content. All 72 source-wfwf tests pass, including overlapping requests and earlier-match precedence despite later-page failure. Current real regression artifacts are under `.artifacts/engine-rewrite-20260906/wfwf-parallel-search-root`.

The cold search regression completed successfully in 78.898 seconds including navigation, resume traversal and capture export. Actual `/cm` request history shows peak active requests increased from 1 to 4 and the observed catalog-request span decreased from the prior timeout run's 31.2191 seconds to 12.8597 seconds. This is one observed comparison, not the required paired performance policy proof. The run submitted 788 native frames with zero failed swaps; p95 43.9087 ms and maximum 234.224 ms still fail the render target. All 87 observed HTTP requests terminated with no ledger loss (75 complete bodies). Surface verification remains blocked on additional raw trace import errors in this new run. Final credit remains 0/200.

## 2026-09-07 raw clock isolation and NTK image-API evidence

Added an unused-TSC clock audit restricted to the observed Android packet schema. It rejects unknown payloads/default clocks, TSC-timestamped packets, non-core ftrace clocks, ftrace loss, and reversal of any used core clock. In the parallel-search original trace only the unused TSC clock reverses. Removing only its snapshot entries from a diagnostic copy eliminates exactly 73 clock-path errors; 12 invalid MainActivity FrameEnds remain, all with actual starts 2.56–21.18 seconds before the episode tap. Both audits are combined only for classification; source queries continue using the original trace. Six TSC and six FrameEnd tests pass.

The parallel-search trace now binds all 146 captures to original SurfaceFlinger buffers and passes pixel comparison and final stopped-buffer verification. Full row coverage still fails: page p0003 has one partially observed last row (missing source interval 716842791/780950..918). This is not waived. Its prior surface-detach predecessor covered all 19 pages; the newer run does not inherit that result.

Added an optional NTK authorization observer and a test recorder. When observation is enabled, the existing browser response clone retains actual image-API bytes via ArrayBuffer, fatal UTF-8 decode and Base64, capped at 128 KiB for the current Binder evidence envelope. The ordinary unobserved path retains the prior JSON-only behavior. No extra fetch is initiated. Document replay hash, authorization identity, API envelope and ACK/manifest/retirement callback times are exported after closure; normal cache and input behavior remain unchanged. Oversized evidence fails rather than claiming completeness.

A separate Python parser independently joins Flight chunks, skips byte-length text records, identifies the unique protected viewer descriptor, verifies exact original API bytes against its envelope, and checks every page/candidate against the plan. The actual NTK webtoon `/webtoon/57451201/jjaptoon-1341148` has 132 pages; all document metadata/API page identities and candidates match. Four focused tests plus 17 affected document/inventory/HTTP tests pass. Original API body SHA256: `2aa4b18490d5a9e13d324868b298d29b3a8648ddd52ffdbfd2d35395da9df93b`. This proves API ordering, not successful image-body downloads or display.

The first NTK run failed image downloads with Connection reset on all three candidate CDN hosts. The engine had used OkHttp despite NTK's existing Chromium transport. A lazy NTK-only HttpEngine transport was wired for page bodies, preserving document/catalog transport; that H2-preferred run also reset. The existing NTK route additionally prefers QUIC, which was missing from the new planner. Restored that request preference and exported it in the HTTP ledger. Source-api tests (8) and source-ntk tests (160), plus debug/instrumentation builds, pass. The QUIC regression reached the 90-second traversal deadline instead of the prior immediate resets; final collection analysis is pending. Every run remains diagnostic with zero corpus credit.

The QUIC run completed 101 response bodies (110 observed requests, all closed without ledger loss) and recorded 2,180 partial native observations plus 370 captures. Eighty-five distinct pages were captured, reaching page p0097 before the fixed 90-second traversal deadline. There is no successful whole-episode or sealed renderer-close claim for this failed run. Partial native p95 is 44.8438 ms, maximum 206.8828 ms, two submissions >=100 ms, zero failed swaps. A test cleanup change now drains and seals renderer/input/resource evidence in `finally` for failed as well as successful runs; instrumentation build passed, but that cleanup change has not yet received a device regression. Evidence: `.artifacts/engine-rewrite-20260906/ntk-quic-root/live`.

## 2026-09-07 five paired readback controls

A frozen 50-gesture direction plan and identical application/instrumentation APKs were run in five alternating on/off readback pairs. Every pair verifies the same injected coordinate/action sequence hash, complete renderer/input journals and zero post-close ownership. Cache and reading positions were preserved; exact initial cache/position equivalence is not claimed. These are timing controls, not corpus successes or an unavoidable-cost policy.

Readback-on native submission p95 values were 43.6384, 42.9159, 43.0675, 43.5924 and 43.1674 ms. Off values were 44.4469, 44.4272, 44.4260, 44.3810 and 44.1222 ms. Thus removing readback did not remove the p95 problem. Readback still affected submission throughput; the first two pairs were roughly 24 native submissions/s with readback versus 29/s without it. These are submission rates, not physical display rates. All evidence and the frozen design are in `.artifacts/engine-rewrite-20260906/readback-paired-root`.

The GL scheduling path now consumes an already waiting scene immediately when a full refresh interval has elapsed since the preceding frame began, instead of always posting another Choreographer callback. Early updates still wait for display cadence. Four timing-policy tests, APK builds and architecture checks passed. A first no-readback fixed-input run reduced matched input-application-to-submission median from about 16.0 to 9.5506 ms, but native p95 remained 44.4296 ms (maximum 275.8996 ms). Its submission rate was 26.12/s, so broader performance benefit remains unproven. The candidate has not yet passed full pixel/SF regression.

The same candidate without Perfetto tracing produced p95 44.5768 ms (maximum 244.801 ms), so the large cost persists without either readback or trace collection. The no-trace collector path is restricted to fixed-gesture no-readback controls, labels the missing trace explicitly and cannot satisfy display verification. Current read-only device inspection reports an NVIDIA GeForce GTX 1060 3GB OpenGL translator and 60.000004 Hz display mode; no GPU, display, RAM or security setting was changed.

The failed-test close drain now runs in a NonCancellable context, retaining its own 30-second close timeout, so cancellation does not skip evidence cleanup. Final corpus remains 0/200. A minimal EGL/surface baseline is still needed before attributing the remaining cost to the device.

## 2026-09-07 empty Surface submission controls

Added a debug-only, non-exported empty-scene Surface activity and instrumentation test. It uses the same GL owner and 1080x2138 buffer geometry, initiates no image/episode fetch, and labels every result synthetic with zero corpus credit. Ten-second baseline: 304 submissions, native p95 43.6401 ms, maximum 191.0795 ms, 303 COMPOSITION_LATCH observations and one teardown cancellation.

A debug-only swap-interval setter rejects release calls and is permitted by the Java owner only before the first submission. Normal viewer interval remains 1. Interval-0 empty-scene control: 330 submissions, p95 24.0168 ms, mean 7.9294 ms, 260 COMPOSITION_LATCH, 66 UNAVAILABLE and four teardown cancellations. Missing EGL timing is not assumed to prove missing physical display.

Additional test-only pending-submission and polling controls preserve ordinary defaults. With interval 0 and one pending observation, the 10-second run produced 196 submissions, p95 10.209 ms and no unavailable observations; throughput fell and this was not adopted. Two pending observations produced 258 submissions and p95 12.21 ms. With 2 ms polling and immediate consumption after acknowledgment, one pending produced 249 submissions/p95 11.467 ms; two produced 262/p95 12.2676 ms. The latter runs had only their final one/two teardown cancellations. Median delay from reported latch timestamp to receipt remained about 28.1 ms, so using the delayed EGL observation to govern admission limits throughput. These controls do not establish a qualifying display rate or an unavoidable-cost exception.

A concurrent SurfaceFlinger trace of the unrestricted interval-0 baseline is being collected to distinguish missing EGL timestamps from actual missing buffer latches. No production swap mode or input/scroll restriction has been enabled. Normal renderer still uses synchronous interval 1 and no pending/poll verification overrides.

The interval-0 trace completed with no reported trace error/data-loss stats. Of 340 positive native EGL frame IDs, 91 have no SurfaceFlinger Latch event; these are exactly the 91 EGL UNAVAILABLE IDs, and none has a latch. This is a raw ID/layer diagnostic, not full producer/epoch binding or physical-display proof. It rejects the explanation that only EGL metadata was missing. Archived APKs, original trace (SHA256 `2857d2c114804a1fae078c805577d7abf2d8bf546eca62d42fb00d69fe25abd8`) and results are in `.artifacts/engine-rewrite-20260906/empty-swap-async-trace-root`.

A subsequent interval-1 trace with the same installed APK hashes produced 311 submissions, native p95 43.0300 ms and maximum 193.0336 ms. It also has no reported trace error/data-loss stats. All positive native EGL IDs have SurfaceFlinger latches; one final SF ID is outside that positive-ID set, so no full closure/display conclusion is inferred. Slice-overlap analysis measures 311 eglSwapBuffers calls: mean 31.0731 ms, p95 42.5984 ms. Their overlapping dequeueBuffer time averages 23.8586 ms (p95 40.2980 ms), whereas queueBuffer averages 0.6306 ms. Across the swap intervals the GL thread spends 7393.0872 ms sleeping, 2218.2388 ms running and 52.4214 ms runnable/preempted. The longest swap is 153.6976 ms, containing 126.9174 ms in dequeueBuffer and 126.6421 ms sleeping. Thus the empty-scene cost is dominated by buffer acquisition wait, not image processing or CPU runnable starvation in this trace. It does not establish that the cost is unavoidable, identify the host-side cause, or permit dropping frames to shorten the measured call.

Evidence: `.artifacts/engine-rewrite-20260906/empty-swap-sync-trace-root/{collection.json,capture/summary.json,swap-cost-analysis.json,display.perfetto-trace,app.apk,test.apk}`; original trace SHA256 `4615858b1c35a8785ace03a747de26c320a7b3434ba78a5138f670904f585b7b`. Collection validates emulator-5554/MangaViewerApi35 and installed APK hashes. No environment settings or production swap policy changed. These two short traces are diagnostics, not the required five/ten paired real-content controls or final 200-episode evidence. Corpus remains 0/200.

## 2026-09-07 NTK independent episode catalog and UI search failure

The independent catalog verifier now accepts complete NTK episode API responses for webtoon/manhwa, requiring actual HTTP bytes, matching origin/series, response completion before the observed plan, an authoritative total, unique episode identities and unique explicit positive epNo values. It orders by provider sequence instead of title, ID or response-array order, then checks both viewer neighbors. Missing/ambiguous sequence and unsupported alternate schemas remain failures. Seven catalog tests pass, including prior WFWF cases and HTTP tampering/time/origin, wrong-neighbor and ambiguous NTK sequence cases.

A real catalog-UI diagnostic on the current installed APK failed before viewer entry: `CorpusUiEntry.prepare` found zero search results for the sampled identity `/webtoon/57451201`. The original catalog identifies this series as 역대급 창기사의 회귀; the stored episode slug `jjaptoon-1341148` must not be interpreted as its series title. The API request `keyword=역대급 창기사의 회귀` returned the ordinary 80-row works page with total 7568, not matching search results, and rows lack kind/path fields required by the unforced search parser. This is an unresolved source-search integration failure, not an episode substitution or corpus success.

The same failed run did obtain an original complete 225-row episode API response. The new independent parser accepts all 225 explicit epNo identities and preserves nonconsecutive remote IDs. Its body SHA256 is `481962549e846384d95c3c4f2d44628e5491ae3eb247588487457b3709ec00cd`; the sealed HTTP ledger verifies with events SHA256 `c7de110b4fda26d2711b945b7cf781bf006aff26e74bb5f8f8f914a6002658ba`. Report `.artifacts/engine-rewrite-20260906/ntk-catalog-proof-root/ntk-catalog-parse.json` explicitly does not claim viewer adjacency or UI-entry success. Collection, original HTTP bodies, instrumentation failure and concurrent original trace are retained in that directory. No app code/APK, cache, positions or environment settings were changed in this step; corpus remains 0/200.

## 2026-09-07 NTK search fallback regression

Fixed the source-search empty-result decision: a works-shaped API response with a positive/unknown total and zero assignable series identities now falls through to the existing `/search?q=...&field=title&match=contains` page. A genuine total-zero API response still completes without another request. The code does not invent missing content kinds. Two regression tests cover both cases; all 162 source-ntk tests and debug/instrumentation APK builds pass (`ntk-search-fallback-build.log`).

The same series/episode now passes actual catalog search and episode-row entry with the new APK. Input metadata was taken from the preceding original catalog response; real UI identity checks remain enabled. The search HTML response is 168409 bytes. The new run's original complete 225-episode API independently matches both viewer neighbors, and its protected document/API evidence independently matches all 132 planned pages. All 16 observed HTTP requests are sealed, with 12 complete response bodies. Renderer history (102 submissions), input history (190 accepted/272 observations, no cancelled/clamped inputs) and zero post-close work/file/preparation ownership verify.

This short capture is not a complete episode. Native p95 is 88.8262 ms, maximum 238.4878 ms, with five submissions at least 100 ms and zero failed swaps. Fifty-four submitted scenes have zero source placements and 83 report incomplete viewport coverage; no physical-display conclusion is inferred from these scene counters. Four readbacks were collected, without full source-row/SF/stopped-screen verification in this step. Evidence and the exact app/test APKs are retained under `.artifacts/engine-rewrite-20260906/ntk-search-fallback-root`, including `catalog-plan-0.json`, `document-plan-0.json`, `frames-verification.json`, `inputs-verification.json`, `http-history.json` and the original capture/trace. Search now functions for this regression, but the unsuccessful API round trip before the HTML fallback remains a potential removable latency to investigate. Final corpus remains 0/200; no commit/push.

## 2026-09-07 canonical NTK search and diagnostic storage ownership

The preserved original search page contains a GET form targeting `/search` with q, field=title and match=contains. Title search now uses that path directly, removing the general-catalog API request that ignored keyword. Author search shares the same documented form route. Content kinds continue to come from actual series links. All 162 NTK tests and debug/instrumentation builds pass (`ntk-search-direct-build.log`).

The first full-traversal regression of this candidate failed during GZIP capture output with ENOSPC, not a successful episode. Its 172 compressed capture files total 485869187 bytes; the failed original and trace were pulled and retained under `.artifacts/engine-rewrite-20260906/ntk-search-direct-traversal-root`. About 3.4 GB of old diagnostic traces had accumulated on the 5.8 GB device data partition. After confirming no perfetto client was active, 55 named diagnostic traces were copied into `.artifacts/device-trace-archive-20260907`, matched against device SHA256 before and after transfer, then their exact device files were removed. `receipt.json` records all 3394189609 archived bytes and deletions. App caches, databases, reading positions, AVD size/RAM and security settings were not modified. Device free space increased to 3.6 GB.

The live collector now automatically retires only its own stopped engine-live trace after preserving and twice hash-checking the original host file. A receipt is written before and after removal; malformed paths, non-stopped traces and changed hashes fail without deletion. Six archive tests pass. A new full traversal on the same direct-search APK is running with the existing 90-second traversal deadline and unchanged gestures/performance criteria. No corpus credit is granted by storage cleanup or by this diagnostic.

The post-cleanup full traversal again reaches its 90-second diagnostic deadline, but no storage error occurs. It starts at captured pages p0046..p0047, returns to the first page, then reaches p0053..p0055 by the final captured frames; 310 captures include 56 distinct pages. The 2413-submission journal seals successfully, with zero failed swaps, p95 45.3903 ms, maximum 266.9352 ms and five submissions >=100 ms. All final work/file/preparation ownership counters are zero. The sealed HTTP history contains the direct `/search` request and no keyword API search request. The new trace-archive path succeeds on the real device and retains the original trace on the host. Exact app/test APKs, complete journals and partial captures are retained in `.artifacts/engine-rewrite-20260906/ntk-search-direct-space-regression-root`. This run remains a failed traversal with zero corpus credit. A longer bounded diagnostic may be required to observe the complete return-to-start plus 132-page forward route; changing that collection bound would not relax any latency, missing-frame, source-row or corpus acceptance requirement.

## 2026-09-07 bounded long NTK traversal

Diagnostic traversal now accepts an explicitly fixed 1..300-second collection bound and 1..1024 capture cap, retaining 90 seconds/512 defaults. The bounds are recorded in the collection and successful traversal summary. No production timing/input rule changes. Instrumentation build passes (`ntk-long-traversal-build.log`). With 300 seconds/1024 fixed before execution, the same NTK episode reaches both document endpoints in a 174.852-second test, using 236 actual gestures and 577 captures. It records a fixed final stop with two compositor screenshots. This is endpoint traversal, not full qualification.

The sealed renderer history contains 4275 submissions: native p95 45.1124 ms, maximum 201.1983 ms, three >=100 ms, zero failed swaps, 114 zero-source scenes and 718 incomplete-viewport scenes. Both screenshots consistently name frame 4274/input 8315/geometry 134 and independently match source pixels. Complete HTTP/input/frame journals, original 225-episode catalog order, 132-page document/API order and read-only source export verify. Only 28 source bodies bind to this run's complete HTTP responses; 104 remain cache-origin gaps. An unverified feasibility index finds exact candidate URL and body-digest matches for 131/132 pages across retained older ledgers, with p0107 still missing; it is explicitly not a completed historical-origin proof.

The original trace has 170 unused-TSC clock-path errors and one near-maximum FrameEnd belonging to `TX - StatusBar#79`, PID 853/UID 10181/com.android.systemui. Its actual start is before the episode tap (60929600074200 BOOTTIME vs tap 60934069755600 MONOTONIC, with the recorded clock binding checked by the audit). The audit now permits only an exact foreign SystemUI StatusBar with an independently established UID and start strictly before the conservative tap bound. Seven timeline mutation tests pass, rejecting overlap, viewer identity and unknown process/UID. Removing only that invalid FrameEnd and unused TSC snapshot entries from a nonqualifying diagnostic copy clears the import errors; all binding queries retain the original trace.

Full SurfaceFlinger binding then fails at frame 1663. There is one exact owner/frame/dimension transaction and one causal Binder path, but transaction postTime 60998066074700 ns is 2000 ns after the recorded receiver-buffer release at 60998066072700 ns (receive 60998065869000 ns, Binder ID 6677138, layer 4470, transaction 111377091923330). No timestamp tolerance or acceptance waiver was added. `frame-1663-transaction-diagnostic.json` preserves the discrepancy. The full pixel/source-row and stopped-SF gates remain incomplete because the full surface stage fails. Evidence, exact APKs, original trace, audit reports, source bodies and all captures are in `.artifacts/engine-rewrite-20260906/ntk-long-traversal-root`. Corpus remains 0/200; no commit/push.

## 2026-09-07 ftrace clock collection correction

AOSP Android 15 SurfaceFlinger records postTime with systemTime inside setTransactionState. Perfetto documents that nondefault ftrace clocks, including the explicitly selected MONOTONIC_RAW mode, use best-effort alignment from timestamp pairs; the default is BOOTTIME, which is coherent across CPUs and available to userspace. The live trace config now stops forcing RAW and uses the default. This removes that avoidable alignment step in new captures; it does not repair or qualify the prior 2-microsecond mismatch. No Binder acceptance tolerance changed. References: https://android.googlesource.com/platform/frameworks/native/+/refs/heads/android15-release/services/surfaceflinger/SurfaceFlinger.cpp and https://perfetto.dev/docs/reference/trace-packet-proto . A new same-APK long traversal is being collected under `ntk-boottime-traversal-root`; its actual emitted clock schema and binding outcome remain to be checked.

The offline binder verifier now indexes swap/acquire identities, normalized transaction identities, layer/frame events, Binder ancestor scopes, releases and handler slices once. It preserves all original owner, ancestry, duplicate, dimension and exact time-bound checks. Twenty-nine mutation/equivalence tests pass; the new equivalence case compares indexed and scan-based Binder candidates including duplicate flow records. This is host verification efficiency only, not an app performance improvement.

The BOOTTIME run completed endpoint traversal with 331 gestures and 799 captures; all 16248 original ftrace bundles declare the default BOOTTIME clock. Original trace SHA256 `237f745dceac6c454f67d77033d330d5561f04bd638a42e17f33406acda59fea`. The final submitted frame 6095 is captured. Native history seals 6096 submissions with p95 45.2704 ms and maximum 242.5417 ms. This is not a paired-input performance comparison: preserved resume positions required different gesture counts from the prior run.

Full binding still fails, now at frame 159: one owner/frame transaction has postTime 62050263018100 ns, while the matching Binder send/receive/release are 62050263106800/62050263189000/62050263354000 ns. The setTransactionState handler spans 62050263207300..62050263342200 ns. The discrepancy is 170900 ns before receiver dispatch. TraceProcessor clock_snapshot shows MONOTONIC mapped with zero delta and BOOTTIME mapped with deltas from 10200 to 412100 ns across 271 snapshots. Thus merely removing RAW does not remove cross-clock conversion from the current comparison. No causal acceptance, correction offset or tolerance is inferred from the observed range. `frame-159-transaction-diagnostic.json` and `ftrace-clock-observation.json` preserve these findings under `.artifacts/engine-rewrite-20260906/ntk-boottime-traversal-root` along with exact APKs and original captures/trace. Surface/source-row/stopped-SF verification remains incomplete, and corpus remains 0/200.

The indexed verifier's related surface/live/stopped mutation suite passes 37 tests. A read-only host GPU observation during the run reported GTX 1060 3GB/P8/0% GPU/139 MHz graphics/405 MHz memory/1746 MiB used/8.44 W; this single sample does not establish either saturation or unavoidable cost. Read-only inspection confirms the tracefs clock exposes mono and is shell-writable, but no manual tracefs clock change was made. Kernel suspend statistics were permission-denied and the named suspend-control service was unavailable; security settings were not changed. Any future direct-clock collection needs its actual clock domain and original raw-event correspondence verified, rather than accepting the present mismatch.

## 2026-09-07 original MONOTONIC clock control

A short synthetic control temporarily selects the already shell-writable tracefs `mono` clock with tracing stopped and uses preserve_ftrace_buffer. The collector records clock selection before/start/end and restores the original clock in finally. This does not change AVD RAM, security or app data. The first control's per-CPU buffers lost events, so it is not complete evidence despite 100 observed native-clock brackets matching.

The second control explicitly provisions 4096 KiB per-CPU trace buffers and drains every 50 ms, then restores the original buffer size and clock after stopping. Its original trace has no import errors/data loss. All 307 submitted tokens have unique native swap markers with their literal CLOCK_MONOTONIC values between the original raw viewer_clock and viewer_swap kernel timestamps. Native p95 is 43.1978 ms and maximum 193.3459 ms; this empty-scene control earns no performance or corpus credit. Exact APKs, baseline summary, original trace, restoration receipt and `raw-clock-verification.json` are retained under `.artifacts/engine-rewrite-20260906/monotonic-buffered-control-root`; original trace SHA256 `6e05882d1cfb69b4250cc0e0df3aef40b956b0bfdbfb8fbf93c856f06ba3be05`. Both control traces' device copies were removed only after matching the retained original host bytes.

In this preserve mode, Perfetto emits ftrace_clock=0 even while tracefs is attested `[mono]`; the report exposes that declaration instead of treating converted TraceProcessor timestamps as authoritative. `verify_engine_raw_clock.py` checks original raw timestamps, all expected tokens, unique thread/owner, exact brackets, loss flags and restoration attestations. Five tests reject out-of-bracket values without tolerance, missing/duplicate token evidence, malformed expected sets, foreign ownership and trace loss. This proves raw clock correspondence only. A raw-event Binder/handler/transaction join and full viewer buffer/display regression still remain to be implemented; current viewer trace failures are not waived. The wire fields follow the [official FtraceEvent schema](https://raw.githubusercontent.com/google/perfetto/main/protos/perfetto/trace/ftrace/ftrace_event.proto). Corpus remains 0/200.

## 2026-09-07 raw Binder and SurfaceFlinger clock control

Added `engine_raw_binder.py` to decode original ATRACE begin/end markers, kernel Binder send/receive/debug IDs and generic buffer-release events. It reconstructs per-thread call stacks and message flows using exact IDs, preserves duplicate observations, and never substitutes embedded marker PIDs for kernel thread ownership. Process/thread metadata is used only for identity; timestamps come directly from the original packets. Six tests cover message/destination mismatch, duplicate release, handler outside dispatch, spoofed marker PID, wire decoding, loss flags and graphics sender/clock identity.

On the loss-free buffered MONOTONIC control, all 307 native submissions have one exact consumer acquire/Binder path and one matching SurfaceFlinger transaction. Every original postTime lies inside both the raw receive/release interval and raw setTransactionState handler. No converted timestamp or tolerance is used. `raw-binder-control.json` preserves each message ID and all original bounds. This directly resolves the clock-correspondence mechanism in the control; it does not retroactively qualify the older viewer traces.

The graphics-event decoder additionally requires the trusted SurfaceFlinger PID, UID 1000 and explicit MONOTONIC clock ID 3. Its original Queue/Latch events connect all 307 transactions to the exact layer/frame identity, with no missing token in `raw-queue-latch-control.json`. These remain synthetic controls with zero corpus credit and no physical-presentation claim. Full viewer collector/verifier integration and actual image/row/stopped-buffer regression are still required. The original trace and all control artifacts remain under `.artifacts/engine-rewrite-20260906/monotonic-buffered-control-root`. The graphics wire definition is documented in the [official GraphicsFrameEvent schema](https://raw.githubusercontent.com/google/perfetto/main/protos/perfetto/trace/android/graphics_frame_event.proto). No application cache/position, APK or environment setting was changed in this offline analysis step. Corpus remains 0/200; no commit/push.

## 2026-09-07 full viewer raw timing integration

The live collector now supports `--raw-monotonic-ftrace` with owned setup/restoration, recorded before/during/end clock and buffer values, preserve mode and 50 ms drain. It refuses active tracing and records restoration failure instead of success. Kernel trace buffers round a 4096 KiB request to 4099 KiB on this device; the helper records the actual configured value and requires it to remain unchanged. An initial attempt stopped before trace/instrumentation because it expected exact 4096; its original settings restored successfully. Five clock-ownership tests include active tracing, partial setup failure and kernel rounding.

`engine_raw_trace.py` validates the complete sealed native journal and original clock attestations, reconstructs kernel calls/Binder flows from raw events, and reads graphics Queue/Latch timestamps from explicitly MONOTONIC SurfaceFlinger packets. TraceProcessor provides only identity metadata and literal transaction fields for this path. Both the viewer and stopped-screen verifier use these original timings. The related clock/raw/surface/live/stopped suite passes 53 tests.

The actual NTK raw-MONOTONIC regression completes in 238.732 seconds with 330 gestures and 789 natural captures. All 6023 submitted tokens pass original native/kernel clock correspondence. All 789 captures bind to exact engine frames, kernel Binder messages, caller credentials, transaction buffers and SurfaceFlinger layers/frames. Final stopped buffer 6022 verifies against two actual compositor screenshots and the unchanged raw SF interval. Clock and trace buffer settings restore successfully. Native p95 remains 45.207 ms, maximum 195.7943 ms, two submissions >=100 ms, zero failed swaps. No performance qualification follows from successful buffer binding.

Independent pixel comparison fails for two captures: frame-509/token3888 has max row RGB MAE 9.4472222 and token4536/frame-589 has 8.7466049, against the unchanged 4.0 limit. Their mean errors are 0.2939766 and 0.3056259 respectively. Boundary positions include 65.5 px for p0036/p0037 and 155.498046875 px for p0064/p0065; whether this is raster-edge reference behavior or renderer error remains to be established. No row/pixel tolerance was loosened. Full source-row inventory remains unverified. All 132 source bodies in this run are cache-origin gaps in the same-run HTTP binding.

The bundle runner also had an incorrect success condition: a verifier returning a false result flag was marked completed. It now requires each stage's specific boolean verdict. Three tests reject pixel false, missing image-origin proof despite document HTTP success, missing/number-valued flags and unknown stage contracts. Current hash-matched stage artifacts were reclassified without repeating their calculations; `bundle-before-verdict-fix.json` preserves the old report. The corrected bundle fails HTTP image-origin and pixel-verification stages. Exact APKs, native captures, original trace, raw clock report, full buffer binding, stopped proof and corrected bundle are in `.artifacts/engine-rewrite-20260906/ntk-raw-monotonic-viewer-regression-root` (capture `engine-capture-1788724338014`). Corpus remains 0/200; no commit/push.

## 2026-09-07 pixel-edge diagnosis and device control

The two failed real captures differ above the unchanged row tolerance only at three page-boundary rows. Token 3888 rows 65 and 1712 have ideal-reference RGB row MAE 9.4472222 and 5.4913580, but match the preceding page's clamped last texture row with MAE 0.0450617 and 0.0246914. Token 4536 row 155 has ideal-reference MAE 8.7466049 and preceding-page edge MAE 0.0302469. `pixel-edge-diagnostic.json` retains both candidate comparisons without accepting either failed frame.

Added a debug-only rasterization-state query, executed on the existing GL owner with its window context; release calls reject it. `EngineRasterEdgeTest` draws two separately generated solid-color 720x1098 sources at the actual 1080x2138 viewport through the same owner/renderer, measuring three rows around eight fixed boundary positions. It records GL_SUBPIXEL_BITS=8, SAMPLE_BUFFERS=0 and SAMPLES=0 in every case. A boundary at 65.49609375 selects the lower page at row 65; 65.498046875, 65.4990234375, 65.5 and the nearby tested higher values select the upper page. The actual failed boundary at 155.498046875 also selects the upper page at row 155. Every sampled pixel equals one of the two source colors, with no unexpected color. Debug/instrumentation builds and the device test pass; exact APKs, source fixtures and result are in `.artifacts/engine-rewrite-20260906/raster-edge-control-root`. An initial test compilation used Long values where the existing placement API requires Int; corrected before the successful build.

The independent comparator currently uses ideal continuous bounds with top-inclusive/bottom-exclusive ownership. The [OpenGL ES 3.0 specification](https://registry.khronos.org/OpenGL/specs/es/3.0/es_spec_3.0.pdf), section 3.6 and table 6.28, specifies exclusive ownership for a shared boundary and implementation-dependent subpixel precision; it does not mandate that comparator's chosen page. These measured results support a raster-edge reference mismatch. A predictive, metadata-bound rasterization reference and full regression are still required before reclassifying the two real captures. The RGB tolerance, original-image quality, app input, scrolling, cache and reading positions were not changed. Corpus remains 0/200; no commit/push.

## 2026-09-07 frozen raster-edge model and capture metadata

Added an explicitly nonqualifying raster-edge hypothesis implementing float32 clip/viewport arithmetic, 8-bit nearest-even window-coordinate snapping and upper-page ownership at a shared horizontal edge. Before the next device run, 54 predictions were frozen for six viewport rows (2, 65, 155, 1069, 1712, 2135) and nine offsets around each half-pixel boundary. All 54 device observations match the frozen prediction; no hypothesis changes were made after seeing these observations. Artifacts and exact APKs are in `.artifacts/engine-rewrite-20260906/raster-edge-grid-root`; the prediction file SHA256 is `39cdf9ccb2c9f2394fcddda46e04ea2bd4617533b6f726cd7eab85b475a695cc`. Two model tests pass. The model is not yet used by the ordinary image comparator.

Natural capture tickets now carry immutable rasterization information measured on their GL owner at frame binding. The owner caches it per renderer/surface epoch and queries it only when a capture is pending. A single retained callback avoids per-frame closure allocation. Capture JSON exports subpixel bits, sample-buffer count and sample count. Submission timing now begins before capture binding so query/arming cost is not hidden outside that metric. The normal rendering path does not issue these verification queries without a capture request.

Debug/instrumentation builds pass. The raster-edge test and all GL-owner tests pass together (11 tests), including an assertion that the capture's rasterization metadata equals the queried context values. All 54 grid observations still match the original frozen predictions after this integration. The exact metadata-enabled APKs and results are in `.artifacts/engine-rewrite-20260906/raster-metadata-regression-root`. A metadata-bound, explicitly validated profile must still be connected to the pixel comparator and receive a full real-image regression; older failed captures were not reclassified and the RGB threshold remains 4.0. Corpus remains 0/200; no commit/push.
