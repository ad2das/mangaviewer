package ml.melun.mangaview.ntkack

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkAckProofCodecTest {
    private val keyPair = NtkAckRequestKeyStore.generateKeyPair()
    private val signed = NtkAckTestFixtures.signedProof(keyPair)

    @Test
    fun signedProofAndQuiescenceVerify() {
        assertTrue(NtkAckProofCodec.verifyProof(signed, keyPair.public))
        val seal = NtkAckTestFixtures.signedSeal(signed, keyPair)
        assertTrue(NtkAckProofCodec.verifyQuiescence(seal, keyPair.public))
    }

    @Test
    fun everyAuthorityFieldMutationBreaksSignature() {
        val mutations = listOf(
            signed.copy(episodePath = "/manhwa/33727/other"),
            signed.copy(origin = "https://other.example"),
            signed.copy(generation = signed.generation + 1),
            signed.copy(authEpoch = signed.authEpoch + 1),
            signed.copy(requestNonce = signed.requestNonce.clone().also { it[0] = 99 }),
            signed.copy(challengeResponseDigestSha256 = NtkAckTestFixtures.DF),
            signed.copy(observationSetDigestSha256 = NtkAckTestFixtures.DE),
            signed.copy(requiredObservationCount = 3),
            signed.copy(guardJsDigestSha256 = NtkAckTestFixtures.DD),
            signed.copy(guardWasmDigestSha256 = NtkAckTestFixtures.DC),
            signed.copy(guardTpDigestSha256 = NtkAckTestFixtures.DB),
            signed.copy(ackRequestBodyDigestSha256 = NtkAckTestFixtures.DA),
            signed.copy(ackResponseBodyDigestSha256 = NtkAckTestFixtures.D9),
            signed.copy(requestKeyId = "key-other"),
            signed.copy(cookieGrantDigestSha256 = NtkAckTestFixtures.D8),
        )
        mutations.forEach { assertFalse(NtkAckProofCodec.verifyProof(it, keyPair.public)) }
    }
}
