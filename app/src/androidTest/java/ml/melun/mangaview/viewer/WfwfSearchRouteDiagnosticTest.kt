package ml.melun.mangaview.viewer

import androidx.test.platform.app.InstrumentationRegistry
import java.net.URLEncoder
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import ml.melun.mangaview.data.network.HttpEngineSourceTransport
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.readBytes
import ml.melun.mangaview.source.wfwf.DEFAULT_WFWF_ORIGIN
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

/** Device-only document diagnosis; no viewer images, cache preparation, or corpus credit. */
class WfwfSearchRouteDiagnosticTest {
    @Test
    fun recordProviderSearchFormAndResponses() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = requireNotNull(context.getExternalFilesDir("ux-evidence"))
            .resolve("wfwf-search-route-${System.nanoTime()}").apply { check(mkdirs()) }
        val query = "마왕의 딸은 너무 착해!!"
        val routes = listOf("comic-query.html" to "/cm?q=${URLEncoder.encode(query, "EUC-KR")}",
            "comic-search.html" to "/cm?o=n&pg=1&t3=&q=${URLEncoder.encode(query, "EUC-KR")}",
            "comic.html" to "/cm?o=n&pg=1&t3=",
            "detail.html" to "/cl?toon=10001",
            "short-search.html" to "/sh?q=${URLEncoder.encode("마왕", "EUC-KR")}",
            "common.js" to "/assets/js/common.js?v=1001") +
            listOf("UTF-8", "EUC-KR").flatMap { encoding ->
                listOf("sh", "search.html").map { route ->
                    "$route-$encoding.html" to "/$route?q=${URLEncoder.encode(query, encoding)}"
                }
            }
        val records = JSONArray()
        try {
            HttpEngineSourceTransport(context, "Mozilla/5.0 Android", protocolAlternatesEnabled = false).use { transport ->
                for ((name, path) in routes) {
                    val request = SourceRequest(DEFAULT_WFWF_ORIGIN + path)
                    val response = transport.execute(request)
                    val status = response.statusCode
                    val url = response.finalUrl
                    val bytes = response.readBytes(8 * 1024 * 1024)
                    directory.resolve(name).writeBytes(bytes)
                    records.put(JSONObject().put("file", name).put("requestedUrl", request.url)
                        .put("finalUrl", url).put("status", status).put("bytes", bytes.size)
                        .put("sha256", MessageDigest.getInstance("SHA-256").digest(bytes)
                            .joinToString("") { "%02x".format(it) }))
                }
            }
        } finally {
            directory.resolve("diagnostic.json").writeText(JSONObject()
                .put("scope", "DOCUMENT_DIAGNOSTIC_NO_CORPUS_CREDIT")
                .put("corpusCredit", 0).put("requests", records).toString(2))
        }
    }
}
