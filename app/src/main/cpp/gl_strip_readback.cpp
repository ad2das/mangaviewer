#include "gl_strip_readback.h"

#include <time.h>

#include <cstdlib>
#include <cstring>
#include <limits>
#include <new>
#include <utility>

namespace {

constexpr std::uint64_t kPacketMagic = 0x4552474253545250ULL;
constexpr std::uint64_t kPacketVersion = 1ULL;

std::int64_t monotonicNanos() noexcept {
    timespec value{};
    if (clock_gettime(CLOCK_MONOTONIC, &value) != 0) return 0;
    return static_cast<std::int64_t>(value.tv_sec) * 1000000000LL + value.tv_nsec;
}

void writeLittleEndian(std::uint8_t* destination, std::uint64_t value) noexcept {
    for (std::size_t index = 0; index < sizeof(value); ++index) {
        destination[index] = static_cast<std::uint8_t>(value >> (index * 8U));
    }
}

std::uint64_t statusCode(GlReadbackStatus status) noexcept {
    return static_cast<std::uint64_t>(status);
}

bool validGeometry(
    int top,
    int bottom,
    int width,
    int height,
    std::uint64_t* byteCount) noexcept {
    if (byteCount == nullptr || width <= 0 || height <= 0 || top < 0 || bottom <= top ||
        bottom > height) return false;
    const std::uint64_t rowCount = static_cast<std::uint64_t>(bottom - top);
    const std::uint64_t rowWidth = static_cast<std::uint64_t>(width);
    if (rowWidth > std::numeric_limits<std::uint64_t>::max() / rowCount) return false;
    const std::uint64_t pixels = rowWidth * rowCount;
    if (pixels > std::numeric_limits<std::uint64_t>::max() / 4ULL) return false;
    *byteCount = pixels * 4ULL;
    return true;
}

struct PackState final {
    GLint buffer = 0;
    GLint alignment = 4;
    GLint rowLength = 0;
    GLint skipRows = 0;
    GLint skipPixels = 0;
    bool active = false;

    bool load() noexcept {
        glGetIntegerv(GL_PIXEL_PACK_BUFFER_BINDING, &buffer);
        glGetIntegerv(GL_PACK_ALIGNMENT, &alignment);
        glGetIntegerv(GL_PACK_ROW_LENGTH, &rowLength);
        glGetIntegerv(GL_PACK_SKIP_ROWS, &skipRows);
        glGetIntegerv(GL_PACK_SKIP_PIXELS, &skipPixels);
        active = true;
        return glGetError() == GL_NO_ERROR;
    }

    void restore() noexcept {
        if (!active) return;
        glBindBuffer(GL_PIXEL_PACK_BUFFER, static_cast<GLuint>(buffer));
        glPixelStorei(GL_PACK_ALIGNMENT, alignment);
        glPixelStorei(GL_PACK_ROW_LENGTH, rowLength);
        glPixelStorei(GL_PACK_SKIP_ROWS, skipRows);
        glPixelStorei(GL_PACK_SKIP_PIXELS, skipPixels);
        active = false;
    }

    ~PackState() { restore(); }
};

}  // namespace

int GlStripReadback::findRequest(
    const std::array<RequestSlot, kMaximumCaptures>& slots,
    std::int64_t token) noexcept {
    for (std::size_t index = 0; index < slots.size(); ++index) {
        if (slots[index].used && slots[index].value.token == token) return static_cast<int>(index);
    }
    return -1;
}

int GlStripReadback::findCapture(
    const std::array<CaptureSlot, kMaximumCaptures>& slots,
    std::int64_t token) noexcept {
    for (std::size_t index = 0; index < slots.size(); ++index) {
        if (slots[index].used && slots[index].value.request.token == token) {
            return static_cast<int>(index);
        }
    }
    return -1;
}

int GlStripReadback::findTerminal(
    const std::array<TerminalSlot, kMaximumCaptures>& slots,
    std::int64_t token) noexcept {
    for (std::size_t index = 0; index < slots.size(); ++index) {
        if (slots[index].used && slots[index].request.token == token) return static_cast<int>(index);
    }
    return -1;
}

int GlStripReadback::freeRequest(
    const std::array<RequestSlot, kMaximumCaptures>& slots) noexcept {
    for (std::size_t index = 0; index < slots.size(); ++index) {
        if (!slots[index].used) return static_cast<int>(index);
    }
    return -1;
}

