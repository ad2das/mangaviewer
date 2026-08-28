package ml.melun.mangaview.reader

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkSubmitNativeFrameHotPathArchitectureTest {
    private val source = File(
        "src/main/java/ml/melun/mangaview/reader/ReaderSurfaceView.kt",
    ).readText()

    @Test
    fun hostGeometryReuseEnqueuesDirectlyWhileDeviceRetainsBoundedHandoff() {
        val submit = functionBody("private fun submitNativeFrame(")

        assertTrue(submit.contains("val directActiveBandGeometry: FrameSyncedGeometryRequest? = null"))
        assertFalse(submit.contains("ViewerNativeGeometryEnqueue"))
        assertTrue(submit.contains("val synchronousEmulatorNativeGeometry ="))
        assertTrue(submit.contains("emulatorNativeSurfaceRuntime"))
        assertTrue(submit.contains("!synchronousEmulatorNativeGeometry"))
        assertTrue(submit.contains("acquireDeferredNativeGeometrySubmission("))
        assertTrue(submit.contains("} else if (synchronousEmulatorNativeGeometry) {"))
        assertTrue(submit.contains("if (!nativeSubmitOrderLock.tryLock())"))
        assertTrue(submit.contains("nativeSubmitOrderLock.unlock()"))
        assertTrue(source.contains("nativeSubmitProducerGeometry("))
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
