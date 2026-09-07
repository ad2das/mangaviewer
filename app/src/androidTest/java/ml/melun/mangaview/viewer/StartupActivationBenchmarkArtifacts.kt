package ml.melun.mangaview.viewer

import android.content.Context
import android.os.SystemClock
import java.io.File
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.ReadingPosition
import ml.melun.mangaview.viewer.runtime.NativePresentationEvidence
import ml.melun.mangaview.viewer.runtime.ViewerCachedResumeRoute
import org.json.JSONObject

internal data class StartupCacheFingerprint(
    val path: String,
    val exists: Boolean,
    val byteCount: Long,
    val sha256: String?,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("path", path)
        .put("exists", exists)
        .put("byteCount", byteCount)
        .put("sha256", sha256 ?: JSONObject.NULL)
}

internal data class StartupActivationTrialResult(
    val pair: Int,
    val trialInPair: Int,
    val mode: StartupActivationMode,
    val apkSha256: String,
    val episode: EpisodeId,
    val savedPosition: ReadingPosition,
    val cacheBefore: StartupCacheFingerprint,
    val cacheAfter: StartupCacheFingerprint,
    val entryRequestedAtNanos: Long,
    val openStartedAtNanos: Long?,
    val manifestReadyAtNanos: Long?,
    val firstActualSubmittedAtNanos: Long?,
    val firstProxyTimestampNanos: Long?,
    val firstProxyTimestampKind: String?,
    val trackerFirstActualSubmittedAtNanos: Long?,
    val trackerFirstActualPresentedAtNanos: Long?,
    val initialResponseStartedAtNanos: Long?,
    val observedAnchor: ReadingPosition?,
    val observedUserInputRevision: Long?,
    val cachedResumeRoute: ViewerCachedResumeRoute,
    val cachedResumeEpisode: EpisodeId?,
    val cachedResumePageCount: Int?,
    val cachedResumeAtNanos: Long?,
    val candidateToken: Long?,
    val candidateRendererIdentity: Long?,
    val candidateGeneration: Long?,
    val candidateAnchorOffsetUnits: Long?,
    val candidateBufferFrameId: Long?,
    val candidateRegionPageId: String?,
    val candidateRegionIdentityVerified: Boolean,
    val candidateReadableActualContent: Boolean,
    val candidateFullVisualCoverage: Boolean,
    val candidateFullActualCoverage: Boolean,
    val physicalPresentationQualified: Boolean = false,
    val corpusCredit: Int = 0,
)

