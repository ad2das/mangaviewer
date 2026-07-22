#pragma once

#include "HardwareBufferRenderTargetPool.h"

#include <cstdint>

namespace ntk::present {

struct RendererPostSubmitFatalTransition {
    std::int32_t exactReason = 0;
    bool inputClosed = false;
    bool presentationClosed = false;
    bool engineFailed = false;
    bool authorityFailed = false;
    bool gpuFailed = false;
    bool producerFailed = false;
    bool submittedDraining = false;
    bool stickyFatal = false;
};

inline RendererPostSubmitFatalTransition makeRendererPostSubmitFatalTransition(
        std::int32_t exactReason) noexcept {
    return {
        .exactReason = exactReason,
        .inputClosed = true,
        .presentationClosed = true,
        .engineFailed = true,
        .authorityFailed = true,
        .gpuFailed = true,
        .producerFailed = true,
        .submittedDraining = true,
        .stickyFatal = true,
    };
}

inline bool postSubmitFatalAcceptsActionDown(
        const RendererPostSubmitFatalTransition& state) noexcept {
    return !state.inputClosed && !state.gpuFailed;
}

/** The retirement-and-OnCommit JOIN leaves exactly the just-submitted frame logically unlatched.
 * Older callback records may overlap only because their OnComplete/release proof is still pending. */
inline bool rendererPostSubmitLogicalUnlatchedExact(
        std::uint64_t successfulSubmissionCount,
        std::uint64_t terminalProofCount,
        std::uint64_t maxLogicalUnlatched) noexcept {
    if (terminalProofCount > successfulSubmissionCount) return false;
    const std::uint64_t current =
        successfulSubmissionCount - terminalProofCount;
    return current == 1 && maxLogicalUnlatched == 1;
}

}  // namespace ntk::present
