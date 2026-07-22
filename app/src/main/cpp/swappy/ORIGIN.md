# Local Swappy fork

This directory contains the Android Games Frame Pacing 2.1.3 sources from
`platform/frameworks/opt/gamesdk` commit
`f81f888fe11e9540dd580edf5993232172ed3cbe`.

Local changes are intentionally limited to:

- keep `SwappyCommon::mPipelineMode` in `PipelineMode::Off` so a one-period
  90 Hz request cannot spend its startup window in the extra-latency pipelined mode;
- suppress legacy API-33 typedef shims because NDK 27 already declares those
  symbols with availability annotations;
- extract only the upstream 2.1.3 archive's embedded `classes_dex.o` at build
  time; all native Swappy implementation symbols come from this local source.

The upstream Apache 2.0 license is in `LICENSE`.
