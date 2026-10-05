package ml.melun.mangaview.data.engine

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.data.cache.IncrementalHeaderProbe
import ml.melun.mangaview.data.cache.PageCacheKey
import ml.melun.mangaview.engine.api.StoredPage
import ml.melun.mangaview.source.OpenedPage

interface EngineFilePublication {
    fun syncFile(file: File)
    fun rename(staging: File, destination: File)
    fun syncDirectory(directory: File)
}

class PosixEngineFilePublication : EngineFilePublication {
    override fun syncFile(file: File) {
        RandomAccessFile(file, "rw").use { it.fd.sync() }
    }
    override fun rename(staging: File, destination: File) = Os.rename(staging.path, destination.path)
    override fun syncDirectory(directory: File) {
        val fd = Os.open(directory.path, OsConstants.O_RDONLY, 0)
        try {
            check(OsConstants.S_ISDIR(Os.fstat(fd).st_mode)) { "Expected a directory descriptor" }
            Os.fsync(fd)
        } finally { Os.close(fd) }
    }
}

/** File format and durability operations. Contains no publication or lease registry. */
internal class EnginePageFiles(private val root: File, private val operations: EngineFilePublication) {
    private val verificationLock = Any()
    private val verification = object : LinkedHashMap<String, FileStamp>(VERIFIED_CACHE_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FileStamp>?) =
            size > VERIFIED_CACHE_ENTRIES
    }
    private val bufferLock = Any()
    private val pooledBuffers = ArrayDeque<ByteArray>()
    private val digestPasses = AtomicLong()
    private val bufferAllocations = AtomicLong()

    fun initialize() {
        if (!root.isDirectory) {
            check(root.mkdir() || root.isDirectory) { "Storage root unavailable" }
            operations.syncDirectory(checkNotNull(root.canonicalFile.parentFile))
        }
        for (name in listOf("staging", "pages")) {
            val directory = File(root, name)
            require(directory.canonicalFile.parentFile == root.canonicalFile) { "Storage directory escapes root" }
            check(directory.isDirectory || directory.mkdir() || directory.isDirectory) { "Storage directory unavailable" }
        }
        operations.syncDirectory(root.canonicalFile)
    }

    fun newStaging(): File = resolve("staging/${UUID.randomUUID()}.part", "staging")

    fun resolve(relative: String, directory: String): File {
        require(!File(relative).isAbsolute)
        val base = root.canonicalFile
        val parent = File(base, directory).canonicalFile
        val file = File(base, relative).canonicalFile
        require(parent.parentFile == base && file.parentFile == parent) { "Storage path escapes root" }
        require(relative == "$directory/${file.name}") { "Storage path is not canonical" }
        return file
    }

    fun destination(page: StoredPage): String =
        "pages/${PageCacheKey.of(page.pageId)}-${digestText(page.contentRevision)}-${page.sha256}.page"

    suspend fun transfer(pageId: PageId, revision: String, opened: OpenedPage, staging: File,
        reportGeometry: suspend (PageDimensions) -> Unit,
    ): StoredPage {
        val body = EngineBodyDigest()
        var reported = false
        val buffer = borrowBuffer()
        try {
            FileOutputStream(staging).use { output ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    opened.stream.awaitReadable()
                    val count = opened.stream.readAtMost(buffer, 0, buffer.size)
                    if (count == -1) break
                    require(count in 1..buffer.size) { "Invalid stream read length" }
                    body.accept(buffer, count)
                    output.write(buffer, 0, count)
                    if (!reported) body.dimensions?.let { reported = true; reportGeometry(it) }
                }
            }
        } finally {
            returnBuffer(buffer)
        }
        opened.contentLength?.let { require(it == body.length) { "Response body length mismatch" } }
        return body.page(pageId, revision, staging)
    }

    suspend fun valid(page: StoredPage): Boolean {
        if (!page.file.isFile || page.file.length() != page.byteCount) return false
        if (isVerified(page.file, FileStamp.of(page.file))) return true
        return try {
            val body = EngineBodyDigest()
            digestPasses.incrementAndGet()
            val buffer = borrowBuffer()
            try {
                FileInputStream(page.file).use { input ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count == -1) break
                        if (count > 0) body.accept(buffer, count)
                    }
                }
            } finally {
                returnBuffer(buffer)
            }
            val matches = body.page(page.pageId, page.contentRevision, page.file) == page
            // Remember only a confirmed full read: a file whose bytes no longer match keeps getting
            // re-verified until it is evicted or overwritten, which is what self-healing needs.
            if (matches) rememberVerified(page.file, FileStamp.of(page.file))
            matches
        } catch (_: IOException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    /**
     * Drops the process-local proof that [file] held its published digest. The next [valid] then
     * re-reads it in full instead of trusting the recorded size and mtime.
     */
    fun forgetVerified(file: File) {
        synchronized(verificationLock) { verification.remove(file.path) }
    }

    /**
     * Seeds the verification stamp for a body this process produced durably. The publication path
     * calls it after a rename and commit: transfer() digested exactly the bytes it wrote to a
     * process-private staging name, the prepared length was checked, and rename preserved size and
     * mtime, so a full re-read in [valid] would only repeat that digest -- and it would pay for it
     * beside visible decodes on the same few cores. The stamp is read from [file] after the rename,
     * so any later write changes size or mtime and forces [valid] back to a full digest.
     */
    fun rememberVerified(file: File) {
        rememberVerified(file, FileStamp.of(file))
    }

    fun syncFile(file: File) = operations.syncFile(file)

    /**
     * Makes a staged file visible under its immutable destination name with one rename. No fsync
     * is taken: the page cache survives a process crash, so the journal -> rename -> commit order
     * in the publication batch is what protects against process death, and the storage row's own
     * WAL commit never fsyncs either, so per-file fsyncs bought no end-to-end power-loss guarantee.
     *
     * Correctness after an OS crash or power loss rests on validation instead of fsync:
     * - A renamed or committed file may be missing, truncated or zero-filled. [valid] re-digests a
     *   body whenever its size/mtime stamp is not in this process's cache, and the cache starts
     *   empty, so the first find of a new process rejects a damaged file and the page work then
     *   re-fetches it. Publishing over an invalid unpinned committed body replaces it through the
     *   batch's replace path.
     * - Every reader of committed bytes holds a lease obtained from EngineRawStorage.find()
     *   (which runs [valid] first) or from publish() in the process that digested the bytes during
     *   [transfer]. That holds for the decoder input (EnginePageWork -> EnginePixelWork), complete
     *   episode resume (EngineCompleteEpisodeStore.acquire -> storage.find) and the opening pixel
     *   preparation (EngineOpeningPixels -> work.page); no export or share path reads page bytes.
     * - Journal recovery (EngineRawStorage.recoverJournalLocked) re-validates the destination and
     *   only heals from a staged copy that [valid] accepts; a missing stage or a torn destination
     *   resolves to abandoned or invalid, never to a committed row serving torn bytes.
     * - An unlink whose directory entry survives a power loss reappears as an orphan: the next
     *   initialize()'s [removeOrphans] pass deletes it unless a journal, committed row or lease
     *   protects it, and any row that lost its file fails [valid] on the next find.
     *
     * Residual risk, accepted for a cache: a localOnly complete episode can lose pages on power
     * loss and then report "no longer available" until it is re-downloaded.
     */
    fun publish(staging: File, destination: File) {
        check(!destination.exists()) { "Immutable destination already exists" }
        operations.rename(staging, destination)
    }

    fun syncDirectory(directory: File) = operations.syncDirectory(directory)

    /** Unlink plus a directory sync for the callers that must make the removal durable now. */
    fun delete(file: File) {
        unlink(file)
        syncDirectory(file.parentFile!!)
    }

    /**
     * Removes the directory entry only; the caller owns directory durability. Trim and invalidate
     * rely on validation on the next find after a power loss instead of a directory sync: a
     * rolled-back unlink simply reappears as an orphan the next initialize() can see.
     */
    fun unlink(file: File) {
        forgetVerified(file)
        if (file.exists()) check(file.isFile && file.delete()) { "Unable to delete storage file" }
    }

    fun removeOrphans(protected: Set<String>) {
        for (directory in listOf("staging", "pages")) {
            val parent = resolve("$directory/probe", directory).parentFile!!
            val entries = checkNotNull(parent.listFiles()) { "Cannot enumerate storage directory" }
            var failure: Throwable? = null
            try {
                for (entry in entries) {
                    val relative = "$directory/${entry.name}"
                    val namePattern = if (directory == "staging") STAGING_NAME else PAGE_NAME
                    if (relative in protected || !namePattern.matches(entry.name) || !entry.isFile) continue
                    val owned = resolve(relative, directory)
                    if (owned.exists()) {
                        forgetVerified(owned)
                        check(owned.isFile && owned.delete()) { "Unable to delete storage file" }
                    }
                }
            } catch (error: Throwable) {
                failure = error
                throw error
            } finally {
                // Only unreferenced files are removed here. Commit the directory batch once;
                // publication and leased-file deletion retain their separate durability steps.
                try { operations.syncDirectory(parent) } catch (syncFailure: Throwable) {
                    val original = failure
                    if (original == null) throw syncFailure
                    if (original !== syncFailure) original.addSuppressed(syncFailure)
                }
            }
        }
    }

    private fun borrowBuffer(): ByteArray = synchronized(bufferLock) {
        pooledBuffers.removeLastOrNull()
            ?: ByteArray(BUFFER_BYTES).also { bufferAllocations.incrementAndGet() }
    }

    private fun returnBuffer(buffer: ByteArray) {
        if (buffer.size != BUFFER_BYTES) return
        synchronized(bufferLock) {
            if (pooledBuffers.size < MAX_POOLED_BUFFERS) pooledBuffers.addLast(buffer)
        }
    }

    private fun isVerified(file: File, stamp: FileStamp): Boolean =
        synchronized(verificationLock) { verification[file.path] == stamp }

    private fun rememberVerified(file: File, stamp: FileStamp) =
        synchronized(verificationLock) { verification[file.path] = stamp }

    /** JVM-test observability for the fast paths; production logic never reads it. */
    internal fun stats(): EnginePageFileStats = EnginePageFileStats(digestPasses.get(), bufferAllocations.get())

    companion object {
        const val BUFFER_BYTES = 64 * 1024

        // The app bounds the raw page cache to 1 GiB; at the measured ~1.5 MB pages that is ~680
        // resident files, and under 2100 even at 512 KB pages. Keep every resident file's stamp so a
        // warm horizon never evicts an entry it will read again; an entry is a path plus two longs,
        // so the worst case stays under a megabyte.
        private const val VERIFIED_CACHE_ENTRIES = 2048
        private const val MAX_POOLED_BUFFERS = 4
        private val STAGING_NAME = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.part")
        private val PAGE_NAME = Regex("[0-9a-f]{64}-[0-9a-f]{64}-[0-9a-f]{64}\\.page")
    }
}

