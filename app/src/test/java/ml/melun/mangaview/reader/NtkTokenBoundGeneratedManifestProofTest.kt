package ml.melun.mangaview.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkTokenBoundGeneratedManifestProofTest {
    @Test
    fun completeTokenBoundNumericManhwaDocumentIsProductionClaimable() {
        val fixture = fixture()

        val proof = NtkTokenBoundGeneratedManifestProof.create(
            fixture.plan,
            fixture.spec,
            fixture.seal,
        )
        val manifest = NtkAuthoritativeManifest(fixture.seal, proof)

        assertTrue(proof.isValidFor(fixture.seal))
        assertTrue(manifest.isProductionClaimable)
    }

    @Test
    fun pageOrderOrReplicaPolicyMutationCannotReuseTheProof() {
        val fixture = fixture()
        val proof = NtkTokenBoundGeneratedManifestProof.create(
            fixture.plan,
            fixture.spec,
            fixture.seal,
        )
        val reversed = NtkEpisodeManifestSeal.create(
            PATH,
            GENERATION,
            fixture.spec.canonicalAssets().reversed(),
        )

        assertFalse(proof.isValidFor(reversed))
    }

    @Test
    fun observedMixedExtensionReplicaTableIsProductionClaimable() {
        val fixture = fixture()
        val assets = listOf(
            "https://booktoki8.org/manhwa/24123/240338/p001.jpg",
            "https://mana.apihost93.com/manhwa/24123/240338/p002.gif",
            "https://booktoki8.org/manhwa/24123/240338/p003.webp",
            "https://mana.apihost93.com/manhwa/24123/240338/p004.png",
        )
        val observedPlan = NtkEpisodeDocumentPlanProof.create(
            PATH,
            GENERATION,
            fixture.plan.canonicalRequestUrl,
            fixture.plan.canonicalFinalUrl,
            fixture.plan.selectedHeadersDigestSha256,
            "episode-document".toByteArray(),
            """{"sourceWorkId":"24123","episodeId":"240338"}""".toByteArray(),
            (1..4).toList(),
            assets,
            fixture.plan.requestIdentity,
        )
        val seal = NtkEpisodeManifestSeal.create(PATH, GENERATION, assets)
        val proof = NtkObservedNumericReplicaManifestProof.create(observedPlan, seal)

        assertTrue(proof.isValidFor(seal))
        assertTrue(NtkAuthoritativeManifest(seal, proof).isProductionClaimable)
    }

    @Test
    fun observedReplicaProofRejectsUntrustedHost() {
        val fixture = fixture()
        val assets = listOf(
            "https://booktoki8.org/manhwa/24123/240338/p001.jpg",
            "https://evil.invalid/manhwa/24123/240338/p002.gif",
            "https://booktoki8.org/manhwa/24123/240338/p003.jpg",
            "https://mana.apihost93.com/manhwa/24123/240338/p004.jpg",
        )
        val plan = NtkEpisodeDocumentPlanProof.create(
            PATH,
            GENERATION,
            fixture.plan.canonicalRequestUrl,
            fixture.plan.canonicalFinalUrl,
            fixture.plan.selectedHeadersDigestSha256,
            "episode-document".toByteArray(),
            """{"sourceWorkId":"24123","episodeId":"240338"}""".toByteArray(),
            (1..4).toList(),
            assets,
            fixture.plan.requestIdentity,
        )
        val seal = NtkEpisodeManifestSeal.create(PATH, GENERATION, assets)

        assertTrue(runCatching {
            NtkObservedNumericReplicaManifestProof.create(plan, seal)
        }.isFailure)
    }

    private data class Fixture(
        val plan: NtkEpisodeDocumentPlanProof,
        val spec: NtkGeneratedAssetSpec,
        val seal: NtkEpisodeManifestSeal,
    )

    private fun fixture(): Fixture {
        val token = "eyJ3IjoiMjQxMjMiLCJlIjoiMjQwMzM4IiwidCI6Im1hbmh3YSJ9.signature"
        val identity = NtkViewerImageRequestIdentity.create(
            "manhwa",
            "/api/manhwa-images",
            "24123",
            "240338",
            token,
        )
        val spec = NtkGeneratedAssetSpec(
            canonicalOrigin = NtkTokenBoundGeneratedManifestProof.CANONICAL_MANHWA_ORIGIN,
            canonicalDirectory = PATH,
            filePrefix = "p",
            firstPageNumber = 1,
            zeroPadWidth = 3,
            extension = "jpg",
            pageCount = 4,
        )
        val response = "episode-document".toByteArray()
        val component = """{"sourceWorkId":"24123","episodeId":"240338"}"""
            .toByteArray()
        val plan = NtkEpisodeDocumentPlanProof.create(
            PATH,
            GENERATION,
            "https://sbxh9.com$PATH",
            "https://sbxh9.com$PATH",
            NtkStripDigests.sha256Tokens("headers"),
            response,
            component,
            (1..4).toList(),
            spec.canonicalAssets(),
            identity,
        )
        val seal = NtkEpisodeManifestSeal.create(
            PATH,
            GENERATION,
            spec.canonicalAssets(),
        )
        return Fixture(plan, spec, seal)
    }

    private companion object {
        const val PATH = "/manhwa/24123/240338"
        const val GENERATION = 17L
    }
}
