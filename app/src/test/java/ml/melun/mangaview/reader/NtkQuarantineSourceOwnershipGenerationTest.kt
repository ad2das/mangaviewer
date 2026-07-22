package ml.melun.mangaview.reader

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NtkQuarantineSourceOwnershipGenerationTest {
    private val path = "/webtoon/same-work/same-episode"

    @Before
    fun setUp() = NtkQuarantineSourceOwnershipRegistry.resetForTest()

    @After
    fun tearDown() = NtkQuarantineSourceOwnershipRegistry.resetForTest()

    @Test
    fun retiredGenerationCanDrainBesideImmediateSamePathReplacement() {
        val old = binding(17L)
        val replacement = binding(18L)
        NtkQuarantineSourceOwnershipRegistry.beginSession(old, 170L)
        NtkQuarantineSourceOwnershipRegistry.beginSession(replacement, 180L)

        assertNotNull(snapshot(old, 170L))
        assertNotNull(snapshot(replacement, 180L))
        assertTrue(
            NtkQuarantineSourceOwnershipRegistry.closeAdmissions(path, 17L, 170L)
        )
        assertTrue(snapshot(old, 170L)?.admissionsClosed == true)
        assertFalse(snapshot(replacement, 180L)?.admissionsClosed ?: true)

        assertTrue(NtkQuarantineSourceOwnershipRegistry.release(path, 17L, 170L))
        assertNull(snapshot(old, 170L))
        assertNotNull(snapshot(replacement, 180L))
        assertTrue(NtkQuarantineSourceOwnershipRegistry.release(path, 18L, 180L))
    }

    private fun snapshot(binding: NtkQuarantinePlanBinding, sessionId: Long) =
        NtkQuarantineSourceOwnershipRegistry.snapshot(
            binding.episodePath,
            binding.discoveryGeneration,
            sessionId,
        )

    private fun binding(generation: Long): NtkQuarantinePlanBinding {
        val assets = listOf(
            "https://images.example/$generation/001.jpg",
            "https://images.example/$generation/002.jpg",
        )
        val orderedDigest = NtkEpisodeManifestSeal.computeDigestSha256(
            path,
            assets.size,
            assets,
        )
        val proofDigest = NtkStripDigests.sha256Tokens("plan-proof", generation.toString())
        val requestIdentity = NtkStripDigests.sha256Tokens("request", generation.toString())
        val policy = NtkEpisodeDocumentPlanProof.SOURCE_REQUEST_POLICY_VERSION
        return NtkQuarantinePlanBinding(
            episodePath = path,
            discoveryGeneration = generation,
            planProofDigest = proofDigest,
            viewerRequestIdentityDigest = requestIdentity,
            orderedCanonicalAssets = assets,
            orderedAssetsDigest = orderedDigest,
            pageCount = assets.size,
            sourceRequestPolicyVersion = policy,
            bindingDigest = NtkProvisionalEpisodePlan.computeBindingDigest(
                proofDigest,
                requestIdentity,
                orderedDigest,
                policy,
            ),
        )
    }
}
