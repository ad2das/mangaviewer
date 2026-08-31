package ml.melun.mangaview.data.cache

import java.nio.ByteBuffer
import java.security.MessageDigest
import ml.melun.mangaview.core.PageId

object PageCacheKey {
    fun of(pageId: PageId): String {
        val digest = MessageDigest.getInstance("SHA-256")
        update(digest, pageId.episodeId.seriesId.sourceId.value)
        update(digest, pageId.episodeId.seriesId.remoteKey)
        update(digest, pageId.episodeId.remoteKey)
        update(digest, pageId.remoteKey)
        return digest.digest().toHex()
    }

    private fun update(digest: MessageDigest, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        digest.update(bytes)
    }

    internal fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }
}
