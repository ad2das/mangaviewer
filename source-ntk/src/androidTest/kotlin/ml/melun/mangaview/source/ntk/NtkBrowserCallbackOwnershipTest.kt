package ml.melun.mangaview.source.ntk

import android.webkit.CookieManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import ml.melun.mangaview.source.PreparationIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NtkBrowserCallbackOwnershipTest {
    @Test fun sameEpisodeCallbacksMustMatchBothRequestAndDocumentEpoch() {
        val request = RemoteRequest(42L, "https://provider.test/webtoon/work/episode", "test",
            PreparationIntent.INITIAL_VIEW, null, null, 0L, linkedMapOf(),
            documentCookiesApplied = true, identityCookiesApplied = true, documentEpoch = 2L)
        var deliveries = 0
        val relay = NtkAckPhaseRelay({ "test" }, { request }, { deliveries++ }, { CookieManager.getInstance() })
        relay.accept(request.origin, request.path, "ack-ready", 200, 41L, 2L)
        relay.accept(request.origin, request.path, "ack-ready", 200, 42L, 1L)
        assertFalse(request.ackReadyReported)
        assertEquals(0, deliveries)
        relay.accept(request.origin, request.path, "ack-ready", 200, 42L, 2L)
        assertTrue(request.ackReadyReported)
        assertEquals(1, deliveries)
    }
}
