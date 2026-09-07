package ml.melun.mangaview.source.ntk

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URI
import java.security.MessageDigest
import java.util.UUID

internal class NtkStaticResourceDiskStore(
    private val root: () -> File?,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val maximumAgeMillis: Long = DEFAULT_MAXIMUM_AGE_MILLIS,
) {
    init {
        require(maximumAgeMillis > 0L)
    }

    fun load(origin: String, path: String, limit: Int, mimeType: String): NtkStaticResource? {
        val originalHost = originHost(origin) ?: return null
        val file = cacheFile(origin, path) ?: return null
        if (!file.isFile || file.length() !in 1..(limit.toLong() + MAX_METADATA_BYTES)) return null
        val resource = runCatching {
            DataInputStream(FileInputStream(file).buffered()).use { input ->
                require(input.readInt() == MAGIC)
                require(input.readInt() == VERSION)
                val fetchedAt = input.readLong()
                val age = nowMillis() - fetchedAt
                require(age in 0..maximumAgeMillis)
                val storedPath = input.readUTF()
                val storedOriginalHost = input.readUTF()
                val finalHost = input.readUTF()
                val originalPort = input.readInt()
                val finalPort = input.readInt()
                val expiresAtMillis = input.readLong()
                val storedMimeType = input.readUTF()
                require(storedPath == path && storedOriginalHost == originalHost)
                require(originalPort == (URI(origin).port.takeIf { it >= 0 } ?: 443))
                require(nowMillis() < expiresAtMillis)
                require(storedMimeType == mimeType && finalHost.isNotBlank())
                val byteCount = input.readInt()
                require(byteCount in 1..limit)
                val expectedHash = ByteArray(SHA_256_BYTES).also(input::readFully)
                val bytes = ByteArray(byteCount).also(input::readFully)
                require(input.read() == -1)
                require(sha256(bytes).contentEquals(expectedHash))
                NtkStaticResource(path, originalHost, finalHost, mimeType, bytes,
                    originalPort, finalPort, expiresAtMillis)
            }
        }.getOrNull()
        if (resource == null) file.delete()
        return resource
    }

    fun save(resource: NtkStaticResource) {
        val destination = cacheFile(resource.originalOrigin, resource.path) ?: return
        val directory = destination.parentFile ?: return
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) return
        val staging = File(directory, "${destination.name}.${UUID.randomUUID()}.part")
        try {
            FileOutputStream(staging).use { file ->
                val output = DataOutputStream(file.buffered())
                output.writeInt(MAGIC)
                output.writeInt(VERSION)
                output.writeLong(nowMillis())
                output.writeUTF(resource.path)
                output.writeUTF(resource.originalHost)
                output.writeUTF(resource.finalHost)
                output.writeInt(resource.originalPort)
                output.writeInt(resource.finalPort)
                output.writeLong(resource.expiresAtMillis)
                output.writeUTF(resource.mimeType)
                output.writeInt(resource.bytes.size)
                output.write(sha256(resource.bytes))
                output.write(resource.bytes)
                output.flush()
                file.fd.sync()
            }
            if (destination.exists() && !destination.delete()) return
            if (!staging.renameTo(destination)) return
        } finally {
            staging.delete()
        }
    }

    private fun cacheFile(origin: String, path: String): File? {
        val directory = root() ?: return null
        val host = originHost(origin) ?: return null
        val port = URI(origin).port.takeIf { it >= 0 } ?: 443
        val identity = "https://$host:$port$path"
        return File(directory, sha256(identity.toByteArray(Charsets.UTF_8)).toHex() + ".asset")
    }

    private fun originHost(origin: String): String? = runCatching {
        val uri = URI(origin)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank())
        requireNotNull(uri.host).lowercase()
    }.getOrNull()

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun ByteArray.toHex(): String = buildString(size * 2) {
        for (byte in this@toHex) {
            val value = byte.toInt() and 0xff
            append(HEX[value ushr 4])
            append(HEX[value and 0x0f])
        }
    }

    private companion object {
        const val MAGIC = 0x4E544B53
        const val VERSION = 2
        const val SHA_256_BYTES = 32
        const val MAX_METADATA_BYTES = 2_048L
        const val DEFAULT_MAXIMUM_AGE_MILLIS = 24L * 60L * 60L * 1_000L
        const val HEX = "0123456789abcdef"
    }
}
