package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderSurfaceInstallQueueTest {
    @Test
    fun oneFrameInstallsOnlyCurrentPhysicalPagesAsOneBatch() {
        val frames = ArrayDeque<() -> Unit>()
        val batches = ArrayList<List<Int>>()
        var required = setOf(2, 3, 4)
        val queue = ReaderSurfaceInstallQueue(
            framePoster = { frames.addLast(it) },
            currentSurfaceEpoch = { 9L },
            requiredPages = { required },
            batchInstaller = { commands ->
                batches.add(commands.map { it.pageIndex })
                commands.mapTo(LinkedHashSet()) { it.pageIndex }
            }
        )
        fun command(index: Int, epoch: Long = 9L) = ReaderSurfaceInstallQueue.InstallCommand(
            epoch,
            index,
            "asset-$index",
            ReaderPreparedStore.PreparedTilePage(1, 1, emptyList())
        )
        assertTrue(queue.enqueue(command(2)))
        assertTrue(queue.enqueue(command(3)))
        assertTrue(queue.enqueue(command(8)))
        assertEquals(1, frames.size)
        frames.removeFirst().invoke()
        assertEquals(listOf(listOf(2, 3)), batches)
        assertEquals(1, queue.pendingCount())
        required = setOf(8)
        queue.onRequiredPagesChanged()
        assertEquals(1, frames.size)
        frames.removeFirst().invoke()
        assertEquals(listOf(listOf(2, 3), listOf(8)), batches)
        assertEquals(0, queue.pendingCount())
    }

    @Test
    fun staleSurfaceEpochIsDroppedWithoutInstallerCall() {
        val frames = ArrayDeque<() -> Unit>()
        var installs = 0
        val queue = ReaderSurfaceInstallQueue(
            { frames.addLast(it) },
            { 10L },
            { setOf(0) },
            { commands -> installs += commands.size; emptySet() }
        )
        assertFalse(queue.enqueue(ReaderSurfaceInstallQueue.InstallCommand(
            9L, 0, "asset", ReaderPreparedStore.PreparedTilePage(1, 1, emptyList())
        )))
        assertTrue(frames.isEmpty())
        assertEquals(0, installs)
    }
}
