package ml.melun.mangaview.source.ntk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkAckPreparationStateTest {
    @Test
    fun providerReadyEventProvesAuthorizationWithoutHttpStatus() {
        assertTrue(isNtkAuthorizationProof("ack-ready", 0))
        assertTrue(isNtkAuthorizationProof("early-ack-ready", 0))
    }

    @Test
    fun validatedResponseMetadataProvesAuthorization() {
        assertTrue(isNtkAuthorizationProof("ack-meta:ok=true,acked=true,detail=", 200))
        assertTrue(isNtkAuthorizationProof("challenge-meta:ok=true,ackValid=true", 204))
    }

    @Test
    fun incompleteOrFailedSignalsDoNotProveAuthorization() {
        assertFalse(isNtkAuthorizationProof("ack-meta:ok=true,acked=false,detail=", 200))
        assertFalse(isNtkAuthorizationProof("ack-meta:ok=true,acked=true,detail=", 500))
        assertFalse(isNtkAuthorizationProof("ack-start", 0))
    }
}
