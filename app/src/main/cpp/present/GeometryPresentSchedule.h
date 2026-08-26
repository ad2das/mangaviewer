#pragma once

#include <algorithm>
#include <cstdint>
#include <limits>

namespace ntk::present {

inline std::int64_t saturatedPresentAdd(
        std::int64_t base,
        std::int64_t delta) noexcept {
    if (base <= 0 || delta <= 0) return base;
    constexpr std::int64_t kMax = std::numeric_limits<std::int64_t>::max();
    return base > kMax - delta ? kMax : base + delta;
}

/**
 * Keeps cross-thread geometry submissions on distinct future display slots.
 *
 * The producer's requested time is authoritative while it is still usable. A long buffer upload
 * can leave several FIFO geometry commands behind it, however; forwarding those now-past times
 * makes SurfaceFlinger apply all of the crops in one transaction batch. Rebase only that late
 * case and preserve at least one refresh between already-issued crops. A missing producer time
 * stays missing so device FrameTimeline paths retain their existing next-compositor behavior.
 */
inline std::int64_t scheduleGeometryDesiredPresentNanos(
        std::int64_t requestedPresentNanos,
        std::int64_t applyNowNanos,
        std::int64_t previousDesiredPresentNanos,
        std::int64_t refreshPeriodNanos) noexcept {
    if (requestedPresentNanos <= 0 || applyNowNanos <= 0 ||
        refreshPeriodNanos <= 0) {
        return requestedPresentNanos > 0 ? requestedPresentNanos : 0;
    }
    const std::int64_t minimumLeadNanos = std::max<std::int64_t>(
        1'000'000, refreshPeriodNanos / 4);
    const std::int64_t firstUsablePresentNanos = saturatedPresentAdd(
        applyNowNanos, minimumLeadNanos);
    std::int64_t desiredPresentNanos = std::max(
        requestedPresentNanos, firstUsablePresentNanos);
    if (previousDesiredPresentNanos > 0) {
        desiredPresentNanos = std::max(
            desiredPresentNanos,
            saturatedPresentAdd(
                previousDesiredPresentNanos, refreshPeriodNanos));
    }
    return desiredPresentNanos;
}

/**
 * Selects exactly one cadence owner for a crop transaction.
 *
 * The host-emulator producer is already paced by an absolute Handler deadline. Queuing another
 * future desired-present clock in SurfaceFlinger lets the host implementation collect several
 * otherwise on-time position transactions and commit them together. Submit those crops for the
 * next compositor cut. Device producers retain their existing desired-present schedule when no
 * valid FrameTimeline identity can cross the renderer-thread handoff.
 */
inline std::int64_t geometryDesiredPresentNanosForRuntime(
        bool hostHandlerOwnsCadence,
        std::int64_t requestedPresentNanos,
        std::int64_t applyNowNanos,
        std::int64_t previousDesiredPresentNanos,
        std::int64_t refreshPeriodNanos) noexcept {
    if (hostHandlerOwnsCadence) return 0;
    return scheduleGeometryDesiredPresentNanos(
        requestedPresentNanos,
        applyNowNanos,
        previousDesiredPresentNanos,
        refreshPeriodNanos);
}

}  // namespace ntk::present
