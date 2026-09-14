#include "release_fence_watcher.h"

bool ReleaseFenceWatcher::arm(int fd, Wake wake, void* context) noexcept {
    if (fd < 0 || wake == nullptr || fd_ >= 0) return false;
    ALooper* looper = ALooper_forThread();
    if (looper == nullptr) return false;
    // INPUT-only mask: ERROR/HANGUP/INVALID notifications are always sent regardless of it.
    if (ALooper_addFd(looper, fd, ALOOPER_POLL_CALLBACK, ALOOPER_EVENT_INPUT,
            &ReleaseFenceWatcher::onEvent, this) != 1) return false;
    // Stored beyond this call; the extra reference keeps the looper valid until disarm/one-shot.
    ALooper_acquire(looper);
    looper_ = looper;
    fd_ = fd;
    wake_ = wake;
    context_ = context;
    return true;
}

bool ReleaseFenceWatcher::disarm() noexcept {
    if (fd_ < 0) return false;
    ALooper_removeFd(looper_, fd_);
    clearRegistration();
    return true;
}

int ReleaseFenceWatcher::onEvent(int fd, int events, void* data) noexcept {
    auto& watcher = *static_cast<ReleaseFenceWatcher*>(data);
    if (watcher.fd_ != fd) return 0;
    const Wake wake = watcher.wake_;
    void* context = watcher.context_;
    watcher.clearRegistration();
    wake(context, events);
    return 0;
}

void ReleaseFenceWatcher::clearRegistration() noexcept {
    if (looper_ != nullptr) ALooper_release(looper_);
    looper_ = nullptr;
    fd_ = -1;
    wake_ = nullptr;
    context_ = nullptr;
}
