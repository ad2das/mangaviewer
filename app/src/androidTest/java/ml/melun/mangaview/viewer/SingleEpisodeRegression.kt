package ml.melun.mangaview.viewer

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.source.SeriesKind
import ml.melun.mangaview.source.SourceEpisode
import ml.melun.mangaview.source.SourceSeries
import org.json.JSONArray
import org.json.JSONObject

/**
 * An imported one-episode failure.  It deliberately contains no cached title,
 * adjacent episode, or five-episode padding: those values must come from the
 * designated device's live source calls.
 */
internal data class SingleEpisodeRegression(
    val source: SourceId,
    val kind: SeriesKind,
    val seriesKey: String,
    val episodeKey: String,
    val provenance: List<JSONObject>,
) {
    init {
        require(source.value == "ntk" || source.value == "wfwf") {
            "Single-episode regression has an unsupported source"
        }
        require(seriesKey.isNotBlank()) { "Single-episode regression has no series identity" }
        require(episodeKey.isNotBlank()) { "Single-episode regression has no episode identity" }
        require(provenance.isNotEmpty()) { "Single-episode regression has no provenance" }
        provenance.forEach { item ->
            require(item.optString("artifact").isNotBlank()) {
                "Single-episode regression provenance has no artifact"
            }
            require(item.optString("classification").isNotBlank()) {
                "Single-episode regression provenance has no classification"
            }
            val reason = item.opt("reason")
            require(reason != null && reason != JSONObject.NULL && when (reason) {
                is String -> reason.isNotBlank()
                is JSONArray -> reason.length() > 0
                is JSONObject -> reason.keys().hasNext()
                else -> false
            }) {
                "Single-episode regression provenance has no failure reason"
            }
        }
    }

    val seriesId: SeriesId get() = SeriesId(source, seriesKey)
    val episodeId: EpisodeId get() = EpisodeId(seriesId, episodeKey)

    fun toJson(): JSONObject = JSONObject()
        .put("source", source.value)
        .put("kind", kind.name)
        .put("seriesKey", seriesKey)
        .put("episodeKey", episodeKey)
        .put("role", ROLE)
        .put("classification", CLASSIFICATION)
        .put("provenance", JSONArray(provenance.map { JSONObject(it.toString()) }))

    companion object {
        const val ROLE = "MANDATORY_SINGLE_EPISODE_NO_CORPUS_CREDIT"
        const val CLASSIFICATION = "SINGLE_EPISODE_REGRESSION"

        private val ALLOWED_FIELDS = setOf(
            "source", "kind", "seriesKey", "episodeKey", "role", "classification", "provenance",
        )

        fun fromJson(json: JSONObject): SingleEpisodeRegression {
            val unexpected = mutableListOf<String>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key !in ALLOWED_FIELDS) unexpected += key
            }
            require(unexpected.isEmpty()) {
                "Single-episode regression has unsupported fields: ${unexpected.joinToString()}"
            }
            require(json.getString("role") == ROLE) {
                "Single-episode regression has an invalid role"
            }
            require(json.getString("classification") == CLASSIFICATION) {
                "Single-episode regression has an invalid classification"
            }
            val source = SourceId(json.getString("source").also { require(it.isNotBlank()) })
            val kind = SeriesKind.valueOf(json.getString("kind"))
            val seriesKey = json.getString("seriesKey").also { require(it.isNotBlank()) }
            val episodeKey = json.getString("episodeKey").also { require(it.isNotBlank()) }
            val rawProvenance = json.getJSONArray("provenance")
            require(rawProvenance.length() > 0) { "Single-episode regression has no provenance" }
            val provenance = (0 until rawProvenance.length()).map { index ->
                JSONObject(rawProvenance.getJSONObject(index).toString())
            }
            return SingleEpisodeRegression(source, kind, seriesKey, episodeKey, provenance)
        }
    }
}

internal data class ResolvedSingleEpisodeRegression(
    val regression: SingleEpisodeRegression,
    val series: SourceSeries,
    val episode: SourceEpisode,
) {
    val kind: SeriesKind get() = regression.kind
}
