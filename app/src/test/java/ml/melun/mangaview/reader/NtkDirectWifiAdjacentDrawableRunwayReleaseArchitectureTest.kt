package ml.melun.mangaview.reader

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkDirectWifiAdjacentDrawableRunwayReleaseArchitectureTest {
    private val readerSession = source("ReaderSession.kt")
    private val strictSession = source("NtkStrictSourceSession.kt")
    private val strictTransport = source("NtkStrictSourceTransport.kt")
    private val cacheTransport = source("NtkCacheSourceTransport.kt")
    private val registry = source("NtkSourceSpoolRegistry.kt")

    @Test
    fun fourDrawableCommitCrossesEveryTransportBoundaryExactlyOnce() {
        val claim = block("private data class AdjacentStrictSourceClaim(", readerSession)
        val ready = block("private fun markExactAdjacentRunwayTelemetryIfReady(", readerSession)
        val signal = block(
            "private fun signalDirectWifiAdjacentDrawableRunwayCommitted(",
            readerSession,
        )
        val receive = block("fun onAdjacentDrawableRunwayCommitted(", strictSession)

        assertTrue(claim.contains("drawableRunwayCommitted: AtomicBoolean"))
        assertTrue(ready.contains("(0 until requiredPageCount).toSet()"))
        assertTrue(ready.contains("if (!allReady) return"))
        assertTrue(
            ready.indexOf("if (!allReady) return") <
                ready.indexOf("signalDirectWifiAdjacentDrawableRunwayCommitted(target)")
        )
        assertTrue(signal.contains("cancelled.get()"))
        assertTrue(signal.contains("isDirectWifiStrictAdjacentTransportActive()"))
        assertTrue(signal.contains("path.startsWith(\"/webtoon/\")"))
        assertTrue(signal.contains("isAdjacentStrictSourceClaimLive(path, claim)"))
        assertTrue(signal.contains("drawableRunwayCommitted.compareAndSet(false, true)"))
        assertTrue(signal.contains("transport.onAdjacentDrawableRunwayCommitted(claim.episode)"))
        assertTrue(receive.contains("if (closeRequested.get()) return"))
        assertTrue(receive.contains("if (!acceptsEpisode(episode)) return@executeActor"))
        assertTrue(receive.contains("markDrawableRunwayCommitted()"))
        assertTrue(strictTransport.contains("fun onAdjacentDrawableRunwayCommitted("))
        assertTrue(cacheTransport.contains("strictSession.onAdjacentDrawableRunwayCommitted(episode)"))
        assertTrue(registry.contains("transport.onAdjacentDrawableRunwayCommitted(episode)"))
    }

    @Test
    fun drawableRequirementIsScopedOnlyToDirectWifiAdjacentWebtoon() {
        val construction = block("internal class NtkStrictSourceSession(", strictSession)
        val release = block(
            "private fun maybeReleaseAdjacentPrefetchAfterRunwayActor(",
            strictSession,
        )

        assertTrue(construction.contains("requireDrawableRunwayCommit = adjacentPrefetch && directWifiTransport"))
        assertTrue(construction.contains("!cellularResilientTransport"))
        assertTrue(construction.contains("planBinding.episodePath.startsWith(\"/webtoon/\")"))
        assertTrue(release.contains("runwayBodiesComplete"))
        assertTrue(release.contains("adjacentPrefetchReleaseGate.tryClaimRelease(runwayBodiesComplete)"))
        assertTrue(
            release.indexOf("tryClaimRelease(runwayBodiesComplete)") <
                release.indexOf("releaseAdjacentPrefetchActor(")
        )
    }

    private fun source(name: String): String = File(
        "src/main/java/ml/melun/mangaview/reader/$name"
    ).readText()

    private fun block(signature: String, source: String): String {
        val start = source.indexOf(signature)
        require(start >= 0) { "Missing source signature: $signature" }
        val brace = source.indexOf('{', start)
        require(brace >= 0) { "Missing opening brace: $signature" }
        var depth = 0
        for (index in brace until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        error("Missing closing brace: $signature")
    }
}
