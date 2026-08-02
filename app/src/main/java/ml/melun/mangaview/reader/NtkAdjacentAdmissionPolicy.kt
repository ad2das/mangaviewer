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
}
