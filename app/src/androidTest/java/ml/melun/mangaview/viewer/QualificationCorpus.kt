package ml.melun.mangaview.viewer

import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import ml.melun.mangaview.app.SourceRegistry
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.source.CatalogOrder
import ml.melun.mangaview.source.CatalogQuery
import ml.melun.mangaview.source.SeriesKind
import ml.melun.mangaview.source.SourceEpisode
import ml.melun.mangaview.source.SourceSeries
import org.json.JSONArray
import org.json.JSONObject

internal data class CorpusSeriesSample(val kind: SeriesKind, val series: SourceSeries, val chain: List<SourceEpisode>) {
    init {
        require(series.title.isNotBlank()) { "Sampled work title must not be blank" }
        require(chain.size == REQUIRED_CHAIN_EPISODES) {
            "A qualification sample must contain exactly $REQUIRED_CHAIN_EPISODES episodes"
        }
        require(chain.map { it.id }.distinct().size == REQUIRED_CHAIN_EPISODES) {
            "A qualification sample must contain distinct episode identities"
        }
        require(chain.all { it.id.seriesId == series.id }) {
            "A qualification sample chain must remain within its sampled series"
        }
    }

    /** Includes the complete chain so two failures in one series cannot share an identity label. */
    val key: String get() = "${series.id.sourceId.value}/${kind.name}/${series.id.remoteKey}/" +
        chain.joinToString(",") { it.id.remoteKey }
    fun toJson(): JSONObject = JSONObject()
        .put("source", series.id.sourceId.value).put("kind", kind.name)
        .put("seriesKey", series.id.remoteKey).put("title", series.title)
        .put("episodes", JSONArray(chain.map { JSONObject().put("key", it.id.remoteKey).put("title", it.title) }))

    companion object {
        fun fromJson(json: JSONObject): CorpusSeriesSample {
            val sourceId = SourceId(json.getString("source").also { require(it.isNotBlank()) })
            val seriesId = SeriesId(sourceId, json.getString("seriesKey"))
            val title = json.getString("title").also { require(it.isNotBlank()) }
            val episodes = json.getJSONArray("episodes")
            require(episodes.length() == REQUIRED_CHAIN_EPISODES) {
                "A qualification sample must contain exactly $REQUIRED_CHAIN_EPISODES episodes"
            }
            val chain = (0 until episodes.length()).map { index -> episodes.getJSONObject(index).let {
                val key = it.getString("key").also { value -> require(value.isNotBlank()) }
                val episodeTitle = it.getString("title").also { value -> require(value.isNotBlank()) }
                SourceEpisode(EpisodeId(seriesId, key), episodeTitle)
            } }
            require(chain.map { it.id }.distinct().size == REQUIRED_CHAIN_EPISODES) {
                "A qualification sample must contain distinct episode identities"
            }
            return CorpusSeriesSample(SeriesKind.valueOf(json.getString("kind")),
                SourceSeries(seriesId, title), chain)
        }

        const val REQUIRED_CHAIN_EPISODES = 5
    }
}

internal class QualificationCorpus(private val sources: SourceRegistry, private val directory: File) {
    // Catalog metadata for this attempt only; no viewer image-cache access.
    private val catalogSnapshots = mutableMapOf<Pair<SourceId, SeriesKind>, CatalogSnapshot>()
    private val priorDiscoveryFailures = directory.parentFile.resolve("qualification-prior-discovery-failures.json")

    /** Resolve imported one-episode identities from the live catalog and episode pages. */
    fun resolveSingleEpisode(regression: SingleEpisodeRegression): ResolvedSingleEpisodeRegression = runBlocking {
        val source = sources.require(regression.source)
        val series = catalogSeries(source, regression.kind, regression.seriesId)
            ?: error("Mandatory single-episode series disappeared or was duplicated: ${regression.seriesId}")
        val episode = episodePages(source, regression.seriesId).singleOrNull { it.id == regression.episodeId }
            ?: error("Mandatory single-episode disappeared or was duplicated: ${regression.episodeId}")
        ResolvedSingleEpisodeRegression(regression, series, episode)
    }

