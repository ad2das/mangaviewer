package ml.melun.mangaview.viewer

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.ReadingPosition
import ml.melun.mangaview.viewer.runtime.NativePresentationEvidence
import ml.melun.mangaview.viewer.runtime.PresentationTimestampKind
import ml.melun.mangaview.viewer.runtime.PresentedImageRegion
import ml.melun.mangaview.viewer.runtime.ViewerCachedResumeDiagnostic
import ml.melun.mangaview.viewer.runtime.ViewerCachedResumeRoute

/**
 * Frozen method for the debug-only eager-vs-first-use startup comparison.
 *
 * This is deliberately a diagnostic policy. It can decide whether the paired comparison is
 * informative, but it never grants corpus credit or turns a compositor timestamp into physical
 * presentation evidence.
 */
internal enum class StartupActivationMode {
    EAGER,
    FIRST_USE,
    ;

    companion object {
        fun parse(value: String?): StartupActivationMode = when (value?.trim()?.uppercase()) {
            "EAGER" -> EAGER
            "FIRST_USE", "FIRST-USE", "FIRSTUSE" -> FIRST_USE
            else -> error("startupMode must be EAGER or FIRST_USE; got '$value'")
        }
    }
}

internal object StartupActivationBenchmarkPolicy {
    const val SCHEMA_VERSION = 1
    const val INITIAL_PAIRS = 5
    const val MAXIMUM_PAIRS = 10
    const val FIRST_SUBMISSION_TIMEOUT_MILLIS = 15_000L
    const val PRIMARY_METRIC = "firstActualSubmissionFromEntryMs"
    const val PRIMARY_FORMULA =
        "(firstActualSubmittedAtNanos - entryRequestedAtNanos) / 1_000_000.0"

    /** Odd pairs are EAGER then FIRST_USE; even pairs reverse the order. */
    fun expectedMode(pair: Int, trialInPair: Int): StartupActivationMode {
        require(pair in 1..MAXIMUM_PAIRS) { "pair must be in 1..$MAXIMUM_PAIRS" }
        require(trialInPair in 0..1) { "trialInPair must be 0 or 1" }
        val eagerFirst = pair % 2 == 1
        return if ((trialInPair == 0) == eagerFirst) {
            StartupActivationMode.EAGER
        } else {
            StartupActivationMode.FIRST_USE
        }
    }

    fun initialOrder(): List<List<StartupActivationMode>> = (1..INITIAL_PAIRS).map { pair ->
        listOf(expectedMode(pair, 0), expectedMode(pair, 1))
    }

    fun allOrder(): List<List<StartupActivationMode>> = (1..MAXIMUM_PAIRS).map { pair ->
        listOf(expectedMode(pair, 0), expectedMode(pair, 1))
    }

    /** Positive differences favor EAGER: FIRST_USE elapsed minus EAGER elapsed. */
    fun pairedDifference(firstUseMillis: Double, eagerMillis: Double): Double =
        firstUseMillis - eagerMillis
}

/** Shared endpoint predicate so negative controls exercise the exact production probe rule. */
internal object StartupActivationEvidencePolicy {
    val invalidTerminalKinds = setOf(
        PresentationTimestampKind.CANCELLED,
        PresentationTimestampKind.DROPPED,
        PresentationTimestampKind.CONTEXT_LOST,
    )

    fun completeLeaseMatches(
        diagnostic: ViewerCachedResumeDiagnostic?,
        episode: EpisodeId,
        expectedPageCount: Int,
    ): Boolean = diagnostic?.route == ViewerCachedResumeRoute.COMPLETE_LEASE_OPENED &&
        diagnostic.episodeId == episode && diagnostic.manifestPageCount == expectedPageCount &&
        diagnostic.routeAtNanos != null && diagnostic.routeAtNanos > 0L

    fun firstActualSubmissionMatches(
        sample: NativePresentationEvidence,
        region: PresentedImageRegion?,
        expectedPosition: ReadingPosition,
        firstSubmittedGeneration: Long?,
    ): Boolean = sample.readableActualContent && sample.fullVisualCoverage &&
        sample.fullActualCoverage && sample.submittedAtNanos > 0L &&
        sample.bufferFrameId > 0L && sample.userInputRevision == 0L &&
        sample.timestampKind !in invalidTerminalKinds &&
        (firstSubmittedGeneration == null || sample.generation == firstSubmittedGeneration) &&
        sample.anchorOffsetUnits == expectedPosition.offsetInPageUnits &&
        region != null && region.rendererIdentity == sample.rendererIdentity &&
        region.token == sample.token && region.generation == sample.generation &&
        region.bufferFrameId == sample.bufferFrameId &&
        region.submittedAtNanos == sample.submittedAtNanos &&
        region.timestampKind == sample.timestampKind &&
        region.pageId == expectedPosition.pageId &&
        region.userInputRevision == 0L && region.imageIdentityVerified
}
