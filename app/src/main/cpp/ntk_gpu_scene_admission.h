#pragma once

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <string>
#include <utility>

enum class GpuSceneAdmissionState : std::uint8_t {
    EMPTY = 0,
    STORAGE_PENDING = 1,
    STORAGE_COMPLETE = 2,
    UPLOAD_PENDING = 3,
    RESIDENT_COMPLETE = 4,
    SEALED = 5,
    FAILED = 6,
};

enum class GpuSceneFormat : std::uint8_t {
    RGBA8_UNORM = 0,
};

struct GpuSceneAdmissionLedger {
    GpuSceneAdmissionState state = GpuSceneAdmissionState::EMPTY;
    GpuSceneFormat format = GpuSceneFormat::RGBA8_UNORM;
    std::size_t expected_texture_count = 0;
    std::size_t preallocated_texture_count = 0;
    std::size_t resident_texture_count = 0;
    std::int64_t expected_logical_bytes = 0;
    std::int64_t preallocated_logical_bytes = 0;
    std::int64_t resident_logical_bytes = 0;
    std::string expected_digest;
    std::string resident_digest;

    static bool is_sha256(const std::string& value) {
        return value.size() == 64U && std::all_of(
            value.begin(), value.end(), [](char digit) {
                return (digit >= '0' && digit <= '9') ||
                    (digit >= 'a' && digit <= 'f');
            });
    }

    bool begin(
            GpuSceneFormat requested_format, std::size_t texture_count,
            std::int64_t logical_bytes, std::string digest) {
        if (state != GpuSceneAdmissionState::EMPTY ||
            requested_format != GpuSceneFormat::RGBA8_UNORM || texture_count == 0 ||
            logical_bytes <= 0 || !is_sha256(digest)) return fail();
        format = requested_format;
        expected_texture_count = texture_count;
        expected_logical_bytes = logical_bytes;
        expected_digest = std::move(digest);
        state = GpuSceneAdmissionState::STORAGE_PENDING;
        return true;
    }

    bool record_storage(
            std::int64_t logical_bytes, std::size_t current_storage_map_size,
            bool storage_queue_drained,
            std::size_t current_scene_size = 0,
            const std::string& exact_scene_digest_if_complete = {}) {
        if (state != GpuSceneAdmissionState::STORAGE_PENDING || logical_bytes <= 0 ||
            preallocated_texture_count >= expected_texture_count ||
            logical_bytes > expected_logical_bytes - preallocated_logical_bytes) {
            return fail();
        }
        ++preallocated_texture_count;
        preallocated_logical_bytes += logical_bytes;
        if (!storage_queue_drained) return true;
        if (preallocated_texture_count != expected_texture_count ||
            preallocated_logical_bytes != expected_logical_bytes ||
            current_storage_map_size != expected_texture_count) return fail();
        if (resident_texture_count == expected_texture_count) {
            resident_digest = exact_scene_digest_if_complete;
            if (resident_logical_bytes != expected_logical_bytes ||
                current_scene_size != expected_texture_count ||
                resident_digest != expected_digest) return fail();
            state = GpuSceneAdmissionState::RESIDENT_COMPLETE;
        } else {
            state = GpuSceneAdmissionState::STORAGE_COMPLETE;
        }
        return true;
    }

    // A preparation-bank texture already owns immutable RGBA8 storage and a
    // fence-complete resident image before final geometry exists. Geometry bind
    // adopts that exact name into the drawable scene; it must count as both
    // storage and residency without allocating or uploading a second texture.
    bool record_adopted_resident(std::int64_t logical_bytes) {
        if (state != GpuSceneAdmissionState::STORAGE_PENDING || logical_bytes <= 0 ||
            preallocated_texture_count >= expected_texture_count ||
            resident_texture_count >= expected_texture_count ||
            logical_bytes > expected_logical_bytes - preallocated_logical_bytes ||
            logical_bytes > expected_logical_bytes - resident_logical_bytes) return fail();
        ++preallocated_texture_count;
        preallocated_logical_bytes += logical_bytes;
        ++resident_texture_count;
        resident_logical_bytes += logical_bytes;
        return true;
    }

    bool finish_adopted_storage(
            std::size_t current_scene_size,
            const std::string& exact_scene_digest) {
        if (state != GpuSceneAdmissionState::STORAGE_PENDING ||
            preallocated_texture_count != expected_texture_count ||
            resident_texture_count != expected_texture_count ||
            preallocated_logical_bytes != expected_logical_bytes ||
            resident_logical_bytes != expected_logical_bytes ||
            current_scene_size != expected_texture_count ||
            exact_scene_digest != expected_digest) return fail();
        resident_digest = exact_scene_digest;
        state = GpuSceneAdmissionState::RESIDENT_COMPLETE;
        return true;
    }

    bool begin_upload() {
        if (state == GpuSceneAdmissionState::STORAGE_COMPLETE) {
            state = GpuSceneAdmissionState::UPLOAD_PENDING;
            return true;
        }
        if (state == GpuSceneAdmissionState::UPLOAD_PENDING) return true;
        return fail();
    }

    bool record_resident(
            std::int64_t logical_bytes, std::size_t current_scene_size,
            const std::string& exact_scene_digest_if_complete) {
        if (state != GpuSceneAdmissionState::UPLOAD_PENDING || logical_bytes <= 0 ||
            resident_texture_count >= expected_texture_count ||
            logical_bytes > expected_logical_bytes - resident_logical_bytes) return fail();
        ++resident_texture_count;
        resident_logical_bytes += logical_bytes;
        if (resident_texture_count != expected_texture_count) return true;
        resident_digest = exact_scene_digest_if_complete;
        if (resident_logical_bytes != expected_logical_bytes ||
            current_scene_size != expected_texture_count ||
            resident_digest != expected_digest) return fail();
        state = GpuSceneAdmissionState::RESIDENT_COMPLETE;
        return true;
    }

    bool exact_resident() const {
        return (state == GpuSceneAdmissionState::RESIDENT_COMPLETE ||
                state == GpuSceneAdmissionState::SEALED) &&
            format == GpuSceneFormat::RGBA8_UNORM &&
            expected_texture_count > 0 &&
            preallocated_texture_count == expected_texture_count &&
            resident_texture_count == expected_texture_count &&
            expected_logical_bytes > 0 &&
            preallocated_logical_bytes == expected_logical_bytes &&
            resident_logical_bytes == expected_logical_bytes &&
            is_sha256(expected_digest) && resident_digest == expected_digest;
    }

    bool seal(std::int64_t last_resource_completion_ns,
              std::int64_t seal_fence_completion_ns) {
        if (!exact_resident() || last_resource_completion_ns <= 0 ||
            seal_fence_completion_ns < last_resource_completion_ns) return fail();
        state = GpuSceneAdmissionState::SEALED;
        return true;
    }

    bool fail() {
        state = GpuSceneAdmissionState::FAILED;
        return false;
    }
};
