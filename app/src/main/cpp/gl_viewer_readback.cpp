#include "gl_viewer_renderer.h"

bool GlViewerRenderer::requestReadback(
    std::int64_t token,
    std::int64_t sessionId,
    std::int64_t rendererEpoch,
    std::int64_t surfaceEpoch,
    int top,
    int bottom) noexcept {
    if (!onOwnerThread() || token <= lastSubmittedToken_) return false;
    return readback_.request(token, sessionId, rendererEpoch, surfaceEpoch, top, bottom);
}

std::optional<GlReadbackPacket> GlViewerRenderer::takeReadback(std::int64_t token) {
    if (!onOwnerThread()) return std::nullopt;
    return readback_.take(token);
}

std::array<std::int64_t, 5> GlViewerRenderer::readbackCounts() const noexcept {
    if (!onOwnerThread()) return {-1, -1, -1, -1, -1};
    const GlReadbackCounts counts = readback_.counts();
    return {
        counts.pendingRequests,
        counts.pendingGpuCaptures,
        counts.retainedTerminalPackets,
        counts.livePboBytes,
        counts.liveFenceCount,
    };
}

bool GlViewerRenderer::hasReadbackRequest(std::int64_t token) const noexcept {
    return readback_.hasRequest(token);
}

void GlViewerRenderer::issueReadback(
    const GlViewerFrame& frame,
    EGLuint64KHR frameId) noexcept {
    readback_.issue(frame.token, frame.surfaceWidth, frame.surfaceHeight, frameId);
}

void GlViewerRenderer::failReadback(
    const GlViewerFrame& frame,
    GlReadbackStatus status) noexcept {
    readback_.fail(frame.token, frame.surfaceWidth, status);
}

void GlViewerRenderer::completeReadbackSwap(
    std::int64_t token,
    bool swapSucceeded,
    bool contextLost) noexcept {
    readback_.completeSwap(token, swapSucceeded, contextLost);
}

void GlViewerRenderer::cancelReadbacks(GlReadbackStatus status) noexcept {
    if (!onOwnerThread()) return;
    readback_.cancel(status);
}

void GlViewerRenderer::markContextLostReadbacks() noexcept {
    if (onOwnerThread()) readback_.markContextLost();
}

void GlViewerRenderer::contextDestroyedReadbacks() noexcept {
    if (onOwnerThread()) readback_.contextDestroyed();
}

bool GlViewerRenderer::bindOwnerThread() noexcept {
    const std::thread::id current = std::this_thread::get_id();
    if (ownerThread_ == std::thread::id{}) {
        ownerThread_ = current;
        return true;
    }
    return ownerThread_ == current;
}

bool GlViewerRenderer::onOwnerThread() const noexcept {
    return ownerThread_ != std::thread::id{} && ownerThread_ == std::this_thread::get_id();
}
