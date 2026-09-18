# Newxtoon on the emulator — host relay setup — 2026-09-18

## Problem

`newxtoon1.com` never loads on the Android emulator (AVD `MangaViewerApi35`).
The app's own clearance path fails in two independent ways:

1. This network applies a KCOPA SNI-level block on `newxtoon1.com`. A plain TLS
   ClientHello to the domain is reset; the app's split-ClientHello fragmenter
   gets past the block and receives Cloudflare's `403` challenge, but the
   challenge never clears in the emulator's WebView (Chromium on the emulator
   is flagged; host Chrome 153 passes the same challenge on the same IP).
2. Even with a WebView engine updated to 152.0.7977.88 (x86_64, key `1f38`),
   the managed challenge still loops with a new Ray ID.

The app itself is not at fault: on a real phone the same build clears the
challenge and loads newxtoon. The emulator environment is what Cloudflare
rejects, so the fix lives in the dev environment, not in shipped app code.

## Solution

Route emulator traffic for `newxtoon1.com` through a host-side MITM relay that
re-issues the request with a real Chrome TLS fingerprint and a freshly cleared
`cf_clearance` cookie.

```
app (emulator) --proxy--> mitmdump :8889 (nxt_addon.py)
                             |
                             +-- curl_cffi impersonate="chrome"
                             +-- cf_clearance + session cookies (desktop UA)
                             +-- proxy relay2 :8899 (split-ClientHello)
                                       |
                                       +--> newxtoon1.com (Cloudflare)
```

- `relay2.py` — local HTTP CONNECT proxy that fragments the TLS ClientHello
  into two records (mid-SNI, 50 ms apart). This is the same technique the app's
  `NewxtoonClearance` transport uses, and it still bypasses the KCOPA block.
- `nxt_relay_test.py` — drives host Chrome (CDP) through `relay2` to clear the
  real challenge, then saves the cleared cookies + UA to `nxt_clearance.json`
  and verifies them with `curl_cffi`.
- `nxt_addon.py` — mitmproxy addon. For `*.newxtoon1.com` it drops the app's
  headers (including its Android `user-agent`; sending both that and the
  desktop UA breaks the `cf_clearance` UA binding), sends the cleared cookie jar
  with a Chrome-impersonating `curl_cffi` session through `relay2`, and returns
  the response. Other hosts pass through untouched.

App-side enablers (debug builds only):

- `app/src/debug/res/xml/network_security_config.xml` trusts system + user CAs
  via `debug-overrides`, so the debug build accepts the mitmproxy CA.
- `AndroidBrowserViews` enables WebView devtools only on debuggable builds,
  which allows CDP inspection of the clearance WebView.

## Verification (2026-09-18, emulator 5558)

- Fresh clearance via host Chrome 9555: `cf_clearance` saved, `curl_cffi`
  through `relay2` -> `HTTP 200`, 109,357 bytes.
- With the emulator proxy at `10.0.2.2:8889` and the fixed addon:
  - `relay GET /comics?page=1&sort=popular -> 200 (115116 bytes, 1422 ms)`.
  - Home `인기` feed renders newxtoon works (촉법소년, 나 혼자만 렙 뉴비,
    나노마신, 킬러 배드로, 연애혁명, 전지적 독자 시점).
  - Work detail `나노마신` loads cover, metadata and the episode list
    (320화, 180개 불러옴) through the relay.

## Reproduce

1. `python tools/newxtoon-relay/relay2.py` (listens on `127.0.0.1:8899`).
2. Launch host Chrome with `--proxy-server=http://127.0.0.1:8899` and a CDP
   port, then `python tools/newxtoon-relay/nxt_relay_test.py` to refresh
   `nxt_clearance.json`.
3. `mitmdump -s tools/newxtoon-relay/nxt_addon.py --listen-host 127.0.0.1
   --listen-port 8889 --set block_global=false`.
4. Install the mitmproxy CA as a user CA on the emulator and set the global
   proxy: `settings put global http_proxy 10.0.2.2:8889`.
5. Install the debug APK (debug NSC trusts the user CA) and open newxtoon.

## Caveats

- The clearance cookie is UA- and TLS-fingerprint-bound; it expires and must be
  refreshed with `nxt_relay_test.py`.
- This is a debug/emulator workflow. Release builds do not trust user CAs, and
  real devices do not need any of it.
- `nxt_addon.py` paths are absolute; adjust `CLEAR_PATH`/`RELAY` for your host.
