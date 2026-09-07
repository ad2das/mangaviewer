package ml.melun.mangaview.viewer

import ml.melun.mangaview.viewer.runtime.NativePresentationEvidence
import ml.melun.mangaview.viewer.runtime.PresentedImageRegion
import org.json.JSONArray
import org.json.JSONObject

/** Correlates submission identities only; the screenshot API supplies no buffer/token identity. */
internal object ViewerScreenshotSceneEvidence {
    fun sameSubmission(frame: NativePresentationEvidence, region: PresentedImageRegion): Boolean =
        frame.rendererIdentity == region.rendererIdentity && frame.token == region.token &&
            frame.generation == region.generation && frame.bufferFrameId == region.bufferFrameId &&
            frame.submittedAtNanos == region.submittedAtNanos &&
            frame.renderLatencyNanos == region.renderLatencyNanos && frame.presentedNanos == region.presentedNanos &&
            frame.timestampKind == region.timestampKind &&
            frame.geometryRevision == region.geometryRevision && frame.userInputRevision == region.userInputRevision

    fun candidates(frames: List<NativePresentationEvidence>, regions: List<PresentedImageRegion>): JSONArray =
        JSONArray(frames.sortedByDescending { it.submittedAtNanos }.take(32).map { frame ->
            JSONObject().put("rendererIdentity", frame.rendererIdentity).put("token", frame.token)
                .put("generation", frame.generation).put("bufferFrameId", frame.bufferFrameId)
                .put("submittedAtNanos", frame.submittedAtNanos).put("renderLatencyNanos", frame.renderLatencyNanos)
                .put("presentedNanos", frame.presentedNanos).put("timestampKind", frame.timestampKind.name)
                .put("geometryRevision", frame.geometryRevision).put("userInputRevision", frame.userInputRevision)
                .put("scrollOffsetUnits", frame.scrollOffsetUnits).put("viewportHeightUnits", frame.viewportHeightUnits)
                .put("anchorOrdinal", frame.anchorOrdinal).put("anchorOffsetUnits", frame.anchorOffsetUnits)
                .put("fullActualCoverage", frame.fullActualCoverage)
                .put("regions", JSONArray(regions.filter { sameSubmission(frame, it) }.map(::regionJson)))
        })

    private fun regionJson(region: PresentedImageRegion) = JSONObject()
        .put("sourceId", region.pageId.episodeId.seriesId.sourceId.value)
        .put("seriesKey", region.pageId.episodeId.seriesId.remoteKey)
        .put("episodeKey", region.pageId.episodeId.remoteKey).put("pageKey", region.pageId.remoteKey)
        .put("sourceTopRow", region.sourceTopRow).put("sourceBottomRowExclusive", region.sourceBottomRowExclusive)
        .put("sourceHeightRows", region.sourceHeightRows)
        .put("screenTopPx", region.screenTopPx).put("screenBottomPx", region.screenBottomPx)
        .put("viewportWidthPx", region.viewportWidthPx).put("viewportHeightPx", region.viewportHeightPx)
        .put("imageIdentityVerified", region.imageIdentityVerified)
}
