#include "../ntk_prepared_scene_bank.h"

#include <cstdio>
#include <string>

namespace {

using ntk::prepared_scene::Install;
using ntk::prepared_scene::GeometryBindDrainDisposition;
using ntk::prepared_scene::GeometryBindDrainSnapshot;
using ntk::prepared_scene::Key;
using ntk::prepared_scene::Ledger;
using ntk::prepared_scene::Phase;
using ntk::prepared_scene::SurfaceAdoption;
using ntk::prepared_scene::classifyGeometryBindDrain;

const std::string kDigestA(64U, 'a');
const std::string kDigestB(64U, 'b');

Install tile(int page = 0, int slot = 0, std::int64_t admission = 1,
             std::int64_t lease = 1, int width = 10, int height = 5) {
    return Install{Key{page, slot}, admission, 1, lease,
                   static_cast<std::int64_t>(width) * height * 4,
                   width, height, kDigestB};
}

Ledger opened() {
    Ledger ledger;
    ledger.open(7, 9, 11, 3, kDigestA, 100);
    return ledger;
}

SurfaceAdoption adoption(std::size_t tile_count = 1U) {
    return SurfaceAdoption{
        13, 17, 19, 23, 1080, 2340, tile_count, kDigestA, kDigestB};
}

bool check(bool value, int number, const char* name) {
    if (value) return true;
    std::fprintf(stderr, "PreparedSceneBankLedgerTest case %d failed: %s\n",
                 number, name);
    return false;
}

}  // namespace

