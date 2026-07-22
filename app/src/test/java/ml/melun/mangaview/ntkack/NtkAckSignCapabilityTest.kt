package ml.melun.mangaview.ntkack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

class NtkAckSignCapabilityTest {
    private val f = NtkAckTestFixtures

    @Test
    fun proofAndQuiescenceAreBothRequired() {
        val proof = proof()
        val store = store(proof)
        assertThrows(IllegalStateException::class.java) { store.signExact(signRequest()) }
        store.authorizeExactCapability(proof)
        assertThrows(IllegalStateException::class.java) { store.signExact(signRequest(proof)) }
    }

    @Test
    fun wrongEndpointOrIdentityIsRejectedAndCapabilityIsOneShot() {
        val proof = proof()
        val store = store(proof)
        store.authorizeExactCapability(proof)
        store.markQuiesced(NtkAckTestFixtures.signedSeal(proof, proofKey))
        assertThrows(IllegalArgumentException::class.java) {
            store.signExact(signRequest(proof).copy(endpoint = "/api/webtoon-images"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.signExact(signRequest(proof).copy(flightId = "wrong-flight"))
        }

        val signature = store.signExact(signRequest(proof))
        assertEquals("key-1", signature.requestKeyId)
        assertEquals("p1363", signature.signatureFormat)
        assertEquals(64, Base64.getUrlDecoder().decode(signature.signatureValue).size)
        assertThrows(IllegalStateException::class.java) { store.signExact(signRequest(proof)) }
    }

    @Test
    fun supersededOwnerCannotBindSignOrAuthorizeAfterTheNewFlightBegins() {
        val firstProof = proof()
        val firstIdentity = identity(firstProof)
        val store = store(firstProof)
        val secondIdentity = firstIdentity.copy(
            flightId = "7d789fcb-c36e-45a3-b89a-e0696bcd1b37",
            generation = firstIdentity.generation + 1,
        )

        store.beginFlight(secondIdentity)

        assertThrows(IllegalStateException::class.java) {
            store.bindRegisteredKey(firstIdentity, "stale-key", 0L, Long.MAX_VALUE)
        }
        assertThrows(IllegalStateException::class.java) {
            store.signAckRequest(firstIdentity, "{}".toByteArray())
        }
        assertThrows(IllegalStateException::class.java) {
            store.authorizeExactCapability(firstProof)
        }

        store.bindRegisteredKey(secondIdentity, "key-2", 0L, Long.MAX_VALUE)
        assertEquals(
            "key-2",
            store.signAckRequest(secondIdentity, "{}".toByteArray()).keyId,
        )
    }

    @Test
    fun matchingCancellationRevokesFlightButStaleCancellationCannotRevokeNewOwner() {
        val proof = proof()
        val first = identity(proof)
        val second = first.copy(
            flightId = "7d789fcb-c36e-45a3-b89a-e0696bcd1b37",
            generation = first.generation + 1,
        )
        val store = store(proof)

        store.invalidateFlight(first)
        assertThrows(IllegalStateException::class.java) {
            store.signAckRequest(first, "{}".toByteArray())
        }

        store.beginFlight(second)
        store.bindRegisteredKey(second, "key-2", 0L, Long.MAX_VALUE)
        store.invalidateFlight(first)
        assertEquals("key-2", store.signAckRequest(second, "{}".toByteArray()).keyId)
    }

    private val proofKey = NtkAckRequestKeyStore.generateKeyPair()

    private fun store(proof: NtkAckProof) = NtkAckRequestKeyStore().apply {
        val identity = identity(proof)
        beginFlight(identity)
        bindRegisteredKey(identity, "key-1", serverTimeOffsetMs = 0L, expiresAtEpochMs = Long.MAX_VALUE)
    }

    private fun proof(): NtkAckProof = NtkAckTestFixtures.signedProof(proofKey)

    private fun identity(proof: NtkAckProof) = NtkAckFlightIdentity(
        proof.protocolVersion,
        proof.flightId,
        proof.generation,
        proof.authEpoch,
        proof.origin,
        proof.episodePath,
    )

    private fun signRequest(proof: NtkAckProof = NtkAckTestFixtures.unsignedProof()) = NtkAckSignRequest(
        protocolVersion = NtkAckProtocol.VERSION,
        proofId = proof.proofId,
        flightId = proof.flightId,
        generation = proof.generation,
        authEpoch = proof.authEpoch,
        origin = proof.origin,
        episodePath = proof.episodePath,
        method = "POST",
        endpoint = "/api/manhwa-images",
        requestIdentityDigestSha256 = f.D1,
        imagesTokenDigestSha256 = f.D2,
        bodyBytes = "{\"requestKeyId\":\"key-1\"}".toByteArray(),
    )
}
