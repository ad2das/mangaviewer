package ml.melun.mangaview.reader

import android.graphics.Bitmap
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

class NtkStrictPredecodedOriginalTest {
    @Test(timeout = 250L)
    fun incompleteSpeculativeDecodeNeverBlocksAuthoritativeDecoder() {
        val completion = CompletableFuture<Bitmap?>()
        val abandoned = AtomicBoolean(false)
        val handoff = NtkStrictPredecodedOriginal(completion, abandoned)

        assertNull(handoff.takeIfReadyOrAbandon(sourceWidth = 1080, sourceHeight = 1920))
        assertTrue(abandoned.get())
        assertTrue(handoff.isAbandoned())
    }

    @Test(timeout = 250L)
    fun completedFailureFallsBackWithoutWaiting() {
        val handoff = NtkStrictPredecodedOriginal(
            CompletableFuture.completedFuture<Bitmap?>(null),
        )

        assertNull(handoff.takeIfReadyOrAbandon(sourceWidth = 1080, sourceHeight = 1920))
        assertTrue(handoff.isAbandoned())
    }
}
