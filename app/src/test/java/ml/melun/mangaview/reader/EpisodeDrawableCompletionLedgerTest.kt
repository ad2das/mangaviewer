package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeDrawableCompletionLedgerTest {
    @Test
    fun canonicalCompletionSurvivesPixelResidencyEviction() {
        val ledger = EpisodeDrawableCompletionLedger()
        assertTrue(ledger.mark("/episode/a", 7, 0, "https://cdn/a-7.jpg", "digest-a", 8))

        // Pixel/cache state is deliberately not part of this ledger. A later LRU eviction has no
        // operation to perform here, so the canonical one-time proof remains available.
        assertTrue(ledger.contains("/episode/a", 7, 0, "https://cdn/a-7.jpg", "digest-a", 8))
        assertEquals(1, ledger.size())
    }

    @Test
    fun displayIndexOrEpisodeReuseCannotSatisfyAnotherCanonicalSource() {
        val ledger = EpisodeDrawableCompletionLedger()
        ledger.mark("/episode/a", 0, 0, "https://cdn/shared-name.jpg", "digest-a", 2)

        assertFalse(ledger.contains("/episode/b", 0, 0, "https://cdn/shared-name.jpg", "digest-a", 2))
        assertFalse(ledger.contains("/episode/a", 1, 0, "https://cdn/shared-name.jpg", "digest-a", 2))
        assertFalse(ledger.contains("/episode/a", 0, 0, "https://cdn/replaced.jpg", "digest-a", 2))
        assertFalse(ledger.contains("/episode/a", 0, 1, "https://cdn/shared-name.jpg", "digest-a", 2))
        assertFalse(ledger.contains("/episode/a", 0, 0, "https://cdn/shared-name.jpg", "digest-b", 2))
        assertFalse(ledger.contains("/episode/a", 0, 0, "https://cdn/shared-name.jpg", "digest-a", 3))
    }

    @Test
    fun missingSourceRemainsIncompleteAndConsumedEpisodeCanBeRetired() {
        val ledger = EpisodeDrawableCompletionLedger()
        ledger.mark("/episode/a", 0, 0, "a0", "", 0)
        ledger.mark("/episode/a", 1, 0, "a1", "", 0)
        ledger.mark("/episode/b", 0, 0, "b0", "", 0)

        assertFalse(ledger.contains("/episode/a", 2, 0, "a2", "", 0))
        ledger.removeEpisodes(setOf("/episode/a"))
        assertFalse(ledger.contains("/episode/a", 0, 0, "a0", "", 0))
        assertFalse(ledger.contains("/episode/a", 1, 0, "a1", "", 0))
        assertTrue(ledger.contains("/episode/b", 0, 0, "b0", "", 0))
        assertEquals(1, ledger.size())
    }

    @Test
    fun malformedProofCannotOpenCompletionGate() {
        val ledger = EpisodeDrawableCompletionLedger()
        assertFalse(ledger.mark("", 0, 0, "asset", "", 0))
        assertFalse(ledger.mark("/episode/a", -1, 0, "asset", "", 0))
        assertFalse(ledger.mark("/episode/a", 0, -1, "asset", "", 0))
        assertFalse(ledger.mark("/episode/a", 0, 0, "", "", 0))
        assertFalse(ledger.mark("/episode/a", 0, 0, "asset", "", -1))
        assertEquals(0, ledger.size())
    }
}
