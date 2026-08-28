package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class NtkStrictResumeAnchorPolicyTest {
    @Test
    fun strictGenerationOwnedFloorReplacesStaleStoredAnchor() {
        assertEquals(
            7,
            NtkStrictResumeAnchorPolicy.resolve(
                strictExact = true,
                startAtFirstPage = false,
                strictSourceFloor = 7,
                storedPage = 0,
            ),
        )
    }

    @Test
    fun explicitFirstPageAndOrdinaryReadersKeepTheirExistingSemantics() {
        assertEquals(0, NtkStrictResumeAnchorPolicy.resolve(true, true, 7, 4))
        assertEquals(4, NtkStrictResumeAnchorPolicy.resolve(false, false, 7, 4))
        assertEquals(4, NtkStrictResumeAnchorPolicy.resolve(true, false, 0, 4))
    }
}
