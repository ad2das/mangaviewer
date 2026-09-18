# Update dialog: consistent version labels (2026-09-18)

## Problem

The in-app update dialog rendered the installed build's version as a raw
`longVersionCode` while the released build used a `"name (code)"` label:

```
현재 버전: 2147000000
배포 버전: 5.0.0 (2147000057)
```

Both rows describe the same kind of value, so the mismatch looked like a
broken version readout to users (found during a dark-theme device review on
emulator 5558).

## Fix

- `UpdateRelease` now exposes `formatLabel(versionName, version)`; the
  existing `label` property delegates to it, so the format lives in one place.
- `AppUpdateViewModel` keeps the installed `PackageInfo` and builds
  `installedLabel` with the same formatter, then uses it in the dialog text.

Resulting dialog:

```
현재 버전: 5.0.0 (2147000000)
배포 버전: 5.0.0 (2147000058)
```

Blank or missing `versionName` still falls back to the bare version code on
both sides.

## Verification

- `:app:testDebugUnitTest verifyArchitectureQuality :app:assembleDebug` —
  BUILD SUCCESSFUL; new unit test covers name/code, null, and blank inputs.
- Device: debug APK installed on emulator 5558 (native 1080x2340, dark theme),
  update dialog screenshot shows both rows in `name (code)` form.
