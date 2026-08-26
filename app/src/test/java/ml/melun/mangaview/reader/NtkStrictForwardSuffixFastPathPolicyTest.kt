package ml.melun.mangaview.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkStrictForwardSuffixFastPathPolicyTest {
    @Test
    fun onlyOneUnshiftedDisplayPerCanonicalSourceCanPublishAProof() {
        assertTrue(
            NtkStrictForwardSuffixFastPathPolicy.hasCanonicalLaunchDisplayShape(
                intArrayOf(0, 1, 2),
                intArrayOf(0, 1, 2),
                manifestPageCount = 3,
            )
        )
        assertFalse(
            NtkStrictForwardSuffixFastPathPolicy.hasCanonicalLaunchDisplayShape(
                intArrayOf(0, 1, 2, 3),
                intArrayOf(0, 1, 2, 2),
                manifestPageCount = 3,
            )
        )
        assertFalse(
            NtkStrictForwardSuffixFastPathPolicy.hasCanonicalLaunchDisplayShape(
                intArrayOf(1, 2, 3),
                intArrayOf(0, 1, 2),
                manifestPageCount = 3,
            )
        )
        assertFalse(
            NtkStrictForwardSuffixFastPathPolicy.hasCanonicalLaunchDisplayShape(
                intArrayOf(0, 1),
                intArrayOf(0, 1),
                manifestPageCount = 3,
            )
        )
    }

    @Test
    fun completedSuffixSurvivesAppendOnlyStructuralGrowth() {
        assertTrue(canCommit(pageCount = 20, launchPageCount = 15))
    }

    @Test
    fun reverseOrChangedSourceDemandAlwaysFallsThrough() {
        assertFalse(canCommit(activeBefore = 6, activeAfter = 6, allowedFirst = 6))
        assertFalse(canCommit(sourceDemandChanged = true))
        assertFalse(canCommit(activeBefore = 9, activeAfter = 6))
    }

    @Test
    fun rollingMissingOrUnpresentedSuffixCannotSkipWork() {
        assertFalse(canCommit(rolling = true))
        assertFalse(canCommit(physicalDrawPresented = false))
        assertFalse(canCommit(suffixInstalled = false))
    }

    @Test
    fun finalLinearizationRejectsReverseFloorAndProofAba() {
        assertTrue(
            NtkStrictForwardSuffixFastPathPolicy.isCommitProofCurrent(
                capturedProofRevision = 7L,
                currentProofRevision = 7L,
                forwardSourceFloor = 9,
                activeSourceFloor = 9,
                launchShapeValid = true,
                rollingPixelResidency = false,
            )
        )
        assertFalse(
            NtkStrictForwardSuffixFastPathPolicy.isCommitProofCurrent(
                capturedProofRevision = 7L,
                currentProofRevision = 8L,
                forwardSourceFloor = 9,
                activeSourceFloor = 9,
                launchShapeValid = true,
                rollingPixelResidency = false,
            )
        )
        assertFalse(
            NtkStrictForwardSuffixFastPathPolicy.isCommitProofCurrent(
                capturedProofRevision = 7L,
                currentProofRevision = 7L,
                forwardSourceFloor = 9,
                activeSourceFloor = 6,
                launchShapeValid = true,
                rollingPixelResidency = false,
            )
        )
    }

    private fun canCommit(
        rolling: Boolean = false,
        physicalDrawPresented: Boolean = true,
        sourceDemandChanged: Boolean = false,
        pageCount: Int = 15,
        launchPageCount: Int = 15,
        forwardFloor: Int = 9,
        activeBefore: Int = 9,
        activeAfter: Int = 9,
        allowedFirst: Int = 9,
        allowedLast: Int = 14,
        suffixInstalled: Boolean = true,
    ): Boolean = NtkStrictForwardSuffixFastPathPolicy.canCommit(
        rollingPixelResidency = rolling,
        physicalDrawPresented = physicalDrawPresented,
        sourceDemandChanged = sourceDemandChanged,
        pageCount = pageCount,
        launchPageCount = launchPageCount,
        forwardSourceFloor = forwardFloor,
        activeSourceFloorBeforeProof = activeBefore,
        activeSourceFloorAfterProof = activeAfter,
        allowedFirstSource = allowedFirst,
        allowedLastSource = allowedLast,
        suffixInstalled = suffixInstalled,
    )
}
