# NTK webtoon cover mirrors — 2026-09-18

## Symptom (Kovak)

NTK 웹툰 (webtoon) cover thumbnails stayed as empty gray tiles while 만화 (comic)
covers loaded normally.

## Root cause

Webtoon thumbnails are served from `aws-cdn9.site`, which is on the Korean
court-ordered block list: port 80 answers `302 http://warning.kcopa.or.kr/`, and
port 443 resets the TLS handshake at the SNI. Comic thumbnails come from a
different host (`booktoki8.org`), which is why only webtoons failed.

- The block is hostname/SNI based, not a trust-anchor problem. The relaxed TLS
  client introduced earlier (`64a39b4f5`) was the wrong layer; the handshake
  never completes.
- TLS ClientHello fragmentation (the app's `LocalTlsRelay` trick) does not
  bypass this block: a probe splitting the record inside the SNI and one
  sending it whole both end in `ConnectionResetError` — the filter reassembles.
- The numbered CDN domains front one bucket. `aws-cdn1.site` serves every
  sampled webtoon thumb at the same path (verified on host and device), while
  `aws-cdn8.site` is HTTP-blocked (302 warning) and `aws-cdn10.site` does not
  carry those objects (404).
- The emulator additionally black-holes IPv6, so each blocked-host attempt
  burned two 10 s AAAA connect timeouts before IPv4 was tried.

## Fix

- `ProviderImageTrust.mirrorCandidates(url)` — for an `aws-cdnN.site` URL,
  rewrites the host to the other numbers (1..12) keeping scheme, path and query.
- `ProviderImageTransport` — when a provider image host fails or answers with a
  non-image (warning redirect), it retries the same object on the mirror hosts.
  A candidate only counts when it returns `2xx` with an `image/*` content type;
  the first working mirror is remembered per original host and tried before the
  blocked host on later requests. If every candidate fails, the original
  response (or first failure) is preserved, so behaviour without a mirror is
  unchanged.
- `ProviderImageDns` (`AndroidIpv4FirstDns.kt`) — drops the AAAA family when an
  A record exists; IPv6-only answers still pass through. Applied to the
  provider-image client in `OkHttpTransportFactory.createForProviderImages`.
  A blocked host now fails in ~100 ms instead of ~20 s, which is what lets the
  mirror engage immediately.

## Verification

- `:data:testDebugUnitTest :app:testDebugUnitTest` — 7 new
  `ProviderImageMirrorFallbackTest` and 2 `ProviderImageDnsTest` cases pass.
- `verifyArchitectureQuality` — gate passed.
- `:app:assembleDebug` — BUILD SUCCESSFUL.
- Device (emulator-5558, MangaViewerApi35), `NtkArtworkProbeDeviceTest`:
  WEBTOON `openArtwork bytes=135368 elapsedMs=581` (exact byte count of the
  same object served by `aws-cdn1.site`; `aws-cdn9.site` blocked), COMIC
  `bytes=704176 elapsedMs=660` from `booktoki8.org` unchanged.
- Visual: NTK 웹툰 home hero and 이번 주 인기 TOP grid render covers.
