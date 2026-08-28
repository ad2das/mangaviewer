package ml.melun.mangaview.reader

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class HostExactFileDecodeAdmissionArchitectureTest {
    @Test
    fun physicalMotionAdmissionIsCheckedOnlyAfterPriorityOrderedExactFileDecodeOwnsJvmGate() {
        val source = File(
            "src/main/java/ml/melun/mangaview/reader/HostExactHardwareTilePool.kt",
        ).readText()

        assertTrue(source.contains("private class ExactFileDecodeAdmissionGate"))
        assertTrue(source.contains("compareByDescending<Waiter> { it.isUrgentNow() }"))
        assertTrue(source.contains("requiredNow = mirrorPublicationRequiredNow"))
        assertTrue(source.contains("private val exactFileDecodeAdmissionGate ="))
        assertTrue(
            source.contains(
                "exactFileDecodeAdmissionGate.withAdmission(\n" +
                    "                    prioritized = prioritizeMirrorPublication,",
            ),
        )
        val gateStart = source.indexOf(
            "exactFileDecodeAdmissionGate.withAdmission(\n" +
                "                    prioritized = prioritizeMirrorPublication,",
        )
        val admission = source.indexOf(
            "awaitOptionalDecodeAdmission(\n" +
                "                        deferWhilePhysicalMotion,\n" +
                "                        decodeAdmission,",
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
        assertTrue(
            source.contains(
                "decodeAdmission?.invoke()\n" +
                    "        awaitOptionalPhysicalMotionAdmission(deferWhilePhysicalMotion)",
            ),
        )
    }
}
