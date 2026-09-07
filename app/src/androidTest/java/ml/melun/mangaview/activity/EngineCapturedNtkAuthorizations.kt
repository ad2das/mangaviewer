package ml.melun.mangaview.activity

import java.io.File
import java.security.MessageDigest
import ml.melun.mangaview.source.ntk.NtkEngineAuthorization
import org.json.JSONArray
import org.json.JSONObject

/** Existing browser proof only: does not initiate network work or retain a browser document. */
internal class EngineCapturedNtkAuthorizations {
    private val entries = mutableListOf<Pair<NtkEngineAuthorization, Long>>()
    private var retainedCharacters = 0L
    private var overflow = 0L
    val observer: (NtkEngineAuthorization) -> Unit = { observed(it) }

    @Synchronized private fun observed(value: NtkEngineAuthorization) {
        if (entries.size >= 32 || retainedCharacters + value.payload.length > 16L * 1024 * 1024) {
            overflow++
            return
        }
        entries += value to System.nanoTime()
        retainedCharacters += value.payload.length
    }

    @Synchronized fun exportAndClear(root: File) {
        try {
            val output = File(root, "ntk-authorization").apply { check(mkdir()) }
            val records = JSONArray()
            entries.forEachIndexed { index, (value, observed) ->
                val bytes = value.payload.toByteArray(Charsets.UTF_8)
                val name = "authorization-$index.json"
                File(output, name).writeBytes(bytes)
                records.put(JSONObject().apply {
                    put("payloadFile", name)
                    put("payloadBytes", bytes.size)
                    put("payloadSha256", MessageDigest.getInstance("SHA-256").digest(bytes)
                        .joinToString("") { "%02x".format(it) })
                    put("sourceId", value.episodeId.seriesId.sourceId.value)
                    put("seriesKey", value.episodeId.seriesId.remoteKey)
                    put("episodeKey", value.episodeId.remoteKey)
                    put("documentSha256", value.documentSha256)
                    put("documentReplaySha256", value.documentReplaySha256)
                    put("authEpoch", value.authEpoch); put("requestId", value.requestId)
                    put("ackObservedElapsedRealtimeNanos", value.ackObservedNanos)
                    put("manifestObservedElapsedRealtimeNanos", value.manifestObservedNanos)
                    put("documentRetiredElapsedRealtimeNanos", value.documentRetiredNanos)
                    put("observedMonotonicNanos", observed)
                    put("rawHttpResponseBytesVerified", false)
                })
            }
            File(output, "index.json").writeText(JSONObject().put("records", records)
                .put("overflow", overflow).put("corpusCredit", 0).toString(2))
            check(overflow == 0L) { "NTK authorization evidence overflow" }
        } finally { entries.clear(); retainedCharacters = 0 }
    }
}
