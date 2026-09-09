# Prepare the opening while later originals download

Opening prediction previously downloaded six originals concurrently but waited for
all six before decoding any of the first viewport. A slow later original therefore
prevented already available opening pixels from being prepared before the tap.

`EngineOpeningPreparations` now consumes those downloads in source order and decodes
each available page while later downloads continue. One `EngineOpeningPixels.Preparation`
retains the viewport, byte budget and two-viewport horizon across the arriving pages.
It permanently stops admitting tiles when either existing limit is reached. Original
bytes, tile geometry, saved source position and work identities are unchanged.
The all-six `preparedSnapshot` still means all six originals and the bounded rasters
are ready; it has not been redefined to make the existing opening test pass earlier.

A regression test holds the sixth original indefinitely. The previous code produces
zero decoded tiles. The candidate prepares both opening tiles, within the 80,000-byte
test budget, before that original completes. The real viewer's work subscription
then reuses the first tile; cancelling prediction preserves that borrowed tile and
final closure releases every resource. All 121 app JVM tests, debug and test builds,
release build, release lint and architecture verification passed. The release APK
was built and linted, not device-tested.

## Same-boot measurements

The existing opening test was run unchanged. It opens by normal viewer intent, with
or without automatic preparation in the real library. Disk originals remain available
in both conditions. Each sequence is control / prepared / prepared / control. This
is an opening diagnostic, not physical display timing or a twelve-case qualification.

| APK and case | Control openings, seconds | Prepared openings, seconds | Preparation before tap, seconds |
| --- | --- | --- | --- |
| Baseline, NTK 6, page 0 | 6.25 / 3.84 | 2.75 / 2.96 | 4.00 / 3.66 |
| Candidate, NTK 6, page 0 | 5.04 / 3.61 | 2.93 / 2.86 | 5.73 / 3.99 |
| Candidate, WFWF 10, saved page 16 | 3.99 / 3.01 | 3.49 / 3.12 | 3.33 / 2.89 |

These observations do not establish a large additional end-to-end gain from the
incremental decoding change. In particular, this protocol waits for all-six readiness,
so it does not measure opening during the newly improved partial-preparation interval.
Network and runtime variation remain in the measurements; the initial and final
controls also differ in process warmness. The historical approximately one-second
prepared opening was on an earlier, faster emulator runtime and is not the result
of this current experiment.

All twelve untraced screenshots have matching 1080-by-2138 image viewports within
each sequence, with exactly zero pixel difference. The four candidate NTK viewports
also match the baseline viewport exactly. The WFWF test explicitly verifies the
seeded source anchor at 90% of page 16. These comparisons are between observed
viewports; they do not prove full-episode coverage or physical presentation.

## Remaining preparation after the tap

A separate candidate NTK sequence with a graphics trace passed and reproduced
prepared openings of 2.76 and 2.51 seconds. Trace import reports no error/data-loss
statistics. In its first prepared opening, renderer `engine-gl-2` spends 1,624.55 ms
in initial attachment including its first empty submission, followed by empty
submissions of 329.33 and 336.86 ms before the first image uploads. The two visible
uploads take 81.24 and 33.77 ms and the following submission takes 39.47 ms. Android's
UI RenderThread also initializes the new activity's window during this interval;
its overlapping durations must not be added to the renderer totals.

This identifies a candidate for a larger architectural change: prepare a bounded
graphics owner and its opening textures before the tap, then transfer that owner to
the viewer without recreating its context or reuploading the same pixels. That
change has not been implemented or measured. Surface creation may still be necessary,
and ownership, context loss, memory-pressure cancellation and exact source position
would all need verification. Early context creation after the tap alone has little
head start in this trace: the GL owner already begins within tens of milliseconds
of its first attachment task. No speculative graphics or window policy was adopted.

Evidence: `.artifacts/account-restore-20260908/progressive-opening/`, including the
old failing and new passing test XML, build logs, `device` comparisons and
`prepared-trace`. Every device wrapper restored and verified the original database
and APKs in `finally`, retaining boot `d8e6c21f-09fb-4185-9eda-e16c3e03ebeb`. The
validated candidate is installed separately after verification. No AVD, GPU, RAM,
network or security setting was changed. No personal Google sign-in was attempted.

Candidate debug SHA-256:
`9f873f30876b680be499cffb3ca5071cd19fdc9bccc4be5c15528fa422a23ae0`.
At that installation the instrumentation APK remained
`242fb7feb7daf6702591c3aa9dd047333973e02fb96e611f6c0da647a7c0f177`.
The full first-image, rendering, missed-frame and no-stall goal remains unmet.

The subsequent [home continuation and common-renderer change](home-continuation-and-renderer-preparation-20260909.md)
records the newer installed APKs and the later framework interruption.
