#include <android/bitmap.h>
#include <android/api-level.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <jni.h>
#include <swappy/swappyGL.h>
#include <swappy/swappyGL_extra.h>
#include "swappy/games-frame-pacing/common/FixedNonPipelinePhase.h"
#include "swappy/games-frame-pacing/common/FixedExternalSubmissionContract.h"
#include "ntk_gpu_scene_admission.h"
#include "ntk_prepared_scene_bank.h"
#include "ntk_fixed_depth_one_scheduler.h"
#include "AttachGenerationContract.h"
#include "DetachedWarmContract.h"
#include "NativeSurfaceLeaseRegistry.h"
#include "present/SurfaceControlPresentBackend.h"
#include "present/AhbCompositorCoordinates.h"
#include "present/FixedPresentJoinStateMachine.h"
#include "present/RendererPostSubmitFatalContract.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <deque>
#include <iterator>
#include <limits>
#include <map>
#include <memory>
#include <mutex>
#include <optional>
#include <shared_mutex>
#include <set>
#include <string>
#include <thread>
#include <tuple>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <vector>
#include <time.h>
#include <sys/resource.h>

namespace {

constexpr char kLogTag[] = "NtkStripRenderer";
// Keep the presentation owner in the same Linux scheduling class as Android's
// urgent-display main/input lane.  The renderer only wakes for bounded command and
// presentation work, so this trims wake-up jitter without introducing polling.
constexpr int kUrgentDisplayNice = -8;
constexpr int kDisplayNiceFallback = -4;
constexpr std::uint64_t kNinetyHzPeriodNs = 11'111'111ULL;
constexpr std::int64_t kFixedAppVsyncOffsetNs = 2'000'000LL;
// Exact AChoreographer FrameTimeline D.  Display's public presentation
// deadline is 1 ms larger and is not valid frame-timeline authority.
constexpr std::int64_t kFixedPresentationDeadlineNs = 5'111'111LL;
constexpr std::int32_t kFixedFatalConservationFailure = 20;

enum class RendererPostApplyFatalBranch : std::uint32_t {
    NONE = 0,
    DUPLICATE_FRAME_ID = 1,
    PROOF_AHEAD = 2,
    UNLATCHED_OVERFLOW = 3,
    POST_APPLY_CUT_INVALID = 4,
};
constexpr std::uint64_t kRefreshPeriodToleranceNs = 100'000ULL;
constexpr char kRequiredEglVendor[] = "Android";
constexpr char kRequiredGlRenderer[] =
    "Android Emulator OpenGL ES Translator (NVIDIA GeForce GTX 1060 3GB/PCIe/SSE2)";
constexpr char kRequiredGlVersion[] = "OpenGL ES 3.1 (4.5.0 NVIDIA 582.53)";
#ifndef NTK_QUALIFICATION_PROFILE_ID
#error "NTK_QUALIFICATION_PROFILE_ID must be supplied by the sealed manifest"
#endif
constexpr char kQualificationProfileId[] = NTK_QUALIFICATION_PROFILE_ID;
#define NTK_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, kLogTag, __VA_ARGS__)
#define NTK_LOGI(...) __android_log_print(ANDROID_LOG_INFO, kLogTag, __VA_ARGS__)
#define NTK_LOGW(...) __android_log_print(ANDROID_LOG_WARN, kLogTag, __VA_ARGS__)

void request_urgent_display_priority(const char* role) noexcept {
    errno = 0;
    const int before_nice = getpriority(PRIO_PROCESS, 0);
    const int before_error = errno;
    if (before_error == 0 && before_nice <= kUrgentDisplayNice) {
        NTK_LOGI(
            "thread-priority role=%s requested=%d before=%d effective=%d "
            "setErrno=0 fallbackErrno=0 getErrno=0",
            role,
            kUrgentDisplayNice,
            before_nice,
            before_nice);
        return;
    }

    errno = 0;
    const int set_result = setpriority(PRIO_PROCESS, 0, kUrgentDisplayNice);
    const int set_error = set_result == 0 ? 0 : errno;
    int fallback_error = 0;
    if (set_result != 0 &&
        (before_error != 0 || before_nice > kDisplayNiceFallback)) {
        // Some vendor kernels expose a narrower RLIMIT_NICE to application threads.
        // Preserve the previous display priority on those devices instead of making
        // priority availability a renderer-fatal condition.
        errno = 0;
        const int fallback_result =
            setpriority(PRIO_PROCESS, 0, kDisplayNiceFallback);
        fallback_error = fallback_result == 0 ? 0 : errno;
    }
    errno = 0;
    const int effective_nice = getpriority(PRIO_PROCESS, 0);
    const int get_error = errno;
    const int log_priority = get_error == 0 && effective_nice <= kUrgentDisplayNice
        ? ANDROID_LOG_INFO
        : ANDROID_LOG_WARN;
    __android_log_print(
        log_priority,
        kLogTag,
        "thread-priority role=%s requested=%d before=%d effective=%d "
        "setErrno=%d fallbackErrno=%d getErrno=%d",
        role, kUrgentDisplayNice, before_nice, effective_nice,
        set_error, fallback_error, get_error);
}

bool contains(const char* value, const char* token) {
    return value != nullptr && token != nullptr && std::strstr(value, token) != nullptr;
}

bool exact_swappy_identity(
        const ntk::present::FixedFrameIdentity& local,
        const SwappyFixedFrameIdentityV1& swappy) noexcept {
    return swappy::fixedFrameIdentityValid(swappy) &&
        local.engineGeneration == swappy.engineGeneration &&
        local.surfaceEpoch == swappy.surfaceEpoch &&
        local.authorityGeneration == swappy.authorityGeneration &&
        local.authority == swappy.authority &&
        local.workGeneration == swappy.workGeneration &&
        local.ntkFrameId == swappy.ntkFrameId &&
        local.frameSequence == swappy.frameSequence &&
        local.admissionSequence == swappy.admissionSequence &&
        local.capsuleSequence == swappy.capsuleSequence &&
        local.backendSurfaceSerial == swappy.backendSurfaceSerial &&
        local.transactionSerial == swappy.transactionSerial &&
        local.bufferSlot == swappy.bufferSlot &&
        local.bufferGeneration == swappy.bufferGeneration &&
        local.frameTimelineVsyncId == swappy.frameTimelineVsyncId;
}

SwappyFixedFrameIdentityV1 to_swappy_identity(
        const ntk::present::FixedFrameIdentity& local) noexcept {
    SwappyFixedFrameIdentityV1 swappy{};
    swappy.structSize = sizeof(swappy);
    swappy.version = SWAPPY_FIXED_FRAME_IDENTITY_V1_VERSION;
    swappy.engineGeneration = local.engineGeneration;
    swappy.surfaceEpoch = local.surfaceEpoch;
    swappy.authorityGeneration = local.authorityGeneration;
    swappy.authority = local.authority;
    swappy.workGeneration = local.workGeneration;
    swappy.ntkFrameId = local.ntkFrameId;
    swappy.frameSequence = local.frameSequence;
    swappy.admissionSequence = local.admissionSequence;
    swappy.capsuleSequence = local.capsuleSequence;
    swappy.backendSurfaceSerial = local.backendSurfaceSerial;
    swappy.transactionSerial = local.transactionSerial;
    swappy.bufferSlot = local.bufferSlot;
    swappy.bufferGeneration = local.bufferGeneration;
    swappy.frameTimelineVsyncId = local.frameTimelineVsyncId;
    return swappy;
}

bool is_sha256(const std::string& value) {
    return value.size() == 64U && std::all_of(value.begin(), value.end(), [](char digit) {
        return (digit >= '0' && digit <= '9') || (digit >= 'a' && digit <= 'f');
    });
}

// Release proofs are part of the ownership protocol, so the inventory digest must not depend
// on a Java helper, process-global provider, or an optional platform crypto library. This small
// SHA-256 implementation hashes the exact canonical byte stream built below.
class Sha256 final {
public:
    void update(const void* source, std::size_t length) {
        const auto* bytes = static_cast<const std::uint8_t*>(source);
        bit_count_ += static_cast<std::uint64_t>(length) * 8ULL;
        while (length > 0) {
            const std::size_t copied = std::min(length, block_.size() - block_size_);
            std::memcpy(block_.data() + block_size_, bytes, copied);
            block_size_ += copied;
            bytes += copied;
            length -= copied;
            if (block_size_ == block_.size()) {
                transform(block_.data());
                block_size_ = 0;
            }
        }
    }

    std::string finish() {
        const std::uint64_t original_bits = bit_count_;
        const std::uint8_t marker = 0x80U;
        update(&marker, 1);
        const std::uint8_t zero = 0;
        while (block_size_ != 56U) update(&zero, 1);
        std::array<std::uint8_t, 8> length{};
        for (std::size_t index = 0; index < length.size(); ++index) {
            length[7U - index] = static_cast<std::uint8_t>(original_bits >> (index * 8U));
        }
        update(length.data(), length.size());
        char output[65]{};
        for (std::size_t index = 0; index < state_.size(); ++index) {
            std::snprintf(output + index * 8U, 9U, "%08x", state_[index]);
        }
        return output;
    }

private:
    static std::uint32_t rotate(std::uint32_t value, unsigned count) {
        return (value >> count) | (value << (32U - count));
    }

    void transform(const std::uint8_t* bytes) {
        static constexpr std::array<std::uint32_t, 64> constants{{
            0x428a2f98U,0x71374491U,0xb5c0fbcfU,0xe9b5dba5U,0x3956c25bU,0x59f111f1U,0x923f82a4U,0xab1c5ed5U,
            0xd807aa98U,0x12835b01U,0x243185beU,0x550c7dc3U,0x72be5d74U,0x80deb1feU,0x9bdc06a7U,0xc19bf174U,
            0xe49b69c1U,0xefbe4786U,0x0fc19dc6U,0x240ca1ccU,0x2de92c6fU,0x4a7484aaU,0x5cb0a9dcU,0x76f988daU,
            0x983e5152U,0xa831c66dU,0xb00327c8U,0xbf597fc7U,0xc6e00bf3U,0xd5a79147U,0x06ca6351U,0x14292967U,
            0x27b70a85U,0x2e1b2138U,0x4d2c6dfcU,0x53380d13U,0x650a7354U,0x766a0abbU,0x81c2c92eU,0x92722c85U,
            0xa2bfe8a1U,0xa81a664bU,0xc24b8b70U,0xc76c51a3U,0xd192e819U,0xd6990624U,0xf40e3585U,0x106aa070U,
            0x19a4c116U,0x1e376c08U,0x2748774cU,0x34b0bcb5U,0x391c0cb3U,0x4ed8aa4aU,0x5b9cca4fU,0x682e6ff3U,
            0x748f82eeU,0x78a5636fU,0x84c87814U,0x8cc70208U,0x90befffaU,0xa4506cebU,0xbef9a3f7U,0xc67178f2U
        }};
        std::array<std::uint32_t, 64> schedule{};
        for (std::size_t index = 0; index < 16U; ++index) {
            schedule[index] = (static_cast<std::uint32_t>(bytes[index * 4U]) << 24U) |
                (static_cast<std::uint32_t>(bytes[index * 4U + 1U]) << 16U) |
                (static_cast<std::uint32_t>(bytes[index * 4U + 2U]) << 8U) |
                static_cast<std::uint32_t>(bytes[index * 4U + 3U]);
        }
        for (std::size_t index = 16U; index < schedule.size(); ++index) {
            const std::uint32_t x = schedule[index - 15U];
            const std::uint32_t y = schedule[index - 2U];
            const std::uint32_t s0 = rotate(x, 7U) ^ rotate(x, 18U) ^ (x >> 3U);
            const std::uint32_t s1 = rotate(y, 17U) ^ rotate(y, 19U) ^ (y >> 10U);
            schedule[index] = schedule[index - 16U] + s0 + schedule[index - 7U] + s1;
        }
        std::uint32_t a=state_[0], b=state_[1], c=state_[2], d=state_[3];
        std::uint32_t e=state_[4], f=state_[5], g=state_[6], h=state_[7];
        for (std::size_t index = 0; index < schedule.size(); ++index) {
            const std::uint32_t s1 = rotate(e,6U)^rotate(e,11U)^rotate(e,25U);
            const std::uint32_t choice = (e&f)^((~e)&g);
            const std::uint32_t t1 = h+s1+choice+constants[index]+schedule[index];
            const std::uint32_t s0 = rotate(a,2U)^rotate(a,13U)^rotate(a,22U);
            const std::uint32_t majority = (a&b)^(a&c)^(b&c);
            const std::uint32_t t2 = s0+majority;
            h=g; g=f; f=e; e=d+t1; d=c; c=b; b=a; a=t1+t2;
        }
        state_[0]+=a; state_[1]+=b; state_[2]+=c; state_[3]+=d;
        state_[4]+=e; state_[5]+=f; state_[6]+=g; state_[7]+=h;
    }

    std::array<std::uint32_t, 8> state_{{
        0x6a09e667U,0xbb67ae85U,0x3c6ef372U,0xa54ff53aU,
        0x510e527fU,0x9b05688cU,0x1f83d9abU,0x5be0cd19U}};
    std::array<std::uint8_t, 64> block_{};
    std::size_t block_size_ = 0;
    std::uint64_t bit_count_ = 0;
};

void sha256_token(Sha256& digest, const std::string& token) {
    const std::uint32_t size = static_cast<std::uint32_t>(token.size());
    const std::array<std::uint8_t, 4> prefix{{
        static_cast<std::uint8_t>(size >> 24U),
        static_cast<std::uint8_t>(size >> 16U),
        static_cast<std::uint8_t>(size >> 8U),
        static_cast<std::uint8_t>(size)}};
    digest.update(prefix.data(), prefix.size());
    digest.update(token.data(), token.size());
}

// Evidence for the selected raw interval-0 forwarding path.  This deliberately
// says nothing about an eglSwapBuffers latency bound or non-blocking behavior;
// the exact per-submission runtime telemetry remains the qualification authority.
enum class RawZeroForwardingMode : std::uint8_t {
    NONE = 0,
    PORTABLE_EGL_CONFIG_MIN_ZERO,
    PINNED_API35_GFXSTREAM,
};

enum class BackendClass : std::uint8_t {
    NONE = 0,
    PORTABLE_MIN_ZERO,
    API35_GFXSTREAM_HOST_MIN1,
};

std::int64_t monotonic_now_ns() {
    timespec value{};
    clock_gettime(CLOCK_MONOTONIC, &value);
    return static_cast<std::int64_t>(value.tv_sec) * 1'000'000'000LL + value.tv_nsec;
}

std::mutex g_swappy_mutex;
int g_swappy_users = 0;
// SwappyCommon is process-global while StripRenderer instances may overlap
// during exact engine handoff.  Its monotonic admission ledger must therefore
// never receive a renderer-local generation that restarts at one.
std::atomic<std::uint64_t> g_fixed_work_generation{0};
std::atomic<std::uint64_t> g_ntk_frame_id{0};
std::atomic<std::int64_t> g_engine_create_count{0};
std::atomic<std::int64_t> g_engine_destroy_count{0};
std::atomic<std::int64_t> g_backend_retirement_serial{0};
std::atomic<bool> g_fail_next_native_create_for_testing{false};
std::atomic<bool> g_fail_next_callback_resolution_for_testing{false};

std::int64_t next_release_protocol_serial(std::uint64_t& watermark) {
    if (watermark >= static_cast<std::uint64_t>(
            std::numeric_limits<std::int64_t>::max())) {
        return 0;
    }
    return static_cast<std::int64_t>(++watermark);
}

std::uint64_t next_fixed_work_generation() {
    const std::uint64_t previous = g_fixed_work_generation.fetch_add(
        1, std::memory_order_acq_rel);
    return previous == std::numeric_limits<std::uint64_t>::max()
        ? 0 : previous + 1;
}

bool acquire_swappy(JNIEnv* env, jobject activity) {
    std::lock_guard<std::mutex> lock(g_swappy_mutex);
    if (g_swappy_users == 0) {
        if (!SwappyGL_init(env, activity)) {
            NTK_LOGE("SwappyGL_init failed");
            return false;
        }
        SwappyGL_setUseAffinity(false);
    }
    ++g_swappy_users;
    return true;
}

void release_swappy() {
    std::lock_guard<std::mutex> lock(g_swappy_mutex);
    if (g_swappy_users <= 0) return;
    --g_swappy_users;
    if (g_swappy_users == 0) SwappyGL_destroy();
}

struct TileKey {
    std::int64_t authority = 0;
    int page = -1;
    int slot = -1;

    bool operator==(const TileKey& other) const {
        return authority == other.authority && page == other.page && slot == other.slot;
    }
};

struct TileKeyHash {
    std::size_t operator()(const TileKey& key) const {
        std::size_t value = std::hash<std::int64_t>{}(key.authority);
        value ^= std::hash<int>{}(key.page) + 0x9e3779b9U + (value << 6U) + (value >> 2U);
        value ^= std::hash<int>{}(key.slot) + 0x9e3779b9U + (value << 6U) + (value >> 2U);
        return value;
    }
};

struct UploadCommand {
    TileKey key;
    std::int64_t authority_generation = 0;
    std::int64_t preparation_generation = 0;
    std::int64_t surface_epoch = 0;
    std::int64_t admission_id = 0;
    std::int64_t resource_revision = 0;
    std::int64_t install_lease = 0;
    std::int64_t rgba_bytes = 0;
    jobject bitmap = nullptr;
    std::int64_t content_top = 0;
    std::int64_t content_bottom = 0;
    int width = 0;
    int height = 0;
    std::string tile_proof_digest;
    bool pre_geometry = false;
    bool prepared_protocol = false;
};

struct PreallocateCommand {
    TileKey key;
    std::int64_t authority_generation = 0;
    int ordinal = -1;
    int width = 0;
    int height = 0;
    std::int64_t content_top = 0;
    std::int64_t content_bottom = 0;
};

struct PreallocatedTexture {
    GLuint texture = 0;
    int width = 0;
    int height = 0;
};

struct GpuReadyTile {
    TileKey key;
    std::int64_t authority_generation = 0;
    std::int64_t preparation_generation = 0;
    std::int64_t surface_epoch = 0;
    std::int64_t admission_id = 0;
    std::int64_t resource_revision = 0;
    std::int64_t install_lease = 0;
    std::int64_t rgba_bytes = 0;
    GLuint texture = 0;
    int width = 0;
    int height = 0;
    std::int64_t content_top = 0;
    std::int64_t content_bottom = 0;
    GLsync upload_fence = nullptr;
    std::int64_t fence_submitted_ns = 0;
    bool success = false;
    bool release_transition_output = false;
    bool consumed_preallocation = false;
    std::string tile_proof_digest;
    std::string resident_inventory_digest;
    std::int64_t resource_completion_ns = 0;
    bool pre_geometry = false;
    bool prepared_protocol = false;
};

struct PreparedBankTile {
    std::int64_t preparation_generation = 0;
    std::int64_t admission_id = 0;
    std::int64_t resource_revision = 0;
    std::int64_t install_lease = 0;
    std::int64_t rgba_bytes = 0;
    GLuint texture = 0;
    int width = 0;
    int height = 0;
    std::string tile_proof_digest;
    std::int64_t resource_completion_ns = 0;
};

struct PendingPublishAck {
    TileKey key;
    std::int64_t authority_generation = 0;
    std::int64_t surface_epoch = 0;
    std::int64_t admission_id = 0;
    std::int64_t resource_revision = 0;
    std::int64_t install_lease = 0;
    std::int64_t rgba_bytes = 0;
    std::int64_t scene_version = 0;
};

enum class RendererMode : std::uint8_t { PREPARING = 0, ARMED = 1, ACTIVE = 2 };

enum class GpuPhase : std::uint8_t {
    PRE_STAGE_CPU = 0,
    PRE_STAGE_GPU = 1,
    SEALING = 2,
    INPUT_ARMED = 3,
    GESTURE_ACTIVE = 4,
    DISARMING = 5,
    FAILED = 6,
};

enum class GpuResourceWorkerState : std::uint8_t {
    ABSENT = 0,
    PRE_STAGE_ACTIVE = 1,
    SEALING = 2,
    RETIRED = 3,
    FAILED = 4,
};

enum class ResourceWorkerStartState : std::uint8_t {
    IDLE = 0,
    STARTING = 1,
    READY = 2,
    FAILED = 3,
};

using HeadFrameState = ntk::scheduler::HeadFrameState;
using PendingFrameKind = ntk::scheduler::FrameKind;

enum class PreparedCommitResult : std::uint8_t {
    RETAINED = 0,
    SUBMITTED = 1,
    FATAL = 2,
    SUBMITTED_FATAL_AFTER_EGL_TRUE = 3,
    SLOT_CLOSED = 4,
};

enum class PresentDrainMode : std::uint8_t {
    NORMAL = 0,
    FORCE_DRAIN = 1,
};

enum class PresentPumpResult : std::uint8_t {
    IDLE = 0,
    PROGRESSED = 1,
    SUBMITTED = 2,
    FATAL = 3,
    SUBMITTED_FATAL = 4,
};

using PresentedViewState = ntk::scheduler::ViewState;

struct PreparedFrameWork {
    std::int64_t engine_generation = 0;
    std::uint64_t surface_epoch = 0;
    std::int64_t authority_generation = 0;
    std::int64_t authority = 0;
    std::int64_t scene_version = 0;
    std::uint64_t admission_sequence = 0;
    std::uint64_t work_generation = 0;
    std::uint64_t raw_authority_sequence = 0;
    std::uint64_t raw_baseline_sequence = 0;
    std::uint64_t swappy_reservation_sequence = 0;
    std::uint64_t last_consumed_opportunity_sequence = 0;
    std::uint64_t input_watermark = 0;
    PendingFrameKind kind = PendingFrameKind::MOVE;
    bool terminal = false;
    std::uint64_t terminal_input_sequence = 0;
    PresentedViewState view_state{};
    SwappyFixedPhasePlanInput input{};
    SwappyFixedPhaseTelemetry plan{};
    bool draw_issued = false;
    bool frame_id_reserved = false;
    bool stage_candidate = false;
    std::uint64_t frame_sequence = 0;
    std::int64_t draw_begin_ns = 0;
    std::int64_t draw_issue_end_ns = 0;
    std::int64_t frame_id_reservation_begin_ns = 0;
    std::int64_t frame_id_reserved_ns = 0;
    std::int64_t common_reservation_ns = 0;
    std::int64_t prior_post_swap_to_reservation_ns = 0;
    EGLuint64KHR frame_id = 0;
    std::uint64_t frame_id_count_before_submission = 0;
    std::int64_t continuous_start = 0;
    std::int64_t continuous_end = 0;
    std::int64_t visible_start = 0;
    std::int64_t visible_end = 0;
    std::int64_t visible_gap = -1;
    bool viewport_complete = false;
    bool runway_complete = false;
    int first_visible_page = -1;
    int last_visible_page = -1;
    std::int64_t predicted_stop = 0;
    std::uint64_t gesture_generation = 0;
    std::uint64_t visual_demand_epoch = 0;
    std::uint64_t visual_mutation_serial = 0;
    bool visible_state_changed = false;
    bool terminal_obligation_submitted = false;
    std::int64_t input_oldest_ns = 0;
    std::int64_t input_newest_ns = 0;
    std::int64_t main_ingress_oldest_ns = 0;
    std::int64_t main_ingress_newest_ns = 0;
    std::int64_t receipt_oldest_ns = 0;
    std::int64_t receipt_newest_ns = 0;
    std::int64_t mutation_oldest_ns = 0;
    std::int64_t mutation_newest_ns = 0;
    std::int64_t backend_ready_ns = 0;
    std::int64_t first_commit_attempt_ns = 0;
    std::uint64_t reserved_evidence_slot_sequence = 0;
    std::uint64_t buffer_slot = 0;
    std::uint64_t buffer_generation = 0;
    ntk::present::GpuSubmissionProof gpu_ready_proof{};
    ntk::present::SurfaceControlPresentBackend::PreparedSurfaceSubmission
        surface_submission{};
    SwappyFixedExternalTransportReady transport_ready{};
    SwappyFixedExternalClaim external_claim{};
    std::uint64_t claimed_candidate_sequence = 0;
    std::uint64_t claimed_opportunity_sequence = 0;
    std::uint64_t claimed_notice_sequence = 0;
};

struct AdmissionPredecessor {
    std::uint64_t work_generation = 0;
    std::uint64_t frame_sequence = 0;
    EGLuint64KHR frame_id = 0;
    std::uint64_t admission_sequence = 0;
    std::int64_t post_apply_nanos = 0;
    ntk::present::FixedFrameIdentity identity{};
};

enum class CadenceQualificationState : std::uint8_t {
    NO_SURFACE = 0,
    STAGE_PROOF_PENDING = 1,
    QUALIFIED_IDLE = 2,
    QUALIFIED_GESTURE = 3,
    DRAINING = 4,
    FAILED = 5,
};

enum class LatchProofState : std::uint8_t {
    RESERVED = 0,
    QUEUED = 1,
    PENDING = 2,
    LATCHED = 3,
    LOST = 4,
};

struct SceneTile {
    std::int64_t surface_epoch = 0;
    std::int64_t admission_id = 0;
    std::int64_t resource_revision = 0;
    std::int64_t install_lease = 0;
    std::int64_t rgba_bytes = 0;
    GLuint texture = 0;
    int width = 0;
    int height = 0;
    std::int64_t content_top = 0;
    std::int64_t content_bottom = 0;
};

struct SealedDrawTile {
    GLuint texture = 0;
    std::int64_t content_top = 0;
    std::int64_t content_bottom = 0;
    int page = -1;
    int slot = -1;
};

struct SealedDrawIndex {
    std::vector<SealedDrawTile> by_content_top;
    std::string scene_digest;
    std::int64_t scene_version = 0;
    std::int64_t content_height = 0;
    std::uint64_t resource_submit_serial = 0;
};

struct ProtectionCommit {
    std::int64_t authority_generation = 0;
    std::int64_t authority = 0;
    std::int64_t surface_epoch = 0;
    std::int64_t demand_epoch = 0;
    std::int64_t basis_frame_sequence = 0;
    std::int64_t basis_input_sequence = 0;
    int direction = 0;
    std::vector<int> protected_tile_ordinals;
    std::string protected_digest;
};

struct AppliedProtection {
    bool valid = false;
    ProtectionCommit commit;
    std::vector<std::uint8_t> protected_mask;
};

struct RetireIntent {
    std::int64_t authority_generation = 0;
    std::int64_t authority = 0;
    std::int64_t surface_epoch = 0;
    std::int64_t policy_surface_epoch = 0;
    std::int64_t demand_epoch = 0;
    std::int64_t basis_frame_sequence = 0;
    std::int64_t basis_input_sequence = 0;
    TileKey key;
    std::int64_t resource_revision = 0;
    std::int64_t install_lease = 0;
    std::int64_t retire_lease = 0;
    std::int64_t rgba_bytes = 0;
    std::string protected_digest;
};

enum class RetireResultCode : int {
    DETACHED = 0,
    STALE_POLICY = 1,
    PROTECTED = 2,
    VISIBLE_OR_RUNWAY = 3,
    NOT_RESIDENT = 4,
    FAILED = 5,
};

struct PendingResourceDelete {
    TileKey key;
    std::int64_t authority_generation = 0;
    std::int64_t preparation_generation = 0;
    std::int64_t surface_epoch = 0;
    std::int64_t policy_surface_epoch = 0;
    std::int64_t demand_epoch = 0;
    std::int64_t admission_id = 0;
    std::int64_t resource_revision = 0;
    std::int64_t install_lease = 0;
    std::int64_t retire_lease = 0;
    std::int64_t rgba_bytes = 0;
    std::string protected_digest;
    GLuint texture = 0;
    GLsync fence = nullptr;
    std::int64_t fence_submitted_ns = 0;
    bool notify_freed = false;
    bool detached_preparation = false;
};

struct AuthorityKey {
    std::int64_t engine_generation = 0;
    std::int64_t authority_generation = 0;
    std::int64_t authority = 0;

    bool operator==(const AuthorityKey& other) const {
        return engine_generation == other.engine_generation &&
            authority_generation == other.authority_generation && authority == other.authority;
    }
    bool operator<(const AuthorityKey& other) const {
        if (engine_generation != other.engine_generation) {
            return engine_generation < other.engine_generation;
        }
        if (authority_generation != other.authority_generation) {
            return authority_generation < other.authority_generation;
        }
        return authority < other.authority;
    }
};

struct BindTicket {
    std::uint64_t request_generation = 0;
    bool transition_started = false;
    bool cancel_requested = false;
    bool completed = false;
    bool success = false;
    std::int64_t accepted_authority_generation = 0;
    std::size_t adopted_prepared_count = 0;
    std::size_t missing_geometry_count = 0;
    std::string prepared_inventory_digest;
    std::string resident_inventory_digest;
    std::int64_t geometry_bind_completion_ns = 0;
    std::int64_t last_resource_completion_ns = 0;
};

enum class BindRequestKind : std::uint8_t {
    LEGACY_GEOMETRY = 0,
    OPEN_DETACHED_PREPARATION = 1,
    SURFACE_ADOPTION = 2,
};

enum class BindSceneDisposition : std::uint8_t {
    COMPLETED = 0,
    DEFERRED = 1,
    FAILED = 2,
};

struct BindRequest {
    BindRequestKind kind = BindRequestKind::LEGACY_GEOMETRY;
    std::uint64_t request_generation = 0;
    AuthorityKey successor;
    std::int64_t manifest_revision = 0;
    std::string manifest_digest;
    std::string geometry_digest;
    GpuSceneFormat gpu_scene_format = GpuSceneFormat::RGBA8_UNORM;
    std::int64_t gpu_scene_logical_bytes = 0;
    std::string gpu_scene_digest;
    std::int64_t content_height = 0;
    int viewport_width = 0;
    int viewport_height = 0;
    std::int64_t scroll_top = 0;
    std::int64_t preparation_generation = 0;
    std::int64_t demand_generation = 0;
    std::int64_t adoption_attach_generation = 0;
    std::int64_t adoption_surface_epoch = 0;
    std::int64_t adoption_geometry_revision = 0;
    int adoption_surface_width = 0;
    int adoption_surface_height = 0;
    std::string pregeometry_root_digest;
    std::string prepared_inventory_digest;
    std::unordered_map<TileKey, PreallocateCommand, TileKeyHash> slot_specs;
    std::vector<TileKey> ordinal_keys;
    std::unordered_map<TileKey, int, TileKeyHash> key_ordinals;
    std::shared_ptr<BindTicket> ticket;
};

struct PreparationOpenResult {
    std::int64_t authority_generation = 0;
    std::int64_t token_nonce = 0;
    std::int64_t opened_ns = 0;
};

struct PreparedGeometryBindResult {
    std::int64_t authority_generation = 0;
    std::size_t adopted_count = 0;
    std::size_t missing_count = 0;
    std::string prepared_inventory_digest;
    std::string resident_inventory_digest;
    std::int64_t completion_ns = 0;
    std::int64_t last_resource_completion_ns = 0;
};

struct ResourceWorkerLaunch {
    std::uint64_t generation = 0;
    AuthorityKey owner;
};

struct NativeAuthorityToken {
    AuthorityKey key;
    std::int64_t manifest_revision = 0;
    std::string manifest_digest;
    std::string geometry_digest;
};

enum class AuthorityLifecycle : std::uint8_t {
    BOUND,
    RELEASING_UNCLAIMED,
    RELEASING_CLAIMED,
    RELEASED,
    FAILED,
};

enum class PhysicalReleaseDisposition : int {
    EXPLICIT_DELETE = 0,
    CONTEXT_LOST = 1,
};

struct ReleaseClaim {
    AuthorityKey key;
    std::int64_t reducer_surface_epoch = 0;
    std::int64_t release_nonce = 0;
    // Internal transport for the synchronous live admission-close event. It is not part of
    // claim identity and is never included in proof digests or Java metadata.
    std::int64_t admission_close_serial = 0;
};

struct ReleaseResourceIdentity {
    std::string kind;
    AuthorityKey key;
    std::int64_t surface_epoch = 0;
    std::int64_t admission_id = 0;
    int page = -1;
    int slot = -1;
    std::int64_t resource_revision = 0;
    std::int64_t install_lease = 0;
    std::int64_t retire_lease = 0;
    std::int64_t rgba_bytes = 0;

    bool operator==(const ReleaseResourceIdentity& other) const {
        return kind == other.kind && key == other.key && surface_epoch == other.surface_epoch &&
            admission_id == other.admission_id && page == other.page && slot == other.slot &&
            resource_revision == other.resource_revision &&
            install_lease == other.install_lease && retire_lease == other.retire_lease &&
            rgba_bytes == other.rgba_bytes;
    }

    bool operator<(const ReleaseResourceIdentity& other) const {
        return std::tie(kind, key.engine_generation, key.authority_generation, key.authority,
                        surface_epoch, admission_id, page, slot, resource_revision,
                        install_lease, retire_lease, rgba_bytes) <
            std::tie(other.kind, other.key.engine_generation,
                     other.key.authority_generation, other.key.authority,
                     other.surface_epoch, other.admission_id, other.page, other.slot,
                     other.resource_revision, other.install_lease, other.retire_lease,
                     other.rgba_bytes);
    }
};

std::string release_inventory_digest(std::vector<ReleaseResourceIdentity> resources) {
    std::sort(resources.begin(), resources.end());
    Sha256 digest;
    for (const auto& resource : resources) {
        // Length-prefix the only variable field and encode every integer as fixed-width,
        // big-endian two's-complement. This is unambiguous and independent of host ABI.
        const std::uint32_t kind_length = static_cast<std::uint32_t>(resource.kind.size());
        std::array<std::uint8_t, 4> length_bytes{{
            static_cast<std::uint8_t>(kind_length >> 24U),
            static_cast<std::uint8_t>(kind_length >> 16U),
            static_cast<std::uint8_t>(kind_length >> 8U),
            static_cast<std::uint8_t>(kind_length)}};
        digest.update(length_bytes.data(), length_bytes.size());
        digest.update(resource.kind.data(), resource.kind.size());
        const auto append_i64 = [&](std::int64_t value) {
            const std::uint64_t bits = static_cast<std::uint64_t>(value);
            std::array<std::uint8_t, 8> bytes{};
            for (std::size_t i = 0; i < bytes.size(); ++i) {
                bytes[7U - i] = static_cast<std::uint8_t>(bits >> (i * 8U));
            }
            digest.update(bytes.data(), bytes.size());
        };
        append_i64(resource.key.engine_generation);
        append_i64(resource.key.authority_generation);
        append_i64(resource.key.authority);
        append_i64(resource.surface_epoch);
        append_i64(resource.admission_id);
        append_i64(resource.page);
        append_i64(resource.slot);
        append_i64(resource.resource_revision);
        append_i64(resource.install_lease);
        append_i64(resource.retire_lease);
        append_i64(resource.rgba_bytes);
    }
    return digest.finish();
}

struct AuthorityReleaseAckData {
    ReleaseClaim claim;
    PhysicalReleaseDisposition disposition = PhysicalReleaseDisposition::EXPLICIT_DELETE;
    std::int64_t admission_close_serial = 0;
    std::int64_t release_claim_serial = 0;
    std::int64_t resource_barrier_serial = 0;
    std::int64_t resource_completion_watermark = 0;
    std::int64_t feedback_barrier_serial = 0;
    int captured_resource_count = 0;
    std::int64_t captured_rgba_bytes = 0;
    std::string captured_resource_digest;
    int released_resource_count = 0;
    std::int64_t released_rgba_bytes = 0;
    std::string released_resource_digest;
    int deleted_texture_count = 0;
    int deleted_fence_count = 0;
    int released_bitmap_global_ref_count = 0;
    int drained_upload_count = 0;
    int drained_retire_count = 0;
    int remaining_command_count = 0;
    int remaining_resource_count = 0;
    std::int64_t remaining_rgba_bytes = 0;
    int remaining_fence_count = 0;
    int remaining_bitmap_global_ref_count = 0;
    int remaining_native_callback_count = 0;
    std::int64_t backend_retirement_serial = 0;
    std::int64_t backend_retired_nanos = 0;
    int retired_backend_remaining_thread_count = 0;
    int retired_backend_remaining_egl_handle_count = 0;
    int retired_backend_remaining_native_window_count = 0;
    int retired_backend_remaining_swappy_lease_count = 0;
    int retired_backend_remaining_jni_global_ref_count = 0;
    std::int64_t completed_nanos = 0;
    bool context_reusable = true;
    bool success = false;
};

struct AuthorityReleaseTracker {
    NativeAuthorityToken token;
    AuthorityLifecycle lifecycle = AuthorityLifecycle::RELEASING_UNCLAIMED;
    std::optional<ReleaseClaim> claim;
    std::int64_t admission_close_serial = 0;
    std::int64_t release_claim_serial = 0;
    std::int64_t resource_barrier_serial = 0;
    std::int64_t resource_completion_watermark = 0;
    std::int64_t feedback_barrier_serial = 0;
    std::uint64_t feedback_frame_target = 0;
    std::vector<ReleaseResourceIdentity> captured_resources;
    std::vector<ReleaseResourceIdentity> released_resources;
    std::string captured_resource_digest;
    std::string released_resource_digest;
    std::int64_t captured_rgba_bytes = 0;
    std::int64_t released_rgba_bytes = 0;
    std::unordered_map<TileKey, SceneTile, TileKeyHash> scene;
    std::deque<UploadCommand> queued_uploads;
    std::deque<GpuReadyTile> ready_tiles;
    std::deque<PendingResourceDelete> resource_deletes;
    std::unordered_map<TileKey, PreallocatedTexture, TileKeyHash> preallocated_textures;
    std::unordered_map<TileKey, PreparedBankTile, TileKeyHash> prepared_bank;
    std::unordered_map<TileKey, PreallocateCommand, TileKeyHash> slot_specs;
    std::vector<TileKey> ordinal_keys;
    std::unordered_map<TileKey, int, TileKeyHash> key_ordinals;
    std::vector<std::pair<std::int64_t, std::int64_t>> resident_intervals;
    AppliedProtection applied_protection;
    std::shared_ptr<void> suppressed_latch_records;
    std::shared_ptr<void> suppressed_resolved_records;
    GLsync render_fence = nullptr;
    std::int64_t render_fence_submitted_ns = 0;
    std::int64_t render_fence_surface_epoch = 0;
    bool in_flight_upload = false;
    bool in_flight_resource_delete = false;
    int outstanding_publications = 0;
    int pending_native_callbacks = 0;
    int deleted_texture_count = 0;
    int deleted_fence_count = 0;
    int released_bitmap_global_ref_count = 0;
    int drained_upload_count = 0;
    int drained_retire_count = 0;
    bool physical_complete = false;
    std::int64_t physical_complete_ns = 0;
    bool ack_enqueued = false;
};

enum class NativeHandleMode : std::int64_t {
    LIVE = 0,
    CONTEXT_LOSS_RETIRING = 1,
    RETIRED_PROOF_ONLY = 2,
    DESTROYED = 3,
};

/** Immutable CPU-only release evidence. Never add GL, EGL, JNI, thread, or Swappy owners. */
struct FrozenAuthorityReleaseProof {
    NativeAuthorityToken token;
    AuthorityLifecycle lifecycle = AuthorityLifecycle::RELEASING_UNCLAIMED;
    std::optional<ReleaseClaim> claim;
    AuthorityReleaseAckData frozen_ack;
    bool metadata_dispatch_in_progress = false;
};

/** Proof-only shell installed after the complete context-loss backend lifetime barrier. */
struct RetiredBackendProofStore {
    std::int64_t engine_generation = 0;
    std::uint64_t surface_epoch = 0;
    std::int64_t backend_retirement_serial = 0;
    std::int64_t backend_retired_nanos = 0;
    std::uint64_t terminal_feedback_barrier = 0;
    std::uint64_t release_protocol_serial_watermark = 0;
    std::string retired_authority_digest;
    std::map<AuthorityKey, FrozenAuthorityReleaseProof> authority_proofs;
};

/** Kotlin's exact DETACH_CLOSING frozen set. Keys select native full-token metadata. */
struct RetiredAuthoritySelection {
    std::set<AuthorityKey> keys;
    std::string full_token_digest;
};

enum class RetiredTrackerSelection : std::int64_t {
    FAIL = -1,
    EXCLUDE_HISTORICAL_RELEASED = 0,
    INCLUDE = 1,
};

RetiredTrackerSelection classify_retired_tracker_for_selection(
        const RetiredAuthoritySelection& selection, const AuthorityKey& key,
        AuthorityLifecycle lifecycle) {
    if (selection.keys.find(key) != selection.keys.end()) {
        return RetiredTrackerSelection::INCLUDE;
    }
    return lifecycle == AuthorityLifecycle::RELEASED
        ? RetiredTrackerSelection::EXCLUDE_HISTORICAL_RELEASED
        : RetiredTrackerSelection::FAIL;
}

template <typename MetadataPublisher, typename NativeTerminalizer,
          typename DispatchablePublisher>
bool publish_release_metadata_then_terminalize(
        MetadataPublisher&& publish_metadata,
        NativeTerminalizer&& terminalize_native,
        DispatchablePublisher&& publish_dispatchable) {
    publish_metadata();
    const bool terminalized = terminalize_native();
    if (terminalized) publish_dispatchable();
    return terminalized;
}

/** Frozen proof publication is the last bridge from a retired backend to Kotlin ownership. */
template <typename MetadataPublisher, typename NativeTerminalizer,
          typename DispatchablePublisher>
bool publish_frozen_release_metadata_then_terminalize(
        MetadataPublisher&& publish_metadata,
        NativeTerminalizer&& terminalize_native,
        DispatchablePublisher&& publish_dispatchable) {
    if (!publish_metadata()) return false;
    if (!terminalize_native()) return false;
    return publish_dispatchable();
}

std::string retired_authority_digest(
        const std::map<AuthorityKey, FrozenAuthorityReleaseProof>& proofs) {
    Sha256 digest;
    const auto append_i64 = [&](std::int64_t value) {
        const std::uint64_t bits = static_cast<std::uint64_t>(value);
        std::array<std::uint8_t, 8> bytes{};
        for (std::size_t index = 0; index < bytes.size(); ++index) {
            bytes[7U - index] = static_cast<std::uint8_t>(bits >> (index * 8U));
        }
        digest.update(bytes.data(), bytes.size());
    };
    const auto append_string = [&](const std::string& value) {
        const std::uint32_t size = static_cast<std::uint32_t>(value.size());
        const std::array<std::uint8_t, 4> size_bytes{{
            static_cast<std::uint8_t>(size >> 24U),
            static_cast<std::uint8_t>(size >> 16U),
            static_cast<std::uint8_t>(size >> 8U),
            static_cast<std::uint8_t>(size)}};
        digest.update(size_bytes.data(), size_bytes.size());
        digest.update(value.data(), value.size());
    };
    for (const auto& entry : proofs) {
        const auto& token = entry.second.token;
        append_i64(token.key.engine_generation);
        append_i64(token.key.authority_generation);
        append_i64(token.key.authority);
        append_i64(token.manifest_revision);
        append_string(token.manifest_digest);
        append_string(token.geometry_digest);
    }
    return digest.finish();
}

bool parse_retired_authority_selection(
        JNIEnv* env, jlongArray exact_authority_keys_array,
        jstring exact_authority_digest_string, std::int64_t engine_generation,
        RetiredAuthoritySelection* selection) {
    if (env == nullptr || exact_authority_keys_array == nullptr ||
        exact_authority_digest_string == nullptr || engine_generation <= 0 ||
        selection == nullptr) {
        return false;
    }
    const jsize value_count = env->GetArrayLength(exact_authority_keys_array);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }
    if (value_count < 0 || value_count % 3 != 0) return false;
    std::vector<jlong> values(static_cast<std::size_t>(value_count));
    if (value_count > 0) {
        env->GetLongArrayRegion(
            exact_authority_keys_array, 0, value_count, values.data());
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return false;
        }
    }
    const char* digest_chars = env->GetStringUTFChars(
        exact_authority_digest_string, nullptr);
    if (digest_chars == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return false;
    }
    std::string digest(digest_chars);
    env->ReleaseStringUTFChars(exact_authority_digest_string, digest_chars);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }
    if (!is_sha256(digest)) return false;

    RetiredAuthoritySelection parsed;
    parsed.full_token_digest = std::move(digest);
    AuthorityKey previous;
    bool has_previous = false;
    for (jsize index = 0; index < value_count; index += 3) {
        const AuthorityKey key{
            static_cast<std::int64_t>(values[static_cast<std::size_t>(index)]),
            static_cast<std::int64_t>(values[static_cast<std::size_t>(index + 1)]),
            static_cast<std::int64_t>(values[static_cast<std::size_t>(index + 2)])};
        if (key.engine_generation != engine_generation ||
            key.authority_generation <= 0 || key.authority <= 0 ||
            (has_previous && !(previous < key))) {
            return false;
        }
        parsed.keys.emplace(key);
        previous = key;
        has_previous = true;
    }
    if (parsed.keys.size() * 3U != static_cast<std::size_t>(value_count)) {
        return false;
    }
    *selection = std::move(parsed);
    return true;
}

using InputSample = ntk::scheduler::InputSample;

enum class TracePhase : std::uint8_t {
    PRE_WAIT,
    TARGET_REACHED,
    FENCE_COMPLETE,
    POST_WAIT,
    PRE_SWAP,
    POST_SWAP,
    START_FRAME,
    SWAP_INTERVAL_CHANGED
};

struct TraceRecord {
    std::uint64_t sequence = 0;
    std::uint64_t frame_sequence = 0;
    TracePhase phase = TracePhase::START_FRAME;
    std::int64_t timestamp_ns = 0;
    std::int64_t value = 0;
};

enum class FeedbackKind : std::uint8_t {
    TILE_RESIDENT,
    PREPARED_TILE_RESIDENT,
    PROTECTION_COMMITTED,
    RETIRE_RESULT,
    TILE_FREED,
    PRE_SUBMIT_VIEWPORT_GAP,
    AUTHORITY_RELEASED,
    BARRIER
};

struct FrameFeedback {
    EGLSurface surface = EGL_NO_SURFACE;
    EGLuint64KHR frame_id = 0;
    std::uint64_t frame_sequence = 0;
    std::int64_t engine_generation = 0;
    std::int64_t authority_generation = 0;
    std::int64_t authority = 0;
    std::int64_t scene_version = 0;
    std::int64_t scroll_top = 0;
    float velocity_px_per_second = 0.0F;
    std::int64_t predicted_stop_px = 0;
    std::int64_t continuous_start = 0;
    std::int64_t continuous_end = 0;
    std::int64_t visible_start = 0;
    std::int64_t visible_end = 0;
    int first_visible_page = -1;
    int last_visible_page = -1;
    std::int64_t first_visible_gap = -1;
    bool viewport_original_complete = false;
    bool runway_original_complete = false;
    std::int64_t gesture_id = 0;
    std::int64_t applied_input_sequence = 0;
    std::int64_t input_oldest_ns = 0;
    std::int64_t input_newest_ns = 0;
    std::int64_t main_ingress_oldest_ns = 0;
    std::int64_t main_ingress_newest_ns = 0;
    std::int64_t receipt_oldest_ns = 0;
    std::int64_t receipt_newest_ns = 0;
    std::int64_t mutation_oldest_ns = 0;
    std::int64_t mutation_newest_ns = 0;
    std::int64_t draw_begin_ns = 0;
    std::int64_t draw_issue_end_ns = 0;
    std::int64_t frame_id_reservation_begin_ns = 0;
    std::int64_t frame_id_reserved_ns = 0;
    std::int64_t pre_wait_ns = 0;
    std::int64_t target_reached_ns = 0;
    std::int64_t fence_complete_ns = 0;
    std::int64_t post_wait_ns = 0;
    std::int64_t pre_swap_ns = 0;
    std::int64_t post_swap_ns = 0;
    std::int64_t post_swap_critical_ns = 0;
    std::int64_t post_swap_to_next_reservation_ns = 0;
    std::int64_t common_callback_transaction_ns = 0;
    std::int64_t wake_dispatch_to_renderer_callback_ns = 0;
    std::int64_t renderer_callback_to_commit_entry_ns = 0;
    std::int64_t common_commit_entry_to_claim_ns = 0;
    bool backend_phase_partition_valid = false;
    std::uint64_t ready_commit_priority_violation_frames = 0;
    std::uint64_t pre_commit_retirement_observation_frames = 0;
    std::uint64_t retained_query_required_count = 0;
    std::uint64_t retained_query_executed_count = 0;
    std::uint64_t retained_query_wrong_selection_count = 0;
    std::uint64_t commit_before_retained_query_count = 0;
    std::uint64_t callback_arrived_during_query_count = 0;
    int evidence_capsule_depth = 0;
    int evidence_capsule_max_depth = 0;
    std::uint64_t evidence_capsule_invalid_frames = 0;
    // EGL_REQUESTED_PRESENT_TIME_ANDROID. With AUTO/no per-frame PTS this is
    // the BufferQueue queue timestamp for the exact frame id.
    std::int64_t queue_submit_ns = 0;
    std::uint32_t telemetry_schema_version = 0;
    std::uint64_t backend_completion_token = 0;
    std::uint64_t backend_surface_serial = 0;
    std::uint64_t backend_completion_work_generation = 0;
    std::uint64_t backend_completion_frame_id = 0;
    std::uint64_t backend_completion_gfxstream_frame_number = 0;
    std::uint32_t backend_completion_clock_domain = 0;
    std::int64_t backend_prepare_begin_ns = 0;
    std::int64_t backend_completion_signal_ns = 0;
    std::int64_t backend_wait_return_ns = 0;
    std::uint32_t backend_completion_issue_count = 0;
    std::uint32_t backend_completion_commit_count = 0;
    std::uint32_t backend_completion_publish_count = 0;
    std::uint64_t swap_interval_ns = 0;
    bool fixed_phase_telemetry_valid = false;
    std::uint64_t fixed_phase_sequence = 0;
    std::uint64_t fixed_phase_reservation_sequence = 0;
    std::uint64_t fixed_phase_opportunity_sequence = 0;
    int fixed_phase_opportunity_kind = 0;
    std::uint64_t fixed_phase_physical_callback_sequence = 0;
    std::int64_t fixed_phase_reservation_ns = 0;
    std::int64_t fixed_phase_opportunity_receipt_ns = 0;
    std::int64_t fixed_phase_opportunity_publish_ns = 0;
    std::int64_t fixed_phase_renderer_wake_observed_ns = 0;
    std::uint64_t fixed_candidate_sequence = 0;
    std::uint64_t fixed_candidate_raw_sequence = 0;
    std::int64_t fixed_candidate_capture_ns = 0;
    std::int64_t fixed_candidate_claim_ns = 0;
    std::uint32_t fixed_refresh_issued = 0;
    std::uint32_t fixed_refresh_delivered = 0;
    std::uint64_t fixed_refresh_physical_callback_sequence = 0;
    std::uint64_t fixed_refresh_captured_raw_sequence = 0;
    std::uint64_t fixed_shadow_raw_sequence = 0;
    std::uint64_t fixed_shadow_promotion_count = 0;
    std::uint64_t fixed_wake_notice_sequence = 0;
    std::uint64_t fixed_join_notice_sequence = 0;
    std::int64_t fixed_join_open_ns = 0;
    std::uint64_t fixed_join_prior_retirement_sequence = 0;
    std::uint64_t fixed_latch_observation_work_generation = 0;
    std::uint64_t fixed_latch_observation_admission_sequence = 0;
    std::uint64_t fixed_latch_observation_frame_id = 0;
    std::int64_t fixed_latch_observation_queue_ns = 0;
    std::int64_t fixed_latch_observation_latch_ns = 0;
    std::uint32_t fixed_latch_observation_query_count = 0;
    std::int64_t fixed_final_corridor_begin_ns = 0;
    std::int64_t fixed_queue_mark_ns = 0;
    std::int64_t fixed_egl_swap_enter_ns = 0;
    std::int64_t fixed_decision_to_egl_enter_ns = 0;
    std::int64_t fixed_common_commit_entry_ns = 0;
    std::int64_t fixed_opportunity_claim_ns = 0;
    std::uint64_t fixed_retirement_demand_issued = 0;
    std::uint64_t fixed_retirement_demand_satisfied = 0;
    std::uint64_t fixed_retirement_demand_cancelled = 0;
    std::uint64_t fixed_opportunity_demand_issued = 0;
    std::uint64_t fixed_opportunity_demand_satisfied = 0;
    std::uint64_t fixed_opportunity_demand_cancelled = 0;
    std::uint64_t fixed_superseded_before_claim_count = 0;
    std::uint64_t fixed_closed_opportunity_count = 0;
    std::uint64_t fixed_target_physical_callback_sequence = 0;
    std::int64_t fixed_target_frame_time_ns = 0;
    std::int64_t fixed_target_frame_index = 0;
    std::int64_t fixed_retirement_publish_ns = 0;
    std::int64_t fixed_renderer_wake_publish_ns = 0;
    std::uint64_t fixed_retirement_record_demand_issued = 0;
    std::uint64_t fixed_retirement_record_demand_satisfied = 0;
    std::uint64_t fixed_retirement_record_demand_cancelled = 0;
    std::uint64_t fixed_prior_retirement_work_generation = 0;
    std::uint64_t fixed_prior_retirement_admission_sequence = 0;
    std::uint64_t fixed_prior_retirement_sequence = 0;
    std::int64_t fixed_backend_ready_ns = 0;
    std::int64_t fixed_first_commit_attempt_ns = 0;
    std::uint32_t fixed_timestamp_query_before_first_commit_count = 0;
    bool fixed_phase_stale_target_observed = false;
    bool fixed_phase_miss_proven = false;
    int fixed_phase_outcome = 0;
    int fixed_phase_fatal_reason = 0;
    bool fixed_phase_plan_valid = false;
    std::int64_t fixed_phase_refresh_period_ns = 0;
    std::int64_t fixed_phase_app_vsync_offset_ns = 0;
    std::int64_t fixed_phase_accepted_frame_time_ns = 0;
    std::int64_t fixed_phase_accepted_frame_index = 0;
    std::int64_t fixed_phase_decision_ns = 0;
    std::int64_t fixed_phase_missed_presentation_ns = 0;
    std::int64_t fixed_phase_planned_presentation_ns = 0;
    std::int64_t fixed_phase_presentation_deadline_ns = 0;
    std::int64_t fixed_phase_open_ns = 0;
    std::int64_t fixed_phase_latest_swap_start_exclusive_ns = 0;
    std::int64_t fixed_phase_wait_ns = 0;
    std::int64_t fixed_phase_planned_target_frame = 0;
    std::int64_t fixed_phase_pre_swap_ns = 0;
    std::int64_t fixed_phase_post_swap_ns = 0;
    std::int64_t fixed_phase_swap_duration_ns = 0;
    int fixed_phase_fence_wait_count = 0;
    int fixed_phase_post_swap_target_rebase_count = 0;
    int control_backlog_max = 0;
    int move_mailbox_writes = 0;
    int integrated_tiles = 0;
    int upload_commands_submitting = 0;
    int upload_gpu_fences_pending = 0;
    int gpu_phase = static_cast<int>(GpuPhase::PRE_STAGE_CPU);
    bool sealed_scene = false;
    std::uint64_t resource_submit_serial = 0;
    std::uint64_t sealed_resource_submit_serial = 0;
    int ready_tile_queue_depth = 0;
    int native_publications_outstanding = 0;
    int pending_publish_acks = 0;
    int retire_queue_depth = 0;
    int retirement_count = 0;
    bool upload_context_alive = false;
    std::int64_t last_gpu_resource_completion_ns = 0;
    std::int64_t seal_fence_completion_ns = 0;
    std::int64_t upload_context_destroyed_ns = 0;
    std::int64_t stage_latch_ns = 0;
    std::int64_t first_down_ingress_ns = 0;
    std::int64_t sealed_scene_version = 0;
    int resource_worker_state = static_cast<int>(GpuResourceWorkerState::ABSENT);
    std::uint64_t resource_worker_generation = 0;
    std::uint64_t resource_worker_create_count = 0;
    std::uint64_t resource_worker_destroy_count = 0;
    int active_resource_worker_count = 0;
    int active_upload_context_count = 0;
    std::uint64_t scene_mutation_count_since_seal = 0;
    std::int64_t offscreen_warm_fence_completion_ns = 0;
    std::int64_t predecessor_physical_complete_ns = 0;
    std::uint64_t seal_barrier_serial = 0;
    std::int64_t stage_backbuffer_ready_ns = 0;
    std::uint64_t offscreen_warm_draw_count = 0;
    int frame_work_kind = -1;
    std::uint64_t admission_sequence = 0;
    std::uint64_t planner_invocation_count = 0;
    std::uint64_t backend_present_prepare_count = 0;
    std::uint64_t swap_attempt_count = 0;
    std::uint64_t slot_closed_no_attempt_count = 0;
    std::uint64_t terminal_swap_count = 0;
    std::uint64_t window_swap_count_before_stage = 0;
    std::uint64_t window_frame_id_count_before_stage = 0;
    // Immutable preparation/submission identity and exactly-once accounting for this frame.
    // These values are copied into FrameFeedback before the renderer can prepare later work,
    // so delayed latch/JNI delivery cannot accidentally observe successor counters.
    std::uint64_t prepared_work_generation = 0;
    std::uint64_t swappy_work_generation = 0;
    std::uint64_t swappy_admission_sequence = 0;
    std::uint64_t prepared_draw_count = 0;
    std::uint64_t prepared_frame_id_reservation_count = 0;
    bool admission_consumed = false;
    std::int64_t timestamp_query_work_ns = 0;
    bool stage_candidate = false;
    std::int64_t stage_nonce = 0;
    std::int64_t stage_corridor_start = 0;
    std::int64_t stage_corridor_end = 0;
    bool latch_timestamp_supported = false;
    bool presentation_timestamp_supported = false;
    bool latch_complete = false;
    bool presentation_complete = false;
    bool stage_callback_sent = false;
    std::int64_t latch_time_ns = 0;
    std::int64_t present_time_ns = 0;
    std::int64_t feedback_deadline_ns = 0;
    std::int64_t next_timestamp_query_ns = 0;
    EGLint last_latch_query_error = EGL_SUCCESS;
    EGLint last_present_query_error = EGL_SUCCESS;
    std::uint64_t surface_epoch = 0;
    EGLuint64KHR frame_id_telemetry = 0;
    LatchProofState latch_proof_state = LatchProofState::RESERVED;
    int logical_unlatched_submissions = 0;
    int max_logical_unlatched_submissions = 0;
    std::int64_t oldest_unlatched_age_ns = 0;
    std::int64_t latch_evidence_deadline_ns = 0;
    bool cadence_qualification_failed = false;
};

struct SubmittedGpuInvariantSnapshot {
    int sceneFormat = static_cast<int>(GpuSceneFormat::RGBA8_UNORM);
    int expectedTextureCount = 0;
    int residentTextureCount = 0;
    std::int64_t expectedLogicalBytes = 0;
    std::int64_t residentLogicalBytes = 0;
    std::string sceneDigest;
    int controlBacklogMax = 0;
    int moveMailboxWrites = 0;
    int integratedTiles = 0;
    int uploadCommandsSubmitting = 0;
    int uploadGpuFencesPending = 0;
    int gpuPhase = static_cast<int>(GpuPhase::PRE_STAGE_CPU);
    bool sealedScene = false;
    std::uint64_t resourceSubmitSerial = 0;
    std::uint64_t sealedResourceSubmitSerial = 0;
    int readyTileQueueDepth = 0;
    int nativePublicationsOutstanding = 0;
    int pendingPublishAcks = 0;
    int retireQueueDepth = 0;
    int retirementCount = 0;
    bool uploadContextAlive = false;
    std::int64_t lastGpuResourceCompletionNanos = 0;
    std::int64_t sealFenceCompletionNanos = 0;
    std::int64_t uploadContextDestroyedNanos = 0;
    std::int64_t stageLatchNanos = 0;
    std::int64_t firstDownIngressNanos = 0;
    std::int64_t sealedSceneVersion = 0;
    int resourceWorkerState = static_cast<int>(GpuResourceWorkerState::ABSENT);
    std::uint64_t resourceWorkerGeneration = 0;
    std::uint64_t resourceWorkerCreateCount = 0;
    std::uint64_t resourceWorkerDestroyCount = 0;
    int activeResourceWorkerCount = 0;
    int activeUploadContextCount = 0;
    std::uint64_t sceneMutationCountSinceSeal = 0;
    std::int64_t offscreenWarmFenceCompletionNanos = 0;
    std::int64_t predecessorPhysicalCompleteNanos = 0;
    std::uint64_t sealBarrierSerial = 0;
    std::int64_t stageBackbufferReadyNanos = 0;
    std::uint64_t offscreenWarmDrawCount = 0;
    std::uint64_t plannerInvocationCount = 0;
    std::uint64_t backendPresentPrepareCount = 0;
    std::uint64_t swapAttemptCount = 0;
    std::uint64_t slotClosedNoAttemptCount = 0;
    std::uint64_t terminalSwapCount = 0;
    std::uint64_t windowSwapCountBeforeStage = 0;
    std::uint64_t windowFrameIdCountBeforeStage = 0;
    std::uint64_t preparedDrawCount = 0;
    std::uint64_t preparedFrameIdReservationCount = 0;
    std::uint64_t swapIntervalNanos = 0;
    std::int64_t stageNonce = 0;
    std::int64_t stageCorridorStart = 0;
    std::int64_t stageCorridorEnd = 0;
    bool latchTimestampSupported = false;
    bool presentationTimestampSupported = false;
};

struct SubmittedEvidenceCapsule {
    std::uint64_t capsuleSequence = 0;
    std::uint64_t workGeneration = 0;
    std::uint64_t frameSequence = 0;
    EGLuint64KHR frameId = 0;
    std::uint64_t admissionSequence = 0;
    PreparedFrameWork prepared{};
    SwappyFixedPhaseTelemetry phase{};
    SubmittedGpuInvariantSnapshot gpu{};
    bool exactPhaseTelemetry = false;
    bool qualificationSensitive = false;
    std::int64_t feedbackDeadlineNanos = 0;
    std::int64_t postSwapCriticalBeginNanos = 0;
    std::int64_t postSwapCriticalEndNanos = 0;
    std::uint64_t readyCommitPriorityViolationFrames = 0;
    std::uint64_t preCommitRetirementObservationFrames = 0;
    int evidenceCapsuleDepth = 0;
    int evidenceCapsuleMaxDepth = 0;
    std::uint64_t evidenceCapsuleInvalidFrames = 0;
    ntk::present::FixedFrameIdentity identity{};
    ntk::present::GpuSubmissionProof gpuReadyProof{};
    std::int64_t transactionApplyBeginNanos = 0;
    std::int64_t transactionApplyEndNanos = 0;
    std::uint32_t setBufferCount = 0;
    std::uint32_t transactionApplyCount = 0;
    std::uint32_t onCommitCallbackCount = 0;
    std::uint32_t onCompleteCallbackCount = 0;
    std::uint64_t acquireFenceSerial = 0;
    std::int64_t acquireFenceSignalNanos = 0;
    std::uint64_t acquireFenceEventSequence = 0;
    std::uint32_t proofFdCloseCount = 0;
    bool applyBeforeAcquireSignalProven = false;
    std::uint32_t latchSource = 0;
    std::uint64_t latchEventSequence = 0;
    std::int64_t latchCallbackObservedNanos = 0;
    std::uint64_t swappyRetirementSequence = 0;
    std::int64_t retirementCallbackObservedNanos = 0;
    std::uint64_t previousBufferSlot = 0;
    std::uint64_t previousBufferGeneration = 0;
    bool previousBufferExpected = false;
    ntk::present::AppliedBufferRef previousAppliedBufferRef{};
    ntk::present::AppliedBufferRef appliedBufferRef{};
    std::uint32_t targetUnretiredNow = 0;
    std::uint32_t targetUnretiredMax = 0;
    std::uint32_t preparedProducerNow = 0;
    std::uint32_t preparedProducerMax = 0;
    ntk::present::SurfaceControlPresentBackend::ConservationSnapshot
        postApplyConservation{};
    bool postApplyConservationExact = false;
    bool postApplyExternalClaimPresent = false;
    RendererPostApplyFatalBranch postApplyFatalBranch =
        RendererPostApplyFatalBranch::NONE;
    std::uint64_t postSubmitSuccessfulCount = 0;
    std::uint64_t postSubmitLatchedProofCount = 0;
    std::uint64_t postSubmitTerminalLostProofCount = 0;
    std::uint64_t postSubmitLogicalUnlatchedNow = 0;
    std::uint64_t postSubmitMaxLogicalUnlatched = 0;
};

struct FrameLocalConservationProof {
    bool postApplyExact = false;
    bool latchTransitionExact = false;
    bool completionTransitionExact = false;
    bool previousReleaseTransitionExact = false;
    bool acquireFenceTransitionExact = false;
    std::uint32_t latchTransitionCount = 0;
    std::uint32_t completionTransitionCount = 0;
    std::uint32_t previousReleaseTransitionCount = 0;
    std::uint32_t acquireFenceTransitionCount = 0;
};

struct CompletedFrameEvidence {
    SubmittedEvidenceCapsule capsule{};
    ntk::present::FixedPresentEvent latchEvent{};
    SwappyFixedRetirementTelemetryV2 retirementEvent{};
    ntk::present::SurfaceControlPresentBackend::ConservationSnapshot
        conservation{};
    std::uint64_t transactionCompleteEventSequence = 0;
    std::uint64_t previousReleaseEventSequence = 0;
    std::uint64_t acquireFenceEventSequence = 0;
    std::uint32_t retirementCallbackObservedCount = 0;
    bool externalClaimPresent = false;
    bool qualified = false;
};

struct EvidenceCapsuleSlot {
    SubmittedEvidenceCapsule capsule{};
    CompletedFrameEvidence completed{};
    bool cadenceQualificationFailed = false;
    ntk::present::LatchTerminalState latchTerminalState =
        ntk::present::LatchTerminalState::WAITING_EVENT;
    ntk::present::RetirementTerminalState retirementTerminalState =
        ntk::present::RetirementTerminalState::WAITING_EVENT;
    ntk::present::FixedPresentEvent latchEvent{};
    SwappyFixedRetirementTelemetryV2 retirementEvent{};
    std::uint32_t retirementCallbackObservedCount = 0;
    bool transactionCompleteTerminal = false;
    bool transactionCompleteExact = false;
    std::uint64_t transactionCompleteEventSequence = 0;
    bool previousReleaseTerminal = false;
    bool previousReleaseExact = false;
    std::uint64_t previousReleaseEventSequence = 0;
    bool acquireFenceTerminal = false;
    bool acquireFenceExact = false;
    std::uint64_t acquireFenceEventSequence = 0;
    FrameLocalConservationProof conservationProof{};
    std::atomic<std::uint64_t> committedSequence{0};
    std::atomic<std::uint64_t> completedSequence{0};
};

struct FixedRetirementEventSlot {
    SwappyFixedRetirementTelemetryV2 event{};
    std::atomic<std::uint64_t> committedSequence{0};
};

struct FeedbackRecord {
    FeedbackKind kind = FeedbackKind::TILE_RESIDENT;
    std::int64_t engine_generation = 0;
    std::int64_t authority_generation = 0;
    TileKey key;
    std::int64_t surface_epoch = 0;
    std::int64_t policy_surface_epoch = 0;
    std::int64_t demand_epoch = 0;
    std::int64_t admission_id = 0;
    std::int64_t value = 0;
    std::int64_t resource_revision = 0;
    std::int64_t install_lease = 0;
    std::int64_t retire_lease = 0;
    std::int64_t rgba_bytes = 0;
    std::string protected_digest;
    std::string tile_proof_digest;
    std::string resident_inventory_digest;
    std::int64_t resource_completion_ns = 0;
    bool pre_geometry = false;
    RetireResultCode retire_result = RetireResultCode::FAILED;
    std::int64_t fence_serial = 0;
    bool success = false;
    std::uint64_t barrier_sequence = 0;
    std::uint64_t frame_target_sequence = 0;
    std::shared_ptr<AuthorityReleaseAckData> release_ack;
};

enum class AttachState : std::uint8_t {
    QUEUED = 0,
    CLAIMED = 1,
    READY = 2,
    PUBLISHED = 3,
    LOSS_PENDING = 4,
    TERMINAL = 5,
};

enum class NativeAttachResultCode : std::int64_t {
    FAILED = 0,
    READY = 1,
    CANCELLED_BEFORE_CLAIM = 2,
    ATTACHED_LOSS_PENDING = 3,
};

struct NativeAttachResult {
    NativeAttachResultCode code = NativeAttachResultCode::FAILED;
    std::uint64_t generation = 0;
    std::uint64_t surface_epoch = 0;
    std::uint64_t applied_geometry_revision = 0;
    int width = 0;
    int height = 0;
    std::uint64_t completed_ns = 0;
};

struct NativeResizeAck {
    bool success = false;
    std::uint64_t generation = 0;
    std::uint64_t surface_epoch = 0;
    std::uint64_t applied_geometry_revision = 0;
    int width = 0;
    int height = 0;
};

struct AttachRequest {
    std::uint64_t generation = 0;
    std::uint64_t surface_epoch = 0;
    std::uint64_t requested_geometry_revision = 0;
    std::uint64_t applied_geometry_revision = 0;
    int width = 0;
    int height = 0;
    std::uint64_t refresh_period_ns = kNinetyHzPeriodNs;
    ANativeWindow* window = nullptr;
    AttachState state = AttachState::QUEUED;
    bool surface_loss_requested = false;
    bool timed_out_unclaimed = false;
    bool success = false;
    std::uint64_t completed_ns = 0;
};

ntk::surface_lease::Registry<ANativeWindow>& native_surface_lease_registry() {
    static ntk::surface_lease::Registry<ANativeWindow> registry(
        [](ANativeWindow* window) {
            if (window != nullptr) ANativeWindow_release(window);
        });
    return registry;
}

std::atomic<std::int64_t> g_renderer_owned_surface_lease_count{0};

class EnvScope final {
public:
    explicit EnvScope(JavaVM* vm) : vm_(vm) {
        if (vm_ == nullptr) return;
        void* raw = nullptr;
        const jint result = vm_->GetEnv(&raw, JNI_VERSION_1_6);
        if (result == JNI_OK) {
            env_ = static_cast<JNIEnv*>(raw);
        } else if (result == JNI_EDETACHED &&
                   vm_->AttachCurrentThread(&env_, nullptr) == JNI_OK) {
            attached_ = true;
        }
    }

    ~EnvScope() {
        if (attached_ && vm_ != nullptr) vm_->DetachCurrentThread();
    }

    JNIEnv* get() const { return env_; }

private:
    JavaVM* vm_ = nullptr;
    JNIEnv* env_ = nullptr;
    bool attached_ = false;
};

class StripRenderer final {
public:
    StripRenderer(JNIEnv* env, jobject activity, jobject callback,
                  std::string qualification_profile_id, std::int64_t engine_generation)
        : native_create_begin_ns_(monotonic_now_ns()),
          swappy_init_begin_ns_(monotonic_now_ns()),
          swappy_ready_(acquire_swappy(env, activity)),
          swappy_init_end_ns_(monotonic_now_ns()),
          engine_generation_(engine_generation),
          qualification_manifest_verified_(
              qualification_profile_id == kQualificationProfileId) {
        g_engine_create_count.fetch_add(1, std::memory_order_acq_rel);
        const bool forced_callback_failure =
            g_fail_next_callback_resolution_for_testing.exchange(
                false, std::memory_order_acq_rel);
        const bool vm_resolved = env->GetJavaVM(&java_vm_) == JNI_OK &&
            java_vm_ != nullptr;
        callback_ = env->NewGlobalRef(callback);
        jclass callback_class = env->GetObjectClass(callback);
        const auto resolve = [&](const char* name, const char* descriptor) {
            if (forced_callback_failure || callback_class == nullptr ||
                env->ExceptionCheck()) return static_cast<jmethodID>(nullptr);
            return env->GetMethodID(callback_class, name, descriptor);
        };
        on_tile_resident_ = resolve(
            "onNativeTileResident", "(JJJJJIIJJJJZ)V");
        on_prepared_tile_resident_ = resolve(
            "onNativePreparedTileResident",
            "(JJJJJIIJJJLjava/lang/String;Ljava/lang/String;JZZ)V");
        on_protection_committed_ = resolve(
            "onNativeProtectionCommitted", "(JJJJJLjava/lang/String;JZ)V");
        on_retire_result_ = resolve(
            "onNativeRetireResult", "(JJJJJJIIJJJLjava/lang/String;IJJ)V");
        on_tile_freed_ = resolve(
            "onNativeTileFreed", "(JJJJJJJIIJJJJLjava/lang/String;JZ)V");
        on_pre_submit_viewport_gap_ = resolve(
            "onNativePreSubmitViewportGap", "(JJJJJ)V");
        on_frame_evidence_v11_ = resolve(
            "onNativeFrameEvidenceV11", "([B)V");
        on_stage_latched_v2_ = resolve(
            "onNativeStageLatchedV2",
            "(JJJJJJJJJJJJJJJJIIIJJLjava/lang/String;JJ)V");
        on_authority_released_ = resolve(
            "onNativeAuthorityReleased",
            "(JJJJJIJJJJJIJLjava/lang/String;IJLjava/lang/String;IIIIIIIJIIIJJIIIIIJZZ)V");
        on_authority_release_dispatchable_ = resolve(
            "onNativeAuthorityReleaseDispatchable", "(JJJJ)V");
        const bool callbacks_resolved = vm_resolved && callback_ != nullptr &&
            callback_class != nullptr && on_tile_resident_ != nullptr &&
            on_prepared_tile_resident_ != nullptr &&
            on_protection_committed_ != nullptr && on_retire_result_ != nullptr &&
            on_tile_freed_ != nullptr && on_pre_submit_viewport_gap_ != nullptr &&
            on_frame_evidence_v11_ != nullptr &&
            on_stage_latched_v2_ != nullptr &&
            on_authority_released_ != nullptr &&
            on_authority_release_dispatchable_ != nullptr &&
            !env->ExceptionCheck() && !forced_callback_failure;
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (callback_class != nullptr) env->DeleteLocalRef(callback_class);
        if (!callbacks_resolved) {
            if (callback_ != nullptr) env->DeleteGlobalRef(callback_);
            callback_ = nullptr;
            return;
        }
        configure_swappy_tracer();
        feedback_thread_ = std::thread(&StripRenderer::feedback_loop, this);
        render_thread_ = std::thread(&StripRenderer::render_loop, this);
        upload_thread_ = std::thread(
            &StripRenderer::upload_loop, this,
            ResourceWorkerLaunch{1, AuthorityKey{engine_generation_, 0, 0}});
        initialization_valid_ = true;
        native_create_end_ns_.store(monotonic_now_ns(), std::memory_order_release);
    }

    ~StripRenderer() {
        ANativeWindow* abandoned_attach_window = nullptr;
        {
            std::unique_lock<std::mutex> lock(mutex_);
            stopped_ = true;
            upload_exit_requested_ = true;
            if (attach_request_.has_value()) {
                attach_request_->surface_loss_requested = true;
                attach_request_->state = AttachState::TERMINAL;
                attach_request_->success = false;
                abandoned_attach_window = attach_request_->window;
                attach_request_->window = nullptr;
            }
            ++command_generation_;
        }
        if (abandoned_attach_window != nullptr) {
            ANativeWindow_release(abandoned_attach_window);
            g_renderer_owned_surface_lease_count.fetch_sub(
                1, std::memory_order_acq_rel);
        }
        preparation_drain_condition_.notify_all();
        render_condition_.notify_all();
        upload_condition_.notify_all();
        upload_start_condition_.notify_all();
        bind_condition_.notify_all();
        attach_condition_.notify_all();
        if (upload_thread_.joinable()) {
            upload_thread_.join();
            record_resource_worker_thread_joined();
        }
        if (render_thread_.joinable()) render_thread_.join();
        // Unregister after the swap owner has stopped but before tearing down the feedback lane
        // or JNI state. The Swappy registry's lifetime lock makes this a quiescence barrier for
        // every callback carrying this renderer as userData, including successor-engine races.
        if (swappy_ready_ && swappy_tracer_injected_ && !swappy_lifetime_released_) {
            SwappyGL_uninjectTracer(&swappy_tracer_);
            swappy_tracer_injected_ = false;
        }
        {
            std::lock_guard<std::mutex> lock(feedback_mutex_);
            feedback_exit_requested_ = true;
        }
        feedback_ready_.notify_all();
        feedback_space_.notify_all();
        if (feedback_thread_.joinable()) feedback_thread_.join();
        EnvScope scope(java_vm_);
        if (scope.get() != nullptr && callback_ != nullptr) {
            scope.get()->DeleteGlobalRef(callback_);
            callback_ = nullptr;
        }
        if (swappy_ready_ && !swappy_lifetime_released_) release_swappy();
        g_engine_destroy_count.fetch_add(1, std::memory_order_acq_rel);
    }

    bool initialization_valid() const { return initialization_valid_; }

    std::array<std::int64_t, 9> await_detached_warm() {
        std::unique_lock<std::mutex> lock(mutex_);
        attach_condition_.wait(lock, [&] {
            return render_initialization_complete_ || stopped_ || render_exited_;
        });
        const ntk::detached_warm::Snapshot warm_snapshot{
            render_pbuffer_ready_ns_.load(std::memory_order_acquire) > 0,
            upload_pbuffer_ready_ns_.load(std::memory_order_acquire) > 0,
            program_ready_ns_.load(std::memory_order_acquire) > 0,
            detached_warm_ready_ns_.load(std::memory_order_acquire) > 0,
            attach_request_.has_value() ? 1ULL : 0ULL,
            static_cast<std::uint64_t>(
                surface_control_attach_count_.load(std::memory_order_acquire)),
            static_cast<std::uint64_t>(
                active_authority_.load(std::memory_order_acquire)),
            static_cast<std::uint64_t>(
                window_frame_id_count_.load(std::memory_order_acquire)),
            static_cast<std::uint64_t>(
                window_swap_count_.load(std::memory_order_acquire)),
        };
        const bool exact_warm =
            render_initialization_complete_ && egl_ready_ &&
            !stopped_ && !render_exited_ &&
            ntk::detached_warm::exactWarm(warm_snapshot);
        if (!exact_warm) return {{engine_generation_, 0, 0, 0, 0, 0, 0, 0, 0}};
        return {{
            engine_generation_,
            egl_ready_ns_.load(std::memory_order_acquire),
            render_pbuffer_ready_ns_.load(std::memory_order_acquire),
            upload_pbuffer_ready_ns_.load(std::memory_order_acquire),
            program_ready_ns_.load(std::memory_order_acquire),
            0,
            surface_control_attach_count_.load(std::memory_order_acquire),
            window_frame_id_count_.load(std::memory_order_acquire),
            window_swap_count_.load(std::memory_order_acquire),
        }};
    }

    bool queue_attach_lease(
            ntk::surface_lease::Registry<ANativeWindow>::Transfer lease,
            std::uint64_t attach_generation,
            int width,
            int height,
            std::uint64_t geometry_revision,
            std::uint64_t refresh_period_ns,
            std::uint64_t surface_epoch) {
        std::lock_guard<std::mutex> api_lock(attach_api_mutex_);
        if (!lease || lease.surfaceEpoch != surface_epoch ||
            attach_generation == 0 || width <= 0 || height <= 0 ||
            geometry_revision == 0 || surface_epoch == 0) {
            if (lease.window != nullptr) ANativeWindow_release(lease.window);
            return false;
        }
        std::lock_guard<std::mutex> lock(mutex_);
        if (stopped_ || render_exited_ ||
            attach_authority_failed_.load(std::memory_order_acquire) ||
            authority_failed_.load(std::memory_order_acquire) ||
            detach_requested_ || attach_request_.has_value() ||
            !ntk::attach_generation::generationStrictlyMonotonic(
                last_attach_generation_, attach_generation)) {
            ANativeWindow_release(lease.window);
            return false;
        }
        last_attach_generation_ = attach_generation;
        attach_request_ = AttachRequest{
            .generation = attach_generation,
            .surface_epoch = surface_epoch,
            .requested_geometry_revision = geometry_revision,
            .applied_geometry_revision = 0,
            .width = width,
            .height = height,
            .refresh_period_ns = refresh_period_ns,
            .window = lease.window,
            .state = AttachState::QUEUED,
        };
        attach_lease_queued_ns_.store(monotonic_now_ns(), std::memory_order_release);
        g_renderer_owned_surface_lease_count.fetch_add(
            1, std::memory_order_acq_rel);
        ++command_generation_;
        render_condition_.notify_one();
        return true;
    }

    NativeAttachResult await_attach(
            std::uint64_t attach_generation,
            std::uint64_t surface_epoch) {
        std::unique_lock<std::mutex> lock(mutex_);
        auto exact_request = [&]() -> AttachRequest* {
            if (!attach_request_.has_value() ||
                attach_request_->generation != attach_generation ||
                attach_request_->surface_epoch != surface_epoch) {
                return nullptr;
            }
            return &*attach_request_;
        };
        AttachRequest* request = exact_request();
        if (request == nullptr) {
            return NativeAttachResult{
                .generation = attach_generation,
                .surface_epoch = surface_epoch,
            };
        }
        attach_condition_.wait(lock, [&] {
            request = exact_request();
            return request == nullptr || stopped_ || render_exited_ ||
                render_initialization_complete_ ||
                request->state == AttachState::TERMINAL;
        });
        request = exact_request();
        if (request == nullptr) {
            return NativeAttachResult{
                .generation = attach_generation,
                .surface_epoch = surface_epoch,
            };
        }
        if (!render_initialization_complete_ || !egl_ready_ ||
            stopped_ || render_exited_) {
            request->state = AttachState::TERMINAL;
            request->success = false;
        }
        constexpr auto kAttachTimeout = std::chrono::seconds(1);
        const auto deadline = std::chrono::steady_clock::now() + kAttachTimeout;
        while (request->state != AttachState::READY &&
               request->state != AttachState::LOSS_PENDING &&
               request->state != AttachState::TERMINAL &&
               !render_exited_) {
            const auto completed = [&] {
                request = exact_request();
                return request == nullptr || render_exited_ ||
                    request->state == AttachState::READY ||
                    request->state == AttachState::LOSS_PENDING ||
                    request->state == AttachState::TERMINAL;
            };
            if (attach_condition_.wait_until(lock, deadline, completed)) continue;
            request = exact_request();
            if (request == nullptr) break;
            if (ntk::attach_generation::timeoutDisposition(
                    request->generation,
                    request->state == AttachState::CLAIMED
                        ? request->generation : 0) ==
                ntk::attach_generation::TimeoutDisposition::
                    WAIT_FOR_CLAIMED_COMPLETION) {
                attach_condition_.wait(lock, completed);
                continue;
            }
            request->timed_out_unclaimed = true;
            request->state = AttachState::TERMINAL;
            request->success = false;
            block_input_and_presentation();
            attach_authority_failed_.store(true, std::memory_order_release);
            authority_failed_.store(true, std::memory_order_release);
            gpu_phase_.store(GpuPhase::FAILED, std::memory_order_release);
            ANativeWindow* released = request->window;
            request->window = nullptr;
            ++command_generation_;
            lock.unlock();
            if (released != nullptr) {
                ANativeWindow_release(released);
                g_renderer_owned_surface_lease_count.fetch_sub(
                    1, std::memory_order_acq_rel);
            }
            render_condition_.notify_one();
            lock.lock();
            request = exact_request();
            break;
        }
        request = exact_request();
        if (request == nullptr) {
            return NativeAttachResult{
                .generation = attach_generation,
                .surface_epoch = surface_epoch,
            };
        }
        NativeAttachResultCode code = NativeAttachResultCode::FAILED;
        if (request->state == AttachState::READY && request->success) {
            code = NativeAttachResultCode::READY;
        } else if (request->state == AttachState::LOSS_PENDING &&
                   request->success) {
            code = NativeAttachResultCode::ATTACHED_LOSS_PENDING;
        } else if (request->state == AttachState::TERMINAL &&
                   request->surface_loss_requested &&
                   !request->timed_out_unclaimed) {
            code = NativeAttachResultCode::CANCELLED_BEFORE_CLAIM;
        }
        return NativeAttachResult{
            .code = code,
            .generation = request->generation,
            .surface_epoch = request->surface_epoch,
            .applied_geometry_revision = request->applied_geometry_revision,
            .width = request->width,
            .height = request->height,
            .completed_ns = request->completed_ns,
        };
    }

    bool update_attach_geometry(
            std::uint64_t attach_generation,
            std::uint64_t surface_epoch,
            int width,
            int height,
            std::uint64_t geometry_revision) {
        if (width <= 0 || height <= 0 || geometry_revision == 0) return false;
        std::lock_guard<std::mutex> lock(mutex_);
        if (!attach_request_.has_value()) return false;
        auto& request = *attach_request_;
        if (request.generation != attach_generation ||
            request.surface_epoch != surface_epoch ||
            request.state == AttachState::PUBLISHED ||
            request.state == AttachState::LOSS_PENDING ||
            request.state == AttachState::TERMINAL ||
            geometry_revision < request.requested_geometry_revision) {
            return false;
        }
        request.width = width;
        request.height = height;
        request.requested_geometry_revision = geometry_revision;
        ++command_generation_;
        render_condition_.notify_one();
        return true;
    }

    NativeResizeAck apply_resize_before_publish(
            std::uint64_t attach_generation,
            std::uint64_t surface_epoch,
            std::uint64_t geometry_revision) {
        std::unique_lock<std::mutex> lock(mutex_);
        if (!attach_request_.has_value()) return {};
        auto exact = [&] {
            return attach_request_.has_value() &&
                attach_request_->generation == attach_generation &&
                attach_request_->surface_epoch == surface_epoch;
        };
        if (!exact()) return {};
        auto& request = *attach_request_;
        if (geometry_revision > request.requested_geometry_revision) return {};
        ++command_generation_;
        lock.unlock();
        render_condition_.notify_one();
        lock.lock();
        attach_condition_.wait(lock, [&] {
            return !exact() || render_exited_ ||
                attach_request_->state == AttachState::LOSS_PENDING ||
                attach_request_->state == AttachState::TERMINAL ||
                (attach_request_->state == AttachState::READY &&
                 attach_request_->applied_geometry_revision >= geometry_revision);
        });
        if (!exact()) return {};
        const auto& completed = *attach_request_;
        return NativeResizeAck{
            .success = completed.state == AttachState::READY &&
                completed.applied_geometry_revision == geometry_revision &&
                completed.requested_geometry_revision == geometry_revision,
            .generation = completed.generation,
            .surface_epoch = completed.surface_epoch,
            .applied_geometry_revision = completed.applied_geometry_revision,
            .width = completed.width,
            .height = completed.height,
        };
    }

    bool publish_attach(
            std::uint64_t attach_generation,
            std::uint64_t surface_epoch,
            std::uint64_t geometry_revision) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!attach_request_.has_value()) return false;
        auto& request = *attach_request_;
        if (!ntk::attach_generation::publishAllowed(
                request.generation,
                request.state == AttachState::READY ? request.generation : 0,
                request.surface_epoch, surface_epoch,
                request.requested_geometry_revision,
                request.applied_geometry_revision,
                request.surface_loss_requested,
                request.state == AttachState::TERMINAL) ||
            request.generation != attach_generation ||
            request.applied_geometry_revision != geometry_revision ||
            !request.success || !present_backend_attached_) {
            return false;
        }
        attach_published_ns_.store(monotonic_now_ns(), std::memory_order_release);
        request.state = AttachState::PUBLISHED;
        admitted_surface_epoch_.store(surface_epoch, std::memory_order_release);
        presentation_blocked_.store(false, std::memory_order_release);
        return true;
    }

    ntk::attach_generation::SurfaceLossDisposition request_surface_loss(
            std::uint64_t attach_generation,
            std::uint64_t surface_epoch) {
        ANativeWindow* release_window = nullptr;
        ntk::attach_generation::SurfaceLossDisposition disposition;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (!attach_request_.has_value()) {
                return ntk::attach_generation::SurfaceLossDisposition::
                    IDENTITY_MISMATCH;
            }
            auto& request = *attach_request_;
            const bool identity_matches =
                request.generation == attach_generation &&
                request.surface_epoch == surface_epoch;
            disposition = ntk::attach_generation::surfaceLossDisposition(
                request.generation,
                request.state == AttachState::CLAIMED ||
                        request.state == AttachState::READY ||
                        request.state == AttachState::LOSS_PENDING
                    ? request.generation : 0,
                request.state == AttachState::PUBLISHED
                    ? request.generation : 0,
                request.state == AttachState::TERMINAL
                    ? request.generation : 0,
                identity_matches);
            if (!identity_matches) return disposition;
            block_input_and_presentation();
            admitted_surface_epoch_.store(0, std::memory_order_release);
            request.surface_loss_requested = true;
            if (disposition ==
                ntk::attach_generation::SurfaceLossDisposition::
                    CANCELLED_UNCLAIMED) {
                request.state = AttachState::TERMINAL;
                request.success = false;
                request.completed_ns = monotonic_now_ns();
                release_window = request.window;
                request.window = nullptr;
            } else if (disposition ==
                       ntk::attach_generation::SurfaceLossDisposition::
                           COMPLETE_CLAIMED_THEN_DETACH &&
                       request.state == AttachState::READY) {
                request.state = AttachState::LOSS_PENDING;
            } else if (disposition ==
                       ntk::attach_generation::SurfaceLossDisposition::
                           DETACH_PUBLISHED) {
                request.state = AttachState::LOSS_PENDING;
            }
            ++command_generation_;
        }
        if (release_window != nullptr) {
            ANativeWindow_release(release_window);
            g_renderer_owned_surface_lease_count.fetch_sub(
                1, std::memory_order_acq_rel);
        }
        attach_condition_.notify_all();
        render_condition_.notify_one();
        return disposition;
    }

    void resize(
            std::uint64_t attach_generation,
            std::uint64_t surface_epoch,
            int width,
            int height) {
        if (width <= 0 || height <= 0) return;
        std::lock_guard<std::mutex> lock(mutex_);
        if (!attach_request_.has_value() ||
            attach_request_->generation != attach_generation ||
            attach_request_->surface_epoch != surface_epoch ||
            attach_request_->state != AttachState::PUBLISHED) {
            return;
        }
        // Published Surface geometry changes are not expected in the fixed portrait profile.
        // Keep exact identity and fail closed rather than silently mutating the schema11 pool.
        if (width != width_ || height != height_) {
            block_input_and_presentation();
            authority_failed_.store(true, std::memory_order_release);
        }
    }

    void set_context_loss_for_testing() {
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (context_loss_pending_) return;
            context_loss_surface_epoch_ = admitted_surface_epoch_.exchange(
                0, std::memory_order_acq_rel);
            context_loss_pending_ = true;
            context_resources_valid_.store(false, std::memory_order_release);
            upload_submission_blocked_.store(true, std::memory_order_release);
            block_input_and_presentation();
            render_requested_ = true;
            ++command_generation_;
        }
        render_condition_.notify_all();
        upload_condition_.notify_all();
        upload_start_condition_.notify_all();
        attach_condition_.notify_all();
    }

    void set_context_loss_during_detach_for_testing() {
        test_context_loss_during_detach_.store(true, std::memory_order_release);
    }

    PreparationOpenResult open_detached_preparation(
            std::int64_t authority, std::int64_t authority_generation_candidate,
            std::int64_t preparation_generation, std::int64_t manifest_revision,
            std::string manifest_digest) {
        std::lock_guard<std::mutex> bind_api_lock(bind_api_mutex_);
        if (authority <= 0 || authority_generation_candidate <= 0 ||
            preparation_generation <= 0 ||
            manifest_revision < 0 || !is_sha256(manifest_digest)) return {};
        BindRequest request;
        request.kind = BindRequestKind::OPEN_DETACHED_PREPARATION;
        request.successor = AuthorityKey{
            engine_generation_, authority_generation_candidate, authority};
        request.preparation_generation = preparation_generation;
        request.manifest_revision = manifest_revision;
        request.manifest_digest = std::move(manifest_digest);
        auto ticket = std::make_shared<BindTicket>();
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (stopped_ || engine_failed_.load(std::memory_order_acquire) ||
                !context_resources_valid_.load(std::memory_order_acquire) ||
                !egl_ready_ ||
                !upload_context_alive_.load(std::memory_order_acquire)) return {};
            if (preparation_open_ && authority == authority_ &&
                authority_generation_candidate == authority_generation_) {
                if (manifest_revision != current_manifest_revision_ ||
                    request.manifest_digest != current_manifest_digest_ ||
                    prepared_bank_ledger_.preparationGeneration() !=
                        preparation_generation) return {};
                return PreparationOpenResult{
                    authority_generation_, preparation_token_nonce_,
                    preparation_opened_ns_};
            }
            if (authority_generation_candidate <= max_authority_generation_ ||
                release_trackers_.find(request.successor) != release_trackers_.end() ||
                pending_bind_request_.has_value()) return {};
            active_authority_generation_.store(0, std::memory_order_release);
            active_authority_.store(0, std::memory_order_release);
            upload_submission_blocked_.store(true, std::memory_order_release);
            input_admission_blocked_.store(true, std::memory_order_release);
            max_authority_generation_ = authority_generation_candidate;
            request.request_generation = ++bind_request_generation_;
            ticket->request_generation = request.request_generation;
            request.ticket = ticket;
            pending_bind_request_ = std::move(request);
            render_requested_ = true;
            ++command_generation_;
        }
        render_condition_.notify_one();
        upload_condition_.notify_one();
        std::unique_lock<std::mutex> lock(mutex_);
        bind_condition_.wait(lock, [&] { return stopped_ || ticket->completed; });
        if (stopped_ || !ticket->completed || !ticket->success ||
            ticket->accepted_authority_generation != authority_generation_candidate ||
            authority_generation_ != authority_generation_candidate) return {};
        return PreparationOpenResult{
            authority_generation_candidate,
            static_cast<std::int64_t>(ticket->request_generation),
            ticket->geometry_bind_completion_ns};
    }

    std::int64_t bind(std::int64_t authority, std::int64_t authority_generation_candidate,
              std::int64_t manifest_revision,
              std::string manifest_digest, std::string geometry_digest,
              std::string pregeometry_root_digest,
              int gpu_scene_format, std::int64_t gpu_scene_logical_bytes,
              std::string gpu_scene_digest,
              std::int64_t content_height, int viewport_width,
              int viewport_height, std::int64_t scroll_top,
              const std::vector<PreallocateCommand>& slots) {
        // Kotlin deliberately releases its protocol lock across JNI. Serialize the complete
        // native transaction so a later caller cannot overtake an earlier return and publish a
        // different current token before the first caller performs its Kotlin bookkeeping.
        std::lock_guard<std::mutex> bind_api_lock(bind_api_mutex_);
        if (authority <= 0 || authority_generation_candidate <= 0 || manifest_revision < 0 ||
            !is_sha256(manifest_digest) || !is_sha256(geometry_digest) ||
            !is_sha256(pregeometry_root_digest) ||
            gpu_scene_format != static_cast<int>(GpuSceneFormat::RGBA8_UNORM) ||
            gpu_scene_logical_bytes <= 0 || !is_sha256(gpu_scene_digest) ||
            content_height <= 0 || viewport_width <= 0 ||
            viewport_height <= 0) {
            return 0;
        }
        if (slots.empty()) return 0;
        for (const auto& slot : slots) {
            if (slot.key.authority != authority || slot.key.page < 0 || slot.key.slot < 0 ||
                slot.ordinal < 0 || static_cast<std::size_t>(slot.ordinal) >= slots.size() ||
                slot.width <= 0 || slot.height <= 0) return 0;
        }
        std::int64_t computed_gpu_bytes = 0;
        for (const auto& slot : slots) {
            const std::int64_t bytes = rgba8_bytes(slot.width, slot.height);
            if (bytes <= 0 || computed_gpu_bytes >
                    std::numeric_limits<std::int64_t>::max() - bytes) return 0;
            computed_gpu_bytes += bytes;
        }
        if (computed_gpu_bytes != gpu_scene_logical_bytes ||
            gpu_scene_digest_from_slots(
                geometry_digest, pregeometry_root_digest, slots) != gpu_scene_digest) return 0;

        BindRequest request;
        request.successor = AuthorityKey{
            engine_generation_, authority_generation_candidate, authority};
        request.manifest_revision = manifest_revision;
        request.manifest_digest = std::move(manifest_digest);
        request.geometry_digest = std::move(geometry_digest);
        request.pregeometry_root_digest = std::move(pregeometry_root_digest);
        request.gpu_scene_format = GpuSceneFormat::RGBA8_UNORM;
        request.gpu_scene_logical_bytes = gpu_scene_logical_bytes;
        request.gpu_scene_digest = std::move(gpu_scene_digest);
        request.content_height = content_height;
        request.viewport_width = viewport_width;
        request.viewport_height = viewport_height;
        request.scroll_top = std::max<std::int64_t>(0, scroll_top);
        request.ordinal_keys.assign(slots.size(), TileKey{});
        for (const auto& slot : slots) {
            PreallocateCommand scoped = slot;
            scoped.authority_generation = authority_generation_candidate;
            if (!request.slot_specs.emplace(scoped.key, scoped).second ||
                !request.key_ordinals.emplace(scoped.key, scoped.ordinal).second) return 0;
            request.ordinal_keys[static_cast<std::size_t>(slot.ordinal)] = slot.key;
        }
        auto ticket = std::make_shared<BindTicket>();
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (stopped_ || engine_failed_.load(std::memory_order_acquire) ||
                !context_resources_valid_.load(std::memory_order_acquire)) return 0;
            if (authority == authority_ && authority_generation_candidate == authority_generation_ &&
                authority_ > 0) {
                if (request.manifest_revision != current_manifest_revision_ ||
                    request.manifest_digest != current_manifest_digest_ ||
                    request.geometry_digest != current_geometry_digest_ ||
                    request.pregeometry_root_digest != current_pregeometry_root_digest_ ||
                    request.gpu_scene_format != gpu_scene_admission_.format ||
                    request.gpu_scene_logical_bytes !=
                        gpu_scene_admission_.expected_logical_bytes ||
                    request.gpu_scene_digest != gpu_scene_admission_.expected_digest ||
                    request.content_height != content_height_) {
                    NTK_LOGE("fatal in-place manifest/geometry replacement authority=%lld",
                             static_cast<long long>(authority));
                    authority_failed_.store(true, std::memory_order_release);
                    gpu_phase_.store(GpuPhase::FAILED, std::memory_order_release);
                    return 0;
                }
                const GpuPhase same_phase = gpu_phase_.load(std::memory_order_acquire);
                if (same_phase == GpuPhase::SEALING || same_phase == GpuPhase::INPUT_ARMED ||
                    same_phase == GpuPhase::GESTURE_ACTIVE) return authority_generation_;
                return authority_generation_;
            }
            if (authority_generation_candidate <= max_authority_generation_ ||
                release_trackers_.find(request.successor) != release_trackers_.end() ||
                pending_bind_request_.has_value()) return 0;
            if (authority_generation_candidate != authority_generation_) {
                // Close the old admission gate before publishing successor bind metadata. An
                // in-flight upload observes the exact generation mismatch at its next chunk.
                active_authority_generation_.store(0, std::memory_order_release);
                active_authority_.store(0, std::memory_order_release);
                upload_submission_blocked_.store(true, std::memory_order_release);
                // The JNI caller closes only external mutation/input admission. Presentation,
                // phase, and render-owned state are closed at the next render-loop boundary so
                // an already-admitted token can always reach its one required swap.
                input_admission_blocked_.store(true, std::memory_order_release);
            }
            max_authority_generation_ = authority_generation_candidate;
            request.request_generation = ++bind_request_generation_;
            ticket->request_generation = request.request_generation;
            request.ticket = ticket;
            pending_bind_request_ = std::move(request);
            render_requested_ = true;
            ++command_generation_;
        }
        render_condition_.notify_one();
        upload_condition_.notify_one();
        std::unique_lock<std::mutex> lock(mutex_);
        bind_condition_.wait(lock, [&] { return stopped_ || ticket->completed; });
        return !stopped_ && ticket->completed && ticket->success &&
                ticket->accepted_authority_generation == authority_generation_candidate &&
                authority_generation_ == authority_generation_candidate
            ? authority_generation_candidate : 0;
    }

    PreparedGeometryBindResult adopt_detached_preparation_to_surface(
            std::int64_t authority, std::int64_t authority_generation,
            std::int64_t preparation_generation,
            std::int64_t demand_generation,
            std::int64_t attach_generation,
            std::int64_t surface_epoch,
            std::int64_t geometry_revision,
            std::int64_t manifest_revision,
            std::string manifest_digest,
            std::string geometry_digest, std::string pregeometry_root_digest,
            std::string prepared_inventory_digest, int gpu_scene_format,
            std::int64_t gpu_scene_logical_bytes, std::string gpu_scene_digest,
            std::int64_t content_height, int viewport_width, int viewport_height,
            std::int64_t scroll_top, const std::vector<PreallocateCommand>& slots) {
        std::lock_guard<std::mutex> bind_api_lock(bind_api_mutex_);
        if (authority <= 0 || authority_generation <= 0 ||
            preparation_generation <= 0 || demand_generation <= 0 ||
            attach_generation <= 0 || surface_epoch <= 0 ||
            geometry_revision <= 0 ||
            manifest_revision < 0 ||
            !is_sha256(manifest_digest) || !is_sha256(geometry_digest) ||
            !is_sha256(pregeometry_root_digest) ||
            !is_sha256(prepared_inventory_digest) ||
            gpu_scene_format != static_cast<int>(GpuSceneFormat::RGBA8_UNORM) ||
            gpu_scene_logical_bytes <= 0 || !is_sha256(gpu_scene_digest) ||
            content_height <= 0 || viewport_width <= 0 || viewport_height <= 0 ||
            slots.empty()) return {};
        std::int64_t computed_bytes = 0;
        for (const auto& slot : slots) {
            if (slot.key.authority != authority || slot.key.page < 0 || slot.key.slot < 0 ||
                slot.ordinal < 0 || static_cast<std::size_t>(slot.ordinal) >= slots.size() ||
                slot.width <= 0 || slot.height <= 0) return {};
            const std::int64_t bytes = rgba8_bytes(slot.width, slot.height);
            if (bytes <= 0 || computed_bytes >
                    std::numeric_limits<std::int64_t>::max() - bytes) return {};
            computed_bytes += bytes;
        }
        if (computed_bytes != gpu_scene_logical_bytes ||
            gpu_scene_digest_from_slots(
                geometry_digest, pregeometry_root_digest, slots) != gpu_scene_digest) return {};
        BindRequest request;
        request.kind = BindRequestKind::SURFACE_ADOPTION;
        request.successor = AuthorityKey{engine_generation_, authority_generation, authority};
        request.manifest_revision = manifest_revision;
        request.preparation_generation = preparation_generation;
        request.demand_generation = demand_generation;
        request.adoption_attach_generation = attach_generation;
        request.adoption_surface_epoch = surface_epoch;
        request.adoption_geometry_revision = geometry_revision;
        request.adoption_surface_width = viewport_width;
        request.adoption_surface_height = viewport_height;
        request.manifest_digest = std::move(manifest_digest);
        request.geometry_digest = std::move(geometry_digest);
        request.pregeometry_root_digest = std::move(pregeometry_root_digest);
        request.prepared_inventory_digest = std::move(prepared_inventory_digest);
        request.gpu_scene_format = GpuSceneFormat::RGBA8_UNORM;
        request.gpu_scene_logical_bytes = gpu_scene_logical_bytes;
        request.gpu_scene_digest = std::move(gpu_scene_digest);
        request.content_height = content_height;
        request.viewport_width = viewport_width;
        request.viewport_height = viewport_height;
        request.scroll_top = std::max<std::int64_t>(0, scroll_top);
        request.ordinal_keys.assign(slots.size(), TileKey{});
        for (const auto& slot : slots) {
            PreallocateCommand scoped = slot;
            scoped.authority_generation = authority_generation;
            if (!request.slot_specs.emplace(scoped.key, scoped).second ||
                !request.key_ordinals.emplace(scoped.key, scoped.ordinal).second) return {};
            request.ordinal_keys[static_cast<std::size_t>(scoped.ordinal)] = scoped.key;
        }
        auto ticket = std::make_shared<BindTicket>();
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (stopped_ || engine_failed_.load(std::memory_order_acquire) ||
                !context_resources_valid_.load(std::memory_order_acquire) ||
                !preparation_open_ || prepared_geometry_bound_ || authority != authority_ ||
                authority_generation != authority_generation_ ||
                prepared_bank_ledger_.preparationGeneration() !=
                    preparation_generation ||
                static_cast<std::uint64_t>(surface_epoch) != surface_epoch_ ||
                static_cast<std::uint64_t>(surface_epoch) !=
                    admitted_surface_epoch_.load(std::memory_order_acquire) ||
                !attach_request_.has_value() ||
                attach_request_->generation !=
                    static_cast<std::uint64_t>(attach_generation) ||
                attach_request_->surface_epoch !=
                    static_cast<std::uint64_t>(surface_epoch) ||
                attach_request_->state != AttachState::PUBLISHED ||
                attach_request_->applied_geometry_revision !=
                    static_cast<std::uint64_t>(geometry_revision) ||
                attach_request_->width != viewport_width ||
                attach_request_->height != viewport_height ||
                manifest_revision != current_manifest_revision_ ||
                request.manifest_digest != current_manifest_digest_ ||
                pending_bind_request_.has_value()) return {};
            request.request_generation = ++bind_request_generation_;
            ticket->request_generation = request.request_generation;
            request.ticket = ticket;
            pending_bind_request_ = std::move(request);
            render_requested_ = true;
            ++command_generation_;
        }
        render_condition_.notify_one();
        std::unique_lock<std::mutex> lock(mutex_);
        bind_condition_.wait(lock, [&] { return stopped_ || ticket->completed; });
        if (stopped_ || !ticket->completed || !ticket->success ||
            ticket->accepted_authority_generation != authority_generation) return {};
        return PreparedGeometryBindResult{
            authority_generation, ticket->adopted_prepared_count,
            ticket->missing_geometry_count, ticket->prepared_inventory_digest,
            ticket->resident_inventory_digest,
            ticket->geometry_bind_completion_ns,
            ticket->last_resource_completion_ns};
    }

    bool disarm(std::int64_t authority_generation, std::int64_t authority) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (stopped_ || authority <= 0 || authority_generation <= 0 ||
            authority != authority_ || authority_generation != authority_generation_) return false;
        const GpuPhase phase = gpu_phase_.load(std::memory_order_acquire);
        if (phase != GpuPhase::INPUT_ARMED && phase != GpuPhase::GESTURE_ACTIVE &&
            phase != GpuPhase::FAILED) return false;
        input_admission_blocked_.store(true, std::memory_order_release);
        renderer_mode_.store(RendererMode::PREPARING, std::memory_order_release);
        stage_pin_active_.store(false, std::memory_order_release);
        disarm_requested_ = true;
        render_requested_ = true;
        ++command_generation_;
        render_condition_.notify_one();
        return true;
    }

    bool upload(JNIEnv* env, std::int64_t authority_generation, const TileKey& key,
                std::int64_t surface_epoch,
                std::int64_t admission_id, std::int64_t resource_revision,
                std::int64_t install_lease, std::int64_t rgba_bytes, jobject bitmap,
                std::int64_t content_top, std::int64_t content_bottom) {
        if (key.authority <= 0 || key.page < 0 || key.slot < 0 || bitmap == nullptr ||
            surface_epoch <= 0 || admission_id <= 0 || resource_revision <= 0 ||
            install_lease <= 0 || rgba_bytes <= 0 ||
            content_top < 0 || content_bottom <= content_top) {
            return false;
        }
        jobject global_bitmap = env->NewGlobalRef(bitmap);
        if (global_bitmap == nullptr) return false;
        bool accepted = false;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            const std::size_t native_outstanding = native_outstanding_;
            const GpuPhase phase = gpu_phase_.load(std::memory_order_acquire);
            constexpr std::size_t native_cap = 1U;
            if (stopped_ || authority_failed_ || authority_generation != authority_generation_ ||
                key.authority != authority_ ||
                static_cast<std::uint64_t>(surface_epoch) !=
                    admitted_surface_epoch_.load(std::memory_order_acquire) ||
                upload_submission_blocked_.load(std::memory_order_acquire) ||
                !upload_context_alive_.load(std::memory_order_acquire) ||
                !resource_worker_owns(authority_generation, key.authority) ||
                gpu_resource_worker_state_.load(std::memory_order_acquire) !=
                    GpuResourceWorkerState::PRE_STAGE_ACTIVE ||
                native_outstanding >= native_cap ||
                phase != GpuPhase::PRE_STAGE_GPU) {
                accepted = false;
            } else {
                const auto spec = slot_specs_.find(key);
                if (spec != slot_specs_.end() && spec->second.authority_generation ==
                        authority_generation && spec->second.content_top == content_top &&
                    spec->second.content_bottom == content_bottom) {
                    UploadCommand command;
                    command.key = key;
                    command.authority_generation = authority_generation;
                    command.surface_epoch = surface_epoch;
                    command.admission_id = admission_id;
                    command.resource_revision = resource_revision;
                    command.install_lease = install_lease;
                    command.rgba_bytes = rgba_bytes;
                    command.bitmap = global_bitmap;
                    command.content_top = content_top;
                    command.content_bottom = content_bottom;
                    upload_commands_.push_back(std::move(command));
                    const AuthorityKey token{
                        engine_generation_, authority_generation, key.authority};
                    registry_add_locked(release_identity(
                        "queued-upload", token, surface_epoch, admission_id,
                        key.page, key.slot, resource_revision, install_lease, 0, rgba_bytes));
                    registry_add_locked(release_identity(
                        "bitmap-global-ref", token, surface_epoch, admission_id,
                        key.page, key.slot, resource_revision, install_lease, 0, rgba_bytes));
                    ++native_outstanding_;
                    native_outstanding_mirror_.store(
                        static_cast<int>(native_outstanding_),
                        std::memory_order_release);
                    accepted = true;
                }
            }
        }
        // JNI is deliberately outside native locks; release closes admission atomically and
        // rejected ownership never enters a native queue.
        if (!accepted) {
            env->DeleteGlobalRef(global_bitmap);
            return false;
        }
        upload_condition_.notify_one();
        return true;
    }

    bool install_prepared(
            JNIEnv* env, std::int64_t authority_generation, const TileKey& key,
            std::int64_t preparation_generation,
            std::int64_t surface_epoch,
            bool detached_install,
            std::int64_t admission_id,
            std::int64_t resource_revision, std::int64_t install_lease,
            std::int64_t rgba_bytes, int width, int height,
            std::string tile_proof_digest, jobject bitmap) {
        if (env == nullptr || key.authority <= 0 || key.page < 0 || key.slot < 0 ||
            preparation_generation <= 0 ||
            (!detached_install && surface_epoch <= 0) ||
            (detached_install && surface_epoch != 0) ||
            admission_id <= 0 || resource_revision != 1 ||
            install_lease <= 0 || rgba_bytes <= 0 || width <= 0 || height <= 0 ||
            rgba8_bytes(width, height) != rgba_bytes ||
            !is_sha256(tile_proof_digest) || bitmap == nullptr) return false;
        jobject global_bitmap = env->NewGlobalRef(bitmap);
        if (global_bitmap == nullptr) return false;
        bool accepted = false;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            const ntk::prepared_scene::Install install{
                {key.page, key.slot}, admission_id, resource_revision,
                install_lease, rgba_bytes, width, height, tile_proof_digest};
            const GpuPhase phase = gpu_phase_.load(std::memory_order_acquire);
            constexpr std::size_t native_cap = 1U;
            const bool pre_geometry = !prepared_geometry_bound_;
            const auto final_spec = slot_specs_.find(key);
            const bool protocol_slot_valid = pre_geometry
                ? prepared_bank_.find(key) == prepared_bank_.end()
                : final_spec != slot_specs_.end() &&
                    final_spec->second.width == width &&
                    final_spec->second.height == height &&
                    scene_.find(key) == scene_.end();
            const bool base_valid = !stopped_ && !authority_failed_ && preparation_open_ &&
                !preparation_admissions_closed_ &&
                authority_generation == authority_generation_ &&
                key.authority == authority_ &&
                prepared_bank_ledger_.preparationGeneration() ==
                    preparation_generation &&
                detached_install == pre_geometry &&
                (detached_install ||
                    (static_cast<std::uint64_t>(surface_epoch) == surface_epoch_ &&
                     static_cast<std::uint64_t>(surface_epoch) ==
                        admitted_surface_epoch_.load(std::memory_order_acquire))) &&
                !upload_submission_blocked_.load(std::memory_order_acquire) &&
                upload_context_alive_.load(std::memory_order_acquire) &&
                resource_worker_owns(authority_generation, key.authority) &&
                gpu_resource_worker_state_.load(std::memory_order_acquire) ==
                    GpuResourceWorkerState::PRE_STAGE_ACTIVE &&
                native_outstanding_ < native_cap && phase == GpuPhase::PRE_STAGE_GPU;
            const bool protocol_ledger_valid = base_valid && protocol_slot_valid &&
                (!pre_geometry || prepared_bank_ledger_.beginInstall(install));
            if (protocol_ledger_valid) {
                UploadCommand command;
                command.key = key;
                command.authority_generation = authority_generation;
                command.preparation_generation = preparation_generation;
                command.surface_epoch = surface_epoch;
                command.admission_id = admission_id;
                command.resource_revision = resource_revision;
                command.install_lease = install_lease;
                command.rgba_bytes = rgba_bytes;
                command.bitmap = global_bitmap;
                command.width = width;
                command.height = height;
                command.tile_proof_digest = std::move(tile_proof_digest);
                command.pre_geometry = pre_geometry;
                command.prepared_protocol = true;
                if (!pre_geometry) {
                    command.content_top = final_spec->second.content_top;
                    command.content_bottom = final_spec->second.content_bottom;
                }
                upload_commands_.push_back(std::move(command));
                const AuthorityKey token{
                    engine_generation_, authority_generation, key.authority};
                const std::int64_t resource_scope = detached_install
                    ? preparation_generation : surface_epoch;
                registry_add_locked(release_identity(
                    detached_install ? "queued-detached-upload" : "queued-upload",
                    token, resource_scope, admission_id,
                    key.page, key.slot, resource_revision, install_lease, 0, rgba_bytes));
                registry_add_locked(release_identity(
                    detached_install ? "detached-bitmap-global-ref" :
                        "bitmap-global-ref",
                    token, resource_scope, admission_id,
                    key.page, key.slot, resource_revision, install_lease, 0, rgba_bytes));
                ++native_outstanding_;
                native_outstanding_mirror_.store(
                    static_cast<int>(native_outstanding_), std::memory_order_release);
                accepted = true;
            }
        }
        if (!accepted) {
            env->DeleteGlobalRef(global_bitmap);
            return false;
        }
        upload_condition_.notify_one();
        return true;
    }

    bool close_preparation_admissions(
            std::int64_t authority_generation, std::int64_t authority) {
        std::unique_lock<std::mutex> lock(mutex_);
        if (stopped_ || authority_failed_ || !preparation_open_ ||
            !prepared_geometry_bound_ || preparation_admissions_closed_ ||
            authority_generation != authority_generation_ || authority != authority_) return false;
        // Closing admission is the linearization point. The caller never retries: accepted work
        // is allowed to finish and all shared-context fences are physically consumed in this
        // same call before the drain proof can be published.
        preparation_admissions_closed_ = true;
        preparation_drain_condition_.wait(lock, [&] {
            return stopped_ || authority_failed_.load(std::memory_order_acquire) ||
                preparation_drain_ready_locked();
        });
        if (stopped_ || authority_failed_.load(std::memory_order_acquire) ||
            !preparation_drain_ready_locked() ||
            !prepared_bank_ledger_.closeAdmissions()) return false;
        return true;
    }

    bool activate(std::int64_t authority_generation, std::int64_t authority,
                  std::int64_t stage_nonce) {
        std::lock_guard<std::mutex> lock(mutex_);
        const bool authority_failed = authority_failed_.load(std::memory_order_acquire);
        const std::int64_t staged_nonce = staged_nonce_.load(std::memory_order_acquire);
        const bool scene_sealed = scene_sealed_.load(std::memory_order_acquire);
        const bool upload_context_alive = upload_context_alive_.load(std::memory_order_acquire);
        const GpuResourceWorkerState worker_state =
            gpu_resource_worker_state_.load(std::memory_order_acquire);
        const RendererMode renderer_mode = renderer_mode_.load(std::memory_order_acquire);
        const bool identity_valid = authority > 0 && authority == authority_ &&
            authority_generation == authority_generation_;
        const bool sealed_activation_valid = stage_nonce > 0 && stage_nonce == staged_nonce &&
            scene_sealed && !upload_context_alive &&
            worker_state == GpuResourceWorkerState::RETIRED;
        if (stopped_ || authority_failed || !identity_valid || !sealed_activation_valid) {
            NTK_LOGE("activation rejected boundary=sealed-admission stopped=%d failed=%d "
                     "authority=%lld/%lld generation=%lld/%lld nonce=%lld/%lld "
                     "sealed=%d uploadContext=%d worker=%d mode=%d phase=%d",
                     stopped_ ? 1 : 0, authority_failed ? 1 : 0,
                     static_cast<long long>(authority), static_cast<long long>(authority_),
                     static_cast<long long>(authority_generation),
                     static_cast<long long>(authority_generation_),
                     static_cast<long long>(stage_nonce),
                     static_cast<long long>(staged_nonce), scene_sealed ? 1 : 0,
                     upload_context_alive ? 1 : 0, static_cast<int>(worker_state),
                     static_cast<int>(renderer_mode),
                     static_cast<int>(gpu_phase_.load(std::memory_order_acquire)));
            return false;
        }
        RendererMode expected = RendererMode::ARMED;
        if (!renderer_mode_.compare_exchange_strong(
                expected, RendererMode::ACTIVE,
                std::memory_order_acq_rel, std::memory_order_acquire)) {
            NTK_LOGE("activation rejected boundary=mode expected=%d actual=%d phase=%d "
                     "authority=%lld generation=%lld nonce=%lld",
                     static_cast<int>(RendererMode::ARMED), static_cast<int>(expected),
                     static_cast<int>(gpu_phase_.load(std::memory_order_acquire)),
                     static_cast<long long>(authority),
                     static_cast<long long>(authority_generation),
                     static_cast<long long>(stage_nonce));
            return false;
        }
        presentation_blocked_.store(false, std::memory_order_release);
        input_admission_blocked_.store(false, std::memory_order_release);
        stage_pin_active_.store(false, std::memory_order_release);
        return true;
    }

    bool commit_protection(ProtectionCommit commit) {
        if (commit.authority <= 0 || commit.surface_epoch <= 0 ||
            commit.demand_epoch < 0 || commit.basis_frame_sequence < 0 ||
            commit.basis_input_sequence < 0 ||
            (commit.direction != 1 && commit.direction != -1) ||
            !is_sha256(commit.protected_digest) ||
            !std::is_sorted(commit.protected_tile_ordinals.begin(),
                            commit.protected_tile_ordinals.end()) ||
            std::adjacent_find(commit.protected_tile_ordinals.begin(),
                               commit.protected_tile_ordinals.end()) !=
                commit.protected_tile_ordinals.end()) {
            authority_failed_.store(true, std::memory_order_release);
            gpu_phase_.store(GpuPhase::FAILED, std::memory_order_release);
            return false;
        }
        std::optional<ProtectionCommit> superseded;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            const bool ordinal_corruption = std::any_of(
                commit.protected_tile_ordinals.begin(),
                commit.protected_tile_ordinals.end(), [&](int ordinal) {
                    return ordinal < 0 ||
                        static_cast<std::size_t>(ordinal) >= expected_tile_count_;
                });
            if (ordinal_corruption) {
                authority_failed_.store(true, std::memory_order_release);
                gpu_phase_.store(GpuPhase::FAILED, std::memory_order_release);
                return false;
            }
            if (stopped_ || commit.authority != authority_ ||
                commit.authority_generation != authority_generation_ ||
                static_cast<std::uint64_t>(commit.surface_epoch) !=
                    admitted_surface_epoch_.load(std::memory_order_acquire) ||
                gpu_phase_.load(std::memory_order_acquire) !=
                    GpuPhase::PRE_STAGE_GPU ||
                upload_submission_blocked_.load(std::memory_order_acquire)) return false;
            if (pending_protection_commit_.has_value()) {
                if (commit.demand_epoch < pending_protection_commit_->demand_epoch) return false;
                superseded = std::move(pending_protection_commit_);
            }
            pending_protection_commit_ = std::move(commit);
            ++command_generation_;
        }
        if (superseded.has_value()) {
            enqueue_protection_committed(*superseded, 0, false);
        }
        render_condition_.notify_one();
        return true;
    }

    bool retire(RetireIntent intent) {
        if (intent.authority <= 0 || intent.surface_epoch <= 0 ||
            intent.policy_surface_epoch <= 0 ||
            intent.demand_epoch < 0 || intent.basis_frame_sequence < 0 ||
            intent.basis_input_sequence < 0 || intent.key.page < 0 ||
            intent.key.slot < 0 || intent.resource_revision <= 0 ||
            intent.install_lease <= 0 || intent.retire_lease <= 0 ||
            intent.rgba_bytes <= 0 || !is_sha256(intent.protected_digest) ||
            intent.key.authority != intent.authority) {
            authority_failed_.store(true, std::memory_order_release);
            gpu_phase_.store(GpuPhase::FAILED, std::memory_order_release);
            return false;
        }
        {
            std::lock_guard<std::mutex> lock(mutex_);
            constexpr std::size_t kRetireIntentCap = 2U;
            const GpuPhase phase = gpu_phase_.load(std::memory_order_acquire);
            if (stopped_ || intent.authority != authority_ ||
                intent.authority_generation != authority_generation_ ||
                static_cast<std::uint64_t>(intent.policy_surface_epoch) !=
                    admitted_surface_epoch_.load(std::memory_order_acquire) ||
                upload_submission_blocked_.load(std::memory_order_acquire) ||
                !resource_worker_owns(intent.authority_generation, intent.authority) ||
                gpu_resource_worker_state_.load(std::memory_order_acquire) !=
                    GpuResourceWorkerState::PRE_STAGE_ACTIVE ||
                retire_intents_.size() >= kRetireIntentCap ||
                phase != GpuPhase::PRE_STAGE_GPU) {
                return false;
            }
            retire_intents_.push_back(std::move(intent));
            retire_intent_depth_mirror_.store(
                static_cast<int>(retire_intents_.size()),
                std::memory_order_release);
            ++command_generation_;
        }
        render_condition_.notify_one();
        return true;
    }

    bool stage(std::int64_t authority_generation, std::int64_t authority,
               std::int64_t corridor_start,
               std::int64_t corridor_end, std::int64_t stage_nonce) {
        if (authority <= 0 || corridor_start < 0 || corridor_end <= corridor_start ||
            stage_nonce <= 0) return false;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (authority != authority_ || authority_generation != authority_generation_ ||
                corridor_start != 0 || corridor_end != content_height_ ||
                gpu_phase_.load(std::memory_order_acquire) != GpuPhase::PRE_STAGE_GPU) {
                NTK_LOGE("fatal stage request authority=%lld/%lld end=%lld/%lld phase=%d",
                         static_cast<long long>(authority),
                         static_cast<long long>(authority_),
                         static_cast<long long>(corridor_end),
                         static_cast<long long>(content_height_),
                         static_cast<int>(gpu_phase_.load(std::memory_order_acquire)));
                authority_failed_.store(true, std::memory_order_release);
                gpu_phase_.store(GpuPhase::FAILED, std::memory_order_release);
                return false;
            }
            stage_authority_ = authority;
            stage_corridor_start_ = corridor_start;
            stage_corridor_end_ = corridor_end;
            stage_nonce_ = stage_nonce;
            stage_pin_active_.store(true, std::memory_order_release);
            // STAGE closes the current authority's mutation admission atomically. Work already
            // accepted by PRE_STAGE drains to the render context; no later upload/retire may
            // race the immutable full-scene seal.
            upload_submission_blocked_.store(true, std::memory_order_release);
            stage_requested_ = true;
            render_requested_ = true;
            ++command_generation_;
        }
        render_condition_.notify_one();
        return true;
    }

    std::uint64_t touch(std::int64_t authority_generation, std::int64_t authority,
                        int action, std::int64_t event_time_ns, std::int64_t main_ingress_ns,
                        float x, float y, int pointer_id) {
        constexpr int action_down = 0;
        constexpr int action_up = 1;
        constexpr int action_move = 2;
        constexpr int action_cancel = 3;
        if (input_admission_blocked_.load(std::memory_order_acquire) ||
            renderer_mode_.load(std::memory_order_acquire) != RendererMode::ACTIVE ||
            active_authority_generation_.load(std::memory_order_acquire) !=
                authority_generation ||
            active_authority_.load(std::memory_order_acquire) != authority) return 0;
        if (event_time_ns <= 0 || main_ingress_ns <= 0 ||
            !std::isfinite(x) || !std::isfinite(y) || pointer_id < 0 ||
            (action != action_down && action != action_up && action != action_move &&
             action != action_cancel)) return 0;
        const std::int64_t previous_event_ns = last_ingress_event_time_ns_.load(
            std::memory_order_acquire);
        const std::int64_t previous_main_ns = last_ingress_main_time_ns_.load(
            std::memory_order_acquire);
        if (event_time_ns < previous_event_ns || main_ingress_ns < previous_main_ns) return 0;
        // Physical input is delivered by the Android main thread. Atomic publication keeps the
        // rejection proof race-free for diagnostics without adding a mutex to the 12 ms path.
        last_ingress_event_time_ns_.store(event_time_ns, std::memory_order_release);
        last_ingress_main_time_ns_.store(main_ingress_ns, std::memory_order_release);
        std::int64_t empty_ingress = 0;
        first_main_ingress_ns_.compare_exchange_strong(
            empty_ingress, main_ingress_ns, std::memory_order_release, std::memory_order_relaxed);
        InputSample sample;
        sample.action = action;
        sample.event_time_ns = event_time_ns;
        sample.main_ingress_ns = main_ingress_ns;
        sample.input_sequence = next_input_sequence_.fetch_add(1, std::memory_order_relaxed) + 1;
        sample.x = x;
        sample.y = y;
        sample.pointer_id = pointer_id;
        if (action == action_down) {
            bool ingress_expected = false;
            const GpuPhase ingress_phase = gpu_phase_.load(std::memory_order_acquire);
            if (!ingress_pointer_down_.compare_exchange_strong(
                    ingress_expected, true,
                    std::memory_order_acq_rel, std::memory_order_acquire) ||
                (ingress_phase != GpuPhase::INPUT_ARMED &&
                 ingress_phase != GpuPhase::GESTURE_ACTIVE)) {
                NTK_LOGE("fatal overlapping ACTION_DOWN ingress=%d phase=%d",
                         ingress_expected ? 1 : 0, static_cast<int>(ingress_phase));
                authority_failed_.store(true, std::memory_order_release);
                gpu_phase_.store(GpuPhase::FAILED, std::memory_order_release);
                ingress_pointer_down_.store(false, std::memory_order_release);
                return 0;
            }
            const std::int64_t stage_ns = stage_latch_ns_.load(std::memory_order_acquire);
            if (renderer_mode_.load(std::memory_order_acquire) != RendererMode::ACTIVE ||
                stage_ns <= 0 || stage_ns > main_ingress_ns) {
                NTK_LOGE("fatal ACTION_DOWN activation mode=%d stage=%lld ingress=%lld",
                         static_cast<int>(renderer_mode_.load(std::memory_order_acquire)),
                         static_cast<long long>(stage_ns), static_cast<long long>(main_ingress_ns));
                authority_failed_.store(true, std::memory_order_release);
                gpu_phase_.store(GpuPhase::FAILED, std::memory_order_release);
                ingress_pointer_down_.store(false, std::memory_order_release);
                return 0;
            }
            if (upload_context_alive_.load(std::memory_order_acquire) ||
                gpu_resource_worker_state_.load(std::memory_order_acquire) !=
                    GpuResourceWorkerState::RETIRED ||
                !scene_sealed_.load(std::memory_order_acquire) ||
                sealed_scene_version_ != scene_version_ ||
                sealed_resource_submit_serial_.load(std::memory_order_acquire) !=
                    resource_submit_serial_.load(std::memory_order_acquire)) {
                NTK_LOGE("fatal ACTION_DOWN mutable resource state context=%d worker=%d "
                         "scene=%lld/%lld serial=%llu/%llu",
                         upload_context_alive_.load(std::memory_order_acquire) ? 1 : 0,
                         static_cast<int>(gpu_resource_worker_state_.load(
                             std::memory_order_acquire)),
                         static_cast<long long>(sealed_scene_version_),
                         static_cast<long long>(scene_version_),
                         static_cast<unsigned long long>(resource_submit_serial_.load(
                             std::memory_order_acquire)),
                         static_cast<unsigned long long>(sealed_resource_submit_serial_.load(
                             std::memory_order_acquire)));
                authority_failed_.store(true, std::memory_order_release);
                gpu_phase_.store(GpuPhase::FAILED, std::memory_order_release);
                ingress_pointer_down_.store(false, std::memory_order_release);
                return 0;
            }
            std::int64_t empty_down = 0;
            first_down_ingress_ns_.compare_exchange_strong(
                empty_down, main_ingress_ns,
                std::memory_order_release, std::memory_order_relaxed);
            sample.gesture_generation = ingress_gesture_generation_.fetch_add(
                1, std::memory_order_acq_rel) + 1;
        } else {
            const GpuPhase ingress_phase = gpu_phase_.load(std::memory_order_acquire);
            if (!ingress_pointer_down_.load(std::memory_order_acquire) ||
                (ingress_phase != GpuPhase::INPUT_ARMED &&
                 ingress_phase != GpuPhase::GESTURE_ACTIVE)) {
                NTK_LOGE("fatal non-DOWN input ingress=%d gpu phase=%d action=%d",
                         ingress_pointer_down_.load(std::memory_order_acquire) ? 1 : 0,
                         static_cast<int>(ingress_phase), action);
                authority_failed_.store(true, std::memory_order_release);
                gpu_phase_.store(GpuPhase::FAILED, std::memory_order_release);
                return 0;
            }
            sample.gesture_generation = ingress_gesture_generation_.load(
                std::memory_order_acquire);
        }
        if (action == action_move) {
            {
                std::lock_guard<std::mutex> lock(move_mailbox_mutex_);
                move_mailbox_ = sample;
                move_mailbox_.receipt_time_ns = monotonic_now_ns();
                move_mailbox_sequence_ = sample.input_sequence;
                move_mailbox_writes_.fetch_add(1, std::memory_order_relaxed);
            }
            // MOVE is a latest-value mailbox, but publishing its sequence is still a render
            // command. It may race draw/swap/latch work, so a sampled sleeping flag cannot own
            // this wake without creating a lost-command window.
            {
                std::lock_guard<std::mutex> lock(mutex_);
                render_requested_ = true;
                ++command_generation_;
            }
            render_condition_.notify_one();
            const std::uint64_t packed_sequence = sample.input_sequence & 0xffffffffULL;
            const std::uint64_t packed_gesture = sample.gesture_generation & 0xffffULL;
            return (1ULL << 63U) | (1ULL << 56U) | (packed_gesture << 32U) |
                packed_sequence;
        }
        if (action == action_up || action == action_cancel) {
            std::lock_guard<std::mutex> mailbox_lock(move_mailbox_mutex_);
            if (move_mailbox_sequence_ > 0 &&
                move_mailbox_.gesture_generation == sample.gesture_generation) {
                sample.terminal_move.valid = true;
                sample.terminal_move.event_time_ns = move_mailbox_.event_time_ns;
                sample.terminal_move.main_ingress_ns = move_mailbox_.main_ingress_ns;
                sample.terminal_move.receipt_time_ns = move_mailbox_.receipt_time_ns;
                sample.terminal_move.input_sequence = move_mailbox_.input_sequence;
                sample.terminal_move.gesture_generation = move_mailbox_.gesture_generation;
                sample.terminal_move.x = move_mailbox_.x;
                sample.terminal_move.y = move_mailbox_.y;
                sample.terminal_move.pointer_id = move_mailbox_.pointer_id;
            }
        }
        std::size_t control_backlog = 0;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            const GpuPhase enqueue_phase = gpu_phase_.load(std::memory_order_acquire);
            if (authority_failed_.load(std::memory_order_acquire) ||
                (enqueue_phase != GpuPhase::INPUT_ARMED &&
                 enqueue_phase != GpuPhase::GESTURE_ACTIVE)) {
                NTK_LOGE("fatal input enqueue phase=%d action=%d",
                         static_cast<int>(enqueue_phase), action);
                authority_failed_.store(true, std::memory_order_release);
                gpu_phase_.store(GpuPhase::FAILED, std::memory_order_release);
                if (action == action_down || action == action_up || action == action_cancel) {
                    ingress_pointer_down_.store(false, std::memory_order_release);
                }
                return 0;
            }
            if (!ntk::scheduler::FixedDepthOneScheduler::canAcceptControl(
                    input_control_commands_.size()) ||
                ((action == action_up || action == action_cancel) &&
                 !fixed_scheduler_.appendAcceptedTerminal(
                     sample.gesture_generation, sample.input_sequence))) {
                NTK_LOGE("fatal bounded input admission action=%d depth=%zu",
                         action, input_control_commands_.size());
                input_admission_blocked_.store(true, std::memory_order_release);
                authority_failed_.store(true, std::memory_order_release);
                gpu_phase_.store(GpuPhase::FAILED, std::memory_order_release);
                if (action == action_down || action == action_up ||
                    action == action_cancel) {
                    ingress_pointer_down_.store(false, std::memory_order_release);
                }
                return 0;
            }
            input_control_commands_.push_back(sample);
            input_control_commands_.back().receipt_time_ns = monotonic_now_ns();
            control_backlog = input_control_commands_.size();
            int observed = control_backlog_max_.load(std::memory_order_relaxed);
            while (observed < static_cast<int>(control_backlog) &&
                   !control_backlog_max_.compare_exchange_weak(
                       observed, static_cast<int>(control_backlog),
                       std::memory_order_relaxed)) {}
            // DOWN changes gesture authority/telemetry but not a pixel. Wake the owner to apply
            // it without manufacturing an identical buffer; its pending input proof is folded
            // into the first real MOVE or the mandatory terminal frame. UP/CANCEL always own a
            // terminal submission, while MOVE publishes through its mailbox path above.
            if (action != action_down) render_requested_ = true;
            ++command_generation_;
        }
        render_condition_.notify_one();
        if (action == action_up || action == action_cancel) {
            ingress_pointer_down_.store(false, std::memory_order_release);
        }
        const std::uint64_t packed_sequence = sample.input_sequence & 0xffffffffULL;
        const std::uint64_t packed_gesture = sample.gesture_generation & 0xffffULL;
        const std::uint64_t packed_backlog = std::min<std::size_t>(control_backlog, 255U);
        return (1ULL << 63U) | (packed_backlog << 48U) | (packed_gesture << 32U) |
            packed_sequence;
    }

    void reset_input_telemetry() {
        first_main_ingress_ns_.store(0, std::memory_order_release);
        latest_successful_swap_input_event_ns_.store(0, std::memory_order_release);
        latest_delivered_latched_input_event_ns_.store(0, std::memory_order_release);
    }

    std::int64_t first_main_ingress_ns() const {
        return first_main_ingress_ns_.load(std::memory_order_acquire);
    }

    std::int64_t latest_successful_swap_input_event_ns() const {
        return latest_successful_swap_input_event_ns_.load(std::memory_order_acquire);
    }

    std::int64_t latest_delivered_latched_input_event_ns() const {
        return latest_delivered_latched_input_event_ns_.load(std::memory_order_acquire);
    }

    std::int64_t pre_submit_viewport_gap() const {
        return static_cast<std::int64_t>(
            pre_submit_viewport_gap_.load(std::memory_order_acquire));
    }

    bool release_authority(std::int64_t engine_generation,
                           std::int64_t authority_generation,
                           std::int64_t authority,
                           std::int64_t reducer_surface_epoch,
                           std::int64_t release_nonce) {
        if (engine_generation <= 0 || authority_generation <= 0 || authority <= 0 ||
            reducer_surface_epoch < 0 || release_nonce <= 0 ||
            engine_generation != engine_generation_) return false;
        const AuthorityKey key{engine_generation, authority_generation, authority};
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (stopped_ || engine_failed_.load(std::memory_order_acquire) ||
                released_authorities_.find(key) != released_authorities_.end() ||
                pending_release_claims_.find(key) != pending_release_claims_.end()) return false;
            ReleaseClaim claim{key, reducer_surface_epoch, release_nonce};
            const auto existing = release_trackers_.find(key);
            if (existing != release_trackers_.end()) {
                auto& tracker = *existing->second;
                if (tracker.lifecycle != AuthorityLifecycle::RELEASING_UNCLAIMED ||
                    tracker.claim.has_value()) return false;
                tracker.claim = claim;
                tracker.lifecycle = AuthorityLifecycle::RELEASING_CLAIMED;
                tracker.release_claim_serial = next_release_protocol_serial_locked();
                if (tracker.release_claim_serial <= tracker.admission_close_serial) {
                    tracker.lifecycle = AuthorityLifecycle::FAILED;
                    return false;
                }
            } else {
                if (authority_ != authority || authority_generation_ != authority_generation) {
                    return false;
                }
                // Admission closes synchronously at the JNI boundary. Render ownership is
                // transferred on its lane without waiting for any GPU fence.
                claim.admission_close_serial = next_release_protocol_serial_locked();
                if (claim.admission_close_serial <= 0) return false;
                active_authority_generation_.store(0, std::memory_order_release);
                active_authority_.store(0, std::memory_order_release);
                upload_submission_blocked_.store(true, std::memory_order_release);
                input_admission_blocked_.store(true, std::memory_order_release);
                pending_release_claims_.emplace(key, claim);
                render_requested_ = true;
                ++command_generation_;
            }
        }
        render_condition_.notify_one();
        upload_condition_.notify_one();
        return true;
    }

    std::array<std::int64_t, 21> debug_lifecycle_counters() {
        return {{
            engine_generation_,
            g_engine_create_count.load(std::memory_order_acquire),
            g_engine_destroy_count.load(std::memory_order_acquire),
            egl_initialize_count_.load(std::memory_order_acquire),
            egl_context_create_count_.load(std::memory_order_acquire),
            bind_apply_count_.load(std::memory_order_acquire),
            release_ack_count_.load(std::memory_order_acquire),
            // The JNI boundary substitutes the opaque registry ID.  Never expose the
            // renderer address, even through test-only lifecycle evidence.
            0,
            context_resources_valid_.load(std::memory_order_acquire) ? 1 : 0,
            engine_failed_.load(std::memory_order_acquire) ? 1 : 0,
            static_cast<std::int64_t>(
                resource_worker_generation_.load(std::memory_order_acquire)),
            static_cast<std::int64_t>(
                resource_worker_create_count_.load(std::memory_order_acquire)),
            static_cast<std::int64_t>(
                resource_worker_destroy_count_.load(std::memory_order_acquire)),
            active_resource_worker_count_.load(std::memory_order_acquire),
            resource_worker_owner_authority_generation_.load(
                std::memory_order_acquire),
            resource_worker_owner_authority_.load(std::memory_order_acquire),
            resource_worker_context_created_ns_.load(std::memory_order_acquire),
            resource_worker_ready_ns_.load(std::memory_order_acquire),
            resource_worker_context_destroyed_ns_.load(std::memory_order_acquire),
            resource_worker_thread_joined_ns_.load(std::memory_order_acquire),
            bind_committed_ns_.load(std::memory_order_acquire),
        }};
    }

    std::array<std::int64_t, 28> debug_startup_lifecycle() const {
        return {{
            engine_generation_,
            native_create_begin_ns_,
            native_create_end_ns_.load(std::memory_order_acquire),
            swappy_init_begin_ns_,
            swappy_init_end_ns_,
            egl_initialize_begin_ns_.load(std::memory_order_acquire),
            egl_initialize_end_ns_.load(std::memory_order_acquire),
            render_context_ready_ns_.load(std::memory_order_acquire),
            upload_context_ready_ns_.load(std::memory_order_acquire),
            render_pbuffer_ready_ns_.load(std::memory_order_acquire),
            upload_pbuffer_ready_ns_.load(std::memory_order_acquire),
            program_ready_ns_.load(std::memory_order_acquire),
            egl_ready_ns_.load(std::memory_order_acquire),
            detached_warm_ready_ns_.load(std::memory_order_acquire),
            attach_lease_queued_ns_.load(std::memory_order_acquire),
            attach_lease_claimed_ns_.load(std::memory_order_acquire),
            swappy_window_begin_ns_.load(std::memory_order_acquire),
            swappy_window_end_ns_.load(std::memory_order_acquire),
            surface_control_attach_begin_ns_.load(std::memory_order_acquire),
            surface_control_attach_end_ns_.load(std::memory_order_acquire),
            attach_ready_ns_.load(std::memory_order_acquire),
            attach_published_ns_.load(std::memory_order_acquire),
            first_backend_prepare_ns_.load(std::memory_order_acquire),
            first_transaction_apply_ns_.load(std::memory_order_acquire),
            stage_latch_ns_.load(std::memory_order_acquire),
            surface_control_attach_count_.load(std::memory_order_acquire),
            window_frame_id_count_.load(std::memory_order_acquire),
            window_swap_count_.load(std::memory_order_acquire),
        }};
    }

    std::array<std::int64_t, 9> debug_scheduler_counters() const {
        const auto counters = fixed_scheduler_.counters();
        return {{
            static_cast<std::int64_t>(
                counters.max_logical_producer_depth),
            static_cast<std::int64_t>(counters.max_successor_depth),
            static_cast<std::int64_t>(
                counters.max_swappy_reservation_depth),
            static_cast<std::int64_t>(
                counters.max_backend_prepared_depth),
            static_cast<std::int64_t>(
                counters.spurious_commit_attempt_count),
            static_cast<std::int64_t>(counters.terminal_accepted_count),
            static_cast<std::int64_t>(counters.terminal_submitted_count),
            static_cast<std::int64_t>(counters.terminal_joined_count),
            static_cast<std::int64_t>(counters.terminal_lost_count),
        }};
    }

    std::array<std::int64_t, 10> debug_authority_inventory(
            std::int64_t authority_generation, std::int64_t authority) {
        std::lock_guard<std::mutex> lock(mutex_);
        const AuthorityKey key{engine_generation_, authority_generation, authority};
        const auto found = release_trackers_.find(key);
        if (found == release_trackers_.end()) return {{-1,0,0,0,0,0,0,0,0,0}};
        const auto& tracker = *found->second;
        return {{
            static_cast<std::int64_t>(tracker.lifecycle),
            static_cast<std::int64_t>(tracker.captured_resources.size()),
            static_cast<std::int64_t>(tracker.scene.size()),
            static_cast<std::int64_t>(tracker.queued_uploads.size()),
            static_cast<std::int64_t>(tracker.ready_tiles.size()),
            static_cast<std::int64_t>(tracker.resource_deletes.size()),
            static_cast<std::int64_t>(tracker.preallocated_textures.size() +
                                      tracker.prepared_bank.size()),
            (tracker.in_flight_upload ? 1 : 0) +
                (tracker.in_flight_resource_delete ? 1 : 0),
            tracker.physical_complete ? 1 : 0,
            tracker.ack_enqueued ? 1 : 0,
        }};
    }

    void request_render() {
        {
            std::lock_guard<std::mutex> lock(mutex_);
            render_requested_ = true;
            ++command_generation_;
        }
        render_condition_.notify_one();
    }

    bool detach(std::uint64_t surface_epoch) {
        std::unique_lock<std::mutex> lock(mutex_);
        if (surface_epoch == 0 || context_loss_pending_ ||
            !attach_request_.has_value() ||
            attach_request_->surface_epoch != surface_epoch ||
            (attach_request_->state != AttachState::READY &&
             attach_request_->state != AttachState::PUBLISHED &&
             attach_request_->state != AttachState::LOSS_PENDING)) {
            return false;
        }
        input_admission_blocked_.store(true, std::memory_order_release);
        presentation_blocked_.store(true, std::memory_order_release);
        admitted_surface_epoch_.store(0, std::memory_order_release);
        const std::uint64_t requested_generation = ++command_generation_;
        detach_requested_ = true;
        attach_condition_.notify_all();
        render_condition_.notify_one();
        detached_condition_.wait(lock, [&] {
            return stopped_ || detached_generation_ >= requested_generation;
        });
        const bool reusable =
            context_resources_valid_.load(std::memory_order_acquire);
        ANativeWindow* released = nullptr;
        if (attach_request_.has_value() &&
            attach_request_->surface_epoch == surface_epoch) {
            attach_request_->state = AttachState::TERMINAL;
            attach_request_->success = false;
            released = attach_request_->window;
            attach_request_->window = nullptr;
            attach_request_.reset();
        }
        lock.unlock();
        if (released != nullptr) {
            ANativeWindow_release(released);
            g_renderer_owned_surface_lease_count.fetch_sub(
                1, std::memory_order_acq_rel);
        }
        return reusable;
    }

    bool has_pending_context_loss(std::uint64_t surface_epoch) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!context_loss_pending_ &&
            !context_resources_valid_.load(std::memory_order_acquire)) {
            context_loss_pending_ = true;
            context_loss_surface_epoch_ = surface_epoch;
            admitted_surface_epoch_.store(0, std::memory_order_release);
            upload_submission_blocked_.store(true, std::memory_order_release);
            block_input_and_presentation();
        }
        return context_loss_pending_ && surface_epoch > 0 &&
            surface_epoch == context_loss_surface_epoch_;
    }

    std::unique_ptr<RetiredBackendProofStore> retire_context_lost_on_detach(
            std::uint64_t surface_epoch,
            const RetiredAuthoritySelection& expected_authorities) {
        EnvScope scope(java_vm_);
        {
            std::unique_lock<std::mutex> lock(mutex_);
            if (!context_loss_pending_ || surface_epoch == 0 ||
                surface_epoch != context_loss_surface_epoch_ || stopped_) {
                return nullptr;
            }
            block_input_and_presentation();
            const std::uint64_t requested_generation = ++command_generation_;
            detach_requested_ = true;
            attach_condition_.notify_all();
            render_condition_.notify_all();
            detached_condition_.wait(lock, [&] {
                return stopped_ || detached_generation_ >= requested_generation;
            });
            if (stopped_ || detached_generation_ < requested_generation) return nullptr;

            // PRE_STAGE sealing deliberately retires the resource worker before authoritative
            // input begins. The context-loss detach has now moved the current scene into its
            // exact release tracker, but an already-retired worker cannot close that CPU ledger.
            // Keep the render owner alive (so destroy_egl cannot clear the tracker), drop the
            // mutex, and finish the same context-loss accounting without issuing any GL call.
            // A still-active worker retains sole ownership of this work and drains on exit.
            const bool drain_retired_worker_release_ledger =
                !context_resources_valid_.load(std::memory_order_acquire) &&
                upload_exited_ && !upload_thread_.joinable() &&
                gpu_resource_worker_state_.load(std::memory_order_acquire) ==
                    GpuResourceWorkerState::RETIRED;
            if (drain_retired_worker_release_ledger) {
                lock.unlock();
                while (process_release_tracker_once(scope.get())) {
                }
                lock.lock();
                if (stopped_ || detached_generation_ < requested_generation) return nullptr;
            }
            stopped_ = true;
            ++command_generation_;
        }
        render_condition_.notify_all();
        upload_condition_.notify_all();
        upload_start_condition_.notify_all();
        attach_condition_.notify_all();
        if (render_thread_.joinable()) render_thread_.join();
        if (upload_thread_.joinable()) {
            upload_thread_.join();
            record_resource_worker_thread_joined();
        }

        // The render and upload producers are gone. Uninject with the exact tracer identity,
        // release the backend-scoped lease, then make the feedback barrier terminal.
        if (swappy_ready_ && !swappy_lifetime_released_) {
            if (swappy_tracer_injected_) {
                SwappyGL_uninjectTracer(&swappy_tracer_);
                swappy_tracer_injected_ = false;
            }
            release_swappy();
            swappy_lifetime_released_ = true;
        }
        flush_feedback();
        const std::uint64_t terminal_feedback_barrier = feedback_barrier_requested_;
        {
            std::lock_guard<std::mutex> lock(feedback_mutex_);
            feedback_exit_requested_ = true;
        }
        feedback_ready_.notify_all();
        feedback_space_.notify_all();
        if (feedback_thread_.joinable()) feedback_thread_.join();

        if (scope.get() != nullptr && callback_ != nullptr) {
            scope.get()->DeleteGlobalRef(callback_);
        }
        callback_ = nullptr;
        on_tile_resident_ = nullptr;
        on_prepared_tile_resident_ = nullptr;
        on_protection_committed_ = nullptr;
        on_retire_result_ = nullptr;
        on_tile_freed_ = nullptr;
        on_pre_submit_viewport_gap_ = nullptr;
        on_frame_evidence_v11_ = nullptr;
        on_stage_latched_v2_ = nullptr;
        on_authority_released_ = nullptr;
        on_authority_release_dispatchable_ = nullptr;

        auto retired = std::make_unique<RetiredBackendProofStore>();
        retired->engine_generation = engine_generation_;
        retired->surface_epoch = surface_epoch;
        retired->terminal_feedback_barrier = terminal_feedback_barrier;
        {
            std::unique_lock<std::mutex> lock(mutex_);
            ANativeWindow* released_surface_window = nullptr;
            if (attach_request_.has_value()) {
                attach_request_->state = AttachState::TERMINAL;
                attach_request_->success = false;
                released_surface_window = attach_request_->window;
                attach_request_->window = nullptr;
                attach_request_.reset();
            }
            if (released_surface_window != nullptr) {
                lock.unlock();
                ANativeWindow_release(released_surface_window);
                g_renderer_owned_surface_lease_count.fetch_sub(
                    1, std::memory_order_acq_rel);
                lock.lock();
            }
            const bool backend_owners_zero = render_exited_ && upload_exited_ &&
                display_ == EGL_NO_DISPLAY && render_context_ == EGL_NO_CONTEXT &&
                upload_context_ == EGL_NO_CONTEXT && render_pbuffer_ == EGL_NO_SURFACE &&
                upload_pbuffer_ == EGL_NO_SURFACE && !present_backend_attached_ &&
                !attach_request_.has_value() && !render_thread_.joinable() &&
                !upload_thread_.joinable() && !feedback_thread_.joinable() &&
                callback_ == nullptr && swappy_lifetime_released_;
            if (!backend_owners_zero || in_flight_upload_.has_value() ||
                in_flight_resource_delete_.has_value() || !upload_commands_.empty() ||
                native_outstanding_ != 0) {
                NTK_LOGE("context-loss detach retained backend ownership");
                return nullptr;
            }
            for (const auto& entry : release_trackers_) {
                const auto& tracker = *entry.second;
                if (!(tracker.token.key == entry.first)) {
                    NTK_LOGE("context-loss detach authority tracker key mismatch");
                    return nullptr;
                }
                const auto selection = classify_retired_tracker_for_selection(
                    expected_authorities, entry.first, tracker.lifecycle);
                if (selection ==
                    RetiredTrackerSelection::EXCLUDE_HISTORICAL_RELEASED) {
                    continue;
                }
                if (selection != RetiredTrackerSelection::INCLUDE) {
                    NTK_LOGE(
                        "context-loss detach found unrepresented live authority generation=%lld authority=%lld",
                        static_cast<long long>(entry.first.authority_generation),
                        static_cast<long long>(entry.first.authority));
                    return nullptr;
                }
                if (!tracker.physical_complete ||
                    tracker.lifecycle == AuthorityLifecycle::FAILED ||
                    tracker.pending_native_callbacks != 0 || tracker.outstanding_publications != 0) {
                    NTK_LOGE("context-loss detach retained authority resources generation=%lld authority=%lld",
                             static_cast<long long>(entry.first.authority_generation),
                             static_cast<long long>(entry.first.authority));
                    return nullptr;
                }
                FrozenAuthorityReleaseProof proof;
                proof.token = tracker.token;
                proof.lifecycle = tracker.lifecycle;
                proof.claim = tracker.claim;
                proof.frozen_ack.claim = tracker.claim.value_or(ReleaseClaim{tracker.token.key, 0, 0});
                const bool already_released =
                    tracker.lifecycle == AuthorityLifecycle::RELEASED;
                proof.frozen_ack.disposition = already_released
                    ? PhysicalReleaseDisposition::EXPLICIT_DELETE
                    : PhysicalReleaseDisposition::CONTEXT_LOST;
                proof.frozen_ack.admission_close_serial = tracker.admission_close_serial;
                proof.frozen_ack.release_claim_serial = tracker.release_claim_serial;
                proof.frozen_ack.resource_barrier_serial = tracker.resource_barrier_serial;
                proof.frozen_ack.resource_completion_watermark = tracker.resource_completion_watermark;
                proof.frozen_ack.feedback_barrier_serial = already_released
                    ? tracker.feedback_barrier_serial
                    : next_release_protocol_serial_locked();
                proof.frozen_ack.captured_resource_count = static_cast<int>(
                    tracker.captured_resources.size());
                proof.frozen_ack.captured_rgba_bytes = tracker.captured_rgba_bytes;
                proof.frozen_ack.captured_resource_digest = tracker.captured_resource_digest;
                proof.frozen_ack.released_resource_count = static_cast<int>(
                    tracker.released_resources.size());
                proof.frozen_ack.released_rgba_bytes = tracker.released_rgba_bytes;
                proof.frozen_ack.released_resource_digest = tracker.released_resource_digest;
                proof.frozen_ack.deleted_texture_count = tracker.deleted_texture_count;
                proof.frozen_ack.deleted_fence_count = tracker.deleted_fence_count;
                proof.frozen_ack.released_bitmap_global_ref_count =
                    tracker.released_bitmap_global_ref_count;
                proof.frozen_ack.drained_upload_count = tracker.drained_upload_count;
                proof.frozen_ack.drained_retire_count = tracker.drained_retire_count;
                proof.frozen_ack.context_reusable = already_released;
                proof.frozen_ack.success =
                    proof.frozen_ack.admission_close_serial > 0 &&
                    proof.frozen_ack.resource_barrier_serial >
                        proof.frozen_ack.admission_close_serial &&
                    proof.frozen_ack.resource_completion_watermark >
                        proof.frozen_ack.resource_barrier_serial &&
                    proof.frozen_ack.feedback_barrier_serial >
                        proof.frozen_ack.resource_completion_watermark &&
                    (!tracker.claim.has_value() ||
                        proof.frozen_ack.release_claim_serial >
                            proof.frozen_ack.admission_close_serial) &&
                    proof.frozen_ack.captured_resource_count ==
                        proof.frozen_ack.released_resource_count &&
                    proof.frozen_ack.captured_rgba_bytes ==
                        proof.frozen_ack.released_rgba_bytes &&
                    proof.frozen_ack.captured_resource_digest ==
                        proof.frozen_ack.released_resource_digest;
                if (!proof.frozen_ack.success) return nullptr;
                retired->authority_proofs.emplace(entry.first, std::move(proof));
            }
            if (retired->authority_proofs.size() != expected_authorities.keys.size()) {
                NTK_LOGE(
                    "context-loss detach exact authority set mismatch expected=%zu native=%zu",
                    expected_authorities.keys.size(), retired->authority_proofs.size());
                return nullptr;
            }
            retired->release_protocol_serial_watermark = release_protocol_serial_;
        }
        retired->retired_authority_digest = retired_authority_digest(
            retired->authority_proofs);
        if (retired->retired_authority_digest !=
            expected_authorities.full_token_digest) {
            NTK_LOGE("context-loss detach exact authority digest mismatch");
            return nullptr;
        }
        retired->backend_retirement_serial =
            g_backend_retirement_serial.fetch_add(1, std::memory_order_acq_rel) + 1;
        retired->backend_retired_nanos = monotonic_now_ns();
        for (auto& entry : retired->authority_proofs) {
            auto& ack = entry.second.frozen_ack;
            ack.backend_retirement_serial = retired->backend_retirement_serial;
            ack.backend_retired_nanos = retired->backend_retired_nanos;
            ack.completed_nanos = retired->backend_retired_nanos;
        }
        return retired;
    }

private:
    static constexpr std::size_t kTraceRingSize = 4096;
    static constexpr std::size_t kFeedbackRingSize = 8192;
    static constexpr std::size_t kFrameFeedbackRingSize = 4096;
    static constexpr std::size_t kFixedRetirementEventRingSize = 64;
    // NORMAL is a priority pump, not a queue flush.  Eight events cover the
    // complete callback/fence fan-out of one applied frame while keeping a
    // newly published JOIN_OPEN observable between every cleanup transition.
    static constexpr std::size_t kNormalPresentCleanupBudget = 8;
    // A NORMAL pump may have to dequeue cleanup records to reach an OnCommit
    // record that opens the owned successor. Keep those records in a fixed
    // render-thread ring until the successor has applied (or lifecycle forces
    // a drain); never allocate or discard callback ownership on this lane.
    static constexpr std::size_t kDeferredPresentCleanupCapacity = 64;
    static_assert(kNormalPresentCleanupBudget <=
        kDeferredPresentCleanupCapacity);
    struct DeferredPresentCleanupRecord {
        ntk::present::FixedPresentEvent event{};
        // Cleanup is blocked only by the exact successor that owned a closed
        // opportunity when this event was dequeued. A later successor must
        // not inherit that block and starve the bounded backend ledgers.
        std::uint64_t blockedWorkGeneration = 0;
        // A non-terminal frame's OnComplete is retained until one strictly
        // newer frame has actually submitted. This proves real callback
        // overlap without retaining terminal/stage cleanup indefinitely.
        bool waitForNewerSubmission = false;
    };
    // Once this reserve is entered, authority fails immediately. The already committed frame
    // and every bounded (100 ms) latch record that was submitted before failure still fit and
    // can be drained without overwriting evidence or blocking the render owner.
    static constexpr std::size_t kFrameFeedbackEmergencyReserve = 64;
    static_assert(kFrameFeedbackEmergencyReserve < kFrameFeedbackRingSize);

    // mutex_ is the sole authority for this engine-local release event domain.
    std::int64_t next_release_protocol_serial_locked() {
        return next_release_protocol_serial(release_protocol_serial_);
    }

    AuthorityKey current_authority_key() const {
        return AuthorityKey{engine_generation_, authority_generation_, authority_};
    }

    void block_input_and_presentation() {
        // Input admission and presentation ownership are separate state machines. Fatal,
        // release, disarm, detach, and context-loss transitions close both atomically from the
        // observer's perspective (each consumer treats either closed gate as terminal).
        input_admission_blocked_.store(true, std::memory_order_release);
        presentation_blocked_.store(true, std::memory_order_release);
    }

    void clear_fixed_opportunity_ownership() noexcept {
        fixed_opportunity_gate_.cancelArmedReservation();
        armed_fixed_work_generation_.store(0, std::memory_order_release);
        armed_fixed_reservation_sequence_.store(0, std::memory_order_release);
    }

    void publish_terminal_progress() {
        bool notify = false;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            ++terminal_progress_sequence_;
            if (deferred_lifecycle_.active &&
                deferred_lifecycle_.last_rearmed_progress !=
                    terminal_progress_sequence_) {
                deferred_lifecycle_.last_rearmed_progress =
                    terminal_progress_sequence_;
                render_requested_ = true;
                ++command_generation_;
                notify = true;
            }
        }
        if (notify) render_condition_.notify_one();
    }

    bool current_authority_has_pending_release_locked() const {
        return authority_ > 0 && authority_generation_ > 0 &&
            pending_release_claims_.find(current_authority_key()) !=
                pending_release_claims_.end();
    }

    bool abort_prepared_frame_for_lifecycle() {
        if (context_resources_valid_.load(std::memory_order_acquire) &&
            fixed_scheduler_.hasUnjoinedTerminalObligation()) {
            NTK_LOGE("fatal normal lifecycle abort with unjoined terminal state=%d",
                     static_cast<int>(head_frame_state_));
            fixed_scheduler_.noteOpportunityProtocolFatal();
            return false;
        }
        if (!prepared_frame_work_.has_value()) {
            fixed_scheduler_.discardProducerWork();
            if (head_frame_state_ != HeadFrameState::FAILED) {
                head_frame_state_ = HeadFrameState::EMPTY;
            }
            reserved_frame_id_ = 0;
            reserved_frame_id_work_generation_ = 0;
            clear_fixed_opportunity_ownership();
            return true;
        }
        const std::uint64_t work_generation =
            prepared_frame_work_->work_generation;
        if (head_frame_state_ == HeadFrameState::PHASE_COMMITTING) {
            NTK_LOGE("fatal lifecycle close during one-shot phase commit work=%llu state=%d",
                     static_cast<unsigned long long>(work_generation),
                     static_cast<int>(head_frame_state_));
            return false;
        }
        bool swappy_aborted = true;
        bool backend_aborted = true;
        const auto abortActions =
            ntk::scheduler::abortOwnershipActions(head_frame_state_);
        if (abortActions.abort_swappy_reservation) {
            swappy_aborted =
                SwappyGL_abortFixedReservationForNtk(work_generation);
            if (swappy_aborted) clear_fixed_opportunity_ownership();
        }
        if (abortActions.abort_external_claim) {
            swappy_aborted =
                prepared_frame_work_->external_claim.claimToken != 0 &&
                SwappyGL_abortExternalFixedClaimForNtk(
                    prepared_frame_work_->external_claim.claimToken);
            if (swappy_aborted) clear_fixed_opportunity_ownership();
        }
        if (abortActions.abort_backend_transaction) {
            backend_aborted = present_backend_.abortPreparedBufferTransaction(
                prepared_frame_work_->surface_submission);
        } else if (abortActions.abort_render_target) {
            backend_aborted = present_backend_.abortRenderTargetBeforePreparation(
                prepared_frame_work_->buffer_slot,
                prepared_frame_work_->buffer_generation);
        }
        if (!swappy_aborted || !backend_aborted) {
            NTK_LOGE("fatal prepared-frame lifecycle abort failed work=%llu",
                     static_cast<unsigned long long>(work_generation));
            head_frame_state_ = HeadFrameState::FAILED;
            block_input_and_presentation();
            engine_failed_.store(true, std::memory_order_release);
            authority_failed_.store(true, std::memory_order_release);
            gpu_phase_.store(GpuPhase::FAILED, std::memory_order_release);
            return false;
        }
        prepared_frame_work_.reset();
        clear_fixed_opportunity_ownership();
        fixed_scheduler_.discardProducerWork();
        reserved_frame_id_ = 0;
        reserved_frame_id_work_generation_ = 0;
        if (head_frame_state_ != HeadFrameState::FAILED) {
            head_frame_state_ = HeadFrameState::EMPTY;
        }
        return true;
    }

    static ReleaseResourceIdentity release_identity(
            const char* kind, const AuthorityKey& key, std::int64_t surface_epoch,
            std::int64_t admission_id, int page, int slot,
            std::int64_t resource_revision, std::int64_t install_lease,
            std::int64_t retire_lease, std::int64_t rgba_bytes) {
        ReleaseResourceIdentity identity;
        identity.kind = kind;
        identity.key = key;
        identity.surface_epoch = surface_epoch;
        identity.admission_id = admission_id;
        identity.page = page;
        identity.slot = slot;
        identity.resource_revision = resource_revision;
        identity.install_lease = install_lease;
        identity.retire_lease = retire_lease;
        identity.rgba_bytes = rgba_bytes;
        return identity;
    }

    static std::int64_t inventory_rgba_bytes(
            const std::vector<ReleaseResourceIdentity>& resources) {
        std::int64_t total = 0;
        for (const auto& resource : resources) {
            if (resource.rgba_bytes > 0 &&
                total <= std::numeric_limits<std::int64_t>::max() - resource.rgba_bytes) {
                total += resource.rgba_bytes;
            }
        }
        return total;
    }

    void registry_add_locked(const ReleaseResourceIdentity& identity) {
        current_resource_registry_.push_back(identity);
    }

    bool registry_remove_locked(const ReleaseResourceIdentity& identity) {
        const auto found = std::find(
            current_resource_registry_.begin(), current_resource_registry_.end(), identity);
        if (found == current_resource_registry_.end()) return false;
        current_resource_registry_.erase(found);
        return true;
    }

    void requeue_in_flight_resource_delete_locked(PendingResourceDelete resource_delete) {
        const AuthorityKey key{
            engine_generation_, resource_delete.authority_generation,
            resource_delete.key.authority};
        const auto tracker = release_trackers_.find(key);
        if (tracker != release_trackers_.end()) {
            tracker->second->in_flight_resource_delete = false;
            tracker->second->resource_deletes.push_back(std::move(resource_delete));
        } else {
            resource_deletes_.push_back(std::move(resource_delete));
            resource_delete_depth_mirror_.store(
                static_cast<int>(resource_deletes_.size()),
                std::memory_order_release);
        }
        in_flight_resource_delete_.reset();
        upload_active_ = false;
    }

    bool complete_in_flight_resource_delete_locked(
            const PendingResourceDelete& resource_delete, bool success) {
        const AuthorityKey key{
            engine_generation_, resource_delete.authority_generation,
            resource_delete.key.authority};
        const std::int64_t resource_scope =
            resource_delete.detached_preparation
                ? resource_delete.preparation_generation
                : resource_delete.surface_epoch;
        const char* fence_kind = resource_delete.notify_freed
            ? "retire-fence"
            : (resource_delete.detached_preparation
                ? "detached-resource-delete-fence"
                : "resource-delete-fence");
        const char* texture_kind = resource_delete.detached_preparation
            ? "detached-resource-delete-texture"
            : "resource-delete-texture";
        const auto tracker = release_trackers_.find(key);
        if (tracker != release_trackers_.end()) {
            auto& release = *tracker->second;
            release.in_flight_resource_delete = false;
            if (!success) {
                release.lifecycle = AuthorityLifecycle::FAILED;
            } else {
                if (resource_delete.fence != nullptr) {
                    record_released_resource(release, release_identity(
                        fence_kind, key, resource_scope,
                        resource_delete.admission_id, resource_delete.key.page,
                        resource_delete.key.slot, resource_delete.resource_revision,
                        resource_delete.install_lease, resource_delete.retire_lease,
                        resource_delete.rgba_bytes));
                    if (context_resources_valid_.load(std::memory_order_acquire)) {
                        ++release.deleted_fence_count;
                    }
                }
                if (resource_delete.texture != 0) {
                    record_released_resource(release, release_identity(
                        texture_kind, key, resource_scope,
                        resource_delete.admission_id, resource_delete.key.page,
                        resource_delete.key.slot, resource_delete.resource_revision,
                        resource_delete.install_lease, resource_delete.retire_lease,
                        resource_delete.rgba_bytes));
                    if (context_resources_valid_.load(std::memory_order_acquire)) {
                        ++release.deleted_texture_count;
                    }
                }
                ++release.drained_retire_count;
            }
            in_flight_resource_delete_.reset();
            upload_active_ = false;
            return true;
        }
        if (resource_delete.fence != nullptr) registry_remove_locked(release_identity(
            fence_kind, key, resource_scope, resource_delete.admission_id,
            resource_delete.key.page, resource_delete.key.slot,
            resource_delete.resource_revision, resource_delete.install_lease,
            resource_delete.retire_lease, resource_delete.rgba_bytes));
        if (resource_delete.texture != 0) registry_remove_locked(release_identity(
            texture_kind, key, resource_scope,
            resource_delete.admission_id, resource_delete.key.page,
            resource_delete.key.slot, resource_delete.resource_revision,
            resource_delete.install_lease, resource_delete.retire_lease,
            resource_delete.rgba_bytes));
        in_flight_resource_delete_.reset();
        upload_active_ = false;
        return false;
    }

    bool record_released_resource(
            AuthorityReleaseTracker& tracker, const ReleaseResourceIdentity& identity) {
        const std::size_t captured = static_cast<std::size_t>(std::count(
            tracker.captured_resources.begin(), tracker.captured_resources.end(), identity));
        const std::size_t released = static_cast<std::size_t>(std::count(
            tracker.released_resources.begin(), tracker.released_resources.end(), identity));
        if (captured == 0 || released >= captured) {
            tracker.lifecycle = AuthorityLifecycle::FAILED;
            return false;
        }
        tracker.released_resources.push_back(identity);
        return true;
    }

    std::shared_ptr<AuthorityReleaseTracker> begin_release_current_on_render(
            const std::optional<ReleaseClaim>& explicit_claim,
            bool ensure_release_worker = true) {
        if (authority_ <= 0 || authority_generation_ <= 0) return nullptr;
        if (context_resources_valid_.load(std::memory_order_acquire) &&
            !fixed_scheduler_.normalTerminalConservationExact()) {
            NTK_LOGE("fatal normal release before terminal conservation");
            authority_failed_.store(true, std::memory_order_release);
            gpu_phase_.store(GpuPhase::FAILED, std::memory_order_release);
            return nullptr;
        }
        if (!abort_prepared_frame_for_lifecycle()) return nullptr;
        // A submitted buffer is never aborted or suppressed. Retire its exact
        // immutable target first, then resolve every compositor latch while
        // the current EGLSurface and private backend remain alive.
        if (!wait_present_join_for_lifecycle()) return nullptr;
        flush_feedback();
        if (!evidence_capsules_drained()) {
            NTK_LOGE("fatal authority release with incomplete evidence capsules committed=%llu delivered=%llu",
                     static_cast<unsigned long long>(
                         frame_feedback_committed_sequence_.load(
                             std::memory_order_acquire)),
                     static_cast<unsigned long long>(
                         frame_feedback_delivered_sequence_.load(
                             std::memory_order_acquire)));
            return nullptr;
        }
        const AuthorityKey key = current_authority_key();
        std::int64_t admission_close_serial = 0;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            const auto found = release_trackers_.find(key);
            if (found != release_trackers_.end()) return found->second;
            admission_close_serial = explicit_claim.has_value() &&
                    explicit_claim->admission_close_serial > 0
                ? explicit_claim->admission_close_serial
                : next_release_protocol_serial_locked();
        }
        auto tracker = std::make_shared<AuthorityReleaseTracker>();
        tracker->token.key = key;
        tracker->token.manifest_revision = current_manifest_revision_;
        tracker->token.manifest_digest = current_manifest_digest_;
        tracker->token.geometry_digest = current_geometry_digest_;
        tracker->lifecycle = explicit_claim.has_value()
            ? AuthorityLifecycle::RELEASING_CLAIMED
            : AuthorityLifecycle::RELEASING_UNCLAIMED;
        tracker->claim = explicit_claim;
        tracker->admission_close_serial = admission_close_serial;

        // Physical evidence is fully joined above; release never hides an
        // unresolved submitted generation in a background suppression object.
        successful_swap_count_ = 0;
        latched_proof_count_ = 0;
        terminal_lost_proof_count_ = 0;
        duplicate_frame_id_count_ = 0;
        max_logical_unlatched_submissions_ = 0;

        // The render lane is the only scene owner. Fence the last old draw and move the scene
        // in O(1), then successor bind can proceed without polling this fence.
        if (!scene_.empty() && context_resources_valid_.load(std::memory_order_acquire)) {
            tracker->render_fence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            if (tracker->render_fence != nullptr) {
                glFlush();
                tracker->render_fence_submitted_ns = monotonic_now_ns();
                tracker->render_fence_surface_epoch = static_cast<std::int64_t>(surface_epoch_);
            } else {
                tracker->lifecycle = AuthorityLifecycle::FAILED;
            }
        }
        tracker->scene.swap(scene_);
        tracker->resident_intervals.swap(resident_intervals_);
        tracker->applied_protection = std::move(applied_protection_);
        applied_protection_ = AppliedProtection{};

        {
            std::lock_guard<std::mutex> lock(mutex_);
            // These containers contain only the current token; B cannot submit until bind ACK.
            // Swap each owner wholesale so the render critical path is independent of A size.
            tracker->queued_uploads.swap(upload_commands_);
            preallocate_commands_.clear();
            tracker->ready_tiles.swap(gpu_ready_tiles_);
            tracker->resource_deletes.swap(resource_deletes_);
            ready_tile_queue_depth_mirror_.store(
                static_cast<int>(gpu_ready_tiles_.size()),
                std::memory_order_release);
            resource_delete_depth_mirror_.store(
                static_cast<int>(resource_deletes_.size()),
                std::memory_order_release);
            tracker->preallocated_textures.swap(preallocated_textures_);
            tracker->prepared_bank.swap(prepared_bank_);
            if (preparation_open_) prepared_bank_ledger_.retire();
            preparation_open_ = false;
            prepared_geometry_bound_ = false;
            preparation_admissions_closed_ = false;
            preparation_token_nonce_ = 0;
            preparation_opened_ns_ = 0;
            tracker->in_flight_upload = in_flight_upload_.has_value() &&
                in_flight_upload_->authority_generation == key.authority_generation &&
                in_flight_upload_->key.authority == key.authority;
            tracker->in_flight_resource_delete = in_flight_resource_delete_.has_value() &&
                in_flight_resource_delete_->authority_generation == key.authority_generation &&
                in_flight_resource_delete_->key.authority == key.authority;
            tracker->outstanding_publications = static_cast<int>(native_outstanding_);
            native_outstanding_ = 0;
            native_outstanding_mirror_.store(0, std::memory_order_release);
            tracker->slot_specs.swap(slot_specs_);
            tracker->ordinal_keys.swap(ordinal_keys_);
            tracker->key_ordinals.swap(key_ordinals_);
            input_control_commands_.clear();
            tracker->captured_resources.swap(current_resource_registry_);
            if (tracker->render_fence != nullptr) {
                tracker->captured_resources.push_back(release_identity(
                    "render-fence", key, tracker->render_fence_surface_epoch,
                    0, -1, -1, 0, 0, 0, 0));
            }
            tracker->resource_barrier_serial = next_release_protocol_serial_locked();
            if (explicit_claim.has_value()) {
                tracker->release_claim_serial = next_release_protocol_serial_locked();
            }
            if (tracker->admission_close_serial <= 0 ||
                tracker->resource_barrier_serial <= tracker->admission_close_serial ||
                (explicit_claim.has_value() &&
                    tracker->release_claim_serial <= tracker->admission_close_serial)) {
                tracker->lifecycle = AuthorityLifecycle::FAILED;
            }
            release_trackers_.emplace(key, tracker);
            pending_release_claims_.erase(key);
        }
        scene_version_ = 0;
        // Frame telemetry is authority-scoped. An unstaged predecessor can integrate resources
        // without ever submitting a window frame, so clear its historical count at the exact
        // release boundary before any successor scene is admitted.
        integrated_tiles_since_frame_ = 0;
        {
            std::lock_guard<std::mutex> mailbox_lock(move_mailbox_mutex_);
            move_mailbox_ = InputSample{};
            move_mailbox_sequence_ = 0;
        }
        authority_ = 0;
        authority_generation_ = 0;
        current_manifest_revision_ = 0;
        current_manifest_digest_.clear();
        current_geometry_digest_.clear();
        current_pregeometry_root_digest_.clear();
        content_height_ = 0;
        presented_view_state_ = PresentedViewState{};
        presented_visual_mutation_serial_ = 0;
        fixed_scheduler_.reset(presented_view_state_);
        ingress_pointer_down_.store(false, std::memory_order_release);
        first_main_ingress_ns_.store(0, std::memory_order_release);
        first_down_ingress_ns_.store(0, std::memory_order_release);
        stage_latch_ns_.store(0, std::memory_order_release);
        control_backlog_max_.store(0, std::memory_order_release);
        move_mailbox_writes_.store(0, std::memory_order_release);
        stage_requested_ = false;
        head_frame_state_ = HeadFrameState::EMPTY;
        prepared_frame_work_.reset();
        reserved_frame_id_ = 0;
        reserved_frame_id_work_generation_ = 0;
        stage_nonce_ = 0;
        staged_nonce_.store(0, std::memory_order_release);
        renderer_mode_.store(RendererMode::PREPARING, std::memory_order_release);
        block_input_and_presentation();
        if (context_resources_valid_.load(std::memory_order_acquire) &&
            ensure_release_worker) {
            const GpuResourceWorkerState worker_state =
                gpu_resource_worker_state_.load(std::memory_order_acquire);
            const bool worker_ready = worker_state ==
                    GpuResourceWorkerState::PRE_STAGE_ACTIVE
                ? resource_worker_owns(key.authority_generation, key.authority)
                : create_resource_worker_context(key);
            if (!worker_ready) {
                tracker->lifecycle = AuthorityLifecycle::FAILED;
                gpu_resource_worker_state_.store(
                    GpuResourceWorkerState::FAILED, std::memory_order_release);
                authority_failed_.store(true, std::memory_order_release);
                gpu_phase_.store(GpuPhase::FAILED, std::memory_order_release);
            }
        }
        upload_condition_.notify_one();
        return tracker;
    }

    static void swappy_pre_swap(void* user_data) {
        static_cast<StripRenderer*>(user_data)->record_trace(TracePhase::PRE_SWAP, 0);
    }

    static void swappy_post_swap(void* user_data, std::int64_t desired_present_ms) {
        static_cast<StripRenderer*>(user_data)->record_trace(
            TracePhase::POST_SWAP, desired_present_ms);
    }

    static void swappy_fixed_state_changed(
            void* user_data, SwappyFixedWakeNotice* notice) {
        auto* renderer = static_cast<StripRenderer*>(user_data);
        if (renderer == nullptr || notice == nullptr) return;
        const std::int64_t observed_ns = monotonic_now_ns();
        const bool exact = notice->structSize == sizeof(SwappyFixedWakeNotice) &&
            notice->version == SWAPPY_FIXED_WAKE_NOTICE_VERSION &&
            notice->noticeSequence != 0 && notice->workGeneration != 0 &&
            notice->reservationSequence != 0 &&
            notice->candidateSequence != 0 &&
            notice->wakeDispatchNanos > 0 &&
            observed_ns >= notice->wakeDispatchNanos;
        if (!exact) {
            renderer->fixed_wake_notice_invalid_.store(
                true, std::memory_order_release);
            renderer->fixed_scheduler_.noteOpportunityProtocolFatal();
            renderer->signal_external_render_event();
            return;
        }
        if (notice->wakeReason == SWAPPY_FIXED_WAKE_CANDIDATE_AVAILABLE) {
            renderer->fixed_scheduler_.noteCandidateNoticeIgnored();
            return;
        }
        if (notice->wakeReason != SWAPPY_FIXED_WAKE_JOIN_OPEN ||
            notice->opportunitySequence == 0 ||
            notice->opportunityPublishNanos <= 0 ||
            notice->wakeDispatchNanos < notice->opportunityPublishNanos) {
            renderer->fixed_wake_notice_invalid_.store(
                true, std::memory_order_release);
            renderer->fixed_scheduler_.noteOpportunityProtocolFatal();
            renderer->signal_external_render_event();
            return;
        }
        const std::uint64_t armed_work =
            renderer->armed_fixed_work_generation_.load(
                std::memory_order_acquire);
        const std::uint64_t armed_reservation =
            renderer->armed_fixed_reservation_sequence_.load(
                std::memory_order_acquire);
        if (notice->workGeneration != armed_work ||
            notice->reservationSequence != armed_reservation) {
            renderer->fixed_scheduler_.noteForeignNotice();
            return;
        }

        // Only the matching owner may publish observation evidence back into
        // Common's process-global tracer payload.
        notice->rendererCallbackObservedNanos = observed_ns;
        const ntk::scheduler::FixedOpportunityIdentity identity{
            .work_generation = notice->workGeneration,
            .reservation_sequence = notice->reservationSequence,
            .opportunity_sequence = notice->opportunitySequence,
            .candidate_sequence = notice->candidateSequence,
            .notice_sequence = notice->noticeSequence,
        };
        const auto result = renderer->fixed_opportunity_gate_.publish(identity);
        using PublishResult =
            ntk::scheduler::FixedOpportunityGate::PublishResult;
        if (result == PublishResult::PUBLISHED) {
            renderer->fixed_scheduler_.noteMatchingJoinOpenPublish();
            renderer->signal_external_render_event();
        } else if (result == PublishResult::DUPLICATE) {
            renderer->fixed_scheduler_.noteDuplicateJoinOpen();
        } else if (result == PublishResult::FOREIGN) {
            renderer->fixed_scheduler_.noteForeignNotice();
        } else {
            renderer->fixed_scheduler_.noteOpportunityProtocolFatal();
            renderer->fixed_wake_notice_invalid_.store(
                true, std::memory_order_release);
            renderer->signal_external_render_event();
        }
    }

    static void swappy_start_frame(void* user_data, int current_frame,
                                   std::int64_t desired_present_ms) {
        const std::int64_t packed = (static_cast<std::int64_t>(current_frame) << 32) ^
            (desired_present_ms & 0xffffffffLL);
        static_cast<StripRenderer*>(user_data)->record_trace(TracePhase::START_FRAME, packed);
    }

    static void swappy_interval_changed(void* user_data) {
        auto* renderer = static_cast<StripRenderer*>(user_data);
        renderer->swap_interval_changed_.store(true, std::memory_order_release);
        renderer->authority_failed_.store(true, std::memory_order_release);
        renderer->record_trace(TracePhase::SWAP_INTERVAL_CHANGED, 0);
    }

    static void swappy_fixed_retirement_completed(
            void* user_data,
            const SwappyFixedRetirementTelemetryV2* event) {
        auto* renderer = static_cast<StripRenderer*>(user_data);
        if (renderer == nullptr || event == nullptr) return;
        const std::uint64_t sequence =
            renderer->fixed_retirement_event_write_sequence_.fetch_add(
                1, std::memory_order_relaxed) + 1;
        const std::uint64_t consumed =
            renderer->fixed_retirement_event_read_sequence_.load(
                std::memory_order_acquire);
        if (sequence <= consumed ||
            sequence - consumed > kFixedRetirementEventRingSize) {
            renderer->fixed_retirement_event_invalid_.store(
                true, std::memory_order_release);
        } else {
            FixedRetirementEventSlot& slot =
                renderer->fixed_retirement_event_ring_[
                    (sequence - 1) % kFixedRetirementEventRingSize];
            slot.event = *event;
            slot.committedSequence.store(sequence, std::memory_order_release);
        }
        renderer->signal_external_render_event();
    }

    void configure_swappy_tracer() {
        if (!swappy_ready_) return;
        swappy_tracer_.preWait = nullptr;
        swappy_tracer_.postWait = nullptr;
        swappy_tracer_.preSwapBuffers = nullptr;
        swappy_tracer_.postSwapBuffers = nullptr;
        swappy_tracer_.startFrame = &StripRenderer::swappy_start_frame;
        swappy_tracer_.userData = this;
        swappy_tracer_.swapIntervalChanged = &StripRenderer::swappy_interval_changed;
        swappy_tracer_.fixedPhaseOpportunity =
            &StripRenderer::swappy_fixed_state_changed;
        swappy_tracer_.fixedRetirementCompleted =
            &StripRenderer::swappy_fixed_retirement_completed;
        SwappyGL_injectTracer(&swappy_tracer_);
        swappy_tracer_injected_ = true;
    }

    void fail_present_event(EvidenceCapsuleSlot* slot, const char* reason) {
        NTK_LOGE("fatal present event reason=%s capsule=%llu",
            reason,
            static_cast<unsigned long long>(
                slot != nullptr ? slot->capsule.capsuleSequence : 0));
        if (slot != nullptr) {
            slot->latchTerminalState =
                ntk::present::LatchTerminalState::INVALID_EVENT;
            slot->retirementTerminalState =
                ntk::present::RetirementTerminalState::INVALID_EVENT;
            slot->cadenceQualificationFailed = true;
            complete_evidence_capsule_if_joined(
                slot->capsule.capsuleSequence);
        }
        cadence_qualification_failed_.store(true, std::memory_order_release);
        cadence_qualification_state_.store(
            CadenceQualificationState::FAILED, std::memory_order_release);
        authority_failed_.store(true, std::memory_order_release);
        gpu_phase_.store(GpuPhase::FAILED, std::memory_order_release);
        block_input_and_presentation();
    }

    void apply_compositor_latch_event(
            const ntk::present::FixedPresentEvent& event) {
        EvidenceCapsuleSlot* slot = evidence_capsule_slot(
            event.identity.capsuleSequence);
        if (slot != nullptr) {
            ++slot->conservationProof.latchTransitionCount;
        }
        ntk::present::SurfaceControlPresentBackend::ExactPresentLatchObservation
            latchObservation{};
        if (slot == nullptr ||
            !ntk::present::FixedPresentJoinState::isExactLatch(
                slot->capsule.identity, event) ||
            slot->latchTerminalState !=
                ntk::present::LatchTerminalState::WAITING_EVENT ||
            !present_backend_.consumeCompositorLatch(
                event, &latchObservation)) {
            fail_present_event(slot, "invalid-or-duplicate-on-commit");
            return;
        }
        SwappyFixedLatchObservationV1 swappyObservation{};
        swappyObservation.structSize = sizeof(swappyObservation);
        swappyObservation.version = SWAPPY_FIXED_LATCH_OBSERVATION_V1_VERSION;
        swappyObservation.identity = to_swappy_identity(latchObservation.identity);
        swappyObservation.latchEventSequence =
            latchObservation.latchEventSequence;
        swappyObservation.compositorLatchNanos = latchObservation.latchNanos;
        swappyObservation.callbackObservedNanos = event.callbackObservedNanos;
        swappyObservation.source =
            static_cast<std::uint32_t>(latchObservation.source);
        swappyObservation.onCommitCallbackCount = event.onCommitCallbackCount;
        // OnCommit is retained as exact own-frame evidence. Together with the
        // predecessor's retirement proof it opens the successor JOIN exactly
        // once; the proof is consumed by that successor before apply.
        if (!SwappyGL_recordExternalLatchObservationForNtk(
                &swappyObservation)) {
            fail_present_event(slot, "swappy-on-commit-observation");
            return;
        }
        slot->latchEvent = event;
        slot->latchTerminalState =
            ntk::present::LatchTerminalState::LATCHED;
        slot->conservationProof.latchTransitionExact =
            slot->conservationProof.latchTransitionCount == 1;
        slot->capsule.latchSource =
            static_cast<std::uint32_t>(event.latchSource);
        slot->capsule.latchEventSequence = event.eventSequence;
        slot->capsule.latchCallbackObservedNanos =
            event.callbackObservedNanos;
        slot->capsule.onCommitCallbackCount =
            event.onCommitCallbackCount;
        ++latched_proof_count_;
        if (event.identity.workGeneration >= 45) {
            NTK_LOGI(
                "qualification timing latch work=%llu admission=%llu "
                "latch=%lld observed=%lld",
                static_cast<unsigned long long>(
                    event.identity.workGeneration),
                static_cast<unsigned long long>(
                    event.identity.admissionSequence),
                static_cast<long long>(event.latchNanos),
                static_cast<long long>(event.callbackObservedNanos));
        }
        complete_evidence_capsule_if_joined(
            event.identity.capsuleSequence);
    }

    void apply_swappy_retirement_event(
            const SwappyFixedRetirementTelemetryV2& event) {
        EvidenceCapsuleSlot* slot = evidence_capsule_slot(
            event.capsuleSequence);
        if (slot != nullptr) {
            ++slot->retirementCallbackObservedCount;
        }
        if (slot == nullptr || event.structSize != sizeof(event) ||
            event.version != SWAPPY_FIXED_RETIREMENT_TELEMETRY_V2_VERSION ||
            event.retirementSequence == 0 ||
            slot->retirementCallbackObservedCount != 1 ||
            slot->retirementTerminalState !=
                ntk::present::RetirementTerminalState::WAITING_EVENT) {
            fail_present_event(slot, "invalid-or-duplicate-retirement");
            return;
        }
        const auto& identity = slot->capsule.identity;
        const bool exact =
            ntk::present::FixedPresentJoinState::isExactRetirementIdentity(
                identity, event);
        if (!exact) {
            fail_present_event(slot, "retirement-identity-mismatch");
            return;
        }
        slot->retirementEvent = event;
        slot->retirementTerminalState =
                event.state == SWAPPY_FIXED_RETIREMENT_RETIRED &&
                event.fatalReason == 0
            ? ntk::present::RetirementTerminalState::RETIRED
            : ntk::present::RetirementTerminalState::INVALID_EVENT;
        const bool targetRetired = slot->retirementTerminalState ==
            ntk::present::RetirementTerminalState::RETIRED;
        slot->capsule.swappyRetirementSequence =
            event.retirementSequence;
        slot->capsule.retirementCallbackObservedNanos =
            event.callbackPublishedNanos;
        if (!targetRetired) {
            cadence_qualification_failed_.store(true, std::memory_order_release);
            cadence_qualification_state_.store(
                CadenceQualificationState::FAILED,
                std::memory_order_release);
            block_input_and_presentation();
            engine_failed_.store(true, std::memory_order_release);
            authority_failed_.store(true, std::memory_order_release);
            gpu_phase_.store(GpuPhase::FAILED, std::memory_order_release);
        }
        if (event.workGeneration >= 45) {
            NTK_LOGI(
                "qualification timing retire work=%llu admission=%llu "
                "target=%lld reached=%lld published=%lld",
                static_cast<unsigned long long>(event.workGeneration),
                static_cast<unsigned long long>(event.admissionSequence),
                static_cast<long long>(event.plannedTargetFrame),
                static_cast<long long>(event.targetReachedNanos),
                static_cast<long long>(event.callbackPublishedNanos));
        }
        complete_evidence_capsule_if_joined(event.capsuleSequence);
    }

    void drain_fixed_retirement_events_on_render_thread() {
        std::uint64_t expected =
            fixed_retirement_event_read_sequence_.load(
                std::memory_order_relaxed) + 1;
        const std::uint64_t published =
            fixed_retirement_event_write_sequence_.load(
                std::memory_order_acquire);
        while (expected <= published) {
            FixedRetirementEventSlot& slot =
                fixed_retirement_event_ring_[
                    (expected - 1) % kFixedRetirementEventRingSize];
            if (slot.committedSequence.load(std::memory_order_acquire) !=
                expected) {
                break;
            }
            apply_swappy_retirement_event(slot.event);
            fixed_retirement_event_read_sequence_.store(
                expected, std::memory_order_release);
            ++expected;
        }
        if (fixed_retirement_event_invalid_.load(
                std::memory_order_acquire)) {
            fail_present_event(nullptr, "retirement-event-ring-overflow");
        }
    }

    static bool is_deferrable_present_cleanup_event(
            ntk::present::FixedPresentEventKind kind) noexcept {
        using Kind = ntk::present::FixedPresentEventKind;
        return kind == Kind::TRANSACTION_COMPLETED ||
            kind == Kind::PREVIOUS_BUFFER_RELEASED ||
            kind == Kind::ACQUIRE_FENCE_SIGNALED;
    }

    bool complete_cleanup_requires_successor_submission(
            const ntk::present::FixedPresentEvent& event) {
        if (event.kind != ntk::present::FixedPresentEventKind::
                TRANSACTION_COMPLETED ||
            event.identity.workGeneration == 0 ||
            last_successfully_submitted_work_generation_ >
                event.identity.workGeneration) {
            return false;
        }
        EvidenceCapsuleSlot* slot = evidence_capsule_slot(
            event.identity.capsuleSequence);
        return slot != nullptr && !slot->capsule.prepared.terminal &&
            !slot->capsule.prepared.stage_candidate;
    }

    bool present_cleanup_event_should_defer(
            const ntk::present::FixedPresentEvent& event) {
        if (!present_backend_attached_ ||
            presentation_blocked_.load(std::memory_order_acquire) ||
            authority_failed_.load(std::memory_order_acquire) ||
            fixed_causal_lane_fatal_) {
            return false;
        }
        return complete_cleanup_requires_successor_submission(event) ||
            normal_present_cleanup_should_defer();
    }

    bool defer_present_cleanup_event(
            const ntk::present::FixedPresentEvent& event) noexcept {
        const bool waitForNewer =
            complete_cleanup_requires_successor_submission(event);
        const std::uint64_t blocker = waitForNewer
            ? event.identity.workGeneration
            : (prepared_frame_work_.has_value()
                ? prepared_frame_work_->work_generation : 0);
        if (!present_cleanup_event_should_defer(event) || blocker == 0 ||
            deferred_present_cleanup_count_ >=
            kDeferredPresentCleanupCapacity) {
            return false;
        }
        deferred_present_cleanup_events_[deferred_present_cleanup_write_] =
            DeferredPresentCleanupRecord{
                .event = event,
                .blockedWorkGeneration = blocker,
                .waitForNewerSubmission = waitForNewer,
            };
        deferred_present_cleanup_write_ =
            (deferred_present_cleanup_write_ + 1) %
                kDeferredPresentCleanupCapacity;
        ++deferred_present_cleanup_count_;
        return true;
    }

    bool pop_deferred_present_cleanup_event(
            ntk::present::FixedPresentEvent* event,
            bool forceDrain) noexcept {
        if (event == nullptr || deferred_present_cleanup_count_ == 0) {
            return false;
        }
        DeferredPresentCleanupRecord& record =
            deferred_present_cleanup_events_[
                deferred_present_cleanup_read_];
        if (!forceDrain) {
            const bool blocked = record.waitForNewerSubmission
                ? last_successfully_submitted_work_generation_ <=
                    record.blockedWorkGeneration
                : normal_present_cleanup_should_defer() &&
                    prepared_frame_work_->work_generation ==
                        record.blockedWorkGeneration;
            if (blocked) return false;
        }
        *event = record.event;
        record = {};
        deferred_present_cleanup_read_ =
            (deferred_present_cleanup_read_ + 1) %
                kDeferredPresentCleanupCapacity;
        --deferred_present_cleanup_count_;
        return true;
    }

    bool deferred_present_cleanup_front_is_actionable() const {
        if (deferred_present_cleanup_count_ == 0) return false;
        const DeferredPresentCleanupRecord& record =
            deferred_present_cleanup_events_[
                deferred_present_cleanup_read_];
        if (record.waitForNewerSubmission) {
            return last_successfully_submitted_work_generation_ >
                record.blockedWorkGeneration;
        }
        return !normal_present_cleanup_should_defer() ||
            prepared_frame_work_->work_generation !=
                record.blockedWorkGeneration;
    }

    bool normal_present_cleanup_should_defer() const {
        return prepared_frame_work_.has_value() &&
            head_frame_state_ == HeadFrameState::SWAPPY_RESERVED &&
            present_backend_attached_ &&
            !presentation_blocked_.load(std::memory_order_acquire) &&
            !authority_failed_.load(std::memory_order_acquire) &&
            !fixed_causal_lane_fatal_;
    }

    PresentPumpResult try_commit_priority_present_lane() {
        if (!prepared_frame_work_.has_value() ||
            head_frame_state_ != HeadFrameState::SWAPPY_RESERVED ||
            !fixed_opportunity_gate_.hasPending()) {
            return PresentPumpResult::IDLE;
        }
        const PreparedCommitResult commit = service_ready_prepared_frame();
        if (commit == PreparedCommitResult::SUBMITTED) {
            return PresentPumpResult::SUBMITTED;
        }
        if (commit ==
                PreparedCommitResult::SUBMITTED_FATAL_AFTER_EGL_TRUE) {
            return PresentPumpResult::SUBMITTED_FATAL;
        }
        if (commit == PreparedCommitResult::FATAL) {
            return PresentPumpResult::FATAL;
        }
        return PresentPumpResult::PROGRESSED;
    }

    bool normal_present_event_wake_is_actionable() {
        if (present_backend_.hasPendingEvent()) return true;
        return deferred_present_cleanup_front_is_actionable();
    }

    PresentPumpResult drain_present_events_on_render_thread(
            PresentDrainMode mode) {
        const bool forceDrain = mode == PresentDrainMode::FORCE_DRAIN;
        PresentPumpResult result = PresentPumpResult::IDLE;
        // An already-open exact JOIN owns the first instruction of NORMAL.
        // Only when it does not submit may retirement publish a newer JOIN,
        // which is retried before any backend callback is inspected.
        if (!forceDrain) {
            const PresentPumpResult commit =
                try_commit_priority_present_lane();
            if (commit == PresentPumpResult::SUBMITTED ||
                commit == PresentPumpResult::SUBMITTED_FATAL ||
                commit == PresentPumpResult::FATAL) {
                // The synchronous post-apply cut owns this return. No
                // OnCommit/OnComplete/release/acquire cleanup may follow it.
                return commit;
            }
            if (commit == PresentPumpResult::PROGRESSED) {
                result = PresentPumpResult::PROGRESSED;
            }
        }
        drain_fixed_retirement_events_on_render_thread();
        if (!forceDrain) {
            const PresentPumpResult commit =
                try_commit_priority_present_lane();
            if (commit == PresentPumpResult::SUBMITTED ||
                commit == PresentPumpResult::SUBMITTED_FATAL ||
                commit == PresentPumpResult::FATAL) {
                return commit;
            }
            if (commit == PresentPumpResult::PROGRESSED) {
                result = PresentPumpResult::PROGRESSED;
            }
        }

        ntk::present::FixedPresentEvent event{};
        std::size_t drained = 0;
        const std::size_t budget = forceDrain
            ? std::numeric_limits<std::size_t>::max()
            : kNormalPresentCleanupBudget;
        while (drained < budget) {
            const bool fromDeferred =
                pop_deferred_present_cleanup_event(&event, forceDrain);
            if (!fromDeferred && !present_backend_.drainEvent(&event)) break;
            ++drained;
            result = PresentPumpResult::PROGRESSED;

            // COMPLETE/release/acquire can be ahead of the predecessor's
            // OnCommit in the callback queue. Once dequeued, preserve them in
            // this fixed ring and keep scanning for the authority-driving
            // latch. A concurrently published JOIN_OPEN gets first use below.
            if (!forceDrain && !fromDeferred &&
                is_deferrable_present_cleanup_event(event.kind) &&
                present_cleanup_event_should_defer(event)) {
                if (!defer_present_cleanup_event(event)) {
                    fail_present_event(
                        evidence_capsule_slot(
                            event.identity.capsuleSequence),
                        "deferred-present-cleanup-overflow");
                    return PresentPumpResult::FATAL;
                }
                drain_fixed_retirement_events_on_render_thread();
                const PresentPumpResult commit =
                    try_commit_priority_present_lane();
                if (commit == PresentPumpResult::SUBMITTED ||
                    commit == PresentPumpResult::SUBMITTED_FATAL ||
                    commit == PresentPumpResult::FATAL) {
                    return commit;
                }
                continue;
            }

            const bool failedBefore = authority_failed_.load(
                std::memory_order_acquire);
            switch (event.kind) {
                case ntk::present::FixedPresentEventKind::
                        COMPOSITOR_LATCHED:
                    apply_compositor_latch_event(event);
                    break;
                case ntk::present::FixedPresentEventKind::
                        TRANSACTION_COMPLETED: {
                    EvidenceCapsuleSlot* slot = evidence_capsule_slot(
                        event.identity.capsuleSequence);
                    if (slot != nullptr) {
                        ++slot->conservationProof.completionTransitionCount;
                    }
                    if (slot == nullptr ||
                        !ntk::present::exactIdentity(
                            slot->capsule.identity, event.identity) ||
                        event.onCompleteCallbackCount != 1 ||
                        event.onCommitCallbackCount != 1) {
                        fail_present_event(slot, "invalid-on-complete");
                    } else if (!present_backend_.consumeTransactionCompleted(
                                   event)) {
                        fail_present_event(slot, "duplicate-transaction-complete");
                    } else {
                        slot->capsule.onCompleteCallbackCount =
                            event.onCompleteCallbackCount;
                        slot->transactionCompleteTerminal = true;
                        slot->transactionCompleteExact = true;
                        slot->conservationProof.completionTransitionExact =
                            slot->conservationProof.completionTransitionCount == 1;
                        slot->transactionCompleteEventSequence =
                            event.eventSequence;
                        complete_evidence_capsule_if_joined(
                            event.identity.capsuleSequence);
                    }
                    break;
                }
                case ntk::present::FixedPresentEventKind::
                        PREVIOUS_BUFFER_RELEASED: {
                    EvidenceCapsuleSlot* slot = evidence_capsule_slot(
                        event.identity.capsuleSequence);
                    if (slot != nullptr) {
                        ++slot->conservationProof.
                            previousReleaseTransitionCount;
                    }
                    const bool exactRelease = slot != nullptr &&
                        slot->capsule.previousBufferExpected &&
                        !slot->previousReleaseTerminal &&
                        slot->capsule.previousBufferSlot ==
                            event.releasedBufferSlot &&
                        slot->capsule.previousBufferGeneration ==
                            event.releasedBufferGeneration &&
                        present_backend_.consumePreviousBufferReleased(event);
                    if (!exactRelease) {
                        fail_present_event(slot, "invalid-release-fence");
                    } else {
                        slot->previousReleaseTerminal = true;
                        slot->previousReleaseExact = true;
                        slot->conservationProof.
                            previousReleaseTransitionExact =
                            slot->conservationProof.
                                previousReleaseTransitionCount == 1;
                        slot->previousReleaseEventSequence =
                            event.eventSequence;
                        complete_evidence_capsule_if_joined(
                            event.identity.capsuleSequence);
                    }
                    break;
                }
                case ntk::present::FixedPresentEventKind::
                        ACQUIRE_FENCE_SIGNALED: {
                    EvidenceCapsuleSlot* slot = evidence_capsule_slot(
                        event.identity.capsuleSequence);
                    if (slot != nullptr) {
                        ++slot->conservationProof.
                            acquireFenceTransitionCount;
                    }
                    const bool exactAcquire = slot != nullptr &&
                        !slot->acquireFenceTerminal &&
                        ntk::present::exactIdentity(
                            slot->capsule.identity, event.identity) &&
                        event.acquireFenceSerial ==
                            slot->capsule.gpuReadyProof.acquireFenceSerial &&
                        present_backend_.consumeAcquireFenceSignaled(event);
                    if (!exactAcquire) {
                        fail_present_event(slot, "invalid-acquire-fence");
                    } else {
                        slot->acquireFenceTerminal = true;
                        slot->acquireFenceExact = true;
                        slot->acquireFenceEventSequence = event.eventSequence;
                        slot->conservationProof.acquireFenceTransitionExact =
                            slot->conservationProof.
                                acquireFenceTransitionCount == 1;
                        slot->capsule.acquireFenceSerial =
                            event.acquireFenceSerial;
                        slot->capsule.acquireFenceSignalNanos =
                            event.acquireFenceSignalNanos;
                        slot->capsule.acquireFenceEventSequence =
                            event.eventSequence;
                        slot->capsule.proofFdCloseCount =
                            event.proofFdCloseCount;
                        complete_evidence_capsule_if_joined(
                            event.identity.capsuleSequence);
                    }
                    break;
                }
                case ntk::present::FixedPresentEventKind::
                        TEARDOWN_COMPLETED:
                    break;
                case ntk::present::FixedPresentEventKind::INVALID_CALLBACK:
                default:
                    {
                        EvidenceCapsuleSlot* slot = evidence_capsule_slot(
                            event.identity.capsuleSequence);
                        if (event.onCommitCallbackCount != 0 &&
                            event.onCompleteCallbackCount == 0) {
                            ntk::present::SurfaceControlPresentBackend::
                                ExactPresentLatchObservation ignored{};
                            (void)present_backend_.consumeCompositorLatch(
                                event, &ignored);
                        }
                        if (slot != nullptr &&
                            event.onCompleteCallbackCount != 0) {
                            if (ntk::present::exactIdentity(
                                    slot->capsule.identity,
                                    event.identity)) {
                                (void)present_backend_.consumeTransactionCompleted(
                                    event);
                            }
                            slot->transactionCompleteTerminal = true;
                            slot->transactionCompleteExact = false;
                            slot->transactionCompleteEventSequence =
                                event.eventSequence;
                        }
                        if (slot != nullptr &&
                            event.releasedBufferGeneration != 0) {
                            (void)present_backend_.
                                consumePreviousBufferReleased(event);
                            slot->previousReleaseTerminal = true;
                            slot->previousReleaseExact = false;
                            slot->previousReleaseEventSequence =
                                event.eventSequence;
                        }
                        if (slot != nullptr &&
                            event.acquireFenceSerial != 0) {
                            slot->acquireFenceTerminal = true;
                            slot->acquireFenceExact = false;
                            slot->acquireFenceEventSequence =
                                event.eventSequence;
                        }
                        fail_present_event(
                            slot, "surface-control-callback-contract");
                    }
                    break;
            }
            if (!forceDrain) {
                if (!failedBefore && authority_failed_.load(
                        std::memory_order_acquire)) {
                    return PresentPumpResult::FATAL;
                }
                // A JOIN_OPEN may race any compositor/fence callback. Recheck
                // its progress lane after every single cleanup transition.
                drain_fixed_retirement_events_on_render_thread();
                const PresentPumpResult commit =
                    try_commit_priority_present_lane();
                if (commit == PresentPumpResult::SUBMITTED ||
                    commit == PresentPumpResult::SUBMITTED_FATAL ||
                    commit == PresentPumpResult::FATAL) {
                    return commit;
                }
            }
        }
        if (present_backend_.eventOverflowed()) {
            fail_present_event(nullptr, "present-event-ring-overflow");
            if (!forceDrain) return PresentPumpResult::FATAL;
        }
        return result;
    }

    void record_trace(TracePhase phase, std::int64_t value) {
        const std::uint64_t sequence = trace_write_sequence_.fetch_add(
            1, std::memory_order_relaxed) + 1;
        TraceSlot& slot = trace_ring_[sequence % kTraceRingSize];
        slot.record.sequence = sequence;
        slot.record.frame_sequence = 0;
        slot.record.phase = phase;
        slot.record.timestamp_ns = monotonic_now_ns();
        slot.record.value = value;
        slot.committed_sequence.store(sequence, std::memory_order_release);
        switch (phase) {
            case TracePhase::PRE_WAIT:
            case TracePhase::TARGET_REACHED:
            case TracePhase::FENCE_COMPLETE:
            case TracePhase::POST_WAIT:
                break;
            case TracePhase::PRE_SWAP:
                trace_pre_swap_ns_.store(slot.record.timestamp_ns, std::memory_order_release);
                break;
            case TracePhase::POST_SWAP:
                trace_post_swap_ns_.store(slot.record.timestamp_ns, std::memory_order_release);
                break;
            case TracePhase::START_FRAME:
            case TracePhase::SWAP_INTERVAL_CHANGED:
                break;
        }
    }

    void enqueue_feedback(const FeedbackRecord& record) {
        std::unique_lock<std::mutex> lock(feedback_mutex_);
        feedback_space_.wait(lock, [&] {
            return feedback_exit_requested_ || feedback_count_ < kFeedbackRingSize;
        });
        if (feedback_exit_requested_) return;
        feedback_ring_[feedback_write_ % kFeedbackRingSize] = record;
        ++feedback_write_;
        ++feedback_count_;
        lock.unlock();
        feedback_ready_.notify_one();
    }

    void fail_frame_feedback_capacity(
            const char* boundary, std::uint64_t next, std::uint64_t consumed) {
        evidence_capsule_invalid_count_.fetch_add(1, std::memory_order_acq_rel);
        if (frame_feedback_capacity_failed_.exchange(
                true, std::memory_order_acq_rel)) return;
        NTK_LOGE("fatal frame evidence capacity boundary=%s next=%llu consumed=%llu "
                 "write=%llu committed=%llu read=%llu delivered=%llu",
                 boundary, static_cast<unsigned long long>(next),
                 static_cast<unsigned long long>(consumed),
                 static_cast<unsigned long long>(frame_feedback_write_sequence_),
                 static_cast<unsigned long long>(frame_feedback_committed_sequence_.load(
                     std::memory_order_acquire)),
                 static_cast<unsigned long long>(frame_feedback_read_sequence_.load(
                     std::memory_order_acquire)),
                 static_cast<unsigned long long>(frame_feedback_delivered_sequence_.load(
                     std::memory_order_acquire)));
        cadence_qualification_failed_.store(true, std::memory_order_release);
        cadence_qualification_state_.store(
            CadenceQualificationState::FAILED, std::memory_order_release);
        authority_failed_.store(true, std::memory_order_release);
        gpu_phase_.store(GpuPhase::FAILED, std::memory_order_release);
        renderer_mode_.store(RendererMode::PREPARING, std::memory_order_release);
        staged_nonce_.store(0, std::memory_order_release);
        stage_pin_active_.store(false, std::memory_order_release);
        block_input_and_presentation();
        stage_requested_ = false;
    }

    /** Single render-thread producer; fixed-size, non-blocking and no-overwrite. */
    bool evidence_capsules_drained() const {
        const std::uint64_t committed =
            frame_feedback_committed_sequence_.load(std::memory_order_acquire);
        const std::uint64_t consumed =
            frame_feedback_read_sequence_.load(std::memory_order_acquire);
        const std::uint64_t delivered =
            frame_feedback_delivered_sequence_.load(std::memory_order_acquire);
        return frame_feedback_write_sequence_ == committed &&
            committed == consumed && consumed == delivered;
    }

    bool queue_depth_mirrors_exact_locked(const char* where) {
        const int ready = static_cast<int>(gpu_ready_tiles_.size());
        const int native = static_cast<int>(native_outstanding_);
        const int publish = static_cast<int>(pending_publish_acks_.size());
        const int retire = static_cast<int>(retire_intents_.size());
        const int deletes = static_cast<int>(resource_deletes_.size());
        const int mirrored_ready = ready_tile_queue_depth_mirror_.load(
            std::memory_order_acquire);
        const int mirrored_native = native_outstanding_mirror_.load(
            std::memory_order_acquire);
        const int mirrored_publish = pending_publish_ack_mirror_.load(
            std::memory_order_acquire);
        const int mirrored_retire = retire_intent_depth_mirror_.load(
            std::memory_order_acquire);
        const int mirrored_deletes = resource_delete_depth_mirror_.load(
            std::memory_order_acquire);
        if (ready == mirrored_ready && native == mirrored_native &&
            publish == mirrored_publish && retire == mirrored_retire &&
            deletes == mirrored_deletes) {
            return true;
        }
        NTK_LOGE("fatal GPU depth mirror mismatch where=%s ready=%d/%d native=%d/%d publish=%d/%d retire=%d/%d deletes=%d/%d",
                 where, ready, mirrored_ready, native, mirrored_native,
                 publish, mirrored_publish, retire, mirrored_retire,
                 deletes, mirrored_deletes);
        fixed_causal_lane_fatal_ = true;
        engine_failed_.store(true, std::memory_order_release);
        authority_failed_.store(true, std::memory_order_release);
        gpu_phase_.store(GpuPhase::FAILED, std::memory_order_release);
        return false;
    }

    std::uint64_t reserve_evidence_capsule_sequence() {
        const std::uint64_t next = frame_feedback_write_sequence_ + 1;
        const std::uint64_t consumed = frame_feedback_read_sequence_.load(
            std::memory_order_acquire);
        if (next <= consumed || next - consumed > kFrameFeedbackRingSize) {
            fail_frame_feedback_capacity("reserve", next, consumed);
            return 0;
        }
        return next;
    }

    bool validate_post_apply_cut(
            const PreparedFrameWork& admitted,
            const ntk::present::SurfaceControlPresentBackend::
                SubmissionReceipt& receipt,
            const ntk::present::SurfaceControlPresentBackend::
                ConservationSnapshot& snapshot,
            bool external_claim_present,
            std::uint64_t* failure_mask) const {
        using Pool = ntk::present::HardwareBufferRenderTargetPool;
        using PreparedState = ntk::present::SurfaceControlPresentBackend::
            PreparedTransactionState;
        std::uint64_t failures = 0;
        const auto mark = [&failures](std::uint64_t bit, bool failed) {
            if (failed) failures |= bit;
        };
        mark(1ULL << 0,
            !receipt.submitted ||
            receipt.applyDisposition != ntk::present::
                SurfaceControlPresentBackend::ApplyDisposition::APPLIED ||
            receipt.identity.bufferSlot != admitted.buffer_slot ||
            receipt.identity.bufferGeneration != admitted.buffer_generation ||
            !ntk::present::validAppliedBufferRef(
                receipt.appliedBufferRef) ||
            !ntk::present::exactIdentity(
                receipt.appliedBufferRef.identity, receipt.identity));
        mark(1ULL << 1,
            admitted.buffer_slot >= snapshot.poolStates.size() ||
            snapshot.outstandingSubmissionCount < 1 ||
            snapshot.outstandingSubmissionCount > 8 ||
            snapshot.maxOutstandingSubmissionCount <
                snapshot.outstandingSubmissionCount ||
            snapshot.maxOutstandingSubmissionCount > 8 ||
            snapshot.callbackRecordDepth !=
                snapshot.outstandingSubmissionCount ||
            snapshot.maxCallbackRecordDepth !=
                snapshot.maxOutstandingSubmissionCount ||
            !ntk::present::postApplyLatchConjunctionDepthsExact(
                snapshot.callbackRecordDepth,
                snapshot.maxCallbackRecordDepth,
                snapshot.logicalUnlatchedNow,
                snapshot.maxLogicalUnlatched,
                snapshot.submittedWaitLatchCount,
                snapshot.commitProofPendingNow,
                snapshot.completeProofPendingNow,
                snapshot.maxCommitProofPending,
                snapshot.maxCompleteProofPending));
        mark(1ULL << 2,
            snapshot.latchedCurrentCount != 1 ||
            snapshot.releaseWaitCount > 7 ||
            snapshot.previousReleaseRecordDepth !=
                snapshot.releaseWaitCount ||
            snapshot.heldFrameworkRefCount > 7 ||
            snapshot.freeReusableCount < 1);
        mark(1ULL << 3,
            snapshot.acquireFenceRecordDepth > 8 ||
            snapshot.appOwnedAcquireFdCount !=
                snapshot.acquireFenceRecordDepth);
        // Count ranges are validated with the logical-unlatched ledger above. Keep this bit for
        // the independent callback retention relation only.
        mark(1ULL << 4,
            !ntk::present::postApplyCallbackRetentionExact(
                snapshot.retainedWaitingOnCompleteCount,
                snapshot.commitProofPendingNow,
                snapshot.completeProofPendingNow));
        mark(1ULL << 5,
            snapshot.backpressureEnableCount != 1 ||
            snapshot.backpressureDisableCount != 0 ||
            snapshot.capacityExhaustedCount != 0 ||
            snapshot.capacityWaitCount != 0);
        const bool target_state_exact =
            admitted.buffer_slot < snapshot.poolStates.size() &&
            snapshot.poolStates[admitted.buffer_slot] ==
                Pool::SlotState::FRAMEWORK_CHAIN_HEAD;
        mark(1ULL << 6,
            snapshot.backendInvariantFatalCount != 0 ||
            snapshot.preparedTransactionState != PreparedState::EMPTY ||
            external_claim_present ||
            snapshot.teardownReleaseEventSequence != 0 ||
            !target_state_exact);
        std::uint32_t free_count = 0;
        std::uint32_t chain_head_count = 0;
        std::uint32_t release_wait_count = 0;
        bool pool_state_exact = true;
        for (const auto state : snapshot.poolStates) {
            switch (state) {
                case Pool::SlotState::FREE:
                    ++free_count;
                    break;
                case Pool::SlotState::FRAMEWORK_CHAIN_HEAD:
                    ++chain_head_count;
                    break;
                case Pool::SlotState::FRAMEWORK_REPLACED_WAIT_RELEASE:
                    ++release_wait_count;
                    break;
                case Pool::SlotState::RENDERING:
                case Pool::SlotState::ACQUIRE_FENCE_EXPORTED:
                default:
                    pool_state_exact = false;
                    break;
            }
        }
        mark(1ULL << 7,
            !pool_state_exact ||
            chain_head_count != 1 ||
            chain_head_count != snapshot.latchedCurrentCount ||
            release_wait_count != snapshot.releaseWaitCount ||
            free_count != snapshot.freeReusableCount ||
            chain_head_count + release_wait_count !=
                snapshot.heldFrameworkRefCount ||
            free_count + chain_head_count + release_wait_count !=
                Pool::kSlotCount);
        const bool previousExpected =
            ntk::present::validAppliedBufferRef(
                receipt.previousAppliedBufferRef);
        mark(1ULL << 8,
            (!previousExpected &&
             (release_wait_count != 0 ||
              free_count != Pool::kSlotCount - 1)) ||
            (previousExpected &&
             (release_wait_count < 1 || release_wait_count > 7)));
        mark(1ULL << 9,
            snapshot.pendingFenceWatchCount +
                snapshot.activeFenceWatchCount >
                    release_wait_count + snapshot.acquireFenceRecordDepth);
        if (failure_mask != nullptr) *failure_mask = failures;
        return failures == 0;
    }

    bool commit_evidence_capsule(SubmittedEvidenceCapsule* capsule) {
        if (capsule == nullptr || capsule->capsuleSequence == 0) return false;
        const std::uint64_t next = frame_feedback_write_sequence_ + 1;
        const std::uint64_t consumed = frame_feedback_read_sequence_.load(
            std::memory_order_acquire);
        if (capsule->capsuleSequence != next || next <= consumed ||
            next - consumed > kFrameFeedbackRingSize) {
            // The exact O(1) slot was reserved before backend preparation.
            // Never allocate or overwrite after EGL_TRUE.
            fail_frame_feedback_capacity("commit", next, consumed);
            return false;
        }
        EvidenceCapsuleSlot& slot = evidence_capsule_ring_[
            (next - 1) % kFrameFeedbackRingSize];
        const std::uint64_t depth = next - consumed;
        capsule->evidenceCapsuleDepth = static_cast<int>(depth);
        std::uint64_t observed_max = evidence_capsule_max_depth_.load(
            std::memory_order_acquire);
        while (observed_max < depth &&
               !evidence_capsule_max_depth_.compare_exchange_weak(
                   observed_max, depth, std::memory_order_acq_rel,
                   std::memory_order_acquire)) {
        }
        capsule->evidenceCapsuleMaxDepth = static_cast<int>(
            evidence_capsule_max_depth_.load(std::memory_order_acquire));
        capsule->evidenceCapsuleInvalidFrames =
            evidence_capsule_invalid_count_.load(std::memory_order_acquire);
        slot.capsule = *capsule;
        slot.completed = {};
        slot.cadenceQualificationFailed = false;
        slot.latchTerminalState =
            ntk::present::LatchTerminalState::WAITING_EVENT;
        slot.retirementTerminalState =
            ntk::present::RetirementTerminalState::WAITING_EVENT;
        slot.latchEvent = {};
        slot.retirementEvent = {};
        slot.retirementCallbackObservedCount = 0;
        slot.transactionCompleteTerminal = false;
        slot.transactionCompleteExact = false;
        slot.transactionCompleteEventSequence = 0;
        slot.previousReleaseTerminal =
            !capsule->previousBufferExpected;
        slot.previousReleaseExact =
            !capsule->previousBufferExpected;
        slot.previousReleaseEventSequence = 0;
        slot.acquireFenceTerminal = false;
        slot.acquireFenceExact = false;
        slot.acquireFenceEventSequence = 0;
        slot.conservationProof = {};
        slot.conservationProof.postApplyExact =
            capsule->postApplyConservationExact;
        slot.conservationProof.previousReleaseTransitionExact =
            !capsule->previousBufferExpected;
        slot.completedSequence.store(0, std::memory_order_relaxed);
        slot.committedSequence.store(next, std::memory_order_release);
        frame_feedback_write_sequence_ = next;
        frame_feedback_committed_sequence_.store(next, std::memory_order_release);
        if (next - consumed >
            kFrameFeedbackRingSize - kFrameFeedbackEmergencyReserve) {
            fail_frame_feedback_capacity("emergency-reserve", next, consumed);
        }
        return true;
    }

    EvidenceCapsuleSlot* evidence_capsule_slot(std::uint64_t sequence) {
        if (sequence == 0 || sequence > frame_feedback_write_sequence_) {
            return nullptr;
        }
        EvidenceCapsuleSlot& slot = evidence_capsule_ring_[
            (sequence - 1) % kFrameFeedbackRingSize];
        return slot.committedSequence.load(std::memory_order_acquire) == sequence
            ? &slot : nullptr;
    }

    bool complete_evidence_capsule_if_joined(std::uint64_t sequence) {
        EvidenceCapsuleSlot* slot = evidence_capsule_slot(sequence);
        if (slot == nullptr ||
            slot->latchTerminalState ==
                ntk::present::LatchTerminalState::WAITING_EVENT ||
            slot->retirementTerminalState ==
                ntk::present::RetirementTerminalState::WAITING_EVENT ||
            !slot->transactionCompleteTerminal ||
            !slot->previousReleaseTerminal ||
            !slot->acquireFenceTerminal ||
            slot->completedSequence.load(std::memory_order_acquire) != 0) {
            return slot != nullptr;
        }
        const auto& identity = slot->capsule.identity;
        const auto& retirement = slot->retirementEvent;
        const bool exact_latch =
            slot->latchTerminalState ==
                ntk::present::LatchTerminalState::LATCHED &&
            ntk::present::FixedPresentJoinState::isExactLatch(
                identity, slot->latchEvent);
        const bool exact_retirement =
            slot->retirementTerminalState ==
                ntk::present::RetirementTerminalState::RETIRED &&
            ntk::present::FixedPresentJoinState::isExactRetirement(
                identity, retirement);
        const auto& conservation = slot->conservationProof;
        const bool exact_frame_local_conservation =
            conservation.postApplyExact &&
            conservation.latchTransitionExact &&
            conservation.completionTransitionExact &&
            conservation.previousReleaseTransitionExact &&
            conservation.acquireFenceTransitionExact &&
            conservation.latchTransitionCount == 1 &&
            conservation.completionTransitionCount == 1 &&
            conservation.previousReleaseTransitionCount ==
                (slot->capsule.previousBufferExpected ? 1U : 0U) &&
            conservation.acquireFenceTransitionCount == 1;
        bool qualified = exact_latch && exact_retirement &&
            exact_frame_local_conservation &&
            slot->retirementCallbackObservedCount == 1 &&
            slot->transactionCompleteExact && slot->previousReleaseExact &&
            slot->acquireFenceExact;
        if (slot->capsule.prepared.terminal) {
            const bool terminalJoined = fixed_scheduler_.markTerminalJoined(
                slot->capsule.prepared.gesture_generation,
                slot->capsule.prepared.terminal_input_sequence,
                slot->capsule.prepared.work_generation);
            if (!terminalJoined) {
                NTK_LOGE(
                    "fatal terminal physical join ledger gesture=%llu input=%llu work=%llu",
                    static_cast<unsigned long long>(
                        slot->capsule.prepared.gesture_generation),
                    static_cast<unsigned long long>(
                        slot->capsule.prepared.terminal_input_sequence),
                    static_cast<unsigned long long>(
                        slot->capsule.prepared.work_generation));
                qualified = false;
                authority_failed_.store(true, std::memory_order_release);
                gpu_phase_.store(GpuPhase::FAILED, std::memory_order_release);
            } else {
                publish_terminal_progress();
            }
            refresh_input_phase();
        }
        if (slot->capsule.workGeneration >= 45) {
            NTK_LOGI(
                "qualification timing join work=%llu admission=%llu "
                "gesture=%llu input=%llu latch=%lld retire=%lld complete=%llu",
                static_cast<unsigned long long>(
                    slot->capsule.workGeneration),
                static_cast<unsigned long long>(
                    slot->capsule.identity.admissionSequence),
                static_cast<unsigned long long>(
                    slot->capsule.prepared.gesture_generation),
                static_cast<unsigned long long>(
                    slot->capsule.prepared.terminal_input_sequence),
                static_cast<long long>(slot->latchEvent.callbackObservedNanos),
                static_cast<long long>(
                    slot->retirementEvent.callbackPublishedNanos),
                static_cast<unsigned long long>(
                    slot->transactionCompleteEventSequence));
        }
        if (!qualified) {
            slot->cadenceQualificationFailed = true;
            cadence_qualification_failed_.store(true,
                                                std::memory_order_release);
            cadence_qualification_state_.store(
                CadenceQualificationState::FAILED,
                std::memory_order_release);
        }
        if (slot->capsule.prepared.stage_candidate) {
            if (!qualified ||
                cadence_qualification_failed_.load(
                    std::memory_order_acquire)) {
                NTK_LOGE(
                    "stage join rejected sequence=%llu exactRetirement=%d "
                    "targetRetired=%d latchState=%d latchNs=%lld cadenceFailed=%d "
                    "nonce=%lld phase=%d",
                    static_cast<unsigned long long>(sequence),
                    exact_retirement ? 1 : 0, exact_retirement ? 1 : 0,
                    static_cast<int>(slot->latchTerminalState),
                    static_cast<long long>(slot->latchEvent.latchNanos),
                    cadence_qualification_failed_.load(std::memory_order_acquire) ? 1 : 0,
                    static_cast<long long>(slot->capsule.gpu.stageNonce),
                    static_cast<int>(gpu_phase_.load(std::memory_order_acquire)));
                authority_failed_.store(true, std::memory_order_release);
                gpu_phase_.store(GpuPhase::FAILED,
                                 std::memory_order_release);
            } else {
                GpuPhase expected_phase = GpuPhase::SEALING;
                if (!gpu_phase_.compare_exchange_strong(
                        expected_phase, GpuPhase::INPUT_ARMED,
                        std::memory_order_acq_rel,
                        std::memory_order_acquire)) {
                    authority_failed_.store(true,
                                            std::memory_order_release);
                    gpu_phase_.store(GpuPhase::FAILED,
                                     std::memory_order_release);
                    slot->cadenceQualificationFailed = true;
                } else {
                    stage_latch_ns_.store(
                        slot->latchEvent.latchNanos,
                        std::memory_order_release);
                    staged_nonce_.store(
                        slot->capsule.gpu.stageNonce,
                        std::memory_order_release);
                    renderer_mode_.store(RendererMode::ARMED,
                                         std::memory_order_release);
                    block_input_and_presentation();
                    cadence_qualification_state_.store(
                        CadenceQualificationState::QUALIFIED_IDLE,
                        std::memory_order_release);
                    NTK_LOGI(
                        "stage join armed sequence=%llu work=%llu nonce=%lld "
                        "latchNs=%lld wakeNs=%lld",
                        static_cast<unsigned long long>(sequence),
                        static_cast<unsigned long long>(
                            slot->capsule.workGeneration),
                        static_cast<long long>(slot->capsule.gpu.stageNonce),
                        static_cast<long long>(slot->latchEvent.latchNanos),
                        static_cast<long long>(
                            retirement.callbackPublishedNanos));
                }
            }
        }
        slot->completed.capsule = slot->capsule;
        slot->completed.latchEvent = slot->latchEvent;
        slot->completed.retirementEvent = slot->retirementEvent;
        slot->completed.conservation =
            slot->capsule.postApplyConservation;
        slot->completed.transactionCompleteEventSequence =
            slot->transactionCompleteEventSequence;
        slot->completed.previousReleaseEventSequence =
            slot->previousReleaseEventSequence;
        slot->completed.acquireFenceEventSequence =
            slot->acquireFenceEventSequence;
        slot->completed.retirementCallbackObservedCount =
            slot->retirementCallbackObservedCount;
        slot->completed.externalClaimPresent =
            slot->capsule.postApplyExternalClaimPresent;
        slot->completed.qualified = qualified &&
            !slot->cadenceQualificationFailed;
        {
            // Pair predicate publication with the consumer mutex so the event-driven feedback
            // wait cannot lose the single notification between predicate inspection and sleep.
            std::lock_guard<std::mutex> lock(feedback_mutex_);
            slot->completedSequence.store(sequence, std::memory_order_release);
        }
        feedback_ready_.notify_one();
        return true;
    }

    void enqueue_tile_resident(const GpuReadyTile& tile,
                               std::int64_t scene_version, bool success) {
        FeedbackRecord record;
        record.kind = FeedbackKind::TILE_RESIDENT;
        record.engine_generation = engine_generation_;
        record.authority_generation = tile.authority_generation;
        record.key = tile.key;
        // Prepared callbacks are scoped by immutable preparation generation. Surface identity
        // is published separately by the exact adoption transaction.
        record.surface_epoch = tile.preparation_generation;
        record.admission_id = tile.admission_id;
        record.resource_revision = tile.resource_revision;
        record.install_lease = tile.install_lease;
        record.rgba_bytes = tile.rgba_bytes;
        record.value = scene_version;
        record.success = success;
        enqueue_feedback(record);
    }

    void enqueue_prepared_tile_resident(const GpuReadyTile& tile, bool success) {
        FeedbackRecord record;
        record.kind = FeedbackKind::PREPARED_TILE_RESIDENT;
        record.engine_generation = engine_generation_;
        record.authority_generation = tile.authority_generation;
        record.key = tile.key;
        // Java keys every prepared-install callback by the immutable preparation
        // generation.  A detached install has surface_epoch == 0, so forwarding the
        // surface field here silently orphaned the callback and permanently held the
        // machine's single native upload credit.
        record.surface_epoch = tile.preparation_generation;
        record.admission_id = tile.admission_id;
        record.resource_revision = tile.resource_revision;
        record.install_lease = tile.install_lease;
        record.rgba_bytes = tile.rgba_bytes;
        record.tile_proof_digest = tile.tile_proof_digest;
        record.resident_inventory_digest = tile.resident_inventory_digest;
        record.resource_completion_ns = tile.resource_completion_ns;
        record.pre_geometry = tile.pre_geometry;
        record.success = success;
        enqueue_feedback(record);
    }

    void enqueue_protection_committed(const ProtectionCommit& commit,
                                      std::int64_t scene_version, bool success) {
        FeedbackRecord record;
        record.kind = FeedbackKind::PROTECTION_COMMITTED;
        record.engine_generation = engine_generation_;
        record.authority_generation = commit.authority_generation;
        record.key.authority = commit.authority;
        record.surface_epoch = commit.surface_epoch;
        record.demand_epoch = commit.demand_epoch;
        record.protected_digest = commit.protected_digest;
        record.value = scene_version;
        record.success = success;
        enqueue_feedback(record);
    }

    void enqueue_retire_result(const RetireIntent& intent, RetireResultCode result,
                               std::int64_t scene_version,
                               std::int64_t fence_serial = 0) {
        FeedbackRecord record;
        record.kind = FeedbackKind::RETIRE_RESULT;
        record.engine_generation = engine_generation_;
        record.authority_generation = intent.authority_generation;
        record.key = intent.key;
        record.surface_epoch = intent.surface_epoch;
        record.policy_surface_epoch = intent.policy_surface_epoch;
        record.demand_epoch = intent.demand_epoch;
        record.resource_revision = intent.resource_revision;
        record.install_lease = intent.install_lease;
        record.retire_lease = intent.retire_lease;
        record.rgba_bytes = intent.rgba_bytes;
        record.protected_digest = intent.protected_digest;
        record.retire_result = result;
        record.value = scene_version;
        record.fence_serial = fence_serial;
        record.success = result == RetireResultCode::DETACHED;
        enqueue_feedback(record);
    }

    void enqueue_tile_freed(const PendingResourceDelete& retired, bool success) {
        FeedbackRecord record;
        record.kind = FeedbackKind::TILE_FREED;
        record.engine_generation = engine_generation_;
        record.authority_generation = retired.authority_generation;
        record.key = retired.key;
        record.surface_epoch = retired.surface_epoch;
        record.policy_surface_epoch = retired.policy_surface_epoch;
        record.demand_epoch = retired.demand_epoch;
        record.admission_id = retired.admission_id;
        record.resource_revision = retired.resource_revision;
        record.install_lease = retired.install_lease;
        record.retire_lease = retired.retire_lease;
        record.rgba_bytes = retired.rgba_bytes;
        record.protected_digest = retired.protected_digest;
        record.value = monotonic_now_ns();
        record.success = success;
        enqueue_feedback(record);
    }

    void enqueue_pre_submit_viewport_gap(
            std::int64_t authority_generation, std::int64_t authority,
            std::int64_t surface_epoch,
            std::int64_t count) {
        FeedbackRecord record;
        record.kind = FeedbackKind::PRE_SUBMIT_VIEWPORT_GAP;
        record.engine_generation = engine_generation_;
        record.authority_generation = authority_generation;
        record.key.authority = authority;
        record.surface_epoch = surface_epoch;
        record.value = count;
        enqueue_feedback(record);
    }

    void flush_feedback() {
        if (!feedback_thread_.joinable()) return;
        const std::uint64_t frame_target = frame_feedback_committed_sequence_.load(
            std::memory_order_acquire);
        const std::uint64_t barrier = ++feedback_barrier_requested_;
        FeedbackRecord record;
        record.kind = FeedbackKind::BARRIER;
        record.barrier_sequence = barrier;
        record.frame_target_sequence = frame_target;
        enqueue_feedback(record);
        std::unique_lock<std::mutex> lock(feedback_mutex_);
        feedback_barrier_condition_.wait(lock, [&] {
            return feedback_exit_requested_ ||
                (feedback_barrier_completed_ >= barrier &&
                 frame_feedback_delivered_sequence_.load(std::memory_order_acquire) >=
                    frame_target);
        });
    }



    bool handle_jni_callback_exception(
            JNIEnv* env, const char* callbackName,
            bool poisonAuthority, bool poisonEngine) {
        if (env == nullptr || !env->ExceptionCheck()) return false;
        NTK_LOGE("JNI callback threw callback=%s", callbackName);
        env->ExceptionDescribe();
        env->ExceptionClear();
        if (poisonAuthority) {
            authority_failed_.store(true, std::memory_order_release);
        }
        if (poisonEngine) {
            engine_failed_.store(true, std::memory_order_release);
        }
        return true;
    }

    class EvidenceWriter {
       public:
        void put32(std::uint32_t value) {
            for (int shift = 0; shift < 32; shift += 8) {
                bytes_.push_back(static_cast<std::uint8_t>(value >> shift));
            }
        }
        void put64(std::uint64_t value) {
            for (int shift = 0; shift < 64; shift += 8) {
                bytes_.push_back(static_cast<std::uint8_t>(value >> shift));
            }
        }
        void putSigned(std::int64_t value) {
            put64(static_cast<std::uint64_t>(value));
            ++signedCount_;
        }
        const std::vector<std::uint8_t>& bytes() const { return bytes_; }
        std::size_t signedCount() const { return signedCount_; }

       private:
        std::vector<std::uint8_t> bytes_;
        std::size_t signedCount_ = 0;
    };

    std::vector<std::uint8_t> encode_frame_evidence_v11(
            const CompletedFrameEvidence& evidence,
            const FrameFeedback& frame) const {
        const auto& capsule = evidence.capsule;
        const auto& phase = capsule.phase;
        const auto& gpu = capsule.gpu;
        const auto& identity = capsule.identity;
        const auto& latch = evidence.latchEvent;
        const auto& retirement = evidence.retirementEvent;
        EvidenceWriter writer;
        writer.put32(0x414b544eU);  // NTKA: NTK schema 11.
        writer.put32(11U);
        writer.put32(311U);
        writer.put32(evidence.qualified ? 1U : 0U);
        const auto put = [&](std::int64_t value) { writer.putSigned(value); };
        put(frame.engine_generation);
        put(frame.authority_generation);
        put(frame.authority);
        put(static_cast<std::int64_t>(frame.surface_epoch));
        put(static_cast<std::int64_t>(capsule.workGeneration));
        put(static_cast<std::int64_t>(capsule.frameId));
        put(static_cast<std::int64_t>(capsule.frameSequence));
        put(static_cast<std::int64_t>(capsule.admissionSequence));
        put(static_cast<std::int64_t>(capsule.capsuleSequence));
        put(frame.scene_version);
        put(frame.scroll_top);
        std::uint32_t velocityBits = 0;
        std::memcpy(&velocityBits, &frame.velocity_px_per_second,
                    sizeof(velocityBits));
        put(static_cast<std::int64_t>(velocityBits));
        put(frame.predicted_stop_px);
        put(frame.continuous_start);
        put(frame.continuous_end);
        put(frame.visible_start);
        put(frame.visible_end);
        put(frame.first_visible_page);
        put(frame.last_visible_page);
        put(frame.first_visible_gap);
        put(frame.viewport_original_complete ? 1 : 0);
        put(frame.runway_original_complete ? 1 : 0);
        put(frame.gesture_id);
        put(frame.applied_input_sequence);
        put(frame.input_oldest_ns);
        put(frame.input_newest_ns);
        put(frame.main_ingress_oldest_ns);
        put(frame.main_ingress_newest_ns);
        put(frame.receipt_oldest_ns);
        put(frame.receipt_newest_ns);
        put(frame.mutation_oldest_ns);
        put(frame.mutation_newest_ns);
        put(frame.draw_begin_ns);
        put(phase.preSubmitNanos);
        put(capsule.transactionApplyBeginNanos);
        put(capsule.transactionApplyEndNanos);
        put(phase.postSubmitNanos);
        put(retirement.targetReachedNanos);
        put(latch.latchNanos);
        put(static_cast<std::int64_t>(latch.eventSequence));
        put(static_cast<std::int64_t>(retirement.retirementSequence));
        put(gpu.controlBacklogMax);
        put(gpu.moveMailboxWrites);
        put(gpu.integratedTiles);
        put(gpu.uploadCommandsSubmitting);
        put(gpu.uploadGpuFencesPending);
        put(gpu.gpuPhase);
        put(gpu.sealedScene ? 1 : 0);
        put(static_cast<std::int64_t>(gpu.resourceSubmitSerial));
        put(static_cast<std::int64_t>(gpu.sealedResourceSubmitSerial));
        put(gpu.readyTileQueueDepth);
        put(gpu.nativePublicationsOutstanding);
        put(gpu.pendingPublishAcks);
        put(gpu.retireQueueDepth);
        put(gpu.retirementCount);
        put(gpu.uploadContextAlive ? 1 : 0);
        put(gpu.lastGpuResourceCompletionNanos);
        put(gpu.sealFenceCompletionNanos);
        put(gpu.uploadContextDestroyedNanos);
        put(latch.latchNanos);
        put(gpu.firstDownIngressNanos);
        put(gpu.sealedSceneVersion);
        put(gpu.resourceWorkerState);
        put(static_cast<std::int64_t>(gpu.resourceWorkerGeneration));
        put(static_cast<std::int64_t>(gpu.resourceWorkerCreateCount));
        put(static_cast<std::int64_t>(gpu.resourceWorkerDestroyCount));
        put(gpu.activeResourceWorkerCount);
        put(gpu.activeUploadContextCount);
        put(static_cast<std::int64_t>(gpu.sceneMutationCountSinceSeal));
        put(gpu.offscreenWarmFenceCompletionNanos);
        put(gpu.predecessorPhysicalCompleteNanos);
        put(static_cast<std::int64_t>(gpu.sealBarrierSerial));
        put(gpu.stageBackbufferReadyNanos);
        put(static_cast<std::int64_t>(gpu.offscreenWarmDrawCount));
        put(frame.frame_work_kind);
        put(static_cast<std::int64_t>(gpu.plannerInvocationCount));
        put(static_cast<std::int64_t>(gpu.backendPresentPrepareCount));
        put(static_cast<std::int64_t>(gpu.swapAttemptCount));
        put(static_cast<std::int64_t>(gpu.slotClosedNoAttemptCount));
        put(static_cast<std::int64_t>(gpu.terminalSwapCount));
        put(static_cast<std::int64_t>(gpu.preparedDrawCount));
        put(static_cast<std::int64_t>(gpu.preparedFrameIdReservationCount));
        put(phase.admissionConsumed != 0 ? 1 : 0);
        put(phase.schemaVersion);
        put(capsule.exactPhaseTelemetry ? 1 : 0);
        put(phase.fatalReason);
        put(phase.planValid);
        put(phase.refreshPeriodNanos);
        put(phase.decisionNanos);
        put(phase.plannedPresentationNanos);
        put(phase.missedPresentationNanos);
        put(phase.latestSwapStartExclusiveNanos);
        put(phase.plannedTargetFrame);
        put(capsule.gpuReadyProof.renderBeginNanos);
        put(capsule.gpuReadyProof.renderEndNanos);
        put(capsule.gpuReadyProof.acquireFenceIssuedNanos);
        put(capsule.gpuReadyProof.acquireFenceExportReturnNanos);
        put(static_cast<std::int64_t>(identity.backendSurfaceSerial));
        put(static_cast<std::int64_t>(identity.transactionSerial));
        put(static_cast<std::int64_t>(identity.bufferSlot));
        put(static_cast<std::int64_t>(identity.bufferGeneration));
        put(identity.frameTimelineVsyncId);
        put(capsule.setBufferCount);
        put(capsule.transactionApplyCount);
        put(capsule.onCommitCallbackCount);
        put(capsule.onCompleteCallbackCount);
        put(capsule.latchSource);
        put(capsule.latchCallbackObservedNanos);
        put(retirement.callbackPublishedNanos);
        put(retirement.targetWaitCount);
        put(retirement.targetRebaseCount);
        put(capsule.prepared.stage_candidate ? 1 : 0);
        put(gpu.stageNonce);
        put(gpu.stageCorridorStart);
        put(gpu.stageCorridorEnd);
        put(capsule.evidenceCapsuleDepth);
        put(capsule.evidenceCapsuleMaxDepth);
        put(static_cast<std::int64_t>(capsule.evidenceCapsuleInvalidFrames));
        put(evidence.qualified ? 0 : 1);
        put(retirement.state);
        put(retirement.fatalReason);
        put(static_cast<std::int64_t>(phase.physicalCallbackSequence));
        put(static_cast<std::int64_t>(phase.candidateSequence));
        put(static_cast<std::int64_t>(phase.candidateRawSequence));
        put(phase.candidateCaptureNanos);
        put(phase.candidateClaimNanos);
        put(phase.refreshIssued);
        put(phase.refreshDelivered);
        put(static_cast<std::int64_t>(phase.refreshPhysicalCallbackSequence));
        put(static_cast<std::int64_t>(phase.refreshCapturedRawSequence));
        put(static_cast<std::int64_t>(phase.priorRetirementSequence));
        put(static_cast<std::int64_t>(phase.externalWorkGeneration));
        put(static_cast<std::int64_t>(phase.externalNtkFrameId));
        put(static_cast<std::int64_t>(phase.sequence));
        put(static_cast<std::int64_t>(phase.opportunitySequence));
        put(static_cast<std::int64_t>(phase.reservationSequence));
        put(phase.opportunityReceiptNanos);
        put(phase.opportunityPublishNanos);
        put(phase.rendererCallbackObservedNanos);
        put(static_cast<std::int64_t>(phase.retirementDemandIssued));
        put(static_cast<std::int64_t>(phase.retirementDemandSatisfied));
        put(static_cast<std::int64_t>(phase.retirementDemandCancelled));
        put(static_cast<std::int64_t>(phase.opportunityDemandIssued));
        put(static_cast<std::int64_t>(phase.opportunityDemandSatisfied));
        put(static_cast<std::int64_t>(phase.opportunityDemandCancelled));
        put(static_cast<std::int64_t>(phase.supersededBeforeClaimCount));
        put(static_cast<std::int64_t>(phase.closedOpportunityCount));
        put(static_cast<std::int64_t>(phase.transportProfileDigest));
        put(static_cast<std::int64_t>(phase.timingGeneration));
        put(phase.transportBoundNanos);
        put(phase.initialDecisionNanos);
        put(phase.case1CutoffNanos);
        put(phase.case2PhaseOpenNanos);
        put(phase.case2GateNanos);
        put(phase.case2CutoffNanos);
        put(phase.case2LatestStartExclusiveNanos);
        put(phase.case1LatestSafeDecisionNanos);
        put(phase.initialTransportAdmissionOutcome);
        put(phase.phaseWaitCount);
        put(phase.case2GateWaitTargetNanos);
        put(phase.case2GateWaitReturnNanos);
        put(phase.finalDecisionNanos);
        put(phase.claimIssuedCount);
        put(phase.transactionPrepareBeginNanos);
        put(phase.transactionPrepareEndNanos);
        put(phase.decisionToClaimReturnNanos);
        put(phase.applyCallDurationNanos);
        put(phase.decisionToApplyEndNanos);
        put(phase.transportBoundSlackNanos);
        put(phase.cutoffSlackNanos);
        put(phase.setFrameTimelineCount);
        put(phase.applyDisposition);
        put(phase.phaseFatalReason);
        put(phase.receiptFatalReason);
        put(phase.retirementFatalReason);
        // The submission receipt predates asynchronous target retirement and
        // legitimately carries zero.  The joined capsule counts the exact
        // terminal callback at receipt; a duplicate makes the join fatal.
        put(evidence.retirementCallbackObservedCount);
        put(evidence.conservation.outstandingSubmissionCount);
        put(evidence.conservation.maxOutstandingSubmissionCount);
        put(static_cast<std::int64_t>(
            evidence.conservation.preparedTransactionState));
        put(evidence.externalClaimPresent ? 1 : 0);
        for (const auto state : evidence.conservation.poolStates) {
            put(static_cast<std::int64_t>(state));
        }
        put(evidence.conservation.pendingFenceWatchCount);
        put(evidence.conservation.activeFenceWatchCount);
        put(static_cast<std::int64_t>(
            evidence.transactionCompleteEventSequence));
        put(capsule.previousBufferExpected ? 1 : 0);
        put(static_cast<std::int64_t>(
            evidence.previousReleaseEventSequence));
        put(static_cast<std::int64_t>(
            evidence.conservation.teardownReleaseEventSequence));
        put(capsule.acquireFenceSignalNanos);
        put(static_cast<std::int64_t>(
            capsule.acquireFenceEventSequence));
        put(static_cast<std::int64_t>(capsule.acquireFenceSerial));
        put(capsule.gpuReadyProof.acquireFenceDupCount);
        put(phase.frameworkTransferCount);
        put(capsule.proofFdCloseCount);
        put(capsule.gpuReadyProof.rendererGpuClientWaitCount);
        put(capsule.applyBeforeAcquireSignalProven ? 1 : 0);
        put(static_cast<std::int64_t>(
            capsule.prepared.visual_demand_epoch));
        put(static_cast<std::int64_t>(
            capsule.prepared.visual_mutation_serial));
        put(capsule.prepared.visible_state_changed ? 1 : 0);
        put(evidence.conservation.callbackRecordDepth);
        put(evidence.conservation.maxCallbackRecordDepth);
        put(evidence.conservation.previousReleaseRecordDepth);
        put(evidence.conservation.acquireFenceRecordDepth);
        put(evidence.conservation.appOwnedAcquireFdCount);
        put(evidence.conservation.commitProofPendingNow);
        put(evidence.conservation.completeProofPendingNow);
        put(evidence.conservation.maxCommitProofPending);
        put(evidence.conservation.maxCompleteProofPending);
        put(evidence.conservation.heldFrameworkRefCount);
        put(evidence.conservation.maxHeldFrameworkRefCount);
        put(evidence.conservation.freeReusableCount);
        put(evidence.conservation.minFreeReusableCount);
        put(evidence.conservation.appOwnedBufferDomainNow);
        put(evidence.conservation.minAppOwnedBufferDomain);
        put(static_cast<std::int64_t>(
            evidence.conservation.backpressureEnableCount));
        put(static_cast<std::int64_t>(
            evidence.conservation.backpressureDisableCount));
        put(static_cast<std::int64_t>(
            evidence.conservation.capacityExhaustedCount));
        put(static_cast<std::int64_t>(
            evidence.conservation.capacityWaitCount));
        put(static_cast<std::int64_t>(
            evidence.conservation.backendInvariantFatalCount));
        put(static_cast<std::int64_t>(
            evidence.conservation.applyBeforePriorCompleteCount));
        put(evidence.conservation.lastLatchConsumedToSuccessorApplyNanos);
        put(evidence.conservation.lastSuccessorApplyMinusPriorCompleteNanos);
        put(evidence.conservation.lastSuccessorReadyMinusPriorCompleteNanos);
        put(capsule.targetUnretiredNow);
        put(capsule.targetUnretiredMax);
        put(capsule.preparedProducerNow);
        put(capsule.preparedProducerMax);
        put(phase.priorLatchGateRequired);
        put(phase.priorLatchGateUsed);
        put(phase.priorLatchWaitCount);
        put(phase.priorLatchObservationState);
        put(phase.priorCommitProofPendingAtClaim);
        put(phase.priorRetirementProofPresent);
        const auto& prior = phase.priorRetirementProof;
        const auto& predecessor = prior.predecessor;
        const auto& predecessorIdentity = predecessor.identity;
        put(static_cast<std::int64_t>(prior.retirementSequence));
        put(static_cast<std::int64_t>(
            predecessor.appliedBufferRefSerial));
        put(static_cast<std::int64_t>(
            predecessorIdentity.engineGeneration));
        put(static_cast<std::int64_t>(
            predecessorIdentity.surfaceEpoch));
        put(predecessorIdentity.authorityGeneration);
        put(predecessorIdentity.authority);
        put(static_cast<std::int64_t>(
            predecessorIdentity.workGeneration));
        put(static_cast<std::int64_t>(
            predecessorIdentity.ntkFrameId));
        put(static_cast<std::int64_t>(
            predecessorIdentity.frameSequence));
        put(static_cast<std::int64_t>(
            predecessorIdentity.admissionSequence));
        put(static_cast<std::int64_t>(
            predecessorIdentity.capsuleSequence));
        put(static_cast<std::int64_t>(
            predecessorIdentity.backendSurfaceSerial));
        put(static_cast<std::int64_t>(
            predecessorIdentity.transactionSerial));
        put(static_cast<std::int64_t>(
            predecessorIdentity.bufferSlot));
        put(static_cast<std::int64_t>(
            predecessorIdentity.bufferGeneration));
        put(predecessorIdentity.frameTimelineVsyncId);
        put(prior.targetReachedNanos);
        put(prior.retirementCompleteNanos);
        put(prior.proofCommittedNanos);
        put(static_cast<std::int64_t>(
            prior.targetAuthorityRawSequence));
        put(static_cast<std::int64_t>(
            prior.targetPhysicalCallbackSequence));
        put(prior.plannedTargetFrame);
        put(prior.originalTargetFrame);
        put(prior.targetWaitCount);
        put(prior.targetRebaseCount);
        put(prior.retirementCallbackPublishCount);
        put(prior.state);
        put(prior.fatalReason);
        put(static_cast<std::int64_t>(
            phase.priorLatchObservation.latchEventSequence));
        put(phase.priorLatchObservation.compositorLatchNanos);
        put(phase.priorLatchObservation.callbackObservedNanos);
        put(phase.priorLatchObservation.source);
        put(phase.priorLatchObservation.onCommitCallbackCount);
        put(static_cast<std::int64_t>(
            capsule.previousAppliedBufferRef.serial));
        put(static_cast<std::int64_t>(
            capsule.appliedBufferRef.serial));
        put(phase.priorRetirementProofPresent != 0
                ? capsule.transactionApplyBeginNanos -
                    prior.retirementCompleteNanos
                : 0);
        put(evidence.conservation.lastLatchConsumedToSuccessorApplyNanos);
        put(phase.priorLatchObservationState ==
                SWAPPY_FIXED_PRIOR_LATCH_OBSERVED_AT_CLAIM
                ? 1
                : 0);
        put(capsule.prepared.common_reservation_ns);
        put(capsule.prepared.draw_begin_ns);
        put(static_cast<std::int64_t>(
            capsule.prepared.raw_baseline_sequence));
        put(static_cast<std::int64_t>(
            phase.rawAuthoritySequence));
        put(capsule.prepared.common_reservation_ns > 0 &&
                capsule.prepared.common_reservation_ns <=
                    capsule.prepared.draw_begin_ns
                ? 1
                : 0);
        // V11 appendix. V10's canonical 282-field prefix remains ordered and
        // immutable; retirement+latch JOIN and bounded overlap evidence follow.
        put(static_cast<std::int64_t>(
            capsule.postSubmitSuccessfulCount));
        put(static_cast<std::int64_t>(
            capsule.postSubmitLatchedProofCount));
        put(static_cast<std::int64_t>(
            capsule.postSubmitTerminalLostProofCount));
        put(static_cast<std::int64_t>(
            capsule.postSubmitLogicalUnlatchedNow));
        put(static_cast<std::int64_t>(
            capsule.postSubmitMaxLogicalUnlatched));
        put(static_cast<std::int64_t>(
            capsule.postApplyFatalBranch));
        // V11 structural invariant: the renderer installs the frame's
        // compositor-latch callback before publishing that frame to Swappy.
        // This records callback ordering only; it does not claim that
        // own-frame OnCommit gated JOIN_OPEN.
        put(1);
        put(phase.priorLatchGateRequired);
        put(phase.priorLatchGateUsed);
        put(phase.priorLatchWaitCount);
        put(phase.priorLatchObservationState);
        put(static_cast<std::int64_t>(
            phase.priorLatchObservation.latchEventSequence));
        put(phase.priorLatchObservation.compositorLatchNanos);
        put(phase.priorLatchObservation.callbackObservedNanos);
        put(phase.joinOpenNanos);
        put(phase.priorLatchObservationState ==
                    SWAPPY_FIXED_PRIOR_LATCH_OBSERVED_AT_CLAIM
                ? phase.joinOpenNanos -
                    phase.priorLatchObservation.callbackObservedNanos
                : 0);
        put(phase.priorCommitProofPendingAtClaim);
        put(static_cast<std::int64_t>(
            evidence.conservation.latestAppliedBufferRefSerial));
        put(static_cast<std::int64_t>(
            evidence.conservation.latestConsumedLatchRefSerial));
        put(evidence.conservation.logicalUnlatchedNow);
        put(evidence.conservation.maxLogicalUnlatched);
        put(evidence.conservation.submittedWaitLatchCount);
        put(evidence.conservation.commitProofPendingNow);
        put(evidence.conservation.maxCommitProofPending);
        put(evidence.conservation.completeProofPendingNow);
        put(static_cast<std::int64_t>(
            evidence.conservation.applyBeforePriorCommitConsumedCount));
        put(static_cast<std::int64_t>(
            evidence.conservation.applyBeforePriorCompleteCount));
        put(evidence.conservation.lastLatchConsumedToSuccessorApplyNanos);
        put(evidence.conservation.priorOnCompletePendingAtSuccessorApply);
        if (writer.signedCount() != 311U) {
            NTK_LOGE(
                "fatal schema11 evidence field count=%zu expected=311",
                writer.signedCount());
            return {};
        }
        return writer.bytes();
    }

    bool call_frame_evidence_v11(
            JNIEnv* env, const CompletedFrameEvidence& evidence,
            const FrameFeedback& frame) {
        if (env == nullptr || callback_ == nullptr ||
            on_frame_evidence_v11_ == nullptr) return false;
        const std::vector<std::uint8_t> payload =
            encode_frame_evidence_v11(evidence, frame);
        jbyteArray bytes = env->NewByteArray(
            static_cast<jsize>(payload.size()));
        if (bytes == nullptr || env->ExceptionCheck()) {
            handle_jni_callback_exception(
                env, "onNativeFrameEvidenceV11.allocate", true, true);
            return false;
        }
        env->SetByteArrayRegion(
            bytes, 0, static_cast<jsize>(payload.size()),
            reinterpret_cast<const jbyte*>(payload.data()));
        if (!env->ExceptionCheck()) {
            env->CallVoidMethod(callback_, on_frame_evidence_v11_, bytes);
        }
        env->DeleteLocalRef(bytes);
        return !handle_jni_callback_exception(
            env, "onNativeFrameEvidenceV11", true, true);
    }

    bool call_stage_latched_v2(
            JNIEnv* env, const CompletedFrameEvidence& evidence,
            const FrameFeedback& frame) {
        if (!frame.stage_candidate) return true;
        if (env == nullptr || callback_ == nullptr ||
            on_stage_latched_v2_ == nullptr) return false;
        const auto& capsule = evidence.capsule;
        const auto& gpu = capsule.gpu;
        jstring digest = env->NewStringUTF(gpu.sceneDigest.c_str());
        if (digest == nullptr || env->ExceptionCheck()) {
            handle_jni_callback_exception(
                env, "onNativeStageLatchedV2.allocate", true, true);
            return false;
        }
        env->CallVoidMethod(
            callback_, on_stage_latched_v2_,
            static_cast<jlong>(capsule.identity.engineGeneration),
            static_cast<jlong>(capsule.identity.surfaceEpoch),
            static_cast<jlong>(capsule.identity.authorityGeneration),
            static_cast<jlong>(capsule.identity.authority),
            static_cast<jlong>(capsule.identity.workGeneration),
            static_cast<jlong>(capsule.identity.ntkFrameId),
            static_cast<jlong>(capsule.identity.frameSequence),
            static_cast<jlong>(capsule.identity.admissionSequence),
            static_cast<jlong>(capsule.identity.capsuleSequence),
            static_cast<jlong>(frame.stage_nonce),
            static_cast<jlong>(frame.scene_version),
            static_cast<jlong>(frame.stage_corridor_start),
            static_cast<jlong>(frame.stage_corridor_end),
            static_cast<jlong>(evidence.latchEvent.eventSequence),
            static_cast<jlong>(capsule.identity.transactionSerial),
            static_cast<jlong>(evidence.latchEvent.latchNanos),
            static_cast<jint>(gpu.sceneFormat),
            static_cast<jint>(gpu.expectedTextureCount),
            static_cast<jint>(gpu.residentTextureCount),
            static_cast<jlong>(gpu.expectedLogicalBytes),
            static_cast<jlong>(gpu.residentLogicalBytes),
            digest,
            static_cast<jlong>(last_gpu_resource_completion_ns_.load(
                std::memory_order_acquire)),
            static_cast<jlong>(seal_fence_completion_ns_.load(
                std::memory_order_acquire)));
        env->DeleteLocalRef(digest);
        return !handle_jni_callback_exception(
            env, "onNativeStageLatchedV2", true, true);
    }

    bool validate_gpu_invariant_before_feedback(FrameFeedback& frame) {
        const bool sealedPhase = frame.gpu_phase ==
                static_cast<int>(GpuPhase::SEALING) ||
            frame.gpu_phase == static_cast<int>(GpuPhase::INPUT_ARMED) ||
            frame.gpu_phase == static_cast<int>(GpuPhase::GESTURE_ACTIVE);
        const bool valid = !frame.stage_candidate ||
            (sealedPhase && frame.sealed_scene &&
             frame.resource_submit_serial > 0 &&
             frame.resource_submit_serial ==
                frame.sealed_resource_submit_serial &&
             !frame.upload_context_alive &&
             frame.upload_commands_submitting == 0 &&
             frame.upload_gpu_fences_pending == 0 &&
             frame.ready_tile_queue_depth == 0 &&
             frame.native_publications_outstanding == 0 &&
             frame.pending_publish_acks == 0 &&
             frame.retire_queue_depth == 0 &&
             frame.active_resource_worker_count == 0 &&
             frame.active_upload_context_count == 0 &&
             frame.scene_mutation_count_since_seal == 0 &&
             frame.stage_latch_ns > 0);
        if (!valid) {
            frame.cadence_qualification_failed = true;
            authority_failed_.store(true, std::memory_order_release);
        }
        return valid;
    }

    void dispatch_frame_feedback(
            JNIEnv* env, const CompletedFrameEvidence& evidence,
            FrameFeedback& frame) {
        const AuthorityKey key{
            frame.engine_generation, frame.authority_generation,
            frame.authority};
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (released_authorities_.find(key) != released_authorities_.end()) {
                engine_failed_.store(true, std::memory_order_release);
                return;
            }
        }
        // Oracle ordering contract: immutable V7 evidence is visible first;
        // the stage transition callback is emitted only after that succeeds.
        if (!call_frame_evidence_v11(env, evidence, frame)) return;
        (void)call_stage_latched_v2(env, evidence, frame);
    }

    FrameFeedback materialize_frame_feedback(
            const CompletedFrameEvidence& evidence) const {
        const SubmittedEvidenceCapsule& capsule = evidence.capsule;
        const PreparedFrameWork& admitted = capsule.prepared;
        const SwappyFixedPhaseTelemetry& phase = capsule.phase;
        const SubmittedGpuInvariantSnapshot& gpu = capsule.gpu;
        const auto& latch = evidence.latchEvent;
        const auto& retirement = evidence.retirementEvent;
        FrameFeedback feedback{};
        feedback.engine_generation = admitted.engine_generation;
        feedback.authority_generation = admitted.authority_generation;
        feedback.authority = admitted.authority;
        feedback.surface_epoch = admitted.surface_epoch;
        feedback.frame_sequence = capsule.frameSequence;
        feedback.frame_id = capsule.frameId;
        feedback.frame_id_telemetry = capsule.frameId;
        feedback.scene_version = admitted.scene_version;
        feedback.scroll_top = admitted.view_state.scroll_top;
        feedback.velocity_px_per_second =
            admitted.view_state.velocity_px_per_second;
        feedback.predicted_stop_px = admitted.predicted_stop;
        feedback.continuous_start = admitted.continuous_start;
        feedback.continuous_end = admitted.continuous_end;
        feedback.visible_start = admitted.visible_start;
        feedback.visible_end = admitted.visible_end;
        feedback.first_visible_page = admitted.first_visible_page;
        feedback.last_visible_page = admitted.last_visible_page;
        feedback.first_visible_gap = admitted.visible_gap;
        feedback.viewport_original_complete = admitted.viewport_complete;
        feedback.runway_original_complete = admitted.runway_complete;
        feedback.gesture_id = static_cast<std::int64_t>(
            admitted.gesture_generation);
        feedback.applied_input_sequence = static_cast<std::int64_t>(
            admitted.input_watermark);
        feedback.input_oldest_ns = admitted.input_oldest_ns;
        feedback.input_newest_ns = admitted.input_newest_ns;
        feedback.main_ingress_oldest_ns = admitted.main_ingress_oldest_ns;
        feedback.main_ingress_newest_ns = admitted.main_ingress_newest_ns;
        feedback.receipt_oldest_ns = admitted.receipt_oldest_ns;
        feedback.receipt_newest_ns = admitted.receipt_newest_ns;
        feedback.mutation_oldest_ns = admitted.mutation_oldest_ns;
        feedback.mutation_newest_ns = admitted.mutation_newest_ns;
        feedback.draw_begin_ns = admitted.draw_begin_ns;
        feedback.draw_issue_end_ns = admitted.draw_issue_end_ns;
        feedback.frame_id_reservation_begin_ns =
            admitted.frame_id_reservation_begin_ns;
        feedback.frame_id_reserved_ns = admitted.frame_id_reserved_ns;
        feedback.pre_wait_ns = phase.preSubmitNanos;
        feedback.target_reached_ns = retirement.targetReachedNanos;
        feedback.fence_complete_ns =
            capsule.gpuReadyProof.acquireFenceExportReturnNanos;
        feedback.post_wait_ns = retirement.callbackPublishedNanos;
        feedback.pre_swap_ns = phase.preSubmitNanos;
        feedback.post_swap_ns = phase.postSubmitNanos;
        feedback.queue_submit_ns = capsule.transactionApplyBeginNanos;
        feedback.latch_time_ns = latch.latchNanos;
        feedback.present_time_ns = latch.latchNanos;
        feedback.swap_interval_ns = gpu.swapIntervalNanos;
        feedback.timestamp_query_work_ns = 0;
        feedback.presentation_timestamp_supported = true;
        feedback.latch_timestamp_supported = true;
        feedback.presentation_complete = true;
        feedback.latch_complete = evidence.qualified;
        feedback.latch_proof_state = evidence.qualified
            ? LatchProofState::LATCHED : LatchProofState::LOST;
        feedback.logical_unlatched_submissions = static_cast<int>(
            capsule.postSubmitLogicalUnlatchedNow);
        feedback.max_logical_unlatched_submissions = static_cast<int>(
            capsule.postSubmitMaxLogicalUnlatched);
        feedback.cadence_qualification_failed = !evidence.qualified;
        feedback.telemetry_schema_version = phase.schemaVersion;
        feedback.fixed_phase_telemetry_valid = capsule.exactPhaseTelemetry;
        feedback.fixed_phase_sequence = phase.sequence;
        feedback.fixed_phase_reservation_sequence = phase.reservationSequence;
        feedback.fixed_phase_opportunity_sequence = phase.opportunitySequence;
        feedback.fixed_phase_opportunity_kind = phase.opportunityKind;
        feedback.fixed_phase_physical_callback_sequence =
            phase.physicalCallbackSequence;
        feedback.fixed_phase_reservation_ns = phase.reservationNanos;
        feedback.fixed_phase_opportunity_receipt_ns =
            phase.opportunityReceiptNanos;
        feedback.fixed_phase_opportunity_publish_ns =
            phase.opportunityPublishNanos;
        feedback.fixed_phase_renderer_wake_observed_ns =
            phase.rendererCallbackObservedNanos;
        feedback.fixed_phase_stale_target_observed =
            phase.staleTargetObserved != 0;
        feedback.fixed_phase_miss_proven = phase.phaseMissProven != 0;
        feedback.fixed_phase_outcome = phase.outcome;
        feedback.fixed_phase_fatal_reason = phase.fatalReason;
        feedback.fixed_phase_plan_valid = phase.planValid != 0;
        feedback.fixed_phase_refresh_period_ns = phase.refreshPeriodNanos;
        feedback.fixed_phase_app_vsync_offset_ns = phase.appVsyncOffsetNanos;
        feedback.fixed_phase_accepted_frame_time_ns =
            phase.acceptedFrameTimeNanos;
        feedback.fixed_phase_accepted_frame_index = phase.acceptedFrameIndex;
        feedback.fixed_phase_decision_ns = phase.decisionNanos;
        feedback.fixed_phase_missed_presentation_ns =
            phase.missedPresentationNanos;
        feedback.fixed_phase_planned_presentation_ns =
            phase.plannedPresentationNanos;
        feedback.fixed_phase_presentation_deadline_ns =
            phase.presentationDeadlineNanos;
        feedback.fixed_phase_open_ns = phase.phaseOpenNanos;
        feedback.fixed_phase_latest_swap_start_exclusive_ns =
            phase.latestSwapStartExclusiveNanos;
        feedback.fixed_phase_wait_ns = phase.phaseWaitNanos;
        feedback.fixed_phase_planned_target_frame = phase.plannedTargetFrame;
        feedback.fixed_phase_pre_swap_ns = phase.preSubmitNanos;
        feedback.fixed_phase_post_swap_ns = phase.postSubmitNanos;
        feedback.fixed_phase_swap_duration_ns = phase.submitDurationNanos;
        feedback.fixed_phase_fence_wait_count =
            static_cast<int>(phase.gpuFenceWaitCount);
        feedback.fixed_phase_post_swap_target_rebase_count =
            static_cast<int>(phase.targetRebaseCount);
        feedback.fixed_candidate_sequence = phase.candidateSequence;
        feedback.fixed_candidate_raw_sequence = phase.candidateRawSequence;
        feedback.fixed_candidate_capture_ns = phase.candidateCaptureNanos;
        feedback.fixed_candidate_claim_ns = phase.candidateClaimNanos;
        feedback.fixed_refresh_issued = phase.refreshIssued;
        feedback.fixed_refresh_delivered = phase.refreshDelivered;
        feedback.fixed_refresh_physical_callback_sequence =
            phase.refreshPhysicalCallbackSequence;
        feedback.fixed_refresh_captured_raw_sequence =
            phase.refreshCapturedRawSequence;
        feedback.fixed_shadow_raw_sequence = phase.shadowRawSequence;
        feedback.fixed_shadow_promotion_count = phase.shadowPromotionCount;
        feedback.fixed_wake_notice_sequence = phase.wakeNoticeSequence;
        feedback.fixed_join_notice_sequence = phase.joinNoticeSequence;
        feedback.fixed_join_open_ns = phase.joinOpenNanos;
        feedback.fixed_join_prior_retirement_sequence =
            phase.joinPriorRetirementSequence;
        feedback.fixed_latch_observation_work_generation =
            phase.latchEventWorkGeneration;
        feedback.fixed_latch_observation_admission_sequence =
            phase.latchEventAdmissionSequence;
        feedback.fixed_latch_observation_frame_id =
            phase.latchEventNtkFrameId;
        feedback.fixed_latch_observation_latch_ns =
            phase.latchEventCompositorNanos;
        feedback.fixed_latch_observation_query_count =
            phase.latchEventSequence == 0 ? 0U : 1U;
        feedback.fixed_final_corridor_begin_ns = phase.finalCorridorBeginNanos;
        feedback.fixed_queue_mark_ns = phase.transactionApplyBeginNanos;
        feedback.fixed_egl_swap_enter_ns = phase.transactionApplyBeginNanos;
        feedback.fixed_decision_to_egl_enter_ns =
            phase.decisionToApplyBeginNanos;
        feedback.fixed_common_commit_entry_ns = phase.commonCommitEntryNanos;
        feedback.fixed_opportunity_claim_ns = phase.opportunityClaimNanos;
        feedback.fixed_retirement_demand_issued = phase.retirementDemandIssued;
        feedback.fixed_retirement_demand_satisfied =
            phase.retirementDemandSatisfied;
        feedback.fixed_retirement_demand_cancelled =
            phase.retirementDemandCancelled;
        feedback.fixed_opportunity_demand_issued = phase.opportunityDemandIssued;
        feedback.fixed_opportunity_demand_satisfied =
            phase.opportunityDemandSatisfied;
        feedback.fixed_opportunity_demand_cancelled =
            phase.opportunityDemandCancelled;
        feedback.fixed_superseded_before_claim_count =
            phase.supersededBeforeClaimCount;
        feedback.fixed_closed_opportunity_count = phase.closedOpportunityCount;
        feedback.fixed_target_frame_time_ns = retirement.targetReachedNanos;
        feedback.fixed_retirement_publish_ns =
            retirement.callbackPublishedNanos;
        feedback.fixed_renderer_wake_publish_ns =
            retirement.callbackPublishedNanos;
        feedback.fixed_backend_ready_ns = admitted.backend_ready_ns;
        feedback.fixed_first_commit_attempt_ns = admitted.first_commit_attempt_ns;
        feedback.backend_completion_token = capsule.identity.transactionSerial;
        feedback.backend_surface_serial = capsule.identity.backendSurfaceSerial;
        feedback.backend_completion_work_generation = capsule.workGeneration;
        feedback.backend_completion_frame_id = capsule.frameId;
        feedback.backend_completion_clock_domain = 1;
        feedback.backend_prepare_begin_ns = capsule.gpuReadyProof.renderBeginNanos;
        feedback.backend_completion_signal_ns =
            capsule.gpuReadyProof.acquireFenceExportReturnNanos;
        feedback.backend_wait_return_ns =
            capsule.gpuReadyProof.acquireFenceExportReturnNanos;
        feedback.backend_completion_issue_count = 1;
        feedback.backend_completion_commit_count = 1;
        feedback.backend_completion_publish_count = 1;
        feedback.backend_phase_partition_valid =
            capsule.gpuReadyProof.renderBeginNanos > 0 &&
            capsule.gpuReadyProof.renderEndNanos >=
                capsule.gpuReadyProof.renderBeginNanos &&
            capsule.gpuReadyProof.acquireFenceIssuedNanos >=
                capsule.gpuReadyProof.renderEndNanos &&
            capsule.gpuReadyProof.acquireFenceExportReturnNanos >=
                capsule.gpuReadyProof.acquireFenceIssuedNanos &&
            capsule.transactionApplyBeginNanos >=
                capsule.gpuReadyProof.acquireFenceExportReturnNanos &&
            capsule.transactionApplyEndNanos >=
                capsule.transactionApplyBeginNanos;
        feedback.control_backlog_max = gpu.controlBacklogMax;
        feedback.move_mailbox_writes = gpu.moveMailboxWrites;
        feedback.integrated_tiles = gpu.integratedTiles;
        feedback.upload_commands_submitting = gpu.uploadCommandsSubmitting;
        feedback.upload_gpu_fences_pending = gpu.uploadGpuFencesPending;
        feedback.gpu_phase = gpu.gpuPhase;
        feedback.sealed_scene = gpu.sealedScene;
        feedback.resource_submit_serial = gpu.resourceSubmitSerial;
        feedback.sealed_resource_submit_serial = gpu.sealedResourceSubmitSerial;
        feedback.ready_tile_queue_depth = gpu.readyTileQueueDepth;
        feedback.native_publications_outstanding =
            gpu.nativePublicationsOutstanding;
        feedback.pending_publish_acks = gpu.pendingPublishAcks;
        feedback.retire_queue_depth = gpu.retireQueueDepth;
        feedback.retirement_count = gpu.retirementCount;
        feedback.upload_context_alive = gpu.uploadContextAlive;
        feedback.last_gpu_resource_completion_ns =
            gpu.lastGpuResourceCompletionNanos;
        feedback.seal_fence_completion_ns = gpu.sealFenceCompletionNanos;
        feedback.upload_context_destroyed_ns = gpu.uploadContextDestroyedNanos;
        feedback.stage_latch_ns = latch.latchNanos;
        feedback.first_down_ingress_ns = gpu.firstDownIngressNanos;
        feedback.sealed_scene_version = gpu.sealedSceneVersion;
        feedback.resource_worker_state = gpu.resourceWorkerState;
        feedback.resource_worker_generation = gpu.resourceWorkerGeneration;
        feedback.resource_worker_create_count = gpu.resourceWorkerCreateCount;
        feedback.resource_worker_destroy_count = gpu.resourceWorkerDestroyCount;
        feedback.active_resource_worker_count = gpu.activeResourceWorkerCount;
        feedback.active_upload_context_count = gpu.activeUploadContextCount;
        feedback.scene_mutation_count_since_seal =
            gpu.sceneMutationCountSinceSeal;
        feedback.offscreen_warm_fence_completion_ns =
            gpu.offscreenWarmFenceCompletionNanos;
        feedback.predecessor_physical_complete_ns =
            gpu.predecessorPhysicalCompleteNanos;
        feedback.seal_barrier_serial = gpu.sealBarrierSerial;
        feedback.stage_backbuffer_ready_ns = gpu.stageBackbufferReadyNanos;
        feedback.offscreen_warm_draw_count = gpu.offscreenWarmDrawCount;
        feedback.frame_work_kind = static_cast<int>(admitted.kind);
        feedback.admission_sequence = admitted.admission_sequence;
        feedback.planner_invocation_count = gpu.plannerInvocationCount;
        feedback.backend_present_prepare_count = gpu.backendPresentPrepareCount;
        feedback.swap_attempt_count = gpu.swapAttemptCount;
        feedback.slot_closed_no_attempt_count = gpu.slotClosedNoAttemptCount;
        feedback.terminal_swap_count = gpu.terminalSwapCount;
        feedback.prepared_work_generation = capsule.workGeneration;
        feedback.swappy_work_generation = phase.workGeneration;
        feedback.swappy_admission_sequence = phase.admissionSequence;
        feedback.prepared_draw_count = gpu.preparedDrawCount;
        feedback.prepared_frame_id_reservation_count =
            gpu.preparedFrameIdReservationCount;
        feedback.admission_consumed = phase.admissionConsumed != 0;
        feedback.stage_candidate = admitted.stage_candidate;
        feedback.stage_nonce = gpu.stageNonce;
        feedback.stage_corridor_start = gpu.stageCorridorStart;
        feedback.stage_corridor_end = gpu.stageCorridorEnd;
        feedback.evidence_capsule_depth = capsule.evidenceCapsuleDepth;
        feedback.evidence_capsule_max_depth = capsule.evidenceCapsuleMaxDepth;
        feedback.evidence_capsule_invalid_frames =
            capsule.evidenceCapsuleInvalidFrames;
        return feedback;
    }

    bool frame_feedback_available() const {
        const std::uint64_t expected = frame_feedback_read_sequence_.load(
            std::memory_order_acquire) + 1;
        const EvidenceCapsuleSlot& slot = evidence_capsule_ring_[
            (expected - 1) % kFrameFeedbackRingSize];
        return slot.completedSequence.load(std::memory_order_acquire) == expected;
    }

    bool drain_one_frame_feedback(JNIEnv* env) {
        const std::uint64_t expected = frame_feedback_read_sequence_.load(
            std::memory_order_relaxed) + 1;
        EvidenceCapsuleSlot& slot = evidence_capsule_ring_[
            (expected - 1) % kFrameFeedbackRingSize];
        if (slot.completedSequence.load(std::memory_order_acquire) != expected) return false;
        const CompletedFrameEvidence evidence = slot.completed;
        FrameFeedback frame = materialize_frame_feedback(evidence);
        // The consumer has copied the immutable slot. Release it before JNI so the render owner
        // never waits for Java, while delivered_sequence remains the flush/barrier authority.
        frame_feedback_read_sequence_.store(expected, std::memory_order_release);
        validate_gpu_invariant_before_feedback(frame);
        dispatch_frame_feedback(env, evidence, frame);
        frame_feedback_delivered_sequence_.store(expected, std::memory_order_release);
        // This read-only watermark is the physical qualification boundary. Frame callbacks are
        // delivered in monotonically increasing submission order and are published only after
        // both the fixed target retirement and compositor latch have joined. Therefore reaching
        // a terminal input timestamp proves that every earlier submitted frame callback is also
        // visible to Kotlin, without a test request, renderer drain, feedback flush, or delay.
        if (frame.latch_complete && frame.latch_time_ns > 0 && frame.input_newest_ns > 0) {
            std::int64_t observed = latest_delivered_latched_input_event_ns_.load(
                std::memory_order_acquire);
            while (observed < frame.input_newest_ns &&
                   !latest_delivered_latched_input_event_ns_.compare_exchange_weak(
                       observed, frame.input_newest_ns,
                       std::memory_order_release, std::memory_order_acquire)) {
            }
        }
        return true;
    }

    void dispatch_reliable_feedback(JNIEnv* env, const FeedbackRecord& callback) {
        const AuthorityKey callback_key{
            callback.engine_generation, callback.authority_generation,
            callback.key.authority};
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (released_authorities_.find(callback_key) != released_authorities_.end()) {
                NTK_LOGE("callback after authority release authority=%lld generation=%lld",
                         static_cast<long long>(callback.key.authority),
                         static_cast<long long>(callback.authority_generation));
                engine_failed_.store(true, std::memory_order_release);
                return;
            }
        }
        if (callback.kind == FeedbackKind::TILE_RESIDENT) {
            if (env != nullptr && callback_ != nullptr && on_tile_resident_ != nullptr) {
                env->CallVoidMethod(callback_, on_tile_resident_,
                                    static_cast<jlong>(callback.engine_generation),
                                    static_cast<jlong>(callback.authority_generation),
                                    static_cast<jlong>(callback.key.authority),
                                    static_cast<jlong>(callback.surface_epoch),
                                    static_cast<jlong>(callback.admission_id),
                                    static_cast<jint>(callback.key.page),
                                    static_cast<jint>(callback.key.slot),
                                    static_cast<jlong>(callback.resource_revision),
                                    static_cast<jlong>(callback.install_lease),
                                    static_cast<jlong>(callback.rgba_bytes),
                                    static_cast<jlong>(callback.value),
                                    static_cast<jboolean>(
                                        callback.success ? JNI_TRUE : JNI_FALSE));
                handle_jni_callback_exception(env, "onNativeTileResident", true, false);
            }
        } else if (callback.kind == FeedbackKind::PREPARED_TILE_RESIDENT) {
            if (env != nullptr && callback_ != nullptr &&
                on_prepared_tile_resident_ != nullptr) {
                jstring tile_proof = env->NewStringUTF(
                    callback.tile_proof_digest.c_str());
                jstring inventory = env->NewStringUTF(
                    callback.resident_inventory_digest.c_str());
                env->CallVoidMethod(
                    callback_, on_prepared_tile_resident_,
                    static_cast<jlong>(callback.engine_generation),
                    static_cast<jlong>(callback.authority_generation),
                    static_cast<jlong>(callback.key.authority),
                    static_cast<jlong>(callback.surface_epoch),
                    static_cast<jlong>(callback.admission_id),
                    static_cast<jint>(callback.key.page),
                    static_cast<jint>(callback.key.slot),
                    static_cast<jlong>(callback.resource_revision),
                    static_cast<jlong>(callback.install_lease),
                    static_cast<jlong>(callback.rgba_bytes),
                    tile_proof, inventory,
                    static_cast<jlong>(callback.resource_completion_ns),
                    static_cast<jboolean>(callback.pre_geometry ? JNI_TRUE : JNI_FALSE),
                    static_cast<jboolean>(callback.success ? JNI_TRUE : JNI_FALSE));
                if (tile_proof != nullptr) env->DeleteLocalRef(tile_proof);
                if (inventory != nullptr) env->DeleteLocalRef(inventory);
                handle_jni_callback_exception(
                    env, "onNativePreparedTileResident", true, false);
            }
        } else if (callback.kind == FeedbackKind::PROTECTION_COMMITTED) {
            if (env != nullptr && callback_ != nullptr && on_protection_committed_ != nullptr) {
                jstring digest = env->NewStringUTF(callback.protected_digest.c_str());
                env->CallVoidMethod(callback_, on_protection_committed_,
                                    static_cast<jlong>(callback.engine_generation),
                                    static_cast<jlong>(callback.authority_generation),
                                    static_cast<jlong>(callback.key.authority),
                                    static_cast<jlong>(callback.surface_epoch),
                                    static_cast<jlong>(callback.demand_epoch),
                                    digest,
                                    static_cast<jlong>(callback.value),
                                    static_cast<jboolean>(
                                        callback.success ? JNI_TRUE : JNI_FALSE));
                if (digest != nullptr) env->DeleteLocalRef(digest);
                handle_jni_callback_exception(
                    env, "onNativeProtectionCommitted", true, false);
            }
        } else if (callback.kind == FeedbackKind::RETIRE_RESULT) {
            if (env != nullptr && callback_ != nullptr && on_retire_result_ != nullptr) {
                jstring digest = env->NewStringUTF(callback.protected_digest.c_str());
                env->CallVoidMethod(callback_, on_retire_result_,
                                    static_cast<jlong>(callback.engine_generation),
                                    static_cast<jlong>(callback.authority_generation),
                                    static_cast<jlong>(callback.key.authority),
                                    static_cast<jlong>(callback.surface_epoch),
                                    static_cast<jlong>(callback.policy_surface_epoch),
                                    static_cast<jlong>(callback.demand_epoch),
                                    static_cast<jint>(callback.key.page),
                                    static_cast<jint>(callback.key.slot),
                                    static_cast<jlong>(callback.resource_revision),
                                    static_cast<jlong>(callback.install_lease),
                                    static_cast<jlong>(callback.retire_lease),
                                    digest,
                                    static_cast<jint>(callback.retire_result),
                                    static_cast<jlong>(callback.value),
                                    static_cast<jlong>(callback.fence_serial));
                if (digest != nullptr) env->DeleteLocalRef(digest);
                handle_jni_callback_exception(env, "onNativeRetireResult", true, false);
            }
        } else if (callback.kind == FeedbackKind::TILE_FREED &&
                   env != nullptr && callback_ != nullptr && on_tile_freed_ != nullptr) {
            jstring digest = env->NewStringUTF(callback.protected_digest.c_str());
            env->CallVoidMethod(callback_, on_tile_freed_,
                                static_cast<jlong>(callback.engine_generation),
                                static_cast<jlong>(callback.authority_generation),
                                static_cast<jlong>(callback.key.authority),
                                static_cast<jlong>(callback.surface_epoch),
                                static_cast<jlong>(callback.policy_surface_epoch),
                                static_cast<jlong>(callback.demand_epoch),
                                static_cast<jlong>(callback.admission_id),
                                static_cast<jint>(callback.key.page),
                                static_cast<jint>(callback.key.slot),
                                static_cast<jlong>(callback.resource_revision),
                                static_cast<jlong>(callback.install_lease),
                                static_cast<jlong>(callback.retire_lease),
                                static_cast<jlong>(callback.rgba_bytes),
                                digest,
                                static_cast<jlong>(callback.value),
                                static_cast<jboolean>(
                                    callback.success ? JNI_TRUE : JNI_FALSE));
            if (digest != nullptr) env->DeleteLocalRef(digest);
            handle_jni_callback_exception(env, "onNativeTileFreed", true, false);
        } else if (callback.kind == FeedbackKind::PRE_SUBMIT_VIEWPORT_GAP &&
                   env != nullptr && callback_ != nullptr &&
                   on_pre_submit_viewport_gap_ != nullptr) {
            env->CallVoidMethod(callback_, on_pre_submit_viewport_gap_,
                                static_cast<jlong>(callback.engine_generation),
                                static_cast<jlong>(callback.authority_generation),
                                static_cast<jlong>(callback.key.authority),
                                static_cast<jlong>(callback.surface_epoch),
                                static_cast<jlong>(callback.value));
            handle_jni_callback_exception(
                env, "onNativePreSubmitViewportGap", true, false);
        }
    }

    void dispatch_authority_released(JNIEnv* env, const FeedbackRecord& record) {
        const auto& ack = record.release_ack;
        if (!ack) return;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            const auto found = release_trackers_.find(ack->claim.key);
            if (found == release_trackers_.end()) return;
            ack->feedback_barrier_serial = next_release_protocol_serial_locked();
            found->second->feedback_barrier_serial = ack->feedback_barrier_serial;
            ack->success = ack->success &&
                ack->disposition == PhysicalReleaseDisposition::EXPLICIT_DELETE &&
                ack->feedback_barrier_serial > ack->resource_completion_watermark;
        }

        publish_release_metadata_then_terminalize(
            [&] {
                // This callback publishes immutable metadata only. Java exceptions are
                // observational; they can never roll back physical release truth.
                if (env == nullptr || callback_ == nullptr ||
                    on_authority_released_ == nullptr) return;
                jstring captured_digest = env->NewStringUTF(
                    ack->captured_resource_digest.c_str());
                jstring released_digest = env->NewStringUTF(
                    ack->released_resource_digest.c_str());
                if (captured_digest != nullptr && released_digest != nullptr) {
                    env->CallVoidMethod(
                        callback_, on_authority_released_,
                        static_cast<jlong>(ack->claim.key.engine_generation),
                        static_cast<jlong>(ack->claim.key.authority_generation),
                        static_cast<jlong>(ack->claim.key.authority),
                        static_cast<jlong>(ack->claim.reducer_surface_epoch),
                        static_cast<jlong>(ack->claim.release_nonce),
                        static_cast<jint>(ack->disposition),
                        static_cast<jlong>(ack->admission_close_serial),
                        static_cast<jlong>(ack->release_claim_serial),
                        static_cast<jlong>(ack->resource_barrier_serial),
                        static_cast<jlong>(ack->resource_completion_watermark),
                        static_cast<jlong>(ack->feedback_barrier_serial),
                        static_cast<jint>(ack->captured_resource_count),
                        static_cast<jlong>(ack->captured_rgba_bytes),
                        captured_digest,
                        static_cast<jint>(ack->released_resource_count),
                        static_cast<jlong>(ack->released_rgba_bytes),
                        released_digest,
                        static_cast<jint>(ack->deleted_texture_count),
                        static_cast<jint>(ack->deleted_fence_count),
                        static_cast<jint>(ack->released_bitmap_global_ref_count),
                        static_cast<jint>(ack->drained_upload_count),
                        static_cast<jint>(ack->drained_retire_count),
                        static_cast<jint>(ack->remaining_command_count),
                        static_cast<jint>(ack->remaining_resource_count),
                        static_cast<jlong>(ack->remaining_rgba_bytes),
                        static_cast<jint>(ack->remaining_fence_count),
                        static_cast<jint>(ack->remaining_bitmap_global_ref_count),
                        static_cast<jint>(ack->remaining_native_callback_count),
                        static_cast<jlong>(ack->backend_retirement_serial),
                        static_cast<jlong>(ack->backend_retired_nanos),
                        static_cast<jint>(ack->retired_backend_remaining_thread_count),
                        static_cast<jint>(ack->retired_backend_remaining_egl_handle_count),
                        static_cast<jint>(ack->retired_backend_remaining_native_window_count),
                        static_cast<jint>(ack->retired_backend_remaining_swappy_lease_count),
                        static_cast<jint>(ack->retired_backend_remaining_jni_global_ref_count),
                        static_cast<jlong>(ack->completed_nanos),
                        static_cast<jboolean>(
                            ack->context_reusable ? JNI_TRUE : JNI_FALSE),
                        static_cast<jboolean>(ack->success ? JNI_TRUE : JNI_FALSE));
                }
                if (captured_digest != nullptr) env->DeleteLocalRef(captured_digest);
                if (released_digest != nullptr) env->DeleteLocalRef(released_digest);
                handle_jni_callback_exception(
                    env, "onNativeAuthorityReleased", false, false);
            },
            [&] {
                std::lock_guard<std::mutex> lock(mutex_);
                const auto found = release_trackers_.find(ack->claim.key);
                if (found == release_trackers_.end()) return false;
                if (!ack->success) {
                    found->second->lifecycle = AuthorityLifecycle::FAILED;
                    return false;
                }
                found->second->lifecycle = AuthorityLifecycle::RELEASED;
                released_authorities_.insert(ack->claim.key);
                release_ack_count_.fetch_add(1, std::memory_order_acq_rel);
                return true;
            },
            [&] {
                // This second callback is the only permission for Kotlin to schedule external
                // code. It runs after terminalization and outside every native lock.
                if (env == nullptr || callback_ == nullptr ||
                    on_authority_release_dispatchable_ == nullptr) return;
                env->CallVoidMethod(
                    callback_, on_authority_release_dispatchable_,
                    static_cast<jlong>(ack->claim.key.engine_generation),
                    static_cast<jlong>(ack->claim.key.authority_generation),
                    static_cast<jlong>(ack->claim.key.authority),
                    static_cast<jlong>(ack->claim.release_nonce));
                handle_jni_callback_exception(
                    env, "onNativeAuthorityReleaseDispatchable", false, false);
            });
    }

    void feedback_loop() {
        // EGL timestamp work is render-owned. This neutral-priority lane only serializes JNI
        // callbacks and Java telemetry, so it never competes for the render context.
        setpriority(PRIO_PROCESS, 0, 0);
        EnvScope scope(java_vm_);
        JNIEnv* env = scope.get();
        std::deque<std::pair<std::uint64_t, std::uint64_t>> pending_barriers;
        std::deque<FeedbackRecord> pending_release_acks;
        while (true) {
            FeedbackRecord record;
            bool has_record = false;
            {
                std::unique_lock<std::mutex> lock(feedback_mutex_);
                if (feedback_count_ == 0 && !frame_feedback_available()) {
                    feedback_ready_.wait(lock, [&] {
                        return feedback_exit_requested_ || feedback_count_ > 0 ||
                            frame_feedback_available();
                    });
                }
                if (feedback_count_ == 0 && !frame_feedback_available() &&
                    pending_barriers.empty() && pending_release_acks.empty() &&
                    feedback_exit_requested_) break;
                if (feedback_count_ > 0) {
                    record = feedback_ring_[feedback_read_ % kFeedbackRingSize];
                    ++feedback_read_;
                    --feedback_count_;
                    has_record = true;
                }
            }
            if (has_record) feedback_space_.notify_one();
            if (has_record && record.kind == FeedbackKind::BARRIER) {
                pending_barriers.emplace_back(
                    record.barrier_sequence, record.frame_target_sequence);
            } else if (has_record && record.kind == FeedbackKind::AUTHORITY_RELEASED) {
                pending_release_acks.push_back(std::move(record));
            } else if (has_record) {
                // Reliable ownership ACKs keep FIFO priority over ordinary FRAME telemetry.
                dispatch_reliable_feedback(env, record);
            }

            drain_one_frame_feedback(env);

            const std::uint64_t delivered = frame_feedback_delivered_sequence_.load(
                std::memory_order_acquire);
            while (!pending_release_acks.empty() &&
                   pending_release_acks.front().frame_target_sequence <= delivered) {
                FeedbackRecord release = std::move(pending_release_acks.front());
                pending_release_acks.pop_front();
                dispatch_authority_released(env, release);
            }
            std::uint64_t completed_barrier = 0;
            while (!pending_barriers.empty() &&
                   pending_barriers.front().second <= delivered) {
                completed_barrier = pending_barriers.front().first;
                pending_barriers.pop_front();
            }
            if (completed_barrier > 0) {
                std::lock_guard<std::mutex> lock(feedback_mutex_);
                feedback_barrier_completed_ = std::max(
                    feedback_barrier_completed_, completed_barrier);
                feedback_barrier_condition_.notify_all();
            }
        }
    }

    bool initialize_egl() {
        egl_initialize_begin_ns_.store(monotonic_now_ns(), std::memory_order_release);
        display_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if (display_ == EGL_NO_DISPLAY || !eglInitialize(display_, nullptr, nullptr)) return false;
        egl_initialize_count_.fetch_add(1, std::memory_order_acq_rel);
        egl_initialize_end_ns_.store(monotonic_now_ns(), std::memory_order_release);
        const char* queried_vendor = eglQueryString(display_, EGL_VENDOR);
        const char* queried_version = eglQueryString(display_, EGL_VERSION);
        egl_vendor_ = queried_vendor != nullptr ? queried_vendor : "";
        egl_version_ = queried_version != nullptr ? queried_version : "";
        constexpr EGLint config_attributes[] = {
            EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
            EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 0,
            EGL_NONE
        };
        EGLint config_count = 0;
        if (!eglChooseConfig(display_, config_attributes, nullptr, 0, &config_count) ||
            config_count <= 0) return false;
        std::vector<EGLConfig> configs(static_cast<std::size_t>(config_count));
        EGLint returned_configs = 0;
        if (!eglChooseConfig(display_, config_attributes, configs.data(), config_count,
                             &returned_configs) || returned_configs <= 0) return false;
        EGLConfig portable_zero_config = nullptr;
        EGLint portable_zero_min_interval = 0;
        EGLint matching_config_count = 0;
        for (EGLint index = 0; index < returned_configs; ++index) {
            EGLConfig candidate = configs[static_cast<std::size_t>(index)];
            EGLint min_swap_interval = -1;
            EGLint surface_type = 0;
            if (eglGetConfigAttrib(display_, candidate, EGL_MIN_SWAP_INTERVAL,
                                   &min_swap_interval) != EGL_TRUE ||
                eglGetConfigAttrib(display_, candidate, EGL_SURFACE_TYPE,
                                   &surface_type) != EGL_TRUE) {
                NTK_LOGE("fatal unreadable EGL config index=%d", index);
                return false;
            }
            if ((surface_type & EGL_PBUFFER_BIT) == 0) continue;
            ++matching_config_count;
            if (portable_zero_config == nullptr) {
                portable_zero_config = candidate;
                portable_zero_min_interval = min_swap_interval;
            }
            NTK_LOGI("EGL candidate index=%d surfaceType=0x%x minSwap=%d",
                     index, surface_type, min_swap_interval);
        }
        pending_gfxstream_min1_ = false;
        raw_zero_forwarding_mode_ = RawZeroForwardingMode::NONE;
        backend_class_ = BackendClass::NONE;
        if (portable_zero_config != nullptr) {
            config_ = portable_zero_config;
            selected_min_swap_interval_ = portable_zero_min_interval;
            backend_class_ = BackendClass::PORTABLE_MIN_ZERO;
        } else {
            NTK_LOGE("fatal no GLES3 pbuffer config vendor='%s' candidates=%d matching=%d",
                     egl_vendor_.c_str(), returned_configs, matching_config_count);
            return false;
        }
        constexpr EGLint default_context_attributes[] = {
            EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE
        };
        constexpr EGLint render_priority_attributes[] = {
            EGL_CONTEXT_CLIENT_VERSION, 3,
            EGL_CONTEXT_PRIORITY_LEVEL_IMG, EGL_CONTEXT_PRIORITY_HIGH_IMG,
            EGL_NONE
        };
        constexpr EGLint upload_priority_attributes[] = {
            EGL_CONTEXT_CLIENT_VERSION, 3,
            EGL_CONTEXT_PRIORITY_LEVEL_IMG, EGL_CONTEXT_PRIORITY_LOW_IMG,
            EGL_NONE
        };
        const char* egl_extensions = eglQueryString(display_, EGL_EXTENSIONS);
        const bool context_priority_supported = egl_extensions != nullptr &&
            std::strstr(egl_extensions, "EGL_IMG_context_priority") != nullptr;
        const bool surfaceless_context_supported = egl_extensions != nullptr &&
            std::strstr(egl_extensions, "EGL_KHR_surfaceless_context") != nullptr;
        render_context_ = eglCreateContext(
            display_, config_, EGL_NO_CONTEXT,
            context_priority_supported ? render_priority_attributes : default_context_attributes);
        if (render_context_ == EGL_NO_CONTEXT && context_priority_supported) {
            render_context_ = eglCreateContext(
                display_, config_, EGL_NO_CONTEXT, default_context_attributes);
        }
        if (render_context_ == EGL_NO_CONTEXT) return false;
        render_context_ready_ns_.store(monotonic_now_ns(), std::memory_order_release);
        upload_context_ = eglCreateContext(
            display_, config_, render_context_,
            context_priority_supported ? upload_priority_attributes : default_context_attributes);
        if (upload_context_ == EGL_NO_CONTEXT && context_priority_supported) {
            upload_context_ = eglCreateContext(
                display_, config_, render_context_, default_context_attributes);
        }
        if (upload_context_ == EGL_NO_CONTEXT) return false;
        upload_context_ready_ns_.store(monotonic_now_ns(), std::memory_order_release);
        egl_context_create_count_.fetch_add(2, std::memory_order_acq_rel);
        const std::uint64_t initial_worker_generation =
            record_resource_worker_context_created();
        if (initial_worker_generation != 1) return false;
        NTK_LOGI("EGL shared context priority supported=%d", context_priority_supported ? 1 : 0);
        EGLint surface_type = 0;
        if (eglGetConfigAttrib(display_, config_, EGL_SURFACE_TYPE, &surface_type) != EGL_TRUE) {
            return false;
        }
        if ((surface_type & EGL_PBUFFER_BIT) != 0) {
            constexpr EGLint pbuffer_attributes[] = {
                EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE
            };
            render_pbuffer_ = eglCreatePbufferSurface(display_, config_, pbuffer_attributes);
            upload_pbuffer_ = eglCreatePbufferSurface(display_, config_, pbuffer_attributes);
            if (render_pbuffer_ == EGL_NO_SURFACE || upload_pbuffer_ == EGL_NO_SURFACE) return false;
            render_pbuffer_ready_ns_.store(monotonic_now_ns(), std::memory_order_release);
            upload_pbuffer_ready_ns_.store(monotonic_now_ns(), std::memory_order_release);
        } else if (!surfaceless_context_supported) {
            NTK_LOGE("fatal interval-0 EGL config lacks pbuffer and surfaceless contexts");
            return false;
        }
        NTK_LOGI("EGL offscreen mode=%s", render_pbuffer_ == EGL_NO_SURFACE
            ? "surfaceless" : "pbuffer");
        if (!eglMakeCurrent(display_, render_pbuffer_, render_pbuffer_, render_context_)) return false;
        const char* gl_vendor = reinterpret_cast<const char*>(glGetString(GL_VENDOR));
        const char* gl_renderer = reinterpret_cast<const char*>(glGetString(GL_RENDERER));
        const char* gl_version = reinterpret_cast<const char*>(glGetString(GL_VERSION));
        gl_vendor_ = gl_vendor != nullptr ? gl_vendor : "";
        gl_renderer_ = gl_renderer != nullptr ? gl_renderer : "";
        gl_version_ = gl_version != nullptr ? gl_version : "";
        const bool software_renderer =
            contains(gl_renderer, "SwiftShader") || contains(gl_renderer, "llvmpipe") ||
            contains(gl_renderer, "Lavapipe") || contains(gl_renderer, "lavapipe") ||
            contains(gl_renderer, "ANGLE") || contains(gl_renderer, "Google SwiftShader") ||
            contains(gl_renderer, "guest");
        if (software_renderer) {
            NTK_LOGE("fatal forbidden GL backend renderer='%s'", gl_renderer_.c_str());
            raw_zero_forwarding_mode_ = RawZeroForwardingMode::NONE;
            return false;
        }
        if (pending_gfxstream_min1_) {
            const bool exact_manifest_attested = qualification_manifest_verified_;
            const bool qualified_api35_gfxstream = exact_manifest_attested &&
                android_get_device_api_level() == 35 &&
                egl_vendor_ == kRequiredEglVendor &&
                gl_renderer_ == kRequiredGlRenderer &&
                gl_version_ == kRequiredGlVersion;
            if (!qualified_api35_gfxstream) {
                NTK_LOGE("fatal gfxstream proof fingerprint mismatch api=%d eglVendor='%s' "
                         "glVendor='%s' renderer='%s' glVersion='%s' manifest=%d",
                         android_get_device_api_level(), egl_vendor_.c_str(),
                         gl_vendor_.c_str(), gl_renderer_.c_str(), gl_version_.c_str(),
                         exact_manifest_attested ? 1 : 0);
                backend_class_ = BackendClass::NONE;
                return false;
            }
            backend_class_ = BackendClass::API35_GFXSTREAM_HOST_MIN1;
        }
        NTK_LOGI("EGL backend class=%d eglVendor='%s' eglVersion='%s' glVendor='%s' "
                 "renderer='%s' glVersion='%s' candidates=%d matching=%d manifest=%d",
                 static_cast<int>(backend_class_), egl_vendor_.c_str(),
                 egl_version_.c_str(), gl_vendor_.c_str(), gl_renderer_.c_str(),
                 gl_version_.c_str(), returned_configs, matching_config_count,
                 qualification_manifest_verified_ ? 1 : 0);
        if (!create_gl_program()) return false;
        program_ready_ns_.store(monotonic_now_ns(), std::memory_order_release);
        {
            std::lock_guard<std::mutex> lock(mutex_);
            upload_start_generation_ = initial_worker_generation;
            upload_start_owner_ = AuthorityKey{engine_generation_, 0, 0};
            upload_start_state_ = ResourceWorkerStartState::STARTING;
            egl_ready_ = true;
            egl_ready_ns_.store(monotonic_now_ns(), std::memory_order_release);
        }
        upload_condition_.notify_all();
        std::unique_lock<std::mutex> lock(mutex_);
        upload_start_condition_.wait(lock, [&] {
            return stopped_ ||
                (upload_start_generation_ == initial_worker_generation &&
                (upload_start_state_ == ResourceWorkerStartState::READY ||
                 upload_start_state_ == ResourceWorkerStartState::FAILED));
        });
        if (stopped_ || upload_start_state_ != ResourceWorkerStartState::READY) return false;
        upload_context_alive_.store(true, std::memory_order_release);
        active_resource_worker_count_.store(1, std::memory_order_release);
        gpu_resource_worker_state_.store(
            GpuResourceWorkerState::PRE_STAGE_ACTIVE, std::memory_order_release);
        resource_worker_ready_ns_.store(monotonic_now_ns(), std::memory_order_release);
        detached_warm_ready_ns_.store(monotonic_now_ns(), std::memory_order_release);
        return true;
    }

    GLuint compile_shader(GLenum type, const char* source) {
        GLuint shader = glCreateShader(type);
        glShaderSource(shader, 1, &source, nullptr);
        glCompileShader(shader);
        GLint compiled = GL_FALSE;
        glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
        if (compiled == GL_TRUE) return shader;
        GLint length = 0;
        glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &length);
        std::vector<char> log(static_cast<std::size_t>(std::max(1, length)));
        glGetShaderInfoLog(shader, length, nullptr, log.data());
        NTK_LOGE("shader compile failed: %s", log.data());
        glDeleteShader(shader);
        return 0;
    }

    bool create_gl_program() {
        const GpuPhase creation_phase = gpu_phase_.load(std::memory_order_acquire);
        if (creation_phase != GpuPhase::PRE_STAGE_CPU &&
            creation_phase != GpuPhase::PRE_STAGE_GPU) {
            NTK_LOGE("fatal GL program creation outside PRE_STAGE phase=%d",
                     static_cast<int>(creation_phase));
            return false;
        }
        constexpr char vertex_source[] =
            "#version 300 es\n"
            "layout(location=0) in vec2 aUnitPosition;\n"
            "layout(location=1) in vec2 aTexCoord;\n"
            "uniform vec2 uYBounds;\n"
            "out vec2 vTexCoord;\n"
            "void main(){ float y=mix(uYBounds.x,uYBounds.y,aUnitPosition.y); "
            "gl_Position=vec4(aUnitPosition.x,y,0.0,1.0); vTexCoord=aTexCoord; }\n";
        constexpr char fragment_source[] =
            "#version 300 es\n"
            "precision mediump float;\n"
            "in vec2 vTexCoord;\n"
            "uniform sampler2D uTexture;\n"
            "out vec4 outColor;\n"
            "void main(){ outColor=texture(uTexture,vTexCoord); }\n";
        GLuint vertex = compile_shader(GL_VERTEX_SHADER, vertex_source);
        GLuint fragment = compile_shader(GL_FRAGMENT_SHADER, fragment_source);
        if (vertex == 0 || fragment == 0) return false;
        program_ = glCreateProgram();
        glAttachShader(program_, vertex);
        glAttachShader(program_, fragment);
        glLinkProgram(program_);
        glDeleteShader(vertex);
        glDeleteShader(fragment);
        GLint linked = GL_FALSE;
        glGetProgramiv(program_, GL_LINK_STATUS, &linked);
        if (linked != GL_TRUE) return false;
        y_bounds_uniform_ = glGetUniformLocation(program_, "uYBounds");
        const GLint texture_uniform = glGetUniformLocation(program_, "uTexture");
        if (y_bounds_uniform_ < 0 || texture_uniform < 0) {
            NTK_LOGE("required shader uniform missing yBounds=%d texture=%d",
                     y_bounds_uniform_, texture_uniform);
            return false;
        }
        constexpr float unit_quad_vertices[] = {
            -1.0F, 0.0F, 0.0F, 0.0F,
             1.0F, 0.0F, 1.0F, 0.0F,
            -1.0F, 1.0F, 0.0F, 1.0F,
            -1.0F, 1.0F, 0.0F, 1.0F,
             1.0F, 0.0F, 1.0F, 0.0F,
             1.0F, 1.0F, 1.0F, 1.0F,
        };
        glGenVertexArrays(1, &vao_);
        glGenBuffers(1, &vbo_);
        glBindVertexArray(vao_);
        glBindBuffer(GL_ARRAY_BUFFER, vbo_);
        // Geometry is created once during PRE_STAGE initialization. Active input frames may
        // change only draw uniforms; they must never upload or rename a buffer resource.
        glBufferData(GL_ARRAY_BUFFER, sizeof(unit_quad_vertices), unit_quad_vertices,
                     GL_STATIC_DRAW);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, sizeof(float) * 4, nullptr);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, sizeof(float) * 4,
                              reinterpret_cast<void*>(sizeof(float) * 2));
        glBindVertexArray(0);
        glUseProgram(program_);
        glUniform1i(texture_uniform, 0);
        glUseProgram(0);
        return glGetError() == GL_NO_ERROR;
    }

    bool resource_worker_owns(std::int64_t authority_generation,
                              std::int64_t authority) const {
        if (authority_generation <= 0 || authority <= 0) return false;
        const std::int64_t observed_generation =
            resource_worker_owner_authority_generation_.load(std::memory_order_acquire);
        const std::int64_t observed_authority =
            resource_worker_owner_authority_.load(std::memory_order_acquire);
        return observed_generation == authority_generation &&
            observed_authority == authority;
    }

    bool publish_resource_worker_owner(const AuthorityKey& owner) {
        if (owner.engine_generation != engine_generation_ ||
            owner.authority_generation <= 0 || owner.authority <= 0) return false;
        if (resource_worker_owner_authority_generation_.load(
                std::memory_order_acquire) != 0 ||
            resource_worker_owner_authority_.load(std::memory_order_acquire) != 0) {
            return resource_worker_owns(owner.authority_generation, owner.authority);
        }
        resource_worker_owner_authority_.store(owner.authority, std::memory_order_relaxed);
        resource_worker_owner_authority_generation_.store(
            owner.authority_generation, std::memory_order_release);
        return true;
    }

    void clear_resource_worker_owner() {
        // Generation is the acquire/release publication gate for the identity pair.
        resource_worker_owner_authority_generation_.store(0, std::memory_order_release);
        resource_worker_owner_authority_.store(0, std::memory_order_relaxed);
    }

    std::uint64_t record_resource_worker_context_created() {
        bool expected = false;
        if (!resource_worker_context_counted_alive_.compare_exchange_strong(
                expected, true, std::memory_order_acq_rel, std::memory_order_acquire)) {
            NTK_LOGE("fatal resource worker context created while predecessor is counted alive");
            return 0;
        }
        const std::uint64_t generation =
            resource_worker_generation_.fetch_add(1, std::memory_order_acq_rel) + 1;
        resource_worker_create_count_.fetch_add(1, std::memory_order_acq_rel);
        resource_worker_context_created_ns_.store(
            monotonic_now_ns(), std::memory_order_release);
        return generation;
    }

    void record_resource_worker_context_destroyed() {
        const bool counted = resource_worker_context_counted_alive_.exchange(
            false, std::memory_order_acq_rel);
        upload_context_alive_.store(false, std::memory_order_release);
        active_resource_worker_count_.store(0, std::memory_order_release);
        clear_resource_worker_owner();
        if (!counted) return;
        const std::int64_t destroyed_ns = monotonic_now_ns();
        resource_worker_destroy_count_.fetch_add(1, std::memory_order_acq_rel);
        resource_worker_context_destroyed_ns_.store(
            destroyed_ns, std::memory_order_release);
        upload_context_destroyed_ns_.store(destroyed_ns, std::memory_order_release);
    }

    void record_resource_worker_thread_joined() {
        resource_worker_thread_joined_ns_.store(
            monotonic_now_ns(), std::memory_order_release);
    }

    bool create_resource_worker_context(const AuthorityKey& owner) {
        if (display_ == EGL_NO_DISPLAY || config_ == nullptr ||
            render_context_ == EGL_NO_CONTEXT ||
            owner.engine_generation != engine_generation_ ||
            owner.authority_generation <= 0 || owner.authority <= 0) return false;
        if (upload_context_alive_.load(std::memory_order_acquire) ||
            gpu_resource_worker_state_.load(std::memory_order_acquire) ==
                GpuResourceWorkerState::PRE_STAGE_ACTIVE ||
            active_resource_worker_count_.load(std::memory_order_acquire) != 0 ||
            resource_worker_create_count_.load(std::memory_order_acquire) !=
                resource_worker_destroy_count_.load(std::memory_order_acquire)) {
            return false;
        }
        if (upload_thread_.joinable()) {
            bool exited = false;
            {
                std::lock_guard<std::mutex> lock(mutex_);
                exited = upload_exited_;
            }
            if (!exited) return false;
            upload_thread_.join();
            record_resource_worker_thread_joined();
        }
        if (resource_worker_owner_authority_generation_.load(
                std::memory_order_acquire) != 0 ||
            resource_worker_owner_authority_.load(std::memory_order_acquire) != 0) {
            NTK_LOGE("fatal retired resource worker retained owner generation=%lld authority=%lld",
                     static_cast<long long>(resource_worker_owner_authority_generation_.load(
                         std::memory_order_acquire)),
                     static_cast<long long>(resource_worker_owner_authority_.load(
                         std::memory_order_acquire)));
            return false;
        }

        constexpr EGLint default_context_attributes[] = {
            EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE
        };
        constexpr EGLint upload_priority_attributes[] = {
            EGL_CONTEXT_CLIENT_VERSION, 3,
            EGL_CONTEXT_PRIORITY_LEVEL_IMG, EGL_CONTEXT_PRIORITY_LOW_IMG,
            EGL_NONE
        };
        const char* extensions = eglQueryString(display_, EGL_EXTENSIONS);
        const bool priority_supported = extensions != nullptr &&
            std::strstr(extensions, "EGL_IMG_context_priority") != nullptr;
        const bool surfaceless_supported = extensions != nullptr &&
            std::strstr(extensions, "EGL_KHR_surfaceless_context") != nullptr;
        upload_context_ = eglCreateContext(
            display_, config_, render_context_,
            priority_supported ? upload_priority_attributes : default_context_attributes);
        if (upload_context_ == EGL_NO_CONTEXT && priority_supported) {
            upload_context_ = eglCreateContext(
                display_, config_, render_context_, default_context_attributes);
        }
        if (upload_context_ == EGL_NO_CONTEXT) return false;
        egl_context_create_count_.fetch_add(1, std::memory_order_acq_rel);
        const std::uint64_t worker_generation =
            record_resource_worker_context_created();
        if (worker_generation == 0) {
            eglDestroyContext(display_, upload_context_);
            upload_context_ = EGL_NO_CONTEXT;
            return false;
        }
        const auto destroy_unstarted_worker = [&]() {
            if (upload_pbuffer_ != EGL_NO_SURFACE &&
                eglDestroySurface(display_, upload_pbuffer_) == EGL_TRUE) {
                upload_pbuffer_ = EGL_NO_SURFACE;
            }
            if (upload_context_ != EGL_NO_CONTEXT &&
                eglDestroyContext(display_, upload_context_) == EGL_TRUE) {
                upload_context_ = EGL_NO_CONTEXT;
            }
            if (upload_context_ == EGL_NO_CONTEXT) {
                record_resource_worker_context_destroyed();
            }
        };
        EGLint surface_type = 0;
        if (eglGetConfigAttrib(display_, config_, EGL_SURFACE_TYPE, &surface_type) != EGL_TRUE) {
            destroy_unstarted_worker();
            return false;
        }
        if ((surface_type & EGL_PBUFFER_BIT) != 0) {
            constexpr EGLint pbuffer_attributes[] = {
                EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE
            };
            upload_pbuffer_ = eglCreatePbufferSurface(
                display_, config_, pbuffer_attributes);
            if (upload_pbuffer_ == EGL_NO_SURFACE) {
                destroy_unstarted_worker();
                return false;
            }
        } else if (!surfaceless_supported) {
            destroy_unstarted_worker();
            return false;
        }
        if (!publish_resource_worker_owner(owner)) {
            destroy_unstarted_worker();
            return false;
        }
        {
            std::lock_guard<std::mutex> lock(mutex_);
            upload_exit_requested_ = false;
            upload_exited_ = false;
            upload_seal_requested_ = false;
            upload_sealed_ = false;
            upload_start_generation_ = worker_generation;
            upload_start_owner_ = owner;
            upload_start_state_ = ResourceWorkerStartState::STARTING;
        }
        upload_thread_ = std::thread(
            &StripRenderer::upload_loop, this,
            ResourceWorkerLaunch{worker_generation, owner});
        upload_condition_.notify_all();

        bool ready = false;
        {
            std::unique_lock<std::mutex> lock(mutex_);
            upload_start_condition_.wait(lock, [&] {
                return upload_start_generation_ == worker_generation &&
                    (upload_start_state_ == ResourceWorkerStartState::READY ||
                     upload_start_state_ == ResourceWorkerStartState::FAILED);
            });
            ready = upload_start_state_ == ResourceWorkerStartState::READY;
            if (!ready) {
                upload_exit_condition_.wait(lock, [&] { return upload_exited_; });
            }
        }
        if (!ready) {
            if (upload_thread_.joinable()) {
                upload_thread_.join();
                record_resource_worker_thread_joined();
            }
            destroy_unstarted_worker();
            gpu_resource_worker_state_.store(
                GpuResourceWorkerState::FAILED, std::memory_order_release);
            return false;
        }

        upload_context_alive_.store(true, std::memory_order_release);
        active_resource_worker_count_.store(1, std::memory_order_release);
        gpu_resource_worker_state_.store(
            GpuResourceWorkerState::PRE_STAGE_ACTIVE, std::memory_order_release);
        resource_worker_ready_ns_.store(monotonic_now_ns(), std::memory_order_release);
        return true;
    }

    bool wait_for_resource_worker_owner_release(const AuthorityKey& owner) {
        upload_condition_.notify_one();
        std::unique_lock<std::mutex> lock(mutex_);
        render_condition_.wait(lock, [&] {
            const auto tracker = release_trackers_.find(owner);
            return stopped_ || tracker == release_trackers_.end() ||
                tracker->second->physical_complete ||
                tracker->second->lifecycle == AuthorityLifecycle::FAILED;
        });
        const auto tracker = release_trackers_.find(owner);
        return !stopped_ && tracker != release_trackers_.end() &&
            tracker->second->physical_complete &&
            tracker->second->lifecycle != AuthorityLifecycle::FAILED;
    }

    bool prepare_resource_worker_for_bind(const AuthorityKey& successor) {
        const GpuResourceWorkerState state =
            gpu_resource_worker_state_.load(std::memory_order_acquire);
        if (state == GpuResourceWorkerState::FAILED ||
            state == GpuResourceWorkerState::SEALING) return false;

        if (state == GpuResourceWorkerState::PRE_STAGE_ACTIVE) {
            if (!upload_context_alive_.load(std::memory_order_acquire) ||
                active_resource_worker_count_.load(std::memory_order_acquire) != 1) {
                return false;
            }
            const std::int64_t owner_generation =
                resource_worker_owner_authority_generation_.load(
                    std::memory_order_acquire);
            const std::int64_t owner_authority =
                resource_worker_owner_authority_.load(std::memory_order_acquire);
            if (owner_generation == 0 && owner_authority == 0) {
                // The renderer's initial worker generation belongs to the first authority.
                return publish_resource_worker_owner(successor);
            }
            if (owner_generation <= 0 || owner_authority <= 0) return false;
            if (owner_generation == successor.authority_generation &&
                owner_authority == successor.authority) return true;

            const AuthorityKey predecessor{
                engine_generation_, owner_generation, owner_authority};
            {
                std::lock_guard<std::mutex> lock(mutex_);
                if (release_trackers_.find(predecessor) == release_trackers_.end()) {
                    NTK_LOGE("fatal successor bind lacks worker-owner tracker generation=%lld authority=%lld",
                             static_cast<long long>(owner_generation),
                             static_cast<long long>(owner_authority));
                    return false;
                }
            }
            // The predecessor generation owns all of its cleanup.  No successor allocation is
            // queued until that inventory is physically complete and its context/thread are
            // destroyed and joined.
            const bool release_complete =
                wait_for_resource_worker_owner_release(predecessor);
            const bool worker_retired = retire_resource_worker(predecessor);
            if (!release_complete || !worker_retired) return false;
        } else if (upload_context_alive_.load(std::memory_order_acquire) ||
                   active_resource_worker_count_.load(std::memory_order_acquire) != 0) {
            return false;
        }

        return create_resource_worker_context(successor);
    }

    void signal_external_render_event() noexcept {
        /*
         * Every predicate observed by render_condition_ is synchronized by mutex_.
         * SurfaceControl and Swappy callbacks publish their payload under different
         * internal locks, so a bare notify can land after the render predicate was
         * checked but before wait() releases mutex_, leaving a queued event asleep
         * until an unrelated input or lifecycle command arrives. Advancing the
         * renderer-owned generation under the same mutex closes that lost-wake
         * window for every external callback source.
         */
        {
            std::lock_guard<std::mutex> lock(mutex_);
            ++command_generation_;
        }
        render_condition_.notify_one();
    }

    static void wake_for_present_event(void* context) noexcept {
        auto* renderer = static_cast<StripRenderer*>(context);
        if (renderer != nullptr) renderer->signal_external_render_event();
    }

    bool attach_window(ANativeWindow* window, int width, int height,
                       std::uint64_t refresh_period_ns,
                       std::uint64_t surface_epoch) {
        if (surface_control_attach_count_.load(std::memory_order_acquire) == 0 &&
            (detached_warm_ready_ns_.load(std::memory_order_acquire) <= 0 ||
             window_frame_id_count_.load(std::memory_order_acquire) != 0 ||
             window_swap_count_.load(std::memory_order_acquire) != 0 ||
             present_backend_attached_)) {
            NTK_LOGE("fatal first attach without exact detached warm proof");
            block_input_and_presentation();
            return false;
        }
        if (attach_authority_failed_.load(std::memory_order_acquire) ||
            authority_failed_.load(std::memory_order_acquire) ||
            window == nullptr || width <= 0 || height <= 0 ||
            surface_epoch == 0) {
            block_input_and_presentation();
            return false;
        }
        detach_window();
        if (present_backend_attached_ || prepared_frame_work_.has_value() ||
            fixed_scheduler_.successor().has_value() ||
            head_frame_state_ != HeadFrameState::EMPTY ||
            !evidence_capsules_drained() ||
            authority_failed_.load(std::memory_order_acquire)) {
            NTK_LOGE("fatal SurfaceControl attach after incomplete predecessor");
            block_input_and_presentation();
            return false;
        }
        block_input_and_presentation();
        if (eglMakeCurrent(
                display_, render_pbuffer_, render_pbuffer_, render_context_) !=
                EGL_TRUE) {
            return false;
        }
        surface_epoch_ = surface_epoch;
        width_ = width;
        height_ = height;
        const std::uint64_t fixed_period = refresh_period_ns > 0
            ? refresh_period_ns : kNinetyHzPeriodNs;
        if (!swappy_ready_ ||
            std::llabs(static_cast<std::int64_t>(fixed_period) -
                static_cast<std::int64_t>(kNinetyHzPeriodNs)) >
                static_cast<std::int64_t>(kRefreshPeriodToleranceNs)) {
            authority_failed_.store(true, std::memory_order_release);
            return false;
        }
        fixed_transport_profile_ = ntk::present::makeFixedTransportProfile(
            static_cast<std::int64_t>(fixed_period),
            kFixedAppVsyncOffsetNs, kFixedPresentationDeadlineNs,
            surface_epoch);
        if (!ntk::present::validFixedTransportProfile(
                fixed_transport_profile_)) {
            authority_failed_.store(true, std::memory_order_release);
            return false;
        }
        SwappyGL_setFixedNonPipelineModeNS(fixed_period);
        swappy_window_begin_ns_.store(monotonic_now_ns(), std::memory_order_release);
        if (!SwappyGL_setWindow(window) || !SwappyGL_isEnabled() ||
            !SwappyGL_isFixedNonPipelineModeForNtk() ||
            SwappyGL_getPipelineModeForNtk() != 0 ||
            !SwappyGL_isBlockingWaitEnabledForNtk() ||
            SwappyGL_hasFatalPacingErrorForNtk()) {
            attach_authority_failed_.store(true, std::memory_order_release);
            authority_failed_.store(true, std::memory_order_release);
            return false;
        }
        swappy_window_end_ns_.store(monotonic_now_ns(), std::memory_order_release);
        surface_control_attach_begin_ns_.store(
            monotonic_now_ns(), std::memory_order_release);
        if (!present_backend_.attach(
                display_, window, static_cast<std::uint32_t>(width),
                static_cast<std::uint32_t>(height), surface_epoch,
                &StripRenderer::wake_for_present_event, this)) {
            NTK_LOGE("fatal SurfaceControl/AHardwareBuffer backend unavailable epoch=%llu",
                static_cast<unsigned long long>(surface_epoch));
            attach_authority_failed_.store(true, std::memory_order_release);
            authority_failed_.store(true, std::memory_order_release);
            return false;
        }
        surface_control_attach_end_ns_.store(
            monotonic_now_ns(), std::memory_order_release);
        present_backend_attached_ = true;
        surface_control_attach_count_.fetch_add(1, std::memory_order_acq_rel);
        fixed_period_ns_ = fixed_period;
        presented_view_state_.scroll_direction = 0;
        presented_view_state_.velocity_px_per_second = 0.0F;
        fixed_scheduler_.reset(presented_view_state_);
        presented_visual_mutation_serial_ = 0;
        successful_swap_count_ = 0;
        latched_proof_count_ = 0;
        terminal_lost_proof_count_ = 0;
        duplicate_frame_id_count_ = 0;
        max_logical_unlatched_submissions_ = 0;
        admission_predecessor_.reset();
        epoch_frame_ids_.clear();
        cadence_qualification_state_.store(
            CadenceQualificationState::NO_SURFACE,
            std::memory_order_release);
        NTK_LOGI(
            "SurfaceControl backend attach-ready width=%d height=%d refreshNs=%llu "
            "surfaceSerial=%llu epoch=%llu",
            width_, height_, static_cast<unsigned long long>(fixed_period_ns_),
            static_cast<unsigned long long>(present_backend_.surfaceSerial()),
            static_cast<unsigned long long>(surface_epoch_));
        return true;
    }


    bool wait_present_join_for_lifecycle() {
        auto joined = [this] {
            return frame_feedback_committed_sequence_.load(
                       std::memory_order_acquire) ==
                frame_feedback_read_sequence_.load(std::memory_order_acquire);
        };
        while (!joined() || present_backend_.hasOutstandingSubmission()) {
            (void)drain_present_events_on_render_thread(
                PresentDrainMode::FORCE_DRAIN);
            if (joined() && !present_backend_.hasOutstandingSubmission()) {
                return true;
            }
            std::unique_lock<std::mutex> lock(mutex_);
            render_condition_.wait(lock, [this, &joined] {
                return (joined() &&
                     !present_backend_.hasOutstandingSubmission()) ||
                    present_backend_.hasPendingEvent() ||
                    fixed_retirement_event_write_sequence_.load(
                        std::memory_order_acquire) !=
                    fixed_retirement_event_read_sequence_.load(
                        std::memory_order_acquire);
            });
        }
        return true;
    }

    void detach_window() {
        if (context_resources_valid_.load(std::memory_order_acquire) &&
            !fixed_scheduler_.normalTerminalConservationExact()) {
            NTK_LOGE("fatal normal detach before terminal conservation");
            authority_failed_.store(true, std::memory_order_release);
            gpu_phase_.store(GpuPhase::FAILED, std::memory_order_release);
            return;
        }
        if (!abort_prepared_frame_for_lifecycle()) return;
        block_input_and_presentation();
        if (!present_backend_attached_) {
            admitted_surface_epoch_.store(0, std::memory_order_release);
            return;
        }
        if (!wait_present_join_for_lifecycle()) return;
        flush_feedback();
        if (!present_backend_.detachAfterEvidenceDrained()) {
            fail_present_event(nullptr, "surface-control-teardown");
            return;
        }
        while (!present_backend_.pool().allFree()) {
            (void)drain_present_events_on_render_thread(
                PresentDrainMode::FORCE_DRAIN);
            if (present_backend_.pool().allFree()) break;
            std::unique_lock<std::mutex> lock(mutex_);
            render_condition_.wait(lock, [this] {
                return stopped_ || present_backend_.hasPendingEvent();
            });
            if (stopped_) return;
        }
        (void)drain_present_events_on_render_thread(
            PresentDrainMode::FORCE_DRAIN);
        if (!present_backend_.destroy()) {
            fail_present_event(nullptr, "surface-control-destroy-invariant");
            return;
        }
        present_backend_attached_ = false;
        admitted_surface_epoch_.store(0, std::memory_order_release);
        admission_predecessor_.reset();
        presented_view_state_.scroll_direction = 0;
        presented_view_state_.velocity_px_per_second = 0.0F;
        cadence_qualification_state_.store(
            CadenceQualificationState::NO_SURFACE,
            std::memory_order_release);
        NTK_LOGI(
            "SurfaceControl backend detached epoch=%llu submitted=%llu "
            "latched=%llu maxUnlatched=%llu",
            static_cast<unsigned long long>(surface_epoch_),
            static_cast<unsigned long long>(successful_swap_count_),
            static_cast<unsigned long long>(latched_proof_count_),
            static_cast<unsigned long long>(max_logical_unlatched_submissions_));
    }


    void destroy_egl() {
        if (display_ == EGL_NO_DISPLAY) {
            config_ = nullptr;
            render_context_ = EGL_NO_CONTEXT;
            upload_context_ = EGL_NO_CONTEXT;
            render_pbuffer_ = EGL_NO_SURFACE;
            upload_pbuffer_ = EGL_NO_SURFACE;
            program_ = 0;
            vao_ = 0;
            vbo_ = 0;
            warm_fbo_ = 0;
            warm_color_renderbuffer_ = 0;
            record_resource_worker_context_destroyed();
            eglReleaseThread();
            return;
        }
        detach_window();
        // A lost context already invalidates every GL object owned by this renderer. Draining or
        // deleting those names on the retiring render thread is both meaningless and harmful:
        // gfxstream serializes that work with a live successor context, so an old-engine ACK can
        // block the successor's causal swap for multiple display periods. Let context destruction
        // reclaim the lost namespace without submitting any more GPU work.
        const bool delete_gl_resources =
            context_resources_valid_.load(std::memory_order_acquire);
        if (delete_gl_resources) glFinish();
        for (auto& entry : scene_) {
            if (delete_gl_resources) glDeleteTextures(1, &entry.second.texture);
        }
        scene_.clear();
        for (auto& retired : resource_deletes_) {
            if (delete_gl_resources && retired.fence != nullptr) glDeleteSync(retired.fence);
            if (delete_gl_resources && retired.texture != 0) glDeleteTextures(1, &retired.texture);
        }
        resource_deletes_.clear();
        resource_delete_depth_mirror_.store(0, std::memory_order_release);
        for (auto& ready : gpu_ready_tiles_) {
            if (delete_gl_resources && ready.upload_fence != nullptr) {
                glDeleteSync(ready.upload_fence);
                upload_gpu_fences_pending_.fetch_sub(1, std::memory_order_acq_rel);
                last_gpu_resource_completion_ns_.store(
                    monotonic_now_ns(), std::memory_order_release);
            }
            if (delete_gl_resources && ready.texture != 0) glDeleteTextures(1, &ready.texture);
        }
        gpu_ready_tiles_.clear();
        ready_tile_queue_depth_mirror_.store(0, std::memory_order_release);
        for (auto& entry : preallocated_textures_) {
            if (delete_gl_resources && entry.second.texture != 0) {
                glDeleteTextures(1, &entry.second.texture);
            }
        }
        preallocated_textures_.clear();
        for (auto& entry : prepared_bank_) {
            if (delete_gl_resources && entry.second.texture != 0) {
                glDeleteTextures(1, &entry.second.texture);
            }
        }
        prepared_bank_.clear();
        for (auto& tracker_entry : release_trackers_) {
            auto& tracker = *tracker_entry.second;
            if (delete_gl_resources && tracker.render_fence != nullptr) {
                glDeleteSync(tracker.render_fence);
            }
            tracker.render_fence = nullptr;
            for (auto& entry : tracker.scene) {
                if (delete_gl_resources && entry.second.texture != 0) {
                    glDeleteTextures(1, &entry.second.texture);
                }
            }
            tracker.scene.clear();
            for (auto& ready : tracker.ready_tiles) {
                if (delete_gl_resources && ready.upload_fence != nullptr) {
                    glDeleteSync(ready.upload_fence);
                }
                if (delete_gl_resources && ready.texture != 0) {
                    glDeleteTextures(1, &ready.texture);
                }
            }
            tracker.ready_tiles.clear();
            for (auto& retired : tracker.resource_deletes) {
                if (delete_gl_resources && retired.fence != nullptr) {
                    glDeleteSync(retired.fence);
                }
                if (delete_gl_resources && retired.texture != 0) {
                    glDeleteTextures(1, &retired.texture);
                }
            }
            tracker.resource_deletes.clear();
            for (auto& entry : tracker.preallocated_textures) {
                if (delete_gl_resources && entry.second.texture != 0) {
                    glDeleteTextures(1, &entry.second.texture);
                }
            }
            tracker.preallocated_textures.clear();
            for (auto& entry : tracker.prepared_bank) {
                if (delete_gl_resources && entry.second.texture != 0) {
                    glDeleteTextures(1, &entry.second.texture);
                }
            }
            tracker.prepared_bank.clear();
        }
        if (delete_gl_resources && vbo_ != 0) glDeleteBuffers(1, &vbo_);
        if (delete_gl_resources && vao_ != 0) glDeleteVertexArrays(1, &vao_);
        if (delete_gl_resources && warm_color_renderbuffer_ != 0) {
            glDeleteRenderbuffers(1, &warm_color_renderbuffer_);
        }
        if (delete_gl_resources && warm_fbo_ != 0) {
            glDeleteFramebuffers(1, &warm_fbo_);
        }
        if (delete_gl_resources && program_ != 0) glDeleteProgram(program_);
        if (delete_gl_resources) {
            eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        }
        if (render_pbuffer_ != EGL_NO_SURFACE) eglDestroySurface(display_, render_pbuffer_);
        if (upload_pbuffer_ != EGL_NO_SURFACE) eglDestroySurface(display_, upload_pbuffer_);
        if (upload_context_ != EGL_NO_CONTEXT) eglDestroyContext(display_, upload_context_);
        if (render_context_ != EGL_NO_CONTEXT) eglDestroyContext(display_, render_context_);
        eglTerminate(display_);
        record_resource_worker_context_destroyed();
        display_ = EGL_NO_DISPLAY;
        config_ = nullptr;
        render_context_ = EGL_NO_CONTEXT;
        upload_context_ = EGL_NO_CONTEXT;
        render_pbuffer_ = EGL_NO_SURFACE;
        upload_pbuffer_ = EGL_NO_SURFACE;
        program_ = 0;
        vao_ = 0;
        vbo_ = 0;
        warm_fbo_ = 0;
        warm_color_renderbuffer_ = 0;
        y_bounds_uniform_ = -1;
        eglReleaseThread();
    }

    bool wait_render_resource_fence(GLsync fence) {
        if (fence == nullptr) return false;
        const GLenum status = glClientWaitSync(
            fence, GL_SYNC_FLUSH_COMMANDS_BIT, GL_TIMEOUT_IGNORED);
        return status == GL_ALREADY_SIGNALED ||
            status == GL_CONDITION_SATISFIED;
    }

    bool all_predecessor_gpu_work_complete(std::int64_t* completion_ns) {
        std::int64_t latest = 0;
        std::lock_guard<std::mutex> lock(mutex_);
        for (const auto& entry : release_trackers_) {
            const auto& tracker = *entry.second;
            if (tracker.lifecycle == AuthorityLifecycle::FAILED ||
                !tracker.physical_complete) return false;
            latest = std::max(latest, tracker.physical_complete_ns);
        }
        if (completion_ns != nullptr) *completion_ns = latest;
        return true;
    }

    static std::int64_t rgba8_bytes(int width, int height) {
        if (width <= 0 || height <= 0) return 0;
        const std::int64_t pixels = static_cast<std::int64_t>(width) * height;
        if (pixels > std::numeric_limits<std::int64_t>::max() / 4LL) return 0;
        return pixels * 4LL;
    }

    static void append_gpu_scene_header(
            Sha256& digest, const std::string& geometry_digest,
            const std::string& pregeometry_root_digest, std::size_t count) {
        sha256_token(digest, "ntk-gpu-scene-v1");
        sha256_token(digest, geometry_digest);
        sha256_token(digest, pregeometry_root_digest);
        sha256_token(digest, "RGBA8_UNORM");
        sha256_token(digest, std::to_string(count));
    }

    static void append_gpu_scene_tile(
            Sha256& digest, const TileKey& key, int width, int height,
            std::int64_t content_top, std::int64_t content_bottom,
            std::int64_t rgba_bytes) {
        sha256_token(digest, std::to_string(key.page));
        sha256_token(digest, std::to_string(key.slot));
        sha256_token(digest, std::to_string(width));
        sha256_token(digest, std::to_string(height));
        sha256_token(digest, std::to_string(content_top));
        sha256_token(digest, std::to_string(content_bottom));
        sha256_token(digest, std::to_string(rgba_bytes));
    }

    static std::string gpu_scene_digest_from_slots(
            const std::string& geometry_digest,
            const std::string& pregeometry_root_digest,
            const std::vector<PreallocateCommand>& slots) {
        std::vector<const PreallocateCommand*> ordered(slots.size(), nullptr);
        for (const auto& slot : slots) {
            if (slot.ordinal < 0 || static_cast<std::size_t>(slot.ordinal) >= slots.size() ||
                ordered[static_cast<std::size_t>(slot.ordinal)] != nullptr) return {};
            ordered[static_cast<std::size_t>(slot.ordinal)] = &slot;
        }
        Sha256 digest;
        append_gpu_scene_header(
            digest, geometry_digest, pregeometry_root_digest, ordered.size());
        for (const auto* slot : ordered) {
            if (slot == nullptr) return {};
            append_gpu_scene_tile(
                digest, slot->key, slot->width, slot->height,
                slot->content_top, slot->content_bottom,
                rgba8_bytes(slot->width, slot->height));
        }
        return digest.finish();
    }

    std::string gpu_scene_digest_from_resident_locked() const {
        if (ordinal_keys_.size() != expected_tile_count_ ||
            scene_.size() != expected_tile_count_) return {};
        Sha256 digest;
        append_gpu_scene_header(
            digest, current_geometry_digest_, current_pregeometry_root_digest_,
            ordinal_keys_.size());
        for (const TileKey& key : ordinal_keys_) {
            const auto found = scene_.find(key);
            if (found == scene_.end()) return {};
            const SceneTile& tile = found->second;
            append_gpu_scene_tile(
                digest, key, tile.width, tile.height, tile.content_top,
                tile.content_bottom, tile.rgba_bytes);
        }
        return digest.finish();
    }

    std::string prepared_inventory_digest_locked() const {
        Sha256 digest;
        sha256_token(digest, "ntk-native-prepared-inventory-v1");
        std::vector<std::pair<TileKey, const PreparedBankTile*>> ordered;
        ordered.reserve(prepared_bank_.size());
        for (const auto& entry : prepared_bank_) {
            ordered.emplace_back(entry.first, &entry.second);
        }
        std::sort(ordered.begin(), ordered.end(), [](const auto& left, const auto& right) {
            return std::tie(left.first.page, left.first.slot) <
                std::tie(right.first.page, right.first.slot);
        });
        for (const auto& entry : ordered) {
            const TileKey& key = entry.first;
            const PreparedBankTile& tile = *entry.second;
            sha256_token(digest, std::to_string(key.page));
            sha256_token(digest, std::to_string(key.slot));
            sha256_token(digest, tile.tile_proof_digest);
            sha256_token(digest, std::to_string(tile.rgba_bytes));
        }
        return digest.finish();
    }

    std::string resident_scene_inventory_digest_locked() const {
        Sha256 digest;
        sha256_token(digest, "ntk-native-resident-inventory-v1");
        sha256_token(digest, current_geometry_digest_);
        std::vector<std::pair<TileKey, const SceneTile*>> ordered;
        ordered.reserve(scene_.size());
        for (const auto& entry : scene_) ordered.emplace_back(entry.first, &entry.second);
        std::sort(ordered.begin(), ordered.end(), [](const auto& left, const auto& right) {
            return std::tie(left.first.page, left.first.slot) <
                std::tie(right.first.page, right.first.slot);
        });
        for (const auto& entry : ordered) {
            const SceneTile& tile = *entry.second;
            sha256_token(digest, std::to_string(entry.first.page));
            sha256_token(digest, std::to_string(entry.first.slot));
            sha256_token(digest, std::to_string(tile.admission_id));
            sha256_token(digest, std::to_string(tile.resource_revision));
            sha256_token(digest, std::to_string(tile.install_lease));
            sha256_token(digest, std::to_string(tile.rgba_bytes));
        }
        return digest.finish();
    }

    void fail_gpu_scene_admission(const char* reason) {
        {
            std::lock_guard<std::mutex> lock(mutex_);
            gpu_scene_admission_.fail();
            ++command_generation_;
        }
        NTK_LOGE("fatal GPU scene admission failure: %s", reason);
        block_input_and_presentation();
        upload_submission_blocked_.store(true, std::memory_order_release);
        authority_failed_.store(true, std::memory_order_release);
        engine_failed_.store(true, std::memory_order_release);
        gpu_phase_.store(GpuPhase::FAILED, std::memory_order_release);
        preparation_drain_condition_.notify_all();
        render_condition_.notify_all();
        upload_condition_.notify_all();
    }

    bool gpu_scene_exact_resident_locked() const {
        return gpu_scene_admission_.exact_resident() &&
            gpu_scene_admission_.expected_texture_count == expected_tile_count_ &&
            gpu_scene_admission_.resident_texture_count == scene_.size() &&
            gpu_scene_admission_.resident_digest == gpu_scene_digest_from_resident_locked();
    }

    bool preparation_drain_ready_locked() const {
        return native_outstanding_ == 0 && !upload_active_ &&
            preallocate_commands_.empty() && preallocated_textures_.empty() &&
            upload_commands_.empty() && !in_flight_upload_.has_value() &&
            gpu_ready_tiles_.empty() && resource_deletes_.empty() &&
            !in_flight_resource_delete_.has_value() && retire_intents_.empty() &&
            pending_publish_acks_.empty() && prepared_bank_.empty() &&
            upload_commands_submitting_.load(std::memory_order_acquire) == 0 &&
            upload_gpu_fences_pending_.load(std::memory_order_acquire) == 0 &&
            gpu_scene_exact_resident_locked();
    }

    bool stage_seal_prerequisites_ready() {
        if (!stage_requested_ || stage_corridor_start_ != 0 ||
            stage_corridor_end_ != content_height_ || content_height_ <= 0) return false;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (!preallocate_commands_.empty() || !upload_commands_.empty() ||
                in_flight_upload_.has_value() || !gpu_ready_tiles_.empty() ||
                !resource_deletes_.empty() || in_flight_resource_delete_.has_value() ||
                !retire_intents_.empty() || native_outstanding_ != 0 ||
                upload_active_ || !pending_publish_acks_.empty() ||
                !gpu_scene_exact_resident_locked()) return false;
        }
        if (upload_commands_submitting_.load(std::memory_order_acquire) != 0 ||
            upload_gpu_fences_pending_.load(std::memory_order_acquire) != 0 ||
            scene_.size() != expected_tile_count_ ||
            !resident_contains(0, content_height_)) return false;
        return all_predecessor_gpu_work_complete(nullptr);
    }

    bool stage_seal_permanently_blocked() {
        if (gpu_resource_worker_state_.load(std::memory_order_acquire) ==
            GpuResourceWorkerState::FAILED) return true;
        bool drained = false;
        bool predecessor_failed = false;
        bool ledger_exact = false;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (gpu_scene_admission_.state == GpuSceneAdmissionState::FAILED) return true;
            drained = preallocate_commands_.empty() && upload_commands_.empty() &&
                !in_flight_upload_.has_value() && gpu_ready_tiles_.empty() &&
                resource_deletes_.empty() &&
                !in_flight_resource_delete_.has_value() && retire_intents_.empty() &&
                native_outstanding_ == 0 && !upload_active_ &&
                pending_publish_acks_.empty();
            predecessor_failed = std::any_of(
                release_trackers_.begin(), release_trackers_.end(),
                [](const auto& entry) {
                    return entry.second->lifecycle == AuthorityLifecycle::FAILED;
                });
            ledger_exact = gpu_scene_admission_.exact_resident();
        }
        if (predecessor_failed) return true;
        return drained &&
            (upload_commands_submitting_.load(std::memory_order_acquire) == 0 &&
             upload_gpu_fences_pending_.load(std::memory_order_acquire) == 0) &&
            (scene_.size() != expected_tile_count_ ||
             !resident_contains(0, content_height_) ||
             !ledger_exact);
    }

    bool build_sealed_draw_index() {
        SealedDrawIndex next;
        next.scene_version = scene_version_;
        next.content_height = content_height_;
        next.resource_submit_serial = resource_submit_serial_.load(
            std::memory_order_acquire);
        next.by_content_top.reserve(scene_.size());
        for (const auto& entry : scene_) {
            const SceneTile& tile = entry.second;
            if (tile.texture == 0 || tile.content_top < 0 ||
                tile.content_bottom <= tile.content_top ||
                tile.content_bottom > content_height_) return false;
            next.by_content_top.push_back(SealedDrawTile{
                tile.texture, tile.content_top, tile.content_bottom,
                entry.first.page, entry.first.slot});
        }
        std::sort(next.by_content_top.begin(), next.by_content_top.end(),
                  [](const SealedDrawTile& left, const SealedDrawTile& right) {
                      return std::tie(left.content_top, left.content_bottom,
                                      left.page, left.slot) <
                          std::tie(right.content_top, right.content_bottom,
                                   right.page, right.slot);
                  });
        if (next.by_content_top.size() != expected_tile_count_) return false;
        Sha256 digest;
        digest.update(current_geometry_digest_.data(), current_geometry_digest_.size());
        for (const auto& tile : next.by_content_top) {
            digest.update(&tile.content_top, sizeof(tile.content_top));
            digest.update(&tile.content_bottom, sizeof(tile.content_bottom));
            digest.update(&tile.page, sizeof(tile.page));
            digest.update(&tile.slot, sizeof(tile.slot));
        }
        next.scene_digest = digest.finish();
        sealed_draw_index_ = std::move(next);
        return is_sha256(sealed_draw_index_.scene_digest);
    }

    int draw_sealed_scene_to_current_framebuffer(std::int64_t view_top) {
        if (sealed_draw_index_.by_content_top.empty() || width_ <= 0 || height_ <= 0 ||
            viewport_height_ <= 0) return 0;
        glViewport(0, 0, width_, height_);
        glDisable(GL_BLEND);
        glClearColor(0.0F, 0.0F, 0.0F, 1.0F);
        glClear(GL_COLOR_BUFFER_BIT);
        glUseProgram(program_);
        glBindVertexArray(vao_);
        glActiveTexture(GL_TEXTURE0);
        const std::int64_t view_end = std::min<std::int64_t>(
            content_height_, view_top + viewport_height_);
        const auto begin = std::lower_bound(
            sealed_draw_index_.by_content_top.begin(),
            sealed_draw_index_.by_content_top.end(), view_top,
            [](const SealedDrawTile& tile, std::int64_t value) {
                return tile.content_bottom <= value;
            });
        const auto end = std::lower_bound(
            begin, sealed_draw_index_.by_content_top.end(), view_end,
            [](const SealedDrawTile& tile, std::int64_t value) {
                return tile.content_top < value;
            });
        const float view_height = static_cast<float>(std::max(1, viewport_height_));
        int draws = 0;
        for (auto iterator = begin; iterator != end; ++iterator) {
            const float top = static_cast<float>(iterator->content_top - view_top);
            const float bottom = static_cast<float>(iterator->content_bottom - view_top);
            const float top_ndc = ntk::present::ahbCompositorNdcY(top, view_height);
            const float bottom_ndc = ntk::present::ahbCompositorNdcY(bottom, view_height);
            glUniform2f(y_bounds_uniform_, top_ndc, bottom_ndc);
            glBindTexture(GL_TEXTURE_2D, iterator->texture);
            glDrawArrays(GL_TRIANGLES, 0, 6);
            ++draws;
        }
        glBindVertexArray(0);
        glUseProgram(0);
        return glGetError() == GL_NO_ERROR ? draws : 0;
    }

    bool warm_production_draw_path() {
        if (warm_fbo_ == 0) glGenFramebuffers(1, &warm_fbo_);
        if (warm_color_renderbuffer_ == 0) {
            glGenRenderbuffers(1, &warm_color_renderbuffer_);
        }
        glBindRenderbuffer(GL_RENDERBUFFER, warm_color_renderbuffer_);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_RGBA8,
                              std::max(1, width_), std::max(1, height_));
        glBindFramebuffer(GL_FRAMEBUFFER, warm_fbo_);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                                  GL_RENDERBUFFER, warm_color_renderbuffer_);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            return false;
        }
        const int draws = draw_sealed_scene_to_current_framebuffer(
            presented_view_state_.scroll_top);
        GLsync fence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        glFlush();
        resource_submit_serial_.fetch_add(1, std::memory_order_acq_rel);
        const bool complete = draws > 0 && wait_render_resource_fence(fence);
        if (fence != nullptr) glDeleteSync(fence);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        if (!complete) return false;
        offscreen_warm_draw_count_.fetch_add(
            static_cast<std::uint64_t>(draws), std::memory_order_acq_rel);
        offscreen_warm_fence_completion_ns_.store(
            monotonic_now_ns(), std::memory_order_release);
        return true;
    }

    bool retire_resource_worker(const AuthorityKey& expected_owner) {
        if (expected_owner.engine_generation != engine_generation_ ||
            !resource_worker_owns(expected_owner.authority_generation,
                                  expected_owner.authority) ||
            !upload_context_alive_.load(std::memory_order_acquire) ||
            active_resource_worker_count_.load(std::memory_order_acquire) != 1 ||
            gpu_resource_worker_state_.load(std::memory_order_acquire) !=
                GpuResourceWorkerState::PRE_STAGE_ACTIVE) {
            return false;
        }
        gpu_resource_worker_state_.store(
            GpuResourceWorkerState::SEALING, std::memory_order_release);
        {
            std::lock_guard<std::mutex> lock(mutex_);
            upload_seal_requested_ = true;
        }
        upload_condition_.notify_one();
        bool sealed = false;
        {
            std::unique_lock<std::mutex> lock(mutex_);
            upload_seal_condition_.wait(lock, [&] {
                return upload_sealed_ || upload_exited_ || stopped_;
            });
            sealed = upload_sealed_ &&
                gpu_resource_worker_state_.load(std::memory_order_acquire) ==
                    GpuResourceWorkerState::RETIRED;
        }
        if (upload_thread_.joinable()) {
            upload_thread_.join();
            record_resource_worker_thread_joined();
        }
        return sealed && !upload_context_alive_.load(std::memory_order_acquire) &&
            active_resource_worker_count_.load(std::memory_order_acquire) == 0 &&
            resource_worker_owner_authority_generation_.load(
                std::memory_order_acquire) == 0 &&
            resource_worker_owner_authority_.load(std::memory_order_acquire) == 0;
    }

    bool seal_scene_for_input() {
        if (!stage_seal_prerequisites_ready() || !build_sealed_draw_index() ||
            !warm_production_draw_path()) return false;
        std::int64_t predecessor_complete_ns = 0;
        if (!all_predecessor_gpu_work_complete(&predecessor_complete_ns)) return false;
        predecessor_physical_complete_ns_.store(
            predecessor_complete_ns, std::memory_order_release);
        GLsync seal_fence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        glFlush();
        resource_submit_serial_.fetch_add(1, std::memory_order_acq_rel);
        const bool seal_complete = wait_render_resource_fence(seal_fence);
        if (seal_fence != nullptr) glDeleteSync(seal_fence);
        if (!seal_complete) return false;
        const std::int64_t seal_completion_ns = monotonic_now_ns();
        seal_fence_completion_ns_.store(seal_completion_ns, std::memory_order_release);
        bool seal_ledger_valid = true;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            seal_ledger_valid = gpu_scene_exact_resident_locked() &&
                gpu_scene_admission_.seal(
                    last_gpu_resource_completion_ns_.load(std::memory_order_acquire),
                    seal_completion_ns);
        }
        if (!seal_ledger_valid) {
            fail_gpu_scene_admission("GPU scene seal proof mismatch");
            return false;
        }
        {
            std::lock_guard<std::mutex> lock(mutex_);
            seal_barrier_serial_.store(
                next_release_protocol_serial_locked(), std::memory_order_release);
        }
        if (!retire_resource_worker(current_authority_key())) return false;
        const int stage_draws = draw_sealed_scene_to_current_framebuffer(
            presented_view_state_.scroll_top);
        GLsync backbuffer_fence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        glFlush();
        resource_submit_serial_.fetch_add(1, std::memory_order_acq_rel);
        const bool backbuffer_ready = stage_draws > 0 &&
            wait_render_resource_fence(backbuffer_fence);
        if (backbuffer_fence != nullptr) glDeleteSync(backbuffer_fence);
        if (!backbuffer_ready) return false;
        stage_backbuffer_ready_ns_.store(monotonic_now_ns(), std::memory_order_release);
        sealed_resource_submit_serial_.store(
            resource_submit_serial_.load(std::memory_order_acquire), std::memory_order_release);
        sealed_scene_version_ = scene_version_;
        sealed_tile_count_ = scene_.size();
        sealed_content_end_ = content_height_;
        sealed_scene_mutation_count_.store(
            scene_mutation_count_.load(std::memory_order_acquire),
            std::memory_order_release);
        scene_sealed_.store(true, std::memory_order_release);
        GpuPhase expected = GpuPhase::PRE_STAGE_GPU;
        if (!gpu_phase_.compare_exchange_strong(
                expected, GpuPhase::SEALING,
                std::memory_order_acq_rel, std::memory_order_acquire)) return false;
        NTK_LOGI("GPU full scene armed authority=%lld tiles=%zu sceneVersion=%lld serial=%llu",
                 static_cast<long long>(authority_), sealed_tile_count_,
                 static_cast<long long>(sealed_scene_version_),
                 static_cast<unsigned long long>(
                     sealed_resource_submit_serial_.load(std::memory_order_acquire)));
        return true;
    }

    void consume_feedback_failure_commands() {
        if (feedback_cadence_failure_pending_.exchange(
                false, std::memory_order_acq_rel)) {
            cadence_qualification_failed_.store(true, std::memory_order_release);
            cadence_qualification_state_.store(
                CadenceQualificationState::FAILED, std::memory_order_release);
            NTK_LOGE("render owner consumed JNI cadence failure");
        }
        if (feedback_authority_failure_pending_.exchange(
                false, std::memory_order_acq_rel)) {
            authority_failed_.store(true, std::memory_order_release);
            gpu_phase_.store(GpuPhase::FAILED, std::memory_order_release);
            NTK_LOGE("render owner consumed JNI authority failure");
        }
    }

    bool feedback_failure_command_pending() const {
        return feedback_cadence_failure_pending_.load(std::memory_order_acquire) ||
            feedback_authority_failure_pending_.load(std::memory_order_acquire);
    }

    bool has_pending_active_input() {
        bool control_pending = false;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            control_pending = !input_control_commands_.empty();
        }
        if (control_pending) return true;
        std::lock_guard<std::mutex> lock(move_mailbox_mutex_);
        return move_mailbox_sequence_ >
            fixed_scheduler_.reducer().applied_move_sequence;
    }

    bool bind_cancel_requested(const std::shared_ptr<BindTicket>& ticket) {
        std::lock_guard<std::mutex> lock(mutex_);
        return !ticket || ticket->cancel_requested || stopped_;
    }

    void complete_bind_ticket(const std::shared_ptr<BindTicket>& ticket,
                              bool success,
                              std::int64_t accepted_generation = 0) {
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (!ticket || ticket->completed) return;
            ticket->success = success;
            ticket->accepted_authority_generation = success ? accepted_generation : 0;
            ticket->completed = true;
        }
        bind_condition_.notify_all();
    }

    PreparedCommitResult service_ready_prepared_frame() {
        if (!prepared_frame_work_.has_value()) {
            return PreparedCommitResult::FATAL;
        }
        const bool lifecycle_closed = !present_backend_attached_ ||
            !context_resources_valid_.load(std::memory_order_acquire) ||
            presentation_blocked_.load(std::memory_order_acquire) ||
            authority_failed_.load(std::memory_order_acquire);
        if (lifecycle_closed) {
            abort_prepared_frame_for_lifecycle();
            return PreparedCommitResult::FATAL;
        }
        if (fixed_causal_lane_fatal_) {
            fail_prepared_frame("fixed-causal-lane-sticky-fatal");
            return PreparedCommitResult::FATAL;
        }
        if (head_frame_state_ != HeadFrameState::SWAPPY_RESERVED) {
            return PreparedCommitResult::RETAINED;
        }
        const auto opportunity =
            fixed_opportunity_gate_.beginReadyAttempt();
        if (!opportunity.has_value()) {
            return PreparedCommitResult::RETAINED;
        }
        PreparedCommitResult commit_result =
            try_commit_prepared_frame(*opportunity);
        if (commit_result == PreparedCommitResult::SLOT_CLOSED) {
            if (!fixed_opportunity_gate_.finishConsumed(*opportunity)) {
                fail_prepared_frame("closed-opportunity-gate-consume");
                clear_fixed_opportunity_ownership();
                return PreparedCommitResult::FATAL;
            }
            if (prepared_frame_work_.has_value()) {
                prepared_frame_work_->last_consumed_opportunity_sequence =
                    opportunity->opportunity_sequence;
            }
            fixed_scheduler_.noteOpportunityConsumedClosed();
            ++slot_closed_no_attempt_count_;
            return PreparedCommitResult::RETAINED;
        }
        if (commit_result == PreparedCommitResult::FATAL ||
            commit_result == PreparedCommitResult::RETAINED) {
            if (fixed_opportunity_gate_.attemptInFlight()) {
                (void)fixed_opportunity_gate_.finishConsumed(*opportunity);
            }
            fixed_scheduler_.noteOpportunityProtocolFatal();
            clear_fixed_opportunity_ownership();
            if (commit_result != PreparedCommitResult::FATAL) {
                fail_prepared_frame("ready-attempt-retained");
            }
            return PreparedCommitResult::FATAL;
        }
        if (commit_result == PreparedCommitResult::SUBMITTED ||
            commit_result ==
                PreparedCommitResult::SUBMITTED_FATAL_AFTER_EGL_TRUE) {
            if (!fixed_opportunity_gate_.finishConsumed(*opportunity)) {
                fixed_scheduler_.noteOpportunityProtocolFatal();
                clear_fixed_opportunity_ownership();
                return PreparedCommitResult::SUBMITTED_FATAL_AFTER_EGL_TRUE;
            }
            fixed_scheduler_.noteOpportunityConsumedSubmitted();
            clear_fixed_opportunity_ownership();
            const bool submitted_fatal = commit_result ==
                PreparedCommitResult::SUBMITTED_FATAL_AFTER_EGL_TRUE;
            if (!submitted_fatal) refresh_input_phase();
            if (submitted_fatal) {
                head_frame_state_ = HeadFrameState::FAILED;
                block_input_and_presentation();
                engine_failed_.store(true, std::memory_order_release);
                authority_failed_.store(true, std::memory_order_release);
                gpu_phase_.store(GpuPhase::FAILED, std::memory_order_release);
                return commit_result;
            }
            bool deferred_work = false;
            {
                std::lock_guard<std::mutex> lock(mutex_);
                deferred_work = render_requested_ ||
                    !input_control_commands_.empty() ||
                    pending_protection_commit_.has_value() ||
                    !retire_intents_.empty() || !gpu_ready_tiles_.empty() ||
                    deferred_lifecycle_.active || detach_requested_ ||
                    disarm_requested_ || pending_bind_request_.has_value() ||
                    current_authority_has_pending_release_locked();
                if (deferred_work) {
                    render_requested_ = true;
                    ++command_generation_;
                }
            }
            if (deferred_work) render_condition_.notify_one();
        }
        return commit_result;
    }

    void render_loop() {
        // Keep the renderer registered with ART for its entire lifetime. Per-frame JVM
        // attach/detach forces runtime bookkeeping and can rendezvous with GC/input dispatch.
        // Presentation is latency-critical; texture upload/decode lanes are explicitly nice'd
        // down so this owner and Android input dispatch retain CPU under a four-core load.
        request_urgent_display_priority("render-owner");
        const bool initialized = initialize_egl();
        ANativeWindow* initialization_failed_window = nullptr;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            render_initialization_complete_ = true;
            if (!initialized) {
                NTK_LOGE("EGL initialization failed");
                block_input_and_presentation();
                engine_failed_.store(true, std::memory_order_release);
                attach_authority_failed_.store(true, std::memory_order_release);
                authority_failed_.store(true, std::memory_order_release);
                gpu_phase_.store(GpuPhase::FAILED, std::memory_order_release);
                if (attach_request_.has_value()) {
                    attach_request_->state = AttachState::TERMINAL;
                    attach_request_->success = false;
                    attach_request_->completed_ns = monotonic_now_ns();
                    initialization_failed_window = attach_request_->window;
                    attach_request_->window = nullptr;
                }
            }
        }
        if (initialization_failed_window != nullptr) {
            ANativeWindow_release(initialization_failed_window);
            g_renderer_owned_surface_lease_count.fetch_sub(
                1, std::memory_order_acq_rel);
        }
        attach_condition_.notify_all();
        std::uint64_t observed_generation = 0;
        while (initialized) {
            const PresentPumpResult initialPresentPump =
                drain_present_events_on_render_thread(
                    PresentDrainMode::NORMAL);
            bool postApplyPresentCut =
                initialPresentPump == PresentPumpResult::SUBMITTED ||
                initialPresentPump == PresentPumpResult::SUBMITTED_FATAL;
            ANativeWindow* next_window = nullptr;
            int next_width = 0;
            int next_height = 0;
            std::uint64_t next_refresh_period_ns = 0;
            std::uint64_t next_surface_epoch = 0;
            std::uint64_t next_attach_generation = 0;
            std::uint64_t next_geometry_revision = 0;
            ANativeWindow* resize_window = nullptr;
            int resize_width = 0;
            int resize_height = 0;
            std::uint64_t resize_refresh_period_ns = 0;
            std::uint64_t resize_surface_epoch = 0;
            std::uint64_t resize_attach_generation = 0;
            std::uint64_t resize_geometry_revision = 0;
            bool should_detach = false;
            bool should_disarm = false;
            bool should_render = false;
            std::optional<BindRequest> bind_request;
            std::uint64_t snapshot_generation = 0;
            std::vector<RetireIntent> retire_intents;
            std::optional<ProtectionCommit> protection_commit;
            std::optional<ReleaseClaim> release_claim;
            std::vector<InputSample> controls;
            {
                std::unique_lock<std::mutex> lock(mutex_);
                queue_depth_mirrors_exact_locked("render-loop-entry");
                if (gpu_ready_tiles_.empty()) {
                    render_condition_.wait(lock, [&] {
                        return stopped_ || command_generation_ != observed_generation ||
                               !gpu_ready_tiles_.empty() ||
                               normal_present_event_wake_is_actionable() ||
                               fixed_retirement_event_write_sequence_.load(
                                   std::memory_order_acquire) !=
                                   fixed_retirement_event_read_sequence_.load(
                                       std::memory_order_acquire) ||
                               fixed_scheduler_.hasRunnableControlQueue(
                                   input_control_commands_.size()) ||
                               (fixed_scheduler_.successor().has_value() &&
                                 !prepared_frame_work_.has_value() &&
                                 pending_frame_cause_ready_for_preparation() &&
                                 present_backend_.hasPreparationCapacity() &&
                                 present_backend_.pool().hasFreeRenderTarget()) ||
                                 (prepared_frame_work_.has_value() &&
                                  head_frame_state_ ==
                                      HeadFrameState::SWAPPY_RESERVED &&
                                  fixed_opportunity_gate_.hasPending()) ||
                               feedback_failure_command_pending();
                    });
                }
                if (stopped_) break;
                observed_generation = command_generation_;
                snapshot_generation = observed_generation;
                should_detach = detach_requested_;
                should_disarm = disarm_requested_;
                should_render = render_requested_;
                if (pending_bind_request_.has_value()) {
                    bind_request = std::move(pending_bind_request_);
                    pending_bind_request_.reset();
                }
                if (attach_request_.has_value()) {
                    auto& attach = *attach_request_;
                    if (attach.state == AttachState::QUEUED) {
                        next_window = attach.window;
                        next_width = attach.width;
                        next_height = attach.height;
                        next_refresh_period_ns = attach.refresh_period_ns;
                        next_surface_epoch = attach.surface_epoch;
                        next_attach_generation = attach.generation;
                        next_geometry_revision =
                            attach.requested_geometry_revision;
                        attach.state = AttachState::CLAIMED;
                        attach_lease_claimed_ns_.store(
                            monotonic_now_ns(), std::memory_order_release);
                    } else if (attach.state == AttachState::READY &&
                               attach.applied_geometry_revision <
                                   attach.requested_geometry_revision) {
                        resize_window = attach.window;
                        resize_width = attach.width;
                        resize_height = attach.height;
                        resize_refresh_period_ns = attach.refresh_period_ns;
                        resize_surface_epoch = attach.surface_epoch;
                        resize_attach_generation = attach.generation;
                        resize_geometry_revision =
                            attach.requested_geometry_revision;
                    }
                }
                if (!prepared_frame_work_.has_value()) render_requested_ = false;
                // A terminal successor is the exact gesture boundary. Once it
                // is promoted to the immutable head, the next gesture may be
                // reduced into the one CPU-only successor while that head is
                // retained by pacing.
                if (fixed_scheduler_.hasRunnableControlQueue(
                        input_control_commands_.size())) {
                    while (!input_control_commands_.empty()) {
                        const bool terminal = input_control_commands_.front().action == 1 ||
                            input_control_commands_.front().action == 3;
                        controls.push_back(std::move(input_control_commands_.front()));
                        input_control_commands_.pop_front();
                        if (terminal) break;
                    }
                    if (!input_control_commands_.empty()) {
                        render_requested_ = true;
                        ++command_generation_;
                    }
                }
                if (authority_ > 0 && authority_generation_ > 0) {
                    const auto pending_release = pending_release_claims_.find(
                        current_authority_key());
                    if (pending_release != pending_release_claims_.end()) {
                        release_claim = pending_release->second;
                    }
                }
                const bool lifecycle_work_pending = should_detach || should_disarm ||
                    bind_request.has_value() || release_claim.has_value();
                if (!prepared_frame_work_.has_value() || lifecycle_work_pending) {
                    retire_intents.assign(retire_intents_.begin(), retire_intents_.end());
                    retire_intents_.clear();
                    retire_intent_depth_mirror_.store(0, std::memory_order_release);
                }
            }

            if (!postApplyPresentCut) {
                const PresentPumpResult midPresentPump =
                    drain_present_events_on_render_thread(
                        PresentDrainMode::NORMAL);
                (void)midPresentPump;
            }
            consume_feedback_failure_commands();

            bool successor_closes_current = bind_request.has_value() && authority_ > 0 &&
                (authority_ != bind_request->successor.authority ||
                 authority_generation_ != bind_request->successor.authority_generation);
            const bool fatal_lifecycle_cut =
                !context_resources_valid_.load(std::memory_order_acquire) ||
                authority_failed_.load(std::memory_order_acquire) ||
                fixed_causal_lane_fatal_;
            const bool lifecycle_closes_current = release_claim.has_value() ||
                successor_closes_current || should_detach || should_disarm;
            const bool unjoined_terminal =
                fixed_scheduler_.hasUnjoinedTerminalObligation();
            if (lifecycle_closes_current && !fatal_lifecycle_cut &&
                unjoined_terminal) {
                // Input admission is already closed. Keep the old presentation
                // authority alive until every accepted terminal reaches its
                // exact physical join, then resume this same lifecycle request.
                const auto terminal_counters = fixed_scheduler_.counters();
                const bool runnable_terminal =
                    fixed_scheduler_.hasRunnableTerminalObligation();
                std::lock_guard<std::mutex> lock(mutex_);
                deferred_lifecycle_.active = true;
                deferred_lifecycle_.observed_terminal_progress =
                    terminal_progress_sequence_;
                deferred_lifecycle_.last_rearmed_progress =
                    terminal_progress_sequence_;
                NTK_LOGI(
                    "lifecycle deferred for terminal join detach=%d disarm=%d "
                    "release=%d successorClose=%d accepted=%llu submitted=%llu "
                    "joined=%llu lost=%llu runnable=%d controls=%zu successor=%d "
                    "successorTerminal=%d prepared=%d headState=%d",
                    should_detach ? 1 : 0, should_disarm ? 1 : 0,
                    release_claim.has_value() ? 1 : 0,
                    successor_closes_current ? 1 : 0,
                    static_cast<unsigned long long>(
                        terminal_counters.terminal_accepted_count),
                    static_cast<unsigned long long>(
                        terminal_counters.terminal_submitted_count),
                    static_cast<unsigned long long>(
                        terminal_counters.terminal_joined_count),
                    static_cast<unsigned long long>(
                        terminal_counters.terminal_lost_count),
                    runnable_terminal ? 1 : 0,
                    input_control_commands_.size(),
                    fixed_scheduler_.successor().has_value() ? 1 : 0,
                    fixed_scheduler_.successorTerminal() ? 1 : 0,
                    prepared_frame_work_.has_value() ? 1 : 0,
                    static_cast<int>(head_frame_state_));
                if (bind_request.has_value()) {
                    pending_bind_request_ = std::move(bind_request);
                    bind_request.reset();
                }
                release_claim.reset();
                should_detach = false;
                should_disarm = false;
                successor_closes_current = false;
            } else if (lifecycle_closes_current && !unjoined_terminal) {
                std::lock_guard<std::mutex> lock(mutex_);
                deferred_lifecycle_.active = false;
            }
            if (fatal_lifecycle_cut && lifecycle_closes_current) {
                const std::uint64_t lost =
                    fixed_scheduler_.markUnsubmittedTerminalsLost();
                if (lost != 0) publish_terminal_progress();
            }
            if (release_claim.has_value() || successor_closes_current ||
                should_disarm) {
                // Lifecycle close wins over every normal queue. Clear the exact prepared Common
                // identity/fence/reserved frame ID before protection, retirement, predecessor
                // handoff, or successor bind can observe this authority as closed.
                abort_prepared_frame_for_lifecycle();
                {
                    std::lock_guard<std::mutex> lock(mutex_);
                    if (pending_protection_commit_.has_value()) {
                        protection_commit = std::move(pending_protection_commit_);
                        pending_protection_commit_.reset();
                    }
                }
                if (protection_commit.has_value()) {
                    enqueue_protection_committed(*protection_commit, 0, false);
                    protection_commit.reset();
                }
                for (const auto& intent : retire_intents) {
                    record_retire_result(intent, RetireResultCode::FAILED, scene_version_, 0);
                }
                retire_intents.clear();
                controls.clear();
            }

            if (should_disarm) {
                block_input_and_presentation();
                gpu_phase_.store(GpuPhase::DISARMING,
                                 std::memory_order_release);
                {
                    std::lock_guard<std::mutex> lock(mutex_);
                    disarm_requested_ = false;
                }
                should_render = false;
                controls.clear();
            }

            if (release_claim.has_value()) {
                begin_release_current_on_render(release_claim);
                should_render = false;
                controls.clear();
            }

            if (should_detach) {
                abort_prepared_frame_for_lifecycle();
                const bool context_lost =
                    !context_resources_valid_.load(std::memory_order_acquire);
                {
                    std::lock_guard<std::mutex> lock(mutex_);
                    if (pending_protection_commit_.has_value()) {
                        protection_commit = std::move(pending_protection_commit_);
                        pending_protection_commit_.reset();
                    }
                }
                if (protection_commit.has_value()) {
                    enqueue_protection_committed(*protection_commit, 0, false);
                }
                for (const auto& intent : retire_intents) {
                    if (context_lost) {
                        process_context_lost_retire(intent);
                    } else {
                        record_retire_result(
                            intent, RetireResultCode::FAILED, scene_version_, 0);
                    }
                }
                if (context_lost && authority_ > 0 && authority_generation_ > 0) {
                    // Capture the exact current token as RELEASING_UNCLAIMED before either
                    // worker may exit. Existing old-token trackers remain in the same drain.
                    begin_release_current_on_render(std::nullopt);
                }
                controls.clear();
                detach_window();
                std::lock_guard<std::mutex> lock(mutex_);
                detach_requested_ = false;
                detached_generation_ = snapshot_generation;
                detached_condition_.notify_all();
                continue;
            }
            if (next_window != nullptr) {
                const bool attach_succeeded =
                    attach_window(next_window, next_width, next_height,
                                  next_refresh_period_ns, next_surface_epoch);
                if (!attach_succeeded &&
                    authority_failed_.load(std::memory_order_acquire)) {
                    attach_authority_failed_.store(true, std::memory_order_release);
                }
                {
                    std::lock_guard<std::mutex> lock(mutex_);
                    if (attach_request_.has_value() &&
                        attach_request_->generation == next_attach_generation &&
                        attach_request_->surface_epoch == next_surface_epoch &&
                        attach_request_->state == AttachState::CLAIMED) {
                        auto& request = *attach_request_;
                        request.applied_geometry_revision =
                            next_geometry_revision;
                        request.success = attach_succeeded;
                        request.completed_ns = monotonic_now_ns();
                        request.state = attach_succeeded
                            ? (request.surface_loss_requested
                                ? AttachState::LOSS_PENDING
                                : AttachState::READY)
                            : AttachState::TERMINAL;
                        if (attach_succeeded) {
                            attach_ready_ns_.store(
                                request.completed_ns, std::memory_order_release);
                        }
                    }
                }
                if (!attach_succeeded) {
                    block_input_and_presentation();
                    attach_authority_failed_.store(true, std::memory_order_release);
                    authority_failed_.store(true, std::memory_order_release);
                    gpu_phase_.store(GpuPhase::FAILED, std::memory_order_release);
                    NTK_LOGE("EGL window attach failed");
                }
                attach_condition_.notify_all();
                if (!attach_succeeded) {
                    if (bind_request.has_value()) {
                        engine_failed_.store(true, std::memory_order_release);
                        complete_bind_ticket(bind_request->ticket, false);
                    }
                    continue;
                }
                should_render = true;
            }
            if (resize_window != nullptr) {
                detach_window();
                const bool resized = attach_window(
                    resize_window, resize_width, resize_height,
                    resize_refresh_period_ns, resize_surface_epoch);
                {
                    std::lock_guard<std::mutex> lock(mutex_);
                    if (attach_request_.has_value() &&
                        attach_request_->generation ==
                            resize_attach_generation &&
                        attach_request_->surface_epoch ==
                            resize_surface_epoch &&
                        attach_request_->state == AttachState::READY) {
                        attach_request_->success = resized;
                        attach_request_->completed_ns = monotonic_now_ns();
                        if (resized) {
                            attach_request_->applied_geometry_revision =
                                resize_geometry_revision;
                        } else {
                            attach_request_->state = AttachState::TERMINAL;
                        }
                    }
                }
                if (!resized) {
                    block_input_and_presentation();
                    attach_authority_failed_.store(true, std::memory_order_release);
                    authority_failed_.store(true, std::memory_order_release);
                    gpu_phase_.store(GpuPhase::FAILED, std::memory_order_release);
                }
                attach_condition_.notify_all();
                if (!resized) continue;
            }
            // One real pacing edge permits exactly one commit attempt. A
            // retained immutable head never blocks control/MOVE reduction into
            // the CPU-only successor below.
            if (prepared_frame_work_.has_value() &&
                head_frame_state_ == HeadFrameState::SWAPPY_RESERVED &&
                fixed_opportunity_gate_.hasPending()) {
                const PreparedCommitResult commit =
                    service_ready_prepared_frame();
                if (commit == PreparedCommitResult::FATAL ||
                    commit == PreparedCommitResult::
                        SUBMITTED_FATAL_AFTER_EGL_TRUE) {
                    continue;
                }
            }
            width_ = next_width > 0 ? next_width : width_;
            height_ = next_height > 0 ? next_height : height_;
            // SurfaceChanged is authoritative for the physical viewport. A bind can race the
            // final inset/layout pass and carry a transient taller View height; retaining that
            // value makes the native terminal scroll clamp smaller than the visible viewport's
            // real max even though Kotlin geometry is already final.
            if (width_ > 0) viewport_width_ = width_;
            if (height_ > 0) viewport_height_ = height_;
            bool terminal_control_applied = false;
            for (const auto& control : controls) {
                const bool terminal = control.action == 1 ||
                    control.action == 3;
                const bool changed = apply_control(control);
                should_render = changed || should_render;
                if (authority_failed_.load(std::memory_order_acquire)) break;
                if (terminal) {
                    terminal_control_applied = true;
                    break;
                }
            }
            GpuPhase gpu_phase = gpu_phase_.load(std::memory_order_acquire);
            // UP/CANCEL already captured the last mailbox value while holding
            // the mailbox mutex. Never read MOVE after the terminal boundary.
            const bool first_move_changed = !terminal_control_applied &&
                !fixed_scheduler_.successorTerminal() &&
                latch_latest_move(std::numeric_limits<std::uint64_t>::max());
            should_render = first_move_changed || should_render;
            gpu_phase = gpu_phase_.load(std::memory_order_acquire);
            if (gpu_phase == GpuPhase::PRE_STAGE_GPU) {
                publish_ready_tiles(should_render);
            } else if (!gpu_ready_tiles_.empty()) {
                NTK_LOGE("fatal ready tile in sealed phase=%d", static_cast<int>(gpu_phase));
                authority_failed_.store(true, std::memory_order_release);
                gpu_phase_.store(GpuPhase::FAILED, std::memory_order_release);
            }
            if (bind_request.has_value()) {
                const BindSceneDisposition disposition = bind_scene(*bind_request);
                if (disposition == BindSceneDisposition::DEFERRED) {
                    bool collision = false;
                    {
                        std::lock_guard<std::mutex> lock(mutex_);
                        collision = pending_bind_request_.has_value();
                        if (!collision) {
                            pending_bind_request_ = std::move(bind_request);
                        }
                    }
                    if (collision) {
                        engine_failed_.store(true, std::memory_order_release);
                        complete_bind_ticket(bind_request->ticket, false);
                    }
                } else if (disposition == BindSceneDisposition::FAILED) {
                    engine_failed_.store(true, std::memory_order_release);
                    complete_bind_ticket(bind_request->ticket, false);
                } else {
                    should_render = true;
                }
            }
            // Latest-only policy is installed after all ready uploads and before any retirement.
            {
                std::lock_guard<std::mutex> lock(mutex_);
                if (pending_protection_commit_.has_value()) {
                    protection_commit = std::move(pending_protection_commit_);
                    pending_protection_commit_.reset();
                }
            }
            if (protection_commit.has_value()) {
                apply_protection_commit(*protection_commit);
            }
            gpu_phase = gpu_phase_.load(std::memory_order_acquire);
            if (gpu_phase == GpuPhase::PRE_STAGE_GPU) {
                for (const auto& intent : retire_intents) {
                    should_render = process_retire(intent) || should_render;
                }
            } else {
                for (const auto& intent : retire_intents) {
                    record_retire_result(intent, RetireResultCode::FAILED,
                                         scene_version_, 0);
                }
            }
            // STAGE is a full-scene freeze. Accepted PRE_STAGE work and cross-context fences
            // drain first; only then can the render owner warm, seal, retire the worker, and
            // prepare the one authoritative window backbuffer.
            gpu_phase = gpu_phase_.load(std::memory_order_acquire);
            if (stage_requested_ && gpu_phase == GpuPhase::PRE_STAGE_GPU &&
                stage_seal_prerequisites_ready()) {
                if (!seal_scene_for_input()) {
                    authority_failed_.store(true, std::memory_order_release);
                    gpu_resource_worker_state_.store(
                        GpuResourceWorkerState::FAILED, std::memory_order_release);
                    gpu_phase_.store(GpuPhase::FAILED, std::memory_order_release);
                }
                gpu_phase = gpu_phase_.load(std::memory_order_acquire);
                should_render = gpu_phase == GpuPhase::SEALING || should_render;
            } else if (stage_requested_ && gpu_phase == GpuPhase::PRE_STAGE_GPU &&
                       stage_seal_permanently_blocked()) {
                NTK_LOGE("fatal full-scene stage cannot reach immutable seal tiles=%zu/%zu",
                         scene_.size(), expected_tile_count_);
                authority_failed_.store(true, std::memory_order_release);
                gpu_resource_worker_state_.store(
                    GpuResourceWorkerState::FAILED, std::memory_order_release);
                gpu_phase_.store(GpuPhase::FAILED, std::memory_order_release);
                gpu_phase = GpuPhase::FAILED;
            }
            if (gpu_phase == GpuPhase::SEALING && stage_requested_ &&
                scene_sealed_.load(std::memory_order_acquire) &&
                !fixed_scheduler_.successor().has_value() &&
                !prepared_frame_work_.has_value()) {
                if (!queue_stage_frame()) {
                    head_frame_state_ = HeadFrameState::FAILED;
                    authority_failed_.store(true, std::memory_order_release);
                    gpu_phase_.store(GpuPhase::FAILED,
                                     std::memory_order_release);
                }
            }
            // A hidden staged Surface can be created while its Activity/ViewRoot is still
            // transitioning. Swapping an authority-less blank buffer enters Swappy's
            // post-swap Choreographer wait and can strand the render owner before the real bind.
            // The first authoritative bind already requests a frame, so never pace blank setup.
            const bool stage_proof_presentation =
                gpu_phase == GpuPhase::SEALING && stage_requested_ &&
                stage_authority_ == authority_ && stage_nonce_ > 0 &&
                scene_sealed_.load(std::memory_order_acquire) &&
                stage_backbuffer_ready_ns_.load(std::memory_order_acquire) > 0;
            const bool authoritative_presentation_phase =
                stage_proof_presentation ||
                gpu_phase == GpuPhase::INPUT_ARMED ||
                gpu_phase == GpuPhase::GESTURE_ACTIVE;
            const bool can_submit_authoritative = authoritative_presentation_phase &&
                present_backend_attached_ && authority_ > 0 &&
                content_height_ > 0 &&
                !presentation_blocked_.load(std::memory_order_acquire);
            if (can_submit_authoritative &&
                fixed_scheduler_.successor().has_value() &&
                pending_frame_cause_ready_for_preparation() &&
                present_backend_.hasPreparationCapacity() &&
                present_backend_.pool().hasFreeRenderTarget()) {
                if (!prepare_pending_frame()) {
                    head_frame_state_ = HeadFrameState::FAILED;
                    engine_failed_.store(true, std::memory_order_release);
                    authority_failed_.store(true, std::memory_order_release);
                    gpu_phase_.store(GpuPhase::FAILED, std::memory_order_release);
                } else {
                    const PreparedCommitResult commit =
                        service_ready_prepared_frame();
                    if (commit == PreparedCommitResult::FATAL ||
                        commit == PreparedCommitResult::
                            SUBMITTED_FATAL_AFTER_EGL_TRUE) {
                        continue;
                    }
                }
            }
            // No producer work means no new pixels; compositor latch and
            // Swappy retirement arrive through one-shot callbacks above.
        }
        {
            std::unique_lock<std::mutex> lock(mutex_);
            upload_exit_requested_ = true;
            upload_condition_.notify_all();
            upload_exit_condition_.wait(lock, [&] { return upload_exited_; });
        }
        destroy_egl();
        ANativeWindow* abandoned_attach_window = nullptr;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (attach_request_.has_value()) {
                attach_request_->state = AttachState::TERMINAL;
                attach_request_->success = false;
                attach_request_->completed_ns = monotonic_now_ns();
                abandoned_attach_window = attach_request_->window;
                attach_request_->window = nullptr;
            }
            render_exited_ = true;
            detached_generation_ = command_generation_;
            detached_condition_.notify_all();
        }
        if (abandoned_attach_window != nullptr) {
            ANativeWindow_release(abandoned_attach_window);
            g_renderer_owned_surface_lease_count.fetch_sub(
                1, std::memory_order_acq_rel);
        }
        attach_condition_.notify_all();
    }

    void enqueue_release_ack_if_ready(const std::shared_ptr<AuthorityReleaseTracker>& tracker) {
        std::shared_ptr<AuthorityReleaseAckData> ack;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (!tracker || tracker->ack_enqueued || !tracker->physical_complete) return;
            if (!context_resources_valid_.load(std::memory_order_acquire)) {
                // Context-loss proofs are frozen only after detach retires the entire backend.
                // No ACK may escape from the still-live feedback lane.
                tracker->ack_enqueued = true;
                return;
            }
            if (
                tracker->lifecycle != AuthorityLifecycle::RELEASING_CLAIMED ||
                !tracker->claim.has_value() || tracker->in_flight_upload ||
                tracker->in_flight_resource_delete ||
                tracker->outstanding_publications != 0 || !tracker->queued_uploads.empty() ||
                !tracker->ready_tiles.empty() || !tracker->resource_deletes.empty() ||
                !tracker->scene.empty() || !tracker->preallocated_textures.empty() ||
                !tracker->prepared_bank.empty() ||
                tracker->render_fence != nullptr) return;
            tracker->ack_enqueued = true;
            ack = std::make_shared<AuthorityReleaseAckData>();
            ack->claim = *tracker->claim;
            // A live feedback-lane ACK is always reusable-context explicit deletion.
            // Context-loss ACKs exist only in the frozen proof store after full retirement.
            ack->disposition = PhysicalReleaseDisposition::EXPLICIT_DELETE;
            ack->admission_close_serial = tracker->admission_close_serial;
            ack->release_claim_serial = tracker->release_claim_serial;
            ack->resource_barrier_serial = tracker->resource_barrier_serial;
            ack->resource_completion_watermark = tracker->resource_completion_watermark;
            ack->captured_resource_count = static_cast<int>(tracker->captured_resources.size());
            ack->captured_rgba_bytes = tracker->captured_rgba_bytes;
            ack->captured_resource_digest = tracker->captured_resource_digest;
            ack->released_resource_count = static_cast<int>(tracker->released_resources.size());
            ack->released_rgba_bytes = tracker->released_rgba_bytes;
            ack->released_resource_digest = tracker->released_resource_digest;
            ack->deleted_texture_count = tracker->deleted_texture_count;
            ack->deleted_fence_count = tracker->deleted_fence_count;
            ack->released_bitmap_global_ref_count =
                tracker->released_bitmap_global_ref_count;
            ack->drained_upload_count = tracker->drained_upload_count;
            ack->drained_retire_count = tracker->drained_retire_count;
            ack->completed_nanos = monotonic_now_ns();
            ack->context_reusable =
                ack->disposition == PhysicalReleaseDisposition::EXPLICIT_DELETE;
            ack->success = ack->resource_completion_watermark > ack->resource_barrier_serial &&
                ack->release_claim_serial > ack->admission_close_serial &&
                ack->captured_resource_count == ack->released_resource_count &&
                ack->captured_rgba_bytes == ack->released_rgba_bytes &&
                ack->captured_resource_digest == ack->released_resource_digest;
            tracker->feedback_frame_target = frame_feedback_committed_sequence_.load(
                std::memory_order_acquire);
        }
        FeedbackRecord record;
        record.kind = FeedbackKind::AUTHORITY_RELEASED;
        record.engine_generation = tracker->token.key.engine_generation;
        record.authority_generation = tracker->token.key.authority_generation;
        record.key.authority = tracker->token.key.authority;
        record.frame_target_sequence = tracker->feedback_frame_target;
        record.release_ack = std::move(ack);
        // FIFO position is the reliable callback barrier. The feedback lane additionally waits
        // for every frame committed before this record before invoking Java.
        enqueue_feedback(record);
    }

    bool process_release_tracker_once(JNIEnv* env) {
        std::shared_ptr<AuthorityReleaseTracker> tracker;
        enum class Work {
            NONE, QUEUED_UPLOAD, READY, RETIRED, SCENE, PREALLOC, PREPARED,
            SUPPRESSED_FEEDBACK
        } work = Work::NONE;
        UploadCommand queued;
        GpuReadyTile ready;
        PendingResourceDelete retired;
        TileKey scene_key;
        SceneTile scene_tile;
        TileKey preallocated_key;
        PreallocatedTexture preallocated;
        TileKey prepared_key;
        PreparedBankTile prepared;
        std::shared_ptr<void> suppressed_latches;
        std::shared_ptr<void> suppressed_resolved;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            for (auto& entry : release_trackers_) {
                auto candidate = entry.second;
                if (candidate->lifecycle == AuthorityLifecycle::RELEASED ||
                    candidate->lifecycle == AuthorityLifecycle::FAILED ||
                    candidate->ack_enqueued ||
                    (candidate->physical_complete &&
                     candidate->lifecycle == AuthorityLifecycle::RELEASING_UNCLAIMED)) continue;
                tracker = std::move(candidate);
                if (!tracker->queued_uploads.empty()) {
                    queued = std::move(tracker->queued_uploads.front());
                    tracker->queued_uploads.pop_front();
                    work = Work::QUEUED_UPLOAD;
                } else if (!tracker->ready_tiles.empty()) {
                    ready = tracker->ready_tiles.front();
                    tracker->ready_tiles.pop_front();
                    work = Work::READY;
                } else if (!tracker->resource_deletes.empty()) {
                    retired = tracker->resource_deletes.front();
                    tracker->resource_deletes.pop_front();
                    work = Work::RETIRED;
                } else if (!tracker->scene.empty()) {
                    const auto first = tracker->scene.begin();
                    scene_key = first->first;
                    scene_tile = first->second;
                    work = Work::SCENE;
                } else if (!tracker->preallocated_textures.empty()) {
                    const auto first = tracker->preallocated_textures.begin();
                    preallocated_key = first->first;
                    preallocated = first->second;
                    tracker->preallocated_textures.erase(first);
                    work = Work::PREALLOC;
                } else if (!tracker->prepared_bank.empty()) {
                    const auto first = tracker->prepared_bank.begin();
                    prepared_key = first->first;
                    prepared = first->second;
                    tracker->prepared_bank.erase(first);
                    work = Work::PREPARED;
                } else if (tracker->suppressed_latch_records ||
                           tracker->suppressed_resolved_records) {
                    suppressed_latches = std::move(tracker->suppressed_latch_records);
                    suppressed_resolved = std::move(tracker->suppressed_resolved_records);
                    work = Work::SUPPRESSED_FEEDBACK;
                }
                break;
            }
        }
        if (!tracker) return false;
        const bool context_lost = !context_resources_valid_.load(std::memory_order_acquire);
        const auto wait_fence_once = [&](GLsync fence) {
            if (context_lost || fence == nullptr) return static_cast<GLenum>(GL_ALREADY_SIGNALED);
            std::lock_guard<std::mutex> submit_lock(upload_submit_mutex_);
            return glClientWaitSync(fence, 0, GL_TIMEOUT_IGNORED);
        };
        const auto delete_gl_objects = [&](GLsync fence, GLuint texture) {
            if (context_lost) return;
            std::lock_guard<std::mutex> submit_lock(upload_submit_mutex_);
            if (fence != nullptr) glDeleteSync(fence);
            if (texture != 0) glDeleteTextures(1, &texture);
        };
        const auto fail_tracker = [&](const char* reason) {
            NTK_LOGE("old authority release failed authority=%lld generation=%lld reason=%s",
                     static_cast<long long>(tracker->token.key.authority),
                     static_cast<long long>(tracker->token.key.authority_generation), reason);
            std::lock_guard<std::mutex> lock(mutex_);
            tracker->lifecycle = AuthorityLifecycle::FAILED;
        };
        if (work == Work::QUEUED_UPLOAD) {
            const AuthorityKey key = tracker->token.key;
            const std::int64_t resource_scope = queued.pre_geometry
                ? queued.preparation_generation : queued.surface_epoch;
            if (env != nullptr && queued.bitmap != nullptr) {
                env->DeleteGlobalRef(queued.bitmap);
                ++tracker->released_bitmap_global_ref_count;
                record_released_resource(*tracker, release_identity(
                    queued.pre_geometry ? "detached-bitmap-global-ref" :
                        "bitmap-global-ref",
                    key, resource_scope,
                    queued.admission_id, queued.key.page, queued.key.slot,
                    queued.resource_revision, queued.install_lease, 0,
                    queued.rgba_bytes));
            } else if (queued.bitmap != nullptr) {
                fail_tracker("bitmap-global-ref-env");
                return true;
            }
            record_released_resource(*tracker, release_identity(
                queued.pre_geometry ? "queued-detached-upload" :
                    "queued-upload",
                key, resource_scope, queued.admission_id,
                queued.key.page, queued.key.slot, queued.resource_revision,
                queued.install_lease, 0, queued.rgba_bytes));
            GpuReadyTile failed;
            failed.key = queued.key;
            failed.authority_generation = queued.authority_generation;
            failed.preparation_generation = queued.preparation_generation;
            failed.surface_epoch = queued.surface_epoch;
            failed.admission_id = queued.admission_id;
            failed.resource_revision = queued.resource_revision;
            failed.install_lease = queued.install_lease;
            failed.rgba_bytes = queued.rgba_bytes;
            failed.tile_proof_digest = queued.tile_proof_digest;
            failed.pre_geometry = queued.pre_geometry;
            failed.prepared_protocol = queued.prepared_protocol;
            ++tracker->drained_upload_count;
            complete_publication(failed, 0, false);
        } else if (work == Work::READY) {
            const std::int64_t resource_scope = ready.pre_geometry
                ? ready.preparation_generation : ready.surface_epoch;
            GLenum status = GL_ALREADY_SIGNALED;
            if (!context_lost && ready.upload_fence != nullptr) {
                status = wait_fence_once(ready.upload_fence);
            }
            if (status != GL_ALREADY_SIGNALED &&
                status != GL_CONDITION_SATISFIED) {
                if (!ready.release_transition_output) {
                    complete_publication(ready, 0, false);
                }
                fail_tracker("upload-fence-wait-failed");
                return true;
            }
            if (!context_lost && ready.upload_fence != nullptr) {
                ++tracker->deleted_fence_count;
            }
            if (!context_lost && ready.texture != 0) {
                ++tracker->deleted_texture_count;
            }
            delete_gl_objects(ready.upload_fence, ready.texture);
            if (ready.release_transition_output && ready.consumed_preallocation &&
                ready.texture != 0) {
                record_released_resource(*tracker, release_identity(
                    "in-flight-upload-texture", tracker->token.key,
                    resource_scope, ready.admission_id,
                    ready.key.page, ready.key.slot, ready.resource_revision,
                    ready.install_lease, 0, ready.rgba_bytes));
            }
            if (!ready.release_transition_output) {
                const AuthorityKey key = tracker->token.key;
                if (ready.upload_fence != nullptr) record_released_resource(
                    *tracker, release_identity(
                        ready.pre_geometry ? "detached-upload-fence" :
                            "upload-fence",
                        key, resource_scope, ready.admission_id,
                        ready.key.page, ready.key.slot, ready.resource_revision,
                        ready.install_lease, 0, ready.rgba_bytes));
                if (ready.texture != 0) record_released_resource(
                    *tracker, release_identity(
                        ready.pre_geometry ? "gpu-ready-detached-texture" :
                            "gpu-ready-texture",
                        key, resource_scope,
                        ready.admission_id, ready.key.page, ready.key.slot,
                        ready.resource_revision, ready.install_lease, 0,
                        ready.rgba_bytes));
                ++tracker->drained_upload_count;
                complete_publication(ready, 0, false);
            }
        } else if (work == Work::RETIRED) {
            const std::int64_t resource_scope = retired.detached_preparation
                ? retired.preparation_generation : retired.surface_epoch;
            GLenum status = GL_ALREADY_SIGNALED;
            if (!context_lost && retired.fence != nullptr) {
                status = wait_fence_once(retired.fence);
            }
            if (status != GL_ALREADY_SIGNALED &&
                status != GL_CONDITION_SATISFIED) {
                fail_tracker("retire-fence-wait-failed");
                return true;
            }
            if (!context_lost && retired.fence != nullptr) {
                ++tracker->deleted_fence_count;
            }
            if (!context_lost && retired.texture != 0) {
                ++tracker->deleted_texture_count;
            }
            delete_gl_objects(retired.fence, retired.texture);
            const AuthorityKey key = tracker->token.key;
            if (retired.fence != nullptr) record_released_resource(
                *tracker, release_identity(
                    retired.notify_freed ? "retire-fence" :
                        (retired.detached_preparation
                            ? "detached-resource-delete-fence"
                            : "resource-delete-fence"),
                    key, resource_scope, retired.admission_id,
                    retired.key.page, retired.key.slot, retired.resource_revision,
                    retired.install_lease, retired.retire_lease,
                    retired.rgba_bytes));
            if (retired.texture != 0) record_released_resource(
                *tracker, release_identity(
                    retired.detached_preparation
                        ? "detached-resource-delete-texture"
                        : "resource-delete-texture",
                    key, resource_scope,
                    retired.admission_id, retired.key.page, retired.key.slot,
                    retired.resource_revision, retired.install_lease,
                    retired.retire_lease, retired.rgba_bytes));
            if (retired.notify_freed) enqueue_tile_freed(retired, true);
            ++tracker->drained_retire_count;
        } else if (work == Work::SCENE) {
            GLenum status = GL_ALREADY_SIGNALED;
            if (!context_lost && tracker->render_fence != nullptr) {
                status = wait_fence_once(tracker->render_fence);
            }
            if (status != GL_ALREADY_SIGNALED &&
                status != GL_CONDITION_SATISFIED) {
                fail_tracker("render-fence-wait-failed");
                return true;
            }
            if (tracker->render_fence != nullptr) {
                delete_gl_objects(tracker->render_fence, 0);
                record_released_resource(*tracker, release_identity(
                    "render-fence", tracker->token.key,
                    tracker->render_fence_surface_epoch, 0, -1, -1,
                    0, 0, 0, 0));
                tracker->render_fence = nullptr;
                if (!context_lost) ++tracker->deleted_fence_count;
            }
            if (scene_tile.texture != 0) {
                delete_gl_objects(nullptr, scene_tile.texture);
                record_released_resource(*tracker, release_identity(
                    "scene-texture", tracker->token.key, scene_tile.surface_epoch,
                    scene_tile.admission_id, scene_key.page, scene_key.slot,
                    scene_tile.resource_revision, scene_tile.install_lease,
                    0, scene_tile.rgba_bytes));
                if (!context_lost) ++tracker->deleted_texture_count;
            }
            {
                std::lock_guard<std::mutex> lock(mutex_);
                tracker->scene.erase(scene_key);
            }
        } else if (work == Work::PREALLOC) {
            if (preallocated.texture != 0) {
                delete_gl_objects(nullptr, preallocated.texture);
                record_released_resource(*tracker, release_identity(
                    "preallocated-texture", tracker->token.key, 0, 0,
                    preallocated_key.page, preallocated_key.slot, 0, 0, 0,
                    static_cast<std::int64_t>(preallocated.width) *
                        preallocated.height * 4LL));
                if (!context_lost) ++tracker->deleted_texture_count;
            }
        } else if (work == Work::PREPARED) {
            if (prepared.texture != 0) {
                delete_gl_objects(nullptr, prepared.texture);
                record_released_resource(*tracker, release_identity(
                    "prepared-bank-texture", tracker->token.key,
                    prepared.preparation_generation, prepared.admission_id,
                    prepared_key.page, prepared_key.slot,
                    prepared.resource_revision, prepared.install_lease,
                    0, prepared.rgba_bytes));
                if (!context_lost) ++tracker->deleted_texture_count;
            }
        } else if (work == Work::SUPPRESSED_FEEDBACK) {
            // Local owners destruct after this function returns on the background upload lane.
            // Terminal suppression intentionally emits no late old-token callback.
        }
        bool inventory_candidate = false;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            const bool empty = tracker->queued_uploads.empty() &&
                tracker->ready_tiles.empty() && tracker->resource_deletes.empty() &&
                tracker->scene.empty() && tracker->preallocated_textures.empty() &&
                tracker->prepared_bank.empty() &&
                tracker->render_fence == nullptr && !tracker->in_flight_upload &&
                !tracker->in_flight_resource_delete &&
                tracker->outstanding_publications == 0 &&
                !tracker->suppressed_latch_records &&
                !tracker->suppressed_resolved_records;
            if (empty && tracker->lifecycle != AuthorityLifecycle::FAILED) {
                tracker->slot_specs.clear();
                tracker->ordinal_keys.clear();
                tracker->key_ordinals.clear();
                tracker->resident_intervals.clear();
                tracker->applied_protection = AppliedProtection{};
                inventory_candidate = true;
            }
        }
        if (inventory_candidate) {
            // Hashing/sorting can be O(N); it deliberately runs on the background upload lane
            // with no native lock and never extends successor bind or render latency.
            const std::string captured_digest = release_inventory_digest(
                tracker->captured_resources);
            const std::string released_digest = release_inventory_digest(
                tracker->released_resources);
            const std::int64_t captured_bytes = inventory_rgba_bytes(
                tracker->captured_resources);
            const std::int64_t released_bytes = inventory_rgba_bytes(
                tracker->released_resources);
            std::lock_guard<std::mutex> lock(mutex_);
            const bool exact = tracker->captured_resources.size() ==
                    tracker->released_resources.size() &&
                captured_bytes == released_bytes && captured_digest == released_digest;
            if (exact && tracker->lifecycle != AuthorityLifecycle::FAILED) {
                tracker->captured_resource_digest = captured_digest;
                tracker->released_resource_digest = released_digest;
                tracker->captured_rgba_bytes = captured_bytes;
                tracker->released_rgba_bytes = released_bytes;
                tracker->physical_complete = true;
                tracker->physical_complete_ns = monotonic_now_ns();
                tracker->resource_completion_watermark =
                    next_release_protocol_serial_locked();
                if (tracker->resource_completion_watermark <=
                    tracker->resource_barrier_serial) {
                    tracker->lifecycle = AuthorityLifecycle::FAILED;
                }
            } else {
                tracker->lifecycle = AuthorityLifecycle::FAILED;
            }
        }
        enqueue_release_ack_if_ready(tracker);
        return true;
    }

    void upload_loop(ResourceWorkerLaunch launch) {
        // Texture upload is runway work. It must yield to Android's main input thread and the
        // presentation owner when the four-core qualification device is saturated.
        setpriority(PRIO_PROCESS, 0, 10);
        bool startup_valid = false;
        {
            std::unique_lock<std::mutex> lock(mutex_);
            upload_condition_.wait(lock, [&] {
                return stopped_ || upload_exit_requested_ || egl_ready_;
            });
            const bool initial_unowned = launch.owner.authority_generation == 0 &&
                launch.owner.authority == 0 &&
                resource_worker_owner_authority_generation_.load(
                    std::memory_order_acquire) == 0 &&
                resource_worker_owner_authority_.load(std::memory_order_acquire) == 0;
            const bool exact_owned = launch.owner.authority_generation > 0 &&
                launch.owner.authority > 0 &&
                resource_worker_owns(
                    launch.owner.authority_generation, launch.owner.authority);
            startup_valid = !stopped_ && !upload_exit_requested_ && egl_ready_ &&
                upload_start_state_ == ResourceWorkerStartState::STARTING &&
                upload_start_generation_ == launch.generation &&
                upload_start_owner_ == launch.owner && (initial_unowned || exact_owned);
            if (!startup_valid) {
                if (upload_start_generation_ == launch.generation &&
                    upload_start_state_ == ResourceWorkerStartState::STARTING) {
                    upload_start_state_ = ResourceWorkerStartState::FAILED;
                }
                upload_exited_ = true;
            }
        }
        if (!startup_valid) {
            upload_start_condition_.notify_all();
            upload_exit_condition_.notify_all();
            eglReleaseThread();
            return;
        }

        const bool made_current = startup_valid &&
            eglMakeCurrent(display_, upload_pbuffer_, upload_pbuffer_, upload_context_) == EGL_TRUE;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            startup_valid = made_current &&
                upload_start_state_ == ResourceWorkerStartState::STARTING &&
                upload_start_generation_ == launch.generation &&
                upload_start_owner_ == launch.owner;
            upload_start_state_ = startup_valid
                ? ResourceWorkerStartState::READY
                : ResourceWorkerStartState::FAILED;
            if (!startup_valid) upload_exited_ = true;
        }
        upload_start_condition_.notify_all();
        if (!startup_valid) {
            NTK_LOGE("Upload EGL make-current failed");
            engine_failed_.store(true, std::memory_order_release);
            authority_failed_.store(true, std::memory_order_release);
            gpu_phase_.store(GpuPhase::FAILED, std::memory_order_release);
            gpu_resource_worker_state_.store(
                GpuResourceWorkerState::FAILED, std::memory_order_release);
            upload_exit_condition_.notify_all();
            eglReleaseThread();
            return;
        }
        EnvScope scope(java_vm_);
        JNIEnv* env = scope.get();
        while (true) {
            PreallocateCommand preallocation;
            UploadCommand command;
            PendingResourceDelete resource_delete;
            bool has_preallocation = false;
            bool has_upload = false;
            bool has_resource_delete = false;
            bool has_release_work = false;
            bool seal_now = false;
            {
                std::unique_lock<std::mutex> lock(mutex_);
                upload_condition_.wait(lock, [&] {
                    return upload_exit_requested_ || upload_seal_requested_ ||
                        !resource_deletes_.empty() || !preallocate_commands_.empty() ||
                        !upload_commands_.empty() ||
                        std::any_of(release_trackers_.begin(), release_trackers_.end(),
                            [](const auto& entry) {
                                return entry.second->lifecycle != AuthorityLifecycle::RELEASED &&
                                    entry.second->lifecycle != AuthorityLifecycle::FAILED &&
                                    !entry.second->ack_enqueued &&
                                    (!entry.second->physical_complete ||
                                     entry.second->lifecycle ==
                                         AuthorityLifecycle::RELEASING_CLAIMED);
                            });
                });
                const bool release_cleanup_pending = std::any_of(
                    release_trackers_.begin(), release_trackers_.end(),
                    [](const auto& entry) {
                        return entry.second->lifecycle != AuthorityLifecycle::RELEASED &&
                            entry.second->lifecycle != AuthorityLifecycle::FAILED &&
                            !entry.second->ack_enqueued &&
                            !entry.second->physical_complete;
                    });
                if (upload_exit_requested_ && resource_deletes_.empty() &&
                    preallocate_commands_.empty() && upload_commands_.empty() &&
                    !release_cleanup_pending &&
                    !in_flight_upload_.has_value() &&
                    !in_flight_resource_delete_.has_value()) break;
                // Correctness-critical predecessor cleanup owns the first slot. Once every
                // predecessor is physically complete, the new authority performs storage
                // allocation before any bitmap upload. Exactly one resource command is active.
                if (release_cleanup_pending) {
                    has_release_work = true;
                } else if (!preallocate_commands_.empty()) {
                    preallocation = preallocate_commands_.front();
                    preallocate_commands_.pop_front();
                    has_preallocation = true;
                    upload_active_ = true;
                } else if (!upload_commands_.empty()) {
                    command = upload_commands_.front();
                    upload_commands_.pop_front();
                    in_flight_upload_ = command;
                    const AuthorityKey token{
                        engine_generation_, command.authority_generation,
                        command.key.authority};
                    const std::int64_t resource_scope = command.pre_geometry
                        ? command.preparation_generation : command.surface_epoch;
                    registry_remove_locked(release_identity(
                        command.pre_geometry ? "queued-detached-upload" :
                            "queued-upload",
                        token, resource_scope,
                        command.admission_id, command.key.page, command.key.slot,
                        command.resource_revision, command.install_lease, 0,
                        command.rgba_bytes));
                    registry_add_locked(release_identity(
                        command.pre_geometry ? "in-flight-detached-upload" :
                            "in-flight-upload",
                        token, resource_scope,
                        command.admission_id, command.key.page, command.key.slot,
                        command.resource_revision, command.install_lease, 0,
                        command.rgba_bytes));
                    has_upload = true;
                    upload_active_ = true;
                } else if (!resource_deletes_.empty()) {
                    resource_delete = resource_deletes_.front();
                    resource_deletes_.pop_front();
                    resource_delete_depth_mirror_.store(
                        static_cast<int>(resource_deletes_.size()),
                        std::memory_order_release);
                    in_flight_resource_delete_ = resource_delete;
                    has_resource_delete = true;
                    upload_active_ = true;
                } else if (upload_seal_requested_) {
                    seal_now = true;
                } else {
                    has_release_work = true;
                }
            }
            if (has_release_work) {
                process_release_tracker_once(env);
                {
                    std::lock_guard<std::mutex> lock(mutex_);
                    ++command_generation_;
                }
                render_condition_.notify_one();
                continue;
            }
            if (has_resource_delete) {
                const bool context_lost =
                    !context_resources_valid_.load(std::memory_order_acquire);
                GLenum status = GL_ALREADY_SIGNALED;
                if (!context_lost) {
                    std::lock_guard<std::mutex> submit_lock(upload_submit_mutex_);
                    status = resource_delete.fence == nullptr
                        ? GL_ALREADY_SIGNALED
                        : glClientWaitSync(
                            resource_delete.fence, 0, GL_TIMEOUT_IGNORED);
                }
                const bool success = status == GL_ALREADY_SIGNALED ||
                    status == GL_CONDITION_SATISFIED;
                if (!success) {
                    NTK_LOGE("fatal resource fence wait failed page=%d slot=%d",
                             resource_delete.key.page, resource_delete.key.slot);
                }
                if (!context_lost) {
                    std::lock_guard<std::mutex> submit_lock(upload_submit_mutex_);
                    if (resource_delete.fence != nullptr) glDeleteSync(resource_delete.fence);
                    if (resource_delete.texture != 0) {
                        glDeleteTextures(1, &resource_delete.texture);
                    }
                }
                if (!context_lost) {
                    last_gpu_resource_completion_ns_.store(
                        monotonic_now_ns(), std::memory_order_release);
                }
                if (resource_delete.notify_freed) {
                    enqueue_tile_freed(resource_delete, success);
                }
                bool routed_to_release = false;
                {
                    std::lock_guard<std::mutex> lock(mutex_);
                    routed_to_release = complete_in_flight_resource_delete_locked(
                        resource_delete, success);
                    ++command_generation_;
                }
                preparation_drain_condition_.notify_all();
                if (!success && !routed_to_release) {
                    authority_failed_.store(true, std::memory_order_release);
                    gpu_phase_.store(GpuPhase::FAILED, std::memory_order_release);
                }
                render_condition_.notify_one();
                continue;
            }
            if (seal_now) {
                std::lock_guard<std::mutex> submit_lock(upload_submit_mutex_);
                bool physical_retirement = true;
                if (eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT) !=
                    EGL_TRUE) {
                    NTK_LOGE("fatal upload context release failed error=0x%x", eglGetError());
                    physical_retirement = false;
                }
                if (upload_pbuffer_ != EGL_NO_SURFACE) {
                    if (eglDestroySurface(display_, upload_pbuffer_) == EGL_TRUE) {
                        upload_pbuffer_ = EGL_NO_SURFACE;
                    } else {
                        NTK_LOGE("fatal upload pbuffer destroy failed error=0x%x", eglGetError());
                        physical_retirement = false;
                    }
                }
                if (upload_context_ != EGL_NO_CONTEXT) {
                    if (eglDestroyContext(display_, upload_context_) == EGL_TRUE) {
                        upload_context_ = EGL_NO_CONTEXT;
                    } else {
                        NTK_LOGE("fatal upload context destroy failed error=0x%x", eglGetError());
                        physical_retirement = false;
                    }
                }
                if (eglReleaseThread() != EGL_TRUE) {
                    NTK_LOGE("fatal upload EGL thread release failed error=0x%x", eglGetError());
                    physical_retirement = false;
                }
                const bool context_destroyed = upload_context_ == EGL_NO_CONTEXT;
                if (context_destroyed) {
                    record_resource_worker_context_destroyed();
                } else {
                    upload_context_alive_.store(true, std::memory_order_release);
                }
                const bool retired = physical_retirement && context_destroyed &&
                    upload_pbuffer_ == EGL_NO_SURFACE;
                gpu_resource_worker_state_.store(
                    retired ? GpuResourceWorkerState::RETIRED
                            : GpuResourceWorkerState::FAILED,
                    std::memory_order_release);
                if (!retired) {
                    authority_failed_.store(true, std::memory_order_release);
                    gpu_phase_.store(GpuPhase::FAILED, std::memory_order_release);
                }
                {
                    std::lock_guard<std::mutex> lock(mutex_);
                    upload_seal_requested_ = false;
                    upload_sealed_ = retired;
                    upload_exited_ = true;
                }
                upload_seal_condition_.notify_all();
                upload_exit_condition_.notify_all();
                return;
            }
            if (has_preallocation) {
                const bool allocated = preallocate_texture(preallocation);
                if (!allocated) {
                    NTK_LOGE("fatal texture preallocation page=%d slot=%d size=%dx%d",
                             preallocation.key.page, preallocation.key.slot,
                             preallocation.width, preallocation.height);
                    fail_gpu_scene_admission("GL_RGBA8 immutable storage allocation");
                }
                {
                    std::lock_guard<std::mutex> lock(mutex_);
                    upload_active_ = false;
                    ++command_generation_;
                }
                preparation_drain_condition_.notify_all();
                render_condition_.notify_one();
                continue;
            }
            if (!has_upload) continue;
            GpuReadyTile ready;
            upload_commands_submitting_.fetch_add(1, std::memory_order_acq_rel);
            const bool still_current = active_authority_generation_.load(
                std::memory_order_acquire) == command.authority_generation &&
                active_authority_.load(std::memory_order_acquire) == command.key.authority;
            if (still_current && authority_failed_.load(std::memory_order_acquire)) {
                ready.key = command.key;
                ready.authority_generation = command.authority_generation;
                ready.preparation_generation = command.preparation_generation;
                ready.surface_epoch = command.surface_epoch;
                ready.admission_id = command.admission_id;
                ready.resource_revision = command.resource_revision;
                ready.install_lease = command.install_lease;
                ready.rgba_bytes = command.rgba_bytes;
                ready.tile_proof_digest = command.tile_proof_digest;
                ready.pre_geometry = command.pre_geometry;
                ready.prepared_protocol = command.prepared_protocol;
            } else {
                ready = upload_bitmap(env, command);
            }
            upload_commands_submitting_.fetch_sub(1, std::memory_order_acq_rel);
            if (env != nullptr && command.bitmap != nullptr) env->DeleteGlobalRef(command.bitmap);
            bool routed_to_release = false;
            {
                std::lock_guard<std::mutex> lock(mutex_);
                upload_active_ = false;
                in_flight_upload_.reset();
                const AuthorityKey key{
                    engine_generation_, command.authority_generation, command.key.authority};
                const auto tracker = release_trackers_.find(key);
                if (tracker != release_trackers_.end()) {
                    tracker->second->in_flight_upload = false;
                    ready.release_transition_output = true;
                    tracker->second->ready_tiles.push_back(ready);
                    const std::int64_t resource_scope = command.pre_geometry
                        ? command.preparation_generation : command.surface_epoch;
                    record_released_resource(*tracker->second, release_identity(
                        command.pre_geometry ? "in-flight-detached-upload" :
                            "in-flight-upload",
                        key, resource_scope,
                        command.admission_id, command.key.page, command.key.slot,
                        command.resource_revision, command.install_lease, 0,
                        command.rgba_bytes));
                    if (env != nullptr && command.bitmap != nullptr) {
                        record_released_resource(*tracker->second, release_identity(
                            command.pre_geometry ? "detached-bitmap-global-ref" :
                                "bitmap-global-ref",
                            key, resource_scope,
                            command.admission_id, command.key.page, command.key.slot,
                            command.resource_revision, command.install_lease, 0,
                            command.rgba_bytes));
                        ++tracker->second->released_bitmap_global_ref_count;
                    }
                    // The texture storage existed before the authority close and was captured
                    // as an in-flight resource. Its physical deletion is recorded by the
                    // release worker when this ready result is consumed.
                    ++tracker->second->drained_upload_count;
                    routed_to_release = true;
                } else {
                    const std::int64_t resource_scope = command.pre_geometry
                        ? command.preparation_generation : command.surface_epoch;
                    registry_remove_locked(release_identity(
                        command.pre_geometry ? "in-flight-detached-upload" :
                            "in-flight-upload",
                        key, resource_scope,
                        command.admission_id, command.key.page, command.key.slot,
                        command.resource_revision, command.install_lease, 0,
                        command.rgba_bytes));
                    if (env != nullptr && command.bitmap != nullptr) {
                        registry_remove_locked(release_identity(
                            command.pre_geometry ? "detached-bitmap-global-ref" :
                                "bitmap-global-ref",
                            key, resource_scope,
                            command.admission_id, command.key.page, command.key.slot,
                            command.resource_revision, command.install_lease, 0,
                            command.rgba_bytes));
                    }
                    if (ready.consumed_preallocation) {
                        registry_remove_locked(release_identity(
                            "in-flight-upload-texture", key, command.surface_epoch,
                            command.admission_id, command.key.page, command.key.slot,
                            command.resource_revision, command.install_lease, 0,
                            command.rgba_bytes));
                    }
                    if (ready.texture != 0) registry_add_locked(release_identity(
                        ready.pre_geometry ? "gpu-ready-detached-texture" :
                            "gpu-ready-texture",
                        key, ready.pre_geometry ? ready.preparation_generation :
                            ready.surface_epoch,
                        ready.admission_id, ready.key.page, ready.key.slot,
                        ready.resource_revision, ready.install_lease, 0,
                        ready.rgba_bytes));
                    if (ready.upload_fence != nullptr) registry_add_locked(release_identity(
                        ready.pre_geometry ? "detached-upload-fence" :
                            "upload-fence",
                        key, ready.pre_geometry ? ready.preparation_generation :
                            ready.surface_epoch,
                        ready.admission_id,
                        ready.key.page, ready.key.slot, ready.resource_revision,
                        ready.install_lease, 0, ready.rgba_bytes));
                    gpu_ready_tiles_.push_back(ready);
                    ready_tile_queue_depth_mirror_.store(
                        static_cast<int>(gpu_ready_tiles_.size()),
                        std::memory_order_release);
                }
                ++command_generation_;
            }
            preparation_drain_condition_.notify_all();
            if (routed_to_release) complete_publication(ready, 0, false);
            render_condition_.notify_one();
            upload_condition_.notify_one();
        }
        std::deque<UploadCommand> abandoned_uploads;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            abandoned_uploads.swap(upload_commands_);
            for (auto& entry : release_trackers_) {
                while (!entry.second->queued_uploads.empty()) {
                    abandoned_uploads.push_back(
                        std::move(entry.second->queued_uploads.front()));
                    entry.second->queued_uploads.pop_front();
                }
            }
        }
        if (env != nullptr) {
            // Final engine destruction may abandon proof delivery, but never leaks a bitmap
            // global ref and never invokes JNI while mutex_ is held.
            for (auto& command : abandoned_uploads) {
                if (command.bitmap != nullptr) env->DeleteGlobalRef(command.bitmap);
            }
        }
        if (upload_context_ != EGL_NO_CONTEXT) {
            eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        }
        eglReleaseThread();
        std::lock_guard<std::mutex> lock(mutex_);
        upload_exited_ = true;
        upload_exit_condition_.notify_all();
    }

    bool wait_upload_chunk(GLsync fence) {
        if (fence == nullptr) return false;
        const GLenum status = glClientWaitSync(
            fence, GL_SYNC_FLUSH_COMMANDS_BIT, GL_TIMEOUT_IGNORED);
        return status == GL_ALREADY_SIGNALED ||
            status == GL_CONDITION_SATISFIED;
    }

    bool preallocate_texture(const PreallocateCommand& command) {
        PreallocatedTexture slot;
        slot.width = command.width;
        slot.height = command.height;
        bool success = false;
        bool superseded = false;
        {
            std::lock_guard<std::mutex> submit_lock(upload_submit_mutex_);
            if (gpu_phase_.load(std::memory_order_acquire) != GpuPhase::PRE_STAGE_GPU ||
                upload_submission_blocked_.load(std::memory_order_acquire) ||
                !upload_context_alive_.load(std::memory_order_acquire) ||
                !resource_worker_owns(command.authority_generation,
                                      command.key.authority)) return false;
            while (glGetError() != GL_NO_ERROR) { }
            glGenTextures(1, &slot.texture);
            glBindTexture(GL_TEXTURE_2D, slot.texture);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, command.width, command.height);
            GLint immutable_format = GL_FALSE;
            glGetTexParameteriv(
                GL_TEXTURE_2D, GL_TEXTURE_IMMUTABLE_FORMAT, &immutable_format);
            const GLenum storage_error = glGetError();
            GLsync fence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            glFlush();
            resource_submit_serial_.fetch_add(1, std::memory_order_acq_rel);
            const bool complete = wait_upload_chunk(fence);
            if (fence != nullptr) glDeleteSync(fence);
            success = complete && slot.texture != 0 && immutable_format == GL_TRUE &&
                storage_error == GL_NO_ERROR && glGetError() == GL_NO_ERROR;
            superseded = active_authority_generation_.load(std::memory_order_acquire) !=
                    command.authority_generation ||
                active_authority_.load(std::memory_order_acquire) !=
                    command.key.authority ||
                upload_submission_blocked_.load(std::memory_order_acquire);
            if (superseded && slot.texture != 0) {
                glDeleteTextures(1, &slot.texture);
                slot.texture = 0;
            }
            if (!success && slot.texture != 0) glDeleteTextures(1, &slot.texture);
        }
        if (superseded) return true;
        if (!success) return false;
        last_gpu_resource_completion_ns_.store(
            monotonic_now_ns(), std::memory_order_release);
        std::lock_guard<std::mutex> lock(mutex_);
        const bool inserted = preallocated_textures_.emplace(command.key, slot).second;
        if (!inserted) return false;
        const std::int64_t bytes = rgba8_bytes(slot.width, slot.height);
        if (!gpu_scene_admission_.record_storage(
                bytes, preallocated_textures_.size() + scene_.size(),
                preallocate_commands_.empty(), scene_.size(),
                gpu_scene_admission_.resident_texture_count ==
                        gpu_scene_admission_.expected_texture_count
                    ? gpu_scene_digest_from_resident_locked() : std::string{})) return false;
        registry_add_locked(release_identity(
            "preallocated-texture",
            AuthorityKey{engine_generation_, command.authority_generation,
                         command.key.authority},
            0, 0, command.key.page, command.key.slot, 0, 0, 0,
            bytes));
        return true;
    }

    GpuReadyTile upload_bitmap(JNIEnv* env, const UploadCommand& command) {
        GpuReadyTile ready;
        ready.key = command.key;
        ready.authority_generation = command.authority_generation;
        ready.preparation_generation = command.preparation_generation;
        ready.surface_epoch = command.surface_epoch;
        ready.admission_id = command.admission_id;
        ready.resource_revision = command.resource_revision;
        ready.install_lease = command.install_lease;
        ready.rgba_bytes = command.rgba_bytes;
        ready.content_top = command.content_top;
        ready.content_bottom = command.content_bottom;
        ready.tile_proof_digest = command.tile_proof_digest;
        ready.pre_geometry = command.pre_geometry;
        ready.prepared_protocol = command.prepared_protocol;
        if (command.pre_geometry) {
            ready.width = command.width;
            ready.height = command.height;
        }
        if (env == nullptr || command.bitmap == nullptr) return ready;
        AndroidBitmapInfo info{};
        if (AndroidBitmap_getInfo(env, command.bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
            info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || info.width == 0 || info.height == 0 ||
            info.stride < info.width * 4U) {
            return ready;
        }
        PreallocatedTexture allocation;
        if (command.pre_geometry) {
            if (command.width != static_cast<int>(info.width) ||
                command.height != static_cast<int>(info.height) ||
                command.rgba_bytes != static_cast<std::int64_t>(info.width) *
                    info.height * 4LL || !is_sha256(command.tile_proof_digest)) return ready;
            allocation.width = command.width;
            allocation.height = command.height;
            {
                std::lock_guard<std::mutex> submit_lock(upload_submit_mutex_);
                if (!context_resources_valid_.load(std::memory_order_acquire) ||
                    active_authority_generation_.load(std::memory_order_acquire) !=
                        command.authority_generation ||
                    active_authority_.load(std::memory_order_acquire) !=
                        command.key.authority ||
                    upload_submission_blocked_.load(std::memory_order_acquire) ||
                    !upload_context_alive_.load(std::memory_order_acquire)) return ready;
                while (glGetError() != GL_NO_ERROR) { }
                glGenTextures(1, &allocation.texture);
                glBindTexture(GL_TEXTURE_2D, allocation.texture);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
                glTexStorage2D(
                    GL_TEXTURE_2D, 1, GL_RGBA8, command.width, command.height);
                GLint immutable_format = GL_FALSE;
                glGetTexParameteriv(
                    GL_TEXTURE_2D, GL_TEXTURE_IMMUTABLE_FORMAT, &immutable_format);
                if (allocation.texture == 0 || immutable_format != GL_TRUE ||
                    glGetError() != GL_NO_ERROR) {
                    if (allocation.texture != 0) {
                        glDeleteTextures(1, &allocation.texture);
                    }
                    allocation.texture = 0;
                    return ready;
                }
            }
            const AuthorityKey token{
                engine_generation_, command.authority_generation, command.key.authority};
            const ReleaseResourceIdentity texture_identity = release_identity(
                "in-flight-upload-texture", token, command.surface_epoch,
                command.admission_id, command.key.page, command.key.slot,
                command.resource_revision, command.install_lease, 0,
                command.rgba_bytes);
            {
                std::lock_guard<std::mutex> lock(mutex_);
                const auto tracker = release_trackers_.find(token);
                if (tracker == release_trackers_.end()) {
                    registry_add_locked(texture_identity);
                } else {
                    tracker->second->captured_resources.push_back(texture_identity);
                }
            }
        } else {
          {
            std::lock_guard<std::mutex> lock(mutex_);
            const auto found = slot_specs_.find(command.key);
            if (found == slot_specs_.end() ||
                found->second.width != static_cast<int>(info.width) ||
                found->second.height != static_cast<int>(info.height) ||
                found->second.content_top != command.content_top ||
                found->second.content_bottom != command.content_bottom ||
                command.rgba_bytes != static_cast<std::int64_t>(info.width) * info.height * 4LL) {
                return ready;
            }
            if (!gpu_scene_admission_.begin_upload()) return ready;
            const auto allocated = preallocated_textures_.find(command.key);
            if (allocated == preallocated_textures_.end() || allocated->second.texture == 0 ||
                allocated->second.width != static_cast<int>(info.width) ||
                allocated->second.height != static_cast<int>(info.height)) return ready;
            allocation = allocated->second;
            preallocated_textures_.erase(allocated);
            const AuthorityKey token{
                engine_generation_, command.authority_generation, command.key.authority};
            registry_remove_locked(release_identity(
                "preallocated-texture", token, 0, 0, command.key.page,
                command.key.slot, 0, 0, 0, command.rgba_bytes));
            registry_add_locked(release_identity(
                "in-flight-upload-texture", token, command.surface_epoch,
                command.admission_id, command.key.page, command.key.slot,
                command.resource_revision, command.install_lease, 0,
                command.rgba_bytes));
          }
        }
        ready.texture = allocation.texture;
        ready.consumed_preallocation = true;
        void* pixels = nullptr;
        if (AndroidBitmap_lockPixels(env, command.bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS ||
            pixels == nullptr) {
            return ready;
        }
        while (glGetError() != GL_NO_ERROR) { }
        glBindTexture(GL_TEXTURE_2D, ready.texture);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
        glPixelStorei(GL_UNPACK_ROW_LENGTH, static_cast<GLint>(info.stride / 4U));
        // Keep submissions bounded so an authority/context change is observed between chunks,
        // but do not synchronously fence every 128 rows. The exact per-tile publication fence
        // below orders the complete upload before the render context can integrate it; the
        // failure path also fences any prefix submitted before an authority change. Waiting here
        // duplicated that terminal proof hundreds of times while constructing a full scene.
        constexpr std::uint32_t kUploadChunkRows = 128U;
        bool chunks_complete = true;
        bool context_lost_during_upload = false;
        for (std::uint32_t top = 0; top < info.height; top += kUploadChunkRows) {
            std::lock_guard<std::mutex> submit_lock(upload_submit_mutex_);
            if (!context_resources_valid_.load(std::memory_order_acquire)) {
                context_lost_during_upload = true;
                chunks_complete = false;
                break;
            }
            if (active_authority_generation_.load(std::memory_order_acquire) !=
                    command.authority_generation ||
                active_authority_.load(std::memory_order_acquire) != command.key.authority ||
                upload_submission_blocked_.load(std::memory_order_acquire) ||
                !upload_context_alive_.load(std::memory_order_acquire)) {
                chunks_complete = false;
                break;
            }
            const std::uint32_t rows = std::min(kUploadChunkRows, info.height - top);
            const auto* chunk_pixels = static_cast<const std::uint8_t*>(pixels) +
                static_cast<std::size_t>(top) * info.stride;
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, static_cast<GLint>(top),
                            static_cast<GLsizei>(info.width), static_cast<GLsizei>(rows),
                            GL_RGBA, GL_UNSIGNED_BYTE, chunk_pixels);
            if (glGetError() != GL_NO_ERROR) {
                chunks_complete = false;
                break;
            }
        }
        if (!context_lost_during_upload) glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
        AndroidBitmap_unlockPixels(env, command.bitmap);
        if (context_lost_during_upload) {
            ready.width = static_cast<int>(info.width);
            ready.height = static_cast<int>(info.height);
            ready.upload_fence = nullptr;
            ready.success = false;
            return ready;
        }
        if (!chunks_complete || glGetError() != GL_NO_ERROR) {
            std::lock_guard<std::mutex> submit_lock(upload_submit_mutex_);
            // A generation close may race a chunk boundary. Preserve the created texture and
            // its completion fence for the old token's release tracker; never globally stall
            // the shared group with glFinish on normal authority release.
            ready.width = static_cast<int>(info.width);
            ready.height = static_cast<int>(info.height);
            ready.upload_fence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            glFlush();
            resource_submit_serial_.fetch_add(1, std::memory_order_acq_rel);
            ready.fence_submitted_ns = monotonic_now_ns();
            ready.success = false;
            return ready;
        }
        ready.width = static_cast<int>(info.width);
        ready.height = static_cast<int>(info.height);
        // Publish the upload to the shared render context without globally stalling that
        // context. The render owner polls this fence with a zero timeout and integrates only a
        // signaled texture, preserving both frame cadence and publication ordering.
        {
            std::lock_guard<std::mutex> submit_lock(upload_submit_mutex_);
            if (active_authority_generation_.load(std::memory_order_acquire) ==
                    command.authority_generation &&
                active_authority_.load(std::memory_order_acquire) == command.key.authority &&
                !upload_submission_blocked_.load(std::memory_order_acquire) &&
                upload_context_alive_.load(std::memory_order_acquire)) {
                ready.upload_fence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
                glFlush();
                resource_submit_serial_.fetch_add(1, std::memory_order_acq_rel);
                ready.fence_submitted_ns = monotonic_now_ns();
            }
        }
        ready.success = ready.texture != 0 && ready.upload_fence != nullptr;
        if (ready.success) upload_gpu_fences_pending_.fetch_add(1, std::memory_order_acq_rel);
        if (!ready.success && ready.upload_fence != nullptr) {
            glDeleteSync(ready.upload_fence);
            ready.upload_fence = nullptr;
        }
        return ready;
    }

    bool open_detached_preparation_scene(BindRequest& request) {
        if (request.kind != BindRequestKind::OPEN_DETACHED_PREPARATION ||
            !request.ticket ||
            request.request_generation == 0 ||
            request.ticket->request_generation != request.request_generation ||
            request.successor.engine_generation != engine_generation_ ||
            request.preparation_generation <= 0 ||
            !is_sha256(request.manifest_digest)) return false;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (stopped_ || request.ticket->completed || !egl_ready_ ||
                !upload_context_alive_.load(std::memory_order_acquire)) return false;
            request.ticket->transition_started = true;
        }
        if (authority_ > 0 &&
            context_resources_valid_.load(std::memory_order_acquire) &&
            !fixed_scheduler_.normalTerminalConservationExact()) return false;
        if (!abort_prepared_frame_for_lifecycle()) return false;
        block_input_and_presentation();
        upload_submission_blocked_.store(true, std::memory_order_release);
        renderer_mode_.store(RendererMode::PREPARING, std::memory_order_release);
        staged_nonce_.store(0, std::memory_order_release);
        stage_pin_active_.store(false, std::memory_order_release);
        if (authority_ > 0 &&
            (authority_ != request.successor.authority ||
             authority_generation_ != request.successor.authority_generation)) {
            std::optional<ReleaseClaim> claim;
            {
                std::lock_guard<std::mutex> lock(mutex_);
                const auto found = pending_release_claims_.find(current_authority_key());
                if (found != pending_release_claims_.end()) claim = found->second;
            }
            if (!begin_release_current_on_render(claim, false)) return false;
            scene_version_ = 0;
            applied_protection_ = AppliedProtection{};
            presented_view_state_ = PresentedViewState{};
        }
        if (!evidence_capsules_drained()) return false;
        head_frame_state_ = HeadFrameState::EMPTY;
        prepared_frame_work_.reset();
        admission_predecessor_.reset();
        reserved_frame_id_ = 0;
        reserved_frame_id_work_generation_ = 0;
        fixed_scheduler_.reset(presented_view_state_);
        presented_visual_mutation_serial_ = 0;
        fixed_admission_sequence_ = 0;
        clear_fixed_opportunity_ownership();
        stage_nonce_ = 0;
        stage_corridor_start_ = 0;
        stage_corridor_end_ = 0;
        sealed_scene_version_ = 0;
        sealed_tile_count_ = 0;
        sealed_content_end_ = 0;
        stage_requested_ = false;
        stage_authority_ = 0;
        gpu_phase_.store(GpuPhase::PRE_STAGE_GPU, std::memory_order_release);
        sealed_resource_submit_serial_.store(0, std::memory_order_release);
        seal_barrier_serial_.store(0, std::memory_order_release);
        scene_sealed_.store(false, std::memory_order_release);
        seal_fence_completion_ns_.store(0, std::memory_order_release);
        offscreen_warm_fence_completion_ns_.store(0, std::memory_order_release);
        predecessor_physical_complete_ns_.store(0, std::memory_order_release);
        upload_context_destroyed_ns_.store(0, std::memory_order_release);
        stage_backbuffer_ready_ns_.store(0, std::memory_order_release);
        offscreen_warm_draw_count_.store(0, std::memory_order_release);
        scene_mutation_count_.store(0, std::memory_order_release);
        sealed_scene_mutation_count_.store(0, std::memory_order_release);
        stage_latch_ns_.store(0, std::memory_order_release);
        first_down_ingress_ns_.store(0, std::memory_order_release);
        ingress_pointer_down_.store(false, std::memory_order_release);
        last_ingress_event_time_ns_.store(0, std::memory_order_release);
        last_ingress_main_time_ns_.store(0, std::memory_order_release);
        if (!prepare_resource_worker_for_bind(request.successor)) return false;
        if (bind_cancel_requested(request.ticket)) {
            retire_resource_worker(request.successor);
            return false;
        }
        const std::int64_t opened_ns = monotonic_now_ns();
        ntk::prepared_scene::Ledger ledger;
        if (!ledger.open(
                request.successor.authority_generation,
                request.successor.authority,
                request.preparation_generation,
                request.manifest_revision, request.manifest_digest,
                opened_ns)) return false;
        Sha256 pending_geometry_sha;
        sha256_token(pending_geometry_sha, "ntk-native-preparation-pending-v1");
        sha256_token(pending_geometry_sha, request.manifest_digest);
        sha256_token(
            pending_geometry_sha, std::to_string(request.preparation_generation));
        const std::string pending_geometry_digest = pending_geometry_sha.finish();
        bool cancelled = false;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            cancelled = stopped_ || request.ticket->cancel_requested;
            if (!cancelled) {
                authority_ = request.successor.authority;
                authority_generation_ = request.successor.authority_generation;
                preparation_open_ = false;
                prepared_geometry_bound_ = false;
                preparation_admissions_closed_ = false;
                preparation_token_nonce_ = 0;
                preparation_opened_ns_ = 0;
                prepared_bank_ledger_ = ntk::prepared_scene::Ledger{};
                current_manifest_revision_ = request.manifest_revision;
                current_manifest_digest_ = request.manifest_digest;
                current_geometry_digest_ = pending_geometry_digest;
                gpu_scene_admission_ = GpuSceneAdmissionLedger{};
                slot_specs_.clear();
                ordinal_keys_.clear();
                key_ordinals_.clear();
                preallocate_commands_.clear();
                preallocated_textures_.clear();
                prepared_bank_.clear();
                scene_.clear();
                resident_intervals_.clear();
                expected_tile_count_ = 0;
                content_height_ = 0;
                prepared_bank_ledger_ = std::move(ledger);
                preparation_open_ = true;
                prepared_geometry_bound_ = false;
                preparation_admissions_closed_ = false;
                preparation_token_nonce_ =
                    static_cast<std::int64_t>(request.request_generation);
                preparation_opened_ns_ = opened_ns;
                active_authority_.store(authority_, std::memory_order_release);
                active_authority_generation_.store(
                    authority_generation_, std::memory_order_release);
                upload_submission_blocked_.store(false, std::memory_order_release);
                input_admission_blocked_.store(true, std::memory_order_release);
                presentation_blocked_.store(true, std::memory_order_release);
                request.ticket->success = true;
                request.ticket->accepted_authority_generation = authority_generation_;
                request.ticket->geometry_bind_completion_ns = opened_ns;
                request.ticket->completed = true;
            }
        }
        if (cancelled) {
            retire_resource_worker(request.successor);
            return false;
        }
        bind_condition_.notify_all();
        upload_condition_.notify_one();
        return true;
    }

    BindSceneDisposition adopt_detached_preparation_scene(BindRequest& request) {
        if (request.kind != BindRequestKind::SURFACE_ADOPTION || !request.ticket ||
            request.request_generation == 0 ||
            request.ticket->request_generation != request.request_generation ||
            request.successor.engine_generation != engine_generation_ ||
            request.preparation_generation <= 0 ||
            request.demand_generation <= 0 ||
            request.adoption_attach_generation <= 0 ||
            request.adoption_surface_epoch <= 0 ||
            request.adoption_geometry_revision <= 0 ||
            request.adoption_surface_width <= 0 ||
            request.adoption_surface_height <= 0 ||
            !is_sha256(request.geometry_digest) ||
            !is_sha256(request.pregeometry_root_digest) ||
            !is_sha256(request.prepared_inventory_digest)) {
            return BindSceneDisposition::FAILED;
        }
        std::vector<PreallocateCommand> proof_slots;
        proof_slots.reserve(request.ordinal_keys.size());
        std::int64_t proof_bytes = 0;
        for (const TileKey& key : request.ordinal_keys) {
            const auto found = request.slot_specs.find(key);
            if (found == request.slot_specs.end()) {
                return BindSceneDisposition::FAILED;
            }
            const std::int64_t bytes = rgba8_bytes(found->second.width, found->second.height);
            if (bytes <= 0 || proof_bytes >
                    std::numeric_limits<std::int64_t>::max() - bytes) {
                return BindSceneDisposition::FAILED;
            }
            proof_bytes += bytes;
            proof_slots.push_back(found->second);
        }
        if (request.gpu_scene_format != GpuSceneFormat::RGBA8_UNORM ||
            proof_bytes != request.gpu_scene_logical_bytes ||
            gpu_scene_digest_from_slots(
                request.geometry_digest, request.pregeometry_root_digest, proof_slots) !=
                request.gpu_scene_digest) {
            return BindSceneDisposition::FAILED;
        }
        GpuSceneAdmissionLedger admission;
        if (!admission.begin(
                request.gpu_scene_format, request.ordinal_keys.size(),
                request.gpu_scene_logical_bytes, request.gpu_scene_digest)) {
            return BindSceneDisposition::FAILED;
        }
        std::deque<PreallocateCommand> missing;
        std::size_t adopted_count = 0;
        std::string prepared_digest;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (stopped_ || request.ticket->completed || !preparation_open_ ||
                prepared_geometry_bound_ || authority_ != request.successor.authority ||
                authority_generation_ != request.successor.authority_generation ||
                prepared_bank_ledger_.preparationGeneration() !=
                    request.preparation_generation ||
                request.manifest_revision != current_manifest_revision_ ||
                request.manifest_digest != current_manifest_digest_ ||
                !attach_request_.has_value() ||
                attach_request_->generation !=
                    static_cast<std::uint64_t>(
                        request.adoption_attach_generation) ||
                attach_request_->surface_epoch !=
                    static_cast<std::uint64_t>(
                        request.adoption_surface_epoch) ||
                attach_request_->state != AttachState::PUBLISHED ||
                attach_request_->applied_geometry_revision !=
                    static_cast<std::uint64_t>(
                        request.adoption_geometry_revision) ||
                attach_request_->width != request.adoption_surface_width ||
                attach_request_->height != request.adoption_surface_height) {
                return BindSceneDisposition::FAILED;
            }
            const auto drain = ntk::prepared_scene::classifyGeometryBindDrain({
                native_outstanding_,
                upload_active_,
                upload_commands_.size(),
                in_flight_upload_.has_value(),
                gpu_ready_tiles_.size(),
                upload_commands_submitting_.load(std::memory_order_acquire),
                upload_gpu_fences_pending_.load(std::memory_order_acquire),
                prepared_bank_ledger_.inFlight()
            });
            if (drain == ntk::prepared_scene::GeometryBindDrainDisposition::WAIT) {
                return BindSceneDisposition::DEFERRED;
            }
            if (drain == ntk::prepared_scene::GeometryBindDrainDisposition::REJECT) {
                return BindSceneDisposition::FAILED;
            }
            prepared_digest = prepared_inventory_digest_locked();
            if (prepared_digest != request.prepared_inventory_digest ||
                !prepared_bank_ledger_.beginSurfaceAdoption({
                    request.demand_generation,
                    request.adoption_attach_generation,
                    request.adoption_surface_epoch,
                    request.adoption_geometry_revision,
                    request.adoption_surface_width,
                    request.adoption_surface_height,
                    request.ordinal_keys.size(),
                    request.pregeometry_root_digest,
                    request.prepared_inventory_digest
                })) {
                return BindSceneDisposition::FAILED;
            }
            for (const auto& entry : prepared_bank_) {
                const auto spec = request.slot_specs.find(entry.first);
                if (spec == request.slot_specs.end() ||
                    spec->second.width != entry.second.width ||
                    spec->second.height != entry.second.height ||
                    entry.second.preparation_generation !=
                        request.preparation_generation) {
                    return BindSceneDisposition::FAILED;
                }
            }
            request.ticket->transition_started = true;
            current_geometry_digest_ = request.geometry_digest;
            current_pregeometry_root_digest_ = request.pregeometry_root_digest;
            gpu_scene_admission_ = std::move(admission);
            slot_specs_ = request.slot_specs;
            ordinal_keys_ = request.ordinal_keys;
            key_ordinals_ = request.key_ordinals;
            expected_tile_count_ = ordinal_keys_.size();
            content_height_ = request.content_height;
            viewport_width_ = request.viewport_width;
            viewport_height_ = request.viewport_height;
            presented_view_state_.scroll_top = std::clamp<std::int64_t>(
                request.scroll_top, 0,
                std::max<std::int64_t>(0, content_height_ - viewport_height_));
            presented_view_state_.velocity_px_per_second = 0.0F;
            presented_view_state_.scroll_direction = 0;
            fixed_scheduler_.reset(presented_view_state_);
            presented_visual_mutation_serial_ = 0;
            for (const TileKey& key : ordinal_keys_) {
                const auto spec = slot_specs_.find(key);
                auto prepared = prepared_bank_.find(key);
                if (prepared == prepared_bank_.end()) {
                    missing.push_back(spec->second);
                    continue;
                }
                const PreparedBankTile tile = prepared->second;
                if (!gpu_scene_admission_.record_adopted_resident(tile.rgba_bytes)) {
                    return BindSceneDisposition::FAILED;
                }
                scene_[key] = SceneTile{
                    request.adoption_surface_epoch,
                    tile.admission_id, tile.resource_revision,
                    tile.install_lease, tile.rgba_bytes, tile.texture,
                    tile.width, tile.height,
                    spec->second.content_top, spec->second.content_bottom};
                const AuthorityKey token{
                    engine_generation_, authority_generation_, authority_};
                registry_remove_locked(release_identity(
                    "prepared-bank-texture", token,
                    tile.preparation_generation,
                    tile.admission_id, key.page, key.slot,
                    tile.resource_revision, tile.install_lease, 0,
                    tile.rgba_bytes));
                registry_add_locked(release_identity(
                    "scene-texture", token, request.adoption_surface_epoch,
                    tile.admission_id, key.page, key.slot,
                    tile.resource_revision, tile.install_lease, 0,
                    tile.rgba_bytes));
                prepared_bank_.erase(prepared);
                ++adopted_count;
            }
            if (!prepared_bank_.empty()) return BindSceneDisposition::FAILED;
            if (missing.empty() && !gpu_scene_admission_.finish_adopted_storage(
                    scene_.size(), gpu_scene_digest_from_resident_locked())) {
                return BindSceneDisposition::FAILED;
            }
            const std::int64_t completion_ns = monotonic_now_ns();
            if (!prepared_bank_ledger_.finishSurfaceAdoption(
                    adopted_count, missing.size(), completion_ns)) {
                return BindSceneDisposition::FAILED;
            }
            preallocate_commands_ = missing;
            prepared_geometry_bound_ = true;
            scene_version_ = static_cast<std::int64_t>(adopted_count);
            scene_mutation_count_.fetch_add(adopted_count, std::memory_order_acq_rel);
            integrated_tiles_since_frame_ += static_cast<int>(adopted_count);
            presentation_blocked_.store(false, std::memory_order_release);
            bind_apply_count_.fetch_add(1, std::memory_order_acq_rel);
            bind_committed_ns_.store(completion_ns, std::memory_order_release);
            request.ticket->success = true;
            request.ticket->accepted_authority_generation = authority_generation_;
            request.ticket->adopted_prepared_count = adopted_count;
            request.ticket->missing_geometry_count = missing.size();
            request.ticket->prepared_inventory_digest = prepared_digest;
            request.ticket->resident_inventory_digest =
                resident_scene_inventory_digest_locked();
            request.ticket->geometry_bind_completion_ns = completion_ns;
            request.ticket->last_resource_completion_ns =
                prepared_bank_ledger_.lastCompletionNanos();
            request.ticket->completed = true;
        }
        rebuild_resident_intervals();
        bind_condition_.notify_all();
        upload_condition_.notify_one();
        return BindSceneDisposition::COMPLETED;
    }

    BindSceneDisposition bind_scene(BindRequest& request) {
        if (request.kind == BindRequestKind::OPEN_DETACHED_PREPARATION) {
            return open_detached_preparation_scene(request)
                ? BindSceneDisposition::COMPLETED
                : BindSceneDisposition::FAILED;
        }
        if (request.kind == BindRequestKind::SURFACE_ADOPTION) {
            return adopt_detached_preparation_scene(request);
        }
        if (!request.ticket || request.request_generation == 0 ||
            request.ticket->request_generation != request.request_generation ||
            request.successor.engine_generation != engine_generation_) {
            return BindSceneDisposition::FAILED;
        }
        std::vector<PreallocateCommand> proof_slots;
        proof_slots.reserve(request.ordinal_keys.size());
        std::int64_t proof_bytes = 0;
        for (const TileKey& key : request.ordinal_keys) {
            const auto found = request.slot_specs.find(key);
            if (found == request.slot_specs.end()) {
                return BindSceneDisposition::FAILED;
            }
            const std::int64_t bytes = rgba8_bytes(found->second.width, found->second.height);
            if (bytes <= 0 || proof_bytes >
                    std::numeric_limits<std::int64_t>::max() - bytes) {
                return BindSceneDisposition::FAILED;
            }
            proof_bytes += bytes;
            proof_slots.push_back(found->second);
        }
        if (request.gpu_scene_format != GpuSceneFormat::RGBA8_UNORM ||
            proof_bytes != request.gpu_scene_logical_bytes ||
            gpu_scene_digest_from_slots(
                request.geometry_digest, request.pregeometry_root_digest, proof_slots) !=
                request.gpu_scene_digest) {
            return BindSceneDisposition::FAILED;
        }
        GpuSceneAdmissionLedger initial_gpu_scene_admission;
        if (!initial_gpu_scene_admission.begin(
                request.gpu_scene_format, request.ordinal_keys.size(),
                request.gpu_scene_logical_bytes, request.gpu_scene_digest)) {
            return BindSceneDisposition::FAILED;
        }
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (stopped_ || request.ticket->completed) {
                return BindSceneDisposition::FAILED;
            }
            request.ticket->transition_started = true;
        }
        if (authority_ > 0 &&
            context_resources_valid_.load(std::memory_order_acquire) &&
            !fixed_scheduler_.normalTerminalConservationExact()) {
            NTK_LOGE("fatal successor bind before terminal conservation");
            authority_failed_.store(true, std::memory_order_release);
            gpu_phase_.store(GpuPhase::FAILED, std::memory_order_release);
            return BindSceneDisposition::FAILED;
        }
        // A bind is a lifecycle close for any unsubmitted prepared backbuffer. Destroy the exact
        // caller-thread fence and discard its reserved (never committed) frame ID before moving
        // predecessor resources or allowing the successor to reserve the process-global slot.
        if (!abort_prepared_frame_for_lifecycle()) {
            return BindSceneDisposition::FAILED;
        }
        block_input_and_presentation();
        upload_submission_blocked_.store(true, std::memory_order_release);
        renderer_mode_.store(RendererMode::PREPARING, std::memory_order_release);
        staged_nonce_.store(0, std::memory_order_release);
        stage_pin_active_.store(false, std::memory_order_release);
        if (authority_ > 0 &&
            (authority_ != request.successor.authority ||
             authority_generation_ != request.successor.authority_generation)) {
            std::optional<ReleaseClaim> claim;
            {
                std::lock_guard<std::mutex> lock(mutex_);
                const auto found = pending_release_claims_.find(current_authority_key());
                if (found != pending_release_claims_.end()) claim = found->second;
            }
            // Successor bind owns the worker-generation transition below.  The predecessor
            // inventory is moved first, then its exact worker drains and retires before a B
            // context/thread can exist.
            if (!begin_release_current_on_render(claim, false)) {
                authority_failed_.store(true, std::memory_order_release);
                gpu_phase_.store(GpuPhase::FAILED, std::memory_order_release);
                return BindSceneDisposition::FAILED;
            }
            scene_version_ = 0;
            applied_protection_ = AppliedProtection{};
            // Direction is scoped to one manifest authority.  The initial UNSET value has a
            // deterministic forward interpretation and is replaced only by an applied MOVE
            // that actually changes scrollTop.
            presented_view_state_.scroll_direction = 0;
            presented_view_state_.velocity_px_per_second = 0.0F;
        }
        // Admission, stage, and sealed-scene state belongs exclusively to the render lane.
        // Reset it here after the predecessor handoff, never from the JNI bind caller while a
        // prior draw can still be resolving.
        if (!evidence_capsules_drained()) {
            NTK_LOGE("fatal bind before prior submitted evidence retirement");
            authority_failed_.store(true, std::memory_order_release);
            gpu_phase_.store(GpuPhase::FAILED, std::memory_order_release);
            return BindSceneDisposition::FAILED;
        }
        head_frame_state_ = HeadFrameState::EMPTY;
        prepared_frame_work_.reset();
        admission_predecessor_.reset();
        reserved_frame_id_ = 0;
        reserved_frame_id_work_generation_ = 0;
        fixed_scheduler_.reset(presented_view_state_);
        presented_visual_mutation_serial_ = 0;
        fixed_admission_sequence_ = 0;
        clear_fixed_opportunity_ownership();
        stage_nonce_ = 0;
        stage_corridor_start_ = 0;
        stage_corridor_end_ = 0;
        sealed_scene_version_ = 0;
        sealed_tile_count_ = 0;
        sealed_content_end_ = 0;
        stage_requested_ = false;
        stage_authority_ = 0;
        gpu_phase_.store(GpuPhase::PRE_STAGE_GPU, std::memory_order_release);
        sealed_resource_submit_serial_.store(0, std::memory_order_release);
        seal_barrier_serial_.store(0, std::memory_order_release);
        scene_sealed_.store(false, std::memory_order_release);
        seal_fence_completion_ns_.store(0, std::memory_order_release);
        offscreen_warm_fence_completion_ns_.store(0, std::memory_order_release);
        predecessor_physical_complete_ns_.store(0, std::memory_order_release);
        upload_context_destroyed_ns_.store(0, std::memory_order_release);
        stage_backbuffer_ready_ns_.store(0, std::memory_order_release);
        offscreen_warm_draw_count_.store(0, std::memory_order_release);
        scene_mutation_count_.store(0, std::memory_order_release);
        sealed_scene_mutation_count_.store(0, std::memory_order_release);
        stage_latch_ns_.store(0, std::memory_order_release);
        first_down_ingress_ns_.store(0, std::memory_order_release);
        ingress_pointer_down_.store(false, std::memory_order_release);
        last_ingress_event_time_ns_.store(0, std::memory_order_release);
        last_ingress_main_time_ns_.store(0, std::memory_order_release);
        if (!swap_interval_changed_.load(std::memory_order_acquire) &&
            !attach_authority_failed_.load(std::memory_order_acquire) &&
            !engine_failed_.load(std::memory_order_acquire)) {
            authority_failed_.store(false, std::memory_order_release);
        }
        if (!prepare_resource_worker_for_bind(request.successor)) {
            NTK_LOGE("fatal resource worker creation for authority=%lld generation=%lld",
                     static_cast<long long>(request.successor.authority),
                     static_cast<long long>(request.successor.authority_generation));
            gpu_resource_worker_state_.store(
                GpuResourceWorkerState::FAILED, std::memory_order_release);
            authority_failed_.store(true, std::memory_order_release);
            gpu_phase_.store(GpuPhase::FAILED, std::memory_order_release);
            return BindSceneDisposition::FAILED;
        }

        // A timeout may race EGL creation but it cannot race publication. If cancellation won,
        // retire the just-created exact successor worker before acknowledging failure.
        if (bind_cancel_requested(request.ticket)) {
            const bool worker_retired =
                gpu_resource_worker_state_.load(std::memory_order_acquire) ==
                    GpuResourceWorkerState::PRE_STAGE_ACTIVE &&
                resource_worker_owns(request.successor.authority_generation,
                                     request.successor.authority)
                ? retire_resource_worker(request.successor)
                : !upload_context_alive_.load(std::memory_order_acquire);
            if (!worker_retired) engine_failed_.store(true, std::memory_order_release);
            gpu_resource_worker_state_.store(
                GpuResourceWorkerState::FAILED, std::memory_order_release);
            authority_failed_.store(true, std::memory_order_release);
            gpu_phase_.store(GpuPhase::FAILED, std::memory_order_release);
            return BindSceneDisposition::FAILED;
        }

        std::deque<PreallocateCommand> preallocations;
        for (const TileKey& key : request.ordinal_keys) {
            const auto spec = request.slot_specs.find(key);
            if (spec == request.slot_specs.end()) {
                authority_failed_.store(true, std::memory_order_release);
                gpu_phase_.store(GpuPhase::FAILED, std::memory_order_release);
                return BindSceneDisposition::FAILED;
            }
            preallocations.push_back(spec->second);
        }

        bool cancelled_at_commit = false;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            cancelled_at_commit = stopped_ || request.ticket->cancel_requested;
            if (!cancelled_at_commit) {
                authority_ = request.successor.authority;
                authority_generation_ = request.successor.authority_generation;
                preparation_open_ = false;
                prepared_geometry_bound_ = false;
                preparation_admissions_closed_ = false;
                preparation_token_nonce_ = 0;
                preparation_opened_ns_ = 0;
                prepared_bank_ledger_ = ntk::prepared_scene::Ledger{};
                current_manifest_revision_ = request.manifest_revision;
                current_manifest_digest_ = request.manifest_digest;
                current_geometry_digest_ = request.geometry_digest;
                current_pregeometry_root_digest_ = request.pregeometry_root_digest;
                gpu_scene_admission_ = std::move(initial_gpu_scene_admission);
                slot_specs_ = std::move(request.slot_specs);
                ordinal_keys_ = std::move(request.ordinal_keys);
                key_ordinals_ = std::move(request.key_ordinals);
                expected_tile_count_ = ordinal_keys_.size();
                preallocate_commands_ = std::move(preallocations);
                content_height_ = request.content_height;
                viewport_width_ = request.viewport_width;
                viewport_height_ = request.viewport_height;
                presented_view_state_.scroll_top = std::clamp<std::int64_t>(
                    request.scroll_top, 0,
                    std::max<std::int64_t>(0, content_height_ - viewport_height_));
                presented_view_state_.velocity_px_per_second = 0.0F;
                presented_view_state_.scroll_direction = 0;
                fixed_scheduler_.reset(presented_view_state_);
                presented_visual_mutation_serial_ = 0;
                resident_intervals_.clear();
                active_authority_.store(authority_, std::memory_order_release);
                active_authority_generation_.store(
                    authority_generation_, std::memory_order_release);
                upload_submission_blocked_.store(false, std::memory_order_release);
                input_admission_blocked_.store(true, std::memory_order_release);
                presentation_blocked_.store(false, std::memory_order_release);
                bind_apply_count_.fetch_add(1, std::memory_order_acq_rel);
                bind_committed_ns_.store(monotonic_now_ns(), std::memory_order_release);
                request.ticket->success = true;
                request.ticket->accepted_authority_generation = authority_generation_;
                request.ticket->completed = true;
            }
        }

        if (cancelled_at_commit) {
            const bool worker_retired = retire_resource_worker(request.successor);
            if (!worker_retired) engine_failed_.store(true, std::memory_order_release);
            gpu_resource_worker_state_.store(
                GpuResourceWorkerState::FAILED, std::memory_order_release);
            authority_failed_.store(true, std::memory_order_release);
            gpu_phase_.store(GpuPhase::FAILED, std::memory_order_release);
            return BindSceneDisposition::FAILED;
        }
        bind_condition_.notify_all();
        upload_condition_.notify_one();
        return BindSceneDisposition::COMPLETED;
    }

    ntk::scheduler::FrameScope current_frame_scope() const noexcept {
        return {
            .surface_epoch = surface_epoch_,
            .authority_generation = authority_generation_,
            .authority = authority_,
            .scene_version = scene_version_,
        };
    }

    PresentedViewState scheduled_predecessor_view() const noexcept {
        return prepared_frame_work_.has_value()
            ? prepared_frame_work_->view_state : presented_view_state_;
    }

    void refresh_input_phase() {
        const GpuPhase current = gpu_phase_.load(std::memory_order_acquire);
        if (current != GpuPhase::INPUT_ARMED &&
            current != GpuPhase::GESTURE_ACTIVE) return;
        const auto& reducer = fixed_scheduler_.reducer();
        const bool headInput = prepared_frame_work_.has_value() &&
            prepared_frame_work_->kind != PendingFrameKind::STAGE;
        const bool demandActive = reducer.gesture_state ==
                ntk::scheduler::ReducerGestureState::ACTIVE ||
            headInput || fixed_scheduler_.successor().has_value() ||
            fixed_scheduler_.hasUnjoinedTerminalObligation();
        gpu_phase_.store(
            demandActive ? GpuPhase::GESTURE_ACTIVE : GpuPhase::INPUT_ARMED,
            std::memory_order_release);
        if (!cadence_qualification_failed_.load(std::memory_order_acquire)) {
            cadence_qualification_state_.store(
                demandActive
                    ? CadenceQualificationState::QUALIFIED_GESTURE
                    : CadenceQualificationState::QUALIFIED_IDLE,
                std::memory_order_release);
        }
    }

    bool fold_reduction(const ntk::scheduler::ReductionResult& reduction) {
        if (!reduction.frame_cause) {
            refresh_input_phase();
            return reduction.valid;
        }
        const auto& existing = fixed_scheduler_.successor();
        const std::uint64_t workGeneration = existing.has_value()
            ? existing->work_generation : next_fixed_work_generation();
        if (workGeneration == 0 || !fixed_scheduler_.foldReduction(
                reduction, current_frame_scope(), workGeneration,
                scheduled_predecessor_view())) return false;
        if (reduction.terminal) publish_terminal_progress();
        pending_work_generation_ = workGeneration;
        refresh_input_phase();
        return true;
    }

    bool latch_latest_move(std::uint64_t maximum_sequence) {
        if (fixed_scheduler_.successorTerminal()) return false;
        InputSample sample{};
        {
            std::lock_guard<std::mutex> lock(move_mailbox_mutex_);
            if (move_mailbox_sequence_ == 0 ||
                move_mailbox_sequence_ <=
                    fixed_scheduler_.reducer().applied_move_sequence ||
                move_mailbox_sequence_ > maximum_sequence) return false;
            sample = move_mailbox_;
        }
        const auto reduction = fixed_scheduler_.reduceMove(
            sample, std::max<std::int64_t>(
                0, content_height_ - viewport_height_),
            monotonic_now_ns());
        if (!reduction.valid) return false;
        if (!fold_reduction(reduction)) {
            head_frame_state_ = HeadFrameState::FAILED;
            authority_failed_.store(true, std::memory_order_release);
            gpu_phase_.store(GpuPhase::FAILED, std::memory_order_release);
            return false;
        }
        return reduction.frame_cause;
    }

    void commit_prepared_view_state(const PreparedFrameWork& prepared) {
        presented_view_state_ = prepared.view_state;
        presented_visual_mutation_serial_ =
            prepared.visual_mutation_serial;
    }

    bool queue_stage_frame() {
        const std::uint64_t generation = next_fixed_work_generation();
        if (generation == 0 || !fixed_scheduler_.queueStage(
                current_frame_scope(), generation,
                presented_view_state_)) return false;
        pending_work_generation_ = generation;
        return true;
    }

    bool pending_frame_cause_ready_for_preparation() const {
        if (prepared_frame_work_.has_value() ||
            !fixed_scheduler_.successor().has_value()) return false;
        const auto& work = *fixed_scheduler_.successor();
        if (work.kind == PendingFrameKind::STAGE) {
            return !work.terminal && work.input.input_watermark == 0;
        }
        return (work.kind == PendingFrameKind::TERMINAL) == work.terminal &&
            work.input.ordered() && work.visual_demand_epoch != 0 &&
            work.gesture_generation != 0;
    }

    bool prepare_pending_frame() {
        if (prepared_frame_work_.has_value() ||
            !pending_frame_cause_ready_for_preparation()) return false;
        const auto& queuedCandidate = *fixed_scheduler_.successor();
        if ((active_authority_generation_.load(std::memory_order_acquire) !=
                 authority_generation_ ||
             active_authority_.load(std::memory_order_acquire) != authority_) &&
            !queuedCandidate.terminal) {
            // A successor bind has closed this authority. Retain the non-admitted work until the
            // render boundary moves the old token into its release tracker.
            return false;
        }
        const auto candidate = queuedCandidate;
        if (candidate.scope.surface_epoch != surface_epoch_ ||
            candidate.scope.authority_generation != authority_generation_ ||
            candidate.scope.authority != authority_ ||
            candidate.scope.scene_version != scene_version_) {
            NTK_LOGE("fatal stale pending frame work epoch=%llu/%llu authority=%lld/%lld/%lld/%lld",
                     static_cast<unsigned long long>(candidate.scope.surface_epoch),
                     static_cast<unsigned long long>(surface_epoch_),
                     static_cast<long long>(candidate.scope.authority_generation),
                     static_cast<long long>(authority_generation_),
                     static_cast<long long>(candidate.scope.authority),
                     static_cast<long long>(authority_));
            head_frame_state_ = HeadFrameState::FAILED;
            authority_failed_.store(true, std::memory_order_release);
            gpu_phase_.store(GpuPhase::FAILED, std::memory_order_release);
            return false;
        }
        auto promoted = fixed_scheduler_.promoteSuccessor();
        if (!promoted.has_value()) return false;
        const auto& work = *promoted;
        if (work.terminal) publish_terminal_progress();
        PreparedFrameWork frozen;
        frozen.engine_generation = engine_generation_;
        frozen.surface_epoch = surface_epoch_;
        frozen.authority_generation = authority_generation_;
        frozen.authority = authority_;
        frozen.scene_version = scene_version_;
        frozen.work_generation = work.work_generation;
        frozen.input_watermark = work.input.input_watermark;
        frozen.kind = work.kind;
        frozen.terminal = work.terminal;
        frozen.terminal_input_sequence = work.terminal_input_sequence;
        frozen.view_state = work.view_state;
        frozen.visual_demand_epoch = work.visual_demand_epoch;
        frozen.visual_mutation_serial = work.visual_mutation_serial;
        frozen.visible_state_changed = work.visible_state_changed;
        frozen.gesture_generation = work.gesture_generation;
        frozen.input_oldest_ns = work.input.event_oldest_ns;
        frozen.input_newest_ns = work.input.event_newest_ns;
        frozen.main_ingress_oldest_ns = work.input.main_ingress_oldest_ns;
        frozen.main_ingress_newest_ns = work.input.main_ingress_newest_ns;
        frozen.receipt_oldest_ns = work.input.receipt_oldest_ns;
        frozen.receipt_newest_ns = work.input.receipt_newest_ns;
        frozen.mutation_oldest_ns = work.input.mutation_oldest_ns;
        frozen.mutation_newest_ns = work.input.mutation_newest_ns;
        prepared_frame_work_ = frozen;
        prepared_frame_work_->reserved_evidence_slot_sequence =
            reserve_evidence_capsule_sequence();
        if (prepared_frame_work_->reserved_evidence_slot_sequence == 0) {
            return fail_prepared_frame("evidence-slot-reservation");
        }
        head_frame_state_ = HeadFrameState::CONTENT_FROZEN;
        return prepare_authoritative_frame(*prepared_frame_work_);
    }

    bool complete_prepared_frame(
            const ntk::present::SurfaceControlPresentBackend::
                SubmissionReceipt& receipt) {
        const bool post_submit_fatal =
            head_frame_state_ == HeadFrameState::FAILED &&
            fixed_causal_lane_fatal_;
        const bool exact_submitted = prepared_frame_work_.has_value() &&
            admission_predecessor_.has_value() &&
            admission_predecessor_->work_generation ==
                prepared_frame_work_->work_generation &&
            admission_predecessor_->frame_sequence ==
                prepared_frame_work_->frame_sequence &&
            admission_predecessor_->frame_id == prepared_frame_work_->frame_id &&
            ntk::present::exactIdentity(
                admission_predecessor_->identity, receipt.identity) &&
            prepared_frame_work_->surface_submission.state ==
                ntk::present::SurfaceControlPresentBackend::
                    PreparedTransactionState::TERMINAL &&
            evidence_capsule_slot(
                prepared_frame_work_->reserved_evidence_slot_sequence) != nullptr;
        if (!prepared_frame_work_.has_value() ||
            (head_frame_state_ != HeadFrameState::PHASE_COMMITTING &&
             !post_submit_fatal) ||
            !exact_submitted) {
            head_frame_state_ = HeadFrameState::FAILED;
            engine_failed_.store(true, std::memory_order_release);
            authority_failed_.store(true, std::memory_order_release);
            gpu_phase_.store(GpuPhase::FAILED, std::memory_order_release);
            return false;
        }
        prepared_frame_work_.reset();
        fixed_scheduler_.noteHeadPresent(false);
        reserved_frame_id_ = 0;
        reserved_frame_id_work_generation_ = 0;
        if (!post_submit_fatal) {
            head_frame_state_ = HeadFrameState::EMPTY;
        }
        refresh_input_phase();
        return true;
    }

    bool apply_control(const InputSample& input) {
        const auto reduction = fixed_scheduler_.reduceControl(
            input, std::max<std::int64_t>(
                0, content_height_ - viewport_height_),
            monotonic_now_ns());
        if (!reduction.valid || !fold_reduction(reduction)) {
            NTK_LOGE("fatal render input reduction action=%d gesture=%llu sequence=%llu",
                     input.action,
                     static_cast<unsigned long long>(input.gesture_generation),
                     static_cast<unsigned long long>(input.input_sequence));
            authority_failed_.store(true, std::memory_order_release);
            gpu_phase_.store(GpuPhase::FAILED, std::memory_order_release);
            return false;
        }
        return reduction.frame_cause;
    }

    void complete_publication(const GpuReadyTile& tile,
                              std::int64_t scene_version, bool success) {
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (tile.authority_generation == authority_generation_ &&
                tile.key.authority == authority_) {
                if (native_outstanding_ > 0) --native_outstanding_;
                native_outstanding_mirror_.store(
                    static_cast<int>(native_outstanding_),
                    std::memory_order_release);
            } else {
                const AuthorityKey key{
                    engine_generation_, tile.authority_generation, tile.key.authority};
                const auto tracker = release_trackers_.find(key);
                if (tracker != release_trackers_.end() &&
                    tracker->second->outstanding_publications > 0) {
                    --tracker->second->outstanding_publications;
                }
            }
        }
        preparation_drain_condition_.notify_all();
        if (tile.prepared_protocol) {
            enqueue_prepared_tile_resident(tile, success);
        } else {
            enqueue_tile_resident(tile, scene_version, success);
        }
    }

    void queue_resource_delete(const GpuReadyTile& tile, bool delete_texture) {
        PendingResourceDelete cleanup;
        cleanup.key = tile.key;
        cleanup.authority_generation = tile.authority_generation;
        cleanup.preparation_generation = tile.preparation_generation;
        cleanup.surface_epoch = tile.surface_epoch;
        cleanup.admission_id = tile.admission_id;
        cleanup.resource_revision = tile.resource_revision;
        cleanup.install_lease = tile.install_lease;
        cleanup.rgba_bytes = tile.rgba_bytes;
        cleanup.texture = delete_texture ? tile.texture : 0;
        cleanup.fence = tile.upload_fence;
        cleanup.fence_submitted_ns = tile.fence_submitted_ns;
        cleanup.notify_freed = false;
        cleanup.detached_preparation = tile.pre_geometry;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            const AuthorityKey key{
                engine_generation_, tile.authority_generation, tile.key.authority};
            const std::int64_t resource_scope = tile.pre_geometry
                ? tile.preparation_generation : tile.surface_epoch;
            if (tile.texture != 0) registry_remove_locked(release_identity(
                tile.pre_geometry ? "gpu-ready-detached-texture" :
                    "gpu-ready-texture",
                key, resource_scope, tile.admission_id,
                tile.key.page, tile.key.slot, tile.resource_revision,
                tile.install_lease, 0, tile.rgba_bytes));
            if (tile.upload_fence != nullptr) {
                registry_remove_locked(release_identity(
                    tile.pre_geometry ? "detached-upload-fence" :
                        "upload-fence",
                    key, resource_scope, tile.admission_id,
                    tile.key.page, tile.key.slot, tile.resource_revision,
                    tile.install_lease, 0, tile.rgba_bytes));
                registry_add_locked(release_identity(
                    tile.pre_geometry ? "detached-resource-delete-fence" :
                        "resource-delete-fence",
                    key, resource_scope,
                    tile.admission_id, tile.key.page, tile.key.slot,
                    tile.resource_revision, tile.install_lease, 0,
                    tile.rgba_bytes));
            }
            if (delete_texture && tile.texture != 0) registry_add_locked(release_identity(
                tile.pre_geometry ? "detached-resource-delete-texture" :
                    "resource-delete-texture",
                key, resource_scope,
                tile.admission_id, tile.key.page, tile.key.slot,
                tile.resource_revision, tile.install_lease, 0,
                tile.rgba_bytes));
            resource_deletes_.push_back(cleanup);
            resource_delete_depth_mirror_.store(
                static_cast<int>(resource_deletes_.size()),
                std::memory_order_release);
        }
        upload_condition_.notify_one();
    }

    void publish_ready_tiles(bool& should_render) {
        std::deque<GpuReadyTile> incoming;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            incoming.swap(gpu_ready_tiles_);
            ready_tile_queue_depth_mirror_.store(
                static_cast<int>(gpu_ready_tiles_.size()),
                std::memory_order_release);
        }
        bool scene_changed = false;
        const auto reject_prepared_install = [&](const GpuReadyTile& tile) {
            if (!tile.pre_geometry) return;
            std::lock_guard<std::mutex> lock(mutex_);
            prepared_bank_ledger_.rejectInstall(ntk::prepared_scene::Install{
                {tile.key.page, tile.key.slot}, tile.admission_id,
                tile.resource_revision, tile.install_lease, tile.rgba_bytes,
                tile.width, tile.height, tile.tile_proof_digest});
        };
        while (!incoming.empty()) {
            GpuReadyTile ready = incoming.front();
            incoming.pop_front();
            if (!ready.success) {
                reject_prepared_install(ready);
                queue_resource_delete(ready, true);
                complete_publication(ready, scene_version_, false);
                if (ready.authority_generation == authority_generation_ &&
                    ready.key.authority == authority_) {
                    fail_gpu_scene_admission("GL_RGBA8 upload/fence creation");
                }
                continue;
            }
            const GLenum status = ready.upload_fence == nullptr
                ? GL_WAIT_FAILED
                : glClientWaitSync(ready.upload_fence, 0, GL_TIMEOUT_IGNORED);
            if (status != GL_ALREADY_SIGNALED &&
                status != GL_CONDITION_SATISFIED) {
                reject_prepared_install(ready);
                upload_gpu_fences_pending_.fetch_sub(1, std::memory_order_acq_rel);
                queue_resource_delete(ready, true);
                complete_publication(ready, scene_version_, false);
                if (ready.authority_generation == authority_generation_ &&
                    ready.key.authority == authority_) {
                    fail_gpu_scene_admission("GPU upload fence wait");
                }
                continue;
            }
            const bool exact_generation = ready.pre_geometry
                ? ready.preparation_generation > 0 &&
                    ready.preparation_generation ==
                        prepared_bank_ledger_.preparationGeneration()
                : ready.surface_epoch > 0 &&
                    static_cast<std::uint64_t>(ready.surface_epoch) ==
                        surface_epoch_;
            if (ready.authority_generation != authority_generation_ ||
                ready.key.authority != authority_ || !exact_generation) {
                reject_prepared_install(ready);
                upload_gpu_fences_pending_.fetch_sub(1, std::memory_order_acq_rel);
                queue_resource_delete(ready, true);
                complete_publication(ready, scene_version_, false);
                continue;
            }
            upload_gpu_fences_pending_.fetch_sub(1, std::memory_order_acq_rel);
            const std::int64_t resource_completion_ns = monotonic_now_ns();
            last_gpu_resource_completion_ns_.store(
                resource_completion_ns, std::memory_order_release);
            queue_resource_delete(ready, false);
            ready.upload_fence = nullptr;
            ready.resource_completion_ns = resource_completion_ns;
            if (ready.pre_geometry) {
                bool bank_valid = false;
                {
                    std::lock_guard<std::mutex> lock(mutex_);
                    const ntk::prepared_scene::Install install{
                        {ready.key.page, ready.key.slot}, ready.admission_id,
                        ready.resource_revision, ready.install_lease,
                        ready.rgba_bytes, ready.width, ready.height,
                        ready.tile_proof_digest};
                    bank_valid = preparation_open_ && !prepared_geometry_bound_ &&
                        prepared_bank_.find(ready.key) == prepared_bank_.end() &&
                        prepared_bank_ledger_.finishInstall(
                            install, resource_completion_ns);
                    if (bank_valid) {
                        prepared_bank_.emplace(ready.key, PreparedBankTile{
                            ready.preparation_generation, ready.admission_id,
                            ready.resource_revision, ready.install_lease,
                            ready.rgba_bytes, ready.texture, ready.width, ready.height,
                            ready.tile_proof_digest, resource_completion_ns});
                        registry_add_locked(release_identity(
                            "prepared-bank-texture",
                            AuthorityKey{engine_generation_, ready.authority_generation,
                                         ready.key.authority},
                            ready.preparation_generation, ready.admission_id,
                            ready.key.page, ready.key.slot,
                            ready.resource_revision, ready.install_lease,
                            0, ready.rgba_bytes));
                        ready.resident_inventory_digest =
                            prepared_inventory_digest_locked();
                    }
                }
                if (!bank_valid) {
                    queue_resource_delete(ready, true);
                    complete_publication(ready, scene_version_, false);
                    fail_gpu_scene_admission("prepared-bank publication mismatch");
                    continue;
                }
                complete_publication(ready, scene_version_, true);
                continue;
            }
            auto existing = scene_.find(ready.key);
            if (existing != scene_.end()) {
                NTK_LOGE("fatal texture replacement without completed retire page=%d slot=%d",
                         ready.key.page, ready.key.slot);
                queue_resource_delete(ready, true);
                complete_publication(ready, scene_version_, false);
                fail_gpu_scene_admission("duplicate resident texture publication");
                continue;
            }
            scene_[ready.key] = SceneTile{
                ready.surface_epoch, ready.admission_id, ready.resource_revision,
                ready.install_lease, ready.rgba_bytes,
                ready.texture, ready.width, ready.height,
                ready.content_top, ready.content_bottom};
            bool ledger_valid = true;
            {
                std::lock_guard<std::mutex> lock(mutex_);
                const bool completes_scene =
                    gpu_scene_admission_.resident_texture_count + 1U ==
                        gpu_scene_admission_.expected_texture_count;
                ledger_valid = gpu_scene_admission_.record_resident(
                    ready.rgba_bytes, scene_.size(),
                    completes_scene ? gpu_scene_digest_from_resident_locked() : std::string{});
                registry_add_locked(release_identity(
                    "scene-texture",
                    AuthorityKey{engine_generation_, ready.authority_generation,
                                 ready.key.authority},
                    ready.surface_epoch, ready.admission_id, ready.key.page,
                    ready.key.slot, ready.resource_revision, ready.install_lease,
                    0, ready.rgba_bytes));
                if (ready.prepared_protocol) {
                    ready.resident_inventory_digest =
                        resident_scene_inventory_digest_locked();
                    ready.resource_completion_ns =
                        last_gpu_resource_completion_ns_.load(std::memory_order_acquire);
                }
            }
            if (!ledger_valid) {
                complete_publication(ready, scene_version_, false);
                fail_gpu_scene_admission("resident inventory count/bytes/digest mismatch");
                continue;
            }
            ++scene_version_;
            scene_mutation_count_.fetch_add(1, std::memory_order_acq_rel);
            ++integrated_tiles_since_frame_;
            scene_changed = true;
            should_render = true;
            // PRE_STAGE publication means the render context has consumed the signaled shared
            // upload fence and installed the immutable texture. It is not a display-present
            // acknowledgement: swapping partial scenes here would violate the first-frame rule.
            complete_publication(ready, scene_version_, true);
        }
        if (scene_changed) rebuild_resident_intervals();
    }

    void apply_protection_commit(const ProtectionCommit& commit) {
        // Protection is policy authority, not a scene resource. Commit it at scene version zero
        // so the first admission pressure can never race an unprotected retirement decision.
        bool fresh = commit.authority == authority_ &&
            static_cast<std::uint64_t>(commit.surface_epoch) == surface_epoch_;
        if (fresh && applied_protection_.valid &&
            applied_protection_.commit.authority == commit.authority &&
            applied_protection_.commit.surface_epoch == commit.surface_epoch) {
            const auto& current = applied_protection_.commit;
            fresh = commit.demand_epoch > current.demand_epoch ||
                (commit.demand_epoch == current.demand_epoch &&
                 (commit.basis_frame_sequence > current.basis_frame_sequence ||
                  (commit.basis_frame_sequence == current.basis_frame_sequence &&
                   commit.basis_input_sequence >= current.basis_input_sequence)));
        }
        if (!fresh) {
            enqueue_protection_committed(commit, 0, false);
            return;
        }
        AppliedProtection next;
        next.valid = true;
        next.commit = commit;
        next.protected_mask.assign(expected_tile_count_, 0U);
        for (int ordinal : commit.protected_tile_ordinals) {
            if (ordinal < 0 || static_cast<std::size_t>(ordinal) >= expected_tile_count_) {
                enqueue_protection_committed(commit, 0, false);
                authority_failed_.store(true, std::memory_order_release);
                gpu_phase_.store(GpuPhase::FAILED, std::memory_order_release);
                return;
            }
            next.protected_mask[static_cast<std::size_t>(ordinal)] = 1U;
        }
        applied_protection_ = std::move(next);
        enqueue_protection_committed(commit, scene_version_, true);
    }

    void record_retire_result(const RetireIntent& intent, RetireResultCode result,
                              std::int64_t scene_version, std::int64_t fence_serial) {
        ++retire_intents_received_;
        switch (result) {
            case RetireResultCode::DETACHED: ++retire_intents_detached_; break;
            case RetireResultCode::STALE_POLICY: ++retire_intents_stale_; break;
            case RetireResultCode::PROTECTED: ++retire_intents_protected_; break;
            case RetireResultCode::VISIBLE_OR_RUNWAY: ++retire_intents_visible_; break;
            case RetireResultCode::NOT_RESIDENT: ++retire_intents_not_resident_; break;
            case RetireResultCode::FAILED: ++retire_intents_failed_; break;
        }
        const std::uint64_t conserved = retire_intents_detached_ + retire_intents_stale_ +
            retire_intents_protected_ + retire_intents_visible_ +
            retire_intents_not_resident_ + retire_intents_failed_;
        if (conserved != retire_intents_received_) {
            NTK_LOGE("fatal retire conservation received=%llu accounted=%llu",
                     static_cast<unsigned long long>(retire_intents_received_),
                     static_cast<unsigned long long>(conserved));
            authority_failed_.store(true, std::memory_order_release);
            gpu_phase_.store(GpuPhase::FAILED, std::memory_order_release);
        }
        enqueue_retire_result(intent, result, scene_version, fence_serial);
    }

    bool process_context_lost_retire(const RetireIntent& intent) {
        if (intent.authority != authority_ ||
            intent.authority_generation != authority_generation_) {
            record_retire_result(intent, RetireResultCode::STALE_POLICY, scene_version_, 0);
            return false;
        }
        const auto found = scene_.find(intent.key);
        if (found == scene_.end()) {
            record_retire_result(intent, RetireResultCode::NOT_RESIDENT, scene_version_, 0);
            return false;
        }
        if (found->second.surface_epoch != intent.surface_epoch ||
            found->second.resource_revision != intent.resource_revision ||
            found->second.install_lease != intent.install_lease ||
            found->second.rgba_bytes != intent.rgba_bytes) {
            record_retire_result(intent, RetireResultCode::STALE_POLICY, scene_version_, 0);
            return false;
        }
        PendingResourceDelete retirement;
        retirement.key = intent.key;
        retirement.authority_generation = intent.authority_generation;
        retirement.surface_epoch = intent.surface_epoch;
        retirement.policy_surface_epoch = intent.policy_surface_epoch;
        retirement.demand_epoch = intent.demand_epoch;
        retirement.admission_id = found->second.admission_id;
        retirement.resource_revision = intent.resource_revision;
        retirement.install_lease = intent.install_lease;
        retirement.retire_lease = intent.retire_lease;
        retirement.rgba_bytes = intent.rgba_bytes;
        retirement.protected_digest = intent.protected_digest;
        retirement.texture = found->second.texture;
        retirement.fence = nullptr;
        retirement.fence_submitted_ns = 0;
        retirement.notify_freed = true;
        scene_.erase(found);
        ++scene_version_;
        scene_mutation_count_.fetch_add(1, std::memory_order_acq_rel);
        rebuild_resident_intervals();
        const std::int64_t retire_serial = ++retire_fence_serial_;
        record_retire_result(
            intent, RetireResultCode::DETACHED, scene_version_, retire_serial);
        {
            std::lock_guard<std::mutex> lock(mutex_);
            const AuthorityKey key{
                engine_generation_, intent.authority_generation, intent.authority};
            registry_remove_locked(release_identity(
                "scene-texture", key, intent.surface_epoch,
                retirement.admission_id, intent.key.page, intent.key.slot,
                intent.resource_revision, intent.install_lease, 0,
                intent.rgba_bytes));
            registry_add_locked(release_identity(
                "resource-delete-texture", key, intent.surface_epoch,
                retirement.admission_id, intent.key.page, intent.key.slot,
                intent.resource_revision, intent.install_lease,
                intent.retire_lease, intent.rgba_bytes));
            resource_deletes_.push_back(std::move(retirement));
            resource_delete_depth_mirror_.store(
                static_cast<int>(resource_deletes_.size()),
                std::memory_order_release);
        }
        upload_condition_.notify_one();
        return true;
    }

    bool process_retire(const RetireIntent& intent) {
        const std::int64_t before_scene_version = scene_version_;
        const auto before_intervals = resident_intervals_;
        const auto veto = [&](RetireResultCode result) {
            if (scene_version_ != before_scene_version || resident_intervals_ != before_intervals) {
                NTK_LOGE("fatal retire veto mutated scene result=%d", static_cast<int>(result));
                authority_failed_.store(true, std::memory_order_release);
                gpu_phase_.store(GpuPhase::FAILED, std::memory_order_release);
                record_retire_result(intent, RetireResultCode::FAILED,
                                     before_scene_version, 0);
            } else {
                record_retire_result(intent, result, scene_version_, 0);
            }
            return false;
        };
        if (!applied_protection_.valid || intent.authority != authority_ ||
            intent.authority_generation != authority_generation_ ||
            static_cast<std::uint64_t>(intent.policy_surface_epoch) != surface_epoch_ ||
            applied_protection_.commit.authority != intent.authority ||
            applied_protection_.commit.authority_generation != intent.authority_generation ||
            applied_protection_.commit.surface_epoch != intent.policy_surface_epoch ||
            applied_protection_.commit.demand_epoch != intent.demand_epoch ||
            applied_protection_.commit.protected_digest != intent.protected_digest) {
            return veto(RetireResultCode::STALE_POLICY);
        }
        const auto found = scene_.find(intent.key);
        if (found == scene_.end()) return veto(RetireResultCode::NOT_RESIDENT);
        if (found->second.surface_epoch != intent.surface_epoch ||
            found->second.resource_revision != intent.resource_revision ||
            found->second.install_lease != intent.install_lease ||
            found->second.rgba_bytes != intent.rgba_bytes) {
            return veto(RetireResultCode::STALE_POLICY);
        }
        const auto ordinal = key_ordinals_.find(intent.key);
        if (ordinal == key_ordinals_.end() || ordinal->second < 0 ||
            static_cast<std::size_t>(ordinal->second) >=
                applied_protection_.protected_mask.size()) {
            authority_failed_.store(true, std::memory_order_release);
            gpu_phase_.store(GpuPhase::FAILED, std::memory_order_release);
            return veto(RetireResultCode::FAILED);
        }
        if (applied_protection_.protected_mask[static_cast<std::size_t>(ordinal->second)] != 0U) {
            return veto(RetireResultCode::PROTECTED);
        }
        const std::int64_t visible_start = presented_view_state_.scroll_top;
        const std::int64_t visible_end = std::min<std::int64_t>(
            content_height_, presented_view_state_.scroll_top + viewport_height_);
        const std::int64_t one_and_half_viewports =
            (3LL * viewport_height_ + 1LL) / 2LL;
        // SafetyNow is evaluated against the render owner's latest applied MOVE.  Protection
        // direction is demand-policy evidence and may legitimately lag an input reversal.
        const bool backward = presented_view_state_.scroll_direction < 0;
        const std::int64_t safety_start = std::max<std::int64_t>(
            0, visible_start - (backward ? one_and_half_viewports : viewport_height_));
        const std::int64_t safety_end = std::min<std::int64_t>(
            content_height_, visible_end + (backward ? viewport_height_ : one_and_half_viewports));
        const auto overlaps = [](std::int64_t tile_start, std::int64_t tile_end,
                                 std::int64_t interval_start, std::int64_t interval_end) {
            return tile_end > interval_start && tile_start < interval_end;
        };
        if (overlaps(found->second.content_top, found->second.content_bottom,
                     visible_start, visible_end) ||
            overlaps(found->second.content_top, found->second.content_bottom,
                     safety_start, safety_end)) {
            return veto(RetireResultCode::VISIBLE_OR_RUNWAY);
        }
        GLsync fence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        if (fence == nullptr) {
            authority_failed_.store(true, std::memory_order_release);
            gpu_phase_.store(GpuPhase::FAILED, std::memory_order_release);
            return veto(RetireResultCode::FAILED);
        }
        glFlush();
        PendingResourceDelete retirement;
        retirement.key = intent.key;
        retirement.authority_generation = intent.authority_generation;
        retirement.surface_epoch = intent.surface_epoch;
        retirement.policy_surface_epoch = intent.policy_surface_epoch;
        retirement.demand_epoch = intent.demand_epoch;
        retirement.admission_id = found->second.admission_id;
        retirement.resource_revision = intent.resource_revision;
        retirement.install_lease = intent.install_lease;
        retirement.retire_lease = intent.retire_lease;
        retirement.rgba_bytes = intent.rgba_bytes;
        retirement.protected_digest = intent.protected_digest;
        retirement.texture = found->second.texture;
        retirement.fence = fence;
        retirement.fence_submitted_ns = monotonic_now_ns();
        retirement.notify_freed = true;
        scene_.erase(found);
        ++scene_version_;
        scene_mutation_count_.fetch_add(1, std::memory_order_acq_rel);
        rebuild_resident_intervals();
        const std::int64_t fence_serial = ++retire_fence_serial_;
        record_retire_result(
            intent, RetireResultCode::DETACHED, scene_version_, fence_serial);
        {
            std::lock_guard<std::mutex> lock(mutex_);
            const AuthorityKey key{
                engine_generation_, intent.authority_generation, intent.authority};
            registry_remove_locked(release_identity(
                "scene-texture", key, intent.surface_epoch,
                retirement.admission_id, intent.key.page, intent.key.slot,
                intent.resource_revision, intent.install_lease, 0,
                intent.rgba_bytes));
            registry_add_locked(release_identity(
                "resource-delete-texture", key, intent.surface_epoch,
                retirement.admission_id, intent.key.page, intent.key.slot,
                intent.resource_revision, intent.install_lease,
                intent.retire_lease, intent.rgba_bytes));
            registry_add_locked(release_identity(
                "retire-fence", key, intent.surface_epoch,
                retirement.admission_id, intent.key.page, intent.key.slot,
                intent.resource_revision, intent.install_lease,
                intent.retire_lease, intent.rgba_bytes));
            resource_deletes_.push_back(std::move(retirement));
            resource_delete_depth_mirror_.store(
                static_cast<int>(resource_deletes_.size()),
                std::memory_order_release);
        }
        upload_condition_.notify_one();
        return true;
    }

    void rebuild_resident_intervals() {
        resident_intervals_.clear();
        resident_intervals_.reserve(scene_.size());
        for (const auto& entry : scene_) {
            resident_intervals_.emplace_back(
                entry.second.content_top, entry.second.content_bottom);
        }
        std::sort(resident_intervals_.begin(), resident_intervals_.end());
    }

    std::int64_t resident_continuous_end() const {
        std::int64_t end = presented_view_state_.scroll_top;
        for (const auto& interval : resident_intervals_) {
            if (interval.second <= end) continue;
            if (interval.first > end) break;
            end = std::max(end, interval.second);
        }
        return end;
    }

    std::int64_t resident_continuous_start() const {
        std::int64_t start = presented_view_state_.scroll_top;
        for (auto iterator = resident_intervals_.rbegin();
             iterator != resident_intervals_.rend(); ++iterator) {
            if (iterator->first >= start) continue;
            if (iterator->second < start) break;
            start = std::min(start, iterator->first);
        }
        return start;
    }

    bool resident_contains(std::int64_t start, std::int64_t end) const {
        if (start < 0 || end <= start) return false;
        std::int64_t cursor = start;
        for (const auto& interval : resident_intervals_) {
            if (interval.second <= cursor) continue;
            if (interval.first > cursor) return false;
            cursor = std::max(cursor, interval.second);
            if (cursor >= end) return true;
        }
        return false;
    }

    std::int64_t first_resident_gap(std::int64_t start, std::int64_t end) const {
        if (start < 0 || end <= start) return start;
        std::int64_t cursor = start;
        for (const auto& interval : resident_intervals_) {
            if (interval.second <= cursor) continue;
            if (interval.first > cursor) return cursor;
            cursor = std::max(cursor, interval.second);
            if (cursor >= end) return -1;
        }
        return cursor < end ? cursor : -1;
    }

    bool fail_prepared_frame(const char* where) {
        const std::uint64_t work_generation = prepared_frame_work_.has_value()
            ? prepared_frame_work_->work_generation : 0;
        NTK_LOGE("fatal prepared-frame failure where=%s state=%d admission=%llu work=%llu",
                 where, static_cast<int>(head_frame_state_),
                 static_cast<unsigned long long>(
                     prepared_frame_work_.has_value()
                         ? prepared_frame_work_->admission_sequence : 0),
                 static_cast<unsigned long long>(work_generation));
        // PHASE_COMMITTING is irreversible ownership: never abort its external
        // claim. Every earlier state has one exact owner and one matching abort.
        if (prepared_frame_work_.has_value()) {
            const auto abortActions =
                ntk::scheduler::abortOwnershipActions(head_frame_state_);
            if (abortActions.abort_swappy_reservation && work_generation != 0) {
                (void)SwappyGL_abortFixedReservationForNtk(work_generation);
            }
            if (abortActions.abort_external_claim &&
                prepared_frame_work_->external_claim.claimToken != 0) {
                (void)SwappyGL_abortExternalFixedClaimForNtk(
                    prepared_frame_work_->external_claim.claimToken);
            }
            if (abortActions.abort_backend_transaction) {
                (void)present_backend_.abortPreparedBufferTransaction(
                    prepared_frame_work_->surface_submission);
            } else if (abortActions.abort_render_target) {
                (void)present_backend_.abortRenderTargetBeforePreparation(
                    prepared_frame_work_->buffer_slot,
                    prepared_frame_work_->buffer_generation);
            }
        }
        if (!fixed_opportunity_gate_.attemptInFlight()) {
            clear_fixed_opportunity_ownership();
        }
        const std::uint64_t lost =
            fixed_scheduler_.markUnsubmittedTerminalsLost();
        if (lost != 0) publish_terminal_progress();
        fixed_scheduler_.discardProducerWork();
        reserved_frame_id_ = 0;
        reserved_frame_id_work_generation_ = 0;
        head_frame_state_ = HeadFrameState::FAILED;
        block_input_and_presentation();
        engine_failed_.store(true, std::memory_order_release);
        authority_failed_.store(true, std::memory_order_release);
        gpu_phase_.store(GpuPhase::FAILED, std::memory_order_release);
        return false;
    }

    void enter_post_submit_fatal(
            std::int32_t exact_reason,
            const ntk::present::SurfaceControlPresentBackend::
                SubmissionReceipt& surface_receipt,
            const SwappyFixedExternalSubmissionReceipt& swappy_receipt)
            noexcept {
        (void)surface_receipt;
        (void)swappy_receipt;
        const auto transition =
            ntk::present::makeRendererPostSubmitFatalTransition(exact_reason);
        // Apply is irreversible. Close both admission gates before logging or
        // evidence work, then keep only the physical-drain lane alive.
        if (transition.inputClosed || transition.presentationClosed) {
            block_input_and_presentation();
        }
        engine_failed_.store(transition.engineFailed,
                             std::memory_order_release);
        authority_failed_.store(transition.authorityFailed,
                                std::memory_order_release);
        if (transition.gpuFailed) {
            gpu_phase_.store(GpuPhase::FAILED, std::memory_order_release);
        }
        if (transition.producerFailed) {
            head_frame_state_ = HeadFrameState::FAILED;
        }
        // The physically applied head was transitioned to SUBMITTED at the
        // apply cut. Only later accepted/queued/prepared obligations are LOST.
        const std::uint64_t lost =
            fixed_scheduler_.markUnsubmittedTerminalsLost();
        if (lost != 0) publish_terminal_progress();
        (void)transition.submittedDraining;
        fixed_causal_lane_fatal_ = transition.stickyFatal;
        NTK_LOGE("fatal post-submit authority reason=%d",
                 transition.exactReason);
    }

    bool prepare_authoritative_frame(PreparedFrameWork& prepared) {
        if (presentation_blocked_.load(std::memory_order_acquire)) {
            return fail_prepared_frame("presentation-closed");
        }
        if (!prepared_frame_work_.has_value() ||
            head_frame_state_ != HeadFrameState::CONTENT_FROZEN ||
            prepared.work_generation == 0 ||
            prepared_frame_work_->work_generation != prepared.work_generation ||
            prepared.engine_generation != engine_generation_ ||
            prepared.surface_epoch != surface_epoch_ ||
            prepared.authority_generation != authority_generation_ ||
            prepared.authority != authority_ ||
            prepared.scene_version != scene_version_) {
            return fail_prepared_frame("identity-or-state");
        }
        prepared.stage_candidate = stage_requested_ && stage_authority_ == authority_ &&
            stage_nonce_ > 0 && scene_sealed_.load(std::memory_order_acquire) &&
            stage_backbuffer_ready_ns_.load(std::memory_order_acquire) > 0;
        if (!scene_sealed_.load(std::memory_order_acquire) ||
            sealed_scene_version_ != scene_version_ ||
            sealed_resource_submit_serial_.load(std::memory_order_acquire) !=
                resource_submit_serial_.load(std::memory_order_acquire) ||
            sealed_draw_index_.scene_version != scene_version_) {
            NTK_LOGE("fatal authoritative draw after scene mutation stage=%d scene=%lld/%lld",
                     prepared.stage_candidate ? 1 : 0,
                     static_cast<long long>(scene_version_),
                     static_cast<long long>(sealed_scene_version_));
            return fail_prepared_frame("sealed-scene-mutated");
        }
        const std::int64_t frame_scroll_top = prepared.view_state.scroll_top;
        const float frame_velocity = prepared.view_state.velocity_px_per_second;
        prepared.frame_sequence = ++render_frame_sequence_;
        const bool pacing_invariant = swappy_ready_ && SwappyGL_isEnabled() &&
            SwappyGL_isFixedNonPipelineModeForNtk() &&
            SwappyGL_getPipelineModeForNtk() == 0 &&
            SwappyGL_isBlockingWaitEnabledForNtk() &&
            !SwappyGL_hasFatalPacingErrorForNtk() &&
            std::llabs(static_cast<std::int64_t>(
                SwappyGL_getSwapIntervalNS()) -
                static_cast<std::int64_t>(fixed_period_ns_)) <=
                static_cast<std::int64_t>(kRefreshPeriodToleranceNs) &&
            std::llabs(static_cast<std::int64_t>(
                SwappyGL_getRefreshPeriodNanos()) -
                static_cast<std::int64_t>(fixed_period_ns_)) <=
                static_cast<std::int64_t>(kRefreshPeriodToleranceNs) &&
            present_backend_attached_ &&
            !swap_interval_changed_.load(std::memory_order_acquire) &&
            !presentation_blocked_.load(std::memory_order_acquire) &&
            !authority_failed_.load(std::memory_order_acquire);
        if (!pacing_invariant) {
            return fail_prepared_frame("fixed-pacing-invariant");
        }
        SwappyFixedReservationReceipt reservation{};
        if (!SwappyGL_reserveFixedFrameForNtk(
                prepared.work_generation, &reservation) ||
            reservation.structSize != sizeof(reservation) ||
            reservation.version != SWAPPY_FIXED_RESERVATION_RECEIPT_VERSION ||
            reservation.workGeneration != prepared.work_generation ||
            reservation.reservationSequence == 0 ||
            reservation.reservationNanos <= 0) {
            return fail_prepared_frame("common-frame-reservation");
        }
        prepared.common_reservation_ns = reservation.reservationNanos;
        if (admission_predecessor_.has_value() &&
            admission_predecessor_->post_apply_nanos > 0 &&
            prepared.common_reservation_ns >=
                admission_predecessor_->post_apply_nanos) {
            prepared.prior_post_swap_to_reservation_ns =
                prepared.common_reservation_ns -
                    admission_predecessor_->post_apply_nanos;
        }
        prepared.raw_baseline_sequence = reservation.rawBaselineSequence;
        prepared.swappy_reservation_sequence =
            reservation.reservationSequence;
        if (!fixed_opportunity_gate_.arm(
                prepared.work_generation,
                prepared.swappy_reservation_sequence)) {
            (void)SwappyGL_abortFixedReservationForNtk(
                prepared.work_generation);
            return fail_prepared_frame("opportunity-gate-arm");
        }
        armed_fixed_work_generation_.store(
            prepared.work_generation, std::memory_order_release);
        armed_fixed_reservation_sequence_.store(
            prepared.swappy_reservation_sequence,
            std::memory_order_release);
        head_frame_state_ = HeadFrameState::SWAPPY_RESERVED_PREPARING;
        fixed_scheduler_.noteSwappyReservationDepth(1);
        prepared.draw_begin_ns = monotonic_now_ns();
        trace_pre_swap_ns_.store(0, std::memory_order_release);
        trace_post_swap_ns_.store(0, std::memory_order_release);
        auto* render_target = present_backend_.acquireRenderTarget();
        if (render_target == nullptr ||
            !present_backend_.bindRenderTarget(*render_target)) {
            return fail_prepared_frame("hardware-buffer-target");
        }
        prepared.buffer_slot = render_target->slot;
        prepared.buffer_generation = render_target->generation;
        head_frame_state_ = HeadFrameState::GPU_TARGET_OWNED;
        if (draw_sealed_scene_to_current_framebuffer(frame_scroll_top) <= 0) {
            return fail_prepared_frame("sealed-draw");
        }
        prepared.draw_issue_end_ns = monotonic_now_ns();
        if (!present_backend_.exportAcquireFence(
                *render_target, prepared.draw_begin_ns,
                prepared.draw_issue_end_ns, &prepared.gpu_ready_proof)) {
            return fail_prepared_frame("gpu-ready-before-transaction");
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        prepared.draw_issued = true;
        ++prepared_draw_issuance_count_;
        head_frame_state_ = HeadFrameState::DRAW_ISSUED;
        // The seal already proved exact [0, contentHeight) residency and no later resource
        // mutation is legal. Active admission must not spend its 12 ms budget rescanning the
        // episode-wide interval set; completeness is an immutable sealed-scene constant.
        prepared.continuous_start = 0;
        prepared.continuous_end = content_height_;
        prepared.visible_start = frame_scroll_top;
        prepared.visible_end = std::min<std::int64_t>(
            content_height_, frame_scroll_top + viewport_height_);
        prepared.visible_gap = -1;
        prepared.viewport_complete = true;
        prepared.runway_complete = true;
        // A missing original pixel is a contract failure, not a black frame that may be queued
        // and counted after the damage is already visible.  GLES commands above only touched the
        // back buffer; fail the authority before Swappy/eglSwapBuffers can submit it.
        if (!prepared.viewport_complete) {
            const std::int64_t gap_count = static_cast<std::int64_t>(
                pre_submit_viewport_gap_.fetch_add(1, std::memory_order_acq_rel) + 1);
            NTK_LOGE("fatal pre-submit viewport gap authority=%lld frame=%llu gap=%lld "
                     "visible=[%lld,%lld) count=%lld",
                     static_cast<long long>(authority_),
                     static_cast<unsigned long long>(prepared.frame_sequence),
                     static_cast<long long>(prepared.visible_gap),
                     static_cast<long long>(prepared.visible_start),
                     static_cast<long long>(prepared.visible_end),
                     static_cast<long long>(gap_count));
            enqueue_pre_submit_viewport_gap(
                authority_generation_, authority_,
                static_cast<std::int64_t>(surface_epoch_), gap_count);
            std::vector<PendingPublishAck> failed;
            failed.swap(pending_publish_acks_);
            pending_publish_ack_mirror_.store(
                static_cast<int>(pending_publish_acks_.size()),
                std::memory_order_release);
            for (const auto& ack : failed) {
                GpuReadyTile tile;
                tile.key = ack.key;
                tile.authority_generation = ack.authority_generation;
                tile.surface_epoch = ack.surface_epoch;
                tile.admission_id = ack.admission_id;
                tile.resource_revision = ack.resource_revision;
                tile.install_lease = ack.install_lease;
                tile.rgba_bytes = ack.rgba_bytes;
                complete_publication(tile, ack.scene_version, false);
            }
            return fail_prepared_frame("sealed-viewport-proof");
        }
        int first_visible_page = std::numeric_limits<int>::max();
        int last_visible_page = -1;
        const auto visible_begin = std::lower_bound(
            sealed_draw_index_.by_content_top.begin(),
            sealed_draw_index_.by_content_top.end(), prepared.visible_start,
            [](const SealedDrawTile& tile, std::int64_t value) {
                return tile.content_bottom <= value;
            });
        const auto visible_limit = std::lower_bound(
            visible_begin, sealed_draw_index_.by_content_top.end(), prepared.visible_end,
            [](const SealedDrawTile& tile, std::int64_t value) {
                return tile.content_top < value;
            });
        for (auto iterator = visible_begin; iterator != visible_limit; ++iterator) {
            first_visible_page = std::min(first_visible_page, iterator->page);
            last_visible_page = std::max(last_visible_page, iterator->page);
        }
        if (first_visible_page == std::numeric_limits<int>::max()) first_visible_page = -1;
        const std::int64_t maximum_scroll = std::max<std::int64_t>(
            0, content_height_ - viewport_height_);
        prepared.first_visible_page = first_visible_page;
        prepared.last_visible_page = last_visible_page;
        prepared.predicted_stop = std::clamp<std::int64_t>(
            frame_scroll_top + static_cast<std::int64_t>(std::llround(
                static_cast<double>(frame_velocity) * 0.25)),
            0, maximum_scroll);
        prepared.frame_id_reservation_begin_ns = monotonic_now_ns();
        const std::uint64_t frame_id =
            g_ntk_frame_id.fetch_add(1, std::memory_order_acq_rel) + 1;
        prepared.frame_id_reserved_ns = monotonic_now_ns();
        if (frame_id == 0) return fail_prepared_frame("ntk-frame-id-overflow");
        prepared.frame_id_count_before_submission =
            static_cast<std::uint64_t>(epoch_frame_ids_.size());
        if (epoch_frame_ids_.find(frame_id) != epoch_frame_ids_.end()) {
            ++duplicate_frame_id_count_;
            NTK_LOGE("fatal duplicate frame id before fixed submission frameId=%llu",
                     static_cast<unsigned long long>(frame_id));
            return fail_prepared_frame("duplicate-frame-id");
        }
        prepared.frame_id = frame_id;
        prepared.frame_id_reserved = true;
        ++prepared_frame_id_reservation_count_;
        window_frame_id_count_.fetch_add(1, std::memory_order_acq_rel);
        reserved_frame_id_ = frame_id;
        reserved_frame_id_work_generation_ = prepared.work_generation;
        head_frame_state_ = HeadFrameState::FRAME_ID_RESERVED;
        ntk::present::SurfaceControlPresentBackend::
            FixedPreparedFrameIdentityBase baseIdentity{};
        baseIdentity.engineGeneration = static_cast<std::uint64_t>(
            prepared.engine_generation);
        baseIdentity.surfaceEpoch = prepared.surface_epoch;
        baseIdentity.authorityGeneration = prepared.authority_generation;
        baseIdentity.authority = prepared.authority;
        baseIdentity.workGeneration = prepared.work_generation;
        baseIdentity.ntkFrameId = prepared.frame_id;
        baseIdentity.frameSequence = prepared.frame_sequence;
        baseIdentity.capsuleSequence =
            prepared.reserved_evidence_slot_sequence;
        if (!present_backend_.prepareBufferTransaction(
                baseIdentity, *render_target, prepared.stage_candidate,
                fixed_transport_profile_, &prepared.surface_submission,
                &prepared.transport_ready)) {
            return fail_prepared_frame("surface-transaction-prepare");
        }
        if (first_backend_prepare_ns_.load(std::memory_order_acquire) == 0) {
            first_backend_prepare_ns_.store(
                monotonic_now_ns(), std::memory_order_release);
        }
        ++backend_present_prepare_count_;
        head_frame_state_ = HeadFrameState::BACKEND_READY_UNRESERVED;
        fixed_scheduler_.noteBackendPreparedDepth(1);
        prepared.backend_ready_ns = monotonic_now_ns();
        if (!ntk::present::validGpuSubmissionProof(
                prepared.gpu_ready_proof) ||
            prepared.gpu_ready_proof.bufferSlot != prepared.buffer_slot ||
            prepared.gpu_ready_proof.bufferGeneration !=
                prepared.buffer_generation ||
            prepared.draw_issue_end_ns < prepared.draw_begin_ns ||
            prepared.frame_id_reservation_begin_ns <
                prepared.gpu_ready_proof.acquireFenceExportReturnNanos ||
            prepared.frame_id_reserved_ns <
                prepared.frame_id_reservation_begin_ns) {
            return fail_prepared_frame("backend-completion-proof-missing");
        }
        if (!ntk::scheduler::reservationPrecedesGpuWork(
                prepared.common_reservation_ns,
                prepared.draw_begin_ns, 1)) {
            return fail_prepared_frame(
                "swappy-reservation-after-gpu-work");
        }
        // This state publication may synchronously dispatch a carried exact
        // JOIN_OPEN, so the renderer gate is armed before entering it.
        head_frame_state_ = HeadFrameState::SWAPPY_RESERVED;
        if (!SwappyGL_markReservedExternalGpuReadyForNtk(
                prepared.work_generation, &prepared.transport_ready)) {
            return fail_prepared_frame("common-external-gpu-ready");
        }
        return true;
    }

    PreparedCommitResult finish_external_submitted_frame(
            PreparedFrameWork admitted,
            const ntk::present::SurfaceControlPresentBackend::SubmissionReceipt&
                surfaceReceipt,
            const SwappyFixedExternalSubmissionReceipt& swappyReceipt,
            bool swappy_committed) {
        const bool terminalSubmittedExact = !admitted.terminal ||
            admitted.terminal_obligation_submitted;
        const auto postApplyConservation =
            present_backend_.conservationSnapshot();
        const bool postApplyExternalClaimPresent =
            SwappyGL_hasExternalFixedClaimForNtk();
        std::uint64_t postApplyFailureMask = 0;
        const bool postApplyCutExact = validate_post_apply_cut(
            admitted, surfaceReceipt, postApplyConservation,
            postApplyExternalClaimPresent, &postApplyFailureMask);
        const bool postApplyConservationExact =
            swappy_committed && postApplyCutExact;
        SubmittedEvidenceCapsule capsule{};
        capsule.capsuleSequence = admitted.reserved_evidence_slot_sequence;
        capsule.workGeneration = admitted.work_generation;
        capsule.frameSequence = admitted.frame_sequence;
        capsule.frameId = admitted.frame_id;
        capsule.admissionSequence = admitted.admission_sequence;
        capsule.prepared = admitted;
        capsule.phase = swappyReceipt.phase;
        capsule.exactPhaseTelemetry = swappyReceipt.structSize ==
                sizeof(SwappyFixedExternalSubmissionReceipt) &&
            swappyReceipt.version == SWAPPY_FIXED_EXTERNAL_RECEIPT_VERSION &&
            swappyReceipt.claim.claimToken == admitted.external_claim.claimToken &&
            swappyReceipt.submission.ntkFrameId == admitted.frame_id &&
            swappyReceipt.retirementSequence != 0;
        capsule.qualificationSensitive = true;
        capsule.feedbackDeadlineNanos = 0;
        capsule.postSwapCriticalBeginNanos =
            surfaceReceipt.transactionApplyBeginNanos;
        capsule.postSwapCriticalEndNanos =
            surfaceReceipt.transactionApplyEndNanos;
        capsule.identity = surfaceReceipt.identity;
        capsule.gpuReadyProof = admitted.gpu_ready_proof;
        capsule.transactionApplyBeginNanos =
            surfaceReceipt.transactionApplyBeginNanos;
        capsule.transactionApplyEndNanos =
            surfaceReceipt.transactionApplyEndNanos;
        capsule.setBufferCount = surfaceReceipt.setBufferCount;
        capsule.transactionApplyCount = surfaceReceipt.transactionApplyCount;
        capsule.previousBufferExpected =
            ntk::present::validAppliedBufferRef(
                surfaceReceipt.previousAppliedBufferRef);
        capsule.previousBufferSlot =
            capsule.previousBufferExpected
                ? surfaceReceipt.previousAppliedBufferRef.identity.bufferSlot
                : 0;
        capsule.previousBufferGeneration =
            capsule.previousBufferExpected
                ? surfaceReceipt.previousAppliedBufferRef.identity
                      .bufferGeneration
                : 0;
        capsule.previousAppliedBufferRef =
            surfaceReceipt.previousAppliedBufferRef;
        capsule.appliedBufferRef = surfaceReceipt.appliedBufferRef;
        // This cut is taken immediately after a successful apply and before
        // the current frame's target retirement. Swappy non-pipeline mode
        // owns exactly this one target-unretired generation.
        capsule.targetUnretiredNow = 1;
        capsule.targetUnretiredMax = 1;
        capsule.preparedProducerNow =
            postApplyConservation.preparedTransactionState ==
                    ntk::present::SurfaceControlPresentBackend::
                        PreparedTransactionState::EMPTY
                ? 0U
                : 1U;
        capsule.preparedProducerMax = static_cast<std::uint32_t>(
            fixed_scheduler_.counters().max_backend_prepared_depth);
        capsule.applyBeforeAcquireSignalProven =
            surfaceReceipt.applyBeforeAcquireSignalProven;
        capsule.postApplyConservation = postApplyConservation;
        capsule.postApplyConservationExact =
            postApplyConservationExact;
        capsule.postApplyExternalClaimPresent =
            postApplyExternalClaimPresent;
        capsule.postApplyFatalBranch = postApplyConservationExact
            ? RendererPostApplyFatalBranch::NONE
            : RendererPostApplyFatalBranch::POST_APPLY_CUT_INVALID;
        capsule.gpu.stageNonce = stage_nonce_;
        capsule.gpu.stageCorridorStart = stage_corridor_start_;
        capsule.gpu.stageCorridorEnd = stage_corridor_end_;
        capsule.gpu.controlBacklogMax =
            control_backlog_max_.load(std::memory_order_acquire);
        capsule.gpu.moveMailboxWrites =
            move_mailbox_writes_.load(std::memory_order_acquire);
        capsule.gpu.integratedTiles = integrated_tiles_since_frame_;
        capsule.gpu.uploadCommandsSubmitting =
            upload_commands_submitting_.load(std::memory_order_acquire);
        capsule.gpu.uploadGpuFencesPending =
            upload_gpu_fences_pending_.load(std::memory_order_acquire);
        capsule.gpu.gpuPhase = static_cast<int>(
            gpu_phase_.load(std::memory_order_acquire));
        capsule.gpu.sealedScene =
            scene_sealed_.load(std::memory_order_acquire);
        capsule.gpu.sealedSceneVersion = sealed_scene_version_;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            capsule.gpu.sceneFormat =
                static_cast<int>(gpu_scene_admission_.format);
            capsule.gpu.expectedTextureCount = static_cast<int>(
                gpu_scene_admission_.expected_texture_count);
            capsule.gpu.residentTextureCount = static_cast<int>(
                gpu_scene_admission_.resident_texture_count);
            capsule.gpu.expectedLogicalBytes =
                gpu_scene_admission_.expected_logical_bytes;
            capsule.gpu.residentLogicalBytes =
                gpu_scene_admission_.resident_logical_bytes;
            capsule.gpu.sceneDigest = gpu_scene_admission_.resident_digest;
        }
        capsule.gpu.resourceSubmitSerial =
            resource_submit_serial_.load(std::memory_order_acquire);
        capsule.gpu.sealedResourceSubmitSerial =
            sealed_resource_submit_serial_.load(std::memory_order_acquire);
        capsule.gpu.readyTileQueueDepth =
            ready_tile_queue_depth_mirror_.load(std::memory_order_acquire);
        capsule.gpu.nativePublicationsOutstanding =
            native_outstanding_mirror_.load(std::memory_order_acquire);
        capsule.gpu.pendingPublishAcks =
            pending_publish_ack_mirror_.load(std::memory_order_acquire);
        capsule.gpu.retireQueueDepth =
            retire_intent_depth_mirror_.load(std::memory_order_acquire);
        capsule.gpu.retirementCount =
            resource_delete_depth_mirror_.load(std::memory_order_acquire);
        capsule.gpu.uploadContextAlive =
            upload_context_alive_.load(std::memory_order_acquire);
        capsule.gpu.lastGpuResourceCompletionNanos =
            last_gpu_resource_completion_ns_.load(std::memory_order_acquire);
        capsule.gpu.sealFenceCompletionNanos =
            seal_fence_completion_ns_.load(std::memory_order_acquire);
        capsule.gpu.uploadContextDestroyedNanos =
            upload_context_destroyed_ns_.load(std::memory_order_acquire);
        capsule.gpu.stageLatchNanos =
            stage_latch_ns_.load(std::memory_order_acquire);
        capsule.gpu.firstDownIngressNanos =
            first_down_ingress_ns_.load(std::memory_order_acquire);
        capsule.gpu.resourceWorkerState = static_cast<int>(
            gpu_resource_worker_state_.load(std::memory_order_acquire));
        capsule.gpu.resourceWorkerGeneration =
            resource_worker_generation_.load(std::memory_order_acquire);
        capsule.gpu.resourceWorkerCreateCount =
            resource_worker_create_count_.load(std::memory_order_acquire);
        capsule.gpu.resourceWorkerDestroyCount =
            resource_worker_destroy_count_.load(std::memory_order_acquire);
        capsule.gpu.activeResourceWorkerCount =
            active_resource_worker_count_.load(std::memory_order_acquire);
        capsule.gpu.activeUploadContextCount =
            capsule.gpu.uploadContextAlive ? 1 : 0;
        const std::uint64_t currentSceneMutationCount =
            scene_mutation_count_.load(std::memory_order_acquire);
        const std::uint64_t sealedSceneMutationCount =
            sealed_scene_mutation_count_.load(std::memory_order_acquire);
        capsule.gpu.sceneMutationCountSinceSeal =
            currentSceneMutationCount >= sealedSceneMutationCount
                ? currentSceneMutationCount - sealedSceneMutationCount
                : std::numeric_limits<std::uint64_t>::max();
        capsule.gpu.offscreenWarmFenceCompletionNanos =
            offscreen_warm_fence_completion_ns_.load(std::memory_order_acquire);
        capsule.gpu.predecessorPhysicalCompleteNanos =
            predecessor_physical_complete_ns_.load(std::memory_order_acquire);
        capsule.gpu.sealBarrierSerial =
            seal_barrier_serial_.load(std::memory_order_acquire);
        capsule.gpu.stageBackbufferReadyNanos =
            stage_backbuffer_ready_ns_.load(std::memory_order_acquire);
        capsule.gpu.offscreenWarmDrawCount =
            offscreen_warm_draw_count_.load(std::memory_order_acquire);
        capsule.gpu.plannerInvocationCount = 1;
        capsule.gpu.preparedDrawCount = prepared_draw_issuance_count_;
        capsule.gpu.backendPresentPrepareCount =
            backend_present_prepare_count_;
        capsule.gpu.swapAttemptCount = swap_attempt_count_;
        capsule.gpu.slotClosedNoAttemptCount = slot_closed_no_attempt_count_;
        capsule.gpu.terminalSwapCount = terminal_swap_count_ +
            (admitted.terminal ? 1U : 0U);
        capsule.gpu.preparedFrameIdReservationCount =
            prepared_frame_id_reservation_count_;
        capsule.gpu.swapIntervalNanos = fixed_period_ns_;
        capsule.gpu.latchTimestampSupported = true;
        capsule.gpu.presentationTimestampSupported = true;

        const bool capsuleCommitted = commit_evidence_capsule(&capsule);
        if (capsuleCommitted && capsule.exactPhaseTelemetry &&
            capsule.postApplyConservationExact) {
            commit_prepared_view_state(admitted);
        }
        AdmissionPredecessor submitted{};
        submitted.work_generation = admitted.work_generation;
        submitted.frame_sequence = admitted.frame_sequence;
        submitted.frame_id = admitted.frame_id;
        submitted.admission_sequence = admitted.admission_sequence;
        submitted.post_apply_nanos = surfaceReceipt.transactionApplyEndNanos;
        submitted.identity = surfaceReceipt.identity;
        admission_predecessor_ = submitted;
        const bool preparedCompleted = complete_prepared_frame(surfaceReceipt);
        const auto publishPostSubmitSnapshot =
            [&](RendererPostApplyFatalBranch branch) {
                EvidenceCapsuleSlot* slot = evidence_capsule_slot(
                    admitted.reserved_evidence_slot_sequence);
                if (slot == nullptr) return;
                const std::uint64_t proofs =
                    latched_proof_count_ + terminal_lost_proof_count_;
                slot->capsule.postSubmitSuccessfulCount =
                    successful_swap_count_;
                slot->capsule.postSubmitLatchedProofCount =
                    latched_proof_count_;
                slot->capsule.postSubmitTerminalLostProofCount =
                    terminal_lost_proof_count_;
                slot->capsule.postSubmitLogicalUnlatchedNow =
                    successful_swap_count_ >= proofs
                        ? successful_swap_count_ - proofs
                        : std::numeric_limits<std::uint64_t>::max();
                slot->capsule.postSubmitMaxLogicalUnlatched =
                    max_logical_unlatched_submissions_;
                slot->capsule.postApplyFatalBranch = branch;
            };
        if (!terminalSubmittedExact || !capsule.exactPhaseTelemetry ||
            !capsule.postApplyConservationExact ||
            !capsuleCommitted || !preparedCompleted) {
            publishPostSubmitSnapshot(
                RendererPostApplyFatalBranch::POST_APPLY_CUT_INVALID);
            NTK_LOGE(
                "post-submit conservation detail mask=0x%llx swappy=%d "
                "terminal=%d phase=%d cut=%d capsule=%d prepared=%d "
                "out=%u/%u callbacks=%u/%u acquire=%u/%u watches=%u+%u "
                "held=%u free=%u backpressure=%llu/%llu fatal=%llu "
                "preparedState=%d claim=%d",
                static_cast<unsigned long long>(postApplyFailureMask),
                swappy_committed ? 1 : 0,
                terminalSubmittedExact ? 1 : 0,
                capsule.exactPhaseTelemetry ? 1 : 0,
                postApplyCutExact ? 1 : 0,
                capsuleCommitted ? 1 : 0,
                preparedCompleted ? 1 : 0,
                postApplyConservation.outstandingSubmissionCount,
                postApplyConservation.maxOutstandingSubmissionCount,
                postApplyConservation.callbackRecordDepth,
                postApplyConservation.maxCallbackRecordDepth,
                postApplyConservation.acquireFenceRecordDepth,
                postApplyConservation.appOwnedAcquireFdCount,
                postApplyConservation.pendingFenceWatchCount,
                postApplyConservation.activeFenceWatchCount,
                postApplyConservation.heldFrameworkRefCount,
                postApplyConservation.freeReusableCount,
                static_cast<unsigned long long>(
                    postApplyConservation.backpressureEnableCount),
                static_cast<unsigned long long>(
                    postApplyConservation.backpressureDisableCount),
                static_cast<unsigned long long>(
                    postApplyConservation.backendInvariantFatalCount),
                static_cast<int>(
                    postApplyConservation.preparedTransactionState),
                postApplyExternalClaimPresent ? 1 : 0);
            NTK_LOGE(
                "post-submit pool states=%d,%d,%d,%d,%d,%d,%d,%d "
                "release=%u/%u proof=%u/%u maxProof=%u/%u capacity=%llu/%llu "
                "retained=%llu teardown=%llu swaps=%llu",
                static_cast<int>(postApplyConservation.poolStates[0]),
                static_cast<int>(postApplyConservation.poolStates[1]),
                static_cast<int>(postApplyConservation.poolStates[2]),
                static_cast<int>(postApplyConservation.poolStates[3]),
                static_cast<int>(postApplyConservation.poolStates[4]),
                static_cast<int>(postApplyConservation.poolStates[5]),
                static_cast<int>(postApplyConservation.poolStates[6]),
                static_cast<int>(postApplyConservation.poolStates[7]),
                postApplyConservation.releaseWaitCount,
                postApplyConservation.previousReleaseRecordDepth,
                postApplyConservation.commitProofPendingNow,
                postApplyConservation.completeProofPendingNow,
                postApplyConservation.maxCommitProofPending,
                postApplyConservation.maxCompleteProofPending,
                static_cast<unsigned long long>(
                    postApplyConservation.capacityExhaustedCount),
                static_cast<unsigned long long>(
                    postApplyConservation.capacityWaitCount),
                static_cast<unsigned long long>(
                    postApplyConservation.retainedWaitingOnCompleteCount),
                static_cast<unsigned long long>(
                    postApplyConservation.teardownReleaseEventSequence),
                static_cast<unsigned long long>(swap_attempt_count_));
            enter_post_submit_fatal(
                kFixedFatalConservationFailure,
                surfaceReceipt, swappyReceipt);
            return PreparedCommitResult::SUBMITTED_FATAL_AFTER_EGL_TRUE;
        }
        if (admitted.input_newest_ns > 0) {
            std::int64_t observed = latest_successful_swap_input_event_ns_.load(
                std::memory_order_acquire);
            while (observed < admitted.input_newest_ns &&
                   !latest_successful_swap_input_event_ns_.compare_exchange_weak(
                       observed, admitted.input_newest_ns,
                       std::memory_order_release, std::memory_order_acquire)) {
            }
        }
        if (!epoch_frame_ids_.insert(admitted.frame_id).second) {
            ++duplicate_frame_id_count_;
            publishPostSubmitSnapshot(
                RendererPostApplyFatalBranch::DUPLICATE_FRAME_ID);
            NTK_LOGE(
                "post-submit invariant=duplicate-frame-id frame=%llu work=%llu "
                "sequence=%lld kind=%d stage=%d success=%llu latched=%llu lost=%llu",
                static_cast<unsigned long long>(admitted.frame_id),
                static_cast<unsigned long long>(admitted.work_generation),
                static_cast<long long>(admitted.frame_sequence),
                static_cast<int>(admitted.kind),
                admitted.stage_candidate ? 1 : 0,
                static_cast<unsigned long long>(successful_swap_count_),
                static_cast<unsigned long long>(latched_proof_count_),
                static_cast<unsigned long long>(terminal_lost_proof_count_));
            enter_post_submit_fatal(
                kFixedFatalConservationFailure,
                surfaceReceipt, swappyReceipt);
            return PreparedCommitResult::SUBMITTED_FATAL_AFTER_EGL_TRUE;
        }
        ++successful_swap_count_;
        const std::uint64_t terminalProofCount =
            latched_proof_count_ + terminal_lost_proof_count_;
        if (terminalProofCount > successful_swap_count_) {
            publishPostSubmitSnapshot(
                RendererPostApplyFatalBranch::PROOF_AHEAD);
            NTK_LOGE(
                "post-submit invariant=proof-count-ahead frame=%llu work=%llu "
                "sequence=%lld kind=%d stage=%d success=%llu latched=%llu lost=%llu",
                static_cast<unsigned long long>(admitted.frame_id),
                static_cast<unsigned long long>(admitted.work_generation),
                static_cast<long long>(admitted.frame_sequence),
                static_cast<int>(admitted.kind),
                admitted.stage_candidate ? 1 : 0,
                static_cast<unsigned long long>(successful_swap_count_),
                static_cast<unsigned long long>(latched_proof_count_),
                static_cast<unsigned long long>(terminal_lost_proof_count_));
            enter_post_submit_fatal(
                kFixedFatalConservationFailure,
                surfaceReceipt, swappyReceipt);
            return PreparedCommitResult::SUBMITTED_FATAL_AFTER_EGL_TRUE;
        }
        max_logical_unlatched_submissions_ = std::max(
            max_logical_unlatched_submissions_,
            successful_swap_count_ - terminalProofCount);
        if (!ntk::present::rendererPostSubmitLogicalUnlatchedExact(
                successful_swap_count_, terminalProofCount,
                max_logical_unlatched_submissions_)) {
            publishPostSubmitSnapshot(
                RendererPostApplyFatalBranch::UNLATCHED_OVERFLOW);
            NTK_LOGE(
                "post-submit invariant=logical-unlatched-overflow frame=%llu work=%llu "
                "sequence=%lld kind=%d stage=%d success=%llu proofs=%llu "
                "maxUnlatched=%llu predecessor=%d",
                static_cast<unsigned long long>(admitted.frame_id),
                static_cast<unsigned long long>(admitted.work_generation),
                static_cast<long long>(admitted.frame_sequence),
                static_cast<int>(admitted.kind),
                admitted.stage_candidate ? 1 : 0,
                static_cast<unsigned long long>(successful_swap_count_),
                static_cast<unsigned long long>(terminalProofCount),
                static_cast<unsigned long long>(max_logical_unlatched_submissions_),
                admission_predecessor_.has_value() ? 1 : 0);
            enter_post_submit_fatal(
                kFixedFatalConservationFailure,
                surfaceReceipt, swappyReceipt);
            return PreparedCommitResult::SUBMITTED_FATAL_AFTER_EGL_TRUE;
        }
        publishPostSubmitSnapshot(RendererPostApplyFatalBranch::NONE);
        if (admitted.stage_candidate) {
            cadence_qualification_state_.store(
                CadenceQualificationState::STAGE_PROOF_PENDING,
                std::memory_order_release);
            stage_requested_ = false;
        }
        if (admitted.terminal) ++terminal_swap_count_;
        integrated_tiles_since_frame_ = 0;
        last_successfully_submitted_work_generation_ =
            admitted.work_generation;
        return PreparedCommitResult::SUBMITTED;
    }

    PreparedCommitResult try_commit_prepared_frame(
            const ntk::scheduler::FixedOpportunityIdentity& opportunity) {
        if (!prepared_frame_work_.has_value() ||
            head_frame_state_ != HeadFrameState::SWAPPY_RESERVED) {
            fail_prepared_frame("external-prepared-commit-state");
            return PreparedCommitResult::FATAL;
        }
        PreparedFrameWork& prepared = *prepared_frame_work_;
        SwappyFixedOpportunityIdentity expected{};
        expected.structSize = sizeof(expected);
        expected.version = SWAPPY_FIXED_OPPORTUNITY_IDENTITY_VERSION;
        expected.workGeneration = opportunity.work_generation;
        expected.reservationSequence = opportunity.reservation_sequence;
        expected.opportunitySequence = opportunity.opportunity_sequence;
        expected.candidateSequence = opportunity.candidate_sequence;
        expected.noticeSequence = opportunity.notice_sequence;
        if (expected.workGeneration != prepared.work_generation ||
            expected.reservationSequence !=
                prepared.swappy_reservation_sequence) {
            fail_prepared_frame("external-opportunity-identity");
            return PreparedCommitResult::FATAL;
        }
        if (prepared.first_commit_attempt_ns == 0) {
            prepared.first_commit_attempt_ns = monotonic_now_ns();
        }
        SwappyFixedExternalClaim claim{};
        const SwappyFixedCommitStatus claimStatus =
            SwappyGL_claimPreparedExternalFixedFrameForNtk(
                &expected, &prepared.transport_ready, &claim);
        if (claimStatus == SWAPPY_FIXED_COMMIT_SLOT_CLOSED_WAITING_NEXT) {
            return PreparedCommitResult::SLOT_CLOSED;
        }
        if (claimStatus == SWAPPY_FIXED_COMMIT_WAITING_CANDIDATE ||
            claimStatus == SWAPPY_FIXED_COMMIT_WAITING_PRIOR_TARGET ||
            claimStatus == SWAPPY_FIXED_COMMIT_WAITING_PRIOR_LATCH) {
            fixed_scheduler_.noteSpuriousCommitAttempt();
            fail_prepared_frame("join-open-contract-regressed");
            return PreparedCommitResult::FATAL;
        }
        if (claimStatus != SWAPPY_FIXED_COMMIT_SUBMITTED ||
            claim.structSize != sizeof(claim) || claim.claimToken == 0 ||
            claim.workGeneration != prepared.work_generation ||
            claim.reservationSequence != expected.reservationSequence ||
            claim.opportunitySequence != expected.opportunitySequence ||
            claim.candidateSequence != expected.candidateSequence ||
            claim.noticeSequence != expected.noticeSequence ||
            claim.frameTimelineVsyncId == 0) {
            SwappyFixedPhaseTelemetry failedPhase{};
            const bool hasFailedPhase =
                SwappyGL_getFixedPhaseTelemetryForNtk(
                    prepared.work_generation, &failedPhase);
            NTK_LOGE(
                "external claim rejected status=%d phase=%d fatal=%d "
                "planned=%lld timeline=%lld expected=%lld deadline=%lld",
                static_cast<int>(claimStatus), hasFailedPhase ? 1 : 0,
                hasFailedPhase ? failedPhase.fatalReason : 0,
                hasFailedPhase
                    ? static_cast<long long>(failedPhase.plannedPresentationNanos)
                    : 0LL,
                static_cast<long long>(claim.frameTimelineVsyncId),
                hasFailedPhase
                    ? static_cast<long long>(failedPhase.timelinePresentationDeadlineNanos)
                    : 0LL,
                hasFailedPhase
                    ? static_cast<long long>(failedPhase.presentationDeadlineNanos)
                    : 0LL);
            fail_prepared_frame("external-swappy-claim");
            return PreparedCommitResult::FATAL;
        }
        prepared.external_claim = claim;
        prepared.claimed_candidate_sequence = claim.candidateSequence;
        prepared.claimed_opportunity_sequence = claim.opportunitySequence;
        prepared.claimed_notice_sequence = claim.noticeSequence;
        prepared.admission_sequence = claim.admissionSequence;
        head_frame_state_ =
            HeadFrameState::EXTERNAL_CLAIMED_NOT_APPLIED;

        const bool firstStage = claim.firstStage == 1;
        const bool predecessorPresent =
            admission_predecessor_.has_value();
        const bool priorRetirementProofValid =
            swappy::fixedPriorRetirementProofValid(
                claim.priorRetirementProof);
        const bool previousAppliedBufferExact =
            priorRetirementProofValid &&
            swappy::fixedAppliedBufferRefExact(
                claim.previousAppliedBufferRef,
                claim.priorRetirementProof.predecessor);
        const bool predecessorIdentityExact =
            predecessorPresent && priorRetirementProofValid &&
            exact_swappy_identity(
                admission_predecessor_->identity,
                claim.priorRetirementProof.predecessor.identity);
        const bool claimSelfExact =
            swappy::fixedExternalClaimExact(claim, claim);
        const bool priorLatchObservationValid =
            swappy::fixedLatchObservationValid(
                claim.priorLatchObservation);
        const bool priorLatchMatchesPrevious =
            priorLatchObservationValid &&
            swappy::fixedFrameIdentityExact(
                claim.priorLatchObservation.identity,
                claim.previousAppliedBufferRef.identity);
        const bool priorLatchMatchesAdmission =
            predecessorPresent && priorLatchObservationValid &&
            exact_swappy_identity(
                admission_predecessor_->identity,
                claim.priorLatchObservation.identity);
        const bool predecessorLatchGateExact = firstStage
            ? claim.priorLatchGateRequired == 0 &&
                claim.priorLatchGateUsed == 0 &&
                claim.priorCommitProofPendingAtClaim == 0 &&
                swappy::fixedLatchObservationEmpty(
                    claim.priorLatchObservation)
            : claim.priorLatchGateRequired == 1 &&
                claim.priorLatchGateUsed == 1 &&
                claim.priorCommitProofPendingAtClaim == 0 &&
                priorLatchObservationValid &&
                priorLatchMatchesPrevious &&
                priorLatchMatchesAdmission;
        const bool predecessorRetirementExact = firstStage
            ? !predecessorPresent &&
                swappy::fixedPriorRetirementProofEmpty(
                    claim.priorRetirementProof) &&
                swappy::fixedAppliedBufferRefEmpty(
                    claim.previousAppliedBufferRef)
            : predecessorPresent && priorRetirementProofValid &&
                previousAppliedBufferExact && predecessorIdentityExact;
        if (!claimSelfExact || !predecessorLatchGateExact ||
            !predecessorRetirementExact) {
            NTK_LOGE(
                "fatal pre-apply retirement conservation work=%llu "
                "frame=%llu first=%d predecessor=%d priorValid=%d "
                "previousExact=%d identityExact=%d claimExact=%d "
                "latchValid=%d latchPrevious=%d latchAdmission=%d "
                "latchGate=%u/%u commitPending=%u",
                static_cast<unsigned long long>(prepared.work_generation),
                static_cast<unsigned long long>(prepared.frame_id),
                firstStage ? 1 : 0,
                admission_predecessor_.has_value() ? 1 : 0,
                priorRetirementProofValid ? 1 : 0,
                previousAppliedBufferExact ? 1 : 0,
                predecessorIdentityExact ? 1 : 0,
                claimSelfExact ? 1 : 0,
                priorLatchObservationValid ? 1 : 0,
                priorLatchMatchesPrevious ? 1 : 0,
                priorLatchMatchesAdmission ? 1 : 0,
                claim.priorLatchGateRequired,
                claim.priorLatchGateUsed,
                claim.priorCommitProofPendingAtClaim);
            fail_prepared_frame("pre-apply-prior-retirement-conservation");
            return PreparedCommitResult::FATAL;
        }

        auto* renderTarget = present_backend_.pool().find(
            prepared.buffer_slot, prepared.buffer_generation);
        if (renderTarget == nullptr) {
            fail_prepared_frame("external-target-identity");
            return PreparedCommitResult::FATAL;
        }
        ntk::present::SurfaceControlPresentBackend::SubmissionReceipt
            surfaceReceipt{};
        const auto applyDisposition =
            present_backend_.applyPreparedBufferTransaction(
                prepared.surface_submission, claim, &surfaceReceipt);
        if (applyDisposition !=
                ntk::present::SurfaceControlPresentBackend::
                    ApplyDisposition::APPLIED) {
            fail_prepared_frame("surface-transaction-apply");
            return PreparedCommitResult::FATAL;
        }
        if (first_transaction_apply_ns_.load(std::memory_order_acquire) == 0) {
            first_transaction_apply_ns_.store(
                monotonic_now_ns(), std::memory_order_release);
        }
        head_frame_state_ = HeadFrameState::PHASE_COMMITTING;
        prepared.terminal_obligation_submitted = !prepared.terminal ||
            fixed_scheduler_.markTerminalSubmitted(
                prepared.gesture_generation,
                prepared.terminal_input_sequence,
                prepared.work_generation);
        if (prepared.terminal && prepared.terminal_obligation_submitted) {
            publish_terminal_progress();
        }
        ++swap_attempt_count_;
        window_swap_count_.fetch_add(1, std::memory_order_acq_rel);
        SwappyFixedExternalSubmission submission{};
        submission.structSize = sizeof(submission);
        submission.version = SWAPPY_FIXED_EXTERNAL_SUBMISSION_VERSION;
        submission.claimToken = claim.claimToken;
        submission.workGeneration = prepared.work_generation;
        submission.ntkFrameId = prepared.frame_id;
        submission.engineGeneration = static_cast<std::uint64_t>(
            prepared.engine_generation);
        submission.surfaceEpoch = prepared.surface_epoch;
        submission.authorityGeneration = prepared.authority_generation;
        submission.authority = prepared.authority;
        submission.frameSequence = prepared.frame_sequence;
        submission.admissionSequence = prepared.admission_sequence;
        submission.capsuleSequence =
            prepared.reserved_evidence_slot_sequence;
        submission.backendSurfaceSerial =
            surfaceReceipt.identity.backendSurfaceSerial;
        submission.transactionSerial =
            surfaceReceipt.identity.transactionSerial;
        submission.bufferSlot = prepared.buffer_slot;
        submission.bufferGeneration = prepared.buffer_generation;
        submission.acquireFenceSerial =
            prepared.gpu_ready_proof.acquireFenceSerial;
        submission.frameTimelineVsyncId = claim.frameTimelineVsyncId;
        submission.gpuRenderBeginNanos =
            prepared.gpu_ready_proof.renderBeginNanos;
        submission.gpuRenderEndNanos =
            prepared.gpu_ready_proof.renderEndNanos;
        submission.gpuFenceIssuedNanos =
            prepared.gpu_ready_proof.acquireFenceIssuedNanos;
        submission.gpuFenceWaitReturnNanos =
            prepared.gpu_ready_proof.acquireFenceExportReturnNanos;
        submission.transactionApplyBeginNanos =
            surfaceReceipt.transactionApplyBeginNanos;
        submission.transactionApplyEndNanos =
            surfaceReceipt.transactionApplyEndNanos;
        submission.setBufferCount = surfaceReceipt.setBufferCount;
        submission.acquireFenceDupCount =
            prepared.gpu_ready_proof.acquireFenceDupCount;
        submission.frameworkTransferCount = 1;
        submission.rendererGpuClientWaitCount =
            prepared.gpu_ready_proof.rendererGpuClientWaitCount;
        submission.setFrameTimelineCount =
            surfaceReceipt.setFrameTimelineCount;
        submission.transactionApplyCount =
            surfaceReceipt.transactionApplyCount;
        submission.firstStage =
            prepared.surface_submission.firstStage ? 1U : 0U;
        submission.transportProfileDigest =
            prepared.transport_ready.profile.profileDigest;
        submission.timingGeneration =
            prepared.transport_ready.profile.timingGeneration;
        submission.transportBoundNanos =
            prepared.transport_ready.profile.transportBoundNanos;
        submission.transactionPrepareBeginNanos =
            prepared.surface_submission.prepareBeginNanos;
        submission.transactionPrepareEndNanos =
            prepared.surface_submission.prepareEndNanos;
        submission.applyDisposition = SWAPPY_FIXED_EXTERNAL_APPLIED;
        submission.previousAppliedBufferRef =
            claim.previousAppliedBufferRef;
        submission.appliedBufferRef.structSize =
            sizeof(submission.appliedBufferRef);
        submission.appliedBufferRef.version =
            SWAPPY_FIXED_APPLIED_BUFFER_REF_V1_VERSION;
        submission.appliedBufferRef.appliedBufferRefSerial =
            surfaceReceipt.appliedBufferRef.serial;
        auto& appliedIdentity = submission.appliedBufferRef.identity;
        appliedIdentity.structSize = sizeof(appliedIdentity);
        appliedIdentity.version = SWAPPY_FIXED_FRAME_IDENTITY_V1_VERSION;
        appliedIdentity.engineGeneration =
            surfaceReceipt.appliedBufferRef.identity.engineGeneration;
        appliedIdentity.surfaceEpoch =
            surfaceReceipt.appliedBufferRef.identity.surfaceEpoch;
        appliedIdentity.authorityGeneration =
            surfaceReceipt.appliedBufferRef.identity.authorityGeneration;
        appliedIdentity.authority =
            surfaceReceipt.appliedBufferRef.identity.authority;
        appliedIdentity.workGeneration =
            surfaceReceipt.appliedBufferRef.identity.workGeneration;
        appliedIdentity.ntkFrameId =
            surfaceReceipt.appliedBufferRef.identity.ntkFrameId;
        appliedIdentity.frameSequence =
            surfaceReceipt.appliedBufferRef.identity.frameSequence;
        appliedIdentity.admissionSequence =
            surfaceReceipt.appliedBufferRef.identity.admissionSequence;
        appliedIdentity.capsuleSequence =
            surfaceReceipt.appliedBufferRef.identity.capsuleSequence;
        appliedIdentity.backendSurfaceSerial =
            surfaceReceipt.appliedBufferRef.identity.backendSurfaceSerial;
        appliedIdentity.transactionSerial =
            surfaceReceipt.appliedBufferRef.identity.transactionSerial;
        appliedIdentity.bufferSlot =
            surfaceReceipt.appliedBufferRef.identity.bufferSlot;
        appliedIdentity.bufferGeneration =
            surfaceReceipt.appliedBufferRef.identity.bufferGeneration;
        appliedIdentity.frameTimelineVsyncId =
            surfaceReceipt.appliedBufferRef.identity.frameTimelineVsyncId;
        SwappyFixedExternalSubmissionReceipt swappyReceipt{};
        const bool swappyCommitted =
            SwappyGL_commitExternalFixedSubmissionForNtk(
                &claim, &submission, &swappyReceipt);
        if (!swappyCommitted) {
            enter_post_submit_fatal(
                swappyReceipt.fatalReason != 0
                    ? swappyReceipt.fatalReason
                    : swappyReceipt.phase.fatalReason,
                surfaceReceipt, swappyReceipt);
            NTK_LOGE(
                "fatal external submission commit work=%llu frame=%llu token=%llu "
                "receipt=%u/%u outcome=%d fatal=%d decision=%lld apply=%lld..%lld "
                "api=%lld/%lld/%lld cutoff=%lld duration=%lld retirement=%llu",
                static_cast<unsigned long long>(prepared.work_generation),
                static_cast<unsigned long long>(prepared.frame_id),
                static_cast<unsigned long long>(claim.claimToken),
                swappyReceipt.structSize, swappyReceipt.version,
                swappyReceipt.phase.outcome, swappyReceipt.phase.fatalReason,
                static_cast<long long>(claim.decisionNanos),
                static_cast<long long>(
                    surfaceReceipt.transactionApplyBeginNanos),
                static_cast<long long>(
                    surfaceReceipt.transactionApplyEndNanos),
                static_cast<long long>(
                    surfaceReceipt.frameTimelineSetEndNanos -
                        surfaceReceipt.transactionApplyBeginNanos),
                static_cast<long long>(
                    surfaceReceipt.bufferSetEndNanos -
                        surfaceReceipt.frameTimelineSetEndNanos),
                static_cast<long long>(
                    surfaceReceipt.transactionApplyEndNanos -
                        surfaceReceipt.bufferSetEndNanos),
                static_cast<long long>(
                    swappyReceipt.phase.plannedCutoffNanos),
                static_cast<long long>(
                    swappyReceipt.phase.submitDurationNanos),
                static_cast<unsigned long long>(
                    swappyReceipt.retirementSequence));
        }
        const PreparedCommitResult finishResult =
            finish_external_submitted_frame(
                prepared, surfaceReceipt, swappyReceipt, swappyCommitted);
        return swappyCommitted ? finishResult
            : PreparedCommitResult::SUBMITTED_FATAL_AFTER_EGL_TRUE;
    }
    const std::int64_t native_create_begin_ns_;
    const std::int64_t swappy_init_begin_ns_;
    const bool swappy_ready_;
    const std::int64_t swappy_init_end_ns_;
    std::atomic<std::int64_t> native_create_end_ns_{0};
    std::atomic<std::int64_t> egl_initialize_begin_ns_{0};
    std::atomic<std::int64_t> egl_initialize_end_ns_{0};
    std::atomic<std::int64_t> render_context_ready_ns_{0};
    std::atomic<std::int64_t> upload_context_ready_ns_{0};
    std::atomic<std::int64_t> render_pbuffer_ready_ns_{0};
    std::atomic<std::int64_t> upload_pbuffer_ready_ns_{0};
    std::atomic<std::int64_t> program_ready_ns_{0};
    std::atomic<std::int64_t> egl_ready_ns_{0};
    std::atomic<std::int64_t> detached_warm_ready_ns_{0};
    std::atomic<std::int64_t> attach_lease_queued_ns_{0};
    std::atomic<std::int64_t> attach_lease_claimed_ns_{0};
    std::atomic<std::int64_t> swappy_window_begin_ns_{0};
    std::atomic<std::int64_t> swappy_window_end_ns_{0};
    std::atomic<std::int64_t> surface_control_attach_begin_ns_{0};
    std::atomic<std::int64_t> surface_control_attach_end_ns_{0};
    std::atomic<std::int64_t> attach_ready_ns_{0};
    std::atomic<std::int64_t> attach_published_ns_{0};
    std::atomic<std::int64_t> first_backend_prepare_ns_{0};
    std::atomic<std::int64_t> first_transaction_apply_ns_{0};
    std::atomic<std::int64_t> surface_control_attach_count_{0};
    std::atomic<std::int64_t> window_frame_id_count_{0};
    std::atomic<std::int64_t> window_swap_count_{0};
    JavaVM* java_vm_ = nullptr;
    jobject callback_ = nullptr;
    jmethodID on_tile_resident_ = nullptr;
    jmethodID on_prepared_tile_resident_ = nullptr;
    jmethodID on_protection_committed_ = nullptr;
    jmethodID on_retire_result_ = nullptr;
    jmethodID on_tile_freed_ = nullptr;
    jmethodID on_pre_submit_viewport_gap_ = nullptr;
    jmethodID on_frame_evidence_v11_ = nullptr;
    jmethodID on_stage_latched_v2_ = nullptr;
    jmethodID on_authority_released_ = nullptr;
    jmethodID on_authority_release_dispatchable_ = nullptr;
    const std::int64_t engine_generation_;

    struct TraceSlot {
        TraceRecord record;
        std::atomic<std::uint64_t> committed_sequence{0};
    };
    SwappyTracer swappy_tracer_{};
    bool swappy_tracer_injected_ = false;
    bool initialization_valid_ = false;
    std::array<TraceSlot, kTraceRingSize> trace_ring_{};
    std::atomic<std::uint64_t> trace_write_sequence_{0};
    std::atomic<std::uint64_t> pre_submit_viewport_gap_{0};
    std::atomic<std::int64_t> trace_pre_swap_ns_{0};
    std::atomic<std::int64_t> trace_post_swap_ns_{0};
    std::atomic<bool> swap_interval_changed_{false};
    std::atomic<bool> authority_failed_{false};
    // Share-group/engine poison is distinct from a current-token failure. Old release watchdogs
    // never write authority_failed_ and therefore cannot interrupt an already active successor.
    std::atomic<bool> engine_failed_{false};
    std::atomic<std::int64_t> egl_initialize_count_{0};
    std::atomic<std::int64_t> egl_context_create_count_{0};
    std::atomic<std::int64_t> bind_apply_count_{0};
    std::atomic<std::int64_t> release_ack_count_{0};
    std::atomic<bool> attach_authority_failed_{false};
    // Sticky for this renderer instance. A lost share group requires a new native handle;
    // ordinary EGLSurface destruction leaves it true and preserves rolling resources.
    std::atomic<bool> context_resources_valid_{true};
    std::atomic<bool> cadence_qualification_failed_{false};
    std::atomic<bool> feedback_cadence_failure_pending_{false};
    std::atomic<bool> feedback_authority_failure_pending_{false};
    std::atomic<CadenceQualificationState> cadence_qualification_state_{
        CadenceQualificationState::NO_SURFACE};
    HeadFrameState head_frame_state_ = HeadFrameState::EMPTY;
    std::uint64_t pending_work_generation_ = 0;
    ntk::scheduler::FixedOpportunityGate fixed_opportunity_gate_{};
    std::atomic<std::uint64_t> armed_fixed_work_generation_{0};
    std::atomic<std::uint64_t> armed_fixed_reservation_sequence_{0};
    std::atomic<bool> fixed_wake_notice_invalid_{false};
    std::array<FixedRetirementEventSlot, kFixedRetirementEventRingSize>
        fixed_retirement_event_ring_{};
    std::atomic<std::uint64_t> fixed_retirement_event_write_sequence_{0};
    std::atomic<std::uint64_t> fixed_retirement_event_read_sequence_{0};
    std::atomic<bool> fixed_retirement_event_invalid_{false};
    // Render-thread-owned callback cleanup parked while a reserved successor
    // waits for (or consumes) its exact predecessor OnCommit/JOIN_OPEN.
    std::array<DeferredPresentCleanupRecord,
        kDeferredPresentCleanupCapacity> deferred_present_cleanup_events_{};
    std::size_t deferred_present_cleanup_read_ = 0;
    std::size_t deferred_present_cleanup_write_ = 0;
    std::size_t deferred_present_cleanup_count_ = 0;
    std::uint64_t last_successfully_submitted_work_generation_ = 0;
    bool fixed_causal_lane_fatal_ = false;
    std::uint64_t fixed_admission_sequence_ = 0;
    ntk::scheduler::FixedDepthOneScheduler fixed_scheduler_{};
    std::optional<PreparedFrameWork> prepared_frame_work_;
    // Only the immediate predecessor credit is retained globally. All full
    // latch/retirement/OnComplete/release ownership lives in its capsule.
    std::optional<AdmissionPredecessor> admission_predecessor_;
    EGLuint64KHR reserved_frame_id_ = 0;
    std::uint64_t reserved_frame_id_work_generation_ = 0;
    std::uint64_t slot_closed_no_attempt_count_ = 0;
    std::uint64_t prepared_draw_issuance_count_ = 0;
    std::uint64_t prepared_frame_id_reservation_count_ = 0;
    std::uint64_t backend_present_prepare_count_ = 0;
    std::uint64_t swap_attempt_count_ = 0;
    std::uint64_t terminal_swap_count_ = 0;
    std::atomic<bool> input_admission_blocked_{true};
    std::atomic<bool> presentation_blocked_{true};
    std::atomic<std::int64_t> first_main_ingress_ns_{0};
    std::atomic<std::int64_t> last_ingress_event_time_ns_{0};
    std::atomic<std::int64_t> last_ingress_main_time_ns_{0};
    std::atomic<std::int64_t> latest_successful_swap_input_event_ns_{0};
    std::atomic<std::int64_t> latest_delivered_latched_input_event_ns_{0};
    std::atomic<RendererMode> renderer_mode_{RendererMode::PREPARING};
    std::atomic<int> upload_commands_submitting_{0};
    std::atomic<int> upload_gpu_fences_pending_{0};
    std::atomic<bool> test_context_loss_during_detach_{false};
    std::atomic<bool> upload_submission_blocked_{false};
    std::atomic<GpuPhase> gpu_phase_{GpuPhase::PRE_STAGE_CPU};
    std::atomic<GpuResourceWorkerState> gpu_resource_worker_state_{
        GpuResourceWorkerState::ABSENT};
    std::atomic<std::uint64_t> resource_worker_generation_{0};
    std::atomic<std::uint64_t> resource_worker_create_count_{0};
    std::atomic<std::uint64_t> resource_worker_destroy_count_{0};
    std::atomic<int> active_resource_worker_count_{0};
    std::atomic<bool> resource_worker_context_counted_alive_{false};
    // A worker generation is owned by one exact native authority.  The initial EGL worker is
    // deliberately unowned until the first bind; every later owner change requires physical
    // retirement and a new context/thread generation.
    std::atomic<std::int64_t> resource_worker_owner_authority_generation_{0};
    std::atomic<std::int64_t> resource_worker_owner_authority_{0};
    std::atomic<std::int64_t> resource_worker_context_created_ns_{0};
    std::atomic<std::int64_t> resource_worker_ready_ns_{0};
    std::atomic<std::int64_t> resource_worker_context_destroyed_ns_{0};
    std::atomic<std::int64_t> resource_worker_thread_joined_ns_{0};
    std::atomic<std::int64_t> bind_committed_ns_{0};
    std::atomic<std::uint64_t> resource_submit_serial_{0};
    std::uint64_t release_protocol_serial_ = 0;
    std::atomic<std::uint64_t> sealed_resource_submit_serial_{0};
    std::atomic<std::uint64_t> seal_barrier_serial_{0};
    std::atomic<bool> scene_sealed_{false};
    std::atomic<bool> upload_context_alive_{false};
    std::atomic<std::int64_t> last_gpu_resource_completion_ns_{0};
    std::atomic<std::int64_t> seal_fence_completion_ns_{0};
    std::atomic<std::int64_t> offscreen_warm_fence_completion_ns_{0};
    std::atomic<std::int64_t> predecessor_physical_complete_ns_{0};
    std::atomic<std::int64_t> stage_backbuffer_ready_ns_{0};
    std::atomic<std::uint64_t> offscreen_warm_draw_count_{0};
    std::atomic<std::uint64_t> scene_mutation_count_{0};
    std::atomic<std::uint64_t> sealed_scene_mutation_count_{0};
    std::atomic<std::int64_t> upload_context_destroyed_ns_{0};
    std::atomic<std::int64_t> stage_latch_ns_{0};
    std::atomic<std::int64_t> first_down_ingress_ns_{0};
    std::unordered_set<EGLuint64KHR> epoch_frame_ids_;
    std::uint64_t surface_epoch_ = 0;
    std::atomic<std::uint64_t> admitted_surface_epoch_{0};
    std::uint64_t successful_swap_count_ = 0;
    std::uint64_t latched_proof_count_ = 0;
    std::uint64_t terminal_lost_proof_count_ = 0;
    std::uint64_t duplicate_frame_id_count_ = 0;
    std::uint64_t max_logical_unlatched_submissions_ = 0;

    std::mutex feedback_mutex_;
    std::condition_variable feedback_ready_;
    std::condition_variable feedback_space_;
    std::condition_variable feedback_barrier_condition_;
    std::array<FeedbackRecord, kFeedbackRingSize> feedback_ring_{};
    std::size_t feedback_read_ = 0;
    std::size_t feedback_write_ = 0;
    std::size_t feedback_count_ = 0;
    std::array<EvidenceCapsuleSlot, kFrameFeedbackRingSize>
        evidence_capsule_ring_{};
    std::uint64_t frame_feedback_write_sequence_ = 0;
    std::atomic<std::uint64_t> frame_feedback_committed_sequence_{0};
    std::atomic<std::uint64_t> frame_feedback_read_sequence_{0};
    std::atomic<std::uint64_t> frame_feedback_delivered_sequence_{0};
    std::atomic<bool> frame_feedback_capacity_failed_{false};
    std::atomic<std::uint64_t> evidence_capsule_max_depth_{0};
    std::atomic<std::uint64_t> evidence_capsule_invalid_count_{0};
    bool feedback_exit_requested_ = false;
    std::uint64_t feedback_barrier_requested_ = 0;
    std::uint64_t feedback_barrier_completed_ = 0;
    std::thread feedback_thread_;

    std::mutex mutex_;
    std::mutex attach_api_mutex_;
    std::mutex bind_api_mutex_;
    std::condition_variable render_condition_;
    std::condition_variable attach_condition_;
    std::condition_variable upload_condition_;
    std::condition_variable detached_condition_;
    std::condition_variable upload_exit_condition_;
    std::condition_variable upload_seal_condition_;
    std::condition_variable upload_start_condition_;
    std::condition_variable preparation_drain_condition_;
    std::mutex upload_submit_mutex_;
    std::condition_variable bind_condition_;
    std::thread render_thread_;
    std::thread upload_thread_;
    bool stopped_ = false;
    bool egl_ready_ = false;
    bool render_initialization_complete_ = false;
    bool render_exited_ = false;
    bool upload_exit_requested_ = false;
    bool upload_exited_ = false;
    bool upload_active_ = false;
    bool upload_seal_requested_ = false;
    bool upload_sealed_ = false;
    bool context_loss_pending_ = false;
    std::uint64_t context_loss_surface_epoch_ = 0;
    bool swappy_lifetime_released_ = false;
    std::size_t native_outstanding_ = 0;
    std::atomic<int> native_outstanding_mirror_{0};
    std::optional<AttachRequest> attach_request_;
    std::uint64_t last_attach_generation_ = 0;
    bool detach_requested_ = false;
    bool disarm_requested_ = false;
    bool render_requested_ = false;
    struct DeferredLifecycleState {
        bool active = false;
        std::uint64_t observed_terminal_progress = 0;
        std::uint64_t last_rearmed_progress = 0;
    };
    DeferredLifecycleState deferred_lifecycle_{};
    std::uint64_t terminal_progress_sequence_ = 0;
    std::optional<BindRequest> pending_bind_request_;
    std::uint64_t bind_request_generation_ = 0;
    ResourceWorkerStartState upload_start_state_ = ResourceWorkerStartState::IDLE;
    std::uint64_t upload_start_generation_ = 0;
    AuthorityKey upload_start_owner_{};
    bool stage_requested_ = false;
    std::uint64_t command_generation_ = 0;
    std::uint64_t detached_generation_ = 0;
    std::deque<UploadCommand> upload_commands_;
    std::optional<UploadCommand> in_flight_upload_;
    std::optional<PendingResourceDelete> in_flight_resource_delete_;
    std::deque<PreallocateCommand> preallocate_commands_;
    std::unordered_map<TileKey, PreallocatedTexture, TileKeyHash> preallocated_textures_;
    std::unordered_map<TileKey, PreparedBankTile, TileKeyHash> prepared_bank_;
    ntk::prepared_scene::Ledger prepared_bank_ledger_{};
    bool preparation_open_ = false;
    bool prepared_geometry_bound_ = false;
    bool preparation_admissions_closed_ = false;
    std::int64_t preparation_token_nonce_ = 0;
    std::int64_t preparation_opened_ns_ = 0;
    std::unordered_map<TileKey, PreallocateCommand, TileKeyHash> slot_specs_;
    std::vector<TileKey> ordinal_keys_;
    std::unordered_map<TileKey, int, TileKeyHash> key_ordinals_;
    std::deque<GpuReadyTile> gpu_ready_tiles_;
    std::vector<PendingPublishAck> pending_publish_acks_;
    std::optional<ProtectionCommit> pending_protection_commit_;
    std::deque<RetireIntent> retire_intents_;
    std::atomic<int> ready_tile_queue_depth_mirror_{0};
    std::atomic<int> pending_publish_ack_mirror_{0};
    std::atomic<int> retire_intent_depth_mirror_{0};
    std::deque<InputSample> input_control_commands_;
    std::map<AuthorityKey, std::shared_ptr<AuthorityReleaseTracker>> release_trackers_;
    std::map<AuthorityKey, ReleaseClaim> pending_release_claims_;
    std::set<AuthorityKey> released_authorities_;

    std::mutex move_mailbox_mutex_;
    InputSample move_mailbox_;
    std::uint64_t move_mailbox_sequence_ = 0;
    std::atomic<std::uint64_t> next_input_sequence_{0};
    std::atomic<std::uint64_t> ingress_gesture_generation_{0};
    std::atomic<bool> ingress_pointer_down_{false};
    std::atomic<int> control_backlog_max_{0};
    std::atomic<int> move_mailbox_writes_{0};

    std::int64_t max_authority_generation_ = 0;
    std::int64_t stage_authority_ = 0;
    std::int64_t stage_corridor_start_ = 0;
    std::int64_t stage_corridor_end_ = 0;
    std::int64_t stage_nonce_ = 0;
    std::atomic<std::int64_t> staged_nonce_{0};
    std::atomic<bool> stage_pin_active_{false};
    GpuSceneAdmissionLedger gpu_scene_admission_{};
    std::size_t expected_tile_count_ = 0;
    std::int64_t sealed_scene_version_ = 0;
    std::size_t sealed_tile_count_ = 0;
    std::int64_t sealed_content_end_ = 0;
    AppliedProtection applied_protection_;
    std::uint64_t retire_intents_received_ = 0;
    std::uint64_t retire_intents_detached_ = 0;
    std::uint64_t retire_intents_stale_ = 0;
    std::uint64_t retire_intents_protected_ = 0;
    std::uint64_t retire_intents_visible_ = 0;
    std::uint64_t retire_intents_not_resident_ = 0;
    std::uint64_t retire_intents_failed_ = 0;

    EGLDisplay display_ = EGL_NO_DISPLAY;
    EGLConfig config_ = nullptr;
    BackendClass backend_class_ = BackendClass::NONE;
    RawZeroForwardingMode raw_zero_forwarding_mode_ =
        RawZeroForwardingMode::NONE;
    bool pending_gfxstream_min1_ = false;
    const bool qualification_manifest_verified_;
    EGLint selected_min_swap_interval_ = 1;
    std::string egl_vendor_;
    std::string egl_version_;
    std::string gl_vendor_;
    std::string gl_renderer_;
    std::string gl_version_;
    std::uint64_t fixed_period_ns_ = kNinetyHzPeriodNs;
    ntk::present::FixedTransportProfile fixed_transport_profile_{};
    EGLContext render_context_ = EGL_NO_CONTEXT;
    EGLContext upload_context_ = EGL_NO_CONTEXT;
    EGLSurface render_pbuffer_ = EGL_NO_SURFACE;
    EGLSurface upload_pbuffer_ = EGL_NO_SURFACE;
    ntk::present::SurfaceControlPresentBackend present_backend_{};
    bool present_backend_attached_ = false;
    GLuint program_ = 0;
    GLuint vao_ = 0;
    GLuint vbo_ = 0;
    GLuint warm_fbo_ = 0;
    GLuint warm_color_renderbuffer_ = 0;
    GLint y_bounds_uniform_ = -1;
    int width_ = 0;
    int height_ = 0;
    int viewport_width_ = 0;
    int viewport_height_ = 0;
    std::int64_t authority_ = 0;
    std::int64_t authority_generation_ = 0;
    std::atomic<std::int64_t> active_authority_{0};
    std::atomic<std::int64_t> active_authority_generation_{0};
    std::int64_t current_manifest_revision_ = 0;
    std::string current_manifest_digest_;
    std::string current_geometry_digest_;
    std::string current_pregeometry_root_digest_;
    std::int64_t content_height_ = 0;
    PresentedViewState presented_view_state_{};
    std::int64_t scene_version_ = 0;
    std::uint64_t presented_visual_mutation_serial_ = 0;
    std::uint64_t render_frame_sequence_ = 0;
    int integrated_tiles_since_frame_ = 0;
    std::int64_t retire_fence_serial_ = 0;
    std::unordered_map<TileKey, SceneTile, TileKeyHash> scene_;
    SealedDrawIndex sealed_draw_index_;
    std::vector<std::pair<std::int64_t, std::int64_t>> resident_intervals_;
    std::deque<PendingResourceDelete> resource_deletes_;
    std::atomic<int> resource_delete_depth_mirror_{0};
    std::vector<ReleaseResourceIdentity> current_resource_registry_;
};

std::atomic<std::int64_t> g_handle_wrapper_create_count{0};
std::atomic<std::int64_t> g_handle_wrapper_destroy_count{0};

struct RendererHandle {
    RendererHandle() {
        g_handle_wrapper_create_count.fetch_add(1, std::memory_order_acq_rel);
    }
    ~RendererHandle() {
        g_handle_wrapper_destroy_count.fetch_add(1, std::memory_order_acq_rel);
    }

    std::shared_mutex api_gate;
    std::mutex state_mutex;
    NativeHandleMode mode = NativeHandleMode::LIVE;
    std::int64_t engine_generation = 0;
    std::unique_ptr<StripRenderer> live;
    std::unique_ptr<RetiredBackendProofStore> retired;
};

using RendererHandleId = std::uint64_t;

std::mutex g_handle_registry_mutex;
std::unordered_map<RendererHandleId, std::shared_ptr<RendererHandle>> g_handle_registry;
std::atomic<RendererHandleId> g_next_handle_id{1};

std::shared_ptr<RendererHandle> acquire_handle(jlong opaque_id) {
    if (opaque_id <= 0) return nullptr;
    const auto id = static_cast<RendererHandleId>(opaque_id);
    std::lock_guard<std::mutex> lock(g_handle_registry_mutex);
    const auto found = g_handle_registry.find(id);
    return found == g_handle_registry.end() ? nullptr : found->second;
}

RendererHandleId register_handle(const std::shared_ptr<RendererHandle>& handle) {
    if (!handle) return 0;
    const RendererHandleId id = g_next_handle_id.fetch_add(1, std::memory_order_acq_rel);
    if (id == 0 || id > static_cast<RendererHandleId>(
            std::numeric_limits<std::int64_t>::max())) {
        return 0;
    }
    std::lock_guard<std::mutex> lock(g_handle_registry_mutex);
    return g_handle_registry.emplace(id, handle).second ? id : 0;
}

bool erase_handle_if_same(
        RendererHandleId id, const std::shared_ptr<RendererHandle>& expected) {
    std::lock_guard<std::mutex> lock(g_handle_registry_mutex);
    const auto found = g_handle_registry.find(id);
    if (found == g_handle_registry.end() || found->second != expected) return false;
    g_handle_registry.erase(found);
    return true;
}

class LiveRendererCall final {
public:
    explicit LiveRendererCall(jlong opaque_id)
        : handle_(acquire_handle(opaque_id)) {
        if (handle_ == nullptr) return;
        api_lock_ = std::shared_lock<std::shared_mutex>(handle_->api_gate);
        std::lock_guard<std::mutex> lock(handle_->state_mutex);
        if (handle_->mode == NativeHandleMode::LIVE) renderer_ = handle_->live.get();
    }

    StripRenderer* get() const { return renderer_; }

private:
    std::shared_ptr<RendererHandle> handle_;
    std::shared_lock<std::shared_mutex> api_lock_;
    StripRenderer* renderer_ = nullptr;
};

/**
 * The caller has already published CONTEXT_LOSS_RETIRING. Backend joins and destruction run
 * without a handle lock; the mode itself rejects every later operational acquisition.
 */
bool retire_handle_context_loss_on_detach(
        const std::shared_ptr<RendererHandle>& handle, StripRenderer* renderer,
        std::uint64_t surface_epoch,
        const RetiredAuthoritySelection& expected_authorities) {
    if (!handle || renderer == nullptr || surface_epoch == 0) return false;
    auto retired = renderer->retire_context_lost_on_detach(
        surface_epoch, expected_authorities);
    if (!retired) {
        std::unique_ptr<StripRenderer> failed_renderer;
        {
            std::unique_lock<std::shared_mutex> api_lock(handle->api_gate);
            std::lock_guard<std::mutex> lock(handle->state_mutex);
            if (handle->mode == NativeHandleMode::CONTEXT_LOSS_RETIRING &&
                handle->live.get() == renderer) {
                failed_renderer = std::move(handle->live);
            }
            handle->mode = NativeHandleMode::DESTROYED;
        }
        failed_renderer.reset();
        return false;
    }
    std::unique_ptr<StripRenderer> renderer_owner;
    {
        std::unique_lock<std::shared_mutex> api_lock(handle->api_gate);
        std::lock_guard<std::mutex> lock(handle->state_mutex);
        if (handle->mode != NativeHandleMode::CONTEXT_LOSS_RETIRING ||
            handle->live.get() != renderer || handle->retired) {
            return false;
        }
        renderer_owner = std::move(handle->live);
        handle->retired = std::move(retired);
    }
    // The proof store is CPU-only. Destroy the mutable renderer before publishing its mode.
    renderer_owner.reset();
    {
        std::unique_lock<std::shared_mutex> api_lock(handle->api_gate);
        std::lock_guard<std::mutex> lock(handle->state_mutex);
        if (handle->mode != NativeHandleMode::CONTEXT_LOSS_RETIRING ||
            handle->live || !handle->retired) return false;
        handle->mode = NativeHandleMode::RETIRED_PROOF_ONLY;
    }
    return true;
}

constexpr jint kReleaseRejected = 0;
constexpr jint kReleaseAcceptedAsync = 1;
constexpr jint kReleaseAckedSynchronously = 2;

bool dispatch_frozen_authority_release_metadata(
        JNIEnv* env, jobject callback, const AuthorityReleaseAckData& ack) {
    if (env == nullptr || callback == nullptr || !ack.success) return false;
    jclass callback_class = env->GetObjectClass(callback);
    if (callback_class == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return false;
    }
    jmethodID method = env->GetMethodID(
        callback_class, "onNativeAuthorityReleased",
        "(JJJJJIJJJJJIJLjava/lang/String;IJLjava/lang/String;IIIIIIIJIIIJJIIIIIJZZ)V");
    env->DeleteLocalRef(callback_class);
    if (method == nullptr || env->ExceptionCheck()) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return false;
    }
    jstring captured_digest = env->NewStringUTF(ack.captured_resource_digest.c_str());
    jstring released_digest = env->NewStringUTF(ack.released_resource_digest.c_str());
    if (captured_digest == nullptr || released_digest == nullptr) {
        if (captured_digest != nullptr) env->DeleteLocalRef(captured_digest);
        if (released_digest != nullptr) env->DeleteLocalRef(released_digest);
        if (env->ExceptionCheck()) env->ExceptionClear();
        return false;
    }
    env->CallVoidMethod(
        callback, method,
        static_cast<jlong>(ack.claim.key.engine_generation),
        static_cast<jlong>(ack.claim.key.authority_generation),
        static_cast<jlong>(ack.claim.key.authority),
        static_cast<jlong>(ack.claim.reducer_surface_epoch),
        static_cast<jlong>(ack.claim.release_nonce),
        static_cast<jint>(ack.disposition),
        static_cast<jlong>(ack.admission_close_serial),
        static_cast<jlong>(ack.release_claim_serial),
        static_cast<jlong>(ack.resource_barrier_serial),
        static_cast<jlong>(ack.resource_completion_watermark),
        static_cast<jlong>(ack.feedback_barrier_serial),
        static_cast<jint>(ack.captured_resource_count),
        static_cast<jlong>(ack.captured_rgba_bytes),
        captured_digest,
        static_cast<jint>(ack.released_resource_count),
        static_cast<jlong>(ack.released_rgba_bytes),
        released_digest,
        static_cast<jint>(ack.deleted_texture_count),
        static_cast<jint>(ack.deleted_fence_count),
        static_cast<jint>(ack.released_bitmap_global_ref_count),
        static_cast<jint>(ack.drained_upload_count),
        static_cast<jint>(ack.drained_retire_count),
        static_cast<jint>(ack.remaining_command_count),
        static_cast<jint>(ack.remaining_resource_count),
        static_cast<jlong>(ack.remaining_rgba_bytes),
        static_cast<jint>(ack.remaining_fence_count),
        static_cast<jint>(ack.remaining_bitmap_global_ref_count),
        static_cast<jint>(ack.remaining_native_callback_count),
        static_cast<jlong>(ack.backend_retirement_serial),
        static_cast<jlong>(ack.backend_retired_nanos),
        static_cast<jint>(ack.retired_backend_remaining_thread_count),
        static_cast<jint>(ack.retired_backend_remaining_egl_handle_count),
        static_cast<jint>(ack.retired_backend_remaining_native_window_count),
        static_cast<jint>(ack.retired_backend_remaining_swappy_lease_count),
        static_cast<jint>(ack.retired_backend_remaining_jni_global_ref_count),
        static_cast<jlong>(ack.completed_nanos),
        static_cast<jboolean>(ack.context_reusable ? JNI_TRUE : JNI_FALSE),
        static_cast<jboolean>(ack.success ? JNI_TRUE : JNI_FALSE));
    env->DeleteLocalRef(captured_digest);
    env->DeleteLocalRef(released_digest);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        return false;
    }
    return true;
}

bool dispatch_frozen_authority_release_dispatchable(
        JNIEnv* env, jobject callback, const ReleaseClaim& claim) {
    if (env == nullptr || callback == nullptr) return false;
    jclass callback_class = env->GetObjectClass(callback);
    if (callback_class == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return false;
    }
    jmethodID method = env->GetMethodID(
        callback_class, "onNativeAuthorityReleaseDispatchable", "(JJJJ)V");
    env->DeleteLocalRef(callback_class);
    if (method == nullptr || env->ExceptionCheck()) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return false;
    }
    env->CallVoidMethod(
        callback, method,
        static_cast<jlong>(claim.key.engine_generation),
        static_cast<jlong>(claim.key.authority_generation),
        static_cast<jlong>(claim.key.authority),
        static_cast<jlong>(claim.release_nonce));
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        return false;
    }
    return true;
}

jint claim_retired_authority_proof_synchronously(
        const std::shared_ptr<RendererHandle>& handle, JNIEnv* env, jobject callback,
        std::int64_t engine_generation, std::int64_t authority_generation,
        std::int64_t authority, std::int64_t reducer_surface_epoch,
        std::int64_t release_nonce) {
    if (!handle || env == nullptr || callback == nullptr ||
        engine_generation <= 0 || authority_generation <= 0 || authority <= 0 ||
        reducer_surface_epoch < 0 || release_nonce <= 0) return kReleaseRejected;
    const AuthorityKey key{engine_generation, authority_generation, authority};
    const ReleaseClaim requested{key, reducer_surface_epoch, release_nonce};
    AuthorityReleaseAckData ack;
    {
        std::shared_lock<std::shared_mutex> api_lock(handle->api_gate);
        std::lock_guard<std::mutex> lock(handle->state_mutex);
        if (handle->mode != NativeHandleMode::RETIRED_PROOF_ONLY ||
            !handle->retired || handle->retired->engine_generation != engine_generation) {
            return kReleaseRejected;
        }
        const auto found = handle->retired->authority_proofs.find(key);
        if (found == handle->retired->authority_proofs.end()) return kReleaseRejected;
        auto& proof = found->second;
        if (proof.lifecycle == AuthorityLifecycle::RELEASED ||
            proof.lifecycle == AuthorityLifecycle::FAILED ||
            proof.metadata_dispatch_in_progress) return kReleaseRejected;
        if (proof.claim.has_value()) {
            const auto& claim = *proof.claim;
            if (!(claim.key == requested.key) ||
                claim.reducer_surface_epoch != requested.reducer_surface_epoch ||
                claim.release_nonce != requested.release_nonce) return kReleaseRejected;
        } else {
            proof.claim = requested;
            proof.lifecycle = AuthorityLifecycle::RELEASING_CLAIMED;
            proof.frozen_ack.release_claim_serial = next_release_protocol_serial(
                handle->retired->release_protocol_serial_watermark);
        }
        proof.frozen_ack.claim = requested;
        if (proof.frozen_ack.release_claim_serial <=
                proof.frozen_ack.admission_close_serial ||
            !proof.frozen_ack.success) {
            proof.lifecycle = AuthorityLifecycle::FAILED;
            return kReleaseRejected;
        }
        proof.metadata_dispatch_in_progress = true;
        ack = proof.frozen_ack;
    }
    // Both Java calls are outside registry/api/state locks. The shared production sequencer makes
    // metadata-return -> native terminalization -> dispatchable publication one explicit order.
    const bool terminalized = publish_frozen_release_metadata_then_terminalize(
        [&] { return dispatch_frozen_authority_release_metadata(env, callback, ack); },
        [&] {
            std::shared_lock<std::shared_mutex> api_lock(handle->api_gate);
            std::lock_guard<std::mutex> lock(handle->state_mutex);
            if (handle->mode != NativeHandleMode::RETIRED_PROOF_ONLY ||
                !handle->retired) return false;
            const auto found = handle->retired->authority_proofs.find(key);
            if (found == handle->retired->authority_proofs.end()) return false;
            auto& proof = found->second;
            if (!proof.metadata_dispatch_in_progress || !proof.claim.has_value() ||
                !(proof.claim->key == requested.key) ||
                proof.claim->reducer_surface_epoch != requested.reducer_surface_epoch ||
                proof.claim->release_nonce != requested.release_nonce) return false;
            proof.metadata_dispatch_in_progress = false;
            proof.lifecycle = AuthorityLifecycle::RELEASED;
            return true;
        },
        [&] {
            return dispatch_frozen_authority_release_dispatchable(
                env, callback, requested);
        });
    if (!terminalized) return kReleaseRejected;
    return kReleaseAckedSynchronously;
}

bool dispatch_preclaimed_retired_proofs(
        const std::shared_ptr<RendererHandle>& handle, JNIEnv* env, jobject callback) {
    if (!handle) return false;
    std::vector<ReleaseClaim> claims;
    {
        std::shared_lock<std::shared_mutex> api_lock(handle->api_gate);
        std::lock_guard<std::mutex> lock(handle->state_mutex);
        if (handle->mode != NativeHandleMode::RETIRED_PROOF_ONLY || !handle->retired) {
            return false;
        }
        for (const auto& entry : handle->retired->authority_proofs) {
            if (entry.second.lifecycle == AuthorityLifecycle::RELEASING_CLAIMED &&
                entry.second.claim.has_value()) {
                claims.push_back(*entry.second.claim);
            }
        }
    }
    for (const auto& claim : claims) {
        if (claim_retired_authority_proof_synchronously(
                handle, env, callback, claim.key.engine_generation,
                claim.key.authority_generation, claim.key.authority,
                claim.reducer_surface_epoch, claim.release_nonce) !=
                kReleaseAckedSynchronously) {
            return false;
        }
    }
    return true;
}

bool all_retired_proofs_released(const RetiredBackendProofStore& store) {
    return std::all_of(
        store.authority_proofs.begin(), store.authority_proofs.end(),
        [](const auto& entry) {
            return entry.second.lifecycle == AuthorityLifecycle::RELEASED;
        });
}

bool destroy_registered_handle(jlong opaque_id) {
    const auto handle = acquire_handle(opaque_id);
    if (!handle || opaque_id <= 0) return false;
    std::unique_ptr<StripRenderer> live;
    std::unique_ptr<RetiredBackendProofStore> retired;
    std::unique_lock<std::shared_mutex> api_lock(handle->api_gate);
    {
        std::lock_guard<std::mutex> state_lock(handle->state_mutex);
        if (handle->mode == NativeHandleMode::CONTEXT_LOSS_RETIRING) return false;
        if (handle->mode == NativeHandleMode::RETIRED_PROOF_ONLY && handle->retired &&
            !all_retired_proofs_released(*handle->retired)) {
            NTK_LOGE("proof-only close rejected while claimable proofs remain");
            return false;
        }
        live = std::move(handle->live);
        retired = std::move(handle->retired);
        handle->mode = NativeHandleMode::DESTROYED;
    }
    const bool erased = erase_handle_if_same(
        static_cast<RendererHandleId>(opaque_id), handle);
    api_lock.unlock();
    // Worker joins and owner destructors are forbidden under registry/api/state locks.
    live.reset();
    retired.reset();
    return erased;
}

std::size_t handle_registry_size() {
    std::lock_guard<std::mutex> lock(g_handle_registry_mutex);
    return g_handle_registry.size();
}

std::array<std::int64_t, 15> run_handle_registry_self_test() {
    const auto registry_before = static_cast<std::int64_t>(handle_registry_size());
    const auto creates_before = g_handle_wrapper_create_count.load(std::memory_order_acquire);
    const auto destroys_before = g_handle_wrapper_destroy_count.load(std::memory_order_acquire);

    auto first = std::make_shared<RendererHandle>();
    std::weak_ptr<RendererHandle> first_weak = first;
    const RendererHandleId first_id = register_handle(first);
    std::mutex barrier_mutex;
    std::condition_variable barrier_condition;
    bool caller_ready = false;
    bool caller_acquired = false;
    bool caller_resume = false;
    bool caller_saw_destroyed = false;
    std::thread caller([&] {
        auto pre_acquired = acquire_handle(static_cast<jlong>(first_id));
        {
            std::unique_lock<std::mutex> lock(barrier_mutex);
            caller_acquired = pre_acquired != nullptr;
            caller_ready = true;
            barrier_condition.notify_all();
            barrier_condition.wait(lock, [&] { return caller_resume; });
        }
        if (pre_acquired) {
            std::shared_lock<std::shared_mutex> api_lock(pre_acquired->api_gate);
            std::lock_guard<std::mutex> state_lock(pre_acquired->state_mutex);
            caller_saw_destroyed = pre_acquired->mode == NativeHandleMode::DESTROYED;
        }
    });
    {
        std::unique_lock<std::mutex> lock(barrier_mutex);
        barrier_condition.wait(lock, [&] { return caller_ready; });
    }
    const bool first_destroyed = destroy_registered_handle(static_cast<jlong>(first_id));
    const bool first_missing = !acquire_handle(static_cast<jlong>(first_id));
    {
        std::lock_guard<std::mutex> lock(barrier_mutex);
        caller_resume = true;
    }
    barrier_condition.notify_all();
    caller.join();
    first.reset();
    const bool first_wrapper_destroyed = first_weak.expired();

    auto proof_handle = std::make_shared<RendererHandle>();
    std::weak_ptr<RendererHandle> proof_weak = proof_handle;
    proof_handle->mode = NativeHandleMode::RETIRED_PROOF_ONLY;
    proof_handle->retired = std::make_unique<RetiredBackendProofStore>();
    const AuthorityKey proof_key{991, 7, 13};
    FrozenAuthorityReleaseProof proof;
    proof.token.key = proof_key;
    proof.lifecycle = AuthorityLifecycle::RELEASING_UNCLAIMED;
    proof_handle->retired->authority_proofs.emplace(proof_key, std::move(proof));
    const RendererHandleId proof_id = register_handle(proof_handle);
    const bool reject_preserved =
        !destroy_registered_handle(static_cast<jlong>(proof_id));
    auto retained = acquire_handle(static_cast<jlong>(proof_id));
    const bool retained_after_reject = retained == proof_handle;
    {
        std::shared_lock<std::shared_mutex> api_lock(proof_handle->api_gate);
        std::lock_guard<std::mutex> state_lock(proof_handle->state_mutex);
        proof_handle->retired->authority_proofs.at(proof_key).lifecycle =
            AuthorityLifecycle::RELEASED;
    }
    retained.reset();
    const bool proof_destroyed = destroy_registered_handle(static_cast<jlong>(proof_id));
    const bool proof_missing = !acquire_handle(static_cast<jlong>(proof_id));
    proof_handle.reset();
    const bool proof_wrapper_destroyed = proof_weak.expired();

    const auto registry_after = static_cast<std::int64_t>(handle_registry_size());
    const auto create_delta =
        g_handle_wrapper_create_count.load(std::memory_order_acquire) - creates_before;
    const auto destroy_delta =
        g_handle_wrapper_destroy_count.load(std::memory_order_acquire) - destroys_before;
    const bool passed = first_id > 0 && caller_acquired && first_destroyed &&
        caller_saw_destroyed && first_missing && first_wrapper_destroyed &&
        proof_id > first_id && reject_preserved && retained_after_reject &&
        proof_destroyed && proof_missing && proof_wrapper_destroyed &&
        registry_after == registry_before && create_delta == 2 && destroy_delta == 2;
    return {{
        static_cast<std::int64_t>(first_id > 0),
        static_cast<std::int64_t>(caller_acquired),
        static_cast<std::int64_t>(first_destroyed),
        static_cast<std::int64_t>(caller_saw_destroyed),
        static_cast<std::int64_t>(first_missing),
        static_cast<std::int64_t>(first_wrapper_destroyed),
        static_cast<std::int64_t>(reject_preserved),
        static_cast<std::int64_t>(retained_after_reject),
        static_cast<std::int64_t>(proof_destroyed),
        static_cast<std::int64_t>(proof_missing),
        static_cast<std::int64_t>(proof_wrapper_destroyed),
        static_cast<std::int64_t>(registry_after == registry_before),
        create_delta,
        destroy_delta,
        static_cast<std::int64_t>(passed),
    }};
}

std::array<std::int64_t, 14> run_release_protocol_serial_self_test() {
    std::uint64_t first_engine = 0;
    std::array<std::int64_t, 5> first{};
    for (auto& value : first) value = next_release_protocol_serial(first_engine);
    std::uint64_t foreign_engine = 0;
    std::int64_t foreign_last = 0;
    for (int index = 0; index < 37; ++index) {
        foreign_last = next_release_protocol_serial(foreign_engine);
    }
    std::uint64_t second_engine = 0;
    std::array<std::int64_t, 5> second{};
    for (auto& value : second) value = next_release_protocol_serial(second_engine);
    RetiredBackendProofStore frozen;
    frozen.release_protocol_serial_watermark = 4;
    const std::int64_t late_claim = next_release_protocol_serial(
        frozen.release_protocol_serial_watermark);
    const bool independent = first == second && late_claim == 5 &&
        frozen.release_protocol_serial_watermark == 5 && foreign_last == 37;
    return {{
        first[0], first[1], first[2], first[3], first[4], foreign_last,
        second[0], second[1], second[2], second[3], second[4], late_claim,
        static_cast<std::int64_t>(frozen.release_protocol_serial_watermark),
        static_cast<std::int64_t>(independent),
    }};
}

std::array<std::int64_t, 7> run_release_callback_ordering_self_test() {
    std::array<std::int64_t, 5> events{};
    std::size_t event_count = 0;
    std::int64_t external_completion_count = 0;
    const auto record = [&](std::int64_t event) {
        if (event_count < events.size()) events[event_count++] = event;
    };
    // Event 1 is the engine-local feedback-barrier allocation that precedes publication.
    record(1);
    const bool terminalized = publish_release_metadata_then_terminalize(
        [&] {
            record(2);
            // Event 3 is immediately before the metadata publisher returns to the sequencer.
            record(3);
        },
        [&] {
            record(4);
            return true;
        },
        [&] { record(5); });
    // No external completion is invoked by the production sequencing helper.
    const bool passed = terminalized && event_count == events.size() &&
        events == std::array<std::int64_t, 5>{{1, 2, 3, 4, 5}} &&
        external_completion_count == 0;
    return {{
        events[0], events[1], events[2], events[3], events[4],
        external_completion_count, static_cast<std::int64_t>(passed),
    }};
}

std::array<std::int64_t, 16> run_surface_control_schema11_self_test() {
    using ntk::present::FixedFrameIdentity;
    using ntk::present::FixedLatchSource;
    using ntk::present::FixedPresentEvent;
    using ntk::present::FixedPresentEventKind;
    using ntk::present::FixedPresentJoinResult;
    using ntk::present::FixedPresentJoinState;

    const FixedFrameIdentity identity{
        1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 2, 12, 13};
    const bool identity_exact = ntk::present::exactIdentity(identity, identity);

    FixedPresentEvent latch{};
    latch.kind = FixedPresentEventKind::COMPOSITOR_LATCHED;
    latch.identity = identity;
    latch.latchSource = FixedLatchSource::ANDROID_SURFACE_CONTROL_ON_COMMIT;
    latch.eventSequence = 14;
    latch.latchNanos = 100;
    latch.callbackObservedNanos = 101;
    latch.onCommitCallbackCount = 1;

    SwappyFixedRetirementTelemetryV2 retirement{};
    retirement.structSize = sizeof(retirement);
    retirement.version = SWAPPY_FIXED_RETIREMENT_TELEMETRY_V2_VERSION;
    retirement.workGeneration = identity.workGeneration;
    retirement.ntkFrameId = identity.ntkFrameId;
    retirement.engineGeneration = identity.engineGeneration;
    retirement.surfaceEpoch = identity.surfaceEpoch;
    retirement.authorityGeneration = identity.authorityGeneration;
    retirement.authority = identity.authority;
    retirement.frameSequence = identity.frameSequence;
    retirement.admissionSequence = identity.admissionSequence;
    retirement.capsuleSequence = identity.capsuleSequence;
    retirement.retirementSequence = 15;
    retirement.backendSurfaceSerial = identity.backendSurfaceSerial;
    retirement.transactionSerial = identity.transactionSerial;
    retirement.bufferSlot = identity.bufferSlot;
    retirement.bufferGeneration = identity.bufferGeneration;
    retirement.frameTimelineVsyncId = identity.frameTimelineVsyncId;
    retirement.plannedTargetFrame = 16;
    retirement.targetReachedNanos = 102;
    retirement.callbackPublishedNanos = 103;
    retirement.targetWaitCount = 1;
    retirement.targetRebaseCount = 0;
    retirement.state = SWAPPY_FIXED_RETIREMENT_RETIRED;

    FixedPresentJoinState join;
    const bool committed = join.commit(identity);
    const bool latch_accepted = join.acceptLatch(latch);
    const bool waiting_after_latch =
        join.result() == FixedPresentJoinResult::WAITING;
    const bool retirement_accepted = join.acceptRetirement(retirement);
    const bool qualified = join.result() == FixedPresentJoinResult::QUALIFIED;

    FixedPresentJoinState invalid_join;
    const bool invalid_committed = invalid_join.commit(identity);
    FixedPresentEvent wrong_latch = latch;
    ++wrong_latch.identity.transactionSerial;
    const bool wrong_latch_rejected = !invalid_join.acceptLatch(wrong_latch);
    const bool wrong_latch_fatal =
        invalid_join.result() == FixedPresentJoinResult::FATAL;

    SwappyFixedExternalClaim claim{};
    claim.structSize = sizeof(claim);
    claim.version = SWAPPY_FIXED_EXTERNAL_CLAIM_VERSION;
    claim.claimToken = 20;
    claim.workGeneration = identity.workGeneration;
    claim.admissionSequence = identity.admissionSequence;
    claim.reservationSequence = 21;
    claim.opportunitySequence = 22;
    claim.plannedTargetFrame = retirement.plannedTargetFrame;
    claim.frameTimelineVsyncId = identity.frameTimelineVsyncId;
    claim.decisionNanos = 100;
    claim.ntkFrameId = identity.ntkFrameId;
    claim.engineGeneration = identity.engineGeneration;
    claim.surfaceEpoch = identity.surfaceEpoch;
    claim.authorityGeneration = identity.authorityGeneration;
    claim.authority = identity.authority;
    claim.frameSequence = identity.frameSequence;
    claim.capsuleSequence = identity.capsuleSequence;
    claim.backendSurfaceSerial = identity.backendSurfaceSerial;
    claim.transactionSerial = identity.transactionSerial;
    claim.bufferSlot = identity.bufferSlot;
    claim.bufferGeneration = identity.bufferGeneration;
    claim.acquireFenceSerial = 25;
    claim.transportProfileDigest = 23;
    claim.timingGeneration = 24;
    claim.transportBoundNanos = 50;
    claim.prepareBeginNanos = 90;
    claim.prepareEndNanos = 95;
    claim.initialDecisionNanos = 100;
    claim.claimReturnNanos = 101;
    claim.transportAdmissionOutcome = 2;
    claim.setBufferCount = 0;
    claim.acquireFenceDupCount = 2;
    claim.setBufferPending = 1;
    claim.firstStage = 1;
    claim.priorRetirementProof =
        swappy::emptyFixedPriorRetirementProof();
    claim.previousAppliedBufferRef =
        swappy::emptyFixedAppliedBufferRef();
    const bool claim_exact = swappy::fixedExternalClaimExact(claim, claim);
    SwappyFixedExternalClaim wrong_claim = claim;
    ++wrong_claim.opportunitySequence;
    const bool wrong_claim_rejected =
        !swappy::fixedExternalClaimExact(wrong_claim, claim);

    SwappyFixedExternalSubmission submission{};
    submission.structSize = sizeof(submission);
    submission.version = SWAPPY_FIXED_EXTERNAL_SUBMISSION_VERSION;
    submission.claimToken = claim.claimToken;
    submission.workGeneration = identity.workGeneration;
    submission.ntkFrameId = identity.ntkFrameId;
    submission.engineGeneration = identity.engineGeneration;
    submission.surfaceEpoch = identity.surfaceEpoch;
    submission.authorityGeneration = identity.authorityGeneration;
    submission.authority = identity.authority;
    submission.frameSequence = identity.frameSequence;
    submission.admissionSequence = identity.admissionSequence;
    submission.capsuleSequence = identity.capsuleSequence;
    submission.backendSurfaceSerial = identity.backendSurfaceSerial;
    submission.transactionSerial = identity.transactionSerial;
    submission.bufferSlot = identity.bufferSlot;
    submission.bufferGeneration = identity.bufferGeneration;
    submission.acquireFenceSerial = claim.acquireFenceSerial;
    submission.frameTimelineVsyncId = identity.frameTimelineVsyncId;
    submission.gpuRenderBeginNanos = 80;
    submission.gpuRenderEndNanos = 81;
    submission.gpuFenceIssuedNanos = 82;
    submission.gpuFenceWaitReturnNanos = 83;
    submission.transactionApplyBeginNanos = 102;
    submission.transactionApplyEndNanos = 120;
    submission.setBufferCount = 1;
    submission.acquireFenceDupCount = 2;
    submission.frameworkTransferCount = 1;
    submission.rendererGpuClientWaitCount = 0;
    submission.setFrameTimelineCount = 1;
    submission.transactionApplyCount = 1;
    submission.transportProfileDigest = claim.transportProfileDigest;
    submission.timingGeneration = claim.timingGeneration;
    submission.transportBoundNanos = claim.transportBoundNanos;
    submission.transactionPrepareBeginNanos = claim.prepareBeginNanos;
    submission.transactionPrepareEndNanos = claim.prepareEndNanos;
    submission.previousAppliedBufferRef =
        swappy::emptyFixedAppliedBufferRef();
    submission.appliedBufferRef =
        swappy::emptyFixedAppliedBufferRef();
    submission.appliedBufferRef.appliedBufferRefSerial = 26;
    submission.appliedBufferRef.identity.engineGeneration =
        identity.engineGeneration;
    submission.appliedBufferRef.identity.surfaceEpoch =
        identity.surfaceEpoch;
    submission.appliedBufferRef.identity.authorityGeneration =
        identity.authorityGeneration;
    submission.appliedBufferRef.identity.authority = identity.authority;
    submission.appliedBufferRef.identity.workGeneration =
        identity.workGeneration;
    submission.appliedBufferRef.identity.ntkFrameId = identity.ntkFrameId;
    submission.appliedBufferRef.identity.frameSequence =
        identity.frameSequence;
    submission.appliedBufferRef.identity.admissionSequence =
        identity.admissionSequence;
    submission.appliedBufferRef.identity.capsuleSequence =
        identity.capsuleSequence;
    submission.appliedBufferRef.identity.backendSurfaceSerial =
        identity.backendSurfaceSerial;
    submission.appliedBufferRef.identity.transactionSerial =
        identity.transactionSerial;
    submission.appliedBufferRef.identity.bufferSlot = identity.bufferSlot;
    submission.appliedBufferRef.identity.bufferGeneration =
        identity.bufferGeneration;
    submission.appliedBufferRef.identity.frameTimelineVsyncId =
        identity.frameTimelineVsyncId;
    submission.firstStage = 1;
    submission.applyDisposition = SWAPPY_FIXED_EXTERNAL_APPLIED;
    const bool submission_exact =
        swappy::fixedExternalSubmissionExact(claim, submission);
    SwappyFixedExternalSubmission wrong_submission = submission;
    wrong_submission.transactionApplyCount = 2;
    const bool wrong_submission_rejected =
        !swappy::fixedExternalSubmissionExact(claim, wrong_submission);

    const bool passed =
        ntk::present::kFixedPresentEventSchemaVersion == 11 &&
        ntk::present::HardwareBufferRenderTargetPool::kSlotCount == 8 &&
        identity_exact && committed && latch_accepted && waiting_after_latch &&
        retirement_accepted && qualified && invalid_committed &&
        wrong_latch_rejected && wrong_latch_fatal && claim_exact &&
        wrong_claim_rejected && submission_exact && wrong_submission_rejected;

    return {{
        ntk::present::kFixedPresentEventSchemaVersion,
        static_cast<std::int64_t>(
            ntk::present::HardwareBufferRenderTargetPool::kSlotCount),
        static_cast<std::int64_t>(identity_exact),
        static_cast<std::int64_t>(committed),
        static_cast<std::int64_t>(latch_accepted),
        static_cast<std::int64_t>(waiting_after_latch),
        static_cast<std::int64_t>(retirement_accepted),
        static_cast<std::int64_t>(qualified),
        static_cast<std::int64_t>(invalid_committed),
        static_cast<std::int64_t>(wrong_latch_rejected),
        static_cast<std::int64_t>(wrong_latch_fatal),
        static_cast<std::int64_t>(claim_exact),
        static_cast<std::int64_t>(wrong_claim_rejected),
        static_cast<std::int64_t>(submission_exact),
        static_cast<std::int64_t>(wrong_submission_rejected),
        static_cast<std::int64_t>(passed),
    }};
}

std::string retired_authority_digest_test_vectors() {
    const auto make_proof = [](std::int64_t authority_generation,
                               std::int64_t authority,
                               std::int64_t manifest_revision,
                               std::string manifest_digest,
                               std::string geometry_digest) {
        FrozenAuthorityReleaseProof proof;
        proof.token.key = AuthorityKey{7, authority_generation, authority};
        proof.token.manifest_revision = manifest_revision;
        proof.token.manifest_digest = std::move(manifest_digest);
        proof.token.geometry_digest = std::move(geometry_digest);
        return proof;
    };
    const auto token_one = make_proof(
        3, 101, 11, std::string(64, '0'), std::string(64, '1'));
    const auto token_two = make_proof(
        4, 202, 12, std::string(64, 'a'), std::string(64, 'f'));
    std::map<AuthorityKey, FrozenAuthorityReleaseProof> forward;
    forward.emplace(token_one.token.key, token_one);
    forward.emplace(token_two.token.key, token_two);
    std::map<AuthorityKey, FrozenAuthorityReleaseProof> reverse;
    reverse.emplace(token_two.token.key, token_two);
    reverse.emplace(token_one.token.key, token_one);
    auto mutated_token = token_two;
    mutated_token.token.geometry_digest[0] = 'e';
    std::map<AuthorityKey, FrozenAuthorityReleaseProof> mutated;
    mutated.emplace(token_one.token.key, token_one);
    mutated.emplace(mutated_token.token.key, std::move(mutated_token));
    return retired_authority_digest(forward) + "\n" +
        retired_authority_digest(reverse) + "\n" +
        retired_authority_digest(mutated);
}

std::array<std::int64_t, 9> run_retired_authority_selection_self_test() {
    const AuthorityKey historical_key{7, 3, 101};
    const AuthorityKey selected_key{7, 4, 202};
    RetiredAuthoritySelection selection;
    selection.keys.emplace(selected_key);

    FrozenAuthorityReleaseProof historical;
    historical.token.key = historical_key;
    historical.token.manifest_revision = 11;
    historical.token.manifest_digest = std::string(64, '0');
    historical.token.geometry_digest = std::string(64, '1');
    historical.lifecycle = AuthorityLifecycle::RELEASED;
    FrozenAuthorityReleaseProof selected;
    selected.token.key = selected_key;
    selected.token.manifest_revision = 12;
    selected.token.manifest_digest = std::string(64, 'a');
    selected.token.geometry_digest = std::string(64, 'f');
    selected.lifecycle = AuthorityLifecycle::RELEASING_UNCLAIMED;

    std::map<AuthorityKey, FrozenAuthorityReleaseProof> all;
    all.emplace(historical_key, historical);
    all.emplace(selected_key, selected);
    std::map<AuthorityKey, FrozenAuthorityReleaseProof> filtered;
    for (const auto& entry : all) {
        const auto disposition = classify_retired_tracker_for_selection(
            selection, entry.first, entry.second.lifecycle);
        if (disposition == RetiredTrackerSelection::INCLUDE) {
            filtered.emplace(entry.first, entry.second);
        }
    }
    std::map<AuthorityKey, FrozenAuthorityReleaseProof> expected;
    expected.emplace(selected_key, selected);
    selection.full_token_digest = retired_authority_digest(expected);

    const auto historical_disposition = classify_retired_tracker_for_selection(
        selection, historical_key, AuthorityLifecycle::RELEASED);
    const auto selected_released_disposition = classify_retired_tracker_for_selection(
        selection, selected_key, AuthorityLifecycle::RELEASED);
    const auto selected_unclaimed_disposition = classify_retired_tracker_for_selection(
        selection, selected_key, AuthorityLifecycle::RELEASING_UNCLAIMED);
    const auto unselected_unclaimed_disposition = classify_retired_tracker_for_selection(
        selection, historical_key, AuthorityLifecycle::RELEASING_UNCLAIMED);
    const auto unselected_claimed_disposition = classify_retired_tracker_for_selection(
        selection, historical_key, AuthorityLifecycle::RELEASING_CLAIMED);
    const bool digest_matches =
        retired_authority_digest(filtered) == selection.full_token_digest;
    const bool all_digest_differs =
        retired_authority_digest(all) != selection.full_token_digest;
    const bool passed =
        historical_disposition ==
            RetiredTrackerSelection::EXCLUDE_HISTORICAL_RELEASED &&
        selected_released_disposition == RetiredTrackerSelection::INCLUDE &&
        selected_unclaimed_disposition == RetiredTrackerSelection::INCLUDE &&
        unselected_unclaimed_disposition == RetiredTrackerSelection::FAIL &&
        unselected_claimed_disposition == RetiredTrackerSelection::FAIL &&
        filtered.size() == 1U && digest_matches && all_digest_differs;
    return {{
        static_cast<std::int64_t>(historical_disposition),
        static_cast<std::int64_t>(selected_released_disposition),
        static_cast<std::int64_t>(selected_unclaimed_disposition),
        static_cast<std::int64_t>(unselected_unclaimed_disposition),
        static_cast<std::int64_t>(unselected_claimed_disposition),
        static_cast<std::int64_t>(filtered.size()),
        static_cast<std::int64_t>(digest_matches),
        static_cast<std::int64_t>(all_digest_differs),
        static_cast<std::int64_t>(passed),
    }};
}

jobject make_native_detach_result(
        JNIEnv* env, const char* disposition_name, std::int64_t engine_generation,
        std::uint64_t surface_epoch, std::int64_t backend_retirement_serial,
        std::int64_t backend_retired_nanos, int retired_authority_count,
        const std::string& retired_authority_digest_value) {
    if (env == nullptr || disposition_name == nullptr || engine_generation <= 0 ||
        surface_epoch == 0) return nullptr;
    constexpr char kDispositionClass[] =
        "ml/melun/mangaview/reader/NtkNativeDetachDisposition";
    constexpr char kResultClass[] =
        "ml/melun/mangaview/reader/NtkNativeDetachResult";
    jclass disposition_class = env->FindClass(kDispositionClass);
    jclass result_class = env->FindClass(kResultClass);
    if (disposition_class == nullptr || result_class == nullptr) {
        if (disposition_class != nullptr) env->DeleteLocalRef(disposition_class);
        if (result_class != nullptr) env->DeleteLocalRef(result_class);
        return nullptr;
    }
    jfieldID disposition_field = env->GetStaticFieldID(
        disposition_class, disposition_name,
        "Lml/melun/mangaview/reader/NtkNativeDetachDisposition;");
    jobject disposition = disposition_field != nullptr
        ? env->GetStaticObjectField(disposition_class, disposition_field) : nullptr;
    jmethodID constructor = env->GetMethodID(
        result_class, "<init>",
        "(Lml/melun/mangaview/reader/NtkNativeDetachDisposition;JJJJILjava/lang/String;IIIIIII)V");
    jstring digest = env->NewStringUTF(retired_authority_digest_value.c_str());
    jobject result = nullptr;
    if (disposition != nullptr && constructor != nullptr && digest != nullptr) {
        result = env->NewObject(
            result_class, constructor, disposition,
            static_cast<jlong>(engine_generation),
            static_cast<jlong>(surface_epoch),
            static_cast<jlong>(backend_retirement_serial),
            static_cast<jlong>(backend_retired_nanos),
            static_cast<jint>(retired_authority_count), digest,
            static_cast<jint>(0), static_cast<jint>(0), static_cast<jint>(0),
            static_cast<jint>(0), static_cast<jint>(0), static_cast<jint>(0),
            static_cast<jint>(0));
    }
    if (digest != nullptr) env->DeleteLocalRef(digest);
    if (disposition != nullptr) env->DeleteLocalRef(disposition);
    env->DeleteLocalRef(disposition_class);
    env->DeleteLocalRef(result_class);
    return result;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_ml_melun_mangaview_reader_NtkStripNativeBridge_nativeCreate(
        JNIEnv* env, jobject, jobject activity, jobject callback,
        jstring qualification_profile_id, jlong engine_generation) {
    if (activity == nullptr || callback == nullptr || engine_generation <= 0) return 0;
    if (g_fail_next_native_create_for_testing.exchange(false, std::memory_order_acq_rel)) {
        return 0;
    }
    std::string profile_id;
    if (qualification_profile_id != nullptr) {
        const char* value = env->GetStringUTFChars(qualification_profile_id, nullptr);
        if (value != nullptr) {
            profile_id = value;
            env->ReleaseStringUTFChars(qualification_profile_id, value);
        }
    }
    auto handle = std::make_shared<RendererHandle>();
    handle->engine_generation = engine_generation;
    handle->live = std::make_unique<StripRenderer>(
        env, activity, callback, std::move(profile_id), engine_generation);
    if (!handle->live || !handle->live->initialization_valid() ||
        env->ExceptionCheck()) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        handle->live.reset();
        return 0;
    }
    const RendererHandleId id = register_handle(handle);
    return id == 0 ? 0 : static_cast<jlong>(id);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_ml_melun_mangaview_reader_NtkStripNativeBridge_nativeAwaitDetachedWarm(
        JNIEnv* env, jobject, jlong handle) {
    if (env == nullptr) return nullptr;
    LiveRendererCall renderer_call(handle);
    StripRenderer* renderer = renderer_call.get();
    if (renderer == nullptr) return nullptr;
    const auto proof = renderer->await_detached_warm();
    jlongArray result = env->NewLongArray(static_cast<jsize>(proof.size()));
    if (result == nullptr) return nullptr;
    env->SetLongArrayRegion(
        result, 0, static_cast<jsize>(proof.size()),
        reinterpret_cast<const jlong*>(proof.data()));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        env->DeleteLocalRef(result);
        return nullptr;
    }
    return result;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_ml_melun_mangaview_reader_NtkStripNativeBridge_nativeRunHandleRegistrySelfTest(
        JNIEnv* env, jobject) {
    const auto values = run_handle_registry_self_test();
    jlongArray result = env->NewLongArray(static_cast<jsize>(values.size()));
    if (result != nullptr) {
        env->SetLongArrayRegion(
            result, 0, static_cast<jsize>(values.size()),
            reinterpret_cast<const jlong*>(values.data()));
    }
    return result;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_ml_melun_mangaview_reader_NtkStripNativeBridge_nativeRunReleaseProtocolSelfTest(
        JNIEnv* env, jobject) {
    const auto values = run_release_protocol_serial_self_test();
    jlongArray result = env->NewLongArray(static_cast<jsize>(values.size()));
    if (result != nullptr) {
        env->SetLongArrayRegion(
            result, 0, static_cast<jsize>(values.size()),
            reinterpret_cast<const jlong*>(values.data()));
    }
    return result;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_ml_melun_mangaview_reader_NtkStripNativeBridge_nativeRunReleaseCallbackOrderingSelfTest(
        JNIEnv* env, jobject) {
    const auto values = run_release_callback_ordering_self_test();
    jlongArray result = env->NewLongArray(static_cast<jsize>(values.size()));
    if (result != nullptr) {
        env->SetLongArrayRegion(
            result, 0, static_cast<jsize>(values.size()),
            reinterpret_cast<const jlong*>(values.data()));
    }
    return result;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_ml_melun_mangaview_reader_NtkStripNativeBridge_nativeRunSurfaceControlSchema11SelfTest(
        JNIEnv* env, jobject) {
    const auto values = run_surface_control_schema11_self_test();
    jlongArray result = env->NewLongArray(static_cast<jsize>(values.size()));
    if (result != nullptr) {
        env->SetLongArrayRegion(result, 0, static_cast<jsize>(values.size()),
                                reinterpret_cast<const jlong*>(values.data()));
    }
    return result;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_ml_melun_mangaview_reader_NtkStripNativeBridge_nativeRunRetiredAuthoritySelectionSelfTest(
        JNIEnv* env, jobject) {
    const auto values = run_retired_authority_selection_self_test();
    jlongArray result = env->NewLongArray(static_cast<jsize>(values.size()));
    if (result != nullptr) {
        env->SetLongArrayRegion(
            result, 0, static_cast<jsize>(values.size()),
            reinterpret_cast<const jlong*>(values.data()));
    }
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_ml_melun_mangaview_reader_NtkStripNativeBridge_nativeRetiredAuthorityDigestVectorsForTesting(
        JNIEnv* env, jobject) {
    const std::string vectors = retired_authority_digest_test_vectors();
    return env->NewStringUTF(vectors.c_str());
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_ml_melun_mangaview_reader_NtkStripNativeBridge_nativeDebugHandleRegistryCounters(
        JNIEnv* env, jobject) {
    const std::array<std::int64_t, 4> values{{
        static_cast<std::int64_t>(handle_registry_size()),
        static_cast<std::int64_t>(g_next_handle_id.load(std::memory_order_acquire)),
        g_handle_wrapper_create_count.load(std::memory_order_acquire),
        g_handle_wrapper_destroy_count.load(std::memory_order_acquire),
    }};
    jlongArray result = env->NewLongArray(static_cast<jsize>(values.size()));
    if (result != nullptr) {
        env->SetLongArrayRegion(
            result, 0, static_cast<jsize>(values.size()),
            reinterpret_cast<const jlong*>(values.data()));
    }
    return result;
}

extern "C" JNIEXPORT jlong JNICALL
Java_ml_melun_mangaview_reader_NtkStripNativeBridge_nativeAcquireSurfaceLease(
        JNIEnv* env, jobject, jobject surface, jlong surface_epoch) {
    if (env == nullptr || surface == nullptr || surface_epoch <= 0) return 0;
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (window == nullptr) return 0;
    const std::uint64_t lease_id = native_surface_lease_registry().acquire(
        window, static_cast<std::uint64_t>(surface_epoch));
    if (lease_id == 0) ANativeWindow_release(window);
    return static_cast<jlong>(lease_id);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ml_melun_mangaview_reader_NtkStripNativeBridge_nativeReleaseSurfaceLease(
        JNIEnv*, jobject, jlong lease_id, jlong surface_epoch) {
    if (lease_id <= 0 || surface_epoch <= 0) return JNI_FALSE;
    return native_surface_lease_registry().release(
        static_cast<std::uint64_t>(lease_id),
        static_cast<std::uint64_t>(surface_epoch))
        ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ml_melun_mangaview_reader_NtkStripNativeBridge_nativeQueueAttachLease(
        JNIEnv*, jobject, jlong handle, jlong lease_id, jlong attach_generation,
        jint width, jint height, jlong geometry_revision,
        jlong refresh_period_ns, jlong surface_epoch) {
    if (lease_id <= 0 || attach_generation <= 0 ||
        geometry_revision <= 0 || surface_epoch <= 0) {
        return JNI_FALSE;
    }
    LiveRendererCall renderer_call(handle);
    StripRenderer* renderer = renderer_call.get();
    if (renderer == nullptr) return JNI_FALSE;
    auto transfer = native_surface_lease_registry().transfer(
        static_cast<std::uint64_t>(lease_id),
        static_cast<std::uint64_t>(surface_epoch));
    if (!transfer.has_value()) return JNI_FALSE;
    return renderer->queue_attach_lease(
        std::move(*transfer),
        static_cast<std::uint64_t>(attach_generation),
        width, height,
        static_cast<std::uint64_t>(geometry_revision),
        static_cast<std::uint64_t>(refresh_period_ns),
        static_cast<std::uint64_t>(surface_epoch))
        ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_ml_melun_mangaview_reader_NtkStripNativeBridge_nativeAwaitAttach(
        JNIEnv* env, jobject, jlong handle, jlong attach_generation,
        jlong surface_epoch) {
    if (env == nullptr || attach_generation <= 0 || surface_epoch <= 0) {
        return nullptr;
    }
    LiveRendererCall renderer_call(handle);
    StripRenderer* renderer = renderer_call.get();
    if (renderer == nullptr) return nullptr;
    const NativeAttachResult result = renderer->await_attach(
        static_cast<std::uint64_t>(attach_generation),
        static_cast<std::uint64_t>(surface_epoch));
    const std::array<jlong, 7> values{{
        static_cast<jlong>(result.code),
        static_cast<jlong>(result.generation),
        static_cast<jlong>(result.surface_epoch),
        static_cast<jlong>(result.applied_geometry_revision),
        static_cast<jlong>(result.width),
        static_cast<jlong>(result.height),
        static_cast<jlong>(result.completed_ns),
    }};
    jlongArray output = env->NewLongArray(static_cast<jsize>(values.size()));
    if (output == nullptr) return nullptr;
    env->SetLongArrayRegion(
        output, 0, static_cast<jsize>(values.size()), values.data());
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        env->DeleteLocalRef(output);
        return nullptr;
    }
    return output;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ml_melun_mangaview_reader_NtkStripNativeBridge_nativeUpdateAttachGeometry(
        JNIEnv*, jobject, jlong handle, jlong attach_generation,
        jlong surface_epoch, jint width, jint height, jlong geometry_revision) {
    LiveRendererCall renderer_call(handle);
    StripRenderer* renderer = renderer_call.get();
    return renderer != nullptr && renderer->update_attach_geometry(
        static_cast<std::uint64_t>(attach_generation),
        static_cast<std::uint64_t>(surface_epoch),
        width, height, static_cast<std::uint64_t>(geometry_revision))
        ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_ml_melun_mangaview_reader_NtkStripNativeBridge_nativeApplyResizeBeforePublish(
        JNIEnv* env, jobject, jlong handle, jlong attach_generation,
        jlong surface_epoch, jlong geometry_revision) {
    if (env == nullptr) return nullptr;
    LiveRendererCall renderer_call(handle);
    StripRenderer* renderer = renderer_call.get();
    if (renderer == nullptr) return nullptr;
    const NativeResizeAck ack = renderer->apply_resize_before_publish(
        static_cast<std::uint64_t>(attach_generation),
        static_cast<std::uint64_t>(surface_epoch),
        static_cast<std::uint64_t>(geometry_revision));
    const std::array<jlong, 6> values{{
        ack.success ? 1 : 0,
        static_cast<jlong>(ack.generation),
        static_cast<jlong>(ack.surface_epoch),
        static_cast<jlong>(ack.applied_geometry_revision),
        static_cast<jlong>(ack.width),
        static_cast<jlong>(ack.height),
    }};
    jlongArray output = env->NewLongArray(static_cast<jsize>(values.size()));
    if (output == nullptr) return nullptr;
    env->SetLongArrayRegion(
        output, 0, static_cast<jsize>(values.size()), values.data());
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        env->DeleteLocalRef(output);
        return nullptr;
    }
    return output;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ml_melun_mangaview_reader_NtkStripNativeBridge_nativePublishAttach(
        JNIEnv*, jobject, jlong handle, jlong attach_generation,
        jlong surface_epoch, jlong geometry_revision) {
    LiveRendererCall renderer_call(handle);
    StripRenderer* renderer = renderer_call.get();
    return renderer != nullptr && renderer->publish_attach(
        static_cast<std::uint64_t>(attach_generation),
        static_cast<std::uint64_t>(surface_epoch),
        static_cast<std::uint64_t>(geometry_revision))
        ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_ml_melun_mangaview_reader_NtkStripNativeBridge_nativeRequestSurfaceLoss(
        JNIEnv*, jobject, jlong handle, jlong attach_generation,
        jlong surface_epoch) {
    LiveRendererCall renderer_call(handle);
    StripRenderer* renderer = renderer_call.get();
    if (renderer == nullptr) {
        return static_cast<jint>(
            ntk::attach_generation::SurfaceLossDisposition::IDENTITY_MISMATCH);
    }
    return static_cast<jint>(renderer->request_surface_loss(
        static_cast<std::uint64_t>(attach_generation),
        static_cast<std::uint64_t>(surface_epoch)));
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_ml_melun_mangaview_reader_NtkStripNativeBridge_nativeDebugSurfaceLeaseCounters(
        JNIEnv* env, jobject) {
    if (env == nullptr) return nullptr;
    const std::array<jlong, 2> values{{
        static_cast<jlong>(native_surface_lease_registry().size()),
        static_cast<jlong>(
            g_renderer_owned_surface_lease_count.load(std::memory_order_acquire)),
    }};
    jlongArray output = env->NewLongArray(static_cast<jsize>(values.size()));
    if (output != nullptr) {
        env->SetLongArrayRegion(
            output, 0, static_cast<jsize>(values.size()), values.data());
    }
    return output;
}

extern "C" JNIEXPORT void JNICALL
Java_ml_melun_mangaview_reader_NtkStripNativeBridge_nativeFailNextCreateForTesting(
        JNIEnv*, jobject) {
    g_fail_next_native_create_for_testing.store(true, std::memory_order_release);
}

extern "C" JNIEXPORT void JNICALL
Java_ml_melun_mangaview_reader_NtkStripNativeBridge_nativeFailNextCallbackResolutionForTesting(
        JNIEnv*, jobject) {
    g_fail_next_callback_resolution_for_testing.store(true, std::memory_order_release);
}

extern "C" JNIEXPORT void JNICALL
Java_ml_melun_mangaview_reader_NtkStripNativeBridge_nativeSetContextLossForTesting(
        JNIEnv*, jobject, jlong handle) {
    LiveRendererCall renderer_call(handle);
    StripRenderer* renderer = renderer_call.get();
    if (renderer != nullptr) renderer->set_context_loss_for_testing();
}

extern "C" JNIEXPORT void JNICALL
Java_ml_melun_mangaview_reader_NtkStripNativeBridge_nativeSetContextLossDuringDetachForTesting(
        JNIEnv*, jobject, jlong handle) {
    LiveRendererCall renderer_call(handle);
    StripRenderer* renderer = renderer_call.get();
    if (renderer != nullptr) renderer->set_context_loss_during_detach_for_testing();
}

extern "C" JNIEXPORT jstring JNICALL
Java_ml_melun_mangaview_reader_NtkStripNativeBridge_nativeGpuSceneDigestVectorForTesting(
        JNIEnv* env, jobject) {
    if (env == nullptr) return nullptr;
    Sha256 digest;
    for (const std::string& token : std::vector<std::string>{
            "ntk-gpu-scene-v1", std::string(64U, 'a'), std::string(64U, 'b'),
            "RGBA8_UNORM", "2",
            "0", "0", "1080", "1024", "0", "1024", "4423680",
            "1", "0", "1080", "512", "1024", "1536", "2211840"}) {
        sha256_token(digest, token);
    }
    const std::string value = digest.finish();
    return env->NewStringUTF(value.c_str());
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_ml_melun_mangaview_reader_NtkStripNativeBridge_nativeOpenDetachedPreparation(
        JNIEnv* env, jobject, jlong handle, jlong authority,
        jlong authority_generation_candidate, jlong preparation_generation,
        jlong manifest_revision, jstring manifest_digest_string) {
    LiveRendererCall renderer_call(handle);
    StripRenderer* renderer = renderer_call.get();
    if (env == nullptr || renderer == nullptr || manifest_digest_string == nullptr) {
        return nullptr;
    }
    const char* chars = env->GetStringUTFChars(manifest_digest_string, nullptr);
    if (chars == nullptr) return nullptr;
    std::string manifest_digest(chars);
    env->ReleaseStringUTFChars(manifest_digest_string, chars);
    const PreparationOpenResult result = renderer->open_detached_preparation(
        authority, authority_generation_candidate, preparation_generation,
        manifest_revision, std::move(manifest_digest));
    if (result.authority_generation <= 0 || result.token_nonce <= 0 ||
        result.opened_ns <= 0) return nullptr;
    const std::array<jlong, 3> values{{
        result.authority_generation, result.token_nonce, result.opened_ns}};
    jlongArray output = env->NewLongArray(static_cast<jsize>(values.size()));
    if (output == nullptr) return nullptr;
    env->SetLongArrayRegion(output, 0, static_cast<jsize>(values.size()), values.data());
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        env->DeleteLocalRef(output);
        return nullptr;
    }
    return output;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ml_melun_mangaview_reader_NtkStripNativeBridge_nativeInstallDetachedPrepared(
        JNIEnv* env, jobject, jlong handle, jlong authority_generation,
        jlong authority, jlong preparation_generation, jlong admission_id,
        jint page, jint slot, jlong resource_revision, jlong install_lease,
        jlong rgba_bytes, jint width, jint height,
        jstring tile_proof_digest_string, jobject bitmap) {
    LiveRendererCall renderer_call(handle);
    StripRenderer* renderer = renderer_call.get();
    if (env == nullptr || renderer == nullptr ||
        tile_proof_digest_string == nullptr || bitmap == nullptr) return JNI_FALSE;
    const char* chars = env->GetStringUTFChars(tile_proof_digest_string, nullptr);
    if (chars == nullptr) return JNI_FALSE;
    std::string tile_proof_digest(chars);
    env->ReleaseStringUTFChars(tile_proof_digest_string, chars);
    return renderer->install_prepared(
        env, authority_generation, TileKey{authority, page, slot},
        preparation_generation, 0, true,
        admission_id, resource_revision, install_lease, rgba_bytes,
        width, height, std::move(tile_proof_digest), bitmap)
        ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ml_melun_mangaview_reader_NtkStripNativeBridge_nativeInstallSurfacePrepared(
        JNIEnv* env, jobject, jlong handle, jlong authority_generation,
        jlong authority, jlong preparation_generation, jlong surface_epoch,
        jlong admission_id, jint page, jint slot, jlong resource_revision,
        jlong install_lease, jlong rgba_bytes, jint width, jint height,
        jstring tile_proof_digest_string, jobject bitmap) {
    LiveRendererCall renderer_call(handle);
    StripRenderer* renderer = renderer_call.get();
    if (env == nullptr || renderer == nullptr ||
        tile_proof_digest_string == nullptr || bitmap == nullptr) return JNI_FALSE;
    const char* chars = env->GetStringUTFChars(tile_proof_digest_string, nullptr);
    if (chars == nullptr) return JNI_FALSE;
    std::string tile_proof_digest(chars);
    env->ReleaseStringUTFChars(tile_proof_digest_string, chars);
    return renderer->install_prepared(
        env, authority_generation, TileKey{authority, page, slot},
        preparation_generation, surface_epoch, false,
        admission_id, resource_revision, install_lease, rgba_bytes,
        width, height, std::move(tile_proof_digest), bitmap)
        ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_ml_melun_mangaview_reader_NtkStripNativeBridge_nativeAdoptDetachedPreparationToSurface(
        JNIEnv* env, jobject, jlong handle, jlong authority_generation,
        jlong authority, jlong preparation_generation,
        jlong demand_generation, jlong attach_generation,
        jlong surface_epoch, jlong geometry_revision,
        jlong manifest_revision,
        jstring manifest_digest_string, jstring geometry_digest_string,
        jstring pregeometry_root_digest_string,
        jstring prepared_inventory_digest_string, jint gpu_scene_format,
        jlong gpu_scene_logical_bytes, jstring gpu_scene_digest_string,
        jlong content_height, jint viewport_width, jint viewport_height,
        jlong scroll_top, jintArray pages_array, jintArray slots_array,
        jintArray widths_array, jintArray heights_array,
        jlongArray content_tops_array, jlongArray content_bottoms_array) {
    LiveRendererCall renderer_call(handle);
    StripRenderer* renderer = renderer_call.get();
    if (env == nullptr || renderer == nullptr || pages_array == nullptr ||
        slots_array == nullptr || widths_array == nullptr || heights_array == nullptr ||
        content_tops_array == nullptr || content_bottoms_array == nullptr ||
        manifest_digest_string == nullptr || geometry_digest_string == nullptr ||
        pregeometry_root_digest_string == nullptr ||
        prepared_inventory_digest_string == nullptr ||
        gpu_scene_digest_string == nullptr) return nullptr;
    const jsize count = env->GetArrayLength(pages_array);
    if (count <= 0 || env->GetArrayLength(slots_array) != count ||
        env->GetArrayLength(widths_array) != count ||
        env->GetArrayLength(heights_array) != count ||
        env->GetArrayLength(content_tops_array) != count ||
        env->GetArrayLength(content_bottoms_array) != count) return nullptr;
    std::vector<jint> pages(static_cast<std::size_t>(count));
    std::vector<jint> slots(static_cast<std::size_t>(count));
    std::vector<jint> widths(static_cast<std::size_t>(count));
    std::vector<jint> heights(static_cast<std::size_t>(count));
    std::vector<jlong> tops(static_cast<std::size_t>(count));
    std::vector<jlong> bottoms(static_cast<std::size_t>(count));
    env->GetIntArrayRegion(pages_array, 0, count, pages.data());
    env->GetIntArrayRegion(slots_array, 0, count, slots.data());
    env->GetIntArrayRegion(widths_array, 0, count, widths.data());
    env->GetIntArrayRegion(heights_array, 0, count, heights.data());
    env->GetLongArrayRegion(content_tops_array, 0, count, tops.data());
    env->GetLongArrayRegion(content_bottoms_array, 0, count, bottoms.data());
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }
    const char* manifest_chars =
        env->GetStringUTFChars(manifest_digest_string, nullptr);
    const char* geometry_chars =
        env->GetStringUTFChars(geometry_digest_string, nullptr);
    const char* pregeometry_chars =
        env->GetStringUTFChars(pregeometry_root_digest_string, nullptr);
    const char* prepared_chars =
        env->GetStringUTFChars(prepared_inventory_digest_string, nullptr);
    const char* gpu_chars =
        env->GetStringUTFChars(gpu_scene_digest_string, nullptr);
    if (manifest_chars == nullptr || geometry_chars == nullptr ||
        pregeometry_chars == nullptr || prepared_chars == nullptr ||
        gpu_chars == nullptr) {
        if (manifest_chars != nullptr) env->ReleaseStringUTFChars(
            manifest_digest_string, manifest_chars);
        if (geometry_chars != nullptr) env->ReleaseStringUTFChars(
            geometry_digest_string, geometry_chars);
        if (pregeometry_chars != nullptr) env->ReleaseStringUTFChars(
            pregeometry_root_digest_string, pregeometry_chars);
        if (prepared_chars != nullptr) env->ReleaseStringUTFChars(
            prepared_inventory_digest_string, prepared_chars);
        if (gpu_chars != nullptr) env->ReleaseStringUTFChars(
            gpu_scene_digest_string, gpu_chars);
        return nullptr;
    }
    std::string manifest_digest(manifest_chars);
    std::string geometry_digest(geometry_chars);
    std::string pregeometry_digest(pregeometry_chars);
    std::string prepared_digest(prepared_chars);
    std::string gpu_digest(gpu_chars);
    env->ReleaseStringUTFChars(manifest_digest_string, manifest_chars);
    env->ReleaseStringUTFChars(geometry_digest_string, geometry_chars);
    env->ReleaseStringUTFChars(pregeometry_root_digest_string, pregeometry_chars);
    env->ReleaseStringUTFChars(prepared_inventory_digest_string, prepared_chars);
    env->ReleaseStringUTFChars(gpu_scene_digest_string, gpu_chars);
    std::vector<PreallocateCommand> preallocations;
    preallocations.reserve(static_cast<std::size_t>(count));
    for (jsize index = 0; index < count; ++index) {
        preallocations.push_back(PreallocateCommand{
            TileKey{authority, pages[static_cast<std::size_t>(index)],
                    slots[static_cast<std::size_t>(index)]},
            authority_generation, static_cast<int>(index),
            widths[static_cast<std::size_t>(index)],
            heights[static_cast<std::size_t>(index)],
            tops[static_cast<std::size_t>(index)],
            bottoms[static_cast<std::size_t>(index)]});
    }
    const PreparedGeometryBindResult result =
        renderer->adopt_detached_preparation_to_surface(
        authority, authority_generation, preparation_generation,
        demand_generation, attach_generation, surface_epoch, geometry_revision,
        manifest_revision,
        std::move(manifest_digest), std::move(geometry_digest),
        std::move(pregeometry_digest), std::move(prepared_digest),
        gpu_scene_format, gpu_scene_logical_bytes, std::move(gpu_digest),
        content_height, viewport_width, viewport_height, scroll_top,
        preallocations);
    if (result.authority_generation <= 0 || result.completion_ns <= 0 ||
        !is_sha256(result.prepared_inventory_digest) ||
        !is_sha256(result.resident_inventory_digest)) return nullptr;
    const std::array<std::string, 7> values{{
        std::to_string(result.authority_generation),
        std::to_string(result.adopted_count),
        std::to_string(result.missing_count),
        result.prepared_inventory_digest,
        result.resident_inventory_digest,
        std::to_string(result.completion_ns),
        std::to_string(result.last_resource_completion_ns)}};
    jclass string_class = env->FindClass("java/lang/String");
    if (string_class == nullptr) return nullptr;
    jobjectArray output = env->NewObjectArray(
        static_cast<jsize>(values.size()), string_class, nullptr);
    env->DeleteLocalRef(string_class);
    if (output == nullptr) return nullptr;
    for (std::size_t index = 0; index < values.size(); ++index) {
        jstring value = env->NewStringUTF(values[index].c_str());
        if (value == nullptr) {
            env->DeleteLocalRef(output);
            return nullptr;
        }
        env->SetObjectArrayElement(output, static_cast<jsize>(index), value);
        env->DeleteLocalRef(value);
    }
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        env->DeleteLocalRef(output);
        return nullptr;
    }
    return output;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ml_melun_mangaview_reader_NtkStripNativeBridge_nativeClosePreparationAdmissions(
        JNIEnv*, jobject, jlong handle, jlong authority_generation,
        jlong authority) {
    LiveRendererCall renderer_call(handle);
    StripRenderer* renderer = renderer_call.get();
    return renderer != nullptr && renderer->close_preparation_admissions(
        authority_generation, authority) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_ml_melun_mangaview_reader_NtkStripNativeBridge_nativeBind(
        JNIEnv* env, jobject, jlong handle, jlong authority,
        jlong authority_generation_candidate, jlong manifest_revision,
        jstring manifest_digest_string, jstring geometry_digest_string,
        jstring pregeometry_root_digest_string,
        jint gpu_scene_format, jlong gpu_scene_logical_bytes,
        jstring gpu_scene_digest_string, jlong content_height,
        jint viewport_width, jint viewport_height, jlong scroll_top,
        jintArray pages_array, jintArray slots_array,
        jintArray widths_array, jintArray heights_array,
        jlongArray content_tops_array, jlongArray content_bottoms_array) {
    LiveRendererCall renderer_call(handle);
    StripRenderer* renderer = renderer_call.get();
    if (renderer == nullptr || pages_array == nullptr || slots_array == nullptr ||
        widths_array == nullptr || heights_array == nullptr || content_tops_array == nullptr ||
        content_bottoms_array == nullptr || manifest_digest_string == nullptr ||
        geometry_digest_string == nullptr || pregeometry_root_digest_string == nullptr ||
        gpu_scene_digest_string == nullptr) return 0;
    const jsize count = env->GetArrayLength(pages_array);
    if (count <= 0 || env->GetArrayLength(slots_array) != count ||
        env->GetArrayLength(widths_array) != count ||
        env->GetArrayLength(heights_array) != count ||
        env->GetArrayLength(content_tops_array) != count ||
        env->GetArrayLength(content_bottoms_array) != count) return 0;
    std::vector<jint> pages(static_cast<std::size_t>(count));
    std::vector<jint> slots(static_cast<std::size_t>(count));
    std::vector<jint> widths(static_cast<std::size_t>(count));
    std::vector<jint> heights(static_cast<std::size_t>(count));
    std::vector<jlong> content_tops(static_cast<std::size_t>(count));
    std::vector<jlong> content_bottoms(static_cast<std::size_t>(count));
    env->GetIntArrayRegion(pages_array, 0, count, pages.data());
    env->GetIntArrayRegion(slots_array, 0, count, slots.data());
    env->GetIntArrayRegion(widths_array, 0, count, widths.data());
    env->GetIntArrayRegion(heights_array, 0, count, heights.data());
    env->GetLongArrayRegion(content_tops_array, 0, count, content_tops.data());
    env->GetLongArrayRegion(content_bottoms_array, 0, count, content_bottoms.data());
    if (env->ExceptionCheck()) {
        NTK_LOGE("JNI array extraction failed in nativeBind");
        env->ExceptionDescribe();
        env->ExceptionClear();
        return 0;
    }
    const char* manifest_chars = env->GetStringUTFChars(manifest_digest_string, nullptr);
    const char* geometry_chars = env->GetStringUTFChars(geometry_digest_string, nullptr);
    const char* pregeometry_chars =
        env->GetStringUTFChars(pregeometry_root_digest_string, nullptr);
    const char* gpu_scene_chars = env->GetStringUTFChars(gpu_scene_digest_string, nullptr);
    if (manifest_chars == nullptr || geometry_chars == nullptr ||
        pregeometry_chars == nullptr || gpu_scene_chars == nullptr) {
        if (manifest_chars != nullptr) {
            env->ReleaseStringUTFChars(manifest_digest_string, manifest_chars);
        }
        if (geometry_chars != nullptr) {
            env->ReleaseStringUTFChars(geometry_digest_string, geometry_chars);
        }
        if (pregeometry_chars != nullptr) {
            env->ReleaseStringUTFChars(
                pregeometry_root_digest_string, pregeometry_chars);
        }
        if (gpu_scene_chars != nullptr) {
            env->ReleaseStringUTFChars(gpu_scene_digest_string, gpu_scene_chars);
        }
        return 0;
    }
    std::string manifest_digest(manifest_chars);
    std::string geometry_digest(geometry_chars);
    std::string pregeometry_root_digest(pregeometry_chars);
    std::string gpu_scene_digest(gpu_scene_chars);
    env->ReleaseStringUTFChars(manifest_digest_string, manifest_chars);
    env->ReleaseStringUTFChars(geometry_digest_string, geometry_chars);
    env->ReleaseStringUTFChars(
        pregeometry_root_digest_string, pregeometry_chars);
    env->ReleaseStringUTFChars(gpu_scene_digest_string, gpu_scene_chars);
    std::vector<PreallocateCommand> preallocations;
    preallocations.reserve(static_cast<std::size_t>(count));
    for (jsize index = 0; index < count; ++index) {
        preallocations.push_back(PreallocateCommand{
            TileKey{authority, pages[static_cast<std::size_t>(index)],
                    slots[static_cast<std::size_t>(index)]},
            authority_generation_candidate,
            static_cast<int>(index),
            widths[static_cast<std::size_t>(index)],
            heights[static_cast<std::size_t>(index)],
            content_tops[static_cast<std::size_t>(index)],
            content_bottoms[static_cast<std::size_t>(index)]});
    }
    return renderer->bind(
        authority, authority_generation_candidate, manifest_revision,
        std::move(manifest_digest), std::move(geometry_digest),
        std::move(pregeometry_root_digest), gpu_scene_format,
        gpu_scene_logical_bytes, std::move(gpu_scene_digest), content_height,
        viewport_width, viewport_height, scroll_top, preallocations);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ml_melun_mangaview_reader_NtkStripNativeBridge_nativeDisarm(
        JNIEnv*, jobject, jlong handle, jlong authority_generation, jlong authority) {
    LiveRendererCall renderer_call(handle);
    StripRenderer* renderer = renderer_call.get();
    return renderer != nullptr && renderer->disarm(authority_generation, authority)
        ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ml_melun_mangaview_reader_NtkStripNativeBridge_nativeUpload(
        JNIEnv* env, jobject, jlong handle, jlong authority_generation,
        jlong authority, jlong surface_epoch,
        jlong admission_id, jint page, jint slot,
        jlong resource_revision, jlong install_lease, jlong rgba_bytes,
        jobject bitmap, jlong content_top, jlong content_bottom) {
    LiveRendererCall renderer_call(handle);
    StripRenderer* renderer = renderer_call.get();
    return renderer != nullptr && renderer->upload(
        env, authority_generation, TileKey{authority, page, slot}, surface_epoch, admission_id,
        resource_revision, install_lease, rgba_bytes, bitmap, content_top, content_bottom)
        ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ml_melun_mangaview_reader_NtkStripNativeBridge_nativeActivate(
        JNIEnv*, jobject, jlong handle, jlong authority_generation,
        jlong authority, jlong stage_nonce) {
    LiveRendererCall renderer_call(handle);
    StripRenderer* renderer = renderer_call.get();
    return renderer != nullptr && renderer->activate(
        authority_generation, authority, stage_nonce)
        ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ml_melun_mangaview_reader_NtkStripNativeBridge_nativeCommitProtection(
        JNIEnv* env, jobject, jlong handle, jlong authority_generation,
        jlong authority, jlong surface_epoch,
        jlong demand_epoch, jlong basis_frame_sequence, jlong basis_input_sequence,
        jint direction, jintArray protected_ordinals_array, jstring protected_digest_string) {
    LiveRendererCall renderer_call(handle);
    StripRenderer* renderer = renderer_call.get();
    if (renderer == nullptr || protected_ordinals_array == nullptr ||
        protected_digest_string == nullptr) return JNI_FALSE;
    const jsize count = env->GetArrayLength(protected_ordinals_array);
    std::vector<jint> values(static_cast<std::size_t>(count));
    if (count > 0) {
        env->GetIntArrayRegion(protected_ordinals_array, 0, count, values.data());
    }
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return JNI_FALSE;
    }
    const char* digest_chars = env->GetStringUTFChars(protected_digest_string, nullptr);
    if (digest_chars == nullptr) return JNI_FALSE;
    ProtectionCommit commit;
    commit.authority_generation = authority_generation;
    commit.authority = authority;
    commit.surface_epoch = surface_epoch;
    commit.demand_epoch = demand_epoch;
    commit.basis_frame_sequence = basis_frame_sequence;
    commit.basis_input_sequence = basis_input_sequence;
    commit.direction = direction;
    commit.protected_tile_ordinals.assign(values.begin(), values.end());
    commit.protected_digest = digest_chars;
    env->ReleaseStringUTFChars(protected_digest_string, digest_chars);
    return renderer->commit_protection(std::move(commit)) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ml_melun_mangaview_reader_NtkStripNativeBridge_nativeRetire(
        JNIEnv* env, jobject, jlong handle, jlong authority_generation,
        jlong authority, jlong surface_epoch, jlong policy_surface_epoch,
        jlong demand_epoch, jlong basis_frame_sequence, jlong basis_input_sequence,
        jint page, jint slot, jlong resource_revision, jlong install_lease,
        jlong retire_lease, jlong rgba_bytes, jstring protected_digest_string) {
    LiveRendererCall renderer_call(handle);
    StripRenderer* renderer = renderer_call.get();
    if (renderer == nullptr || protected_digest_string == nullptr) return JNI_FALSE;
    const char* digest_chars = env->GetStringUTFChars(protected_digest_string, nullptr);
    if (digest_chars == nullptr) return JNI_FALSE;
    RetireIntent intent;
    intent.authority_generation = authority_generation;
    intent.authority = authority;
    intent.surface_epoch = surface_epoch;
    intent.policy_surface_epoch = policy_surface_epoch;
    intent.demand_epoch = demand_epoch;
    intent.basis_frame_sequence = basis_frame_sequence;
    intent.basis_input_sequence = basis_input_sequence;
    intent.key = TileKey{authority, page, slot};
    intent.resource_revision = resource_revision;
    intent.install_lease = install_lease;
    intent.retire_lease = retire_lease;
    intent.rgba_bytes = rgba_bytes;
    intent.protected_digest = digest_chars;
    env->ReleaseStringUTFChars(protected_digest_string, digest_chars);
    return renderer->retire(std::move(intent)) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ml_melun_mangaview_reader_NtkStripNativeBridge_nativeStage(
        JNIEnv*, jobject, jlong handle, jlong authority_generation,
        jlong authority, jlong corridor_start,
        jlong corridor_end, jlong stage_nonce) {
    LiveRendererCall renderer_call(handle);
    StripRenderer* renderer = renderer_call.get();
    return renderer != nullptr && renderer->stage(
        authority_generation, authority, corridor_start, corridor_end, stage_nonce)
        ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_ml_melun_mangaview_reader_NtkStripNativeBridge_nativeTouch(
        JNIEnv*, jobject, jlong handle, jlong authority_generation, jlong authority,
        jint action, jlong event_time_ns, jfloat x, jfloat y,
        jint pointer_id) {
    const std::int64_t main_ingress_ns = monotonic_now_ns();
    LiveRendererCall renderer_call(handle);
    StripRenderer* renderer = renderer_call.get();
    return renderer != nullptr
        ? static_cast<jlong>(renderer->touch(
            authority_generation, authority, action, event_time_ns,
            main_ingress_ns, x, y, pointer_id))
        : 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_ml_melun_mangaview_reader_NtkStripNativeBridge_nativeReleaseAuthority(
        JNIEnv* env, jobject, jlong handle, jobject callback, jlong engine_generation,
        jlong authority_generation, jlong authority, jlong reducer_surface_epoch,
        jlong release_nonce) {
    const auto renderer_handle = acquire_handle(handle);
    if (!renderer_handle) return kReleaseRejected;
    NativeHandleMode mode = NativeHandleMode::DESTROYED;
    {
        std::shared_lock<std::shared_mutex> api_lock(renderer_handle->api_gate);
        StripRenderer* live = nullptr;
        {
            std::lock_guard<std::mutex> lock(renderer_handle->state_mutex);
            mode = renderer_handle->mode;
            if (mode == NativeHandleMode::LIVE) live = renderer_handle->live.get();
        }
        if (mode == NativeHandleMode::LIVE) {
            return live != nullptr && live->release_authority(
                engine_generation, authority_generation, authority,
                reducer_surface_epoch, release_nonce)
                ? kReleaseAcceptedAsync : kReleaseRejected;
        }
    }
    if (mode == NativeHandleMode::RETIRED_PROOF_ONLY) {
        return claim_retired_authority_proof_synchronously(
            renderer_handle, env, callback, engine_generation, authority_generation,
            authority, reducer_surface_epoch, release_nonce);
    }
    return kReleaseRejected;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_ml_melun_mangaview_reader_NtkStripNativeBridge_nativeDebugLifecycleCounters(
        JNIEnv* env, jobject, jlong handle) {
    LiveRendererCall renderer_call(handle);
    StripRenderer* renderer = renderer_call.get();
    if (renderer == nullptr) return nullptr;
    auto counters = renderer->debug_lifecycle_counters();
    // Slot 7 is the public opaque registry identity, never a process address.
    counters[7] = static_cast<std::int64_t>(handle);
    jlongArray result = env->NewLongArray(static_cast<jsize>(counters.size()));
    if (result != nullptr) {
        env->SetLongArrayRegion(result, 0, static_cast<jsize>(counters.size()),
                                reinterpret_cast<const jlong*>(counters.data()));
    }
    return result;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_ml_melun_mangaview_reader_NtkStripNativeBridge_nativeDebugStartupLifecycle(
        JNIEnv* env, jobject, jlong handle) {
    LiveRendererCall renderer_call(handle);
    StripRenderer* renderer = renderer_call.get();
    if (renderer == nullptr) return nullptr;
    const auto counters = renderer->debug_startup_lifecycle();
    jlongArray result = env->NewLongArray(static_cast<jsize>(counters.size()));
    if (result != nullptr) {
        env->SetLongArrayRegion(
            result, 0, static_cast<jsize>(counters.size()),
            reinterpret_cast<const jlong*>(counters.data()));
    }
    return result;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_ml_melun_mangaview_reader_NtkStripNativeBridge_nativeDebugSchedulerCounters(
        JNIEnv* env, jobject, jlong handle) {
    LiveRendererCall renderer_call(handle);
    StripRenderer* renderer = renderer_call.get();
    if (renderer == nullptr) return nullptr;
    const auto counters = renderer->debug_scheduler_counters();
    jlongArray result = env->NewLongArray(static_cast<jsize>(counters.size()));
    if (result != nullptr) {
        env->SetLongArrayRegion(result, 0, static_cast<jsize>(counters.size()),
                                reinterpret_cast<const jlong*>(counters.data()));
    }
    return result;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_ml_melun_mangaview_reader_NtkStripNativeBridge_nativeDebugAuthorityInventory(
        JNIEnv* env, jobject, jlong handle, jlong authority_generation, jlong authority) {
    LiveRendererCall renderer_call(handle);
    StripRenderer* renderer = renderer_call.get();
    if (renderer == nullptr) return nullptr;
    const auto inventory = renderer->debug_authority_inventory(
        authority_generation, authority);
    jlongArray result = env->NewLongArray(static_cast<jsize>(inventory.size()));
    if (result != nullptr) {
        env->SetLongArrayRegion(result, 0, static_cast<jsize>(inventory.size()),
                                reinterpret_cast<const jlong*>(inventory.data()));
    }
    return result;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_ml_melun_mangaview_reader_NtkStripNativeBridge_nativeDebugRetiredBackend(
        JNIEnv* env, jobject, jlong handle) {
    const auto renderer_handle = acquire_handle(handle);
    if (!renderer_handle) return nullptr;
    std::shared_lock<std::shared_mutex> api_lock(renderer_handle->api_gate);
    std::array<std::int64_t, 10> snapshot{};
    {
        std::lock_guard<std::mutex> lock(renderer_handle->state_mutex);
        snapshot[0] = static_cast<std::int64_t>(renderer_handle->mode);
        if (renderer_handle->mode == NativeHandleMode::RETIRED_PROOF_ONLY &&
            renderer_handle->retired) {
            snapshot[1] = renderer_handle->retired->backend_retirement_serial;
            snapshot[2] = renderer_handle->retired->backend_retired_nanos;
            // Indices 3..9 are actual remaining owner counts. Proof-only is all zero.
        } else if (renderer_handle->mode == NativeHandleMode::LIVE &&
                   renderer_handle->live) {
            snapshot[3] = 3;  // render, upload, feedback
            snapshot[4] = 1;  // live backend may own EGL handles
            snapshot[6] = 1;  // backend-scoped Swappy lease
            snapshot[7] = 1;  // callback global ref
        }
    }
    jlongArray result = env->NewLongArray(static_cast<jsize>(snapshot.size()));
    if (result != nullptr) {
        env->SetLongArrayRegion(result, 0, static_cast<jsize>(snapshot.size()),
                                reinterpret_cast<const jlong*>(snapshot.data()));
    }
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_ml_melun_mangaview_reader_NtkStripNativeBridge_nativeResetInputTelemetry(
        JNIEnv*, jobject, jlong handle) {
    LiveRendererCall renderer_call(handle);
    StripRenderer* renderer = renderer_call.get();
    if (renderer != nullptr) renderer->reset_input_telemetry();
}

extern "C" JNIEXPORT jlong JNICALL
Java_ml_melun_mangaview_reader_NtkStripNativeBridge_nativeFirstMainIngressNanos(
        JNIEnv*, jobject, jlong handle) {
    LiveRendererCall renderer_call(handle);
    StripRenderer* renderer = renderer_call.get();
    return renderer != nullptr ? static_cast<jlong>(renderer->first_main_ingress_ns()) : 0;
}

extern "C" JNIEXPORT jlong JNICALL
Java_ml_melun_mangaview_reader_NtkStripNativeBridge_nativeLatestSuccessfulSwapInputEventNanos(
        JNIEnv*, jobject, jlong handle) {
    LiveRendererCall renderer_call(handle);
    StripRenderer* renderer = renderer_call.get();
    return renderer != nullptr
        ? static_cast<jlong>(renderer->latest_successful_swap_input_event_ns()) : 0;
}

extern "C" JNIEXPORT jlong JNICALL
Java_ml_melun_mangaview_reader_NtkStripNativeBridge_nativeLatestDeliveredLatchedInputEventNanos(
        JNIEnv*, jobject, jlong handle) {
    LiveRendererCall renderer_call(handle);
    StripRenderer* renderer = renderer_call.get();
    return renderer != nullptr
        ? static_cast<jlong>(renderer->latest_delivered_latched_input_event_ns()) : 0;
}

extern "C" JNIEXPORT jlong JNICALL
Java_ml_melun_mangaview_reader_NtkStripNativeBridge_nativePreSubmitViewportGap(
        JNIEnv*, jobject, jlong handle) {
    LiveRendererCall renderer_call(handle);
    StripRenderer* renderer = renderer_call.get();
    return renderer != nullptr
        ? static_cast<jlong>(renderer->pre_submit_viewport_gap()) : 0;
}

extern "C" JNIEXPORT void JNICALL
Java_ml_melun_mangaview_reader_NtkStripNativeBridge_nativeResize(
        JNIEnv*, jobject, jlong handle, jlong attach_generation,
        jlong surface_epoch, jint width, jint height) {
    LiveRendererCall renderer_call(handle);
    StripRenderer* renderer = renderer_call.get();
    if (renderer != nullptr) {
        renderer->resize(
            static_cast<std::uint64_t>(attach_generation),
            static_cast<std::uint64_t>(surface_epoch),
            width, height);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_ml_melun_mangaview_reader_NtkStripNativeBridge_nativeRequestRender(
        JNIEnv*, jobject, jlong handle) {
    LiveRendererCall renderer_call(handle);
    StripRenderer* renderer = renderer_call.get();
    if (renderer != nullptr) renderer->request_render();
}

extern "C" JNIEXPORT jobject JNICALL
Java_ml_melun_mangaview_reader_NtkStripNativeBridge_nativeDetach(
        JNIEnv* env, jobject, jlong handle, jobject callback, jlong surface_epoch,
        jlongArray exact_authority_keys_array,
        jstring exact_authority_digest_string) {
    const auto renderer_handle = acquire_handle(handle);
    if (!renderer_handle || callback == nullptr || surface_epoch <= 0) return nullptr;
    RetiredAuthoritySelection expected_authorities;
    if (!parse_retired_authority_selection(
            env, exact_authority_keys_array, exact_authority_digest_string,
            renderer_handle->engine_generation, &expected_authorities)) {
        return make_native_detach_result(
            env, "FAILED", renderer_handle->engine_generation,
            static_cast<std::uint64_t>(surface_epoch), 0, 0, 0, "");
    }
    StripRenderer* renderer = nullptr;
    {
        std::unique_lock<std::shared_mutex> api_lock(renderer_handle->api_gate);
        std::lock_guard<std::mutex> lock(renderer_handle->state_mutex);
        if (renderer_handle->mode == NativeHandleMode::LIVE) {
            renderer = renderer_handle->live.get();
            renderer_handle->mode = NativeHandleMode::CONTEXT_LOSS_RETIRING;
        }
    }
    if (renderer == nullptr) {
        return make_native_detach_result(
            env, "FAILED", renderer_handle->engine_generation,
            static_cast<std::uint64_t>(surface_epoch), 0, 0, 0, "");
    }
    const auto epoch = static_cast<std::uint64_t>(surface_epoch);
    bool context_lost = renderer->has_pending_context_loss(epoch);
    const bool preserved = !context_lost && renderer->detach(epoch);
    if (preserved) {
        bool restored_live = false;
        {
            std::unique_lock<std::shared_mutex> api_lock(renderer_handle->api_gate);
            std::lock_guard<std::mutex> lock(renderer_handle->state_mutex);
            if (renderer_handle->mode == NativeHandleMode::CONTEXT_LOSS_RETIRING &&
                renderer_handle->live.get() == renderer) {
                renderer_handle->mode = NativeHandleMode::LIVE;
                restored_live = true;
            }
        }
        return make_native_detach_result(
            env, restored_live ? "SURFACE_PRESERVED" : "FAILED",
            renderer_handle->engine_generation, epoch, 0, 0, 0, "");
    }
    // detach_window itself can discover EGL_CONTEXT_LOST. Reclassify in the same transaction
    // and synchronously retire the complete backend instead of returning a consumed fatal as a
    // generic live-handle failure.
    context_lost = context_lost || renderer->has_pending_context_loss(epoch);
    if (!context_lost) {
        {
            std::unique_lock<std::shared_mutex> api_lock(renderer_handle->api_gate);
            std::lock_guard<std::mutex> lock(renderer_handle->state_mutex);
            if (renderer_handle->mode == NativeHandleMode::CONTEXT_LOSS_RETIRING &&
                renderer_handle->live.get() == renderer) {
                renderer_handle->mode = NativeHandleMode::LIVE;
            }
        }
        return make_native_detach_result(
            env, "FAILED", renderer_handle->engine_generation, epoch, 0, 0, 0, "");
    }
    if (!retire_handle_context_loss_on_detach(
            renderer_handle, renderer, epoch, expected_authorities)) {
        return make_native_detach_result(
            env, "FAILED", renderer_handle->engine_generation, epoch, 0, 0, 0, "");
    }
    std::int64_t retirement_serial = 0;
    std::int64_t retired_nanos = 0;
    int retired_authority_count = 0;
    std::string authority_digest;
    {
        std::shared_lock<std::shared_mutex> api_lock(renderer_handle->api_gate);
        std::lock_guard<std::mutex> lock(renderer_handle->state_mutex);
        if (!renderer_handle->retired) return nullptr;
        retirement_serial = renderer_handle->retired->backend_retirement_serial;
        retired_nanos = renderer_handle->retired->backend_retired_nanos;
        retired_authority_count = static_cast<int>(
            renderer_handle->retired->authority_proofs.size());
        authority_digest = renderer_handle->retired->retired_authority_digest;
    }
    if (!dispatch_preclaimed_retired_proofs(renderer_handle, env, callback)) {
        NTK_LOGE("context-loss detach failed to publish a preclaimed release proof");
        return make_native_detach_result(
            env, "FAILED", renderer_handle->engine_generation, epoch, 0, 0, 0, "");
    }
    return make_native_detach_result(
        env, "CONTEXT_LOST_RETIRED", renderer_handle->engine_generation, epoch,
        retirement_serial, retired_nanos, retired_authority_count, authority_digest);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ml_melun_mangaview_reader_NtkStripNativeBridge_nativeDestroy(
        JNIEnv*, jobject, jlong handle) {
    return destroy_registered_handle(handle) ? JNI_TRUE : JNI_FALSE;
}