int main() {
    int c = 0;
    {
        Ledger ledger;
        if (!check(ledger.open(7, 9, 11, 3, kDigestA, 100) &&
                       ledger.phase() == Phase::OPEN,
                   ++c, "exact open")) return c;
    }
    {
        Ledger ledger;
        if (!check(!ledger.open(0, 9, 11, 3, kDigestA, 100) &&
                       ledger.phase() == Phase::FAILED,
                   ++c, "zero generation rejected")) return c;
    }
    {
        Ledger ledger;
        if (!check(!ledger.open(7, 0, 11, 3, kDigestA, 100),
                   ++c, "zero authority rejected")) return c;
    }
    {
        Ledger ledger;
        if (!check(!ledger.open(7, 9, 0, 3, kDigestA, 100),
                   ++c, "zero preparation generation rejected")) return c;
    }
    {
        Ledger ledger;
        if (!check(!ledger.open(7, 9, 11, -1, kDigestA, 100),
                   ++c, "negative manifest revision rejected")) return c;
    }
    {
        Ledger ledger;
        if (!check(!ledger.open(7, 9, 11, 3, "bad", 100),
                   ++c, "bad manifest digest rejected")) return c;
    }
    {
        Ledger ledger = opened();
        if (!check(ledger.beginInstall(tile()) && ledger.inFlight(),
                   ++c, "one install admitted")) return c;
    }
    {
        Ledger ledger = opened();
        Install invalid = tile();
        invalid.resource_revision = 2;
        if (!check(!ledger.beginInstall(invalid),
                   ++c, "non-original revision rejected")) return c;
    }
    {
        Ledger ledger = opened();
        Install invalid = tile();
        invalid.rgba_bytes -= 4;
        if (!check(!ledger.beginInstall(invalid),
                   ++c, "byte mismatch rejected")) return c;
    }
    {
        Ledger ledger = opened();
        ledger.beginInstall(tile());
        if (!check(!ledger.beginInstall(tile(0, 1, 2, 2)),
                   ++c, "second in-flight install rejected")) return c;
    }
    {
        Ledger ledger = opened();
        const Install value = tile();
        ledger.beginInstall(value);
        if (!check(ledger.finishInstall(value, 120) && ledger.size() == 1U &&
                       !ledger.inFlight(),
                   ++c, "exact completion stored")) return c;
    }
    {
        Ledger ledger = opened();
        const Install value = tile();
        ledger.beginInstall(value);
        if (!check(!ledger.finishInstall(tile(0, 0, 1, 2), 120),
                   ++c, "lease mismatch rejected")) return c;
    }
    {
        Ledger ledger = opened();
        const Install value = tile();
        ledger.beginInstall(value);
        if (!check(!ledger.finishInstall(value, 99),
                   ++c, "completion before open rejected")) return c;
    }
    {
        Ledger ledger = opened();
        const Install value = tile();
        ledger.beginInstall(value);
        ledger.finishInstall(value, 120);
        if (!check(!ledger.beginInstall(value),
                   ++c, "duplicate key rejected")) return c;
    }
    {
        Ledger ledger = opened();
        const Install value = tile();
        ledger.beginInstall(value);
        if (!check(!ledger.rejectInstall(value) && ledger.phase() == Phase::FAILED,
                   ++c, "upload failure is terminal")) return c;
    }
    {
        Ledger ledger = opened();
        if (!check(!ledger.beginSurfaceAdoption(adoption(0)),
                   ++c, "zero geometry tile count rejected")) return c;
    }
    {
        Ledger ledger = opened();
        const Install value = tile();
        ledger.beginInstall(value);
        if (!check(!ledger.beginSurfaceAdoption(adoption()),
                   ++c, "bind cannot overtake upload")) return c;
    }
    {
        Ledger ledger = opened();
        SurfaceAdoption invalid = adoption();
        invalid.pregeometry_root_digest = "bad";
        if (!check(!ledger.beginSurfaceAdoption(invalid),
                   ++c, "bad pregeometry root rejected")) return c;
    }
    {
        Ledger ledger = opened();
        if (!check(ledger.beginSurfaceAdoption(adoption(2)),
                   ++c, "empty bank geometry bind admitted")) return c;
    }
    {
        Ledger ledger = opened();
        const Install value = tile();
        ledger.beginInstall(value);
        ledger.finishInstall(value, 120);
        ledger.beginSurfaceAdoption(adoption(2));
        if (!check(ledger.finishSurfaceAdoption(1, 1, 130) &&
                       ledger.phase() == Phase::SURFACE_BOUND,
                   ++c, "partial adoption exact")) return c;
    }
    {
        Ledger ledger = opened();
        ledger.beginSurfaceAdoption(adoption(2));
        if (!check(!ledger.finishSurfaceAdoption(1, 1, 130),
                   ++c, "invented adoption rejected")) return c;
    }
    {
        Ledger ledger = opened();
        const Install value = tile();
        ledger.beginInstall(value);
        ledger.finishInstall(value, 120);
        ledger.beginSurfaceAdoption(adoption(2));
        if (!check(!ledger.finishSurfaceAdoption(1, 0, 130),
                   ++c, "adopted plus missing mismatch rejected")) return c;
    }
    {
        Ledger ledger = opened();
        const Install value = tile();
        ledger.beginInstall(value);
        ledger.finishInstall(value, 120);
        ledger.beginSurfaceAdoption(adoption());
        if (!check(!ledger.finishSurfaceAdoption(1, 0, 119),
                   ++c, "bind before upload completion rejected")) return c;
    }
    {
        Ledger ledger = opened();
        ledger.beginSurfaceAdoption(adoption());
        ledger.finishSurfaceAdoption(0, 1, 130);
        if (!check(ledger.closeAdmissions() &&
                       ledger.phase() == Phase::ADMISSIONS_CLOSED,
                   ++c, "admissions close after bind")) return c;
    }
    {
        Ledger ledger = opened();
        if (!check(!ledger.closeAdmissions() && ledger.phase() == Phase::FAILED,
                   ++c, "early admissions close rejected")) return c;
    }
    {
        Ledger ledger = opened();
        const SurfaceAdoption value = adoption();
        ledger.beginSurfaceAdoption(value);
        ledger.finishSurfaceAdoption(0, 1, 130);
        if (!check(!ledger.beginSurfaceAdoption(value) &&
                       ledger.phase() == Phase::FAILED,
                   ++c, "surface adoption is exact once")) return c;
    }
    {
        if (!check(
                classifyGeometryBindDrain(GeometryBindDrainSnapshot{}) ==
                    GeometryBindDrainDisposition::READY,
                ++c, "geometry bind drain ready")) return c;
    }
    {
        GeometryBindDrainSnapshot snapshot;
        snapshot.ready_tile_depth = 1;
        if (!check(
                classifyGeometryBindDrain(snapshot) ==
                    GeometryBindDrainDisposition::WAIT,
                ++c, "ready tile defers geometry bind")) return c;
    }
    {
        GeometryBindDrainSnapshot snapshot;
        snapshot.upload_gpu_fences_pending = 1;
        if (!check(
                classifyGeometryBindDrain(snapshot) ==
                    GeometryBindDrainDisposition::WAIT,
                ++c, "upload fence defers geometry bind")) return c;
    }
    {
        GeometryBindDrainSnapshot snapshot;
        snapshot.upload_commands_submitting = -1;
        if (!check(
                classifyGeometryBindDrain(snapshot) ==
                    GeometryBindDrainDisposition::REJECT,
                ++c, "negative drain ledger rejected")) return c;
    }

    if (!check(c == 30, 31, "exactly 30 native contract cases executed")) return 31;
    std::puts("PreparedSceneBankLedgerTest PASS (30 cases)");
    return 0;
}
