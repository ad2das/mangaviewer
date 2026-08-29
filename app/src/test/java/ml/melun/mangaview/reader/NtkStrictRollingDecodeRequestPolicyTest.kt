package ml.melun.mangaview.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkStrictRollingDecodeRequestPolicyTest {
    @Test
    fun `resident exact suffix does not become an unbounded rolling decode queue`() {
        assertFalse(
            NtkStrictRollingDecodeRequestPolicy.allows(
                rollingPixelResidency = true,
                completionSpanPageCount = 86,
                insideRollingPixelWindow = false,
                initialScrollRunway = false,
                viewportBlocker = false,
                compositorForwardWarm = false,
                physicalForwardWarm = false,
            ),
        )
    }

    @Test
    fun `finite launch runway and physical liveness edges remain immediately decodable`() {
        val exceptions = listOf(
            booleanArrayOf(true, false, false, false, false),
            booleanArrayOf(false, true, false, false, false),
            booleanArrayOf(false, false, true, false, false),
            booleanArrayOf(false, false, false, true, false),
            booleanArrayOf(false, false, false, false, true),
        )
        exceptions.forEach { edge ->
            assertTrue(
                NtkStrictRollingDecodeRequestPolicy.allows(
                    rollingPixelResidency = true,
                    completionSpanPageCount = 86,
                    insideRollingPixelWindow = edge[0],
                    initialScrollRunway = edge[1],
                    viewportBlocker = edge[2],
                    compositorForwardWarm = edge[3],
                    physicalForwardWarm = edge[4],
                ),
            )
        }
    }

    @Test
    fun `short finite chapter completes once while rolling retirement still owns residency`() {
        assertTrue(
            NtkStrictRollingDecodeRequestPolicy.allows(
                rollingPixelResidency = true,
                completionSpanPageCount = 16,
                insideRollingPixelWindow = false,
                initialScrollRunway = false,
                viewportBlocker = false,
                compositorForwardWarm = false,
                physicalForwardWarm = false,
            ),
        )
        assertFalse(
            NtkStrictRollingDecodeRequestPolicy.allows(
                rollingPixelResidency = true,
                completionSpanPageCount =
                    NtkStrictRollingDecodeRequestPolicy.MAX_BOUNDED_COMPLETE_SCENE_PAGES + 1,
                insideRollingPixelWindow = false,
                initialScrollRunway = false,
                viewportBlocker = false,
                compositorForwardWarm = false,
                physicalForwardWarm = false,
            ),
        )
    }

    @Test
    fun `saved tail of a long chapter is a bounded completion span`() {
        assertTrue(
            NtkStrictRollingDecodeRequestPolicy.allows(
                rollingPixelResidency = true,
                completionSpanPageCount = 8,
                insideRollingPixelWindow = false,
                initialScrollRunway = false,
                viewportBlocker = false,
                compositorForwardWarm = false,
                physicalForwardWarm = false,
            ),
        )
    }

    @Test
    fun `non rolling exact sessions retain complete scene behavior`() {
        assertTrue(
            NtkStrictRollingDecodeRequestPolicy.allows(
                rollingPixelResidency = false,
                completionSpanPageCount = 86,
                insideRollingPixelWindow = false,
                initialScrollRunway = false,
                viewportBlocker = false,
                compositorForwardWarm = false,
                physicalForwardWarm = false,
            ),
        )
    }
}
