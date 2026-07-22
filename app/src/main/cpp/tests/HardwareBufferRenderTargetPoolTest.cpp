#include "../present/HardwareBufferRenderTargetPool.h"

#include <array>
#include <cstdlib>
#include <iostream>
#include <optional>

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

using Pool = ntk::present::HardwareBufferRenderTargetPool;
using Access = ntk::present::HardwareBufferRenderTargetPoolTestAccess;

void require(bool value, const char* message) {
    if (value) return;
    std::cerr << "FAIL HardwareBufferRenderTargetPoolTest: " << message << '\n';
    std::exit(1);
}

void exactEightSlotCapacityAndAbortConservation() {
    Pool pool;
    Access::initializeStateOnly(pool);
    std::array<Pool::BufferIdentity, Pool::kSlotCount> acquired{};
    for (std::size_t index = 0; index < acquired.size(); ++index) {
        auto* target = pool.acquireForRendering();
        require(target != nullptr, "one of eight render slots was unavailable");
        acquired[index] = {target->slot, target->generation};
    }
    require(Pool::kSlotCount == 8 && !pool.hasFreeRenderTarget() &&
                pool.acquireForRendering() == nullptr,
            "pool capacity was not exactly eight");
    for (const auto& identity : acquired) {
        require(pool.abortBeforeSubmission(
                    identity.slot, identity.generation),
                "rendering owner did not abort exactly");
    }
    require(pool.allFree(), "eight-slot abort did not conserve ownership");
}

void generationRejectsEveryStaleOperation() {
    Pool pool;
    Access::initializeStateOnly(pool);
    auto* first = pool.acquireForRendering();
    require(first != nullptr, "initial acquire failed");
    const auto slot = first->slot;
    const auto generation = first->generation;
    auto stale = *first;
    require(pool.abortBeforeSubmission(slot, generation), "initial abort failed");
    auto* reused = pool.acquireForRendering();
    require(reused != nullptr && reused->slot == slot &&
                reused->generation == generation + 1,
            "slot reuse did not advance generation");
    require(!pool.markAcquireFenceExported(stale) &&
                !pool.markReleaseWait(slot, generation) &&
                !pool.markReleased(slot, generation),
            "stale generation mutated a reused slot");
    require(pool.abortBeforeSubmission(slot, reused->generation),
            "reused owner cleanup failed");
}

void ownershipTransitionsCannotBeSkippedOrDuplicated() {
    Pool pool;
    Access::initializeStateOnly(pool);
    auto* target = pool.acquireForRendering();
    require(target != nullptr, "acquire failed");
    const auto identity = Pool::BufferIdentity{
        target->slot, target->generation};
    require(!pool.markReleaseWait(identity.slot, identity.generation) &&
                !pool.markReleased(identity.slot, identity.generation),
            "ownership transition was skipped");
    require(pool.markAcquireFenceExported(*target) &&
                !pool.markAcquireFenceExported(*target) &&
                pool.commitSubmissionPair(
                    identity.slot, identity.generation, std::nullopt) &&
                !pool.commitSubmissionPair(
                    identity.slot, identity.generation, std::nullopt),
            "exact first-chain transition was not single-use");
    const auto beforeLatch = target->state;
    require(target->state == beforeLatch,
            "OnCommit incorrectly changed physical ownership");
    require(pool.markReleaseWait(identity.slot, identity.generation) &&
                !pool.markReleaseWait(identity.slot, identity.generation) &&
                pool.markReleased(identity.slot, identity.generation) &&
                !pool.markReleased(identity.slot, identity.generation) &&
                pool.allFree(),
            "terminal release transition was not exact");
}

