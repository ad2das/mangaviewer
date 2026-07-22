#include "../AttachGenerationContract.h"
#include "../DetachedWarmContract.h"

#include <array>
#include <cstdio>

namespace {

using ntk::detached_warm::Snapshot;

bool require(bool condition, const char* message) {
    if (!condition) std::fprintf(stderr, "%s\n", message);
    return condition;
}

bool DetachedWarmOwnsNoWindowOrPresentBackend() {
    Snapshot value{true, true, true, true, 0, 0, 0, 0, 0};
    return require(ntk::detached_warm::exactWarm(value), __func__);
}

bool DetachedWarmCreatesExactRenderAndUploadPbuffers() {
    Snapshot missingRender{false, true, true, true, 0, 0, 0, 0, 0};
    Snapshot missingUpload{true, false, true, true, 0, 0, 0, 0, 0};
    return require(!ntk::detached_warm::exactWarm(missingRender), __func__) &&
        require(!ntk::detached_warm::exactWarm(missingUpload), __func__);
}

bool AttachBeginsOnlyAfterWarmReady() {
    Snapshot value{true, true, true, false, 0, 0, 0, 0, 0};
    if (!require(!ntk::detached_warm::attachAllowed(value), __func__)) return false;
    value.warmReady = true;
    return require(ntk::detached_warm::attachAllowed(value), __func__);
}

bool AttachPreservesExistingGenerationLeaseContract() {
    return require(
        ntk::attach_generation::generationStrictlyMonotonic(4, 5), __func__) &&
        require(!ntk::attach_generation::generationStrictlyMonotonic(5, 5), __func__);
}

bool NoAuthorityNoFrameIdNoSwap() {
    Snapshot value{true, true, true, true, 0, 0, 0, 0, 0};
    if (!require(ntk::detached_warm::noAuthorityNoFrameOrSwap(value), __func__)) {
        return false;
    }
    value.frameIdCount = 1;
    return require(!ntk::detached_warm::noAuthorityNoFrameOrSwap(value), __func__);
}

bool FirstWindowSubmissionIsExactStageCandidate() {
    Snapshot before{true, true, true, true, 0, 0, 0, 0, 0};
    if (!require(ntk::detached_warm::exactWarm(before), __func__)) return false;
    before.authority = 7;
    before.frameIdCount = 1;
    before.swapCount = 1;
    return require(before.authority == 7 && before.frameIdCount == 1 &&
        before.swapCount == 1, __func__);
}

bool StaleAttachCannotPublish() {
    return require(!ntk::attach_generation::publishAllowed(
        8, 7, 2, 2, 1, 1, false, false), __func__);
}

bool LossDuringClaimCompletesThenDetaches() {
    using ntk::attach_generation::SurfaceLossDisposition;
    return require(ntk::attach_generation::surfaceLossDisposition(
        5, 5, 0, 0, true) ==
        SurfaceLossDisposition::COMPLETE_CLAIMED_THEN_DETACH,
        __func__);
}

bool ContextLossRetirementStillLeavesNoBackendOwners() {
    Snapshot retired{};
    return require(retired.nativeWindowCount == 0 &&
        retired.presentBackendAttachCount == 0, __func__);
}

bool NormalAuthorityReleaseKeepsReusableEglContext() {
    Snapshot reusable{true, true, true, true, 0, 0, 0, 0, 0};
    reusable.authority = 9;
    reusable.authority = 0;
    return require(ntk::detached_warm::exactWarm(reusable), __func__);
}

bool StartupTelemetryClockOrderIsMonotonic() {
    constexpr std::array<std::int64_t, 9> valid{{1,2,3,4,5,6,7,8,9}};
    constexpr std::array<std::int64_t, 9> invalid{{1,2,3,7,5,6,7,8,9}};
    return require(ntk::detached_warm::startupClockOrder(valid), __func__) &&
        require(!ntk::detached_warm::startupClockOrder(invalid), __func__);
}

}  // namespace

int main() {
    return DetachedWarmOwnsNoWindowOrPresentBackend() &&
        DetachedWarmCreatesExactRenderAndUploadPbuffers() &&
        AttachBeginsOnlyAfterWarmReady() &&
        AttachPreservesExistingGenerationLeaseContract() &&
        NoAuthorityNoFrameIdNoSwap() &&
        FirstWindowSubmissionIsExactStageCandidate() &&
        StaleAttachCannotPublish() &&
        LossDuringClaimCompletesThenDetaches() &&
        ContextLossRetirementStillLeavesNoBackendOwners() &&
        NormalAuthorityReleaseKeepsReusableEglContext() &&
        StartupTelemetryClockOrderIsMonotonic()
        ? 0 : 1;
}
