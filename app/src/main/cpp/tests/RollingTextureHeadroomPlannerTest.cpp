#include "../RollingTextureHeadroomPlanner.h"

#include <cstdlib>
#include <iostream>
#include <limits>
#include <string>
#include <vector>

namespace {

using ntk::rolling::TextureHeadroomIncoming;
using ntk::rolling::TextureHeadroomKey;
using ntk::rolling::TextureHeadroomResident;

constexpr std::uint64_t kBudget = 96ULL * 1024ULL * 1024ULL;
constexpr std::size_t kMaximumNames = 24;

void require(bool condition, const std::string& message) {
    if (!condition) {
        std::cerr << "FAIL: " << message << '\n';
        std::exit(1);
    }
}

TextureHeadroomResident resident(
        int page, std::uint64_t bytes, std::uint64_t use,
        std::uint64_t identity = 1, int slot = 0, std::int64_t epoch = 7) {
    return {{epoch, page, slot}, identity, bytes, use};
}

TextureHeadroomIncoming incoming(
        int page, std::uint64_t bytes, std::uint64_t identity = 1,
        int slot = 0, std::int64_t epoch = 7) {
    return {{epoch, page, slot}, identity, bytes};
}

void exactFailureResidencyGetsHeadroomBeforeUpload() {
    constexpr std::uint64_t residentBytes = 91'413'632ULL;
    constexpr std::uint64_t nextTileBytes = 11'493'376ULL;
    const std::vector<TextureHeadroomResident> residents{
        resident(4, 6'220'800ULL, 2),
        resident(5, residentBytes - 6'220'800ULL, 3),
    };
    const std::vector<TextureHeadroomIncoming> targets{
        incoming(6, nextTileBytes, 8),
    };
    const auto plan = ntk::rolling::planVisibleTextureHeadroom(
        residents, residentBytes, targets, 7, true, kBudget, kMaximumNames);
    require(plan.valid, "91.4 MiB plan must be valid");
    require(plan.freshBytes == nextTileBytes, "full incoming allocation must be reserved");
    require(plan.hasUploadWork, "a missing visible tile must require one upload transaction");
    require(!plan.evictionIndices.empty(), "headroom must be created before GL allocation");
    require(plan.evictionIndices.front() == 0,
            "the oldest already-read page must provide headroom first");
    require(plan.projectedBytes <= kBudget, "post-eviction projection must fit the budget");
}

void changedSameKeyUsesFreshTransientStorage() {
    const std::vector<TextureHeadroomResident> residents{
        resident(5, 6'220'800ULL, 10, 11),
        resident(4, kBudget - 6'220'800ULL, 1, 4),
    };
    const std::vector<TextureHeadroomIncoming> targets{
        incoming(5, 11'493'376ULL, 12),
    };
    const auto plan = ntk::rolling::planVisibleTextureHeadroom(
        residents, kBudget, targets, 7, true, kBudget, kMaximumNames);
    require(plan.freshBytes == 11'493'376ULL,
            "direct-Wi-Fi same-key replacement keeps the old bytes until success");
    require(plan.freshNames == 1, "same-key replacement needs a fresh GL name");
    require(plan.evictionIndices.size() == 1 && plan.evictionIndices.front() == 1,
            "the protected same-key target must survive headroom eviction");
}

void mixedFrameAggregatesExactMissingAndChangedTargets() {
    const std::vector<TextureHeadroomResident> residents{
        resident(5, 10, 10, 1, 0),   // exact target
        resident(5, 20, 9, 2, 1),    // changed target keeps old storage transiently
        resident(9, 40, 8),           // forward victim
        resident(2, 50, 7),           // already-read victim
    };
    const std::vector<TextureHeadroomIncoming> targets{
        incoming(5, 10, 1, 0),
        incoming(5, 30, 3, 1),
        incoming(6, 25, 4, 0),
    };
    const auto plan = ntk::rolling::planVisibleTextureHeadroom(
        residents, 120, targets, 7, true, 100, kMaximumNames);
    require(plan.valid, "mixed target frame must produce one valid aggregate plan");
    require(plan.freshBytes == 55 && plan.freshNames == 2,
            "exact contributes zero while missing and changed contribute full fresh costs");
    require(plan.evictionIndices.size() == 2 &&
            plan.evictionIndices[0] == 3 && plan.evictionIndices[1] == 2,
            "read history then far runway must create aggregate headroom");
    require(plan.evictionIndices[0] != 0 && plan.evictionIndices[0] != 1 &&
            plan.evictionIndices[1] != 0 && plan.evictionIndices[1] != 1,
            "every incoming key remains protected regardless of exactness");
    require(plan.projectedBytes == 120 - 50 - 40 + 55,
            "projected bytes must equal R minus all E plus aggregate FreshBytes");
    require(plan.projectedNames == 4 - 2 + 2,
            "projected names must equal N minus e plus aggregate FreshNames");
}

void targetKeysAreProtectedAndFarthestRunwayLosesFirst() {
    const std::vector<TextureHeadroomResident> residents{
        resident(5, 40, 5),
        resident(6, 40, 4),
        resident(12, 40, 3),
    };
    const std::vector<TextureHeadroomIncoming> targets{
        incoming(5, 40, 1),
    };
    const auto plan = ntk::rolling::planVisibleTextureHeadroom(
        residents, 120, targets, 7, true, 100, kMaximumNames);
    require(plan.evictionIndices.size() == 1, "one victim should create enough headroom");
    require(plan.evictionIndices.front() == 2,
            "farthest forward runway must be evicted before the nearer cached page");
    require(plan.evictionIndices.front() != 0, "the incoming visible key is protected");
}

void staleEpochPrecedesCurrentEpochVictims() {
    const std::vector<TextureHeadroomResident> residents{
        resident(4, 40, 1, 1, 0, 6),
        resident(2, 40, 1),
        resident(5, 40, 1),
    };
    const std::vector<TextureHeadroomIncoming> targets{
        incoming(5, 40, 1),
    };
    const auto plan = ntk::rolling::planVisibleTextureHeadroom(
        residents, 120, targets, 7, true, 100, kMaximumNames);
    require(plan.evictionIndices.size() == 1 && plan.evictionIndices.front() == 0,
            "a stale structure epoch must be evicted before same-epoch history");
}

void appendOnlyFrameProtectsOlderImmutableVisibleKeys() {
    const std::vector<TextureHeadroomResident> residents{
        resident(4, 40, 8, 31, 0, 6),
        resident(5, 40, 9, 32, 0, 7),
    };
    const std::vector<TextureHeadroomIncoming> targets{
        incoming(4, 40, 31, 0, 6),
        incoming(5, 40, 32, 0, 7),
    };
    const auto plan = ntk::rolling::planVisibleTextureHeadroom(
        residents, 80, targets, 7, true, kBudget, kMaximumNames);
    require(plan.valid && !plan.hasUploadWork,
            "append-only frames must retain exact older-epoch visible pixels");

    const std::vector<TextureHeadroomIncoming> future{
        incoming(6, 40, 33, 0, 8),
    };
    require(!ntk::rolling::planVisibleTextureHeadroom(
                residents, 80, future, 7, true, kBudget, kMaximumNames).valid,
            "a future-epoch texture must still fail closed");
}

void freshNameLimitUsesTheSameAggregateEvictionPlan() {
    std::vector<TextureHeadroomResident> residents;
    for (int page = 0; page < 24; ++page) {
        residents.push_back(resident(page, 1, static_cast<std::uint64_t>(page + 1)));
    }
    const std::vector<TextureHeadroomIncoming> targets{
        incoming(23, 1, 2),  // changed target: old name remains alive until upload success
    };
    const auto plan = ntk::rolling::planVisibleTextureHeadroom(
        residents, 24, targets, 7, true,
        std::numeric_limits<std::uint64_t>::max(), kMaximumNames);
    require(plan.freshNames == 1, "changed direct tile reserves one transient name");
    require(plan.evictionIndices.size() == 1, "N-e+FreshNames must remain at the name cap");
    require(plan.evictionIndices.front() != 23, "the changed target name remains protected");
    require(plan.projectedNames == kMaximumNames, "projected name count must equal the cap");
}

void protectedFrameOversizeIsExplicitlyAllowed() {
    const std::vector<TextureHeadroomResident> residents{
        resident(5, 80, 1, 1),
    };
    const std::vector<TextureHeadroomIncoming> targets{
        incoming(5, 140, 2),
    };
    const auto plan = ntk::rolling::planVisibleTextureHeadroom(
        residents, 80, targets, 7, true, 100, 1);
    require(plan.valid, "visible oversize remains a valid real-pixel frame");
    require(plan.evictionIndices.empty(), "protected target storage cannot be evicted");
    require(plan.protectedFrameOversize, "oversize must be explicit to callers and telemetry");
}

void arithmeticOverflowSaturatesInsteadOfWrapping() {
    const std::vector<TextureHeadroomResident> residents{
        resident(1, 8, 1),
    };
    const std::vector<TextureHeadroomIncoming> targets{
        incoming(2, std::numeric_limits<std::uint64_t>::max() - 3, 2, 0),
        incoming(2, 16, 3, 1),
    };
    const auto plan = ntk::rolling::planVisibleTextureHeadroom(
        residents, 8, targets, 7, true, kBudget, kMaximumNames);
    require(!plan.valid, "aggregate overflow must fail closed");
    require(plan.arithmeticOverflow, "aggregate byte overflow must be recorded");
    require(plan.freshBytes == std::numeric_limits<std::uint64_t>::max(),
            "overflow must saturate instead of becoming a small allocation");
    require(plan.evictionIndices.empty(), "invalid overflow must not evict live residency");
    require(!plan.protectedFrameOversize,
            "invalid overflow is distinct from representable protected oversize");
}

void optionalPrewarmDefersInsteadOfEvictingVisibleResidency() {
    const std::vector<TextureHeadroomResident> residents{
        resident(5, kBudget, 10),
    };
    require(!ntk::rolling::canUploadOptionalTextureWithoutEviction(
                residents, kBudget, incoming(8, 11'493'376ULL, 2), true,
                kBudget, kMaximumNames),
            "optional farther prewarm must defer instead of evicting the cached p0");
}

void textureRetirementDebtRequiresASuccessfulBarrier() {
    ntk::rolling::TextureRetirementDebt debt;
    require(!debt.pending(), "a new renderer must not invent retirement debt");
    debt.record(11'493'376ULL);
    debt.record(6'220'800ULL);
    require(debt.pending(), "deleted names must block optional allocations");
    require(debt.names() == 2, "each deleted GL name must be represented once");
    require(debt.bytes() == 17'714'176ULL, "retired storage bytes must be accumulated");
    debt.completeBarrier(false);
    require(debt.pending() && debt.names() == 2,
            "a failed barrier must preserve the complete physical debt");
    debt.completeBarrier(true);
    require(!debt.pending() && debt.names() == 0 && debt.bytes() == 0,
            "only a successful barrier may acknowledge retired storage");

    debt.record(std::numeric_limits<std::uint64_t>::max());
    debt.record(1);
    require(debt.bytes() == std::numeric_limits<std::uint64_t>::max(),
            "retirement accounting must saturate instead of wrapping");
}

void visibleRetirementDebtBatchesInsideTwoGenerationWindow() {
    ntk::rolling::TextureRetirementDebt debt;
    ntk::rolling::TextureHeadroomPlan changedFrame{};
    changedFrame.hasUploadWork = true;
    changedFrame.freshBytes = 11'493'376ULL;
    changedFrame.freshNames = 1;

    debt.record(11'493'376ULL);
    require(!ntk::rolling::shouldSettleTextureRetirementBeforeVisibleUpload(
                debt, 91'413'632ULL, 8, changedFrame, kBudget, kMaximumNames),
            "one retired immutable generation must not serialize the next visible frame");

    for (int index = 0; index < 8; ++index) debt.record(11'493'376ULL);
    require(ntk::rolling::shouldSettleTextureRetirementBeforeVisibleUpload(
                debt, 91'413'632ULL, 8, changedFrame, kBudget, kMaximumNames),
            "physical debt beyond two logical generations must settle before allocating again");

    ntk::rolling::TextureHeadroomPlan exactFrame{};
    exactFrame.hasUploadWork = false;
    require(!ntk::rolling::shouldSettleTextureRetirementBeforeVisibleUpload(
                debt, 91'413'632ULL, 8, exactFrame, kBudget, kMaximumNames),
            "an exact frame must not pay a barrier when it allocates no storage");
}

void exactVisibleFrameDoesNotRequestARetirementBarrier() {
    const std::vector<TextureHeadroomResident> residents{
        resident(5, 6'220'800ULL, 10, 41),
    };
    const std::vector<TextureHeadroomIncoming> targets{
        incoming(5, 6'220'800ULL, 41),
    };
    const auto plan = ntk::rolling::planVisibleTextureHeadroom(
        residents, 6'220'800ULL, targets, 7, true, kBudget, kMaximumNames);
    require(plan.valid && !plan.hasUploadWork,
            "an exact visible identity must not serialize on unrelated retired storage");
}

}  // namespace

int main() {
    exactFailureResidencyGetsHeadroomBeforeUpload();
    changedSameKeyUsesFreshTransientStorage();
    mixedFrameAggregatesExactMissingAndChangedTargets();
    targetKeysAreProtectedAndFarthestRunwayLosesFirst();
    staleEpochPrecedesCurrentEpochVictims();
    appendOnlyFrameProtectsOlderImmutableVisibleKeys();
    freshNameLimitUsesTheSameAggregateEvictionPlan();
    protectedFrameOversizeIsExplicitlyAllowed();
    arithmeticOverflowSaturatesInsteadOfWrapping();
    optionalPrewarmDefersInsteadOfEvictingVisibleResidency();
    textureRetirementDebtRequiresASuccessfulBarrier();
    visibleRetirementDebtBatchesInsideTwoGenerationWindow();
    exactVisibleFrameDoesNotRequestARetirementBarrier();
    std::cout << "RollingTextureHeadroomPlannerTest passed\n";
    return 0;
}
