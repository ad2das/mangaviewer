package ml.melun.mangaview.core

/** Coordinates use 1/1024 px units so geometry corrections never accumulate float drift. */
data class ReadingPosition(
    val pageId: PageId,
    val offsetInPageUnits: Long,
    val viewportOffsetUnits: Long = 0L,
) {
    init {
        require(offsetInPageUnits >= 0L) { "Page offset must not be negative" }
    }
}
