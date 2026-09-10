#include "buffered_frame_compositor.h"
#include <android/data_space.h>
#include <android/hardware_buffer.h>
#include <android/surface_control.h>
#include <android/trace.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>
#include <array>
#include <condition_variable>
#include <cstdio>
#include <deque>
#include <dlfcn.h>
#include <mutex>
#include <poll.h>
#include <pthread.h>
#include <sys/resource.h>
#include <thread>
#include <unistd.h>
#include <unordered_set>
#include <vector>

namespace {
/** Exclusive FIFO handoff of finished transactions; this thread never owns a GL context. */
class TransactionSubmitter final {
public:
    TransactionSubmitter() : thread_([this] { run(); }) {}
    ~TransactionSubmitter() {
        { std::lock_guard lock(mutex_); closing_ = true; }
        changed_.notify_one();
        thread_.join();
    }
    void submit(ASurfaceTransaction* transaction) {
        { std::lock_guard lock(mutex_); queue_.push_back(transaction); }
        changed_.notify_one();
    }
    void flush() {
        std::unique_lock lock(mutex_);
        drained_.wait(lock, [this] { return queue_.empty() && !applying_; });
    }
private:
    void run() {
        pthread_setname_np(pthread_self(), "engine-present");
        setpriority(PRIO_PROCESS, 0, -4);
        for (;;) {
            ASurfaceTransaction* transaction = nullptr;
            {
                std::unique_lock lock(mutex_);
                changed_.wait(lock, [this] { return closing_ || !queue_.empty(); });
                if (queue_.empty()) return;
                transaction = queue_.front(); queue_.pop_front(); applying_ = true;
            }
            ASurfaceTransaction_apply(transaction);
            ASurfaceTransaction_delete(transaction);
            { std::lock_guard lock(mutex_); applying_ = false; }
            drained_.notify_all();
        }
    }
    std::mutex mutex_;
    std::condition_variable changed_, drained_;
    std::deque<ASurfaceTransaction*> queue_;
    bool closing_ = false, applying_ = false;
    std::thread thread_;
};

struct Functions {
    PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC buffer = reinterpret_cast<PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC>(eglGetProcAddress("eglGetNativeClientBufferANDROID"));
    PFNEGLCREATEIMAGEKHRPROC image = reinterpret_cast<PFNEGLCREATEIMAGEKHRPROC>(eglGetProcAddress("eglCreateImageKHR"));
    PFNEGLDESTROYIMAGEKHRPROC destroyImage = reinterpret_cast<PFNEGLDESTROYIMAGEKHRPROC>(eglGetProcAddress("eglDestroyImageKHR"));
    PFNGLEGLIMAGETARGETTEXTURE2DOESPROC target = reinterpret_cast<PFNGLEGLIMAGETARGETTEXTURE2DOESPROC>(eglGetProcAddress("glEGLImageTargetTexture2DOES"));
    PFNEGLCREATESYNCKHRPROC sync = reinterpret_cast<PFNEGLCREATESYNCKHRPROC>(eglGetProcAddress("eglCreateSyncKHR"));
    PFNEGLDESTROYSYNCKHRPROC destroySync = reinterpret_cast<PFNEGLDESTROYSYNCKHRPROC>(eglGetProcAddress("eglDestroySyncKHR"));
    PFNEGLDUPNATIVEFENCEFDANDROIDPROC fence = reinterpret_cast<PFNEGLDUPNATIVEFENCEFDANDROIDPROC>(eglGetProcAddress("eglDupNativeFenceFDANDROID"));
    using Pressure = void (*)(ASurfaceTransaction*, ASurfaceControl*, bool);
    using Transform = void (*)(ASurfaceTransaction*, ASurfaceControl*, int32_t);
    using Acquire = void (*)(ASurfaceControl*);
    using BufferId = int (*)(const AHardwareBuffer*, std::uint64_t*);
    Pressure pressure = reinterpret_cast<Pressure>(dlsym(RTLD_DEFAULT, "ASurfaceTransaction_setEnableBackPressure"));
    Transform transform = reinterpret_cast<Transform>(dlsym(RTLD_DEFAULT, "ASurfaceTransaction_setBufferTransform"));
    Acquire acquire = reinterpret_cast<Acquire>(dlsym(RTLD_DEFAULT, "ASurfaceControl_acquire"));
    BufferId bufferId = reinterpret_cast<BufferId>(dlsym(RTLD_DEFAULT, "AHardwareBuffer_getId"));
    bool valid() const { return buffer && image && destroyImage && target && sync && destroySync && fence && pressure && transform && acquire; }
};
struct Completion { std::int64_t token, latch; int previous, fence; };
struct CompletionQueue {
    std::mutex mutex;
    std::vector<Completion> items;
    ~CompletionQueue() { for (const auto& item : items) if (item.fence >= 0) close(item.fence); }
};
struct Ticket {
    std::shared_ptr<CompletionQueue> queue;
    std::shared_ptr<GlPresentationCallback> callback;
    ASurfaceControl* layer;
    std::int64_t token;
    int previous;
    ~Ticket() { ASurfaceControl_release(layer); }
};
void completed(void* opaque, ASurfaceTransactionStats* stats) {
    const std::unique_ptr<Ticket> ticket(static_cast<Ticket*>(opaque));
    const auto latch = ASurfaceTransactionStats_getLatchTime(stats);
    const int fence = ticket->previous < 0 ? -1 : ASurfaceTransactionStats_getPreviousReleaseFenceFd(stats, ticket->layer);
    {
        std::lock_guard lock(ticket->queue->mutex);
        ticket->queue->items.push_back({ticket->token, latch, ticket->previous, fence});
    }
    ticket->callback->completionPending();
}
struct FrameBuffer {
    AHardwareBuffer* buffer = nullptr;
    EGLImageKHR image = EGL_NO_IMAGE_KHR;
    GLuint texture = 0, framebuffer = 0;
    bool busy = false;
    int releaseFence = -1;
};
bool allocateFrame(FrameBuffer& frame, const Functions& functions, int width, int height) {
    AHardwareBuffer_Desc description{};
    description.width = width; description.height = height; description.layers = 1;
    description.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    description.usage = AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
        AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY;
    if (AHardwareBuffer_allocate(&description, &frame.buffer) != 0) return false;
    const EGLint attributes[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
    frame.image = functions.image(eglGetCurrentDisplay(), EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID,
        functions.buffer(frame.buffer), attributes);
    if (frame.image == EGL_NO_IMAGE_KHR) return false;
    glGenTextures(1, &frame.texture);
    glBindTexture(GL_TEXTURE_2D, frame.texture);
    functions.target(GL_TEXTURE_2D, frame.image);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glGenFramebuffers(1, &frame.framebuffer);
    glBindFramebuffer(GL_FRAMEBUFFER, frame.framebuffer);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, frame.texture, 0);
    return glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE && glGetError() == GL_NO_ERROR;
}
void releaseFrame(FrameBuffer& frame, const Functions& functions) {
    if (frame.releaseFence >= 0) close(frame.releaseFence);
    if (frame.framebuffer) glDeleteFramebuffers(1, &frame.framebuffer);
    if (frame.texture) glDeleteTextures(1, &frame.texture);
    if (frame.image != EGL_NO_IMAGE_KHR) functions.destroyImage(eglGetCurrentDisplay(), frame.image);
    if (frame.buffer) AHardwareBuffer_release(frame.buffer);
    frame = {};
}
int acquireFence(const Functions& functions) {
    const auto display = eglGetCurrentDisplay();
    const EGLint attributes[] = {EGL_NONE};
    const auto sync = functions.sync(display, EGL_SYNC_NATIVE_FENCE_ANDROID, attributes);
    if (sync == EGL_NO_SYNC_KHR) return -1;
    glFlush();
    const int fence = functions.fence(display, sync);
    functions.destroySync(display, sync);
    return fence;
}
}

