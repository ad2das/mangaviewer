package ml.melun.mangaview.source.ntk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkBrowserChallengePreflightTest {
    @Test
    fun providerMinimumSeenAboveFiveSecondsRemainsValid() {
        val payload = """{"ok":true,"challenge":{"scope":"/manhwa/7/11","minSeen":12.5}}"""
        assertTrue(NtkBrowserChallengePreflight.accepts("/manhwa/7/11", 200, payload))
        assertFalse(NtkBrowserChallengePreflight.accepts("/manhwa/7/11", 200,
            payload.replace("12.5", "-1")))
    }

    @Test
    fun acceptsOnlySuccessfulChallengeForExactEpisodeScope() {
        val payload = """{"ok":true,"challenge":{"scope":"/manhwa/7/11","minSeen":1}}"""

        assertTrue(NtkBrowserChallengePreflight.accepts("/manhwa/7/11", 200, payload))
        assertFalse(NtkBrowserChallengePreflight.accepts("/manhwa/7/12", 200, payload))
        assertFalse(NtkBrowserChallengePreflight.accepts("/manhwa/7/11", 500, payload))
    }

    @Test
    fun seedIsOneDocumentAssignmentAndNeverInterpolatesRawPayload() {
        val payload = """{"ok":true,"challenge":{"scope":"</script>"}}"""
        val source = NtkBrowserChallengePreflight.seed(payload, elapsedSinceReceiptMillis = 275L)

        assertTrue(source.startsWith("window.__nativeChallengeSeed = "))
        assertFalse(source.contains(payload))
        assertTrue(source.contains("ageMillis: 275"))
    }

    @Test
    fun adjacentChallengeRestoresTheCurrentDocumentAndDoesNotStartAnAck() {
        val source = NtkBrowserChallengePreflight.startAdjacent("/manhwa/7/12", 19L)

        assertTrue(source.contains("'/api/ad/challenge'"))
        assertTrue(source.contains("JSON.stringify({path, force: false})"))
        assertTrue(source.contains("previousPath"))
        assertTrue(source.contains("restore()"))
        assertFalse(source.contains("location.href ="))
        assertFalse(source.contains("/api/ad/ack"))
    }
}
