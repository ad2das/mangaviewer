#include "../NativeSurfaceLeaseRegistry.h"

#include <cstdio>

namespace {

struct FakeWindow {
    int identity = 0;
};

bool expect(bool value, const char* message) {
    if (value) return true;
    std::fprintf(stderr, "NativeSurfaceLeaseRegistryTest: %s\n", message);
    return false;
}

}  // namespace

int main() {
    int releaseCount = 0;
    ntk::surface_lease::Registry<FakeWindow> registry(
        [&](FakeWindow*) { ++releaseCount; });

    FakeWindow first{1};
    const std::uint64_t firstLease = registry.acquire(&first, 10);
    if (!expect(firstLease != 0, "acquire returned no opaque lease") ||
        !expect(registry.size() == 1, "acquire did not register one lease") ||
        !expect(registry.contains(firstLease, 10),
                "registry lost exact lease identity")) {
        return 1;
    }

    auto transfer = registry.transfer(firstLease, 10);
    if (!expect(transfer.has_value(), "engine transfer failed") ||
        !expect(transfer->window == &first, "transfer returned wrong window") ||
        !expect(registry.size() == 0, "transfer retained registry ownership") ||
        !expect(!registry.transfer(firstLease, 10).has_value(),
                "double transfer was accepted") ||
        !expect(!registry.release(firstLease, 10),
                "stale transferred lease release was accepted")) {
        return 2;
    }

    FakeWindow second{2};
    const std::uint64_t secondLease = registry.acquire(&second, 20);
    if (!expect(secondLease > firstLease, "lease IDs were not monotonic") ||
        !expect(!registry.release(secondLease, 21),
                "epoch mismatch release was accepted") ||
        !expect(registry.contains(secondLease, 20),
                "epoch mismatch mutated registry ownership") ||
        !expect(registry.release(secondLease, 20),
                "exact unclaimed cancel release failed") ||
        !expect(releaseCount == 1, "exact release count mismatch") ||
        !expect(registry.size() == 0, "release retained registry ownership")) {
        return 3;
    }

    FakeWindow third{3};
    const std::uint64_t thirdLease = registry.acquire(&third, 30);
    if (!expect(thirdLease != 0, "third acquire failed")) return 4;
    int teardownReleaseCount = 0;
    {
        FakeWindow teardown{4};
        ntk::surface_lease::Registry<FakeWindow> teardownRegistry(
            [&](FakeWindow*) { ++teardownReleaseCount; });
        if (!expect(teardownRegistry.acquire(&teardown, 40) != 0,
                    "teardown acquire failed") ||
            !expect(teardownRegistry.size() == 1,
                    "teardown registry did not own lease")) {
            return 5;
        }
    }

    if (!expect(teardownReleaseCount == 1,
                "process teardown did not release the remaining lease") ||
        !expect(registry.release(thirdLease, 30),
                "attach failure release failed") ||
        !expect(releaseCount == 2,
                "attach failure did not release exactly once") ||
        !expect(registry.size() == 0,
                "process test teardown retained a lease")) {
        return 6;
    }

    std::puts("NativeSurfaceLeaseRegistryTest PASS");
    return 0;
}
