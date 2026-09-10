#include "gl_viewer_renderer.h"
#include <android/log.h>

bool GlViewerRenderer::setTextureBudget(std::int64_t bytes) noexcept {
    if (bytes <= 0 || !bindOwnerThread()) return false;
    if (textureCounts()[1] > bytes) return false;
    textureBudget_ = bytes;
    return true;
}

bool GlViewerRenderer::textureBudgetAllows(std::uint64_t bytes) const noexcept {
    if (!onOwnerThread()) return false;
    const auto used = static_cast<std::uint64_t>(textureCounts()[1]);
    const auto limit = static_cast<std::uint64_t>(textureBudget_);
    return used <= limit && bytes <= limit - used;
}

std::array<std::int64_t, 5> GlViewerRenderer::textureCounts() const noexcept {
    if (!onOwnerThread()) return {-1, -1, -1, -1, -1};
    std::int64_t bytes = 0;
    std::int64_t retired = 0;
    std::int64_t retiredBytes = 0;
    for (const auto& entry : textures_) {
        bytes += entry.second.bytes;
        if (entry.second.retired) {
            ++retired;
            retiredBytes += entry.second.bytes;
        }
    }
    return {static_cast<std::int64_t>(textures_.size()), bytes, retired, retiredBytes,
            static_cast<std::int64_t>(scene_.size())};
}

bool GlViewerRenderer::hasTexture(std::uint64_t key) const noexcept {
    return onOwnerThread() && textures_.find(key) != textures_.end();
}

bool GlViewerRenderer::clearScene() noexcept {
    if (!initialize()) return false;
    if (buffered_) buffered_->hide();
    scene_.clear();
    sceneKey_ = 0;
    collectRetiredTextures();
    return glSucceeded("clear scene");
}

bool GlViewerRenderer::describeVisibleQuad(
    const GlSceneEntry& entry, int height, int viewportTop, VisibleQuad* quad) noexcept {
    *quad = {};
    const std::int64_t top = static_cast<std::int64_t>(viewportTop) * sceneUnitsPerPixel_;
    const std::int64_t extent = static_cast<std::int64_t>(height) * sceneUnitsPerPixel_;
    if (entry.destinationBottom <= top || entry.destinationTop >= top + extent) return true;
    const auto found = textures_.find(entry.textureKey);
    if (found == textures_.end()) {
        __android_log_print(ANDROID_LOG_ERROR, "GlViewerRenderer", "missing texture key=%llu",
            static_cast<unsigned long long>(entry.textureKey));
        return false;
    }
    const Texture& texture = found->second;
    if (texture.sourceTop != entry.sourceTop || texture.sourceBottom != entry.sourceBottom ||
        texture.sourceHeight != entry.sourceHeight) {
        __android_log_print(ANDROID_LOG_ERROR, "GlViewerRenderer", "texture geometry mismatch key=%llu",
            static_cast<unsigned long long>(entry.textureKey));
        return false;
    }
    quad->texture = texture.name;
    quad->top = 1.0F - 2.0F * static_cast<float>(entry.destinationTop - top) / static_cast<float>(extent);
    quad->bottom = 1.0F - 2.0F * static_cast<float>(entry.destinationBottom - top) / static_cast<float>(extent);
    return true;
}

bool GlViewerRenderer::enableBufferedCompositor() noexcept {
    if (!bindOwnerThread() || context_ != EGL_NO_CONTEXT || !textures_.empty() || buffered_) return false;
    auto buffered = std::make_unique<BufferedFrameCompositor>(callback_);
    if (!buffered->supported()) return false;
    buffered_ = std::move(buffered);
    bufferedEnabled_ = true;
    return true;
}

bool GlViewerRenderer::canSubmit() noexcept {
    return onOwnerThread() && (!buffered_ || buffered_->ready());
}

bool GlViewerRenderer::prepare() noexcept {
    // Reserve only object names, not image/buffer storage. The same context owns these names
    // when a reader claims it; close() already retires the remaining names and unpack buffer.
    if (!initialize() || !initializeStaticQuad()) return false;
    textureUpload_.prepareNames();
    return glSucceeded("prepare texture upload names");
}

std::uint64_t GlViewerRenderer::upload(std::uint64_t cpuTileHandle, int width, int height,
    int sourceTop, int sourceBottom, int sourceHeight) noexcept {
    return uploadGl(cpuTileHandle, width, height, sourceTop, sourceBottom, sourceHeight);
}

int GlViewerRenderer::submit(const GlViewerFrame& frame) noexcept {
    return submitGl(frame);
}

bool GlViewerRenderer::installScene(const GlViewerFrame& frame) noexcept {
    if (frame.coordinateUnitsPerPixel != 1 && frame.coordinateUnitsPerPixel != 1024) return false;
    if (frame.scene == nullptr) {
        if (frame.sceneKey == sceneKey_ && frame.coordinateUnitsPerPixel == sceneUnitsPerPixel_) return true;
        __android_log_print(
            ANDROID_LOG_ERROR, "GlViewerRenderer", "scene key mismatch incoming=%lld active=%lld",
            static_cast<long long>(frame.sceneKey), static_cast<long long>(sceneKey_));
        return false;
    }
    for (const GlSceneEntry& entry : *frame.scene) {
        if (entry.textureKey == 0 || entry.sourceTop < 0 ||
            entry.sourceBottom <= entry.sourceTop || entry.sourceBottom > entry.sourceHeight ||
            entry.destinationBottom <= entry.destinationTop) return false;
    }
    scene_ = *frame.scene;
    sceneKey_ = frame.sceneKey;
    sceneUnitsPerPixel_ = frame.coordinateUnitsPerPixel;
    collectRetiredTextures();
    return true;
}

int GlViewerRenderer::presentBuffered(const GlViewerFrame& frame) noexcept {
    if (hasReadbackRequest(frame.token)) issueReadback(frame, 0);
    const bool submitted = buffered_->present(frame.token);
    completeReadbackSwap(frame.token, submitted, contextLost_);
    return submitted ? 1 : -1;
}
