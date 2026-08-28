package ml.melun.mangaview.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkStrictLaunchDecodeAnchorPolicyTest {
    @Test
    fun preDrawableSwipeCannotMoveTheDecodeAnchor() {
        assertTrue(NtkStrictLaunchDecodeAnchorPolicy.resolve(true, 0, 0, false))
        assertFalse(NtkStrictLaunchDecodeAnchorPolicy.resolve(true, 1, 0, true))
    }

    @Test
    fun ordinaryDecodeKeepsTheRequestedAnchor() {
        assertTrue(NtkStrictLaunchDecodeAnchorPolicy.resolve(false, 9, 0, true))
        assertFalse(NtkStrictLaunchDecodeAnchorPolicy.resolve(false, 0, 0, false))
    }

    @Test
    fun missingLaunchIdentityFallsBackToTheRequest() {
        assertTrue(NtkStrictLaunchDecodeAnchorPolicy.resolve(true, 4, -1, true))
        assertFalse(NtkStrictLaunchDecodeAnchorPolicy.resolve(true, 4, -1, false))
    }
}
