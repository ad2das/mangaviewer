# Library removal, complete genre paging and provider recovery

## Behavior

Holding a saved series opens a cancellable deletion confirmation. The selected tab determines the scope:

| Tab | Removed | Preserved |
| --- | --- | --- |
| Recent | Recent history and continuation position | Favorite, downloads and bookmarks |
| Favorites | Favorite | Reading position, downloads and bookmarks |
| Downloaded | All series downloads, including running and queued jobs | Reading position, favorite and bookmarks |
| All | Recent history, continuation position, favorite and downloads | Bookmarks |

Database removal is scoped to both provider and series. Reading anchors are removed with progress, while bookmark anchors remain. Cloud synchronization publishes deletions so an old cloud copy cannot immediately restore a removed entry. Download removal cancels and joins matching jobs before removing stored episodes and prevents new matching downloads during removal.

Genre screens previously discarded the provider's `nextCursor`, showing only the first page. They now append pages near the bottom, deduplicate series, retain loaded results after a page failure and offer an explicit retry. Empty intermediate pages and repeated cursors are handled without falsely declaring completion. Back from details retains the catalog's scroll position. A late initial settings snapshot also respects an explicit destination selection.

NTK genre requests use the provider's `ongoing` and `completed` status values for both webtoons and comics. WFWF webtoon genres walk `/ing` and then `/end`; comics retain `/cm`. Genre enumeration is progressive, so opening a large genre does not wait for its entire catalog. Home-screen previews remain bounded previews. This removes the identified app-side omissions; it cannot establish that a provider exposes every work it has ever hosted.

NTK recovery uses the supplied `https://sbxh9.com` and `https://newtoki1.org` entry points, validating their catalog API. WFWF uses its existing observed-move and numbered-address resolver. Verified origins are shared by catalog and viewer transports and persisted for later launches. Rewrites retain the path, query and same-origin Referer/Origin headers. Redirected image CDNs are not probed or saved as catalog origins.

On a failed HTTPS GET/HEAD connection, source transports can retry through an app-owned loopback CONNECT relay. The relay uses authenticated DNS over HTTPS with Cloudflare/Google bootstrap addresses and fragments the TLS ClientHello record inside SNI. It does not decrypt TLS or use a remote content proxy. Normal hostname and certificate verification remain enabled; certificate failures are not treated as a reason to bypass verification. Successful ordinary requests keep their existing transport; recovered hosts use the fallback temporarily. Request deadlines and cancellation remain bounded. Google account transport is unchanged.

## Evidence and limits

- JVM tests cover canceled/running/queued download removal with another series preserved, pagination beyond 100 pages, deduplication, empty intermediate pages, retry, stale requests and cursor cycles. Source tests cover ongoing/completed phase transitions and stable-entry recovery.
- A real TLS test server behind a simulated SNI filter rejects the original ClientHello and accepts the fragmented handshake. The exact response bytes match, relay authorization is absent at the origin, and certificate rejection is preserved.
- Android API 35 tests reached both supplied NTK APIs and the WFWF catalog with ordinary transport deliberately failed, exercising the actual encrypted DNS and relay path. An isolated preference test verified origin persistence and path/query/Referer preservation. An image-CDN redirect required exactly one request and did not change the catalog origin.
- Real Room/cloud tests verified removal and tombstones, preserved bookmarks and anchors, and isolation from another provider's matching series key.
- Final UI/network instrumentation passed all 7 tests in 151.699 seconds, including cancellation and all four deletion scopes, existing navigation, genre detail/back, scrolling a real genre to its end and returning from details to the same visible card bounds. Tests dismiss the real automatic update notice when the local APK version trails the published version.
- Representative live genre enumeration reached the final cursor: NTK webtoon Other, 150 unique works over 3 pages; NTK comic Music, 53 over 2 pages; WFWF webtoon Sports, 71 over 2 pages; WFWF comic SF, 417 over 12 pages. These are September 8 UTC observations of four genres, not an exhaustive audit of all genres or a permanent provider total.
- The relay has not been tested on a physical mobile carrier. TLS fragmentation can depend on the carrier's filtering implementation; emulator connectivity and a simulated filter do not establish universal circumvention.

Local evidence is under `.artifacts/account-restore-20260908/library-network-device-*`, `genre-live-audit`, and the associated build logs. Test harnesses preserve and verify the original database and app settings. A SystemUI render-thread ANR initially blocked UI tests. Restarting SystemUI left its keyguard state inconsistent with the window manager; synchronizing that runtime state restored the screen without rebooting the emulator or changing its configuration. Failed runs are retained as failures.

The final Gradle check passed debug, Android-test and minified release builds, release lint, all module unit tests and the architecture gate over 326 production files. XML reports contain 1,054 unit-test executions, including variant repeats, with zero failures, errors or skips. Lint success does not mean warning-free. Final UI-tested debug SHA-256 is `c5dd914295ab14b00b887ce16de82067fb5dad097edab7cd7db7d7350e5b9263`; test APK is `79660024735b65f5e1f15f81e5afd6f3ec3befe9cc9040f9ec1deb4ca8305669`.

The exact minified release (`7b9038c8fbab1808e083c3e3b621cb133efc69ad642ed91fc933deda6b23b34e`, 9,780,596 bytes) passed four alternating NTK/WFWF opens with four real swipes and Back per iteration, retaining PID 11044. All eight before/after screenshots were inspected and showed source content without a blank viewport or missing strip; launch, failure, closure and fatal-log checks passed. Closed main-process PSS was 74,187 / 73,209 / 78,582 / 72,256 KiB. This is a bounded functional smoke, not long-duration memory or frame-cadence qualification. The original database and app settings were restored and verified, and the debug candidate was restored after the smoke. The emulator boot ID remained unchanged.

These features do not finish the separate viewer performance goal. Existing frame-cadence failures remain active; no image-quality reduction or relaxed performance threshold is part of this change.