struct BufferedFrameCompositor::State {
    explicit State(std::shared_ptr<GlPresentationCallback> value) : callback(std::move(value)) {}
    Functions functions;
    // At most three frame transactions can be outstanding: each owns one busy viewport.
    TransactionSubmitter transactions;
    std::shared_ptr<GlPresentationCallback> callback;
    std::shared_ptr<CompletionQueue> completions = std::make_shared<CompletionQueue>();
    std::array<FrameBuffer, 3> frames;
    std::unordered_set<std::int64_t> pending;
    ASurfaceControl* layer = nullptr;
    int width = 0, height = 0, current = -1, drawing = -1;
};

BufferedFrameCompositor::BufferedFrameCompositor(std::shared_ptr<GlPresentationCallback> callback)
    : state_(std::make_unique<State>(std::move(callback))) {}
BufferedFrameCompositor::~BufferedFrameCompositor() { detach(); }
bool BufferedFrameCompositor::supported() const noexcept { return state_->functions.valid(); }

bool BufferedFrameCompositor::attach(ANativeWindow* window) noexcept {
    detach();
    if (!window || !supported()) return false;
    auto& state = *state_;
    state.width = ANativeWindow_getWidth(window); state.height = ANativeWindow_getHeight(window);
    // Infrastructure replaces the window BufferQueue: at most 3 * 16 MiB, independent of originals.
    if (state.width <= 0 || state.height <= 0 || static_cast<std::int64_t>(state.width) * state.height > 4 * 1024 * 1024) return false;
    state.layer = ASurfaceControl_createFromWindow(window, "EngineBufferedViewport");
    if (!state.layer) return false;
    for (auto& frame : state.frames) {
        if (!allocateFrame(frame, state.functions, state.width, state.height)) { detach(); return false; }
    }
    glBindTexture(GL_TEXTURE_2D, 0);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    return true;
}

