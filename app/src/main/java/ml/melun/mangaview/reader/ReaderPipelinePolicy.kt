package ml.melun.mangaview.reader

object ReaderPipelinePolicy {
    const val FOREGROUND_NETWORK_PARALLELISM = 2
    const val IDLE_DECODE_PARALLELISM = 2
    const val BUSY_DECODE_PARALLELISM = 1
    const val INITIAL_WINDOW_BEFORE = 1
    const val INITIAL_WINDOW_AFTER = 4
    const val BUSY_WINDOW_BEFORE = 1
    const val BUSY_WINDOW_AFTER = 3
    const val IDLE_WINDOW_BEFORE = 2
    const val IDLE_WINDOW_AFTER = 6
    const val BUSY_DECODE_WIDTH = 480
    const val IDLE_DECODE_WIDTH = 720

    @JvmStatic
    fun windowBefore(busy: Boolean): Int = if (busy) BUSY_WINDOW_BEFORE else IDLE_WINDOW_BEFORE

    @JvmStatic
    fun windowAfter(busy: Boolean): Int = if (busy) BUSY_WINDOW_AFTER else IDLE_WINDOW_AFTER

    @JvmStatic
    fun decodeParallelism(busy: Boolean): Int = if (busy) BUSY_DECODE_PARALLELISM else IDLE_DECODE_PARALLELISM
}
