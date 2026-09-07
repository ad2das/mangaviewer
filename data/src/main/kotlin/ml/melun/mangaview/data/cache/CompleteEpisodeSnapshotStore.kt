package ml.melun.mangaview.data.cache

import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageId
import kotlin.coroutines.coroutineContext

fun interface SnapshotFilePinner {
    fun pin(source: File, destination: File): PinnedSnapshotBody
}

class PinnedSnapshotBody(val file: File, private val owner: Closeable) : Closeable {
    override fun close() = owner.close()
}

/** Metadata only on disk; open descriptors pin immutable bodies without copying or hard links. */
class CompleteEpisodeSnapshotStore(
    private val root: File,
    private val cache: RawPageCache,
    private val ioDispatcher: CoroutineDispatcher,
    private val publisher: AtomicFilePublisher = PosixAtomicFilePublisher(),
    private val pinner: SnapshotFilePinner = SnapshotFilePinner { source, _ ->
        val descriptor = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
        PinnedSnapshotBody(File("/proc/self/fd/${descriptor.fd}"), descriptor)
    },
) {
    private val initialization = Mutex()
    private var initialized = false

    suspend fun save(snapshot: CompleteEpisodeSnapshot) = withContext(ioDispatcher) {
        initialize()
        val staging = File(root, ".snapshot-${UUID.randomUUID()}")
        try {
            CompleteEpisodeSnapshotCodec.write(staging, snapshot)
            publisher.publish(staging, snapshotFile(snapshot.manifest.id))
            root.listFiles().orEmpty().filter { it.extension == "snapshot" }
                .sortedByDescending(File::lastModified).drop(MAX_SNAPSHOTS).forEach(File::delete)
        } finally {
            staging.delete()
        }
    }

    suspend fun open(episodeId: EpisodeId): CompleteEpisodeLease? {
        var acquired: CompleteEpisodeLease? = null
        return try {
            withContext(ioDispatcher) {
                acquire(episodeId).also { acquired = it }
            }
        } catch (cancelled: CancellationException) {
            // withContext can cancel while returning a lease from IO to its caller.
            acquired?.close()
            throw cancelled
        } catch (failure: SnapshotLeaseCleanupException) {
            throw failure
        } catch (_: Exception) {
            acquired?.close()
            null
        }
    }

    private suspend fun acquire(episodeId: EpisodeId): CompleteEpisodeLease? {
        initialize()
        val directory = File(root, ".lease-${UUID.randomUUID()}")
        val pins = mutableListOf<PinnedSnapshotBody>()
        LiveSnapshotLeases.reserve(directory)
        var retained = false
        try {
            val file = snapshotFile(episodeId)
            if (!file.isFile) return null
            val snapshot = CompleteEpisodeSnapshotCodec.read(file)
            require(snapshot.manifest.id == episodeId)
            require(directory.mkdir())
            val pages = pinAndVerify(snapshot, directory, pins) ?: return null
            return CompleteEpisodeLease(snapshot, pages, directory, pins).also { retained = true }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        } finally {
            if (!retained) releasePinsAndDirectory(pins, directory)
        }
    }

    private suspend fun pinAndVerify(snapshot: CompleteEpisodeSnapshot, directory: File,
                                    pins: MutableList<PinnedSnapshotBody>): Map<PageId, CachedPage>? {
        val pages = linkedMapOf<PageId, CachedPage>()
        val buffer = ByteArray(64 * 1_024)
        snapshot.pages.forEachIndexed { ordinal, binding ->
            coroutineContext.ensureActive()
            val cached = cache.find(binding.pageId) ?: return null
            if (cached.sha256 != binding.sha256 || cached.dimensions != binding.dimensions ||
                cached.byteCount != binding.byteCount || cached.pageId != binding.pageId) return null
            val pinned = pinner.pin(cached.file, File(directory, "$ordinal.page"))
            pins += pinned
            if (!verify(pinned.file, binding, buffer)) return null
            pages[binding.pageId] = cached.copy(file = pinned.file)
        }
        return pages
    }

    private suspend fun verify(file: File, expected: SnapshotPageBinding, buffer: ByteArray): Boolean {
        if (file.length() != expected.byteCount) return false
        val digest = MessageDigest.getInstance("SHA-256")
        val header = IncrementalHeaderProbe(1 * 1_024 * 1_024)
        var bytes = 0L
        file.inputStream().use { input ->
            while (true) {
                coroutineContext.ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                bytes += read
                if (bytes > expected.byteCount) return false
                digest.update(buffer, 0, read)
                header.accept(buffer, read)
            }
        }
        val hash = with(PageCacheKey) { digest.digest().toHex() }
        return bytes == expected.byteCount && hash == expected.sha256 &&
            header.result().dimensions == expected.dimensions
    }

    private suspend fun initialize() = initialization.withLock {
        if (!initialized) {
            require(root.isDirectory || root.mkdirs())
            root.listFiles().orEmpty().filter { it.name.startsWith(".lease-") }
                .forEach(LiveSnapshotLeases::removeStale)
            initialized = true
        }
    }

    private fun snapshotFile(id: EpisodeId): File =
        File(root, PageCacheKey.of(PageId(id, "complete-resume-manifest")) + ".snapshot")

    private companion object { const val MAX_SNAPSHOTS = 128 }
}

