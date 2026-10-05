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

    fun syncFile(file: File) = operations.syncFile(file)

    /**
     * Makes a staged file visible under its immutable destination name. The durable publish
     * sequence is syncFile(staging) -> rename -> syncDirectory(destination.parentFile): the data
     * must be on disk before the name becomes visible, and only the destination directory entry
     * needs syncing after. The staging directory is deliberately not synced here: its only durable
     * content is the staging name, and a crash that loses that directory entry leaves a journal
     * whose stage is missing — recovery treats a missing stage as an abandoned, uncommitted
     * publication and re-fetches, which is exactly the state before the rename.
     */
    fun publish(staging: File, destination: File) {
        check(!destination.exists()) { "Immutable destination already exists" }
        operations.rename(staging, destination)
    }

    fun syncDirectory(directory: File) = operations.syncDirectory(directory)

    fun delete(file: File) {
        unlink(file)
        syncDirectory(file.parentFile!!)
    }

    /**
     * Removes the directory entry only; the caller owns directory durability. Trim batches one
     * sync per distinct directory after its loop instead of one sync per evicted file.
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
        private const val VERIFIED_CACHE_ENTRIES = 256
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
