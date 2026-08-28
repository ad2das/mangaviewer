package ml.melun.mangaview.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkHostGpuEmulatorWebtoonControlPlanePolicyTest {
    private fun eligible(
        directWebtoon: Boolean = true,
        emulatorRuntime: Boolean = true,
        directWifiAdjacent: Boolean = false,
        directWifiCurrent: Boolean = true,
        rollingAdmission: Boolean = true,
        initialPageIndex: Int = 16,
    ): Boolean = NtkHostGpuEmulatorWebtoonControlPlanePolicy.isEligible(
        directWebtoon,
        emulatorRuntime,
        directWifiAdjacent,
        directWifiCurrent,
        rollingAdmission,
        initialPageIndex,
    )

    @Test
    fun currentResumeAndAdjacentShareOnlyTheHostEmulatorDirectWifiProfile() {
        assertTrue(eligible())
        assertTrue(eligible(directWifiAdjacent = true, directWifiCurrent = false, initialPageIndex = 0))

        assertFalse(eligible(directWebtoon = false))
        assertFalse(eligible(emulatorRuntime = false))
        assertFalse(eligible(directWifiCurrent = false))
        assertFalse(eligible(rollingAdmission = false))
        assertFalse(eligible(initialPageIndex = 0))
    }

    @Test
    fun coldCurrentSelectsHttp2WithoutChangingItsCanonicalResponseShape() {
        assertTrue(
            NtkHostGpuEmulatorWebtoonControlPlanePolicy.shouldForceHttp2(
                directWebtoon = true,
                emulatorRuntime = true,
                directWifiAdjacent = false,
                directWifiCurrent = true,
                rollingAdmission = true,
            )
        )
        assertFalse(eligible(initialPageIndex = 0))
        assertFalse(
            NtkHostGpuEmulatorWebtoonControlPlanePolicy.shouldForceHttp2(
                directWebtoon = true,
                emulatorRuntime = false,
                directWifiAdjacent = false,
                directWifiCurrent = true,
                rollingAdmission = true,
            )
        )
        assertFalse(
            NtkHostGpuEmulatorWebtoonControlPlanePolicy.shouldForceHttp2(
                directWebtoon = true,
                emulatorRuntime = true,
                directWifiAdjacent = false,
                directWifiCurrent = true,
                rollingAdmission = false,
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeResumeIndex() {
        eligible(initialPageIndex = -1)
    }
}
