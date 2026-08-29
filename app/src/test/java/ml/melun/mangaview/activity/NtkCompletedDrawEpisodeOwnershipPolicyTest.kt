package ml.melun.mangaview.activity

import org.junit.Assert.assertEquals
import org.junit.Test

class NtkCompletedDrawEpisodeOwnershipPolicyTest {
    @Test
    fun `reading owner follows the same thirty five percent viewport probe`() {
        assertEquals(
            1,
            NtkCompletedDrawEpisodeOwnershipPolicy.readingIdentityIndex(
                visiblePageTopPx = floatArrayOf(-1_800f, 200f, 1_900f),
                physicalViewportPx = 2_340,
                identityCount = 3,
            ),
        )
        assertEquals(
            0,
            NtkCompletedDrawEpisodeOwnershipPolicy.readingIdentityIndex(
                visiblePageTopPx = floatArrayOf(-200f, 1_000f),
                physicalViewportPx = 2_340,
                identityCount = 2,
            ),
        )
    }

    @Test
    fun `incomplete immutable geometry cannot claim an identity below the reading probe`() {
        assertEquals(
            -1,
            NtkCompletedDrawEpisodeOwnershipPolicy.readingIdentityIndex(
                visiblePageTopPx = floatArrayOf(-100f),
                physicalViewportPx = 2_340,
                identityCount = 2,
            ),
        )
        assertEquals(
            -1,
            NtkCompletedDrawEpisodeOwnershipPolicy.readingIdentityIndex(
                visiblePageTopPx = floatArrayOf(),
                physicalViewportPx = 2_340,
                identityCount = 0,
            ),
        )
    }
}
