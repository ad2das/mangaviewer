#pragma once

#include <cstddef>
#include <cstdint>
#include <functional>
#include <mutex>
#include <optional>
#include <unordered_map>
#include <utility>

namespace ntk::surface_lease {

template <typename Window>
class Registry final {
public:
    using Releaser = std::function<void(Window*)>;

    struct Transfer final {
        std::uint64_t leaseId = 0;
        std::uint64_t surfaceEpoch = 0;
        Window* window = nullptr;

        explicit operator bool() const noexcept {
            return leaseId != 0 && surfaceEpoch != 0 && window != nullptr;
        }
    };

    explicit Registry(Releaser releaser)
        : releaser_(std::move(releaser)) {}

    Registry(const Registry&) = delete;
    Registry& operator=(const Registry&) = delete;

    ~Registry() {
        std::unordered_map<std::uint64_t, Entry> remaining;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            remaining.swap(entries_);
        }
        for (auto& entry : remaining) releaseWindow(entry.second.window);
    }

    std::uint64_t acquire(Window* window, std::uint64_t surfaceEpoch) {
        if (window == nullptr || surfaceEpoch == 0) return 0;
        std::lock_guard<std::mutex> lock(mutex_);
        const std::uint64_t leaseId = nextLeaseId_++;
        if (leaseId == 0) return 0;
        const auto inserted = entries_.emplace(
            leaseId, Entry{surfaceEpoch, window});
        if (!inserted.second) return 0;
        return leaseId;
    }

    std::optional<Transfer> transfer(
            std::uint64_t leaseId,
            std::uint64_t surfaceEpoch) {
        std::lock_guard<std::mutex> lock(mutex_);
        const auto found = entries_.find(leaseId);
        if (found == entries_.end() ||
            found->second.surfaceEpoch != surfaceEpoch) {
            return std::nullopt;
        }
        Transfer transfer{leaseId, surfaceEpoch, found->second.window};
        entries_.erase(found);
        return transfer;
    }

    bool release(
            std::uint64_t leaseId,
            std::uint64_t surfaceEpoch) {
        Window* window = nullptr;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            const auto found = entries_.find(leaseId);
            if (found == entries_.end() ||
                found->second.surfaceEpoch != surfaceEpoch) {
                return false;
            }
            window = found->second.window;
            entries_.erase(found);
        }
        releaseWindow(window);
        return true;
    }

    std::size_t size() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return entries_.size();
    }

    bool contains(
            std::uint64_t leaseId,
            std::uint64_t surfaceEpoch) const {
        std::lock_guard<std::mutex> lock(mutex_);
        const auto found = entries_.find(leaseId);
        return found != entries_.end() &&
            found->second.surfaceEpoch == surfaceEpoch;
    }

private:
    struct Entry final {
        std::uint64_t surfaceEpoch = 0;
        Window* window = nullptr;
    };

    void releaseWindow(Window* window) noexcept {
        if (window == nullptr) return;
        releaser_(window);
    }

    const Releaser releaser_;
    mutable std::mutex mutex_;
    std::unordered_map<std::uint64_t, Entry> entries_;
    std::uint64_t nextLeaseId_ = 1;
};

}  // namespace ntk::surface_lease
