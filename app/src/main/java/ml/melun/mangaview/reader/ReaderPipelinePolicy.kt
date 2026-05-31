package ml.melun.mangaview.reader

object ReaderPipelinePolicy {
    const val FOREGROUND_NETWORK_PARALLELISM = 10
    const val IDLE_DECODE_PARALLELISM = 3
    const val BUSY_DECODE_PARALLELISM = 5
    const val INITIAL_WINDOW_BEFORE = 0
    const val INITIAL_WINDOW_AFTER = 24
    const val BUSY_WINDOW_BEFORE = 8
    const val BUSY_WINDOW_AFTER = 14
    const val IDLE_WINDOW_BEFORE = 6
    const val IDLE_WINDOW_AFTER = 10
    const val BUSY_DECODE_WIDTH = Int.MAX_VALUE

    @JvmStatic
    fun windowBefore(busy: Boolean): Int = if (busy) BUSY_WINDOW_BEFORE else IDLE_WINDOW_BEFORE

    @JvmStatic
    fun windowAfter(busy: Boolean): Int = if (busy) BUSY_WINDOW_AFTER else IDLE_WINDOW_AFTER

    @JvmStatic
    fun decodeParallelism(busy: Boolean): Int = if (busy) BUSY_DECODE_PARALLELISM else IDLE_DECODE_PARALLELISM
}
