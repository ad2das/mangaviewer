package ml.melun.mangaview.app

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import ml.melun.mangaview.ViewerApplication
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.source.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in live contract audit. Real provider responses, IDs, cursors and timings are retained. */
@RunWith(AndroidJUnit4::class)
class FourSourceSearchAuditDeviceTest {
    @Test fun ntk() = audit("ntk")
    @Test fun wfwf() = audit("wfwf")
    @Test fun newxtoon() = audit("newxtoon")
    @Test fun goodtoon() = audit("goodtoon")

    private fun audit(provider: String) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val graph = (context.applicationContext as ViewerApplication).graph
        val source = graph.sources.require(SourceId(provider))
        val arguments = InstrumentationRegistry.getArguments()
        val label = arguments.getString("searchAuditLabel", "search-audit")
        val cases = arguments.getString("searchAuditCases", "survival,author,love").split(',').toSet()
        val pauseMillis = arguments.getString("searchAuditPauseMillis", "0").toLong().coerceIn(0, 60_000)
        val output = File(context.getExternalFilesDir(null), "$label/$provider").apply { mkdirs() }
        output.resolve("configuration.json").writeText(JSONObject().put("cases", JSONArray(cases.toList()))
            .put("pauseMillis", pauseMillis).toString(2))
        val evidence = ArrayBlockingQueue<SourceExchangeEvidence>(256)
        val previous = graph.networkEvidenceObserver
        graph.networkEvidenceObserver = SourceExchangeObserver { record ->
            if (record.phase in setOf(SourceExchangePhase.BODY_COMPLETE, SourceExchangePhase.REQUEST_FAILED,
                    SourceExchangePhase.HEADERS)) evidence.offer(record)
        }
        val records = JSONArray()
        val failures = mutableListOf<String>()
        suspend fun search(name: String, query: SourceSearchQuery): SourcePage<SourceSeries>? {
            delay(pauseMillis)
            val record = JSONObject().put("case", name).put("query", query.text)
                .put("kind", query.kind?.name).put("field", query.field.name).put("cursor", query.cursor)
            val start = SystemClock.elapsedRealtime()
            val page = try {
                searchRespectingCooldown(source, query, record).also { result ->
                    record.put("items", JSONArray(result.items.map { item -> JSONObject()
                        .put("id", item.id.remoteKey).put("title", item.title).put("subtitle", item.subtitle) }))
                    record.put("nextCursor", result.nextCursor)
                    check(result.items.map { it.id }.distinct().size == result.items.size) { "duplicate result IDs" }
                    check(result.nextCursor == null || result.nextCursor != query.cursor) { "repeated cursor" }
                }
            } catch (failure: Exception) {
                record.put("error", failure.stackTraceToString())
                failures += "$name: ${failure.message}"
                null
            }
            record.put("elapsedMillis", SystemClock.elapsedRealtime() - start)
            records.put(record)
            output.resolve("results.json").writeText(records.toString(2))
            flushEvidence(evidence, output)
            Log.i("SearchAudit", "$provider $name: ${page?.items?.size} items in ${record.getLong("elapsedMillis")} ms")
            return page
        }
        suspend fun completeSearch(name: String, query: SourceSearchQuery): List<SourceSeries> {
            val seen = linkedMapOf<String, SourceSeries>()
            val cursors = mutableSetOf<String?>()
            var cursor: String? = null
            var exhausted = false
            for (number in 1..160) {
                if (!cursors.add(cursor)) { failures += "$name cursor cycle"; break }
                val page = search("$name-page-$number", query.copy(cursor = cursor)) ?: break
                page.items.forEach { seen[it.id.remoteKey] = it }
                cursor = page.nextCursor
                if (cursor == null) { exhausted = true; break }
            }
            if (!exhausted) failures += "$name did not reach the final page"
            output.resolve("$name-complete.json").writeText(JSONObject().put("query", query.text)
                .put("complete", exhausted).put("count", seen.size)
                .put("items", JSONObject(seen.mapValues { it.value.title })).toString(2))
            return seen.values.toList()
        }
        try {
            val exact = search("exact-title", SourceSearchQuery("화산귀환"))
            if (exact == null) {
                // A provider outage is a failed live gate, never an empty-result pass.
                assertTrue("$provider unavailable: $failures", false)
                return@runBlocking
            }
            if (exact.items.none { it.title.replace(" ", "").contains("화산귀환") }) failures += "known title missing"
            val spaced = search("surrounding-whitespace", SourceSearchQuery("  화산귀환  "))
            if (spaced?.items?.map { it.id } != exact.items.map { it.id }) failures += "whitespace changed results"
            val absent = search("no-match", SourceSearchQuery("mv_no_match_20260917_8fd7"))
            if (absent != null && absent.items.isNotEmpty()) failures += "unrelated results for nonexistent query"
            val survival = if ("survival" in cases) completeSearch("survival", SourceSearchQuery("생존")) else emptyList()
            if (provider == "ntk" && "survival" in cases && survival.none { it.id.remoteKey == "/manhwa/3648" }) {
                failures += "reported regression: 생존 must include 생존게임 /manhwa/3648"
            }
            if (provider != "wfwf" && "author" in cases) completeSearch("author", SourceSearchQuery("비가", field = SearchField.AUTHOR))
            if (provider in setOf("ntk", "wfwf")) {
                val comic = completeSearch("comic-filter", SourceSearchQuery("원피스", SeriesKind.COMIC))
                if (comic.isEmpty()) failures += "known comic missing"
                if (comic.any { !it.id.remoteKey.startsWith(if (provider == "ntk") "/manhwa/" else "comic:") }) {
                    failures += "comic filter leaked webtoons"
                }
                val webtoon = search("webtoon-filter", SourceSearchQuery("화산귀환", SeriesKind.WEBTOON))
                if (webtoon != null && webtoon.items.isEmpty()) failures += "known webtoon missing"
            }
            if ("love" in cases) completeSearch("love", SourceSearchQuery("사랑"))
            output.resolve("verdict.json").writeText(JSONObject().put("failures", JSONArray(failures)).toString(2))
            assertTrue("$provider: ${failures.joinToString()}", failures.isEmpty())
        } finally {
            graph.networkEvidenceObserver = previous
            flushEvidence(evidence, output)
        }
    }

    private fun flushEvidence(queue: ArrayBlockingQueue<SourceExchangeEvidence>, output: File) {
        val batch = mutableListOf<SourceExchangeEvidence>()
        queue.drainTo(batch)
        for (record in batch) {
            record.documentBody?.let { output.resolve("response-${record.requestId}.html").writeBytes(it) }
            output.resolve("network.jsonl").appendText(JSONObject().put("id", record.requestId)
                .put("phase", record.phase.name).put("url", record.requestUrl).put("finalUrl", record.finalUrl)
                .put("status", record.statusCode).put("sha256", record.bodySha256)
                .put("error", record.errorType).toString() + "\n")
        }
    }

    private suspend fun searchRespectingCooldown(
        source: ContentSource,
        query: SourceSearchQuery,
        record: JSONObject,
    ): SourcePage<SourceSeries> {
        val retries = JSONArray()
        repeat(4) { attempt ->
            try {
                return withTimeout(35_000) { source.search(query) }
            } catch (limited: SourceThrottledException) {
                if (attempt == 3 || limited.retryAfterMillis !in 1L..120_000L) throw limited
                retries.put(JSONObject().put("retryAfterMillis", limited.retryAfterMillis))
                record.put("cooldowns", retries)
                delay(limited.retryAfterMillis)
            }
        }
        error("unreachable")
    }
}
