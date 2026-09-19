# Newxtoon in-app Cloudflare identity — 2026-09-19

## Problem
The app's own SNI recovery already reaches Cloudflare (`challenged 403 ... mitigated=challenge`),
so the remaining 403s were not a network block. On the host relay the proven root cause of the
same 403 was a broken clearance binding: the relay sent the app's Android user agent *and* a
desktop one, and the cookie had been issued to exactly one identity. The app had the same class
of mismatch on its own HTTP routes: the clearance cookie is bound to the client hints the
solving WebView sent, and OkHttp adds none of them.

## Change
- `OkHttpTransportFactory.browserHeaders(clientHints)` — one map with the WebView's client
  hints (`sec-ch-ua`, `sec-ch-ua-mobile`, `sec-ch-ua-platform`, `upgrade-insecure-requests`,
  browser `Accept`/`Accept-Language`). Source-set headers always win.
- `createBrowserLike` uses it; `protect(...)` now forwards the same map into the SNI-recovery
  client, so direct and recovery routes present one identity.
- `AppGraph.createNewxtoonTransport` wires the newxtoon routes to that identity.
- `NewxtoonClearance`: emulator-only device-identity spoofing. `spoofsDeviceIdentity` gates both
  the UA model/build rewrite and the fingerprint script, so real devices keep their true UA and
  JavaScript identity (the script previously ran everywhere).

## Verification
- `:app:testDebugUnitTest verifyArchitectureQuality :app:assembleDebug` — BUILD SUCCESSFUL.
- Emulator 5558 (native 1080x2340): install + cold start, home `인기` triggers the in-app path
  (`challenged 403` -> `solve: starting webview challenge` -> challenge page + Turnstile loads,
  no crash, no regression).
- Not verified: content loading on the emulator. Cloudflare's real challenge still loops there
  (`Verifying...` then a new Ray ID); host Chrome 153 passes the same challenge through the same
  relay and the same user agent, so the emulator environment itself is scored as non-phone.
  This is an emulator limitation, not an app route problem; a real device WebView is the
  environment that completes the challenge.
