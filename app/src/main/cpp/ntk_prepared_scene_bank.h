#pragma once

#include <algorithm>
#include <cstdint>
#include <map>
#include <string>
#include <tuple>

namespace ntk::prepared_scene {

enum class Phase : std::uint8_t {
    EMPTY = 0,
    OPEN = 1,
    SURFACE_BOUND = 2,
    ADMISSIONS_CLOSED = 3,
    FAILED = 4,
    RETIRED = 5,
};

enum class GeometryBindDrainDisposition : std::uint8_t {
    READY = 0,
    WAIT = 1,
    REJECT = 2,
};

struct GeometryBindDrainSnapshot {
    std::size_t native_outstanding = 0;
    bool upload_active = false;
    std::size_t upload_queue_depth = 0;
    bool in_flight_upload = false;
    std::size_t ready_tile_depth = 0;
    int upload_commands_submitting = 0;
    int upload_gpu_fences_pending = 0;
    bool ledger_install_in_flight = false;
};

constexpr GeometryBindDrainDisposition classifyGeometryBindDrain(
        const GeometryBindDrainSnapshot& snapshot) noexcept {
    if (snapshot.upload_commands_submitting < 0 ||
        snapshot.upload_gpu_fences_pending < 0) {
        return GeometryBindDrainDisposition::REJECT;
    }
    return snapshot.native_outstanding != 0U || snapshot.upload_active ||
            snapshot.upload_queue_depth != 0U || snapshot.in_flight_upload ||
            snapshot.ready_tile_depth != 0U ||
            snapshot.upload_commands_submitting != 0 ||
            snapshot.upload_gpu_fences_pending != 0 ||
            snapshot.ledger_install_in_flight
        ? GeometryBindDrainDisposition::WAIT
        : GeometryBindDrainDisposition::READY;
}

struct Key {
    int page = -1;
    int slot = -1;

    bool operator<(const Key& other) const noexcept {
        return std::tie(page, slot) < std::tie(other.page, other.slot);
    }
    bool operator==(const Key& other) const noexcept {
        return page == other.page && slot == other.slot;
    }
};

struct Install {
    Key key;
    std::int64_t admission_id = 0;
    std::int64_t resource_revision = 0;
    std::int64_t install_lease = 0;
    std::int64_t rgba_bytes = 0;
    int width = 0;
    int height = 0;
    std::string tile_proof_digest;
};

struct Record {
    Install install;
    std::int64_t completion_ns = 0;
};

struct SurfaceAdoption {
    std::int64_t demand_generation = 0;
    std::int64_t attach_generation = 0;
    std::int64_t surface_epoch = 0;
    std::int64_t geometry_revision = 0;
    int surface_width = 0;
    int surface_height = 0;
    std::size_t geometry_tile_count = 0;
    std::string pregeometry_root_digest;
    std::string expected_inventory_digest;
};

class Ledger final {
public:
    static bool isSha256(const std::string& value) noexcept {
        return value.size() == 64U && std::all_of(
            value.begin(), value.end(), [](char digit) {
                return (digit >= '0' && digit <= '9') ||
                    (digit >= 'a' && digit <= 'f');
            });
    }

    bool open(std::int64_t authority_generation, std::int64_t authority,
              std::int64_t preparation_generation, std::int64_t manifest_revision,
              std::string manifest_digest, std::int64_t opened_ns) {
        if (phase_ != Phase::EMPTY || authority_generation <= 0 || authority <= 0 ||
            preparation_generation <= 0 || manifest_revision < 0 ||
            !isSha256(manifest_digest) || opened_ns <= 0) return fail();
        authority_generation_ = authority_generation;
        authority_ = authority;
        preparation_generation_ = preparation_generation;
        manifest_revision_ = manifest_revision;
        manifest_digest_ = std::move(manifest_digest);
        opened_ns_ = opened_ns;
        phase_ = Phase::OPEN;
        return true;
    }

    bool beginInstall(const Install& install) {
        if (phase_ != Phase::OPEN || in_flight_ || !validInstall(install) ||
            records_.find(install.key) != records_.end()) return fail();
        in_flight_ = true;
        pending_ = install;
        return true;
    }

    bool finishInstall(const Install& install, std::int64_t completion_ns) {
        if (phase_ != Phase::OPEN || !in_flight_ || !sameInstall(pending_, install) ||
            completion_ns <= 0 || completion_ns < opened_ns_ ||
            records_.find(install.key) != records_.end()) return fail();
        records_.emplace(install.key, Record{install, completion_ns});
        last_completion_ns_ = std::max(last_completion_ns_, completion_ns);
        pending_ = Install{};
        in_flight_ = false;
        return true;
    }

    bool rejectInstall(const Install& install) {
        if (phase_ != Phase::OPEN || !in_flight_ || !sameInstall(pending_, install)) {
            return fail();
        }
        pending_ = Install{};
        in_flight_ = false;
        return fail();
    }