/** Full-file digest passes and fresh buffer allocations since this file owner was created. */
internal data class EnginePageFileStats(val digestPasses: Long, val bufferAllocations: Long)

/** Size and mtime identify a file state cheaply; a changed stamp forces a full re-verification. */
private data class FileStamp(val length: Long, val modifiedMillis: Long) {
    companion object {
        fun of(file: File) = FileStamp(file.length(), file.lastModified())
    }
}

private class EngineBodyDigest {
    private val digest = MessageDigest.getInstance("SHA-256")
    private val header = IncrementalHeaderProbe(1024 * 1024)
    var length = 0L
        private set
    val dimensions get() = header.value?.dimensions

    fun accept(bytes: ByteArray, count: Int) {
        length = Math.addExact(length, count.toLong())
        require(length <= 512L * 1024L * 1024L) { "Encoded page exceeds size limit" }
        digest.update(bytes, 0, count)
        header.accept(bytes, count)
    }

    fun page(pageId: PageId, revision: String, file: File): StoredPage {
        require(length > 0L) { "Empty page body" }
        // A body that is not a supported image is a provider anomaly (a soft-block page or a
        // format the probe cannot read), not a programming error: surface it as a fetch failure
        // so the page work retries another route/candidate instead of failing the session.
        val image = try {
            header.result()
        } catch (unsupported: IllegalArgumentException) {
            throw IOException("Page body is not a supported image: ${unsupported.message} head=${header.headHex()}")
        }
        return StoredPage(pageId, revision, file, length, digest.digest().hex(), image.dimensions, image.mediaType)
    }
}

internal fun digestText(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).hex()

private fun ByteArray.hex(): String {
    val alphabet = "0123456789abcdef"
    return buildString(size * 2) {
        for (byte in this@hex) {
            val value = byte.toInt() and 255
            append(alphabet[value ushr 4])
            append(alphabet[value and 15])
        }
    }
}
