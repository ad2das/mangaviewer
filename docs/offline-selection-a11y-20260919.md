# Offline-save selection rows expose their state (2026-09-19)

## Problem

The 오프라인 저장 selection screen rendered each episode row with a plain
`Modifier.clickable`. The circle on the left shows the checked state
visually, but the accessibility tree only saw a clickable view: screen
readers could not tell whether an episode was selected, and the row had no
checkbox role.

## Fix

`DownloadSelectionOverlay.kt` `DownloadChoice`:

- `Modifier.clickable` -> `Modifier.selectable(selected = selected,
  role = Role.Checkbox, onClick = click)`

The row now reports the checkbox role and its selected state, and tapping
still toggles the episode exactly as before.

## Verification

- `:app:testDebugUnitTest verifyArchitectureQuality :app:assembleDebug` —
  BUILD SUCCESSFUL.
- Device (emulator 5558, debug, dark): `uiautomator dump` of the selection
  screen shows each visible row as
  `checkable="true" checked="false"` (before the fix the attribute was
  absent). After tapping 623화 the node reports `checked="true"`
  (`c14c`/`c14d` dumps).
