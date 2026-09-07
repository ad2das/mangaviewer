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
