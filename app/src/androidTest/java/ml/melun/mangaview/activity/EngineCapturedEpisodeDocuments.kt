package ml.melun.mangaview.activity

import java.io.File
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.engine.api.EpisodeAccessPlan
import ml.melun.mangaview.engine.api.EpisodePlanObserver
import ml.melun.mangaview.engine.api.SourceDocument
import org.json.JSONArray
import org.json.JSONObject

/** Test-only bounded references; export happens after viewer closure, never in the content request. */
internal class EngineCapturedEpisodeDocuments : EpisodePlanObserver {
    private data class Key(val episode: EpisodeId, val replay: String, val revision: String, val epoch: Long)
    private data class Entry(val document: SourceDocument, val plan: EpisodeAccessPlan, val atNanos: Long)
    private val entries = linkedMapOf<Key, Entry>()
    private var bytes = 0L

    @Synchronized fun pageBounds(episode: EpisodeId): Pair<PageId, PageId>? {
        val plans = entries.values.filter { it.plan.manifest.id == episode }
        check(plans.map { it.plan.contentRevision }.distinct().size <= 1) { "Episode revision changed during traversal" }
        val pages = plans.lastOrNull()?.plan?.pages ?: return null
        return pages.first().pageId to pages.last().pageId
    }

    @Synchronized override fun observed(episodeId: EpisodeId, document: SourceDocument, plan: EpisodeAccessPlan) {
        check(plan.manifest.id == episodeId && plan.documentSha256 == document.sha256 && plan.finalDocumentUrl == document.finalUrl)
        val key = Key(episodeId, document.replaySha256, plan.contentRevision, plan.authEpoch)
        if (key in entries) return
        check(entries.size < 16 && document.byteCount <= 32L * 1024 * 1024 - bytes) { "Document observation capacity exceeded" }
        entries[key] = Entry(document, plan, System.nanoTime())
        bytes += document.byteCount
    }

    @Synchronized fun exportAndClear(root: File) {
        try {
            val output = File(root, "episodes").apply { check(mkdir()) }
            val index = JSONArray()
            entries.values.forEachIndexed { number, entry ->
                val documentName = "document-$number.html"
                val planName = "plan-$number.json"
                entry.document.openBody().use { input -> File(output, documentName).outputStream().use { input.copyTo(it) } }
                val plan = entry.plan
                File(output, planName).writeText(JSONObject().apply {
                    put("episodeIdentity", episode(plan.manifest.id)); put("documentFile", documentName)
                    put("documentSha256", entry.document.sha256); put("documentBytes", entry.document.byteCount)
                    put("documentReplaySha256", entry.document.replaySha256)
                    put("finalDocumentUrl", entry.document.finalUrl.toString()); put("observedAtNanos", entry.atNanos)
                    put("contentRevision", plan.contentRevision); put("authEpoch", plan.authEpoch)
                    put("navigationKnown", plan.navigationKnown)
                    put("previousEpisode", plan.manifest.previousEpisodeId?.let(::episode) ?: JSONObject.NULL)
                    put("nextEpisode", plan.manifest.nextEpisodeId?.let(::episode) ?: JSONObject.NULL)
                    put("pages", JSONArray().apply { plan.pages.forEachIndexed { ordinal, page ->
                        put(JSONObject().apply {
                            put("ordinal", ordinal); put("pageIdentity", page(page.pageId)); put("sourceRecord", page.sourceRecord)
                            put("candidates", JSONArray(page.candidates.map { it.toString() }))
                        })
                    } })
                }.toString(2))
                index.put(planName)
            }
            File(output, "index.json").writeText(JSONObject().put("plans", index)
                .put("retainedDocumentBytesBeforeExport", bytes).put("corpusCredit", 0).toString(2))
        } finally { entries.clear(); bytes = 0 }
    }

    private fun episode(id: EpisodeId) = JSONObject().apply {
        put("sourceId", id.seriesId.sourceId.value); put("seriesKey", id.seriesId.remoteKey); put("episodeKey", id.remoteKey)
    }
    private fun page(id: PageId) = episode(id.episodeId).put("pageKey", id.remoteKey)
}
