package ml.melun.mangaview.source.ntk

import android.os.Handler
import android.os.Looper
import android.os.Messenger
import androidx.test.ext.junit.runners.AndroidJUnit4
import ml.melun.mangaview.source.PreparationIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NtkBrowserRequestCancellationTest {
    @Test fun quiescingOneSubscriberPreservesOtherSubscriberAndFinishesOnlyOnce() {
        val recipient = Messenger(Handler(Looper.getMainLooper()))
        var active: RemoteRequest? = RemoteRequest(
            41L, "https://provider.test/webtoon/work/episode", "test",
            PreparationIntent.INITIAL_VIEW, null, null, 0L,
            linkedMapOf(41L to recipient, 42L to recipient),
        )
        val request = requireNotNull(active)
        val finishes = mutableListOf<Boolean>()
        val forgotten = mutableListOf<Long>()
        val cancellation = NtkBrowserRequestCancellation(
            { active },
            { retiring, parked ->
                assertTrue(retiring.isEmpty())
                finishes += parked
                active = null
            },
            { forgotten += it },
        )
        cancellation.cancel(NtkBrowserIpcMessages.control(NtkBrowserProtocol.MSG_QUIESCE, 41L), true)
        assertTrue(finishes.isEmpty())
        assertFalse(request.contains(41L))
        assertTrue(request.contains(42L))
        cancellation.cancel(NtkBrowserIpcMessages.control(NtkBrowserProtocol.MSG_CANCEL, 99L), false)
        assertTrue(finishes.isEmpty())
        assertTrue(request.contains(42L))
        cancellation.cancel(NtkBrowserIpcMessages.control(NtkBrowserProtocol.MSG_CANCEL, 42L), false)
        assertEquals(listOf(false), finishes)
        cancellation.cancel(NtkBrowserIpcMessages.control(NtkBrowserProtocol.MSG_CANCEL, 42L), false)
        assertEquals(listOf(false), finishes)
        assertEquals(listOf(41L, 99L, 42L, 42L), forgotten)
    }
}
