package ml.melun.mangaview.activity

import java.io.File
import java.security.MessageDigest
import ml.melun.mangaview.source.SourceExchangeEvidence
import ml.melun.mangaview.source.SourceExchangeObserver
import ml.melun.mangaview.source.SourceExchangePhase
import org.json.JSONObject

/** Bounded in-memory observations; raw document export runs after viewer/library closure. */
internal class EngineCapturedHttpExchanges : SourceExchangeObserver {
    private val events = mutableListOf<SourceExchangeEvidence>()
    private var documentBytes = 0L
    private var overflow = 0L
    private var sealed = false
    private var late = 0L

    @Synchronized override fun observed(evidence: SourceExchangeEvidence) {
        if (sealed) { late++; return }
        val bytes = evidence.documentBody?.size ?: 0
        if (events.size >= 4096 || bytes > 32L * 1024 * 1024 - documentBytes) { overflow++; return }
        documentBytes += bytes
        events += evidence
    }

    @Synchronized fun exportAndClear(root: File) {
        sealed = true
        val sealedAt = System.nanoTime()
        try {
            val output = File(root, "http").apply { check(mkdir()) }
            val active = mutableSetOf<Long>()
            File(output, "events.jsonl").bufferedWriter().use { writer ->
                events.forEachIndexed { ordinal, event ->
                    when (event.phase) {
                        SourceExchangePhase.STARTED -> check(active.add(event.requestId))
                        SourceExchangePhase.CLOSED, SourceExchangePhase.REQUEST_FAILED -> check(active.remove(event.requestId))
                        else -> Unit
                    }
                    val bodyFile = event.documentBody?.let { body ->
                        val sha = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
                        check(sha == event.bodySha256 && body.size.toLong() == event.bodyBytes)
                        "exchange-${event.requestId}-body.bin".also { File(output, it).writeBytes(body) }
                    }
                    val record = JSONObject().apply {
                        put("ordinal", ordinal + 1); put("requestId", event.requestId); put("channel", event.channel)
                        put("phase", event.phase.name); put("atMonotonicNs", event.atNanos)
                        put("requestUrl", event.requestUrl); put("method", event.method.name); put("priority", event.priority.name)
                        put("preferQuic", event.preferQuic)
                        put("requestBodySha256", event.requestBodySha256 ?: JSONObject.NULL); put("requestBodyBytes", event.requestBodyBytes)
                        put("statusCode", event.statusCode ?: JSONObject.NULL); put("finalUrl", event.finalUrl ?: JSONObject.NULL)
                        put("contentType", event.contentType ?: JSONObject.NULL); put("contentLength", event.contentLength ?: JSONObject.NULL)
                        put("bodyBytes", event.bodyBytes); put("bodySha256", event.bodySha256 ?: JSONObject.NULL)
                        put("documentBodyFile", bodyFile ?: JSONObject.NULL); put("documentBodyLimitExceeded", event.documentBodyLimitExceeded)
                        put("errorType", event.errorType ?: JSONObject.NULL)
                    }
                    writer.append(record.toString()).append('\n')
                }
            }
            File(output, "seal.json").writeText(JSONObject().apply {
                put("observations", events.size); put("overflow", overflow); put("lateObservations", late)
                put("activeObservedRequests", active.size); put("sealedAtMonotonicNs", sealedAt)
                put("retainedDocumentBytesBeforeExport", documentBytes); put("corpusCredit", 0)
            }.toString(2))
            check(overflow == 0L && late == 0L && active.isEmpty()) { "HTTP observation history is incomplete" }
        } finally { events.clear(); documentBytes = 0 }
    }
}
