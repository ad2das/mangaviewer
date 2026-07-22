#include "../ntk_gpu_scene_admission.h"

#include <cstdio>
#include <string>

namespace {

bool expect(bool condition, const char* message) {
    if (condition) return true;
    std::fprintf(stderr, "GpuSceneAdmissionLedgerTest: %s\n", message);
    return false;
}

GpuSceneAdmissionLedger storage_complete(const std::string& digest) {
    GpuSceneAdmissionLedger ledger;
    ledger.begin(GpuSceneFormat::RGBA8_UNORM, 3U, 120, digest);
    ledger.record_storage(40, 1U, false);
    ledger.record_storage(40, 2U, false);
    ledger.record_storage(40, 3U, true);
    return ledger;
}

}  // namespace

int main() {
    const std::string digest(64U, 'a');

    auto exact = storage_complete(digest);
    if (!expect(exact.state == GpuSceneAdmissionState::STORAGE_COMPLETE,
                "exact storage did not complete") ||
        !expect(exact.begin_upload(), "upload phase did not begin") ||
        !expect(exact.record_resident(40, 1U, {}), "resident 1 rejected") ||
        !expect(exact.record_resident(40, 2U, {}), "resident 2 rejected") ||
        !expect(exact.record_resident(40, 3U, digest), "resident 3 rejected") ||
        !expect(exact.state == GpuSceneAdmissionState::RESIDENT_COMPLETE,
                "exact resident inventory did not complete") ||
        !expect(exact.seal(100, 101), "exact seal rejected") ||
        !expect(exact.state == GpuSceneAdmissionState::SEALED,
                "exact ledger did not seal")) return 1;

    auto storage_oom = storage_complete(digest);
    std::uint64_t frame_id_reservations = 0;
    std::uint64_t swap_attempts = 0;
    storage_oom.fail();
    if (!expect(storage_oom.state == GpuSceneAdmissionState::FAILED,
                "simulated GL_OUT_OF_MEMORY was not sticky") ||
        !expect(frame_id_reservations == 0 && swap_attempts == 0,
                "allocation failure minted a frame ID or swap")) return 2;

    GpuSceneAdmissionLedger storage_mismatch;
    storage_mismatch.begin(GpuSceneFormat::RGBA8_UNORM, 3U, 120, digest);
    storage_mismatch.record_storage(40, 1U, false);
    storage_mismatch.record_storage(40, 2U, true);
    if (!expect(storage_mismatch.state == GpuSceneAdmissionState::FAILED,
                "short storage count was accepted")) return 3;

    auto duplicate = storage_complete(digest);
    duplicate.begin_upload();
    duplicate.record_resident(40, 1U, {});
    duplicate.record_resident(40, 2U, {});
    duplicate.record_resident(40, 3U, digest);
    duplicate.record_resident(40, 4U, digest);
    if (!expect(duplicate.state == GpuSceneAdmissionState::FAILED,
                "duplicate resident publication was accepted")) return 4;

    auto digest_mismatch = storage_complete(digest);
    digest_mismatch.begin_upload();
    digest_mismatch.record_resident(40, 1U, {});
    digest_mismatch.record_resident(40, 2U, {});
    digest_mismatch.record_resident(40, 3U, std::string(64U, 'b'));
    if (!expect(digest_mismatch.state == GpuSceneAdmissionState::FAILED,
                "resident digest mismatch was accepted")) return 5;

    auto bad_seal = storage_complete(digest);
    bad_seal.begin_upload();
    bad_seal.record_resident(40, 1U, {});
    bad_seal.record_resident(40, 2U, {});
    bad_seal.record_resident(40, 3U, digest);
    bad_seal.seal(101, 100);
    if (!expect(bad_seal.state == GpuSceneAdmissionState::FAILED,
                "seal preceding resource completion was accepted")) return 6;

    GpuSceneAdmissionLedger partial_adoption;
    if (!expect(partial_adoption.begin(
                    GpuSceneFormat::RGBA8_UNORM, 3U, 120, digest),
                "partial-adoption begin rejected") ||
        !expect(partial_adoption.record_adopted_resident(40),
                "prepared resident adoption rejected") ||
        !expect(partial_adoption.record_storage(40, 2U, false),
                "first missing storage rejected") ||
        !expect(partial_adoption.record_storage(40, 3U, true),
                "last missing storage rejected") ||
        !expect(partial_adoption.state == GpuSceneAdmissionState::STORAGE_COMPLETE,
                "partial adoption skipped missing uploads") ||
        !expect(partial_adoption.begin_upload(),
                "partial-adoption upload phase rejected") ||
        !expect(partial_adoption.record_resident(40, 2U, {}),
                "first post-bind resident rejected") ||
        !expect(partial_adoption.record_resident(40, 3U, digest),
                "last post-bind resident rejected") ||
        !expect(partial_adoption.exact_resident(),
                "partial adoption did not reach exact residency")) return 7;

    GpuSceneAdmissionLedger full_adoption;
    if (!expect(full_adoption.begin(
                    GpuSceneFormat::RGBA8_UNORM, 2U, 80, digest),
                "full-adoption begin rejected") ||
        !expect(full_adoption.record_adopted_resident(40),
                "full adoption tile one rejected") ||
        !expect(full_adoption.record_adopted_resident(40),
                "full adoption tile two rejected") ||
        !expect(full_adoption.record_storage(
                    0, 0U, true, 2U, digest) == false,
                "zero-byte synthetic storage close was accepted")) return 8;

    // Closing an all-adopted ledger is explicit and cannot invent a synthetic
    // storage record. The renderer uses this exact finalization operation.
    GpuSceneAdmissionLedger full_adoption_finalized;
    full_adoption_finalized.begin(GpuSceneFormat::RGBA8_UNORM, 2U, 80, digest);
    full_adoption_finalized.record_adopted_resident(40);
    full_adoption_finalized.record_adopted_resident(40);
    if (!expect(full_adoption_finalized.finish_adopted_storage(2U, digest),
                "full adoption finalization rejected") ||
        !expect(full_adoption_finalized.exact_resident(),
                "full adoption did not become exact resident")) return 9;

    std::puts("GpuSceneAdmissionLedgerTest PASS");
    return 0;
}
