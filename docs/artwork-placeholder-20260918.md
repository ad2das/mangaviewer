# Missing cover artwork now shows a placeholder (2026-09-18)

## Problem

`SeriesArtwork` drew a flat `mutedSurface` rectangle whenever the bitmap was
null. Null covered three different situations with one appearance:

- the bitmap is still decoding,
- the provider has no `thumbnailKey` for the work,
- opening, reading, or decoding the artwork failed.

A work with no usable cover therefore rendered as an empty dark box forever,
indistinguishable from a pending load. Found on emulator 5558 in the 성장
genre catalog (완결 filter): "취뽀도 없는 회귀" showed only its 완결 badge and
empty cover space while its neighbours rendered normally, still empty after
8+ seconds.

## Fix

`SeriesArtwork` now tracks an explicit `ArtworkState`:

- `Loading` — muted surface, as before (matches the skeleton look),
- `Ready(image)` — the decoded bitmap,
- `Missing` — the provider has no usable artwork.

On `Missing`, the card draws the same muted surface plus the title's first
letter or digit (`artworkPlaceholderLabel`, skipping quotes and punctuation)
centered in the secondary text color. A failed reload at a new slot size
keeps the previously decoded image instead of degrading to the placeholder.

## Verification

- `:app:testDebugUnitTest verifyArchitectureQuality :app:assembleDebug` —
  BUILD SUCCESSFUL; `ArtworkPlaceholderTest` covers Korean/ASCII initials,
  leading punctuation, blank and punctuation-only titles.
- Device: debug APK installed on emulator 5558 (native 1080x2340, dark theme).
  성장 genre + 완결 filter screenshot shows "취뽀도 없는 회귀" with a "취"
  placeholder while covered cards are unchanged.
