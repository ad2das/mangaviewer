package ml.melun.mangaview.activity

import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.viewer.runtime.EngineSurfaceScene
import org.json.JSONArray
import org.json.JSONObject

internal fun writeEngineSceneEvidence(record: JSONObject, scene: EngineSurfaceScene) {
    fun page(id: PageId) = JSONObject().apply {
        put("sourceId", id.episodeId.seriesId.sourceId.value); put("seriesKey", id.episodeId.seriesId.remoteKey)
        put("episodeKey", id.episodeId.remoteKey); put("pageKey", id.remoteKey)
    }
    record.apply {
        put("width", scene.viewport.widthPx); put("viewportHeight", scene.viewport.heightPx)
        put("coordinateUnitsPerPixel", scene.coordinateUnitsPerPixel)
        put("completeViewportCoverage", scene.completeCoverage)
        put("anchor", scene.anchor.toString())
        put("anchorIdentity", scene.anchor?.let { value -> JSONObject().apply {
            put("pageIdentity", page(value.pageId)); put("sourceYQ32", value.sourceYQ32)
            put("viewportOffsetUnits", value.viewportOffsetUnits)
        } } ?: JSONObject.NULL)
        put("placements", JSONArray().apply { scene.placements.forEach { placement ->
            val tile = placement.texture.tile
            put(JSONObject().apply {
                put("pageId", tile.pageId.toString()); put("sourceSha256", tile.sha256)
                put("contentRevision", tile.contentRevision)
                put("pageIdentity", page(tile.pageId)); put("displayWidth", tile.displayWidth)
                put("rasterHeight", tile.rasterHeight); put("rasterTop", tile.rasterTop); put("rasterBottom", tile.rasterBottom)
                put("sourceWidth", tile.dimensions.widthPx); put("sourceHeight", tile.dimensions.heightPx)
                put("sourceTop", tile.sourceTop); put("sourceBottom", tile.sourceBottom)
                put("screenTopUnits", placement.topPx); put("screenBottomUnits", placement.bottomPx)
            })
        } })
    }
}
