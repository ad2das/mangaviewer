package ml.melun.mangaview.activity

import java.io.File
import ml.melun.mangaview.data.network.HttpEngineReadDiagnostics
import org.json.JSONObject

/** Diagnostic only; enable explicitly and export after the normal viewer has closed. */
internal class EngineCapturedHttpReads {
    private val records = mutableListOf<Map<String, Any>>()
    private var overflow = 0L
    private val sink: (Map<String, Any>) -> Unit = { record -> synchronized(records) {
        if (records.size < 4096) records += record else overflow++
    } }

    fun start() {
        check(HttpEngineReadDiagnostics.observer == null)
        HttpEngineReadDiagnostics.observer = sink
    }

    fun close(output: File) {
        check(HttpEngineReadDiagnostics.observer === sink)
        HttpEngineReadDiagnostics.observer = null
        synchronized(records) {
            File(output, "http-reads.jsonl").bufferedWriter().use { writer ->
                records.forEachIndexed { index, record ->
                    writer.append(JSONObject(record).put("ordinal", index + 1).toString()).append('\n')
                }
            }
            File(output, "http-reads-close.json").writeText(JSONObject()
                .put("records", records.size).put("overflow", overflow)
                .put("diagnosticOnly", true).put("performanceQualified", false).toString())
            check(overflow == 0L)
        }
    }
}