    /** Live metadata only, shared with the engine's real catalog-row entry diagnostic. */
    fun resolveCatalogEpisode(id: EpisodeId, kind: SeriesKind): Pair<SourceSeries, SourceEpisode> = runBlocking {
        val source = sources.require(id.seriesId.sourceId)
        val series = catalogSeries(source, kind, id.seriesId)
            ?: error("Requested series is absent or duplicated in the live catalog: ${id.seriesId}")
        val episode = episodePages(source, id.seriesId).singleOrNull { it.id == id }
            ?: error("Requested episode is absent or duplicated in the live episode list: $id")
        series to episode
    }

    fun refreshRegression(sample: CorpusSeriesSample): CorpusSeriesSample = runBlocking {
        require(sample.chain.size == CorpusSeriesSample.REQUIRED_CHAIN_EPISODES)
        require(sample.chain.map { it.id }.distinct().size == CorpusSeriesSample.REQUIRED_CHAIN_EPISODES)
        require(sample.chain.all { it.id.seriesId == sample.series.id }) {
            "Mandatory regression chain crosses its sampled series"
        }
        val source = sources.require(sample.series.id.sourceId)
        val series = catalogSeries(source, sample.kind, sample.series.id)
            ?: error("Mandatory failed series disappeared or was duplicated: ${sample.series.id}")
        val episodes = episodePages(source, sample.series.id)
        val byId = episodes.associateBy { it.id }
        check(byId.size == episodes.size) { "Mandatory regression episode list contains duplicate identities" }
        val chain = sample.chain.map { requireNotNull(byId[it.id]) { "Mandatory failed episode disappeared: ${it.id}" } }
        val indices = chain.map { episodes.indexOf(it) }
        check(indices.zipWithNext().all { (older, newer) -> older - newer == 1 }) {
            "Mandatory failed chain changed; it cannot be silently substituted"
        }
        sample.copy(series = series, chain = chain)
    }

    private suspend fun catalogSeries(
        source: ml.melun.mangaview.source.ContentSource,
        kind: SeriesKind,
        target: SeriesId,
    ): SourceSeries? {
        val snapshot = catalogSnapshots.getOrPut(source.id to kind) { CatalogSnapshot() }
        while (true) {
            snapshot.items[target]?.let { return it }
            if (snapshot.complete) return null
            val cursor = snapshot.nextCursor
            check(snapshot.cursors.add(cursor)) { "Live catalog cursor cycle for ${source.id}/$kind" }
            val page = source.catalog(CatalogQuery(kind, CatalogOrder.LATEST, cursor = cursor))
            directory.resolve("regression-catalog-${source.id.value}-${kind.name}-${snapshot.cursors.size}.json")
                .writeText(JSONObject().put("source", source.id.value).put("kind", kind.name)
                    .put("cursor", cursor ?: JSONObject.NULL)
                    .put("nextCursor", page.nextCursor ?: JSONObject.NULL)
                    .put("items", JSONArray(page.items.map { series -> JSONObject()
                        .put("source", series.id.sourceId.value).put("key", series.id.remoteKey)
                        .put("title", series.title) })).toString(2))
            page.items.forEach { series ->
                check(series.id.sourceId == source.id && series.title.isNotBlank()) {
                    "Live catalog contains an invalid identity or title for ${source.id}/$kind"
                }
                check(!snapshot.items.containsKey(series.id)) {
                    "Live catalog contains duplicate identity ${series.id}"
                }
                snapshot.items[series.id] = series
            }
            snapshot.nextCursor = page.nextCursor
            snapshot.complete = page.nextCursor == null
        }
    }

    private class CatalogSnapshot {
        val items = linkedMapOf<SeriesId, SourceSeries>()
        val cursors = mutableSetOf<String?>()
        var nextCursor: String? = null
        var complete = false
    }

