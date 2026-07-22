#pragma once

#include <cstdint>

namespace ntk::attach_generation {

enum class TimeoutDisposition : std::uint8_t {
    FAIL_CANCEL_UNCLAIMED = 0,
    WAIT_FOR_CLAIMED_COMPLETION = 1,
};

enum class SurfaceLossDisposition : std::uint8_t {
    CANCELLED_UNCLAIMED = 0,
    COMPLETE_CLAIMED_THEN_DETACH = 1,
    DETACH_PUBLISHED = 2,
    ALREADY_TERMINAL = 3,
    IDENTITY_MISMATCH = 4,
};

inline TimeoutDisposition timeoutDisposition(
        std::uint64_t requestGeneration,
        std::uint64_t claimedGeneration) noexcept {
    return requestGeneration != 0 &&
            claimedGeneration >= requestGeneration
        ? TimeoutDisposition::WAIT_FOR_CLAIMED_COMPLETION
        : TimeoutDisposition::FAIL_CANCEL_UNCLAIMED;
}

inline SurfaceLossDisposition surfaceLossDisposition(
        std::uint64_t requestGeneration,
        std::uint64_t claimedGeneration,
        std::uint64_t publishedGeneration,
        std::uint64_t terminalGeneration,
        bool identityMatches) noexcept {
    if (!identityMatches || requestGeneration == 0) {
        return SurfaceLossDisposition::IDENTITY_MISMATCH;
    }
    if (terminalGeneration >= requestGeneration) {
        return SurfaceLossDisposition::ALREADY_TERMINAL;
    }
    if (publishedGeneration >= requestGeneration) {
        return SurfaceLossDisposition::DETACH_PUBLISHED;
    }
    if (claimedGeneration >= requestGeneration) {
        return SurfaceLossDisposition::COMPLETE_CLAIMED_THEN_DETACH;
    }
    return SurfaceLossDisposition::CANCELLED_UNCLAIMED;
}

inline bool claimAllowed(
        std::uint64_t requestGeneration,
        std::uint64_t claimedGeneration,
        std::uint64_t terminalGeneration) noexcept {
    return requestGeneration != 0 &&
        claimedGeneration < requestGeneration &&
        terminalGeneration < requestGeneration;
}

inline bool generationStrictlyMonotonic(
        std::uint64_t previousGeneration,
        std::uint64_t nextGeneration) noexcept {
    return nextGeneration > previousGeneration;
}

inline bool publishAllowed(
        std::uint64_t requestGeneration,
        std::uint64_t readyGeneration,
        std::uint64_t requestSurfaceEpoch,
        std::uint64_t publishSurfaceEpoch,
        std::uint64_t requestedGeometryRevision,
        std::uint64_t appliedGeometryRevision,
        bool surfaceLossRequested,
        bool terminal) noexcept {
    return requestGeneration != 0 &&
        readyGeneration == requestGeneration &&
        requestSurfaceEpoch != 0 &&
        publishSurfaceEpoch == requestSurfaceEpoch &&
        requestedGeometryRevision == appliedGeometryRevision &&
        !surfaceLossRequested &&
        !terminal;
}

}  // namespace ntk::attach_generation
