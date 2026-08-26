package ml.melun.mangaview.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkAdjacentDescriptorWakePolicyTest {
    @Test
    fun offscreenSuffixPublicationDoesNotWakeVisibleStructureOwner() {
        assertFalse(NtkAdjacentDescriptorWakePolicy.shouldWakeRemainder(false))
    }

    @Test
    fun compositorProvenEpisodeEntryReleasesRemainderWake() {
        assertTrue(NtkAdjacentDescriptorWakePolicy.shouldWakeRemainder(true))
    }
}
