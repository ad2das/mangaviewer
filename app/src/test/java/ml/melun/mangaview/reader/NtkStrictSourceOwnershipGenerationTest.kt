package ml.melun.mangaview.reader

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class NtkStrictSourceOwnershipGenerationTest {
    private val path = "/webtoon/same-work/same-episode"

    @Before
    fun setUp() = NtkStrictSourceOwnershipRegistry.clearForTest()

    @After
    fun tearDown() = NtkStrictSourceOwnershipRegistry.clearForTest()

    @Test
    fun retiredGenerationCanDrainBesideImmediateSamePathReplacement() {
        val old = token(generation = 7L, sessionId = 70L, nonce = 700L)
        NtkStrictSourceOwnershipRegistry.beginDiscoveryFence(path, old.discoveryGeneration)
        val oldReservation = NtkStrictSourceOwnershipRegistry.reserveExact(old)
        val oldOwner = NtkStrictSourceOwnershipRegistry.claimExact(oldReservation, old.sessionId)

        val replacement = token(generation = 8L, sessionId = 80L, nonce = 800L)
        NtkStrictSourceOwnershipRegistry.beginDiscoveryFence(
            path,
            replacement.discoveryGeneration,
        )
        val newReservation = NtkStrictSourceOwnershipRegistry.reserveExact(replacement)
        val newOwner = NtkStrictSourceOwnershipRegistry.claimExact(
            newReservation,
            replacement.sessionId,
        )

        assertEquals(newOwner.discoveryGeneration, assertNotNullOwner().discoveryGeneration)
        assertTrue(
            NtkStrictSourceOwnershipRegistry.release(
                path,
                oldOwner.manifestDigest,
                oldOwner.sessionId,
                oldOwner.discoveryGeneration,
            )
        )
        assertEquals(newOwner.discoveryGeneration, assertNotNullOwner().discoveryGeneration)
        assertTrue(
            NtkStrictSourceOwnershipRegistry.release(
                path,
                newOwner.manifestDigest,
                newOwner.sessionId,
                newOwner.discoveryGeneration,
            )
        )
    }

    @Test
    fun failedPrimaryCanRetryAfterFirstAttemptAdmissionsAreSealed() {
        val token = token(generation = 11L, sessionId = 110L, nonce = 1_100L)
        NtkStrictSourceOwnershipRegistry.beginDiscoveryFence(path, token.discoveryGeneration)
        NtkStrictSourceOwnershipRegistry.claimExact(
            NtkStrictSourceOwnershipRegistry.reserveExact(token),
            token.sessionId,
        )
        val firstTag = NtkStrictSourceCallTag.strict(
            token.sessionId,
            token.exactManifestDigest,
            NtkStrictSourceOwnershipRegistry.nextOperationId(),
            0,
            0,
            attemptOrdinal = 1,
        )
        NtkStrictSourceOwnershipRegistry.beginOperation(
            path,
            firstTag,
            routeKeyHash = NtkStripDigests.sha256Tokens("route", "first"),
            callFactoryId = "test",
            attempt = 1,
        ).complete(succeeded = false)
        assertTrue(
            NtkStrictSourceOwnershipRegistry.sealPrimaryAdmissions(
                path,
                token.exactManifestDigest,
                token.sessionId,
            )
        )
        assertTrue(
            NtkStrictSourceOwnershipRegistry.canBeginOperationNow(
                path,
                token.exactManifestDigest,
                token.sessionId,
            )
        )

        val retryTag = NtkStrictSourceCallTag.strict(
            token.sessionId,
            token.exactManifestDigest,
            NtkStrictSourceOwnershipRegistry.nextOperationId(),
            0,
            0,
            attemptOrdinal = 2,
        )
        NtkStrictSourceOwnershipRegistry.beginOperation(
            path,
            retryTag,
            routeKeyHash = NtkStripDigests.sha256Tokens("route", "retry"),
            callFactoryId = "test",
            attempt = 2,
        ).complete(succeeded = true)
        assertFalse(
            NtkStrictSourceOwnershipRegistry.canBeginOperationNow(
                path,
                token.exactManifestDigest,
                token.sessionId,
            )
        )
    }

    private fun assertNotNullOwner(): NtkStrictSourceOwnershipRegistry.Owner {
        val owner = NtkStrictSourceOwnershipRegistry.owner(path)
        assertNotNull(owner)
        return checkNotNull(owner)
    }

    private fun token(
        generation: Long,
        sessionId: Long,
        nonce: Long,
    ) = NtkPromotionToken(
        episodePath = path,
        discoveryGeneration = generation,
        sessionId = sessionId,
        planBindingDigest = NtkStripDigests.sha256Tokens("plan", generation.toString()),
        exactManifestDigest = NtkStripDigests.sha256Tokens("manifest", generation.toString()),
        exactProofDigest = NtkStripDigests.sha256Tokens("proof", generation.toString()),
        nonce = nonce,
    )
}
