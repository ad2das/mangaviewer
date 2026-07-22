package ml.melun.mangaview.reader

/** Actual OS priority observed for a physical full-scene decode task. */
enum class NtkDecodePriority { NORMAL, BACKGROUND }

/** Immutable qualification counters for the one-shot full-scene preparation authority. */
data class NtkResidencyCounters(
    val viewportOffers: Long = 0L,
    val viewportDelivered: Long = 0L,
    val viewportCoalesced: Long = 0L,
    val demandFingerprintChanges: Long = 0L,
    val resourceCallbackDemandAdvances: Long = 0L,
    val sourceDemandOffers: Long = 0L,
    val sourceDemandMailboxMaxDepth: Int = 0,
    val hardAdmissions: Long = 0L,
    val softAdmissions: Long = 0L,
    val admissionDemandCancellations: Long = 0L,
    val duplicateAdmissions: Long = 0L,
    val decodeActiveMaxPreStage: Int = 0,
    val decodeActiveMaxPostStage: Int = 0,
    val postStageNormalPriorityRegions: Long = 0L,
    val nativeUploadMax: Int = 0,
    val retireOutstandingMax: Int = 0,
    val retireRequested: Long = 0L,
    val retireAccepted: Long = 0L,
    val retireVetoes: Long = 0L,
    val retireAtZeroShortage: Long = 0L,
    val retireProtectedRequested: Long = 0L,
    val retireVisibleRequested: Long = 0L,
    val leaseReopenWhileRefPositive: Long = 0L,
    val staleCompletions: Long = 0L
) {
    init {
        require(listOf(
            viewportOffers,
            viewportDelivered,
            viewportCoalesced,
            demandFingerprintChanges,
            resourceCallbackDemandAdvances,
            sourceDemandOffers,
            hardAdmissions,
            softAdmissions,
            admissionDemandCancellations,
            duplicateAdmissions,
            postStageNormalPriorityRegions,
            retireRequested,
            retireAccepted,
            retireVetoes,
            retireAtZeroShortage,
            retireProtectedRequested,
            retireVisibleRequested,
            leaseReopenWhileRefPositive,
            staleCompletions
        ).all { it >= 0L })
        require(listOf(
            sourceDemandMailboxMaxDepth,
            decodeActiveMaxPreStage,
            decodeActiveMaxPostStage,
            nativeUploadMax,
            retireOutstandingMax
        ).all { it >= 0 })
        require(viewportOffers == viewportDelivered + viewportCoalesced)
        require(resourceCallbackDemandAdvances == 0L)
        require(sourceDemandOffers == demandFingerprintChanges)
        require(sourceDemandMailboxMaxDepth in 0..1)
        require(admissionDemandCancellations == 0L)
        require(duplicateAdmissions == 0L)
        require(decodeActiveMaxPreStage <=
            NtkRollingResidencyConstants.PRE_STAGE_DECODE_CONCURRENCY)
        require(decodeActiveMaxPostStage == 0)
        require(postStageNormalPriorityRegions == 0L)
        require(nativeUploadMax <= NtkRollingResidencyConstants.MAX_NATIVE_UPLOADS_IN_FLIGHT)
        require(retireOutstandingMax == 0)
        require(retireAtZeroShortage == 0L)
        require(retireProtectedRequested == 0L && retireVisibleRequested == 0L)
        require(retireRequested == 0L && retireAccepted == 0L && retireVetoes == 0L)
        require(leaseReopenWhileRefPositive == 0L && staleCompletions == 0L)
    }
}
