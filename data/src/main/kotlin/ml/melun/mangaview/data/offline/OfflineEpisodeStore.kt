package ml.melun.mangaview.data.offline

import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.data.cache.CachedPage
import ml.melun.mangaview.data.cache.PageCacheKey
import ml.melun.mangaview.source.SourceEpisode
import ml.melun.mangaview.source.SourceSeries

class OfflineEpisodeStore(
    private val root: File,
    private val ioDispatcher: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val mutableEpisodes = MutableStateFlow<List<DownloadedEpisode>>(emptyList())
    private var loaded = false
    private var entries: Map<EpisodeId, StoredEpisode> = emptyMap()

    val episodes: StateFlow<List<DownloadedEpisode>> = mutableEpisodes.asStateFlow()

    suspend fun load() = withContext(ioDispatcher) { mutex.withLock { loadLocked() } }

    suspend fun manifest(episodeId: EpisodeId): EpisodeManifest? = entry(episodeId)?.manifest

    suspend fun episodes(seriesId: SeriesId): List<SourceEpisode> = withContext(ioDispatcher) {
        mutex.withLock {
            loadLocked()
            entries.values.filter { it.summary.series.id == seriesId }
                .sortedByDescending { it.summary.savedAtEpochMillis }
                .map { it.summary.episode }
        }
    }

    suspend fun find(pageId: PageId): CachedPage? = entry(pageId.episodeId)?.pages?.get(pageId)

    suspend fun save(
        series: SourceSeries,
        episode: SourceEpisode,
        manifest: EpisodeManifest,
        pages: List<CachedPage>,
    ) = withContext(ioDispatcher) {
        require(episode.id == manifest.id)
        require(manifest.pages.map { it.id } == pages.map { it.pageId })
        mutex.withLock { saveLocked(series, episode, manifest, pages) }
    }

    suspend fun remove(episodeId: EpisodeId) = withContext(ioDispatcher) {
        mutex.withLock {
            loadLocked()
            val current = entries[episodeId] ?: return@withLock
            safeDeleteTree(current.pages.values.first().file.parentFile ?: return@withLock)
            publish(entries - episodeId)
        }
    }

    private suspend fun entry(episodeId: EpisodeId): StoredEpisode? = withContext(ioDispatcher) {
        mutex.withLock {
            loadLocked()
            entries[episodeId]
        }
    }

    private fun loadLocked() {
        if (loaded) return
        ensureRoot()
        val found = root.listFiles().orEmpty().asSequence()
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .mapNotNull { directory -> runCatching { OfflineEpisodeCodec.read(directory) }.getOrNull() }
            .associateBy { it.summary.episode.id }
        loaded = true
        publish(found)
        root.listFiles().orEmpty().filter { it.name.startsWith(".stage-") }.forEach(::safeDeleteTree)
    }

    private fun saveLocked(
        series: SourceSeries,
        episode: SourceEpisode,
        manifest: EpisodeManifest,
        pages: List<CachedPage>,
    ) {
        loadLocked()
        val stage = File(root, ".stage-${UUID.randomUUID()}")
        require(stage.mkdir()) { "Cannot create offline staging directory" }
        try {
            val copied = copyPages(stage, pages)
            val stored = StoredEpisode(
                DownloadedEpisode(series, episode, pages.size, pages.sumOf(CachedPage::byteCount), clock()),
                manifest,
                copied.associateBy(CachedPage::pageId),
            )
            OfflineEpisodeCodec.write(File(stage, OfflineEpisodeCodec.MANIFEST_NAME), stored)
            val destination = File(root, episodeDirectoryName(episode.id))
            if (destination.exists()) safeDeleteTree(destination)
            require(stage.renameTo(destination)) { "Cannot publish offline episode" }
            val published = OfflineEpisodeCodec.read(destination)
            publish(entries + (episode.id to published))
        } catch (failure: Throwable) {
            safeDeleteTree(stage)
            throw failure
        }
    }

    private fun copyPages(stage: File, pages: List<CachedPage>): List<CachedPage> = pages.mapIndexed { index, page ->
        val destination = File(stage, OfflineEpisodeCodec.pageName(index))
        page.file.copyTo(destination, overwrite = true)
        require(destination.length() == page.byteCount) { "Offline page copy is truncated" }
        page.copy(file = destination)
    }

    private fun publish(next: Map<EpisodeId, StoredEpisode>) {
        entries = next
        mutableEpisodes.value = next.values.map(StoredEpisode::summary)
            .sortedByDescending(DownloadedEpisode::savedAtEpochMillis)
    }

    private fun ensureRoot() {
        require(root.isDirectory || root.mkdirs()) { "Offline storage is unavailable" }
    }

    private fun safeDeleteTree(target: File) {
        val canonicalRoot = root.canonicalFile
        val canonicalTarget = target.canonicalFile
        require(canonicalTarget.parentFile == canonicalRoot) { "Refusing to remove an unrelated path" }
        canonicalTarget.listFiles().orEmpty().forEach { child ->
            require(!child.isDirectory) { "Unexpected nested offline directory" }
            child.delete()
        }
        canonicalTarget.delete()
    }

    private fun episodeDirectoryName(id: EpisodeId): String =
        PageCacheKey.of(PageId(id, "offline-manifest"))
}
