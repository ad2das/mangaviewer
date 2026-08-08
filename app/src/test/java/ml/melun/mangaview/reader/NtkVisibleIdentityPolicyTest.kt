package ml.melun.mangaview.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkVisibleIdentityPolicyTest {
    private val launchAssets = listOf("a0", "a1", "a2")
    private val launch = NtkVisibleIdentityPolicy.LaunchManifest(
        episodePath = "/manhwa/1/10",
        manifestDigest = DIGEST_A,
        canonicalAssets = launchAssets
    )

    @Test
    fun acceptsOneContiguousLaunchEpisodeViewport() {
        assertTrue(
            NtkVisibleIdentityPolicy.isValid(
                listOf(identity("/manhwa/1/10", 0), identity("/manhwa/1/10", 1)),
                launch
            )
        )
    }

    @Test
    fun acceptsAForwardSparseViewportWithoutTreatingItAsReordered() {
        assertTrue(
            NtkVisibleIdentityPolicy.isValid(
                listOf(
                    identity("/manhwa/1/20", 25, digest = DIGEST_B, count = 84, asset = "p25"),
                    identity("/manhwa/1/20", 26, digest = DIGEST_B, count = 84, asset = "p26"),
                    identity("/manhwa/1/20", 27, digest = DIGEST_B, count = 84, asset = "p27"),
                    identity("/manhwa/1/20", 29, digest = DIGEST_B, count = 84, asset = "p29")
                ),
                launch
            )
        )
    }

    @Test
    fun acceptsThePhysicalLastToFirstEpisodeBoundary() {
        assertTrue(
            NtkVisibleIdentityPolicy.isValid(
                listOf(
                    identity("/manhwa/1/10", 2),
                    identity("/manhwa/1/11", 0, digest = DIGEST_B, count = 4, asset = "b0"),
                    identity("/manhwa/1/11", 1, digest = DIGEST_B, count = 4, asset = "b1")
                ),
                launch
            )
        )
    }

    @Test
    fun attributesTraversalOnlyToTheRequestedEpisodeAcrossPhysicalBoundary() {
        val resumeAssets = (0..7).map { index -> "resume-$index" }
        val resumeLaunch = NtkVisibleIdentityPolicy.LaunchManifest(
            episodePath = "/webtoon/12868/1346337",
            manifestDigest = DIGEST_A,
            canonicalAssets = resumeAssets
        )
        val boundary = listOf(
            NtkVisibleIdentityPolicy.Identity(
                resumeLaunch.episodePath,
                7,
                resumeAssets[7],
                DIGEST_A,
                resumeAssets.size
            ),
            identity("/webtoon/12868/1348822", 0, digest = DIGEST_B, count = 8, asset = "next-0")
        )

        assertTrue(NtkVisibleIdentityPolicy.isValid(boundary, resumeLaunch))
        assertEquals(
            listOf(7),
            NtkVisibleIdentityPolicy.traversalSourceIndexesForEpisode(
                boundary,
                resumeLaunch.episodePath
            )
        )
        assertEquals(
            listOf(0),
            NtkVisibleIdentityPolicy.traversalSourceIndexesForEpisode(
                boundary,
                "/webtoon/12868/1348822"
            )
        )
    }

    @Test
    fun rejectsAssetSubstitutionAndPageOrderRegression() {
        assertFalse(
            NtkVisibleIdentityPolicy.isValid(
                listOf(identity("/manhwa/1/10", 1, asset = "wrong")),
                launch
            )
        )
        assertFalse(
            NtkVisibleIdentityPolicy.isValid(
                listOf(
                    identity("/manhwa/1/11", 2, digest = DIGEST_B, count = 4, asset = "b2"),
                    identity("/manhwa/1/11", 1, digest = DIGEST_B, count = 4, asset = "b1")
                ),
                launch
            )
        )
    }

    @Test
    fun rejectsNonBoundaryMixAndThreeEpisodeFrame() {
        assertFalse(
            NtkVisibleIdentityPolicy.isValid(
                listOf(
                    identity("/manhwa/1/10", 1),
                    identity("/manhwa/1/11", 0, digest = DIGEST_B, count = 4, asset = "b0")
                ),
                launch
            )
        )
        assertFalse(
            NtkVisibleIdentityPolicy.isValid(
                listOf(
                    identity("/manhwa/1/10", 2),
                    identity("/manhwa/1/11", 0, digest = DIGEST_B, count = 1, asset = "b0"),
                    identity("/manhwa/1/12", 0, digest = DIGEST_C, count = 2, asset = "c0")
                ),
                launch
            )
        )
    }

    private fun identity(
        path: String,
        index: Int,
        digest: String = DIGEST_A,
        count: Int = launchAssets.size,
        asset: String = launchAssets.getOrElse(index) { "asset-$index" }
    ) = NtkVisibleIdentityPolicy.Identity(path, index, asset, digest, count)

    private companion object {
        const val DIGEST_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val DIGEST_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val DIGEST_C = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
    }
}
