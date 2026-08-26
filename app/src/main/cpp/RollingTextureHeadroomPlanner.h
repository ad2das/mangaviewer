#pragma once

#include <cstddef>
#include <cstdint>
#include <limits>
#include <vector>

namespace ntk::rolling {

struct TextureHeadroomKey {
    std::int64_t structureEpoch = 0;
    int page = 0;
    int slot = 0;

    bool operator==(const TextureHeadroomKey& other) const noexcept {
        return structureEpoch == other.structureEpoch && page == other.page &&
            slot == other.slot;
    }
};

struct TextureHeadroomResident {
    TextureHeadroomKey key{};
    std::uint64_t bitmapIdentity = 0;
    std::uint64_t bytes = 0;
    std::uint64_t lastUsedFrame = 0;
};

struct TextureHeadroomIncoming {
    TextureHeadroomKey key{};
    std::uint64_t bitmapIdentity = 0;
    std::uint64_t bytes = 0;
};

struct TextureHeadroomPlan {
    std::vector<std::size_t> evictionIndices;
    std::uint64_t freshBytes = 0;
    std::size_t freshNames = 0;
    std::uint64_t projectedBytes = 0;
    std::size_t projectedNames = 0;
    bool hasUploadWork = false;
    bool valid = true;
    bool arithmeticOverflow = false;
    bool protectedFrameOversize = false;
};

inline std::uint64_t saturatingAddBytes(
        std::uint64_t left,
        std::uint64_t right,
        bool* overflow) noexcept {
    if (left > std::numeric_limits<std::uint64_t>::max() - right) {
        if (overflow != nullptr) *overflow = true;
        return std::numeric_limits<std::uint64_t>::max();
    }
    return left + right;
}

inline std::size_t saturatingAddNames(
        std::size_t left,
        std::size_t right,
        bool* overflow) noexcept {
    if (left > std::numeric_limits<std::size_t>::max() - right) {
        if (overflow != nullptr) *overflow = true;
        return std::numeric_limits<std::size_t>::max();
    }
    return left + right;
}

/**
 * Logical GL names can disappear from the renderer cache before the driver has retired their
 * backing storage. Keep that physical-retirement debt explicit so optional work cannot add more
 * generations and a visible upload can settle the whole batch exactly once.
 */
class TextureRetirementDebt {
public:
    void record(std::uint64_t bytes) noexcept {
        bool ignoredOverflow = false;
        names_ = saturatingAddNames(names_, static_cast<std::size_t>(1), &ignoredOverflow);
        bytes_ = saturatingAddBytes(bytes_, bytes, &ignoredOverflow);
    }

    bool pending() const noexcept { return names_ != 0; }
    std::size_t names() const noexcept { return names_; }
    std::uint64_t bytes() const noexcept { return bytes_; }