internal class StartupActivationBenchmarkArtifacts(
    context: Context,
    prefix: String,
    pair: Int,
    trialInPair: Int,
    mode: StartupActivationMode,
) {
    val directory: File = File(
        requireNotNull(context.getExternalFilesDir("ux-evidence")),
        "startup-activation/$prefix-pair-${pair.toString().padStart(2, '0')}-" +
            "trial-$trialInPair-${mode.name.lowercase()}-${SystemClock.elapsedRealtime()}",
    ).apply { check(mkdirs() || isDirectory) }

    fun write(result: StartupActivationTrialResult, evidence: List<NativePresentationEvidence>): File {
        writeEvidence(evidence)
        return directory.resolve("trial.json").apply {
            writeText(result.toJson().toString(2))
        }
    }

    fun writeFailure(
        pair: Int,
        trialInPair: Int,
        mode: StartupActivationMode,
        reason: String,
        apkSha256: String?,
        episode: EpisodeId?,
        savedPosition: ReadingPosition?,
        cacheBefore: StartupCacheFingerprint?,
        cacheAfter: StartupCacheFingerprint?,
    ): File = directory.resolve("trial-failure.json").apply {
        writeText(JSONObject()
            .put("schemaVersion", StartupActivationBenchmarkPolicy.SCHEMA_VERSION)
            .put("diagnosticOnly", true)
            .put("corpusCredit", 0)
            .put("pair", pair)
            .put("trialInPair", trialInPair)
            .put("mode", mode.name)
            .put("valid", false)
            .put("missingCondition", reason)
            .put("apkSha256", apkSha256 ?: JSONObject.NULL)
            .put("episode", episode?.toJson() ?: JSONObject.NULL)
            .put("savedPosition", savedPosition?.toJson() ?: JSONObject.NULL)
            .put("cacheBefore", cacheBefore?.toJson() ?: JSONObject.NULL)
            .put("cacheAfter", cacheAfter?.toJson() ?: JSONObject.NULL)
            .put("physicalPresentationQualified", false)
            .toString(2))
    }

    private fun writeEvidence(evidence: List<NativePresentationEvidence>) {
        directory.resolve("presentation-evidence.tsv").writeText(buildString {
            appendLine(
                "index\trendererIdentity\ttoken\tgeneration\tpresentedNanos\tsubmittedAtNanos\t" +
                    "renderLatencyNanos\treadableActualContent\tfullVisualCoverage\t" +
                    "fullActualCoverage\ttimestampKind\tbufferFrameId\tanchorOrdinal\t" +
                    "anchorOffsetUnits\tgeometryRevision\tuserInputRevision",
            )
            evidence.forEachIndexed { index, sample ->
                append(index).append('\t').append(sample.rendererIdentity).append('\t')
                    .append(sample.token).append('\t')
                    .append(sample.generation).append('\t').append(sample.presentedNanos).append('\t')
                    .append(sample.submittedAtNanos).append('\t').append(sample.renderLatencyNanos)
                    .append('\t').append(sample.readableActualContent)
                    .append('\t').append(sample.fullVisualCoverage)
                    .append('\t').append(sample.fullActualCoverage)
                    .append('\t').append(sample.timestampKind.name)
                    .append('\t').append(sample.bufferFrameId)
                    .append('\t').append(sample.anchorOrdinal)
                    .append('\t').append(sample.anchorOffsetUnits)
                    .append('\t').append(sample.geometryRevision)
                    .append('\t').append(sample.userInputRevision)
                    .appendLine()
            }
        })
    }

    private fun StartupActivationTrialResult.toJson(): JSONObject = JSONObject()
        .put("schemaVersion", StartupActivationBenchmarkPolicy.SCHEMA_VERSION)
        .put("diagnosticOnly", true)
        .put("corpusCredit", corpusCredit)
        .put("valid", true)
        .put("pair", pair)
        .put("trialInPair", trialInPair)
        .put("mode", mode.name)
        .put("apkSha256", apkSha256)
        .put("episode", episode.toJson())
        .put("savedPosition", savedPosition.toJson())
        .put("cacheBefore", cacheBefore.toJson())
        .put("cacheAfter", cacheAfter.toJson())
        .put("cacheUnchanged", cacheBefore == cacheAfter)
        .put("cachedResume", JSONObject()
            .put("route", cachedResumeRoute.name)
            .put("episode", cachedResumeEpisode?.toJson() ?: JSONObject.NULL)
            .put("manifestPageCount", cachedResumePageCount ?: JSONObject.NULL)
            .put("routeAtNanos", cachedResumeAtNanos ?: JSONObject.NULL))
        .put("timestamps", JSONObject()
            .put("entryRequestedAtNanos", entryRequestedAtNanos)
            .put("openStartedAtNanos", openStartedAtNanos ?: JSONObject.NULL)
            .put("manifestReadyAtNanos", manifestReadyAtNanos ?: JSONObject.NULL)
            .put("firstActualSubmittedAtNanos", firstActualSubmittedAtNanos ?: JSONObject.NULL)
            .put("firstProxyTimestampNanos", firstProxyTimestampNanos ?: JSONObject.NULL)
            .put("firstProxyTimestampKind", firstProxyTimestampKind ?: JSONObject.NULL)
            .put("trackerFirstActualSubmittedAtNanos", trackerFirstActualSubmittedAtNanos ?: JSONObject.NULL)
            .put("trackerFirstActualPresentedAtNanos", trackerFirstActualPresentedAtNanos ?: JSONObject.NULL))
        .put("durationsMs", JSONObject()
            .put("firstActualSubmissionFromEntryMs", elapsedMillis(entryRequestedAtNanos, firstActualSubmittedAtNanos))
            .put("manifestFromOpenMs", elapsedMillis(openStartedAtNanos, manifestReadyAtNanos))
            .put("firstActualSubmissionFromOpenMs", elapsedMillis(openStartedAtNanos, firstActualSubmittedAtNanos))
            .put("firstProxyFromOpenMs", elapsedMillis(openStartedAtNanos, firstProxyTimestampNanos))
            .put("firstProxyFromSubmissionMs", elapsedMillis(firstActualSubmittedAtNanos, firstProxyTimestampNanos)))
        .put("initialResponseStartedAtNanos", initialResponseStartedAtNanos ?: JSONObject.NULL)
        .put("observedAnchor", observedAnchor?.toJson() ?: JSONObject.NULL)
        .put("observedUserInputRevision", observedUserInputRevision ?: JSONObject.NULL)
        .put("candidate", JSONObject()
            .put("token", candidateToken ?: JSONObject.NULL)
            .put("rendererIdentity", candidateRendererIdentity ?: JSONObject.NULL)
            .put("generation", candidateGeneration ?: JSONObject.NULL)
            .put("anchorOffsetUnits", candidateAnchorOffsetUnits ?: JSONObject.NULL)
            .put("bufferFrameId", candidateBufferFrameId ?: JSONObject.NULL)
            .put("regionPageKey", candidateRegionPageId ?: JSONObject.NULL)
            .put("regionImageIdentityVerified", candidateRegionIdentityVerified)
            .put("readableActualContent", candidateReadableActualContent)
            .put("fullVisualCoverage", candidateFullVisualCoverage)
            .put("fullActualCoverage", candidateFullActualCoverage))
        .put("physicalPresentationQualified", physicalPresentationQualified)
        .put("physicalPresentationRule", "No EGL latch, proxy, screenshot, or fallback event grants physical display credit")
        .put("startupMeasurementRule", "First readable full-actual native submission only; no full-content readiness wait")

    private fun EpisodeId.toJson(): JSONObject = JSONObject()
        .put("sourceId", seriesId.sourceId.value)
        .put("seriesKey", seriesId.remoteKey)
        .put("episodeKey", remoteKey)

    private fun ReadingPosition.toJson(): JSONObject = JSONObject()
        .put("pageKey", pageId.remoteKey)
        .put("offsetInPageUnits", offsetInPageUnits)

    private fun elapsedMillis(start: Long?, end: Long?): Any =
        if (start == null || end == null || end < start) JSONObject.NULL
        else (end - start) / 1_000_000.0
}
