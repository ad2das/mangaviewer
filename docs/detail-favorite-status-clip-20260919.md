# Detail favorite status stays visible (2026-09-19)

## Problem

The series detail header put the favorite status row ("관심 등록됨" / "관심 등록")
at the bottom of a right-hand column that was pinned to the cover's fixed
192dp height with `Arrangement.SpaceBetween`. When the metadata stack (2-line
title + 2-line subtitle + 2 wrapped tag chips) grew past that height, Compose
measured the last child with the remaining 2dp and the status row collapsed.

Device evidence (emulator 5558, dark, 외모지상주의 detail):

- before: `관심 등록됨` semantics bounds `[712,1087][921,1089]` — 2px tall,
  invisible on screen (screenshot `c12-info`).
- the row is present for every work; long subtitles/tags just crushed it.

## Fix

`SeriesDetailScreen.kt` `DetailHeader`:

- header row `height(192.dp)` -> `heightIn(min = 192.dp)`
- description column `fillMaxHeight()` -> `heightIn(min = 192.dp)` (still
  `SpaceBetween`, so short content keeps the bottom-aligned status row)
- status row gets `padding(top = 6.dp)` so it does not touch the chips when
  the column grows past 192dp

The cover keeps its fixed 138x192dp box; the header only grows when the text
column needs it.

## Verification

- `:app:testDebugUnitTest verifyArchitectureQuality :app:assembleDebug` —
  BUILD SUCCESSFUL (32s, 109 tasks).
- Device (emulator 5558, debug build, dark): same work now reports
  `관심 등록됨` at `[712,1109][921,1173]` — 64px tall, pink heart + label
  visible below the chips, 바로 읽기 below it (`c12-fix` screenshot).