    void completeBarrier(bool succeeded) noexcept {
        if (!succeeded) return;
        names_ = 0;
        bytes_ = 0;
    }

private:
    std::size_t names_ = 0;
    std::uint64_t bytes_ = 0;
};

/**
 * Deleted GL names are retirement debt, not an instruction to serialize the very next frame.
 * Direct-Wi-Fi already needs one old and one fresh generation while replacing immutable texture
 * identities, so a two-generation physical window is the smallest useful batching allowance.
 * The owner still settles at genuine idle; this predicate forces an earlier barrier only before
 * another visible allocation would exceed that bounded transient window.
 */
inline bool shouldSettleTextureRetirementBeforeVisibleUpload(
        const TextureRetirementDebt& debt,
        std::uint64_t residentBytes,
        std::size_t residentNames,
        const TextureHeadroomPlan& plan,
        std::uint64_t effectiveBudget,
        std::size_t maximumNames) noexcept {
    if (!debt.pending() || !plan.hasUploadWork || effectiveBudget == 0 || maximumNames == 0) {
        return false;
    }
    bool overflow = false;
    const std::uint64_t transientByteLimit = saturatingAddBytes(
        effectiveBudget, effectiveBudget, &overflow);
    const std::size_t transientNameLimit = saturatingAddNames(
        maximumNames, maximumNames, &overflow);
    std::uint64_t projectedPhysicalBytes = saturatingAddBytes(
        residentBytes, debt.bytes(), &overflow);
    projectedPhysicalBytes = saturatingAddBytes(
        projectedPhysicalBytes, plan.freshBytes, &overflow);
    std::size_t projectedPhysicalNames = saturatingAddNames(
        residentNames, debt.names(), &overflow);
    projectedPhysicalNames = saturatingAddNames(
        projectedPhysicalNames, plan.freshNames, &overflow);
    return overflow || projectedPhysicalBytes > transientByteLimit ||
        projectedPhysicalNames > transientNameLimit;
}

inline bool isProtectedTextureKey(
        const TextureHeadroomKey& key,
        const std::vector<TextureHeadroomIncoming>& incoming) noexcept {
    for (const auto& target : incoming) {
        if (target.key == key) return true;
    }
    return false;
}

/**
 * Plans all visible-frame storage before the first GL allocation.
 *
 * Direct-Wi-Fi identity changes use a fresh GL name and keep the old same-key storage alive until
 * upload succeeds. Consequently every non-exact incoming tile contributes its complete byte/name
 * cost to the aggregate transient requirement. Other profiles mutate an existing name and only
 * contribute its positive resident-byte delta; a missing key still contributes a fresh name.
 * Incoming keys are never eviction candidates. If the protected frame itself cannot fit after all
 * legal victims are removed, the plan explicitly permits that visible oversize instead of
 * deleting a target texture or suppressing real pixels.
 */
inline TextureHeadroomPlan planVisibleTextureHeadroom(
        const std::vector<TextureHeadroomResident>& residents,
        std::uint64_t residentBytes,
        const std::vector<TextureHeadroomIncoming>& incoming,
        std::int64_t structureEpoch,
        bool directWifiFreshNames,
        std::uint64_t effectiveBudget,
        std::size_t maximumNames) noexcept {
    TextureHeadroomPlan plan{};
    if (incoming.empty() || structureEpoch <= 0 || effectiveBudget == 0 || maximumNames == 0) {
        plan.valid = false;
        return plan;
    }

    int incomingMinPage = incoming.front().key.page;
    int incomingMaxPage = incoming.front().key.page;
    for (std::size_t index = 0; index < incoming.size(); ++index) {
        const auto& target = incoming[index];
        // An append-only layout advances the frame epoch without mutating already-installed
        // page pixels. Those immutable visible keys legitimately retain an older positive epoch;
        // only a future key would violate the frame's causal snapshot.
        if (target.key.structureEpoch <= 0 ||
            target.key.structureEpoch > structureEpoch || target.key.page < 0 ||
            target.key.slot < 0 || target.bytes == 0) {
            plan.valid = false;
            return plan;
        }
        incomingMinPage = target.key.page < incomingMinPage
            ? target.key.page : incomingMinPage;
        incomingMaxPage = target.key.page > incomingMaxPage
            ? target.key.page : incomingMaxPage;
        bool duplicate = false;
        for (std::size_t prior = 0; prior < index; ++prior) {
            if (!(incoming[prior].key == target.key)) continue;
            if (incoming[prior].bitmapIdentity != target.bitmapIdentity ||
                incoming[prior].bytes != target.bytes) {
                plan.valid = false;
                return plan;
            }
            duplicate = true;
            break;
        }
        if (duplicate) continue;

        const TextureHeadroomResident* resident = nullptr;
        for (const auto& candidate : residents) {
            if (candidate.key == target.key) {
                resident = &candidate;
                break;
            }
        }
        if (resident != nullptr && resident->bitmapIdentity == target.bitmapIdentity) continue;
        plan.hasUploadWork = true;

        std::uint64_t addedBytes = target.bytes;
        std::size_t addedNames = 1;
        if (!directWifiFreshNames && resident != nullptr) {
            // The ordinary path reuses the same GL name. Its steady resident requirement grows
            // only by the positive size delta; this is a soft residency planner, not a claim
            // about opaque driver allocation internals.
            addedBytes = target.bytes > resident->bytes
                ? target.bytes - resident->bytes : 0;
            addedNames = 0;
        }
        plan.freshBytes = saturatingAddBytes(
            plan.freshBytes, addedBytes, &plan.arithmeticOverflow);
        plan.freshNames = saturatingAddNames(
            plan.freshNames, addedNames, &plan.arithmeticOverflow);
    }
    if (plan.arithmeticOverflow) {
        // Arithmetic overflow is invalid input, not a representable protected-frame oversize.
        // Reject before selecting victims so malformed aggregate work cannot evict live cache.
        plan.valid = false;
        plan.projectedBytes = std::numeric_limits<std::uint64_t>::max();
        plan.projectedNames = std::numeric_limits<std::size_t>::max();
        return plan;
    }

    std::vector<bool> evicted(residents.size(), false);
    std::uint64_t evictedBytes = 0;
    std::size_t evictedNames = 0;
    const auto updateProjection = [&]() noexcept {
        const std::uint64_t retainedBytes = residentBytes -
            (evictedBytes < residentBytes ? evictedBytes : residentBytes);
        plan.projectedBytes = saturatingAddBytes(
            retainedBytes, plan.freshBytes, &plan.arithmeticOverflow);
        const std::size_t retainedNames = residents.size() -
            (evictedNames < residents.size() ? evictedNames : residents.size());
        plan.projectedNames = saturatingAddNames(
            retainedNames, plan.freshNames, &plan.arithmeticOverflow);
    };
    updateProjection();
    if (plan.arithmeticOverflow) {
        plan.valid = false;
        plan.evictionIndices.clear();
        return plan;
    }

    while (plan.projectedBytes > effectiveBudget || plan.projectedNames > maximumNames) {
        std::size_t victim = residents.size();
        const auto evictionClass = [&](const TextureHeadroomResident& entry) noexcept {
            if (entry.key.structureEpoch != structureEpoch) return 4;
            if (entry.key.page < incomingMinPage) return 3;
            if (entry.key.page > incomingMaxPage) return 2;
            return 1;
        };
        const auto prefer = [&](std::size_t candidate, std::size_t incumbent) noexcept {
            if (incumbent == residents.size()) return true;
            const auto& left = residents[candidate];
            const auto& right = residents[incumbent];
            const int leftClass = evictionClass(left);
            const int rightClass = evictionClass(right);
            if (leftClass != rightClass) return leftClass > rightClass;
            if (leftClass == 3 && left.key.page != right.key.page) {
                return left.key.page < right.key.page;
            }
            if (leftClass == 2 && left.key.page != right.key.page) {
                return left.key.page > right.key.page;
            }
            if (left.lastUsedFrame != right.lastUsedFrame) {
                return left.lastUsedFrame < right.lastUsedFrame;
            }
            if (left.key.structureEpoch != right.key.structureEpoch) {
                return left.key.structureEpoch < right.key.structureEpoch;
            }
            if (left.key.page != right.key.page) return left.key.page < right.key.page;
            if (left.key.slot != right.key.slot) return left.key.slot < right.key.slot;
            return candidate < incumbent;
        };
        for (std::size_t index = 0; index < residents.size(); ++index) {
            if (evicted[index] || isProtectedTextureKey(residents[index].key, incoming)) continue;
            if (prefer(index, victim)) victim = index;
        }
        if (victim == residents.size()) {
            plan.protectedFrameOversize = true;
            break;
        }
        evicted[victim] = true;
        plan.evictionIndices.push_back(victim);
        evictedBytes = saturatingAddBytes(
            evictedBytes, residents[victim].bytes, &plan.arithmeticOverflow);
        evictedNames = saturatingAddNames(
            evictedNames, static_cast<std::size_t>(1), &plan.arithmeticOverflow);
        updateProjection();
        if (plan.arithmeticOverflow) {
            plan.valid = false;
            plan.evictionIndices.clear();
            return plan;
        }
    }
    return plan;
}

inline bool canUploadOptionalTextureWithoutEviction(
        const std::vector<TextureHeadroomResident>& residents,
        std::uint64_t residentBytes,
        const TextureHeadroomIncoming& incoming,
        bool directWifiFreshNames,
        std::uint64_t effectiveBudget,
        std::size_t maximumNames) noexcept {
    const std::vector<TextureHeadroomIncoming> targets{incoming};
    const auto plan = planVisibleTextureHeadroom(
        residents, residentBytes, targets, incoming.key.structureEpoch,
        directWifiFreshNames, effectiveBudget, maximumNames);
    return plan.valid && plan.evictionIndices.empty() &&
        !plan.protectedFrameOversize && !plan.arithmeticOverflow;
}

}  // namespace ntk::rolling
