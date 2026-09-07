package ml.melun.mangaview.source.ntk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkBrowserRenderPolicyTest {
    @Test
    fun initialAuthorizationKeepsTheFastBoundRenderer() {
        val policy = NtkBrowserRenderPhase.INITIAL_AUTHORIZATION.renderPolicy()

        assertTrue(policy.visible)
        assertTrue(policy.hardwareRaster)
        assertTrue(policy.boundRenderer)
    }

    @Test
    fun activeAdjacentAuthorizationDoesNotCompeteWithVisibleReaderPriority() {
        val policy = NtkBrowserRenderPhase.ADJACENT_AUTHORIZATION.renderPolicy()

        assertTrue(policy.visible)
        assertTrue(policy.hardwareRaster)
        assertFalse(policy.boundRenderer)
    }

    @Test
    fun completedManifestStopsAllBrowserRasterWork() {
        val policy = NtkBrowserRenderPhase.PARKED.renderPolicy()

        assertFalse(policy.visible)
        assertFalse(policy.hardwareRaster)
        assertFalse(policy.boundRenderer)
    }
}
