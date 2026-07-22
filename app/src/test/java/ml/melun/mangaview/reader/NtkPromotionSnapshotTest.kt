package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class NtkPromotionSnapshotTest {
    @Test
    fun promotionSnapshot_beforePartialAllAdmission() {
        val beforeCall = snapshot(
            completed = setOf(0, 1),
            active = setOf(2, 3, 4),
            queued = setOf(5),
            physicalCalls = 2
        )
        assertEquals(2, beforeCall.physicalCallCount)

        val partiallyAdmitted = snapshot(
            completed = setOf(0, 1),
            active = setOf(2, 3, 4),
            queued = setOf(5),
            physicalCalls = 4
        )
        assertEquals(4, partiallyAdmitted.physicalCallCount)

        val allActiveAdmitted = snapshot(
            completed = setOf(0, 1),
            active = setOf(2, 3, 4),
            queued = setOf(5),
            physicalCalls = 5
        )
        assertEquals(5, allActiveAdmitted.physicalCallCount)
    }

    @Test
    fun promotionSnapshot_rejectsUnderAndOverCount() {
        assertRejected {
            snapshot(
                completed = setOf(0, 1),
                active = setOf(2, 3),
                queued = setOf(4),
                physicalCalls = 1
            )
        }
        assertRejected {
            snapshot(
                completed = setOf(0, 1),
                active = setOf(2, 3),
                queued = setOf(4),
                physicalCalls = 5
            )
        }
    }

    @Test
    fun promotionSnapshot_requiresCompletePagePartition() {
        assertRejected {
            snapshot(
                completed = setOf(0),
                active = setOf(1),
                queued = setOf(3),
                physicalCalls = 1,
                pageCount = 4
            )
        }
        assertRejected {
            snapshot(
                completed = setOf(0),
                active = setOf(1),
                queued = setOf(2, 4),
                physicalCalls = 1,
                pageCount = 4
            )
        }
        assertRejected {
            snapshot(
                completed = setOf(0),
                active = setOf(1),
                queued = setOf(1, 2),
                physicalCalls = 1,
                pageCount = 3
            )
        }
    }

    @Test
    fun quarantineStartProof_allowsZeroPartialAndAllPhysicalAdmission() {
        for (physical in listOf(0, 3, 5)) {
            val proof = NtkQuarantineStartProof(
                planReservedAtMs = 1L,
                firstSubmittedAtMs = 2L,
                initialWaveSubmittedAtMs = 3L,
                initialWaveCount = 5,
                submittedOperationCount = 5,
                physicalCallCountAtProof = physical,
                duplicatePhysicalCallCount = 0
            )
            assertEquals(physical, proof.physicalCallCountAtProof)
        }
        val fullySeeded = NtkQuarantineStartProof(
            planReservedAtMs = 10L,
            firstSubmittedAtMs = 10L,
            initialWaveSubmittedAtMs = 10L,
            initialWaveCount = 0,
            submittedOperationCount = 0,
            physicalCallCountAtProof = 0,
            duplicatePhysicalCallCount = 0,
        )
        assertEquals(0, fullySeeded.initialWaveCount)
    }

    private fun snapshot(
        completed: Set<Int>,
        active: Set<Int>,
        queued: Set<Int>,
        physicalCalls: Int,
        pageCount: Int = completed.size + active.size + queued.size
    ): NtkPromotionSnapshot = NtkPromotionSnapshot(
        token = NtkPromotionToken(
            episodePath = "/manhwa/33727/1692251",
            discoveryGeneration = 1L,
            sessionId = 1L,
            planBindingDigest = NtkStripDigests.sha256Tokens("promotion-binding"),
            exactManifestDigest = NtkStripDigests.sha256Tokens("manifest"),
            exactProofDigest = NtkStripDigests.sha256Tokens("proof"),
            nonce = 1L
        ),
        pageCount = pageCount,
        completedPageIndexes = completed,
        activePageIndexes = active,
        queuedPageIndexes = queued,
        physicalCallCount = physicalCalls,
        duplicatePhysicalCallCount = 0
    )

    private fun assertRejected(block: () -> Unit) {
        var rejected = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertEquals(true, rejected)
    }
}
