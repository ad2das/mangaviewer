# Viewer performance checkpoint, 2026-09-10

This checkpoint retains the current reader, transport and library UI changes. It is not a declaration that scrolling meets the requested performance targets.

## Retained changes

- A single native compositor owns three viewport buffers on API 31+, with ordered transaction submission and explicit buffer completion. Original textures remain at source resolution; older API levels retain the GL window path.
- Original headers can publish dimensions before body decoding. Up to 14 already-started transfers survive changes in viewport demand, matching the existing physical body limit and preserving reversals.
- Immutable session snapshots and known page lookups are reused. Identical verified page notifications and the unused viewport-ready acknowledgement no longer trigger redundant updates.
- NTK original requests use Chromium's TCP pool, reused from document preparation. Silent initial headers have a bounded deadline; original-body validation remains unchanged.
- Current library, search, detail, settings and viewer controls include the existing UI work in this workspace.

## Measured transport improvement

`EngineEqualAddressTransportTest` compares four fresh-client rounds per CDN in QUIC-preferred/TCP/TCP/QUIC-preferred order. All rounds use the same 14 provider-authorized URLs per CDN and verify each body against its captured SHA-256 and length. Both recorded CDNs serve the same 2,819,703 bytes of originals.

| CDN | First-page completion, QUIC-preferred | First-page completion, TCP |
| --- | --- | --- |
| booktoki9.org | 2.523 s, 2.312 s | 1.676 s, 1.806 s |
| mana.apihost93.com | 2.916 s, 2.646 s | 1.647 s, 1.624 s |

Across eight rounds, mean first-page download completion improved from 2.599 s to 1.688 s, a 35.05% reduction. All 112 bodies matched. This excludes authorization, decoding and presentation. Server cache state was not controlled, and the negotiated wire protocol was not recorded. These results compare transport settings and do not qualify the complete reader or fixed 12-case corpus.

## Remaining scrolling limitations

The same prepared-image control, without download or decode during motion, still reproduces common display delays. Android hardware Canvas takes only 0.060–0.072 ms P95 to record drawing commands, while window frame duration reaches approximately 100 ms P95 and offered cadence misses approximately 47% of expected slots. A separate trace places substantial delay inside the emulator composer's `rcCompose` request, averaging 24–27 ms per call.

This identifies a bottleneck beyond the reader's drawing commands; it does not establish a permanent hardware ceiling or prove that app optimization is exhausted. Previous ordinary Android restart measurements recovered much faster performance on the same APK, so restart effects must not be reported as code improvements. No AVD, GPU, network or security settings were changed for these comparisons.

Cold input backlogs and visible episode-end coverage, complete physical presentation verification, one source-row comparison, and final fixed-12 qualification remain open. Input order/distance, exact saved positions, image quality and verification thresholds have not been relaxed. Original app packages, account/library data and settings were restored and independently verified after device tests.

The earlier compositor documents retain historical experiments and failures. This document supersedes their current-state summaries.

## Commit-time validation

Engine, storage, NTK source and app unit tests passed; debug app and instrumentation APK assembly passed. The source-export helper passed all five regression tests. The separate architecture gate still reports twelve existing library UI function-length/complexity findings. Those limits were not relaxed, and this checkpoint does not claim a clean architecture gate.
