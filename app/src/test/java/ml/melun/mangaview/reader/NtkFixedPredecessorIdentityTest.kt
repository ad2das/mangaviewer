package ml.melun.mangaview.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkFixedPredecessorIdentityTest {
    @Test
    fun firstGenerationAloneRequiresZeroPredecessor() {
        assertFalse(NtkFixedPredecessorIdentity.invalid(1, 1, 0, 0, 0))
        assertTrue(NtkFixedPredecessorIdentity.invalid(1, 1, 1, 1, 1))
    }

    @Test
    fun collectionMayBeginMidstreamWithoutInventingAFirstGeneration() {
        assertFalse(NtkFixedPredecessorIdentity.invalid(77, 91, 76, 90, 103))
    }

    @Test
    fun laterGenerationRequiresStrictlyOlderExactIdentityComponents() {
        assertTrue(NtkFixedPredecessorIdentity.invalid(77, 91, 0, 0, 0))
        assertTrue(NtkFixedPredecessorIdentity.invalid(77, 91, 77, 90, 103))
        assertTrue(NtkFixedPredecessorIdentity.invalid(77, 91, 76, 91, 103))
        assertTrue(NtkFixedPredecessorIdentity.invalid(77, 91, 76, 90, 0))
    }
}
