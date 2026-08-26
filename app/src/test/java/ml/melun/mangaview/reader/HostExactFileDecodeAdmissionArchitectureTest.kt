package ml.melun.mangaview.reader

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class HostExactFileDecodeAdmissionArchitectureTest {
    @Test
    fun physicalMotionAdmissionIsCheckedOnlyAfterQueuedExactFileDecodeOwnsJvmGate() {
        val source = File(
            "src/main/java/ml/melun/mangaview/reader/HostExactHardwareTilePool.kt",
        ).readText()

        assertTrue(source.contains("private val exactFileDecodeAdmissionLock = Any()"))
        assertTrue(
            source.contains(
                "val nativeDecodeSucceeded = synchronized(exactFileDecodeAdmissionLock) {\n" +
                    "                // This must remain inside the JVM mirror of native's scratch mutex.",
            ),
        )
        val gateStart = source.indexOf(
            "val nativeDecodeSucceeded = synchronized(exactFileDecodeAdmissionLock)",
        )
        val admission = source.indexOf(
            "awaitOptionalPhysicalMotionAdmission(deferWhilePhysicalMotion)",
            startIndex = gateStart,
        )
        val nativeCall = source.indexOf(
            "NtkRollingNativeBridge.nativeDecodeExactFileToHardwareTiles(",
            startIndex = gateStart,
        )
        val gateEnd = source.indexOf("if (!nativeDecodeSucceeded) return null", gateStart)

        assertTrue(gateStart >= 0)
        assertTrue(admission > gateStart)
        assertTrue(nativeCall > admission)
        assertTrue(gateEnd > nativeCall)
    }
}
