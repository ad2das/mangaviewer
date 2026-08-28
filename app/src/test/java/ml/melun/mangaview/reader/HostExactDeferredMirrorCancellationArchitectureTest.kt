package ml.melun.mangaview.reader

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class HostExactDeferredMirrorCancellationArchitectureTest {
    @Test
    fun `new input returns a private offscreen mirror to its retry owner`() {
        val source = File(
            "src/main/java/ml/melun/mangaview/reader/HostExactHardwareTilePool.kt",
        ).readText()
        val enqueueStart = source.indexOf("private fun enqueueMirrorPublications(")
        val enqueueEnd = source.indexOf("/** Closes a handle", enqueueStart)
        val enqueue = source.substring(enqueueStart, enqueueEnd)

        assertTrue(enqueue.contains("cancelOnDeferredInput: Boolean = false"))
        assertTrue(enqueue.contains("NtkReaderTransferPacer.isPhysicalMotionActive()"))
        assertTrue(enqueue.contains("NtkReaderTransferPacer.isPhysicalInputPriorityActive()"))
        assertTrue(enqueue.contains("deferredByNewInput.set(true)"))
        assertTrue(enqueue.contains("completed.countDown()"))
        assertTrue(enqueue.contains("throw NtkPhysicalMotionDecodeDeferredException()"))
        assertTrue(
            source.windowed("cancelOnDeferredInput = deferWhilePhysicalMotion".length)
                .count { it == "cancelOnDeferredInput = deferWhilePhysicalMotion" } >= 2,
        )
    }
}
