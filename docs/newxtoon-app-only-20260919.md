# Newxtoon loads on the emulator with the app alone (2026-09-19)

## Problem
Newxtoon routes on the Android emulator kept ending in Cloudflare
`403 mitigated=challenge`, and the earlier "cleared" logs were false positives.

## Root causes found
1. **False clearance detection.** The challenge interstitial is localized. With the
   emulator's ko-KR locale the title is `잠시만 기다리십시오...`, while the app only
   recognized the English `Just a moment...`. The app declared the challenge solved
   mid-flight, kept the WebView, and replayed requests with a cookie that was still
   invalid — which is why every "cleared" attempt was followed by another 403.
2. **Inconsistent browser identity.** The desktop-UA experiment sent a Windows Chrome
   user agent while the WebView engine still sent
   `sec-ch-ua: ... Android WebView ...`, `sec-ch-ua-mobile: ?1`,
   `sec-ch-ua-platform: "Android"`. Cloudflare rejected every challenge submission for
   that mismatch, so the challenge never completed.

## Fix
- `NewxtoonClearance` now presents one coherent identity, on the emulator only
  (`spoofsDeviceIdentity`): the engine user agent with only the model and build id
  swapped to `SM-S918N` / `UP1A.231005.007` (keeps `; wv` and `Version/4.0`), the
  engine's own Android WebView client hints, and matching JavaScript
  (`Linux aarch64`, Adreno 740, Android WebView UA-CH, `mobile: true`). Real devices
  keep the untouched engine identity.
- Clearance detection uses DOM markers instead of the localized title
  (`_cf_chl_opt`, `#challenge-stage`, `.cf-turnstile`, ...), so the interstitial can
  never be mistaken for the real site.
- The solved WebView is kept alive together with its relay and proxy override, and a
  challenged same-origin GET can be replayed inside it (`NewxtoonWebFetch` bridge +
  `NewxtoonClearanceTransport` fallback).
- Support code was split into `NewxtoonChallengeTaps`, `NewxtoonProxyOverride` and
  `NewxtoonWebFetch` to satisfy the architecture gate.

## Verification (emulator 5558, app alone, global proxy `:0`)
- logcat: `challenge finished cleared=true reason=page-cleared` followed by
  `retry status=200 mitigated=null server=cloudflare`.
- Library -> 외모지상주의 (newxtoon chip): cover, 연재중/웹툰 chips and synopsis render.
- 회차 tab: `624개`, 624화 listed; the viewer opens 624화 and draws the episode images.
- Build: `:app:testDebugUnitTest verifyArchitectureQuality :app:assembleDebug`
  BUILD SUCCESSFUL (gate 415 files).

## Known limitations
- The managed challenge still stalls on some attempts on the emulator; `solve()`
  retries three times with fresh cookies and the transport retries twice, which
  covers it in practice.
- The WebView-fetch fallback is a safety net. On this emulator the HTTP route returns
  200 after a genuine solve, so the fallback's success path is plumbed but is not the
  primary path.
- The identity spoof is emulator-gated; phones are unaffected.
