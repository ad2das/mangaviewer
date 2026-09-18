# Viewer failure card speaks Korean (2026-09-18)

## Problem

A failed page fetch surfaced the provider exception's raw English message on
the viewer failure card. Observed on emulator 5558, dark theme: the detail
screen's 정보 tab rendered `viewer-failure` with **"Page request returned
HTTP 403"** while every other message in the app is Korean. The text comes
straight from `PageHttpException` (engine-v2), and any English `IOException`
message ("Every NTK document protocol failed ...", "Unable to resolve host
...") leaked the same way.

## Fix

- New `viewerFailureMessage(failure)` in `ViewerFailureMessages.kt`:
  - `PageHttpException` -> `페이지를 불러오지 못했습니다 (HTTP <code>)`,
  - messages that already contain Hangul pass through unchanged,
  - blank or English-only messages fall back to
    `페이지를 불러오지 못했습니다`.
- `ViewerScreenUi.showFailure` now uses the helper instead of printing
  `failure.message` verbatim.

## Verification

- `:app:testDebugUnitTest verifyArchitectureQuality :app:assembleDebug` —
  BUILD SUCCESSFUL. New `ViewerFailureMessagesTest` covers the HTTP-status
  mapping, Hangul pass-through, and the English/blank fallbacks.
- Device (emulator 5558, debug build): opened an uncached episode (614화) in
  the viewer with the network disabled — the failure card rendered
  `페이지를 불러오지 못했습니다` with 닫기 / 다시 시도 buttons, no raw
  English (`c10fix` screenshot, 14:2x).