int GlStripReadback::freeCapture(
    const std::array<CaptureSlot, kMaximumCaptures>& slots) noexcept {
    for (std::size_t index = 0; index < slots.size(); ++index) {
        if (!slots[index].used) return static_cast<int>(index);
    }
    return -1;
}

int GlStripReadback::freeTerminal(
    const std::array<TerminalSlot, kMaximumCaptures>& slots) noexcept {
    for (std::size_t index = 0; index < slots.size(); ++index) {
        if (!slots[index].used) return static_cast<int>(index);
    }
    return -1;
}

std::int64_t GlStripReadback::ticketCount() const noexcept {
    std::int64_t count = 0;
    for (const RequestSlot& slot : requests_) count += slot.used ? 1 : 0;
    for (const CaptureSlot& slot : captures_) count += slot.used ? 1 : 0;
    for (const TerminalSlot& slot : terminalPackets_) count += slot.used ? 1 : 0;
    return count;
}

bool GlStripReadback::request(
    std::int64_t token,
    std::int64_t sessionId,
    std::int64_t rendererEpoch,
    std::int64_t surfaceEpoch,
    int top,
    int bottom) noexcept {
    if (token <= 0 || sessionId <= 0 || rendererEpoch <= 0 || surfaceEpoch <= 0 || top < 0 ||
        bottom <= top || ticketCount() >= static_cast<std::int64_t>(kMaximumCaptures)) {
        return false;
    }
    if (findRequest(requests_, token) >= 0 || findCapture(captures_, token) >= 0 ||
        findTerminal(terminalPackets_, token) >= 0) return false;
    const int index = freeRequest(requests_);
    if (index < 0) return false;
    requests_[static_cast<std::size_t>(index)].used = true;
    requests_[static_cast<std::size_t>(index)].value =
        Request{token, sessionId, rendererEpoch, surfaceEpoch, top, bottom};
    return true;
}

bool GlStripReadback::hasRequest(std::int64_t token) const noexcept {
    return findRequest(requests_, token) >= 0;
}

void GlStripReadback::issue(
    std::int64_t token,
    int width,
    int height,
    EGLuint64KHR eglFrameId) noexcept {
    const int requestIndex = findRequest(requests_, token);
    if (requestIndex < 0) return;
    const Request request = requests_[static_cast<std::size_t>(requestIndex)].value;
    requests_[static_cast<std::size_t>(requestIndex)].used = false;
    std::uint64_t byteCount = 0;
    if (!validGeometry(request.top, request.bottom, width, height, &byteCount)) {
        retainFailure(request, width, eglFrameId, GlReadbackStatus::kGlError, 0, 0, 0);
        return;
    }
    if (byteCount > kMaximumCaptureBytes || byteCount > kMaximumCaptureBytes - livePboBytes()) {
        retainFailure(request, width, eglFrameId, GlReadbackStatus::kGlError, 0, 0, 0);
        return;
    }
    GLint readFramebuffer = 0;
    GLint drawFramebuffer = 0;
    glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &readFramebuffer);
    glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &drawFramebuffer);
    if (glGetError() != GL_NO_ERROR || readFramebuffer != 0 || drawFramebuffer != 0) {
        retainFailure(request, width, eglFrameId, GlReadbackStatus::kGlError, 0, 0, 0);
        return;
    }
    const int captureIndex = freeCapture(captures_);
    if (captureIndex < 0) {
        retainFailure(request, width, eglFrameId, GlReadbackStatus::kGlError, 0, 0, 0);
        return;
    }
    CaptureSlot& slot = captures_[static_cast<std::size_t>(captureIndex)];
    slot.used = true;
    slot.value = Capture{};
    slot.value.request = request;
    slot.value.width = width;
    slot.value.surfaceHeight = height;
    slot.value.byteCount = byteCount;
    slot.value.eglFrameId = eglFrameId;
    slot.value.captureIssuedNanos = monotonicNanos();
    if (!allocateGpuCapture(&slot.value)) {
        slot.value.status = GlReadbackStatus::kGlError;
        slot.value.requiresContextDestroy = slot.value.fence == nullptr && slot.value.pbo != 0;
        if (slot.value.pbo == 0 && slot.value.fence == nullptr) {
            slot.used = false;
            retainFailure(request, width, eglFrameId, GlReadbackStatus::kGlError, 0, 0, 0);
        }
    }
}

