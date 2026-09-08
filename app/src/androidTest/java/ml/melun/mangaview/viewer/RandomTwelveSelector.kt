package ml.melun.mangaview.viewer

import java.io.File
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import ml.melun.mangaview.app.SourceRegistry
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.source.CatalogOrder
import ml.melun.mangaview.source.CatalogQuery
import ml.melun.mangaview.source.ContentSource
import ml.melun.mangaview.source.SeriesKind
import ml.melun.mangaview.source.SourceEpisode
import ml.melun.mangaview.source.SourceSeries
import org.json.JSONArray
import org.json.JSONObject

internal data class RandomTwelveCase(
    val sourceId: SourceId,
    val kind: SeriesKind,
    val series: SourceSeries,
    val episode: SourceEpisode,
    val seriesRank: String,
    val episodeRank: String,
) {
    fun json() = JSONObject()
        .put("sourceId", sourceId.value).put("kind", kind.name)
        .put("seriesKey", series.id.remoteKey).put("seriesTitle", series.title)
        .put("episodeKey", episode.id.remoteKey).put("episodeTitle", episode.title)
        .put("seriesRank", seriesRank).put("episodeRank", episodeRank)
}

/** Selection-only live metadata traversal. It never opens a manifest, page, viewer, or cache. */
internal class RandomTwelveSelector(
    private val sources: SourceRegistry,
    private val directory: File,
    seed: ByteArray,
) {
    private val ranker = RandomTwelveRanker(seed)
    private val seedHex = seed.hex()

    init {
        require(seed.size == SEED_BYTES) { "Random-12 seed must contain exactly $SEED_BYTES bytes" }
        require(directory.isDirectory)
    }

    fun select(): List<RandomTwelveCase> = runBlocking {
        val pools = linkedMapOf<Bucket, List<RankedSeries>>()
        // Finish and preserve the complete adapter-reachable series pool before probing eligibility.
        for (bucket in BUCKETS) pools[bucket] = catalogPool(bucket)
        writeJson("catalog-pool.json", JSONObject()
            .put("schema", SCHEMA).put("seedSha256", sha256(seedHex.toByteArray()))
            .put("populationScope", POPULATION_SCOPE)
            .put("buckets", JSONArray(pools.map { (bucket, series) -> bucket.json()
                .put("seriesCount", series.size)
                .put("series", JSONArray(series.map(RankedSeries::json))) })))

        val selected = mutableListOf<RandomTwelveCase>()
        val bucketReceipts = JSONArray()
        for ((bucket, pool) in pools) {
            val source = sources.require(bucket.sourceId)
            val chosen = mutableListOf<RandomTwelveCase>()
            var checked = 0
            for (candidate in pool) {
                if (chosen.size == CASES_PER_BUCKET) break
                checked++
                event(bucket, candidate.series, "CHECKING_EPISODE_BEARING_ELIGIBILITY")
                val episodes = episodePool(source, bucket, candidate.series)
                if (episodes.isEmpty()) {
                    event(bucket, candidate.series, "INELIGIBLE_ZERO_EPISODES", 0)
                    continue
                }
                val rankedEpisodes = episodes.map { episode ->
                    RankedEpisode(episode, ranker.episode(bucket, candidate.series, episode))
                }.sortedBy(RankedEpisode::rank)
                require(rankedEpisodes.map(RankedEpisode::rank).distinct().size == rankedEpisodes.size) {
                    "Episode HMAC rank collision for ${candidate.series.id}"
                }
                val episode = rankedEpisodes.first()
                chosen += RandomTwelveCase(bucket.sourceId, bucket.kind, candidate.series,
                    episode.episode, candidate.rank, episode.rank)
                event(bucket, candidate.series, "SELECTED", episodes.size, episode.episode.id.remoteKey)
            }
            check(chosen.size == CASES_PER_BUCKET) {
                "${bucket.sourceId}/${bucket.kind} has only ${chosen.size} episode-bearing series in its ranked pool"
            }
            selected += chosen
            bucketReceipts.put(bucket.json()
                .put("catalogSeriesCount", pool.size).put("episodeCatalogsChecked", checked)
                .put("higherRankedSeriesNotProbed", pool.size - checked)
                .put("selected", JSONArray(chosen.map(RandomTwelveCase::json))))
        }
        check(selected.size == TOTAL_CASES)
        check(selected.map { it.series.id }.distinct().size == TOTAL_CASES) {
            "Random-12 selection repeated a series identity across buckets"
        }
        check(selected.groupingBy { it.sourceId to it.kind }.eachCount().values.all { it == CASES_PER_BUCKET })

        val plan = JSONObject().put("schema", SCHEMA).put("seed", seedHex)
            .put("algorithm", ALGORITHM).put("populationScope", POPULATION_SCOPE)
            .put("eligibility", "NONEMPTY_COMPLETE_ADAPTER_EPISODE_CURSOR_CHAIN")
            .put("episodeProbePolicy", "INCREASING_SERIES_RANK_UNTIL_THREE_ELIGIBLE")
            .put("shortCircuitEquivalence",
                "Unprobed higher-ranked series cannot displace the first three eligible series in rank order")
            .put("bucketReceipts", bucketReceipts)
            .put("cases", JSONArray(selected.map(RandomTwelveCase::json)))
        val bytes = plan.toString(2).toByteArray()
        atomicWrite("cases.json", bytes)
        directory.resolve("cases.sha256").writeText(sha256(bytes) + "\n")
        selected.toList()
    }

    private suspend fun catalogPool(bucket: Bucket): List<RankedSeries> {
        val source = sources.require(bucket.sourceId)
        val cursors = mutableSetOf<String?>()
        val identities = mutableSetOf<ml.melun.mangaview.core.SeriesId>()
        val result = mutableListOf<SourceSeries>()
        var cursor: String? = null
        var page = 0
        while (true) {
            check(cursors.add(cursor)) { "Catalog cursor cycle for ${bucket.sourceId}/${bucket.kind}: $cursor" }
            val loaded = source.catalog(CatalogQuery(bucket.kind, CatalogOrder.LATEST, cursor = cursor))
            page++
            loaded.items.forEach { series ->
                check(series.id.sourceId == bucket.sourceId && series.title.isNotBlank()) {
                    "Invalid catalog metadata for ${bucket.sourceId}/${bucket.kind}"
                }
                check(identities.add(series.id)) { "Duplicate catalog identity across pages: ${series.id}" }
                result += series
            }
            append("catalog-pages.jsonl", bucket.json().put("pageOrdinal", page)
                .put("cursor", cursor ?: JSONObject.NULL).put("nextCursor", loaded.nextCursor ?: JSONObject.NULL)
                .put("itemCount", loaded.items.size)
                .put("items", JSONArray(loaded.items.map(::seriesJson))).toString())
            cursor = loaded.nextCursor ?: break
        }
        check(result.isNotEmpty()) { "Empty live catalog for ${bucket.sourceId}/${bucket.kind}" }
        val ranked = result.map { RankedSeries(it, ranker.series(bucket, it)) }.sortedBy(RankedSeries::rank)
        require(ranked.map(RankedSeries::rank).distinct().size == ranked.size) {
            "Series HMAC rank collision for ${bucket.sourceId}/${bucket.kind}"
        }
        return ranked
    }

    private suspend fun episodePool(
        source: ContentSource,
        bucket: Bucket,
        series: SourceSeries,
    ): List<SourceEpisode> {
        val cursors = mutableSetOf<String?>()
        val identities = mutableSetOf<ml.melun.mangaview.core.EpisodeId>()
        val result = mutableListOf<SourceEpisode>()
        var cursor: String? = null
        var page = 0
        while (true) {
            check(cursors.add(cursor)) { "Episode cursor cycle for ${series.id}: $cursor" }
            // Failures deliberately escape. A failed request is never converted to ineligibility.
            val loaded = source.episodes(series.id, cursor)
            page++
            loaded.items.forEach { episode ->
                check(episode.id.seriesId == series.id && episode.title.isNotBlank()) {
                    "Invalid episode metadata for ${series.id}"
                }
                check(identities.add(episode.id)) { "Duplicate episode identity across pages: ${episode.id}" }
                result += episode
            }
            append("episode-pages.jsonl", bucket.json().put("seriesKey", series.id.remoteKey)
                .put("seriesRank", ranker.series(bucket, series)).put("pageOrdinal", page)
                .put("cursor", cursor ?: JSONObject.NULL).put("nextCursor", loaded.nextCursor ?: JSONObject.NULL)
                .put("itemCount", loaded.items.size)
                .put("items", JSONArray(loaded.items.map(::episodeJson))).toString())
            cursor = loaded.nextCursor ?: break
        }
        return result
    }

    private fun event(
        bucket: Bucket,
        series: SourceSeries,
        status: String,
        episodeCount: Int? = null,
        episodeKey: String? = null,
    ) = append("selection-events.jsonl", bucket.json().put("seriesKey", series.id.remoteKey)
        .put("seriesTitle", series.title).put("seriesRank", ranker.series(bucket, series))
        .put("status", status).put("episodeCount", episodeCount ?: JSONObject.NULL)
        .put("episodeKey", episodeKey ?: JSONObject.NULL).toString())

    private fun append(name: String, line: String) = directory.resolve(name).appendText(line + "\n")
    private fun writeJson(name: String, value: JSONObject) = directory.resolve(name).writeText(value.toString(2))

    private fun atomicWrite(name: String, bytes: ByteArray) {
        val target = directory.resolve(name)
        check(!target.exists()) { "Frozen selection already exists: $target" }
        val temporary = directory.resolve(".$name-${System.nanoTime()}.tmp")
        temporary.writeBytes(bytes)
        check(temporary.renameTo(target)) { "Could not atomically freeze random selection" }
    }

    private fun seriesJson(value: SourceSeries) = JSONObject()
        .put("key", value.id.remoteKey).put("title", value.title)
        .put("subtitle", value.subtitle ?: JSONObject.NULL)

    private fun episodeJson(value: SourceEpisode) = JSONObject()
        .put("key", value.id.remoteKey).put("title", value.title)
        .put("publishedAtEpochMillis", value.publishedAtEpochMillis ?: JSONObject.NULL)
        .put("pageCountHint", value.pageCountHint ?: JSONObject.NULL)

    private data class RankedSeries(val series: SourceSeries, val rank: String) {
        fun json() = JSONObject().put("key", series.id.remoteKey).put("title", series.title).put("rank", rank)
    }
    private data class RankedEpisode(val episode: SourceEpisode, val rank: String)

    internal data class Bucket(val sourceId: SourceId, val kind: SeriesKind) {
        fun json() = JSONObject().put("sourceId", sourceId.value).put("kind", kind.name)
    }

    private companion object {
        const val SCHEMA = 1
        const val SEED_BYTES = 32
        const val CASES_PER_BUCKET = 3
        const val TOTAL_CASES = 12
        const val ALGORITHM = "HMAC_SHA256_DOMAIN_SEPARATED_LOWEST_RANK_V1"
        const val POPULATION_SCOPE = "ALL_SERIES_REACHABLE_THROUGH_INSTALLED_ADAPTER_CURSOR_CHAIN_AT_SELECTION_TIME"
        val BUCKETS = listOf(SourceId("ntk"), SourceId("wfwf")).flatMap { source ->
            listOf(SeriesKind.COMIC, SeriesKind.WEBTOON).map { kind -> Bucket(source, kind) }
        }
    }
}

internal class RandomTwelveRanker(private val seed: ByteArray) {
    init { require(seed.size == 32) }

    fun series(bucket: RandomTwelveSelector.Bucket, value: SourceSeries): String =
        rank("series", bucket.sourceId.value, bucket.kind.name, value.id.remoteKey)

    fun episode(
        bucket: RandomTwelveSelector.Bucket,
        series: SourceSeries,
        value: SourceEpisode,
    ): String = rank("episode", bucket.sourceId.value, bucket.kind.name,
        series.id.remoteKey, value.id.remoteKey)

    private fun rank(vararg fields: String): String {
        require(fields.all { '\u0000' !in it }) { "Rank identity contains a domain separator" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(seed, "HmacSHA256"))
        return mac.doFinal(fields.joinToString("\u0000").toByteArray(Charsets.UTF_8)).hex()
    }
}

internal fun parseRandomTwelveSeed(value: String): ByteArray {
    require(value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
        "random12Seed must contain exactly 64 hexadecimal characters"
    }
    return ByteArray(32) { index -> value.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
}

private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }
private fun sha256(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).hex()
