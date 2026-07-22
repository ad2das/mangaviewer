package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkStrictSourceBodyPublishedTest {
    @Test
    fun bodyPublicationCarriesOneExactDescriptorCapability() {
        val fixture = fixture()
        val descriptor = fixture.descriptor(7L)

        val events = NtkStrictSourceSchedulerPolicy.orderedBodyEvents(null, descriptor)

        assertEquals(2, events.size)
        assertEquals(SourceEvent.MetadataReady(fixture.metadata), events[0])
        assertSame(descriptor, (events[1] as SourceEvent.BodyPublished).descriptor)
        assertEquals(fixture.metadata.strictSourceKey, descriptor.sourceKey)
        assertEquals(fixture.proof.strictSourceKey, descriptor.sourceKey)
    }

    @Test
    fun ledgerReplayUsesTheSameDescriptorObject() {
        val fixture = fixture()
        val descriptor = fixture.descriptor(11L)

        val first = NtkStrictSourceSchedulerPolicy
            .orderedBodyEvents(fixture.metadata, descriptor)
            .single() as SourceEvent.BodyPublished
        val replay = NtkStrictSourceSchedulerPolicy
            .orderedBodyEvents(fixture.metadata, descriptor)
            .single() as SourceEvent.BodyPublished

        assertSame(descriptor, first.descriptor)
        assertSame(first.descriptor, replay.descriptor)
    }

    @Test
    fun descriptorRejectsMutatedSourceIdentity() {
        val fixture = fixture()
        assertRejected {
            NtkStrictBodyDescriptor(
                descriptorId = 1L,
                sourceKey = fixture.metadata.strictSourceKey.copy(pageIndex = 1),
                metadata = fixture.metadata,
                proof = fixture.proof,
                openLease = { error("not opened") }
            )
        }
    }

    @Test
    fun sourceOverlapProofBindsThePreExactInitialWave() {
        val initialWave = NtkSourceLanePolicy.MAX_NETWORK_OPERATIONS
        val proof = NtkSourceOverlapProof(
            planReservedAtMs = 100L,
            firstQuarantineSubmittedAtMs = 104L,
            initialQuarantineWaveSubmittedAtMs = 108L,
            initialWaveCount = initialWave,
            exactSealAtMs = 114L,
            ownerClaimedAtMs = 116L,
            completedAtPromotion = 2,
            activeAtPromotion = initialWave - 2,
            queuedAtPromotion = 31,
            postPromotionStarted = 0,
            physicalCallCount = initialWave,
            duplicatePhysicalCallCount = 0
        )

        assertEquals(10L, proof.overlapBeforeExactMs)
        assertEquals(initialWave, proof.initialWaveCount)
        assertEquals(0, proof.duplicatePhysicalCallCount)
    }

    private data class Fixture(
        val metadata: NtkSourceMetadata,
        val proof: NtkEncodedOriginalProof
    ) {
        fun descriptor(id: Long): NtkStrictBodyDescriptor = NtkStrictBodyDescriptor(
            descriptorId = id,
            sourceKey = metadata.strictSourceKey,
            metadata = metadata,
            proof = proof,
            openLease = { error("fixture lease must not be opened") }
        )
    }

    private fun fixture(): Fixture {
        val asset = "https://images.example/p1.jpg"
        val seal = NtkEpisodeManifestSeal.create("/webtoon/1/2", 9L, listOf(asset))
        val encodedLength = 18_000L
        val authority = NtkSourceMetadataAuthority.createStrict(
            acquisition = NtkMetadataAcquisition.PRIMARY_BODY_TEE,
            responseIdentityDigest = digest("response"),
            byteWitnessSha256 = digest("witness"),
            byteWitnessLength = 128L,
            encodedLength = encodedLength,
            strongValidatorDigest = digest("validator"),
            imageFormat = "jpeg"
        )
        val metadata = NtkSourceMetadata.createStrict(
            manifestRevision = seal.revision,
            manifestDigest = seal.digestSha256,
            pageIndex = 0,
            canonicalAsset = asset,
            sourceWidth = 1080,
            sourceHeight = 2400,
            authority = authority
        )
        val proof = NtkEncodedOriginalProof.createStrict(
            metadata = metadata,
            encodedSha256 = digest("encoded"),
            encodedLength = encodedLength
        )
        return Fixture(metadata, proof)
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
