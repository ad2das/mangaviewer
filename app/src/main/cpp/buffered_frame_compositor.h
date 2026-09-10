#pragma once
#include <cstdint>
#include <memory>
#include <android/native_window.h>
#include "gl_presentation_callback.h"

/** Three viewport buffers. Owner-thread GL; compositor callbacks only enqueue releases. */
class BufferedFrameCompositor final {
public:
    explicit BufferedFrameCompositor(std::shared_ptr<GlPresentationCallback> callback);
    ~BufferedFrameCompositor();
    bool supported() const noexcept;
    bool attach(ANativeWindow* window) noexcept;
    void detach() noexcept;
    bool ready() noexcept;
    bool bind(int width, int height) noexcept;
    unsigned int drawingFramebuffer() const noexcept;
    bool present(std::int64_t token) noexcept;
    void hide() noexcept;
    void poll() noexcept;
private:
    struct State;
    std::unique_ptr<State> state_;
};
