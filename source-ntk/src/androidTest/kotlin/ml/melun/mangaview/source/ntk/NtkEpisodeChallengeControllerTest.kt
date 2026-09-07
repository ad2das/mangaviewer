package ml.melun.mangaview.source.ntk

import android.os.Handler
import android.os.Looper
import android.os.Messenger
import androidx.test.ext.junit.runners.AndroidJUnit4
import ml.melun.mangaview.source.PreparationIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NtkEpisodeChallengeControllerTest {
    @Test
    fun completedChallengeCannotBeOverwrittenWhileWaitingForDocument() {
        val fixture = Fixture()
        val request = fixture.request
        fixture.controller.accept(request.origin, request.path, request.requestId, 200, CHALLENGE)
        val receivedAt = request.challengeReceivedAtMillis
        fixture.controller.accept(request.origin, request.path, request.requestId, 500, "failure")
        fixture.controller.finishIfUnresolved(request)
        assertEquals(CHALLENGE, request.challengePayload)
        assertEquals(receivedAt, request.challengeReceivedAtMillis)
        assertTrue(request.challengePreflightResolved)
    }

    @Test
    fun expiredChallengeCannotBeReplacedByLateSuccess() {
        val fixture = Fixture()
        val request = fixture.request
        fixture.controller.finishIfUnresolved(request)
        fixture.controller.accept(request.origin, request.path, request.requestId, 200, CHALLENGE)
        assertNull(request.challengePayload)
        assertEquals(0L, request.challengeReceivedAtMillis)
        assertTrue(request.challengePreflightResolved)
    }

    private class Fixture {
        val handler = Handler(Looper.getMainLooper())
        val request = RemoteRequest(
            requestId = 43L, key = "https://provider.example/webtoon/work/episode", userAgent = "test",
            intent = PreparationIntent.INITIAL_VIEW, fingerprint = null, persistentId = null,
            startedAtMillis = 0L, recipients = linkedMapOf(43L to Messenger(handler)),
        )
        val controller = NtkEpisodeChallengeController(
            handler = handler, profileName = { "test" }, currentRequest = { request }, browser = { null },
            originReady = { true }, warmRunning = { true }, navigateDocument = { _, _ -> },
            adjacent = NtkAdjacentChallengeController(handler, { "test" }, { request }, { null }, {}),
        )
    }

    private companion object {
        const val CHALLENGE = """{"ok":true,"challenge":{"scope":"/webtoon/work/episode","minSeen":1.0}}"""
    }
}