    bool beginSurfaceAdoption(const SurfaceAdoption& adoption) {
        if (phase_ != Phase::OPEN || in_flight_ || adoption_started_ ||
            adoption.demand_generation <= 0 || adoption.attach_generation <= 0 ||
            adoption.surface_epoch <= 0 || adoption.geometry_revision <= 0 ||
            adoption.surface_width <= 0 || adoption.surface_height <= 0 ||
            adoption.geometry_tile_count == 0U ||
            records_.size() > adoption.geometry_tile_count ||
            !isSha256(adoption.pregeometry_root_digest) ||
            !isSha256(adoption.expected_inventory_digest)) return fail();
        adoption_ = adoption;
        adoption_started_ = true;
        return true;
    }

    bool finishSurfaceAdoption(std::size_t adopted_count, std::size_t missing_count,
                               std::int64_t completion_ns) {
        if (phase_ != Phase::OPEN || in_flight_ || !adoption_started_ ||
            adoption_.geometry_tile_count == 0U ||
            adopted_count != records_.size() ||
            adopted_count + missing_count != adoption_.geometry_tile_count ||
            completion_ns <= 0 || completion_ns < last_completion_ns_) return fail();
        surface_adoption_completion_ns_ = completion_ns;
        phase_ = Phase::SURFACE_BOUND;
        return true;
    }

    bool closeAdmissions() {
        if (phase_ != Phase::SURFACE_BOUND || in_flight_) return fail();
        phase_ = Phase::ADMISSIONS_CLOSED;
        return true;
    }

    bool retire() {
        if (phase_ == Phase::RETIRED || phase_ == Phase::EMPTY || in_flight_) return fail();
        phase_ = Phase::RETIRED;
        return true;
    }

    bool fail() noexcept {
        phase_ = Phase::FAILED;
        return false;
    }

    Phase phase() const noexcept { return phase_; }
    bool inFlight() const noexcept { return in_flight_; }
    std::int64_t authorityGeneration() const noexcept { return authority_generation_; }
    std::int64_t authority() const noexcept { return authority_; }
    std::int64_t preparationGeneration() const noexcept {
        return preparation_generation_;
    }
    std::int64_t manifestRevision() const noexcept { return manifest_revision_; }
    const std::string& manifestDigest() const noexcept { return manifest_digest_; }
    const std::string& pregeometryRootDigest() const noexcept {
        return adoption_.pregeometry_root_digest;
    }
    const std::string& expectedInventoryDigest() const noexcept {
        return adoption_.expected_inventory_digest;
    }
    const SurfaceAdoption& surfaceAdoption() const noexcept { return adoption_; }
    std::int64_t openedNanos() const noexcept { return opened_ns_; }
    std::int64_t lastCompletionNanos() const noexcept { return last_completion_ns_; }
    std::int64_t surfaceAdoptionCompletionNanos() const noexcept {
        return surface_adoption_completion_ns_;
    }
    std::size_t size() const noexcept { return records_.size(); }
    const std::map<Key, Record>& records() const noexcept { return records_; }

private:
    static bool validInstall(const Install& install) noexcept {
        if (install.key.page < 0 || install.key.slot < 0 ||
            install.admission_id <= 0 || install.resource_revision != 1 ||
            install.install_lease <= 0 || install.rgba_bytes <= 0 ||
            install.width <= 0 || install.height <= 0 ||
            !isSha256(install.tile_proof_digest)) return false;
        const std::int64_t pixels = static_cast<std::int64_t>(install.width) *
            static_cast<std::int64_t>(install.height);
        return pixels > 0 && pixels <= INT64_MAX / 4 &&
            install.rgba_bytes == pixels * 4;
    }

    static bool sameInstall(const Install& left, const Install& right) noexcept {
        return left.key == right.key && left.admission_id == right.admission_id &&
            left.resource_revision == right.resource_revision &&
            left.install_lease == right.install_lease &&
            left.rgba_bytes == right.rgba_bytes && left.width == right.width &&
            left.height == right.height &&
            left.tile_proof_digest == right.tile_proof_digest;
    }

    Phase phase_ = Phase::EMPTY;
    std::int64_t authority_generation_ = 0;
    std::int64_t authority_ = 0;
    std::int64_t preparation_generation_ = 0;
    std::int64_t manifest_revision_ = 0;
    std::string manifest_digest_;
    std::int64_t opened_ns_ = 0;
    std::int64_t last_completion_ns_ = 0;
    std::int64_t surface_adoption_completion_ns_ = 0;
    bool in_flight_ = false;
    bool adoption_started_ = false;
    Install pending_{};
    std::map<Key, Record> records_;
    SurfaceAdoption adoption_{};
};

}  // namespace ntk::prepared_scene