    private suspend fun episodePages(
        source: ml.melun.mangaview.source.ContentSource,
        seriesId: SeriesId,
    ): List<SourceEpisode> {
        val result = mutableListOf<SourceEpisode>()
        val identities = mutableSetOf<EpisodeId>()
        val cursors = mutableSetOf<String?>()
        var cursor: String? = null
        while (true) {
            check(cursors.add(cursor)) { "Live episode cursor cycle for $seriesId" }
            val page = source.episodes(seriesId, cursor)
            page.items.forEach { episode ->
                check(episode.id.seriesId == seriesId && episode.title.isNotBlank()) {
                    "Live episode contains an invalid identity or title for $seriesId"
                }
                check(identities.add(episode.id)) {
                    "Live episode list contains duplicate identity ${episode.id}"
                }
                result += episode
            }
            cursor = page.nextCursor ?: return result
        }
    }

    fun discover(seed: Long, worksPerGroup: Int = DEFAULT_WORKS_PER_GROUP): List<CorpusSeriesSample> {
        require(worksPerGroup in 1..MAX_WORKS_PER_GROUP) {
            "worksPerGroup must be between 1 and $MAX_WORKS_PER_GROUP"
        }
        return runBlocking {
        if (priorDiscoveryFailures.exists()) {
            val prior = JSONArray(priorDiscoveryFailures.readText())
            for (index in 0 until prior.length()) {
                val entry = prior.getJSONObject(index)
                val source = sources.require(SourceId(entry.getString("source")))
                source.episodes(SeriesId(source.id, entry.getString("seriesKey")))
            }
        }
        val selected = mutableListOf<CorpusSeriesSample>()
        persistCorpus(seed, worksPerGroup, selected)
        val expectedWorks = GROUP_COUNT * worksPerGroup
        val expectedEpisodes = expectedWorks * CorpusSeriesSample.REQUIRED_CHAIN_EPISODES
        for (sourceId in listOf(SourceId("ntk"), SourceId("wfwf"))) {
            for (kind in listOf(SeriesKind.COMIC, SeriesKind.WEBTOON)) {
                val source = sources.require(sourceId)
                val random = Random(seed xor (sourceId.value.hashCode().toLong() shl 32) xor kind.ordinal.toLong())
                val catalog = source.catalog(CatalogQuery(kind, CatalogOrder.LATEST)).items
                check(catalog.map { it.id }.distinct().size == catalog.size) { "Duplicate live catalog identity: $sourceId/$kind" }
                check(catalog.all { it.id.sourceId == sourceId && it.title.isNotBlank() }) {
                    "Live catalog contains an identity or title from outside $sourceId/$kind"
                }
                directory.resolve("catalog-${sourceId.value}-${kind.name}.json").writeText(JSONArray(catalog.map {
                    JSONObject().put("key", it.id.remoteKey).put("title", it.title)
                }).toString(2))
                var count = 0
                for (series in catalog.shuffled(random)) {
                    if (count == worksPerGroup) break
                    selectionEvent(sourceId, kind, series, "CHECKING_ELIGIBILITY")
                    // Transport/parser failures throw; they never become eligibility exclusions.
                    val episodes = try {
                        source.episodes(series.id).items
                    } catch (failure: Throwable) {
                        val prior = if (priorDiscoveryFailures.exists()) JSONArray(priorDiscoveryFailures.readText()) else JSONArray()
                        prior.put(JSONObject().put("source", source.id.value).put("kind", kind.name)
                            .put("seriesKey", series.id.remoteKey).put("title", series.title)
                            .put("failure", failure.message).put("attempt", directory.name))
                        priorDiscoveryFailures.writeText(prior.toString(2))
                        selectionEvent(sourceId, kind, series, "FAILED_ELIGIBILITY_REQUEST_NOT_EXCLUDED")
                        throw failure
                    }
                    check(episodes.all { it.id.seriesId == series.id && it.title.isNotBlank() }) {
                        "Live episode list contains an identity or title from outside ${series.id}"
                    }
                    check(episodes.map { it.id }.distinct().size == episodes.size) { "Duplicate live episode identity: ${series.id}" }
                    if (episodes.size < CorpusSeriesSample.REQUIRED_CHAIN_EPISODES) {
                        selectionEvent(sourceId, kind, series, "INELIGIBLE_FEWER_THAN_FIVE", episodes.size)
                        continue
                    }
                    val start = random.nextInt(4, episodes.size)
                    selected += CorpusSeriesSample(kind, series, (start downTo start - 4).map(episodes::get))
                    count++
                    // Publish every fixed sample as soon as its series and episode identities
                    // are available. A later discovery failure leaves this partial sample set
                    // intact for review instead of resetting the whole practical attempt.
                    persistCorpus(seed, worksPerGroup, selected)
                    selectionEvent(sourceId, kind, series, "SELECTED", episodes.size)
                }
                check(count == worksPerGroup) { "$sourceId/$kind has only $count eligible live works" }
            }
        }
        check(selected.size == expectedWorks && selected.sumOf { it.chain.size } == expectedEpisodes)
        check(selected.map(CorpusSeriesSample::key).distinct().size == selected.size) {
            "Fresh corpus selection contains a duplicate exact chain"
        }
        check(selected.groupingBy { it.series.id.sourceId.value to it.kind }.eachCount().values.all { it == worksPerGroup }) {
            "Fresh corpus selection does not contain $worksPerGroup works in every source/kind group"
        }
        persistCorpus(seed, worksPerGroup, selected)
        selected.toList()
        }
    }

