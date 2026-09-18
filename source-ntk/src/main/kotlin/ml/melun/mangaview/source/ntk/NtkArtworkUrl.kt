package ml.melun.mangaview.source.ntk

import java.net.URI

/**
 * Resolves provider artwork references whose file names can contain Hangul or other characters
 * [URI] rejects. The strict parse previously made the whole artwork open fail before any request
 * was made, which surfaced as an artwork that never loaded.
 */
internal object NtkArtworkUrl {
    private const val LEGAL = "-._~:/?#[]@!$&'()*+,;=%"
    private const val HEX = "0123456789ABCDEF"

    fun resolve(base: String, value: String): String? {
        // Encode first: the strict parse accepts some raw non-ASCII values but keeps them
        // unencoded, which then breaks every downstream URL consumer. Already encoded values
        // survive because '%' is a legal character here.
        val encoded = percentEncode(value.trim())
        return runCatching { URI(base).resolve(encoded).toString() }.getOrNull()
    }

    /** Percent-encodes every code point outside the RFC 3986 ASCII set, UTF-8 byte by byte. */
    internal fun percentEncode(value: String): String = buildString(value.length + 16) {
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            index += Character.charCount(codePoint)
            if (codePoint < 0x80 && isLegal(codePoint.toChar())) {
                append(codePoint.toChar())
            } else {
                for (byte in String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8)) {
                    append('%')
                    append(HEX[(byte.toInt() shr 4) and 0xF])
                    append(HEX[byte.toInt() and 0xF])
                }
            }
        }
    }

    private fun isLegal(char: Char): Boolean =
        char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || LEGAL.indexOf(char) >= 0
}
