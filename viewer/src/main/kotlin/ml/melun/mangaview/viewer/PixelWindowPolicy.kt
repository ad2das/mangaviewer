package ml.melun.mangaview.viewer

data class PixelWindow(
    val visibleStartUnits: Long,
    val visibleEndUnits: Long,
    val retainedStartUnits: Long,
    val retainedEndUnits: Long,
)

class PixelWindowPolicy(
    private val screenfulsAhead: Int = 12,
    private val screenfulsBehind: Int = 2,
) {
    init {
        require(screenfulsAhead >= 0)
        require(screenfulsBehind >= 0)
    }

    fun window(state: ViewerState): PixelWindow {
        val start = state.scroll.contentOffset.units
        val end = saturatingAdd(start, state.viewport.height.units)
        val height = state.viewport.height.units
        val forward = state.velocityUnitsPerSecond >= 0L
        val before = saturatingMultiplyNonNegative(
            height,
            if (forward) screenfulsBehind else screenfulsAhead,
        )
        val after = saturatingMultiplyNonNegative(
            height,
            if (forward) screenfulsAhead else screenfulsBehind,
        )
        return PixelWindow(
            visibleStartUnits = start,
            visibleEndUnits = end,
            retainedStartUnits = saturatingSubtract(start, before).coerceAtLeast(0L),
            retainedEndUnits = saturatingAdd(end, after).coerceAtMost(state.layout.totalHeight.units),
        )
    }
}
