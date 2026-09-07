package ml.melun.mangaview.engine.api

import ml.melun.mangaview.core.PageId

/** Source pixels use Q32; viewport offsets retain the existing 1/1024 screen-pixel unit. */
data class SourceAnchor(
    val pageId: PageId,
    val sourceYQ32: Long,
    val viewportOffsetUnits: Long = 0L,
) {
    init {
        require(sourceYQ32 >= 0L) { "A source anchor cannot precede its page" }
    }

    companion object {
        const val SOURCE_UNITS_PER_PIXEL = 4_294_967_296L
        const val SCREEN_UNITS_PER_PIXEL = 1_024L
    }
}

data class EngineViewport(val widthPx: Int, val heightPx: Int) {
    init {
        require(widthPx > 0 && heightPx > 0) { "Viewport dimensions must be positive" }
    }
}
