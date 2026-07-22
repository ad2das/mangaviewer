#include "../ntk_gpu_scene_admission.h"
#include "../ntk_prepared_scene_bank.h"

#include <cstdio>

namespace {

bool expect(bool value, const char* message) {
    if (value) return true;
    std::fprintf(stderr, "UnownedSurfaceBootstrapAuthorityTest: %s\n", message);
    return false;
}

}  // namespace

int main() {
    // Surface/EGL infrastructure may exist before exact manifest promotion, but the production
    // preparation and GPU ledgers must remain unopened and therefore own no native authority.
    const bool surface_infrastructure_ready = true;
    ntk::prepared_scene::Ledger preparation;
    GpuSceneAdmissionLedger scene;
    const std::int64_t authority = preparation.authority();
    const std::int64_t scene_version = 0;
    const std::uint64_t buffer_submit_count = 0;

    if (!expect(surface_infrastructure_ready, "surface infrastructure was not ready") ||
        !expect(preparation.phase() == ntk::prepared_scene::Phase::EMPTY,
                "unowned preparation ledger was opened") ||
        !expect(authority == 0, "unowned bootstrap minted authority") ||
        !expect(scene.state == GpuSceneAdmissionState::EMPTY,
                "unowned bootstrap began a GPU scene") ||
        !expect(scene.resident_texture_count == 0U,
                "unowned bootstrap acquired a resident tile") ||
        !expect(scene_version == 0, "unowned bootstrap minted a scene version") ||
        !expect(buffer_submit_count == 0, "unowned bootstrap submitted a buffer")) {
        return 1;
    }

    std::puts("UnownedSurfaceBootstrapAuthorityTest PASS");
    return 0;
}
