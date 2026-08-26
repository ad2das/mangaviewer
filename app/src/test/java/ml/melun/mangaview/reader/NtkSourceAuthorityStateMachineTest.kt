package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkSourceAuthorityStateMachineTest {
    private val path = "/manhwa/33727/1692251"
    private val headers = NtkStripDigests.sha256Tokens("ntk-selected-headers-v1")

    @Test
    fun generatedDocumentProofIsBoundToGetEofBodyAndExactSeal() {
        val spec = NtkGeneratedAssetSpec(
            canonicalOrigin = "https://images.example",
            canonicalDirectory = "/manhwa/33727/177622",
            filePrefix = "p",
            firstPageNumber = 1,
            zeroPadWidth = 3,
            extension = "jpg",
            pageCount = 3
        )
        val seal = NtkEpisodeManifestSeal.create(path, 7L, spec.canonicalAssets())
        val proof = NtkEpisodeDocumentGeneratedManifestProof.create(
            episodePath = path,
            discoveryGeneration = 7L,
            canonicalRequestUrl = "https://newtoki1.org$path",
            canonicalFinalUrl = "https://newtoki1.org$path",
            selectedHeadersDigestSha256 = headers,
            responseBody = "{\"imageCount\":3,\"src\":\"${spec.canonicalAssets()[0]}\"}"
                .toByteArray(),
            generatedAssetSpec = spec,
            seal = seal
        )

        assertEquals("GET", proof.httpMethod)
        assertTrue(proof.responseBodyConsumedToEof)
        assertTrue(proof.isValidFor(seal))
        assertTrue(NtkAuthoritativeManifest(seal, proof).isProductionClaimable)
        assertFalse(proof.isValidFor(NtkEpisodeManifestSeal.create(
            path,
            7L,
            listOf("https://images.example/changed.jpg")
        )))
    }

    @Test
    fun viewerApiProofIsBoundToPostEofBodyAndOrderedAssets() {
        val assets = listOf(
            "https://images.example/a.jpg",
            "https://images.example/b.jpg"
        )
        val seal = NtkEpisodeManifestSeal.create(path, 11L, assets)
        val proof = NtkViewerImageApiManifestProof.create(
            episodePath = path,
            discoveryGeneration = 11L,
            canonicalRequestUrl = "https://newtoki1.org/api/viewer-images",
            canonicalFinalUrl = "https://newtoki1.org/api/viewer-images",
            selectedHeadersDigestSha256 = headers,
            requestBody = """{"workId":"33727","episodeId":"1692251","token":"token"}"""
                .toByteArray(),
            responseBody = "{\"ok\":true,\"images\":[{\"page\":1,\"src\":\"${assets[0]}\"},{\"page\":2,\"src\":\"${assets[1]}\"}]}"
                .toByteArray(),
            documentPlanProofDigestSha256 = NtkStripDigests.sha256Tokens("plan", "11"),
            viewerImageRequestIdentityDigestSha256 =
                NtkStripDigests.sha256Tokens("identity", "11"),
            responseConsumedToEof = true,
            orderedAssetSelectionPolicyVersion =
                NtkViewerImageApiManifestProof.ORDERED_ASSET_SELECTION_POLICY_VERSION,
            orderedAssets = assets,
            seal = seal
        )

        assertEquals("POST", proof.httpMethod)
        assertTrue(proof.responseBodyConsumedToEof)
        assertTrue(proof.isValidFor(seal))
        assertFalse(proof.isValidFor(NtkEpisodeManifestSeal.create(path, 11L, assets.reversed())))
    }

    @Test
    fun exactAuthorityIsNoOpWhenIdenticalAndFailsClosedAfterOwned() {
        val first = authority(17L, listOf("https://images.example/a.jpg"))
        val same = first.copy()
        val different = authority(17L, listOf("https://images.example/b.jpg"))

        assertEquals(
            NtkManifestChangeAction.ACCEPT_CANDIDATE,
            NtkManifestAuthorityPolicy.decide(null, first, NtkManifestClaimPhase.BEFORE_CLAIM).action
        )
        assertEquals(
            NtkManifestChangeAction.NO_OP,
            NtkManifestAuthorityPolicy.decide(first, same, NtkManifestClaimPhase.ACTIVE).action
        )
        assertEquals(
            NtkManifestChangeAction.REPLACE_CANDIDATE,
            NtkManifestAuthorityPolicy.decide(
                first,
                different,
                NtkManifestClaimPhase.BEFORE_CLAIM
            ).action
        )
        assertEquals(
            NtkManifestChangeAction.FAIL_CLOSED,
            NtkManifestAuthorityPolicy.decide(first, different, NtkManifestClaimPhase.STAGED).action
        )
    }

    @Test
    fun identicalEncodedBodyConvergesAcrossAuthoritativeTransportRaces() {
        val asset = "https://images.example/a.jpg"
        val seal = NtkEpisodeManifestSeal.create(path, 19L, listOf(asset))
        val bodySha = NtkStripDigests.sha256Tokens("same-encoded-body")
        val firstMetadata = strictMetadata(
            seal,
            asset,
            bodySha,
            responseToken = "replica-a",
            validatorToken = "etag-a",
        )
        val racedMetadata = strictMetadata(
            seal,
            asset,
            bodySha,
            responseToken = "replica-b",
            validatorToken = "etag-b",
        )
        val firstProof = NtkEncodedOriginalProof.createStrict(firstMetadata, bodySha, 4096L)
        val racedProof = NtkEncodedOriginalProof.createStrict(racedMetadata, bodySha, 4096L)

        assertFalse(firstMetadata.hasSameAuthority(racedMetadata))
        assertTrue(firstProof.hasSameEncodedSource(racedProof))

        val changedBodySha = NtkStripDigests.sha256Tokens("changed-encoded-body")
        val changedMetadata = strictMetadata(
            seal,
            asset,
            changedBodySha,
            responseToken = "replica-b",
            validatorToken = "etag-b",
        )
        val changedProof = NtkEncodedOriginalProof.createStrict(
            changedMetadata,
            changedBodySha,
            4096L,
        )
        assertFalse(firstProof.hasSameEncodedSource(changedProof))
    }

    @Test
    fun strictCallTagUsesTheSingleBoundedFullBodyLanePolicy() {
        val digest = authority(23L, listOf("https://images.example/a.jpg")).seal.digestSha256
        assertEquals(120, NtkSourceLanePolicy.MAX_NETWORK_OPERATIONS)
        for (lane in 0 until NtkSourceLanePolicy.MAX_NETWORK_OPERATIONS) {
            assertTrue(NtkStrictSourceCallTag.strict(1L, digest, lane + 1L, lane, lane)
                .isProductionStrict)
        }
        assertRejected { NtkStrictSourceCallTag(
            1L, digest, 1L, NtkStrictSourceOperationKind.PRIMARY_FULL_BODY,
            NtkSourceLanePolicy.MAX_NETWORK_OPERATIONS, 0, 1, "GET", -1L, -1L
        ) }
        assertRejected { NtkStrictSourceCallTag(
            1L, digest, 1L, NtkStrictSourceOperationKind.PRIMARY_FULL_BODY,
            0, 0, NtkStrictSourceFailurePolicy.MAX_PHYSICAL_ATTEMPTS + 1,
            "GET", -1L, -1L
        ) }
        assertTrue(
            NtkStrictSourceCallTag.strict(
                1L, digest, 10_001L, 0, 0, attemptOrdinal = 2,
            ).isProductionStrict
        )
        assertRejected { NtkStrictSourceCallTag(
            1L, digest, 1L, NtkStrictSourceOperationKind.PRIMARY_FULL_BODY,
            0, 0, 1, "HEAD", -1L, -1L
        ) }
        assertRejected { NtkStrictSourceCallTag(
            1L, digest, 1L, NtkStrictSourceOperationKind.PRIMARY_FULL_BODY,
            0, 0, 1, "GET", 0L, 1023L
        ) }
    }

    @Test
    fun typedAuthorityChannelPublishesOnceAndCloseUnsubscribes() {
        val channel = NtkAuthoritativeManifestChannel()
        val expected = authority(29L, listOf("https://images.example/a.jpg"))
        val received = mutableListOf<Pair<String, NtkAuthoritativeManifest>>()
        val subscription = channel.subscribe { episodePath, manifest ->
            received += episodePath to manifest
        }

        channel.publish(path, expected)
        subscription.close()
        channel.publish(path, expected)

        assertEquals(1, received.size)
        assertEquals(path, received.single().first)
        assertEquals(expected, received.single().second)
    }

    @Test
    fun typedAuthorityChannelRejectsAStaleGenerationBeforeListenerDelivery() {
        val channel = NtkAuthoritativeManifestChannel()
        val expected = authority(30L, listOf("https://images.example/current.jpg"))
        var received = 0
        channel.subscribe { _, _ -> received++ }

        channel.publish(path, expected) { false }
        assertEquals(0, received)
        channel.publish(path, expected) { true }
        assertEquals(1, received)
    }

    private fun authority(generation: Long, assets: List<String>): NtkAuthoritativeManifest {
        val seal = NtkEpisodeManifestSeal.create(path, generation, assets)
        val body = "{\"ok\":true,\"images\":[${assets.mapIndexed { index, asset ->
            "{\"page\":${index + 1},\"src\":\"$asset\"}"
        }.joinToString(",")}] }".toByteArray()
        return NtkAuthoritativeManifest(
            seal,
            NtkViewerImageApiManifestProof.create(
                episodePath = path,
                discoveryGeneration = generation,
                canonicalRequestUrl = "https://newtoki1.org/api/viewer-images",
                canonicalFinalUrl = "https://newtoki1.org/api/viewer-images",
                selectedHeadersDigestSha256 = headers,
                requestBody =
                    """{"workId":"33727","episodeId":"1692251","token":"token"}"""
                        .toByteArray(),
                responseBody = body,
                documentPlanProofDigestSha256 =
                    NtkStripDigests.sha256Tokens("plan", generation.toString()),
                viewerImageRequestIdentityDigestSha256 =
                    NtkStripDigests.sha256Tokens("identity", generation.toString()),
                responseConsumedToEof = true,
                orderedAssetSelectionPolicyVersion =
                    NtkViewerImageApiManifestProof.ORDERED_ASSET_SELECTION_POLICY_VERSION,
                orderedAssets = assets,
                seal = seal
            )
        )
    }

    private fun strictMetadata(
        seal: NtkEpisodeManifestSeal,
        asset: String,
        bodySha: String,
        responseToken: String,
        validatorToken: String,
    ): NtkSourceMetadata = NtkSourceMetadata.createStrict(
        manifestRevision = seal.revision,
        manifestDigest = seal.digestSha256,
        pageIndex = 0,
        canonicalAsset = asset,
        sourceWidth = 1100,
        sourceHeight = 1600,
        authority = NtkSourceMetadataAuthority.createStrict(
            acquisition = NtkMetadataAcquisition.ADOPTED_QUARANTINE_FULL_BODY,
            responseIdentityDigest = NtkStripDigests.sha256Tokens(responseToken),
            byteWitnessSha256 = bodySha,
            byteWitnessLength = 4096L,
            encodedLength = 4096L,
            strongValidatorDigest = NtkStripDigests.sha256Tokens(validatorToken),
            imageFormat = "png",
        ),
    )

    private fun assertRejected(block: () -> Unit) {
        var rejected = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }
}
