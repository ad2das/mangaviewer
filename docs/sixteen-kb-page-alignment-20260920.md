# 16 KB page size support for the viewer natives

Date: 2026-09-20

## Symptom

Kovak's device (samsung SM-S918N, Android 16 / sdk 36) killed the app right after launch with a
native crash. The app's own report dialog recorded `kind=exit`, `reason=native-crash (5)`,
`status=5` (SIGTRAP).

## Finding

`libviewer_native.so` was linked with 4 KB segment alignment (all `PT_LOAD` `p_align = 0x1000`)
while every other packaged library (`libc++_shared.so`, the AndroidX natives) already used
`0x4000`. On a 16 KB page kernel the system flags such an app: launching the pre-fix release
build on the `google_apis_ps16k` API 36 emulator logs

```
W/AppWarnings: Showing PageSizeMismatchDialog for package ml.melun.mangaview
```

The emulator's compatibility path still ran the misaligned build (with the mismatch warning),
so the reported SIGTRAP was never reproduced locally; on a device without that tolerance the
same class of misalignment is documented to kill apps at load or run time.

## Fix

`app/build.gradle` now passes `-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON` to CMake. The NDK r27
toolchain turns that into `-Wl,-z,max-page-size=16384` for `arm64-v8a` and `x86_64`.

## Verification

- `llvm-readelf -l` on the built `libviewer_native.so`: all `LOAD` segments report
  `Align 0x4000` for both ABIs (was `0x1000`).
- `zipalign -c -P 16 4 app-release.apk` -> `Verification successful`.
- 16 KB emulator (`system-images;android-36;google_apis_ps16k;x86_64`, `getconf PAGESIZE` =
  16384): the pre-fix build raises the system `PageSizeMismatchDialog`; the fixed build
  launches with no mismatch warning, no crash, both processes alive.