void replacementPairIsAtomicAndRollbackableBeforeApply() {
    Pool pool;
    Access::initializeStateOnly(pool);
    auto* previous = pool.acquireForRendering();
    auto* successor = pool.acquireForRendering();
    require(previous != nullptr && successor != nullptr &&
                pool.markAcquireFenceExported(*previous) &&
                pool.commitSubmissionPair(
                    previous->slot, previous->generation, std::nullopt) &&
                pool.markAcquireFenceExported(*successor),
            "replacement setup failed");
    const Pool::BufferIdentity prior{
        previous->slot, previous->generation};
    auto wrong = prior;
    ++wrong.generation;
    require(!pool.commitSubmissionPair(
                    successor->slot, successor->generation, wrong) &&
                previous->state == Pool::SlotState::FRAMEWORK_CHAIN_HEAD &&
                successor->state == Pool::SlotState::ACQUIRE_FENCE_EXPORTED,
            "failed pair validation partially mutated ownership");
    require(pool.commitSubmissionPair(
                    successor->slot, successor->generation, prior) &&
                previous->state ==
                    Pool::SlotState::FRAMEWORK_REPLACED_WAIT_RELEASE &&
                successor->state == Pool::SlotState::FRAMEWORK_CHAIN_HEAD,
            "replacement pair did not advance the framework chain");
    require(pool.rollbackSubmissionPairBeforeApply(
                    successor->slot, successor->generation, prior) &&
                previous->state == Pool::SlotState::FRAMEWORK_CHAIN_HEAD &&
                successor->state == Pool::SlotState::ACQUIRE_FENCE_EXPORTED &&
                pool.commitSubmissionPair(
                    successor->slot, successor->generation, prior),
            "pre-apply rollback did not restore the exact pair");
    require(pool.markReleased(previous->slot, previous->generation) &&
                pool.markReleased(successor->slot, successor->generation) &&
                pool.allFree(),
            "replacement pair cleanup failed");
}

void fullEightNodeChainReleasesOnlyExactReplacedRefs() {
    Pool pool;
    Access::initializeStateOnly(pool);
    std::array<Pool::BufferIdentity, Pool::kSlotCount> chain{};
    std::optional<Pool::BufferIdentity> previous;
    for (std::size_t index = 0; index < chain.size(); ++index) {
        auto* target = pool.acquireForRendering();
        require(target != nullptr && pool.markAcquireFenceExported(*target),
                "chain target preparation failed");
        chain[index] = {target->slot, target->generation};
        require(pool.commitSubmissionPair(
                    target->slot, target->generation, previous),
                "framework chain advance failed");
        previous = chain[index];
    }
    const auto states = pool.stateSnapshot();
    std::size_t headCount = 0;
    std::size_t replacedCount = 0;
    for (const auto state : states) {
        headCount += state == Pool::SlotState::FRAMEWORK_CHAIN_HEAD;
        replacedCount +=
            state == Pool::SlotState::FRAMEWORK_REPLACED_WAIT_RELEASE;
    }
    require(headCount == 1 && replacedCount == Pool::kSlotCount - 1 &&
                !pool.hasFreeRenderTarget(),
            "eight-node framework ownership ledger was not exact");
    for (std::size_t index = 0; index + 1 < chain.size(); ++index) {
        require(pool.markReleased(
                    chain[index].slot, chain[index].generation),
                "exact replaced reference did not release");
    }
    require(pool.markReleased(
                chain.back().slot, chain.back().generation) &&
                pool.allFree(),
            "final chain head did not release at teardown");
}

void uninitializedPoolRejectsOwnership() {
    Pool pool;
    require(pool.acquireForRendering() == nullptr &&
                !pool.hasFreeRenderTarget() && pool.allFree(),
            "uninitialized pool admitted ownership");
}

}  // namespace

int main() {
    exactEightSlotCapacityAndAbortConservation();
    generationRejectsEveryStaleOperation();
    ownershipTransitionsCannotBeSkippedOrDuplicated();
    replacementPairIsAtomicAndRollbackableBeforeApply();
    fullEightNodeChainReleasesOnlyExactReplacedRefs();
    uninitializedPoolRejectsOwnership();
    std::cout << "PASS HardwareBufferRenderTargetPoolTest schema11 6/6\n";
    return 0;
}
