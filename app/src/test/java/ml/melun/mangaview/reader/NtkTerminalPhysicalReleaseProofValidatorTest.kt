package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkTerminalPhysicalReleaseProofValidatorTest {
    @Test
    fun detachedPreparationReleaseHasExplicitZeroSurfaceScope() {
        val token = NtkNativeAuthorityToken(
            engineGeneration = 71L,
            authorityGeneration = 72L,
            authority = 73L,
            manifestRevision = 1L,
            manifestDigest = "a".repeat(64),
            geometryDigest = "b".repeat(64)
        )
        val request = NtkAuthorityReleaseRequest(
            token,
            reducerSurfaceEpoch = 0L,
            releaseNonce = 75L
        )
        assertEquals(0L, request.reducerSurfaceEpoch)
    }

    @Test
    fun contextLossDetachResultRequiresEveryBackendOwnerZero() {
        val retired = NtkNativeDetachResult(
            disposition = NtkNativeDetachDisposition.CONTEXT_LOST_RETIRED,
            engineGeneration = 71L,
            surfaceEpoch = 74L,
            backendRetirementSerial = 5L,
            backendRetiredNanos = 6L,
            retiredAuthorityCount = 1,
            retiredAuthorityDigest = NtkStripDigests.sha256Tokens("retired-authority"),
            retiredBackendRemainingThreadCount = 0,
            retiredBackendRemainingEglHandleCount = 0,
            retiredBackendRemainingNativeWindowCount = 0,
            retiredBackendRemainingSwappyLeaseCount = 0,
            retiredBackendRemainingJniGlobalRefCount = 0,
            remainingBitmapGlobalRefCount = 0,
            remainingNativeCallbackCount = 0
        )
        assertTrue(retired.hasCompleteRetirementBarrier)

        listOf(
            retired.copy(retiredBackendRemainingThreadCount = 1),
            retired.copy(retiredBackendRemainingEglHandleCount = 1),
            retired.copy(retiredBackendRemainingNativeWindowCount = 1),
            retired.copy(retiredBackendRemainingSwappyLeaseCount = 1),
            retired.copy(retiredBackendRemainingJniGlobalRefCount = 1),
            retired.copy(remainingBitmapGlobalRefCount = 1),
            retired.copy(remainingNativeCallbackCount = 1)
        ).forEach { assertFalse(it.hasCompleteRetirementBarrier) }
    }

    @Test
    fun contextLostProofRequiresCompleteDetachTombstone() {
        val request = request()
        val proof = proof(contextLostAck(request))

        assertTrue(
            NtkTerminalPhysicalReleaseProofValidator.violation(proof, request).orEmpty(),
            NtkTerminalPhysicalReleaseProofValidator.isValid(proof, request)
        )

        val missingSerial = proof(contextLostAck(request).copy(backendRetirementSerial = 0L))
        assertFalse(NtkTerminalPhysicalReleaseProofValidator.isValid(missingSerial, request))
        val missingTimestamp = proof(contextLostAck(request).copy(backendRetiredNanos = 0L))
        assertFalse(NtkTerminalPhysicalReleaseProofValidator.isValid(missingTimestamp, request))
    }

    @Test
    fun retiredBackendOwnershipCountersAreHardZeroGate() {
        val request = request()
        val valid = contextLostAck(request)
        val retainedOwnership = listOf(
            valid.copy(retiredBackendRemainingThreadCount = 1),
            valid.copy(retiredBackendRemainingEglHandleCount = 1),
            valid.copy(retiredBackendRemainingNativeWindowCount = 1),
            valid.copy(retiredBackendRemainingSwappyLeaseCount = 1),
            valid.copy(retiredBackendRemainingJniGlobalRefCount = 1)
        )

        retainedOwnership.forEach { ack ->
            assertFalse(
                "accepted retired-backend ownership in $ack",
                NtkTerminalPhysicalReleaseProofValidator.isValid(proof(ack), request)
            )
        }
    }

    @Test
    fun explicitDeleteCannotClaimBackendRetirement() {
        val request = request()
        val explicit = contextLostAck(request).copy(
            disposition = NtkPhysicalReleaseDisposition.EXPLICIT_DELETE,
            contextReusable = true,
            backendRetirementSerial = 0L,
            backendRetiredNanos = 0L
        )
        assertTrue(NtkTerminalPhysicalReleaseProofValidator.isValid(proof(explicit), request))

        val falseRetirement = explicit.copy(
            backendRetirementSerial = 1L,
            backendRetiredNanos = 2L
        )
        assertFalse(
            NtkTerminalPhysicalReleaseProofValidator.isValid(proof(falseRetirement), request)
        )
    }

    @Test
    fun ackCompletionCannotPredateBackendRetirement() {
        val request = request()
        val ack = contextLostAck(request).copy(
            backendRetiredNanos = 1_000L,
            completedNanos = 999L
        )
        assertFalse(NtkTerminalPhysicalReleaseProofValidator.isValid(proof(ack), request))
    }

    @Test
    fun protocolSerialRelationshipsAreStrictlyIncreasing() {
        val request = request()
        val valid = contextLostAck(request)
        assertTrue(NtkTerminalPhysicalReleaseProofValidator.isValid(proof(valid), request))
        listOf(
            valid.copy(releaseClaimSerial = valid.admissionCloseSerial),
            valid.copy(resourceCompletionWatermark = valid.resourceBarrierSerial),
            valid.copy(feedbackBarrierSerial = valid.resourceCompletionWatermark)
        ).forEach { equalBoundary ->
            assertFalse(
                "accepted non-strict release serial boundary: $equalBoundary",
                NtkTerminalPhysicalReleaseProofValidator.isValid(proof(equalBoundary), request)
            )
        }
    }

    private fun request(): NtkAuthorityReleaseRequest {
        val token = NtkNativeAuthorityToken(
            engineGeneration = 71L,
            authorityGeneration = 72L,
            authority = 73L,
            manifestRevision = 1L,
            manifestDigest = NtkStripDigests.sha256Tokens("validator-manifest"),
            geometryDigest = NtkStripDigests.sha256Tokens("validator-geometry")
        )
        return NtkAuthorityReleaseRequest(token, reducerSurfaceEpoch = 74L, releaseNonce = 75L)
    }

    private fun contextLostAck(
        request: NtkAuthorityReleaseRequest
    ): NtkNativeAuthorityReleaseAck {
        val digest = NtkStripDigests.sha256Tokens("validator-inventory")
        return NtkNativeAuthorityReleaseAck(
            request = request,
            disposition = NtkPhysicalReleaseDisposition.CONTEXT_LOST,
            admissionCloseSerial = 1L,
            releaseClaimSerial = 2L,
            resourceBarrierSerial = 3L,
            resourceCompletionWatermark = 4L,
            feedbackBarrierSerial = 5L,
            capturedResourceCount = 1,
            capturedRgbaBytes = 256L,
            capturedResourceDigest = digest,
            releasedResourceCount = 1,
            releasedRgbaBytes = 256L,
            releasedResourceDigest = digest,
            deletedTextureCount = 0,
            deletedFenceCount = 0,
            releasedBitmapGlobalRefCount = 0,
            drainedUploadCount = 1,
            drainedRetireCount = 0,
            remainingCommandCount = 0,
            remainingResourceCount = 0,
            remainingRgbaBytes = 0L,
            remainingFenceCount = 0,
            remainingBitmapGlobalRefCount = 0,
            remainingNativeCallbackCount = 0,
            backendRetirementSerial = 5L,
            backendRetiredNanos = 6L,
            retiredBackendRemainingThreadCount = 0,
            retiredBackendRemainingEglHandleCount = 0,
            retiredBackendRemainingNativeWindowCount = 0,
            retiredBackendRemainingSwappyLeaseCount = 0,
            retiredBackendRemainingJniGlobalRefCount = 0,
            completedNanos = 6L,
            contextReusable = false,
            success = true
        )
    }

    private fun proof(ack: NtkNativeAuthorityReleaseAck) =
        NtkTerminalPhysicalReleaseProof(ack, remainingKotlinCallbackCount = 0)
}
