package ml.melun.mangaview.reader

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkActiveBandCandidateLockArchitectureTest {
    private val source = File(
        "src/main/java/ml/melun/mangaview/reader/ReaderSurfaceView.kt",
    ).readText()

    @Test
    fun displayProducerDoesNotReenterPageStateLockToRecordImmutableBandCandidate() {
        val remember = functionBody("private fun rememberActiveNativeBandCandidate(")
        val activate = functionBody("fun onNtkRollingBandActivated(")

        assertTrue(source.contains("private val activeNativeBandCandidateLock = Any()"))
        assertTrue(remember.contains("synchronized(activeNativeBandCandidateLock)"))
        assertFalse(remember.contains("synchronized(stateLock)"))
        assertTrue(activate.contains("synchronized(activeNativeBandCandidateLock)"))
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
