package ml.melun.mangaview.core

data class PageDimensions(
    val widthPx: Int,
    val heightPx: Int,
) {
    init {
        require(widthPx > 0) { "Page width must be positive" }
        require(heightPx > 0) { "Page height must be positive" }
    }
}

data class PageSpec(
    val id: PageId,
    val ordinal: Int,
    val dimensions: PageDimensions? = null,
    val encodedLength: Long? = null,
    val fingerprint: String? = null,
) {
    init {
        require(ordinal >= 0) { "Page ordinal must not be negative" }
        require(encodedLength == null || encodedLength >= 0L) {
            "Encoded length must not be negative"
        }
        require(fingerprint == null || fingerprint.isNotBlank()) {
            "Fingerprint must not be blank"
        }
    }
}
