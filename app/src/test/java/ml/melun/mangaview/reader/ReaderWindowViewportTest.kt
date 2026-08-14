package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderWindowViewportTest {
    @Test
    fun liveViewWinsOverLargerFullscreenFallback() {
        assertEquals(980, ReaderWindowViewport.resolve(980, 0, 800, 2f, 1600, 2400))
    }

    @Test
    fun measuredDecorWinsBeforeConfigurationAndWindowBounds() {
        assertEquals(1040, ReaderWindowViewport.resolve(0, 1040, 800, 2f, 1600, 2400))
    }

    @Test
    fun configurationRepresentsUnmeasuredMultiWindowPane() {
        assertEquals(900, ReaderWindowViewport.resolve(0, 0, 450, 2f, 1600, 2400))
    }

    @Test
    fun finalFallbackIsAlwaysPositive() {
        assertEquals(1, ReaderWindowViewport.resolve(0, 0, 0, 0f, 0, 0))
    }
}