class CompleteEpisodeLease internal constructor(
    val snapshot: CompleteEpisodeSnapshot,
    private val pages: Map<PageId, CachedPage>,
    private val directory: File,
    private val pins: List<PinnedSnapshotBody>,
) : Closeable {
    private val closed = AtomicBoolean(false)

    fun page(id: PageId): CachedPage {
        check(!closed.get()) { "Cached episode lease is closed" }
        val page = requireNotNull(pages[id]) { "Page is outside the leased manifest" }
        check(page.file.isFile && page.file.length() == page.byteCount) { "Leased snapshot body is unavailable" }
        return page
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try {
                releasePinsAndDirectory(pins, directory)
            } catch (failure: Exception) {
                closed.set(false)
                throw failure
            }
        }
    }
}

private fun releasePinsAndDirectory(pins: List<PinnedSnapshotBody>, directory: File) {
    var failure: Exception? = null
    pins.forEach { pin ->
        try { pin.close() } catch (problem: Exception) {
            if (failure == null) failure = problem else failure?.addSuppressed(problem)
        }
    }
    try { LiveSnapshotLeases.release(directory) } catch (problem: Exception) {
        if (failure == null) failure = problem else failure?.addSuppressed(problem)
    }
    failure?.let { throw SnapshotLeaseCleanupException(directory).also { wrapper -> wrapper.initCause(it) } }
}

private object LiveSnapshotLeases {
    private val paths = mutableSetOf<String>()

    fun reserve(directory: File): Unit = synchronized(paths) { paths += directory.absolutePath }

    fun removeStale(directory: File) = synchronized(paths) {
        if (directory.absolutePath !in paths) removeLeaseDirectory(directory)
    }

    fun release(directory: File) = synchronized(paths) {
        removeLeaseDirectory(directory)
        paths -= directory.absolutePath
    }
}

private fun removeLeaseDirectory(directory: File) {
    if (!directory.exists()) return
    if (!directory.name.startsWith(".lease-") ||
        directory.canonicalFile.parentFile != directory.parentFile?.canonicalFile) {
        throw SnapshotLeaseCleanupException(directory)
    }
    directory.listFiles().orEmpty().forEach { file ->
        if (file.parentFile?.canonicalFile != directory.canonicalFile || file.isDirectory) {
            throw SnapshotLeaseCleanupException(file)
        }
        if (!file.delete() && file.exists()) throw SnapshotLeaseCleanupException(file)
    }
    if (!directory.delete() && directory.exists()) throw SnapshotLeaseCleanupException(directory)
}

class SnapshotLeaseCleanupException(file: File) : java.io.IOException("Snapshot lease cleanup failed: $file")
