# Preparing received originals during fast scrolling

The full performance goal remains unmet. This investigation separates two
preparation costs from the remaining native swap delay. All device captures use
the fixed traversal test APK `e1810fe0…`, the existing AVD and network, and restore
the public APKs and saved data afterward.

## Output allocation

Case 3's native decode trace initially showed two CPU-heavy calls near 100 ms.
Nested trace labels identify the original by SHA prefix and source geometry,
then separate decoder creation, output allocation and pixel decoding. They add
no formatting when tracing is disabled.

The labeled control `trace-ui-case03-decode-identity-01` showed different small
full-page originals, not repeated bands from one original. Decoder creation was
about 0.02 ms. A roughly 6 MiB output allocation took 61.76 ms, of which 61.21 ms
was recorded running on the decode thread. Other allocations consumed about
28–40 ms of CPU time. Some opening delays instead involved runnable waiting.

The native decoder now reuses returned output vectors. Only closed CPU tiles
return storage; a live tile owns its vector exclusively. The pool keeps at most
two idle vectors with a combined capacity of 16 MiB. Exact output size, original
identity validation, RGBA8888, sRGB, target dimensions and crop are unchanged.
The pool does not retain decoded content identities or create GPU textures.

In `trace-ui-case03-decode-reuse-profile-01`, 18 of 21 prepared-period decodes
reused storage. Maximum allocation time was 7.08 ms, versus 61.76 ms in the
control; maximum decode time was 31.53 ms versus 72.35 ms. These runs performed
21 and 24 prepared-period decodes respectively, so summed times are not a
comparison of identical work. The new trace still had a 142.73 ms submission
gap and did not satisfy the goal.

Six device tests passed: immutable identity rejection, native crop allocation,
SurfaceView pixel comparisons with a sentinel frame, exact source rows during
reverse movement/context recreation, and GL loss recovery. This verifies pixel
and ownership behavior, not the full live performance contract.

| Untraced capture | First image, ms | Native P95, ms | Missed submissions | Protocol |
| --- | ---: | ---: | ---: | --- |
| Pool, case 3 | 3115.69 | 12.11 | 12.20% | Failed: launch endpoint not observed before crossing |
| Pool, case 9 | 1088.21 | 35.01 | 22.60% | Passed |
| Earlier native-window app, case 9, idle build daemons | 1080.63 | 28.36 | 19.88% | Passed |
| Pool, case 9, idle build daemons | 963.67 | 24.99 | 10.22% | Passed |

All four preserved complete input and renderer histories with zero cancellation
and had no prepared submission gap of at least 100 ms. Both apps failed native
P95 in the later pair; this does not establish the pool as the cause of that
regression. The parent's idle Gradle/Kotlin build daemons were stopped before
the pair. AVD, GPU, RAM, security and unrelated processes were unchanged.

## Preparation blocked by an earlier missing original

The 142.73 ms gap included eight decodes and eight uploads before one new scene
was offered. Matching SHA prefixes to recorded body completions showed next
episode page 8 had arrived 503.69 ms before its decode started. Pages 6 and 7
waited 371.24 and 485.95 ms respectively. This is time after body completion,
not a measurement of network transfer latency.

`EngineTilePlanner` previously stopped its preparation walk at the first page
whose verified identity was missing. This also prevented preparation of later
originals that had already arrived. The missing-geometry fallback looked at only
three consecutive manifest positions, including unavailable positions.

Preparation now walks the existing linked manifests in order and yields only
verified originals, continuing across missing bodies. Existing preparation
distance, visible-first texture budget, tile dimensions and placement rules
still apply. The missing-geometry fallback prepares up to three received leading
pages. It does not mark absent pages ready, advance input, change display order,
invent source URLs, or treat speculative tiles as visible coverage.

Two reproductions failed on the previous planner and passed after the change:
forward/reverse preparation across a missing body, and an available leading
original beyond five unavailable pages. A third test covers a missing first
original in an explicitly linked next episode and rejects an unavailable next
manifest. Tight budgets and unchanged placements are asserted. All 398 JVM
tests and the architecture quality gate passed.

Evidence lives under `.artifacts/account-restore-20260908/published-performance-20260909/repeated-host-restart/`.
The buffer candidate is `853e3a56…`; the preparation candidate is `c9a3c084…`.
The latter's traced case 3 completed the full traversal with 601 accepted inputs,
eight clamped inputs and zero cancellation. Complete input/renderer histories
passed. First image was 3488.81 ms, native P95 29.70 ms, missed submissions 34.77%,
and one prepared interval edge reached 102.38 ms. It still fails performance.
Page 8's body-to-decode interval was 196.07 ms; later received pages 17 and 20
decoded before page 16 arrived. Transfer ordering differed between runs, so the
deterministic regression tests provide the direct evidence for the walk change.

The separate case 9 pool trace reproduced the native delay: the worst call took
41.60 ms, with 40.53 ms inside the emulator driver's `rcCreateSyncKHR encode`
during `eglSwapBuffers`. Another call waited 26.54 ms in queueing. Application
draw work before swap was less than 1 ms in those examples. Preparation fixes
alone do not resolve that native submission bottleneck.
