package ml.melun.mangaview.reader

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class HostExactCompatibleRetirementGraceArchitectureTest {
    private val source = File(
        "src/main/java/ml/melun/mangaview/reader/HostExactHardwareTilePool.kt",
    ).readText()
    private val session = File(
        "src/main/java/ml/melun/mangaview/reader/ReaderSession.kt",
    ).readText()

    @Test
    fun compatibleRetirementWaitsOnlyForExplicitlyScheduledIdleDrain() {
        val acquire = functionBody("private fun acquireSlots(")

        assertTrue(source.contains("private const val COMPATIBLE_RETIREMENT_GRACE_MS = 5_000L"))
        assertTrue(acquire.contains("pendingCompatibleSlotCount"))
        assertTrue(acquire.contains("shouldWaitForScheduledRetirement"))
        assertTrue(acquire.contains("physicalMotionActive = physicalMotionActive"))
        assertTrue(acquire.contains("signalPressureLocked(newBytes.coerceAtLeast(requiredBytes))"))
        assertTrue(acquire.contains("lock.wait(remainingGrace.coerceAtMost(32L))"))
    }

    @Test
    fun sessionPublishesExactRetirementOwnershipBeforeAsynchronousSurfaceClear() {
        val release = functionBody(
            "private fun postBitmapReleases(",
            session,
        )
        val marker = release.indexOf(
            "HostExactHardwareTilePool.noteRetirementPending(ownedBitmaps)",
        )
        val post = release.indexOf("main.post(publishClears)")

        assertTrue(marker >= 0)
        assertTrue(post > marker)
    }

    @Test
    fun nativeHardwareBufferGrowthIsSerializedWithoutATimeBasedCadenceDelay() {
        val allocation = functionBody("private fun allocateExactHardwareBufferSerially(")

        assertTrue(allocation.contains("synchronized(slotAllocationLock)"))
        assertTrue(allocation.contains("nativeAllocateExactHardwareBuffer"))
        assertTrue(!allocation.contains("Thread.sleep"))
    }

    @Test
    fun idleCompactionSettlesBeforeWarmupAndStopsAtNewPhysicalMotion() {
        val drain = functionBody("private fun drainIdleCompactionAfterQuiet()")
        val compact = functionBody("private fun compactIdleSlotsToTarget()")

        assertTrue(source.contains("private const val IDLE_COMPACTION_QUIET_MS = 1_500L"))
        assertTrue(source.contains("private const val TOKEN_RESERVE_REFILL_QUIET_MS = 5_000L"))
        assertTrue(drain.contains("NtkReaderTransferPacer.isPhysicalMotionActive()"))
        assertTrue(compact.contains("NtkReaderTransferPacer.isPhysicalMotionActive()"))
        assertTrue(compact.contains("firstOrNull()"))
    }

    private fun functionBody(signature: String, text: String = source): String {
        val start = text.indexOf(signature)
        check(start >= 0) { "Missing function: $signature" }
        val open = text.indexOf('{', start)
        check(open >= 0)
        var depth = 0
        for (index in open until text.length) {
            when (text[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, index + 1)
                }
            }
        }
        error("Unclosed function: $signature")
    }
}