    private fun persistCorpus(seed: Long, worksPerGroup: Int, selected: List<CorpusSeriesSample>) {
        val sampleCount = selected.size
        val episodeCount = selected.sumOf { it.chain.size }
        val practical = worksPerGroup == PRACTICAL_WORKS_PER_GROUP
        directory.resolve("corpus.json").writeText(JSONObject()
            .put("schema", CORPUS_SCHEMA)
            .put("seed", seed)
            .put("worksPerGroup", worksPerGroup)
            .put("count", sampleCount)
            .put("sampleCount", sampleCount)
            .put("episodeCount", episodeCount)
            .put("requiredCount", GROUP_COUNT * worksPerGroup)
            .put("requiredEpisodes", GROUP_COUNT * worksPerGroup * CorpusSeriesSample.REQUIRED_CHAIN_EPISODES)
            .put("scope", when {
                practical -> "PRACTICAL_20_FIXED_SAMPLE"
                worksPerGroup == DEFAULT_WORKS_PER_GROUP -> "FULL_200_DISCOVERY"
                else -> "CUSTOM_${GROUP_COUNT * worksPerGroup * CorpusSeriesSample.REQUIRED_CHAIN_EPISODES}_DISCOVERY"
            })
            .put("selectionPolicy", when {
                practical -> "FIXED_20_SAMPLE_SELECTION_METADATA_ONLY"
                worksPerGroup == DEFAULT_WORKS_PER_GROUP -> "RERANDOMIZE_ALL_FOUR_GROUPS_EACH_NEW_ATTEMPT"
                else -> "RERANDOMIZE_CUSTOM_WORKS_PER_GROUP"
            })
            .put("failurePolicy", if (practical) "PRESERVE_PARTIAL_FIXED_SAMPLE_NO_GLOBAL_RESET"
                else "RERANDOMIZE_AFTER_DISCOVERY_FAILURE")
            .put("populationScope", "FIRST_LATEST_CATALOG_PAGE_PER_SOURCE_KIND")
            .put("catalogOrder", CatalogOrder.LATEST.name)
            .put("catalogPageScope", "FIRST_PAGE_ONLY")
            .put("episodePageScope", "FIRST_PAGE_ONLY")
            .put("episodeOrderAssumption", "DESCENDING_ORDER_UNVERIFIED")
            .put("selectionMetadataOnly", true)
            .put("qualifyingCredit", false)
            .put("samples", JSONArray(selected.map(CorpusSeriesSample::toJson))).toString(2))
    }

    private fun selectionEvent(source: SourceId, kind: SeriesKind, series: SourceSeries, status: String, count: Int? = null) {
        directory.resolve("selection-events.jsonl").appendText(JSONObject()
            .put("source", source.value).put("kind", kind.name).put("seriesKey", series.id.remoteKey)
            .put("title", series.title).put("status", status).put("episodeCount", count).toString() + "\n")
    }

    private companion object {
        const val CORPUS_SCHEMA = 3
        const val GROUP_COUNT = 4
        const val DEFAULT_WORKS_PER_GROUP = 10
        const val MAX_WORKS_PER_GROUP = 10
        const val PRACTICAL_WORKS_PER_GROUP = 1
    }
}
