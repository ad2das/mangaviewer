# Main top bar on narrow phones — 2026-09-18

## Problem

At the emulator's native width (1080 px / 440 dpi = 393 dp) the home top bar ran
out of room: the brand title wrapped to two lines ("MangaVie" / "w") and the
PLUS badge was pushed out of the layout entirely. The site chip (121 dp with its
label) plus the 48 dp account button left the weighted title area only ~147 dp,
while "MangaView" (24 sp Black) plus the badge needs ~176 dp.

## Change

`LibraryScreen.kt` only:

- `MainTopBar` now measures itself with `BoxWithConstraints`; below 400 dp the
  source chip renders its site logo without the text label, and the brand title
  drops from 24 sp to 22 sp.
- The brand title gets `Modifier.weight(1f, fill = false)` with
  `maxLines = 1` + ellipsis, so the PLUS badge is always measured at its
  intrinsic size and the title ellipsizes only if a device is still narrower.
- The chip label and the PLUS badge are pinned to one line each.

## Verification

- `:app:testDebugUnitTest verifyArchitectureQuality :app:assembleDebug` —
  BUILD SUCCESSFUL (arch gate 410 files).
- Emulator 5558 at native 1080x2340 (393 dp): header renders
  "MangaView + PLUS" on one line with the compact logo chip and the account
  button; checked in dark theme. Previously the title wrapped and the badge was
  clipped to "PL".
- No behavior change at >= 400 dp: the chip keeps its label and the title stays
  24 sp.
