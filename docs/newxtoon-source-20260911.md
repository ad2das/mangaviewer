# Newxtoon content source — 2026-09-11

Adds `newxtoon` (뉴엑스툰, https://newxtoon1.com) as a third content source beside NTK and WFWF.

## Transport

The provider domain is reachable on the designated emulator only through the app's protected
transport (the ISP resets plain TLS handshakes for the host name; the host browser and scripts
cannot connect). AppGraph builds the source on `transportFactory.protect(...)` and warms the
origin. Device evidence `newxtoon-recon/result.json` shows status 200 for `/`, `/comics`,
`/comics/{id}` and `/comics/{id}/chapters/{id}` through that transport.

## Structure

Server-rendered pages, no ad-guard/ACK:

- list: `/comics?page=N` (cards link to `/comics/{seriesId}`, up to page 369)
- search: `/search?q=`
- series: `/comics/{seriesId}` with `a.chapter-item[data-chapter-id]` rows
- chapter: `/comics/{seriesId}/chapters/{chapterId}` with `div.reader-page-frame img[width,height]`
  delivering the full page list at original aspect; page ids are the absolute image URLs.

`NewxtoonHtmlParser` is pure HTML parsing. `NewxtoonContentSource` implements `ContentSource`
(catalog, search, episodes, manifest with dimensions, adjacent, openPage, openArtwork) and is
registered in `AppGraph` as "뉴엑스툰". Fixtures for unit tests were captured read-only from the
live pages (`source-newxtoon/src/test/resources/newxtoon/`).

## Validation

- `source-newxtoon` unit tests parse the captured catalog, chapter list and reader pages.
- Device test `NewxtoonSourceDeviceTest` (app installed from the patched debug APK) returned:
  catalog 23 cards with next cursor, 7 chapters including the target, 61 pages with dimensions,
  neighbours present, first page 8192 bytes `image/webp`.

## Notes

- No change to NTK/WFWF behavior, thresholds or existing sources.
- The source needs no browser service or challenge handling.
