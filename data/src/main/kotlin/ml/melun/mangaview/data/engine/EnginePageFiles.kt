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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import ml.melun.mangaview.core.PageId
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

    suspend fun transfer(pageId: PageId, revision: String, opened: OpenedPage, staging: File): StoredPage {
        val body = EngineBodyDigest()
        val buffer = ByteArray(BUFFER_BYTES)
        FileOutputStream(staging).use { output ->
            while (true) {
                currentCoroutineContext().ensureActive()
                opened.stream.awaitReadable()
                val count = opened.stream.readAtMost(buffer, 0, buffer.size)
                if (count == -1) break
                require(count in 1..buffer.size) { "Invalid stream read length" }
                body.accept(buffer, count)
                output.write(buffer, 0, count)
            }
        }
        opened.contentLength?.let { require(it == body.length) { "Response body length mismatch" } }
        return body.page(pageId, revision, staging)
    }

    suspend fun valid(page: StoredPage): Boolean {
        if (!page.file.isFile || page.file.length() != page.byteCount) return false
        return try {
            val body = EngineBodyDigest()
            val buffer = ByteArray(BUFFER_BYTES)
            FileInputStream(page.file).use { input ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count == -1) break
                    if (count > 0) body.accept(buffer, count)
                }
            }
            body.page(page.pageId, page.contentRevision, page.file) == page
        } catch (_: IOException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    fun syncStaging(file: File) {
        operations.syncFile(file)
        operations.syncDirectory(resolve("staging/probe", "staging").parentFile!!)
    }

    fun publish(staging: File, destination: File) {
        check(!destination.exists()) { "Immutable destination already exists" }
        operations.rename(staging, destination)
    }

    fun syncPublished(destination: File) {
        operations.syncFile(destination)
        operations.syncDirectory(resolve("staging/probe", "staging").parentFile!!)
        operations.syncDirectory(destination.parentFile!!)
    }

    fun delete(file: File) {
        if (file.exists()) check(file.isFile && file.delete()) { "Unable to delete storage file" }
        operations.syncDirectory(file.parentFile!!)
    }

    fun removeOrphans(protected: Set<String>) {
        for (directory in listOf("staging", "pages")) {
            val parent = resolve("$directory/probe", directory).parentFile!!
            val entries = checkNotNull(parent.listFiles()) { "Cannot enumerate storage directory" }
            for (entry in entries) {
                val relative = "$directory/${entry.name}"
                val namePattern = if (directory == "staging") STAGING_NAME else PAGE_NAME
                if (relative in protected || !namePattern.matches(entry.name) || !entry.isFile) continue
                val owned = resolve(relative, directory)
                delete(owned)
            }
        }
    }

    companion object {
        const val BUFFER_BYTES = 64 * 1024
        private val STAGING_NAME = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.part")
        private val PAGE_NAME = Regex("[0-9a-f]{64}-[0-9a-f]{64}-[0-9a-f]{64}\\.page")
    }
}

private class EngineBodyDigest {
    private val digest = MessageDigest.getInstance("SHA-256")
    private val header = IncrementalHeaderProbe(1024 * 1024)
    var length = 0L
        private set

    fun accept(bytes: ByteArray, count: Int) {
        length = Math.addExact(length, count.toLong())
        require(length <= 512L * 1024L * 1024L) { "Encoded page exceeds size limit" }
        digest.update(bytes, 0, count)
        header.accept(bytes, count)
    }

    fun page(pageId: PageId, revision: String, file: File): StoredPage {
        require(length > 0L) { "Empty page body" }
        val image = header.result()
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
