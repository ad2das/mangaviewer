package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

class NtkClickOwnedExactBodyStreamTest {

    @Test
    fun closeRetiresTheOwningNetworkWaveExactlyOnce() {
        val closes = AtomicInteger()
        val stream = NtkClickOwnedExactBodyStream(
            mapOf(0 to CompletableFuture.completedFuture(null)),
            Closeable { closes.incrementAndGet() },
        )

        stream.close()
        stream.close()

        assertEquals(1, closes.get())
    }

    @Test
    fun productionHandoffDoesNotWaitForEveryBodyBeforePlanReservation() {
        val quarantine = readSource("NtkClickOwnedAnchorQuarantine.kt")
        val coordinator = readSource("NtkStrictEpisodeDiscoveryCoordinator.kt")
        val session = readSource("NtkStrictSourceSession.kt")
        val streamStart = quarantine.indexOf("fun streamIfExact(")
        val streamEnd = quarantine.indexOf("private fun adoptHeldBody(", streamStart)
        val streamBody = quarantine.substring(streamStart, streamEnd)

        assertTrue(streamBody.contains("future.handle"))
        assertFalse(streamBody.contains("future.get(remainingNanos"))
        assertTrue(quarantine.contains("probeLanes="))
        assertTrue(quarantine.contains("(0 until pageLimit).associateWith"))
        assertTrue(quarantine.contains("candidateFuture.thenCompose"))
        assertTrue(coordinator.contains("streamIfExact(exactManifestPreview)"))
        assertTrue(coordinator.contains("clickOwnedExactStream,"))
        assertTrue(session.contains("streamedExactBodyPending"))
        assertTrue(session.contains("acceptStreamedExactBodyCompletionActor"))
        assertTrue(session.contains("!it.streamedExactBodyPending"))
    }

    private fun readSource(name: String): String {
        var cursor = File(checkNotNull(System.getProperty("user.dir"))).canonicalFile
        repeat(8) {
            val candidate = File(
                cursor,
                "app/src/main/java/ml/melun/mangaview/reader/$name",
            )
            if (candidate.isFile) return candidate.readText()
            cursor = cursor.parentFile ?: return@repeat
        }
        error("Repository source not found: $name")
    }
}
