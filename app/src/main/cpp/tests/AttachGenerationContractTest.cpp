#include "../AttachGenerationContract.h"

#include <cstdio>
#include <cstdint>

namespace {

bool expect(bool value, const char* message) {
    if (value) return true;
    std::fprintf(stderr, "AttachGenerationContractTest: %s\n", message);
    return false;
}

struct Model {
    std::uint64_t nextGeneration = 0;
    std::uint64_t requestGeneration = 0;
    std::uint64_t claimedGeneration = 0;
    std::uint64_t readyGeneration = 0;
    std::uint64_t publishedGeneration = 0;
    std::uint64_t terminalGeneration = 0;
    std::uint64_t surfaceEpoch = 0;
    std::uint64_t requestedGeometryRevision = 0;
    std::uint64_t appliedGeometryRevision = 0;
    bool surfaceLossRequested = false;
    bool poisoned = false;

    bool queue(std::uint64_t epoch, std::uint64_t geometryRevision) {
        const std::uint64_t candidate = nextGeneration + 1;
        if (!ntk::attach_generation::generationStrictlyMonotonic(
                nextGeneration, candidate) ||
            requestGeneration > terminalGeneration) {
            return false;
        }
        nextGeneration = candidate;
        requestGeneration = candidate;
        surfaceEpoch = epoch;
        requestedGeometryRevision = geometryRevision;
        appliedGeometryRevision = 0;
        surfaceLossRequested = false;
        return true;
    }

    bool claim() {
        if (!ntk::attach_generation::claimAllowed(
                requestGeneration, claimedGeneration, terminalGeneration)) {
            return false;
        }
        claimedGeneration = requestGeneration;
        return true;
    }

    bool ready(std::uint64_t geometryRevision) {
        if (claimedGeneration != requestGeneration ||
            terminalGeneration >= requestGeneration) {
            return false;
        }
        readyGeneration = requestGeneration;
        appliedGeometryRevision = geometryRevision;
        return true;
    }

    bool publish(std::uint64_t epoch) {
        if (!ntk::attach_generation::publishAllowed(
                requestGeneration, readyGeneration, surfaceEpoch, epoch,
                requestedGeometryRevision, appliedGeometryRevision,
                surfaceLossRequested,
                terminalGeneration >= requestGeneration)) {
            return false;
        }
        publishedGeneration = requestGeneration;
        return true;
    }

    ntk::attach_generation::SurfaceLossDisposition loss(bool identityMatches = true) {
        const auto disposition = ntk::attach_generation::surfaceLossDisposition(
            requestGeneration, claimedGeneration, publishedGeneration,
            terminalGeneration, identityMatches);
        if (disposition ==
            ntk::attach_generation::SurfaceLossDisposition::CANCELLED_UNCLAIMED) {
            terminalGeneration = requestGeneration;
        } else if (disposition ==
            ntk::attach_generation::SurfaceLossDisposition::
                COMPLETE_CLAIMED_THEN_DETACH) {
            surfaceLossRequested = true;
        }
        return disposition;
    }

    ntk::attach_generation::TimeoutDisposition timeout() {
        const auto disposition = ntk::attach_generation::timeoutDisposition(
            requestGeneration, claimedGeneration);
        if (disposition ==
            ntk::attach_generation::TimeoutDisposition::FAIL_CANCEL_UNCLAIMED) {
            terminalGeneration = requestGeneration;
            poisoned = true;
        }
        return disposition;
    }
};

}  // namespace

