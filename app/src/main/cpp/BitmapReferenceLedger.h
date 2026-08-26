#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <limits>

namespace ntk::rolling {

/**
 * Allocation-free ownership ledger for Java Bitmap global references.
 *
 * Android's JNI global reference keeps the Bitmap object reachable, but it does not prevent
 * Bitmap.recycle() from invalidating its pixels. The renderer therefore publishes exact
 * per-identity ownership instead of forcing Java to wait for the entire render pipeline to go
 * idle. The fixed capacity exceeds the native prewarm queue plus every frame slot, and failure is
 * fail-closed: the caller rejects the new JNI handoff rather than accepting an untracked ref.
 */
template <std::size_t Capacity = 2048>
class BitmapReferenceLedger final {
public:
    static_assert(Capacity > 0);

    bool retain(int identity) noexcept {
        for (std::size_t index = 0; index < size_; ++index) {
            Entry& entry = entries_[index];
            if (entry.identity != identity) continue;
            if (entry.count == std::numeric_limits<std::uint32_t>::max()) return false;
            ++entry.count;
            ++totalReferences_;
            return true;
        }
        if (size_ >= Capacity) return false;
        entries_[size_++] = Entry{identity, 1};
        ++totalReferences_;
        return true;
    }

    bool release(int identity) noexcept {
        for (std::size_t index = 0; index < size_; ++index) {
            Entry& entry = entries_[index];
            if (entry.identity != identity) continue;
            if (entry.count == 0 || totalReferences_ == 0) return false;
            --entry.count;
            --totalReferences_;
            if (entry.count == 0) {
                entries_[index] = entries_[size_ - 1];
                entries_[size_ - 1] = {};
                --size_;
            }
            return true;
        }
        return false;
    }

    bool references(int identity) const noexcept {
        for (std::size_t index = 0; index < size_; ++index) {
            if (entries_[index].identity == identity) return entries_[index].count > 0;
        }
        return false;
    }

    std::size_t activeIdentityCount() const noexcept { return size_; }
    std::uint64_t totalReferenceCount() const noexcept { return totalReferences_; }

private:
    struct Entry {
        int identity = 0;
        std::uint32_t count = 0;
    };

    std::array<Entry, Capacity> entries_{};
    std::size_t size_ = 0;
    std::uint64_t totalReferences_ = 0;
};

}  // namespace ntk::rolling
