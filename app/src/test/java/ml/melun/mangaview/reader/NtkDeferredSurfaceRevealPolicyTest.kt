package ml.melun.mangaview.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkDeferredSurfaceRevealPolicyTest {
    @Test
    fun hostDirectWifiResumeWaitsForContinuousViewport() {
        assertFalse(
            reveal(
                emulator = true,
                directResume = true,
                visible = true,
                continuous = false,
                terminal = false,
            )
        )
        assertTrue(
            reveal(
                emulator = true,
                directResume = true,
                visible = true,
                continuous = true,
                terminal = false,
            )
        )
    }

    @Test
    fun exactShortTerminalTailRemainsAnHonestReveal() {
        assertTrue(
            reveal(
                emulator = true,
                directResume = true,
                visible = true,
                continuous = false,
                terminal = true,
            )
        )
    }

    @Test
    fun legacyProfilesKeepProgressiveFirstPixels() {
        assertTrue(reveal(false, true, true, false, false))
        assertTrue(reveal(true, false, true, false, false))
    }

    @Test
    fun noProfileMayRevealWithoutActualPixels() {
        assertFalse(reveal(false, false, false, true, true))
        assertFalse(reveal(true, true, false, true, true))
    }

    private fun reveal(
        emulator: Boolean,
        directResume: Boolean,
        visible: Boolean,
        continuous: Boolean,
        terminal: Boolean,
    ): Boolean = NtkDeferredSurfaceRevealPolicy.shouldReveal(
        emulatorRuntime = emulator,
        directWifiForwardOnlyResume = directResume,
        visibleActualPixels = visible,
        continuousActualViewport = continuous,
        exactTerminalTailActual = terminal,
    )
}
