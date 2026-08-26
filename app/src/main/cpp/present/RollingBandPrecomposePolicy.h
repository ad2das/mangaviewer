#pragma once

namespace ntk::present {

/**
 * A geometry-only frame can differ from the applied band for two distinct reasons:
 *
 *  - pixels were added outside the current viewport while the band origin stayed fixed; or
 *  - the rolling band origin advanced and the old overlap is temporarily displaying the new
 *    viewport through a translated source crop.
 *
 * Both cases need one parked successor before the new pixels reach the viewport. Band composition
 * runs on a shared-context worker, so physical motion is precisely when the immutable overlap must
 * be spent preparing the successor; suppressing it until motion ended discarded that runway and
 * forced a blocking composition at the old band's edge. The renderer retains one exact successor,
 * and visible pixel changes still fail closed through the viewport matcher.
 */
inline bool shouldPrecomposeRollingBandSuccessor(
        bool geometryApplied,
        bool entireBandAlreadyApplied,
        int appliedViewportSourceTop,
        int requestedViewportSourceTop,
        bool physicalMotionActive = false) noexcept {
    (void)physicalMotionActive;
    return geometryApplied && !entireBandAlreadyApplied &&
        appliedViewportSourceTop >= 0 && requestedViewportSourceTop >= 0;
}

}  // namespace ntk::present
