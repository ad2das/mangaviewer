package ml.melun.mangaview.core

/** Locale-independent lower-case byte encoding without per-byte Formatter allocation. */
fun ByteArray.lowerHex(): String {
    val alphabet = "0123456789abcdef"
    val encoded = CharArray(size * 2)
    for (index in indices) {
        val value = this[index].toInt() and 255
        encoded[index * 2] = alphabet[value ushr 4]
        encoded[index * 2 + 1] = alphabet[value and 15]
    }
    return String(encoded)
}
