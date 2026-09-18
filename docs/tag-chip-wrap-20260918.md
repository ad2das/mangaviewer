# Detail header: tag chips stay on one line (2026-09-18)

## Problem

The series detail header renders its subtitle tags (authors, or the
`genre · platform` fallback such as `일반만화 · 네이버웹툰`) in a narrow info
column next to the cover. The chip row used a plain `Row`, so the second chip
only received the leftover width — on a squeezed column that was about 30dp,
and the label wrapped mid-word onto three lines:

```
[일반만화] [네이
          버웹
          툰]
```

Seen on emulator 5558 (native 1080x2340, dark theme) on a detail screen whose
authors are blank and whose subtitle falls back to the `genre · platform`
string.

## Fix

`SeriesDetailScreen.kt`:

- `TagChip` text now uses `maxLines = 1, overflow = TextOverflow.Ellipsis`,
  so a chip can never break mid-word again.
- The chip row changed from `Row` to `FlowRow` with 6dp horizontal and
  vertical spacing, so a chip that does not fit next to the first one moves
  to its own line as a whole chip instead of being compressed.

## Verification

- `:app:testDebugUnitTest verifyArchitectureQuality :app:assembleDebug` —
  BUILD SUCCESSFUL.
- Device (emulator 5558, dark): debug APK installed and reviewed.
  - `죽지 말아줘!` detail — single author chip `지와`, one line.
  - `빙의자 베네핏이 없음` detail — two author chips `괴나나` `설동원` side
    by side in the narrow column, each on one line, no squeeze.
