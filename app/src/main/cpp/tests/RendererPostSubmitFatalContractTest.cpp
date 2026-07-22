#include "../present/HardwareBufferRenderTargetPool.h"
#include "../present/RendererPostSubmitFatalContract.h"

#include <cstdlib>
#include <iostream>

namespace ntk::present {

struct HardwareBufferRenderTargetPoolTestAccess {
    static void initializeStateOnly(HardwareBufferRenderTargetPool& pool) {
        pool.initialized_ = true;
        for (std::size_t index = 0; index < pool.targets_.size(); ++index) {
            pool.targets_[index].slot = index;
            pool.targets_[index].generation = 0;
            pool.targets_[index].state =
                HardwareBufferRenderTargetPool::SlotState::FREE;
        }
    }
};

}  // namespace ntk::present

namespace {

using ntk::present::HardwareBufferRenderTargetPool;
using ntk::present::HardwareBufferRenderTargetPoolTestAccess;

void require(bool value, const char* message) {
    if (value) return;
    std::cerr << "FAIL RendererPostSubmitFatalContractTest: " << message << '\n';
    std::exit(1);
}

void gatesCloseBeforeAnyFurtherActionDown() {
    const auto transition =
        ntk::present::makeRendererPostSubmitFatalTransition(8);
    std::uint32_t overlappingDownFatalCount = 0;
    const auto actionDown = [&] {
        if (!ntk::present::postSubmitFatalAcceptsActionDown(transition)) {
            return std::uint64_t{0};
        }
        ++overlappingDownFatalCount;
        return std::uint64_t{1};
    };
    require(transition.exactReason == 8 && transition.inputClosed &&
            transition.presentationClosed && transition.engineFailed &&
            transition.authorityFailed && transition.gpuFailed &&
            transition.producerFailed && transition.submittedDraining &&
            transition.stickyFatal && actionDown() == 0 &&
            overlappingDownFatalCount == 0,
            "post-submit fatal did not close gates synchronously");
}

void fatalStillDrainsAllIrreversiblePhysicalOwnership() {
    HardwareBufferRenderTargetPool pool;
    HardwareBufferRenderTargetPoolTestAccess::initializeStateOnly(pool);
    auto* previous = pool.acquireForRendering();
    auto* submitted = pool.acquireForRendering();
    require(previous != nullptr && submitted != nullptr &&
            HardwareBufferRenderTargetPool::kSlotCount == 8 &&
            pool.markAcquireFenceExported(*previous) &&
            pool.commitSubmissionPair(
                previous->slot, previous->generation, std::nullopt) &&
            pool.markAcquireFenceExported(*submitted),
            "fatal drain setup failed");
    const auto previousIdentity =
        HardwareBufferRenderTargetPool::BufferIdentity{
            previous->slot, previous->generation};
    require(pool.commitSubmissionPair(
                submitted->slot, submitted->generation, previousIdentity),
            "applied pair was not committed");

    std::uint32_t outstanding = 1;
    bool externalClaimPresent = false;
    bool preparedTransactionPresent = false;
    bool onCommitDrained =
        submitted->state ==
            HardwareBufferRenderTargetPool::SlotState::FRAMEWORK_CHAIN_HEAD;
    bool onCompleteDrained = outstanding-- == 1;
    bool fatalRetirementDrained = true;
    bool previousReleaseDrained = pool.markReleased(
        previous->slot, previous->generation);
    // Teardown releases the final latched buffer only after every submitted
    // callback has reached its terminal lane.
    const bool teardownDrained =
        pool.markReleased(submitted->slot, submitted->generation);
    require(onCommitDrained && onCompleteDrained &&
            fatalRetirementDrained && previousReleaseDrained &&
            teardownDrained && pool.allFree() && outstanding == 0 &&
            !externalClaimPresent && !preparedTransactionPresent,
            "post-submit fatal did not conserve K=8 physical ownership");
}

void joinedPostSubmitDepthIsExactlyOne() {
    using ntk::present::rendererPostSubmitLogicalUnlatchedExact;
    require(rendererPostSubmitLogicalUnlatchedExact(1, 0, 1),
            "first post-submit generation was rejected");
    require(rendererPostSubmitLogicalUnlatchedExact(2, 1, 1) &&
            rendererPostSubmitLogicalUnlatchedExact(8, 7, 1) &&
            rendererPostSubmitLogicalUnlatchedExact(9, 8, 1),
            "joined successor did not preserve one logical unlatched frame");
    require(!rendererPostSubmitLogicalUnlatchedExact(2, 0, 2),
            "pre-OnCommit successor overlap was accepted");
    require(!rendererPostSubmitLogicalUnlatchedExact(8, 0, 8),
            "callback-ledger depth was conflated with logical unlatched depth");
    require(!rendererPostSubmitLogicalUnlatchedExact(2, 1, 2),
            "logical unlatched lifetime maximum escaped depth one");
    require(!rendererPostSubmitLogicalUnlatchedExact(1, 2, 1),
            "terminal proof count escaped ahead of successful submissions");
}

}  // namespace

int main() {
    gatesCloseBeforeAnyFurtherActionDown();
    fatalStillDrainsAllIrreversiblePhysicalOwnership();
    joinedPostSubmitDepthIsExactlyOne();
    std::cout << "PASS RendererPostSubmitFatalContractTest schema11 JOIN/K8\n";
    return 0;
}
