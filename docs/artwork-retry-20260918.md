# Covers retry in the background after a transient failure (2026-09-18)

## Problem

A cover fetch that fails once (flaky network, mirror hiccup) left the card on
the title-initial placeholder until its slot was disposed and recomposed by
scrolling away and back — there was no retry. Observed on emulator 5558: the
search result `언니, 이번 생엔 내가 왕비야` showed the placeholder at 13:45 and
13:47, and only loaded its cover after a later fling recreated the card.

## Fix

`Artwork.kt` keeps the cycle-6 behavior — the placeholder is shown immediately
on failure, never a blank pending box — and then retries the fetch in the
background while the slot stays composed:

- new `retryArtworkLoad(attempts, firstDelayMs, load)` helper with backoff,
- 4 attempts at 1s / 2s / 4s / 8s after the first failure,
- skipped entirely when the series has no thumbnail key (nothing to fetch).

A successful retry replaces the placeholder with the cover; a genuinely
unavailable cover still settles on the placeholder after the bounded attempts.

## Verification

- `:app:testDebugUnitTest verifyArchitectureQuality :app:assembleDebug` —
  BUILD SUCCESSFUL. New `ArtworkRetryTest` covers retry-until-success and the
  bounded give-up case.
- Device (emulator 5558, debug build): force-stopped the app, disabled mobile
  data + wifi, relaunched, opened 보관함 (local data). `외모지상주의` rendered
  with the `외` placeholder while offline (14:02). Re-enabled the network and
  waited 17s without touching the screen — the real cover appeared on its own
  (14:03).