void GlStripReadback::fail(
    std::int64_t token,
    int width,
    GlReadbackStatus status) noexcept {
    const int requestIndex = findRequest(requests_, token);
    if (requestIndex >= 0) {
        const std::size_t index = static_cast<std::size_t>(requestIndex);
        const Request request = requests_[index].value;
        requests_[index].used = false;
        retainFailure(request, width, 0, status, 0, 0, 0);
        return;
    }
    const int captureIndex = findCapture(captures_, token);
    if (captureIndex >= 0) captures_[static_cast<std::size_t>(captureIndex)].value.status = status;
}

void GlStripReadback::completeSwap(
    std::int64_t token,
    bool swapSucceeded,
    bool contextLost) noexcept {
    const std::int64_t completed = monotonicNanos();
    const int captureIndex = findCapture(captures_, token);
    if (captureIndex >= 0) {
        Capture& capture = captures_[static_cast<std::size_t>(captureIndex)].value;
        capture.swapKnown = true;
        capture.swapCompletedNanos = completed;
        if (!swapSucceeded) {
            capture.status = contextLost ? GlReadbackStatus::kContextLost
                                          : GlReadbackStatus::kSwapFailed;
        }
        return;
    }
    updateTerminalSwapTime(token, completed);
}

GlReadbackCounts GlStripReadback::counts() const noexcept {
    GlReadbackCounts result{};
    for (const RequestSlot& slot : requests_) result.pendingRequests += slot.used ? 1 : 0;
    for (const CaptureSlot& slot : captures_) {
        if (!slot.used) continue;
        ++result.pendingGpuCaptures;
        result.livePboBytes += static_cast<std::int64_t>(slot.value.byteCount);
        result.liveFenceCount += slot.value.fence == nullptr ? 0 : 1;
    }
    for (const TerminalSlot& slot : terminalPackets_) {
        result.retainedTerminalPackets += slot.used ? 1 : 0;
    }
    return result;
}

void GlStripReadback::retainFailure(
    const Request& request,
    int width,
    EGLuint64KHR eglFrameId,
    GlReadbackStatus status,
    std::int64_t captureIssuedNanos,
    std::int64_t captureReadyNanos,
    std::int64_t swapCompletedNanos) noexcept {
    const int index = freeTerminal(terminalPackets_);
    if (index < 0) std::abort();
    TerminalSlot& slot = terminalPackets_[static_cast<std::size_t>(index)];
    slot.used = true;
    slot.request = request;
    writeHeader(slot.packet.data(), request, width, eglFrameId, status, captureIssuedNanos,
                captureReadyNanos, swapCompletedNanos, 0);
}

void GlStripReadback::writeHeader(
    std::uint8_t* destination,
    const Request& request,
    int width,
    EGLuint64KHR eglFrameId,
    GlReadbackStatus status,
    std::int64_t captureIssuedNanos,
    std::int64_t captureReadyNanos,
    std::int64_t swapCompletedNanos,
    std::uint64_t rgbaByteCount) noexcept {
    const std::uint64_t packetWidth = width > 0 ? static_cast<std::uint64_t>(width) : 0ULL;
    const std::uint64_t values[16]{
        kPacketMagic, kPacketVersion, statusCode(status),
        static_cast<std::uint64_t>(request.sessionId),
        static_cast<std::uint64_t>(request.rendererEpoch),
        static_cast<std::uint64_t>(request.surfaceEpoch),
        static_cast<std::uint64_t>(request.token), static_cast<std::uint64_t>(eglFrameId),
        packetWidth, static_cast<std::uint64_t>(request.top),
        static_cast<std::uint64_t>(request.bottom), static_cast<std::uint64_t>(captureIssuedNanos),
        static_cast<std::uint64_t>(captureReadyNanos),
        static_cast<std::uint64_t>(swapCompletedNanos), rgbaByteCount, 0ULL,
    };
    for (std::size_t index = 0; index < 16U; ++index) {
        writeLittleEndian(destination + index * sizeof(std::uint64_t), values[index]);
    }
}

void GlStripReadback::updateTerminalSwapTime(
    std::int64_t token,
    std::int64_t swapCompletedNanos) noexcept {
    const int index = findTerminal(terminalPackets_, token);
    if (index < 0) return;
    writeLittleEndian(
        terminalPackets_[static_cast<std::size_t>(index)].packet.data() + 13U * sizeof(std::uint64_t),
        static_cast<std::uint64_t>(swapCompletedNanos));
}

