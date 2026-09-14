#pragma once
#include <android/looper.h>

/**
 * One-shot wake for a pending release-fence fd on the owning looper thread. The watcher never owns
 * the fd and never decides readiness: ALooper only notifies, and the caller keeps its nonblocking
 * poll as the sole authority over buffer state. Any notification - INPUT readiness or an ERROR/
 * HANGUP/INVALID fault, which ALooper delivers regardless of the event mask - clears the
 * registration, invokes the sink once with the raw events, and returns 0 so ALooper removes the fd.
 * Registration is attempted at most once per arm() call; failures leave the caller's plain tick
 * poll untouched. All methods must run on the thread that owns the looper.
 */
class ReleaseFenceWatcher final {
public:
    using Wake = void (*)(void* context, int events) noexcept;

    ReleaseFenceWatcher() noexcept = default;
    ReleaseFenceWatcher(const ReleaseFenceWatcher&) = delete;
    ReleaseFenceWatcher& operator=(const ReleaseFenceWatcher&) = delete;
    // Never disarms implicitly: removal must be an explicit owner-thread operation before close.
    ~ReleaseFenceWatcher() noexcept = default;

    /** Registers |fd| with the calling thread's looper; false when unarmed state, missing looper, or addFd fails. */
    bool arm(int fd, Wake wake, void* context) noexcept;
    /** Removes a still-registered fd without closing it; true when a registration was removed. */
    bool disarm() noexcept;
    bool armed() const noexcept { return fd_ >= 0; }

private:
    static int onEvent(int fd, int events, void* data) noexcept;
    void clearRegistration() noexcept;

    ALooper* looper_ = nullptr;
    int fd_ = -1;
    Wake wake_ = nullptr;
    void* context_ = nullptr;
};
