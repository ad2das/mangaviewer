#pragma once

#include <android/hardware_buffer.h>
#include <android/native_window.h>

#include <cstddef>
#include <cstdint>
#include <memory>

namespace ntk::present {

/**
 * Immutable display-resolution page tile submitted directly to SurfaceFlinger.
 *
 * The backing AHardwareBuffer is unlocked and may still have an asynchronous write in flight.
 * The presenter duplicates the borrowed acquire fence when the tile becomes a layer buffer,
 * takes its own strong buffer reference, and retires replaced references only after the
 * compositor's previous-release fence signals.
 */
struct DirectTileLayerInput {
    AHardwareBuffer* buffer = nullptr;
    /** Borrowed; storage retains ownership. SurfaceFlinger receives a duplicate. */
    int acquireFenceFd = -1;
    std::uint64_t contentIdentity = 0;
    std::int64_t structureEpoch = 0;
    std::int32_t page = 0;
    std::int32_t slot = 0;
    std::int32_t sourceTop = 0;
    std::int32_t sourceBottom = 0;
    std::int32_t sourceHeight = 0;
    std::uint32_t contentWidth = 0;
    std::uint32_t contentHeight = 0;
    float pageTop = 0.0F;
    float pageHeight = 0.0F;
};

struct DirectTileFrameInput {
    std::uint64_t token = 0;
    std::uint64_t producerSceneId = 0;
    std::int64_t structureEpoch = 0;
    std::int32_t bandWidth = 0;
    std::int32_t bandHeight = 0;
    std::int32_t viewportSourceTop = 0;
    std::int32_t viewportSourceHeight = 0;
    const DirectTileLayerInput* tiles = nullptr;
    std::size_t tileCount = 0;
};

enum class DirectTilePresentEventKind : std::uint8_t {
    COMMITTED = 0,
    PRESENTED = 1,
    FAILED = 2,
    PRODUCER_SUBMITTED = 3,
};

struct DirectTilePresentEvent {
    DirectTilePresentEventKind kind = DirectTilePresentEventKind::FAILED;
    std::uint64_t token = 0;
    std::uint64_t producerSceneId = 0;
    std::int64_t structureEpoch = 0;
    std::int64_t completedNanos = 0;
    std::int64_t observedNanos = 0;
    bool contentChanged = false;
};

/**
 * Bounded multi-layer SurfaceControl presenter for the emulator exact-CPU profile.
 *
 * It removes the 16-24 MiB rolling-band replacement from physical scrolling. Each already
 * decoded page tile stays in its own small layer; ordinary MOVE frames update only container
 * geometry. Producer submission is reported distinctly from optional compositor-fence evidence,
 * while OnComplete and previous-release fences retain exact buffer ownership.
 */
class DirectTileSurfacePresenter final {
public:
    using WakeCallback = void (*)(void*) noexcept;

    DirectTileSurfacePresenter();
    ~DirectTileSurfacePresenter();

    DirectTileSurfacePresenter(const DirectTileSurfacePresenter&) = delete;
    DirectTileSurfacePresenter& operator=(const DirectTileSurfacePresenter&) = delete;

    bool attach(
        ANativeWindow* parentWindow,
        std::uint32_t destinationWidth,
        std::uint32_t destinationHeight,
        float frameRate,
        WakeCallback wakeCallback,
        void* wakeContext) noexcept;

    bool attached() const noexcept;
    bool canPresent() const noexcept;
    std::uint32_t failureReason() const noexcept;
    std::size_t queuedEventCount() const noexcept;
    bool present(const DirectTileFrameInput& frame) noexcept;
    bool drainEvent(DirectTilePresentEvent* event) noexcept;
    bool idle() const noexcept;

    /** Returns false until every callback and release-fence owner has drained. */
    bool detach() noexcept;

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

}  // namespace ntk::present