void GlStripReadback::destroyCapture(Capture* capture) noexcept {
    if (capture == nullptr) return;
    if (capture->fence != nullptr) glDeleteSync(capture->fence);
    if (capture->pbo != 0) glDeleteBuffers(1, &capture->pbo);
    capture->fence = nullptr;
    capture->pbo = 0;
}

bool GlStripReadback::fenceSignaled(Capture* capture) noexcept {
    if (capture == nullptr || capture->fence == nullptr) return true;
    const GLenum result = glClientWaitSync(capture->fence, 0, 0);
    if (result == GL_TIMEOUT_EXPIRED) return false;
    if (result == GL_ALREADY_SIGNALED || result == GL_CONDITION_SATISFIED) return true;
    capture->status = GlReadbackStatus::kGlError;
    glGetError();
    return true;
}

bool GlStripReadback::allocateGpuCapture(Capture* capture) noexcept {
    PackState pack;
    if (capture == nullptr || !pack.load()) return false;
    glGenBuffers(1, &capture->pbo);
    if (capture->pbo == 0) return false;
    glBindBuffer(GL_PIXEL_PACK_BUFFER, capture->pbo);
    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    glPixelStorei(GL_PACK_ROW_LENGTH, 0);
    glPixelStorei(GL_PACK_SKIP_ROWS, 0);
    glPixelStorei(GL_PACK_SKIP_PIXELS, 0);
    glBufferData(GL_PIXEL_PACK_BUFFER, static_cast<GLsizeiptr>(capture->byteCount), nullptr,
                 GL_STREAM_READ);
    glReadPixels(0, capture->surfaceHeight - capture->request.bottom, capture->width,
                 capture->request.bottom - capture->request.top, GL_RGBA, GL_UNSIGNED_BYTE,
                 nullptr);
    if (glGetError() != GL_NO_ERROR) return false;
    pack.restore();
    if (glGetError() != GL_NO_ERROR) return false;
    capture->fence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
    return capture->fence != nullptr && glGetError() == GL_NO_ERROR;
}

bool GlStripReadback::mapCapturePixels(
    Capture* capture,
    std::uint8_t* rgba) noexcept {
    if (capture == nullptr || rgba == nullptr || capture->pbo == 0) return false;
    GLint previous = 0;
    glGetIntegerv(GL_PIXEL_PACK_BUFFER_BINDING, &previous);
    if (glGetError() != GL_NO_ERROR) return false;
    glBindBuffer(GL_PIXEL_PACK_BUFFER, capture->pbo);
    void* mapped = glMapBufferRange(GL_PIXEL_PACK_BUFFER, 0,
                                    static_cast<GLsizeiptr>(capture->byteCount), GL_MAP_READ_BIT);
    const bool mapCallSucceeded = glGetError() == GL_NO_ERROR;
    bool success = mapped != nullptr && mapCallSucceeded;
    if (mapped != nullptr) {
        if (success) {
            const std::size_t rowBytes = static_cast<std::size_t>(capture->width) * 4U;
            const std::size_t rows = static_cast<std::size_t>(
                capture->request.bottom - capture->request.top);
            const auto* source = static_cast<const std::uint8_t*>(mapped);
            for (std::size_t row = 0; row < rows; ++row) {
                std::memcpy(rgba + row * rowBytes,
                            source + (rows - row - 1U) * rowBytes, rowBytes);
            }
        }
        if (glUnmapBuffer(GL_PIXEL_PACK_BUFFER) != GL_TRUE) success = false;
    }
    glBindBuffer(GL_PIXEL_PACK_BUFFER, static_cast<GLuint>(previous));
    if (glGetError() != GL_NO_ERROR) success = false;
    return success;
}

std::optional<GlReadbackPacket> GlStripReadback::take(std::int64_t token) {
    const int terminalIndex = findTerminal(terminalPackets_, token);
    if (terminalIndex >= 0) {
        const std::size_t index = static_cast<std::size_t>(terminalIndex);
        GlReadbackPacket packet{};
        std::memcpy(packet.inlineBytes.data(), terminalPackets_[index].packet.data(),
                    GlReadbackPacket::kHeaderBytes);
        packet.size = GlReadbackPacket::kHeaderBytes;
        terminalPackets_[index].used = false;
        return std::optional<GlReadbackPacket>(std::move(packet));
    }
    const int captureIndex = findCapture(captures_, token);
    if (captureIndex < 0) return std::nullopt;
    CaptureSlot& slot = captures_[static_cast<std::size_t>(captureIndex)];
    std::optional<GlReadbackPacket> packet = takeCapture(&slot.value);
    if (!packet.has_value()) return std::nullopt;
    slot.used = false;
    slot.value = Capture{};
    return packet;
}

