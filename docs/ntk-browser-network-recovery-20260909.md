# NTK browser requests on a blocked network

NTK episode lists could load while every reader stayed black. The document and
image transports already used `SniRecoveryTransport`, but the isolated NTK WebView
made its own script and authorization requests without that recovery. The engine
also waited indefinitely for browser authorization and manifest replies when a
subresource failed silently. These code paths explain the reported pattern;
the user's cellular network is not available for direct reproduction here.

The NTK browser process now configures an authenticated loopback CONNECT relay
before allowing WebView navigation. The relay uses the existing encrypted DNS
and ClientHello record fragmentation. Chromium retains HTTPS certificate and
hostname verification, cookies, provider JavaScript, and request/response bytes.
Authorization POSTs remain browser requests and are not replayed by an HTTP
interceptor. The relay does not terminate TLS, and proxy credentials do not reach
the provider. Other application processes retain their existing network setup.

The WebView startup gate waits for the proxy configuration callback. This follows
the process scope and callback requirement documented by Android's
[ProxyController API](https://developer.android.com/reference/androidx/webkit/ProxyController).

A silent browser wait now fails as an ordinary load error after 25 seconds,
including the existing document retirement and binding cleanup. Reader
cancellation retains its cancellation semantics. The reader shows a loading
indicator after 500 ms while the first image is pending and displays a failure
message if loading fails. Neither value is a performance acceptance threshold.

## Validation

- Debug app/test build, release build, release lint, and architecture checks pass.
- Regression tests cover a blocked TLS handshake, an authenticated proxy
  challenge before DNS/upstream connection, unchanged POST/response bytes, no
  proxy credential leakage, and preservation of certificate rejection.
- Browser deadline tests cover silent requests, successful authorization, and
  cancellation when leaving the reader.
- Actual NTK webtoon case 5 and comic case 1 both opened through the real home
  card after removal of only their backed-up complete-plan metadata. Both tests
  proved fresh browser authorization and plan resolution after the tap, then
  restored the exact original plan, database, and preferences. Original image
  bodies were retained; these are not fully empty-cache measurements.
- The WFWF real viewer smoke test also passes, including immediate scrolling,
  its episode picker, and orderly resource shutdown.
- Artifacts are in `.artifacts/account-restore-20260908/ntk-browser-sni-20260909`.
  Tested debug APK SHA-256:
  `d5bf951a2375be2633cc76c6e13c7a29e4e6309f8cd8d55ecbc3042d81842164`.

The user's cellular SNI filter still needs verification on the phone. This patch
does not claim that every network's filtering can be recovered. The fixed
12-case physical presentation and missed-frame performance goal remains unmet.
The fresh-emulator performance screening was paused after case 4 to address this
report; those earlier results used the preceding APK.

## Installable APK parity

The final package comparison caught another defect in the release versioning
script: it removed every `META-INF/` entry, including gRPC providers and coroutine
service registrations. It now removes only old APK/JAR signature entries and
preserves service descriptors, library metadata, and licenses. A ZIP regression
test covers those resources and nested files that are not root signatures.
The phone APK is checked against the tested base APK: every nonsignature entry
except the versioned Android manifest must remain byte-identical, and the signing
certificate must match. The first automatic release attempt was cancelled before
publication when this mismatch was found.
