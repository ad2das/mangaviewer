package ml.melun.mangaview.reader

internal data class NtkAdjacentAdmission(
    val predecessorCompletionRequired: Boolean,
    val directWifiPhysicalRunway: Boolean,
)

/**
 * Separates the network-neutral reading-order contract from direct-Wi-Fi transport tuning.
 */
internal object NtkAdjacentAdmissionPolicy {
    fun decide(
        adjacentOwned: Boolean,
        wifiTransportActive: Boolean,
        cellularResilientTransportActive: Boolean,
    ): NtkAdjacentAdmission = NtkAdjacentAdmission(
        predecessorCompletionRequired = adjacentOwned,
        directWifiPhysicalRunway = adjacentOwned &&
            wifiTransportActive &&
            !cellularResilientTransportActive,
    )

    /**
     * A request made at the old physical tail must not be promoted to a newly published tail that
     * the viewport has never reached. This is the A(p4 tail) -> publish p5/p6 -> A(p4) race:
     * following the normalized p6 anchor would append the next-next episode while p5/p6 are still
     * below the user's viewport.
     */
    fun shouldRejectStaleForwardTail(
        direction: Int,
        requestedAnchor: Int,
        normalizedAnchor: Int,
        viewportAnchor: Int,
    ): Boolean {
        if (direction <= 0 || normalizedAnchor <= requestedAnchor) return false
        return viewportAnchor < normalizedAnchor
    }
}
