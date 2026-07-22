package ml.melun.mangaview.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkStrictSourceSessionCloseRearmTest {
    @Test
    fun finalQueuedActorCallbackRearmsDeferredCloseExactlyAtZero() {
        assertFalse(policy(closeRequested = true, remainingCallbacks = 2))
        assertFalse(policy(closeRequested = true, remainingCallbacks = 1))
        assertTrue(policy(closeRequested = true, remainingCallbacks = 0))
        assertFalse(policy(closeRequested = false, remainingCallbacks = 0))
        assertFalse(
            policy(
                closeRequested = true,
                closeFinalized = true,
                remainingCallbacks = 0
            )
        )
    }

    private fun policy(
        closeRequested: Boolean,
        closeFinalized: Boolean = false,
        remainingCallbacks: Int
    ) = NtkStrictSourceActorCloseRearmPolicy.shouldRearm(
        closeRequested,
        closeFinalized,
        remainingCallbacks
    )
}
