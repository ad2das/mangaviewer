# Library source favicons — 2026-09-18

## Change

Saved-library cards and bookmark cards identified their provider with a colored
text chip (`NTK`, `WFWF`, …). They now show the provider's site logo (favicon)
inside the same tinted chip, so sources read apart at a glance.

- `LegacySiteArtwork.forSourceOrNull(sourceId)` returns the bundled logo for the
  four providers (`ntk`, `wfwf`, `newxtoon`, `goodtoon`) and `null` otherwise.
  `forSource` keeps the previous WFWF fallback for the top-bar chip and the
  source picker, so those call sites are unchanged.
- `SourceLabelChip` in `SavedLibraryScreen` renders a 16 dp `Image` of the logo
  when one exists and falls back to the old text chip for unknown sources.
  The chip's tinted background (per-source color) is unchanged, and
  `contentDescription` stays `출처: <label>` for screen readers.

## Verification

- `:app:testDebugUnitTest verifyArchitectureQuality :app:assembleDebug` —
  BUILD SUCCESSFUL (arch gate 410 files).
- Emulator 5558 (debug build; app data had been wiped by the earlier
  instrumented run): favorited one NTK work and one WFWF work, then checked
  보관함:
  - NTK item shows the rabbit logo chip, WFWF item shows the wolf logo chip.
  - UI dump confirms `출처: NTK` and `출처: WFWF` accessibility nodes.
  - Source chip switched back to NTK afterwards; the two test favorites were
    left in place.
