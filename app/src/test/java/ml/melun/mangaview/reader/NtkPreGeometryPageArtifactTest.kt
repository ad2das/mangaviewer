package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkPreGeometryPageArtifactTest {
    @Test
    fun sourceLayoutHasExactHalfOpenBoundsAndPartialTail() {
        val fixture = fixture(pageIndex = 0, width = 764, height = 2_225)
        val plan = NtkSourceTileLayout.create(fixture.episode, fixture.metadata, 1_024)

        assertEquals(listOf(0, 1, 2), plan.tiles.map { it.key.slotIndex })
        assertEquals(listOf(0, 1_024, 2_048), plan.tiles.map { it.sourceTop })
        assertEquals(listOf(1_024, 2_048, 2_225), plan.tiles.map { it.sourceBottom })
        assertEquals(177, plan.tiles.last().sourceBottom - plan.tiles.last().sourceTop)
        assertEquals(
            fixture.metadata.sourceWidth.toLong() * fixture.metadata.sourceHeight * 4L,
            plan.totalRgbaBytes
        )
        plan.tiles.zipWithNext().forEach { (left, right) ->
            assertEquals(left.sourceBottom, right.sourceTop)
        }
    }

    @Test
    fun bodyArtifactBindsExactMetadataAndEncodedProof() {
        val fixture = fixture(pageIndex = 0, width = 900, height = 2_401)
        val plan = NtkSourceTileLayout.create(fixture.episode, fixture.metadata)
        val artifact = NtkPreGeometryPageArtifact.create(
            plan,
            fixture.metadata,
            fixture.proof
        )

        assertEquals(plan.planDigest, artifact.plan.planDigest)
        assertEquals(fixture.proof.encodedSha256, artifact.encodedSha256)
        assertEquals(fixture.proof.encodedLength, artifact.encodedLength)
        assertTrue(NtkStripDigests.isSha256(artifact.bodyProofDigest))
        assertTrue(NtkStripDigests.isSha256(artifact.artifactDigest))
    }

    @Test
    fun metadataEncodedAndTileHeightMutationsAreRejected() {
        val fixture = fixture(pageIndex = 0, width = 800, height = 2_200)
        val plan = NtkSourceTileLayout.create(fixture.episode, fixture.metadata)
        val artifact = NtkPreGeometryPageArtifact.create(plan, fixture.metadata, fixture.proof)

        assertRejected { plan.copy(metadataBindingDigest = digest("mutated-metadata")) }
        assertRejected { plan.copy(tileSourceHeightPx = plan.tileSourceHeightPx + 1) }
        assertRejected { artifact.copy(encodedSha256 = digest("mutated-encoded")) }
        assertRejected { artifact.copy(encodedLength = artifact.encodedLength + 1L) }
    }

    @Test
    fun pageArtifactRootIsStableAndManifestOrdered() {
        val first = fixture(pageIndex = 0, width = 700, height = 1_600)
        val second = fixture(pageIndex = 1, width = 720, height = 1_900)
        val artifacts = listOf(first, second).map { item ->
            val plan = NtkSourceTileLayout.create(item.episode, item.metadata)
            NtkPreGeometryPageArtifact.create(plan, item.metadata, item.proof)
        }

        assertEquals(
            NtkPreGeometryPageArtifact.rootDigest(artifacts),
            NtkPreGeometryPageArtifact.rootDigest(artifacts.map { it.copy() })
        )
        assertNotEquals(
            NtkPreGeometryPageArtifact.rootDigest(artifacts),
            NtkPreGeometryPageArtifact.rootDigest(artifacts.reversed())
        )
    }

    private data class Fixture(
        val episode: NtkEpisodeToken,
        val metadata: NtkSourceMetadata,
        val proof: NtkEncodedOriginalProof
    )

    private fun fixture(pageIndex: Int, width: Int, height: Int): Fixture {
        val episode = NtkEpisodeToken(41L)
        val assets = (0..pageIndex).map { "https://images.example/p${it + 1}.jpg" }
        val seal = NtkEpisodeManifestSeal.create("/manhwa/1/2", 17L, assets)
        val encodedLength = 180_000L + pageIndex
        val authority = NtkSourceMetadataAuthority.createStrict(
            acquisition = NtkMetadataAcquisition.PRIMARY_BODY_TEE,
            responseIdentityDigest = digest("response-$pageIndex"),
            byteWitnessSha256 = digest("witness-$pageIndex"),
            byteWitnessLength = 150L,
            encodedLength = encodedLength,
            strongValidatorDigest = digest("validator-$pageIndex"),
            imageFormat = "jpeg"
        )
        val metadata = NtkSourceMetadata.createStrict(
            manifestRevision = seal.revision,
            manifestDigest = seal.digestSha256,
            pageIndex = pageIndex,
            canonicalAsset = assets[pageIndex],
            sourceWidth = width,
            sourceHeight = height,
            authority = authority
        )
        return Fixture(
            episode = episode,
            metadata = metadata,
            proof = NtkEncodedOriginalProof.createStrict(
                metadata = metadata,
                encodedSha256 = digest("encoded-$pageIndex"),
                encodedLength = encodedLength
            )
        )
    }

    private fun digest(value: String): String = NtkStripDigests.sha256Tokens(value)

    private fun assertRejected(block: () -> Unit) {
        var rejected = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("mutation must be rejected", rejected)
    }
}
