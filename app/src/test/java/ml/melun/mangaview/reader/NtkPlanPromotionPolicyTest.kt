package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkPlanPromotionPolicyTest {
    private val path = "/manhwa/33727/1692251"
    private val headers = NtkStripDigests.sha256Tokens("promotion-test-headers")

    @Test
    fun sameDocumentPlanIsNoOpButDifferentPlanConflicts() {
        val first = plan(41L, 31, "token-a")
        val same = first.copy()
        val different = plan(41L, 31, "token-b")

        assertTrue(NtkPlanPromotionPolicy.samePlan(first, same))
        assertFalse(NtkPlanPromotionPolicy.samePlan(first, different))
    }

    @Test
    fun exactPromotionAcceptsOnlyTheReservedPlan() {
        val lease = NtkDiscoveryLease(path, NtkDiscoveryGeneration(41L))
        val plan = plan(41L, 31, "token-a")
        val exact = authority(plan, 31)

        assertEquals(
            NtkPlanPromotionValidation.ACCEPT,
            NtkPlanPromotionPolicy.validate(
                NtkSourceState.DISCOVERING,
                NtkPlanState.PLAN_RESERVED,
                lease,
                plan,
                plan.proof.proofDigestSha256,
                exact
            )
        )
    }

    @Test
    fun tokenBoundDocumentPromotionAcceptsOnlyItsIdenticalReservedPlan() {
        val lease = NtkDiscoveryLease(path, NtkDiscoveryGeneration(41L))
        val (plan, exact) = tokenBoundAuthority(41L, 31, "token-a")

        assertEquals(
            NtkPlanPromotionValidation.ACCEPT,
            validate(lease, plan, exact),
        )
        assertEquals(
            NtkPlanPromotionValidation.PLAN_DIGEST_MISMATCH,
            validate(lease, plan(41L, 31, "token-b"), exact),
        )
    }

    @Test
    fun observedNumericReplicaPromotionAcceptsItsIdenticalReservedPlan() {
        val lease = NtkDiscoveryLease(path, NtkDiscoveryGeneration(41L))
        val (plan, exact) = observedAuthority(41L, 31, "token-a")

        assertEquals(NtkPlanPromotionValidation.ACCEPT, validate(lease, plan, exact))
        assertEquals(
            NtkPlanPromotionValidation.PLAN_DIGEST_MISMATCH,
            validate(lease, plan(41L, 31, "token-b"), exact),
        )
    }

    @Test
    fun exactPromotionRejectsMismatchedPlanDigestIdentityAndCount() {
        val lease = NtkDiscoveryLease(path, NtkDiscoveryGeneration(41L))
        val plan = plan(41L, 31, "token-a")

        assertEquals(
            NtkPlanPromotionValidation.PLAN_DIGEST_MISMATCH,
            validate(lease, plan, authority(plan, 31), NtkStripDigests.sha256Tokens("wrong"))
        )
        assertEquals(
            NtkPlanPromotionValidation.REQUEST_IDENTITY_MISMATCH,
            validate(
                lease,
                plan,
                authority(plan, 31, NtkStripDigests.sha256Tokens("wrong-identity"))
            )
        )
        assertEquals(
            NtkPlanPromotionValidation.PAGE_COUNT_MISMATCH,
            validate(lease, plan, authority(plan, 30))
        )
    }

    @Test
    fun retiredGenerationAndWrongStatesCannotPromote() {
        val currentLease = NtkDiscoveryLease(path, NtkDiscoveryGeneration(42L))
        val retiredPlan = plan(41L, 31, "token-a")
        val exact = authority(retiredPlan, 31)

        assertEquals(
            NtkPlanPromotionValidation.STALE_LEASE,
            validate(currentLease, retiredPlan, exact)
        )
        assertEquals(
            NtkPlanPromotionValidation.WRONG_SOURCE_STATE,
            NtkPlanPromotionPolicy.validate(
                NtkSourceState.TERMINAL_CLOSING,
                NtkPlanState.PLAN_RESERVED,
                NtkDiscoveryLease(path, NtkDiscoveryGeneration(41L)),
                retiredPlan,
                retiredPlan.proof.proofDigestSha256,
                exact
            )
        )
        assertEquals(
            NtkPlanPromotionValidation.WRONG_PLAN_STATE,
            NtkPlanPromotionPolicy.validate(
                NtkSourceState.DISCOVERING,
                NtkPlanState.PROMOTED,
                NtkDiscoveryLease(path, NtkDiscoveryGeneration(41L)),
                retiredPlan,
                retiredPlan.proof.proofDigestSha256,
                exact
            )
        )
    }

    private fun validate(
        lease: NtkDiscoveryLease,
        plan: NtkProvisionalEpisodePlan,
        exact: NtkAuthoritativeManifest,
        digest: String = plan.proof.proofDigestSha256
    ): NtkPlanPromotionValidation = NtkPlanPromotionPolicy.validate(
        NtkSourceState.DISCOVERING,
        NtkPlanState.PLAN_RESERVED,
        lease,
        plan,
        digest,
        exact
    )

    private fun plan(
        generation: Long,
        pageCount: Int,
        token: String
    ): NtkProvisionalEpisodePlan {
        val identity = NtkViewerImageRequestIdentity.create(
            "manhwa",
            "/api/manhwa-images",
            "33727",
            "1692251",
            token
        )
        val body = """{"sourceWorkId":"33727","episodeId":"1692251","pages":$pageCount}"""
            .toByteArray()
        val assets = (1..pageCount).map { "https://images.example/$it.jpg" }
        val proof = NtkEpisodeDocumentPlanProof.create(
            path,
            generation,
            "https://newtoki.example$path",
            "https://newtoki.example$path",
            headers,
            body,
            body,
            (1..pageCount).toList(),
            assets,
            identity
        )
        return NtkProvisionalEpisodePlan.create(proof, token, assets)
    }

    private fun authority(
        plan: NtkProvisionalEpisodePlan,
        pageCount: Int,
        requestIdentityDigest: String =
            plan.proof.requestIdentity.identityDigestSha256
    ): NtkAuthoritativeManifest {
        val assets = (1..pageCount).map { "https://images.example/$it.jpg" }
        val seal = NtkEpisodeManifestSeal.create(path, plan.proof.discoveryGeneration, assets)
        val requestBody = """{"workId":"33727","episodeId":"1692251","token":"${plan.imagesToken}"}"""
            .toByteArray()
        val responseBody = """{"ok":true,"images":[${assets.mapIndexed { index, asset ->
            """{"page":${index + 1},"src":"$asset"}"""
        }.joinToString(",")}]}""".toByteArray()
        val proof = NtkViewerImageApiManifestProof.create(
            path,
            plan.proof.discoveryGeneration,
            "https://newtoki.example/api/manhwa-images",
            "https://newtoki.example/api/manhwa-images",
            headers,
            requestBody,
            responseBody,
            plan.proof.proofDigestSha256,
            requestIdentityDigest,
            true,
            NtkViewerImageApiManifestProof.ORDERED_ASSET_SELECTION_POLICY_VERSION,
            assets,
            seal
        )
        return NtkAuthoritativeManifest(seal, proof)
    }

    private fun tokenBoundAuthority(
        generation: Long,
        pageCount: Int,
        token: String,
    ): Pair<NtkProvisionalEpisodePlan, NtkAuthoritativeManifest> {
        val identity = NtkViewerImageRequestIdentity.create(
            "manhwa",
            "/api/manhwa-images",
            "33727",
            "1692251",
            token,
        )
        val spec = NtkGeneratedAssetSpec(
            canonicalOrigin = NtkTokenBoundGeneratedManifestProof.CANONICAL_MANHWA_ORIGIN,
            canonicalDirectory = path,
            filePrefix = "p",
            firstPageNumber = 1,
            zeroPadWidth = 3,
            extension = "jpg",
            pageCount = pageCount,
        )
        val body = """{"sourceWorkId":"33727","episodeId":"1692251","pages":$pageCount}"""
            .toByteArray()
        val proof = NtkEpisodeDocumentPlanProof.create(
            path,
            generation,
            "https://newtoki.example$path",
            "https://newtoki.example$path",
            headers,
            body,
            body,
            (1..pageCount).toList(),
            spec.canonicalAssets(),
            identity,
        )
        val plan = NtkProvisionalEpisodePlan.create(proof, token, spec.canonicalAssets())
        val seal = NtkEpisodeManifestSeal.create(path, generation, spec.canonicalAssets())
        val exactProof = NtkTokenBoundGeneratedManifestProof.create(proof, spec, seal)
        return plan to NtkAuthoritativeManifest(seal, exactProof)
    }

    private fun observedAuthority(
        generation: Long,
        pageCount: Int,
        token: String,
    ): Pair<NtkProvisionalEpisodePlan, NtkAuthoritativeManifest> {
        val identity = NtkViewerImageRequestIdentity.create(
            "manhwa",
            "/api/manhwa-images",
            "33727",
            "1692251",
            token,
        )
        val assets = (1..pageCount).map { page ->
            val host = if (page % 2 == 1) "booktoki8.org" else "mana.apihost93.com"
            val extension = if (page == 2) "gif" else "jpg"
            "https://$host$path/p${page.toString().padStart(3, '0')}.$extension"
        }
        val body = """{"sourceWorkId":"33727","episodeId":"1692251","pages":$pageCount}"""
            .toByteArray()
        val proof = NtkEpisodeDocumentPlanProof.create(
            path,
            generation,
            "https://newtoki.example$path",
            "https://newtoki.example$path",
            headers,
            body,
            body,
            (1..pageCount).toList(),
            assets,
            identity,
        )
        val plan = NtkProvisionalEpisodePlan.create(proof, token, assets)
        val seal = NtkEpisodeManifestSeal.create(path, generation, assets)
        val exactProof = NtkObservedNumericReplicaManifestProof.create(proof, seal)
        return plan to NtkAuthoritativeManifest(seal, exactProof)
    }
}