std::optional<GlReadbackPacket> GlStripReadback::takeCapture(Capture* capture) {
    if (capture == nullptr || capture->requiresContextDestroy || !capture->swapKnown) {
        return std::nullopt;
    }
    if (!fenceSignaled(capture)) return std::nullopt;
    const std::int64_t ready = monotonicNanos();
    if (capture->status == GlReadbackStatus::kOk &&
        (capture->captureIssuedNanos <= 0 || capture->swapCompletedNanos <= 0 || ready <= 0 ||
         ready < capture->captureIssuedNanos || ready < capture->swapCompletedNanos ||
         capture->swapCompletedNanos < capture->captureIssuedNanos)) {
        capture->status = GlReadbackStatus::kGlError;
    }
    if (capture->status != GlReadbackStatus::kOk) {
        GlReadbackPacket packet{};
        writeHeader(packet.inlineBytes.data(), capture->request, capture->width,
                    capture->eglFrameId, capture->status, capture->captureIssuedNanos, ready,
                    capture->swapCompletedNanos, 0);
        packet.size = GlReadbackPacket::kHeaderBytes;
        destroyCapture(capture);
        return std::optional<GlReadbackPacket>(std::move(packet));
    }
    const std::size_t packetBytes = GlReadbackPacket::kHeaderBytes +
        static_cast<std::size_t>(capture->byteCount);
    GlReadbackPacket packet{};
    packet.heapBytes.reset(new (std::nothrow) std::uint8_t[packetBytes]);
    if (packet.heapBytes == nullptr || !mapCapturePixels(
            capture, packet.heapBytes.get() + GlReadbackPacket::kHeaderBytes)) {
        packet.heapBytes.reset();
        capture->status = GlReadbackStatus::kGlError;
        writeHeader(packet.inlineBytes.data(), capture->request, capture->width,
                    capture->eglFrameId, capture->status, capture->captureIssuedNanos, ready,
                    capture->swapCompletedNanos, 0);
        packet.size = GlReadbackPacket::kHeaderBytes;
        destroyCapture(capture);
        return std::optional<GlReadbackPacket>(std::move(packet));
    }
    writeHeader(packet.heapBytes.get(), capture->request, capture->width, capture->eglFrameId,
                GlReadbackStatus::kOk, capture->captureIssuedNanos, ready,
                capture->swapCompletedNanos, capture->byteCount);
    packet.size = packetBytes;
    destroyCapture(capture);
    return std::optional<GlReadbackPacket>(std::move(packet));
}

std::uint64_t GlStripReadback::livePboBytes() const noexcept {
    std::uint64_t bytes = 0;
    for (const CaptureSlot& slot : captures_) {
        if (slot.used) bytes += slot.value.byteCount;
    }
    return bytes;
}

void GlStripReadback::cancel(GlReadbackStatus status) noexcept {
    for (RequestSlot& slot : requests_) {
        if (!slot.used) continue;
        const Request request = slot.value;
        slot.used = false;
        retainFailure(request, 0, 0, status, 0, 0, 0);
    }
    for (CaptureSlot& slot : captures_) {
        if (slot.used && slot.value.status == GlReadbackStatus::kOk) slot.value.status = status;
    }
}

void GlStripReadback::markContextLost() noexcept {
    cancel(GlReadbackStatus::kContextLost);
}

void GlStripReadback::contextDestroyed() noexcept {
    cancel(GlReadbackStatus::kContextLost);
    for (CaptureSlot& slot : captures_) {
        if (!slot.used) continue;
        Capture capture = slot.value;
        if (capture.status == GlReadbackStatus::kOk) {
            capture.status = GlReadbackStatus::kContextLost;
        }
        retainFailure(capture.request, capture.width, capture.eglFrameId, capture.status,
                      capture.captureIssuedNanos, monotonicNanos(), capture.swapCompletedNanos);
        slot.used = false;
        slot.value = Capture{};
    }
}
