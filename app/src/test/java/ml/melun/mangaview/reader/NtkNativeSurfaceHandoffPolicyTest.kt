package ml.melun.mangaview.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkNativeSurfaceHandoffPolicyTest {
    @Test
    fun recognizesTheQualifiedRanchuHostEmulator() {
        assertTrue(
            NtkNativeSurfaceFrameRatePolicy.isEmulatorRuntime(
                fingerprint = "google/sdk_gphone64_x86_64/emu64xa",
                model = "sdk_gphone64_x86_64",
                hardware = "ranchu",
                product = "sdk_gphone64_x86_64",
            )
        )
    }

    @Test
    fun aPhysicalPhoneNeverReceivesTheHostLayerFrameRateVote() {
        val physical = NtkNativeSurfaceFrameRatePolicy.isEmulatorRuntime(
            fingerprint = "samsung/a55x/a55x:15/release-keys",
            model = "SM-A556N",
            hardware = "exynos1480",
            product = "a55xks",
        )
        assertFalse(
            NtkNativeSurfaceFrameRatePolicy.shouldApplyLayerFrameRateVote(
                emulatorRuntime = physical,
                directWifiRendererProfile = true,
            )
        )
    }

    @Test
    fun mobileOrSniProfileOnTheEmulatorRemainsUnchanged() {
        assertFalse(
            NtkNativeSurfaceFrameRatePolicy.shouldApplyLayerFrameRateVote(
                emulatorRuntime = true,
                directWifiRendererProfile = false,
            )
        )
    }

    @Test
    fun onlyDirectWifiOnTheEmulatorReceivesTheLayerFrameRateVote() {
        assertTrue(
            NtkNativeSurfaceFrameRatePolicy.shouldApplyLayerFrameRateVote(
                emulatorRuntime = true,
                directWifiRendererProfile = true,
            )
        )
    }
}