int main() {
    using ntk::attach_generation::SurfaceLossDisposition;
    using ntk::attach_generation::TimeoutDisposition;

    Model timeout_queued;
    if (!expect(timeout_queued.queue(11, 1), "queue for timeout failed") ||
        !expect(timeout_queued.timeout() ==
                    TimeoutDisposition::FAIL_CANCEL_UNCLAIMED,
                "timeout did not cancel queued generation") ||
        !expect(timeout_queued.poisoned,
                "queued timeout did not poison the engine")) {
        return 1;
    }

    Model timeout_claimed;
    if (!expect(timeout_claimed.queue(12, 1), "claimed timeout queue failed") ||
        !expect(timeout_claimed.claim(), "claimed timeout claim failed") ||
        !expect(timeout_claimed.timeout() ==
                    TimeoutDisposition::WAIT_FOR_CLAIMED_COMPLETION,
                "claimed timeout did not preserve exact completion") ||
        !expect(!timeout_claimed.poisoned,
                "claimed timeout poisoned before completion")) {
        return 2;
    }

    Model loss_queued;
    if (!expect(loss_queued.queue(13, 1), "queued loss queue failed") ||
        !expect(loss_queued.loss() ==
                    SurfaceLossDisposition::CANCELLED_UNCLAIMED,
                "surface loss did not cancel queued generation") ||
        !expect(!loss_queued.poisoned,
                "normal queued surface loss poisoned the engine")) {
        return 3;
    }

    Model loss_claimed;
    if (!expect(loss_claimed.queue(14, 1), "claimed loss queue failed") ||
        !expect(loss_claimed.claim(), "claimed loss claim failed") ||
        !expect(loss_claimed.loss() ==
                    SurfaceLossDisposition::COMPLETE_CLAIMED_THEN_DETACH,
                "claimed surface loss did not require completion then detach") ||
        !expect(loss_claimed.ready(1),
                "claimed generation was rolled back after holder loss") ||
        !expect(!loss_claimed.publish(14),
                "loss-pending claimed generation was published")) {
        return 4;
    }

    Model loss_before_claim;
    if (!expect(loss_before_claim.queue(15, 1), "loss-before-claim queue failed") ||
        !expect(loss_before_claim.loss() ==
                    SurfaceLossDisposition::CANCELLED_UNCLAIMED,
                "loss-before-claim did not win exact mutex ordering") ||
        !expect(!loss_before_claim.claim(),
                "claim succeeded after loss terminalized the generation")) {
        return 5;
    }

    Model claim_before_loss;
    if (!expect(claim_before_loss.queue(16, 1), "claim-before-loss queue failed") ||
        !expect(claim_before_loss.claim(), "claim-before-loss claim failed") ||
        !expect(claim_before_loss.loss() ==
                    SurfaceLossDisposition::COMPLETE_CLAIMED_THEN_DETACH,
                "claim-before-loss did not preserve claimed generation") ||
        !expect(claim_before_loss.ready(1),
                "claimed generation could not reach exact ready state")) {
        return 6;
    }

    Model duplicate_claim;
    if (!expect(duplicate_claim.queue(17, 1), "duplicate claim queue failed") ||
        !expect(duplicate_claim.claim(), "first claim failed") ||
        !expect(!duplicate_claim.claim(), "duplicate claim was accepted")) {
        return 7;
    }

    Model stale_publish;
    if (!expect(stale_publish.queue(18, 1), "stale publish queue failed") ||
        !expect(stale_publish.claim(), "stale publish claim failed") ||
        !expect(stale_publish.ready(1), "stale publish ready failed") ||
        !expect(!stale_publish.publish(19), "stale epoch publish was accepted")) {
        return 8;
    }

    Model geometry;
    if (!expect(geometry.queue(20, 2), "geometry queue failed") ||
        !expect(geometry.claim(), "geometry claim failed") ||
        !expect(geometry.ready(1), "geometry ready failed") ||
        !expect(!geometry.publish(20),
                "publish accepted before exact geometry revision ACK") ||
        !expect(geometry.ready(2), "geometry resize ACK failed") ||
        !expect(geometry.publish(20),
                "publish rejected exact geometry revision ACK")) {
        return 9;
    }

    Model monotonic;
    if (!expect(monotonic.queue(21, 1), "first monotonic queue failed")) {
        return 10;
    }
    monotonic.terminalGeneration = monotonic.requestGeneration;
    if (!expect(monotonic.queue(22, 1), "second monotonic queue failed") ||
        !expect(monotonic.requestGeneration == 2,
                "attach generations were not strictly monotonic")) {
        return 11;
    }

    std::puts("AttachGenerationContractTest PASS");
    return 0;
}
