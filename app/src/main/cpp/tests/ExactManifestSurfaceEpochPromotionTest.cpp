#include "../ntk_gpu_scene_admission.h"
#include "../ntk_prepared_scene_bank.h"

#include <cstdio>
#include <string>

namespace {

bool expect(bool value, const char* message) {
    if (value) return true;
    std::fprintf(stderr, "ExactManifestSurfaceEpochPromotionTest: %s\n", message);
    return false;
}

}  // namespace

int main() {
    using ntk::prepared_scene::Ledger;
    using ntk::prepared_scene::Phase;

    const std::int64_t preparation_generation = 31;
    const std::string manifest_digest(64U, 'a');
    const std::string conflicting_digest(64U, 'b');

    Ledger missing_manifest;
    if (!expect(!missing_manifest.open(
                    1, 1, preparation_generation, 7, "", 100),
                "manifest-less preparation token was accepted")) {
        return 1;
    }

    Ledger promoted;
    std::uint64_t preparation_token_count = 0;
    if (promoted.open(
            1, 1, preparation_generation, 7, manifest_digest, 100)) {
        ++preparation_token_count;
    }
    if (!expect(preparation_token_count == 1,
                "exact promotion did not mint exactly one preparation token") ||
        !expect(promoted.phase() == Phase::OPEN,
                "exact promotion did not open preparation") ||
        !expect(promoted.preparationGeneration() == preparation_generation,
                "promotion changed the preparation generation") ||
        !expect(promoted.manifestDigest() == manifest_digest,
                "preparation token lost the exact manifest digest")) {
        return 2;
    }

    // A second promotion on the same preparation generation with a different exact manifest is terminally
    // rejected by the production preparation ledger.
    if (!expect(!promoted.open(
                    2, 2, preparation_generation, 8, conflicting_digest, 101),
                "conflicting second manifest promotion was accepted") ||
        !expect(promoted.phase() == Phase::FAILED,
                "conflicting promotion was not fail-closed") ||
        !expect(preparation_token_count == 1,
                "conflicting promotion minted another token")) {
        return 3;
    }

    // Buffer submission is impossible until the exact GPU inventory is resident and sealed.
    GpuSceneAdmissionLedger scene;
    std::uint64_t buffer_submit_count = 0;
    if (!expect(scene.begin(
                    GpuSceneFormat::RGBA8_UNORM, 1U, 16, manifest_digest),
                "exact GPU scene begin failed") ||
        !expect(buffer_submit_count == 0,
                "buffer submitted before exact storage") ||
        !expect(scene.record_storage(16, 1U, true),
                "exact storage completion failed") ||
        !expect(scene.begin_upload(), "exact upload phase failed") ||
        !expect(scene.record_resident(16, 1U, manifest_digest),
                "exact resident completion failed") ||
        !expect(buffer_submit_count == 0,
                "buffer submitted before full exact stage proof") ||
        !expect(scene.seal(200, 201), "exact scene seal failed")) {
        return 4;
    }
    ++buffer_submit_count;
    if (!expect(buffer_submit_count == 1,
                "first buffer was not submitted exactly once after seal")) {
        return 5;
    }

    std::puts("ExactManifestSurfaceEpochPromotionTest PASS");
    return 0;
}
