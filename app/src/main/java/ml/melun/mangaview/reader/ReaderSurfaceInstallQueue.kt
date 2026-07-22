package ml.melun.mangaview.reader

import java.util.TreeMap

/** Frame-paced, physical-window-only queue. The Controller supplies the sole Surface writer. */
class ReaderSurfaceInstallQueue(
    private val framePoster: ((() -> Unit) -> Unit),
    private val currentSurfaceEpoch: () -> Long,
    private val requiredPages: () -> Set<Int>,
    private val batchInstaller: (List<InstallCommand>) -> Set<Int>
) {
    data class InstallCommand(
        val episodeEpoch: Long,
        val pageIndex: Int,
        val canonicalAsset: String,
        val tilePage: ReaderPreparedStore.PreparedTilePage
    )

    private val lock = Any()
    private val pending = TreeMap<Int, InstallCommand>()
    private var framePosted = false

    fun enqueue(command: InstallCommand): Boolean {
        if (command.episodeEpoch <= 0L || command.pageIndex < 0 || command.canonicalAsset.isBlank()) {
            return false
        }
        if (command.episodeEpoch != currentSurfaceEpoch()) return false
        val post = synchronized(lock) {
            pending[command.pageIndex] = command
            val eligibleNow = command.pageIndex in requiredPages()
            if (!eligibleNow || framePosted) false else {
                framePosted = true
                true
            }
        }
        if (post) framePoster(::drainFrame)
        return true
    }

    /** Re-evaluates retained prepared tiles after the physical window advances. */
    fun onRequiredPagesChanged() {
        val post = synchronized(lock) {
            val epoch = currentSurfaceEpoch()
            pending.entries.removeAll { (_, command) -> command.episodeEpoch != epoch }
            val eligible = requiredPages()
            if (framePosted || pending.values.none { it.pageIndex in eligible }) false else {
                framePosted = true
                true
            }
        }
        if (post) framePoster(::drainFrame)
    }

    fun clear() = synchronized(lock) {
        pending.clear()
        framePosted = false
    }

    internal fun pendingCount(): Int = synchronized(lock) { pending.size }

    private fun drainFrame() {
        val commands = synchronized(lock) {
            framePosted = false
            val epoch = currentSurfaceEpoch()
            val eligible = requiredPages()
            val selected = pending.values
                .filter { it.episodeEpoch == epoch && it.pageIndex in eligible }
                .sortedBy { it.pageIndex }
            pending.entries.removeAll { (_, command) ->
                command.episodeEpoch != epoch || selected.any { it.pageIndex == command.pageIndex }
            }
            selected
        }
        if (commands.isNotEmpty()) batchInstaller(commands)
        val postAgain = synchronized(lock) {
            val eligible = requiredPages()
            if (framePosted || pending.values.none { it.pageIndex in eligible }) false else {
                framePosted = true
                true
            }
        }
        if (postAgain) framePoster(::drainFrame)
    }
}