void BufferedFrameCompositor::detach() noexcept {
    auto& state = *state_;
    state.transactions.flush();
    if (state.layer) {
        auto* transaction = ASurfaceTransaction_create();
        ASurfaceTransaction_setVisibility(transaction, state.layer, ASURFACE_TRANSACTION_VISIBILITY_HIDE);
        ASurfaceTransaction_reparent(transaction, state.layer, nullptr);
        ASurfaceTransaction_apply(transaction);
        ASurfaceTransaction_delete(transaction);
        ASurfaceControl_release(state.layer);
        state.layer = nullptr;
    }
    for (const auto token : state.pending) state.callback->presented(token, 0, -2, 0);
    state.pending.clear();
    // Callbacks retain the old queue; its fences cannot release buffers of a new attachment.
    state.completions = std::make_shared<CompletionQueue>();
    for (auto& frame : state.frames) releaseFrame(frame, state.functions);
    state.current = -1; state.drawing = -1; state.width = 0; state.height = 0;
}

void BufferedFrameCompositor::poll() noexcept {
    auto& state = *state_;
    std::vector<Completion> items;
    { std::lock_guard lock(state.completions->mutex); items.swap(state.completions->items); }
    for (const auto& item : items) {
        if (item.previous >= 0) {
            auto& frame = state.frames[item.previous];
            frame.releaseFence = item.fence;
            if (item.fence < 0) frame.busy = false;
        }
        if (state.pending.erase(item.token)) state.callback->presented(item.token, item.latch > 0 ? item.latch : 0,
            item.latch > 0 ? EGL_COMPOSITION_LATCH_TIME_ANDROID : -1, 0);
    }
    for (auto& frame : state.frames) {
        if (frame.releaseFence < 0) continue;
        pollfd descriptor{frame.releaseFence, POLLIN, 0};
        if (::poll(&descriptor, 1, 0) > 0 && (descriptor.revents & POLLIN)) {
            close(frame.releaseFence); frame.releaseFence = -1; frame.busy = false;
        }
    }
}

bool BufferedFrameCompositor::ready() noexcept {
    poll();
    if (!state_->layer) return false;
    for (const auto& frame : state_->frames) if (!frame.busy) return true;
    return false;
}

bool BufferedFrameCompositor::bind(int width, int height) noexcept {
    if (!ready() || width != state_->width || height != state_->height) return false;
    for (int i = 0; i < static_cast<int>(state_->frames.size()); ++i) {
        if (state_->frames[i].busy) continue;
        state_->drawing = i;
        glBindFramebuffer(GL_FRAMEBUFFER, state_->frames[i].framebuffer);
        return glGetError() == GL_NO_ERROR;
    }
    return false;
}

bool BufferedFrameCompositor::present(std::int64_t token) noexcept {
    auto& state = *state_;
    if (state.drawing < 0 || token <= 0) return false;
    const int fence = acquireFence(state.functions);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    if (fence < 0) return false;
    auto* transaction = ASurfaceTransaction_create();
    auto& frame = state.frames[state.drawing];
    if (ATrace_isEnabled()) {
        std::uint64_t bufferId = 0;
        if (state.functions.bufferId) state.functions.bufferId(frame.buffer, &bufferId);
        char label[128]{};
        std::snprintf(label, sizeof(label), "engine_buffer:%lld:%llu", static_cast<long long>(token),
                      static_cast<unsigned long long>(bufferId));
        ATrace_beginSection(label); ATrace_endSection();
    }
    ASurfaceTransaction_setBuffer(transaction, state.layer, frame.buffer, fence);
    state.functions.transform(transaction, state.layer, ANATIVEWINDOW_TRANSFORM_MIRROR_VERTICAL);
    ASurfaceTransaction_setBufferDataSpace(transaction, state.layer, ADATASPACE_SRGB);
    ASurfaceTransaction_setBufferTransparency(transaction, state.layer, ASURFACE_TRANSACTION_TRANSPARENCY_OPAQUE);
    ASurfaceTransaction_setVisibility(transaction, state.layer, ASURFACE_TRANSACTION_VISIBILITY_SHOW);
    state.functions.pressure(transaction, state.layer, true);
    state.functions.acquire(state.layer);
    ASurfaceTransaction_setOnComplete(transaction,
        new Ticket{state.completions, state.callback, state.layer, token, state.current}, completed);
    frame.busy = true;
    state.current = state.drawing; state.drawing = -1;
    state.pending.insert(token);
    state.transactions.submit(transaction);
    return true;
}

unsigned int BufferedFrameCompositor::drawingFramebuffer() const noexcept {
    return state_->drawing < 0 ? 0 : state_->frames[state_->drawing].framebuffer;
}

void BufferedFrameCompositor::hide() noexcept {
    if (!state_->layer) return;
    state_->transactions.flush();
    auto* transaction = ASurfaceTransaction_create();
    ASurfaceTransaction_setVisibility(transaction, state_->layer, ASURFACE_TRANSACTION_VISIBILITY_HIDE);
    ASurfaceTransaction_apply(transaction);
    ASurfaceTransaction_delete(transaction);
}
