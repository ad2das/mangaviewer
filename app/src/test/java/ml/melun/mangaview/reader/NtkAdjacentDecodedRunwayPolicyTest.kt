package ml.melun.mangaview.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkAdjacentDecodedRunwayPolicyTest {
    @Test
    fun hostGpuDirectWifiWebtoonParksOnlyAfterTheFullRunwayIsDrawable() {
        assertTrue(
            NtkAdjacentDecodedRunwayPolicy.shouldParkRemainder(
                hostGpuEmulatorRuntime = true,
                directWifi = true,
                episodePath = "/webtoon/1/2",
                requiredRunwayPages = 5,
                drawablePagesAtOrAhead = 5,
            )
        )
        assertFalse(
            NtkAdjacentDecodedRunwayPolicy.shouldParkRemainder(
                hostGpuEmulatorRuntime = true,
                directWifi = true,
                episodePath = "/webtoon/1/2",
                requiredRunwayPages = 5,
                drawablePagesAtOrAhead = 4,
            )
        )
    }

    @Test
    fun physicalMobileAndManhwaKeepTheirExistingPublicationPolicy() {
        val cases = listOf(
            NtkAdjacentDecodedRunwayPolicy.shouldParkRemainder(
                false, true, "/webtoon/1/2", 5, 5,
            ),
            NtkAdjacentDecodedRunwayPolicy.shouldParkRemainder(
                true, false, "/webtoon/1/2", 5, 5,
            ),
            NtkAdjacentDecodedRunwayPolicy.shouldParkRemainder(
                true, true, "/manhwa/1/2", 5, 5,
            ),
        )
        cases.forEach(::assertFalse)
    }

}
