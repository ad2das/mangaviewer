# Offline-save confirmation closes on an outside tap (2026-09-19)

## Problem

The 오프라인 저장 confirmation was a custom overlay: the 55% scrim only dimmed
the screen, so the only way out was the 취소 button. The app's Compose
dialogs (for example the update dialog, `Dialog(onDismissRequest = dismiss)`)
close on an outside tap, so this overlay broke the house pattern.

## Fix

`DownloadSelectionOverlay.kt` `DownloadConfirmation`:

- the scrim `Box` gets `pointerInput { detectTapGestures { dismiss() } }`
- the dialog `Column` consumes its own taps, so tapping the card does not
  close the dialog

## Verification

- `:app:testDebugUnitTest verifyArchitectureQuality :app:assembleDebug` —
  BUILD SUCCESSFUL.
- Device (emulator 5558, debug, dark): selection screen -> one episode
  selected -> 선택 저장 -> dialog shows `외모지상주의 을(를) 오프라인
  저장하시겠습니까? [ 총 1화 ]` with 취소/저장 (`c16c` dump). Tapping the
  scrim at (540,600) closes the dialog and keeps the selection
  (`c16d` dump: no dialog nodes, still `1개 선택`).
