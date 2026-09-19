# Detail header shows the subtitle once (2026-09-19)

## Problem

The detail header rendered its subtitle/authors string twice: once as a
plain hint line and again as the tag chips split from that same string.
On 외모지상주의 (subtitle `일반만화 · 네이버 시리즈`) the header showed

```
일반만화 · 네이버 시리즈
[일반만화] [네이버 시리즈]
```

so the same metadata appeared in two styles and the wrapped hint line also
broke `네이버 시리즈` mid-word in the narrow column.

## Fix

`SeriesDetailScreen.kt` `DetailDescription`:

- split the subtitle/authors string once into tags
- two or more parts -> render the chips only (up to 4, FlowRow wraps)
- a single part -> render the hint line only, as before

No information is dropped for the common cases and the header no longer
repeats itself.

## Verification

- `:app:testDebugUnitTest verifyArchitectureQuality :app:assembleDebug` —
  BUILD SUCCESSFUL.
- Device (emulator 5558, debug, dark), work opened from the home feed so the
  subtitle is present: header now reports only `일반만화` `네이버 시리즈` chips
  plus the full-height `관심 등록됨` row; the plain subtitle line is gone
  (`c13-final` screenshot, `c13e` UI dump).
- Works opened from the saved library carry no subtitle, so their header is
  unchanged (badges + title + status row).
