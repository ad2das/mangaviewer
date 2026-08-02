package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class NtkProofBackedAdjacentReplicaPolicyTest {
    private val candidates = listOf(
        "https://mana.apihost93.com/board_uploads/exact.png",
        "https://booktoki9.org/board_uploads/exact.png",
        "https://booktoki8.org/board_uploads/exact.png",
    )

    @Test
    fun directWifiAdjacentRunwayStripesOneProvedOriginPerPage() {
        val firstHosts = (0 until 4).map { page ->
            NtkProofBackedAdjacentReplicaPolicy.orderedCandidates(
                candidates[page % candidates.size],
                candidates,
                page,
                directWifiAdjacent = true,
            ).first().substringAfter("https://").substringBefore('/')
        }

        assertEquals(
            listOf("mana.apihost93.com", "booktoki9.org", "booktoki8.org", "mana.apihost93.com"),
            firstHosts,
        )
    }

    @Test
    fun nonAdjacentOrNonDirectTransportKeepsCanonicalRoute() {
        assertEquals(
            listOf(candidates.first()),
            NtkProofBackedAdjacentReplicaPolicy.orderedCandidates(
                candidates.first(),
                candidates,
                pageIndex = 1,
                directWifiAdjacent = false,
            ),
        )
    }

}
