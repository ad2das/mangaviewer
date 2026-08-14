package ml.melun.mangaview.util

import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

/** Base64 codecs that remain available on every supported Android API. */
object NtkBase64 {
    fun encode(bytes: ByteArray): String = bytes.toByteString().base64()

    fun decode(value: String): ByteArray =
        requireNotNull(value.decodeBase64()) { "Invalid base64" }.toByteArray()

    fun encodeUrlWithoutPadding(bytes: ByteArray): String =
        bytes.toByteString().base64Url().trimEnd('=')

    fun decodeUrl(value: String): ByteArray =
        requireNotNull(value.decodeBase64()) { "Invalid base64url" }.toByteArray()
}
