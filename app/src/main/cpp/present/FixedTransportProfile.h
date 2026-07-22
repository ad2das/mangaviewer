#pragma once

#include "FixedPresentEventContract.h"

#include <android/hardware_buffer.h>
#include "../swappy/include/swappy/swappyGL_extra.h"

#include <cstddef>
#include <cstdint>

#ifndef NTK_QUALIFICATION_PROFILE_ID
#define NTK_QUALIFICATION_PROFILE_ID "ntk-native-test-profile"
#endif

namespace ntk::present {

struct FixedTransportProfile {
    static constexpr std::uint32_t kVersion = 1;
    static constexpr std::uint32_t kAhbFormat =
        AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    static constexpr std::uint64_t kAhbUsage =
        AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER |
        AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
        AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY;

    std::uint64_t digest = 0;
    std::uint64_t timingGeneration = 0;
    std::int64_t refreshPeriodNanos = 0;
    std::int64_t appVsyncOffsetNanos = 0;
    std::int64_t presentationDeadlineNanos = 0;
    std::int64_t transportBoundNanos = 0;
};

namespace fixed_transport_detail {

constexpr std::uint64_t fnvOffset = 1469598103934665603ULL;
constexpr std::uint64_t fnvPrime = 1099511628211ULL;

constexpr std::uint64_t appendByte(
        std::uint64_t hash, std::uint8_t value) noexcept {
    return (hash ^ value) * fnvPrime;
}

constexpr std::uint64_t appendU64(
        std::uint64_t hash, std::uint64_t value) noexcept {
    for (int index = 0; index < 8; ++index) {
        hash = appendByte(hash, static_cast<std::uint8_t>(value & 0xffU));
        value >>= 8;
    }
    return hash;
}

constexpr std::uint64_t appendString(
        std::uint64_t hash, const char* value) noexcept {
    if (value == nullptr) return 0;
    for (std::size_t index = 0; value[index] != '\0'; ++index) {
        hash = appendByte(hash, static_cast<std::uint8_t>(value[index]));
    }
    return hash;
}

}  // namespace fixed_transport_detail

inline FixedTransportProfile makeFixedTransportProfile(
        std::int64_t refreshPeriodNanos,
        std::int64_t appVsyncOffsetNanos,
        std::int64_t presentationDeadlineNanos,
        std::uint64_t timingGeneration) noexcept {
    FixedTransportProfile profile;
    profile.timingGeneration = timingGeneration;
    profile.refreshPeriodNanos = refreshPeriodNanos;
    profile.appVsyncOffsetNanos = appVsyncOffsetNanos;
    profile.presentationDeadlineNanos = presentationDeadlineNanos;
    profile.transportBoundNanos = refreshPeriodNanos / 2;
    std::uint64_t hash = fixed_transport_detail::appendString(
        fixed_transport_detail::fnvOffset, NTK_QUALIFICATION_PROFILE_ID);
    hash = fixed_transport_detail::appendU64(hash, FixedTransportProfile::kVersion);
    hash = fixed_transport_detail::appendU64(
        hash, kFixedPresentEventSchemaVersion);
    hash = fixed_transport_detail::appendU64(
        hash, SWAPPY_FIXED_EXTERNAL_CLAIM_VERSION);
    hash = fixed_transport_detail::appendU64(
        hash, SWAPPY_FIXED_EXTERNAL_SUBMISSION_VERSION);
    hash = fixed_transport_detail::appendU64(
        hash, static_cast<std::uint64_t>(refreshPeriodNanos));
    hash = fixed_transport_detail::appendU64(
        hash, static_cast<std::uint64_t>(appVsyncOffsetNanos));
    hash = fixed_transport_detail::appendU64(
        hash, static_cast<std::uint64_t>(presentationDeadlineNanos));
    hash = fixed_transport_detail::appendU64(
        hash, static_cast<std::uint64_t>(profile.transportBoundNanos));
    hash = fixed_transport_detail::appendU64(
        hash, FixedTransportProfile::kAhbFormat);
    hash = fixed_transport_detail::appendU64(
        hash, FixedTransportProfile::kAhbUsage);
    profile.digest = hash;
    return profile;
}

inline bool validFixedTransportProfile(
        const FixedTransportProfile& profile) noexcept {
    return profile.digest != 0 && profile.timingGeneration != 0 &&
        profile.refreshPeriodNanos > 0 &&
        profile.appVsyncOffsetNanos >= 0 &&
        profile.appVsyncOffsetNanos < profile.refreshPeriodNanos &&
        profile.presentationDeadlineNanos > 0 &&
        profile.presentationDeadlineNanos < profile.refreshPeriodNanos &&
        profile.transportBoundNanos == profile.refreshPeriodNanos / 2;
}

inline SwappyFixedExternalTransportProfile toSwappyTransportProfile(
        const FixedTransportProfile& profile) noexcept {
    return {
        .structSize = sizeof(SwappyFixedExternalTransportProfile),
        .version = SWAPPY_FIXED_EXTERNAL_TRANSPORT_PROFILE_VERSION,
        .profileDigest = profile.digest,
        .timingGeneration = profile.timingGeneration,
        .refreshPeriodNanos = profile.refreshPeriodNanos,
        .appVsyncOffsetNanos = profile.appVsyncOffsetNanos,
        .presentationDeadlineNanos = profile.presentationDeadlineNanos,
        .transportBoundNanos = profile.transportBoundNanos,
    };
}

}  // namespace ntk::present
