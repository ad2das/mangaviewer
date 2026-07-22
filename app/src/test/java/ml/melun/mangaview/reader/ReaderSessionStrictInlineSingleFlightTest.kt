package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderSessionStrictInlineSingleFlightTest {
    @Test
    fun sameOriginalPageHasExactlyOneDecodeOwnerUntilRelease() {
        val flights = ReaderSession.StrictInlineOriginalDecodeFlights()
        val page = "/manhwa/33727/1692251#12"

        assertTrue(flights.tryClaim(page))
        assertFalse(flights.tryClaim(page))
        assertEquals(1, flights.activeCount())

        flights.release(page)

        assertTrue(flights.tryClaim(page))
        assertEquals(1, flights.activeCount())
    }

    @Test
    fun differentRunwayPagesUseBothDecodeLanes() {
        val flights = ReaderSession.StrictInlineOriginalDecodeFlights()

        assertTrue(flights.tryClaim("/manhwa/33727/1692251#12"))
        assertTrue(flights.tryClaim("/manhwa/33727/1692251#13"))
        assertEquals(2, flights.activeCount())
    }
}
