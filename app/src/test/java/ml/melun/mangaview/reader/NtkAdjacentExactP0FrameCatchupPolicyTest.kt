package ml.melun.mangaview.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkAdjacentExactP0FrameCatchupPolicyTest {
    @Test
    fun exactAuthorizedHostEmulatorTokenReplacesItsPendingCallback() {
        assertTrue(shouldPost())
    }

    @Test
    fun physicalMobileAndUnprovenEpisodesKeepNormalChoreographerCadence() {
        assertFalse(shouldPost(emulatorRuntime = false))
        assertFalse(shouldPost(directWifiRunway = false))
        assertFalse(shouldPost(authorizedEpisode = false))
    }

    @Test
    fun noMutationNoSurfaceAndSuppressedPublicationNeverFabricateAFrame() {
        assertFalse(shouldPost(hasAdmittedFrame = false))
        assertFalse(shouldPost(callbackPosted = false))
        assertFalse(shouldPost(nativeAttached = false))
        assertFalse(shouldPost(surfaceValid = false))
        assertFalse(shouldPost(frameSchedulingSuppressed = true))
    }

    @Test
    fun onlyOneCatchupCanOwnTheExistingToken() {
        assertFalse(shouldPost(contentCatchupPosted = true))
        assertFalse(shouldPost(inputCatchupPosted = true))
    }

    private fun shouldPost(
        emulatorRuntime: Boolean = true,
        directWifiRunway: Boolean = true,
        authorizedEpisode: Boolean = true,
        renderRunning: Boolean = true,
        directSurfaceReady: Boolean = true,
        frameSchedulingSuppressed: Boolean = false,
        callbackPosted: Boolean = true,
        contentCatchupPosted: Boolean = false,
        inputCatchupPosted: Boolean = false,
        hasAdmittedFrame: Boolean = true,
        nativeAttached: Boolean = true,
        surfaceValid: Boolean = true,
    ): Boolean = NtkAdjacentExactP0FrameCatchupPolicy.shouldPost(
        emulatorRuntime = emulatorRuntime,
        directWifiRunway = directWifiRunway,
        authorizedEpisode = authorizedEpisode,
        renderRunning = renderRunning,
        directSurfaceReady = directSurfaceReady,
        frameSchedulingSuppressed = frameSchedulingSuppressed,
        callbackPosted = callbackPosted,
        contentCatchupPosted = contentCatchupPosted,
        inputCatchupPosted = inputCatchupPosted,
        hasAdmittedFrame = hasAdmittedFrame,
        nativeAttached = nativeAttached,
        surfaceValid = surfaceValid,
    )
}
