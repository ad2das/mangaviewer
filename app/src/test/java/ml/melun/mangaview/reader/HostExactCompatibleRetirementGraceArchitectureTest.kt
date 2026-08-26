package ml.melun.mangaview.reader

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class HostExactCompatibleRetirementGraceArchitectureTest {
    private val source = File(
        "src/main/java/ml/melun/mangaview/reader/HostExactHardwareTilePool.kt",
    ).readText()

    @Test
    fun compatibleRetirementWaitsForARealContinuousReadingDrainBeforeAllocating() {
        val acquire = functionBody("private fun acquireSlots(")

        assertTrue(source.contains("private const val COMPATIBLE_RETIREMENT_GRACE_MS = 5_000L"))
        assertTrue(acquire.contains("compatibleSlotCount >= count"))
        assertTrue(acquire.contains("signalPressureLocked(newBytes.coerceAtLeast(requiredBytes))"))
        assertTrue(acquire.contains("lock.wait(remainingGrace.coerceAtMost(32L))"))
    }

    private fun functionBody(signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "Missing function: $signature" }
        val open = source.indexOf('{', start)
        check(open >= 0)
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        error("Unclosed function: $signature")
    }
}
