package ml.melun.mangaview.reader

import android.os.Process
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkStrictEpisodeDiscoveryCoordinatorPathTest {
    @Test
    fun exactDiscoveryControlWorkerYieldsToDisplayOwners() {
        assertEquals(
            Process.THREAD_PRIORITY_BACKGROUND,
            NtkStrictDiscoveryThreadPolicy.ANDROID_PRIORITY,
        )
        assertTrue(NtkStrictDiscoveryThreadPolicy.JAVA_PRIORITY < Thread.NORM_PRIORITY)
    }

    @Test
    fun slugEpisodePathPreservesServerSignificantCase() {
        val path = "/webtoon/u-bt-I_killed-863ce912/u-mqaz97dp-sc5w"

        assertEquals(
            path,
            NtkStrictEpisodeDiscoveryCoordinator.normalizedPath(path),
        )
    }

    @Test
    fun routePrefixMatchingDoesNotRequireLowercaseInput() {
        assertEquals(
            "/WEBTOON/Work-ID/Episode-ID",
            NtkStrictEpisodeDiscoveryCoordinator.normalizedPath(
                "WEBTOON/Work-ID/Episode-ID/?ignored=yes",
            ),
        )
    }

    @Test
    fun nonEpisodePathsRemainRejected() {
        assertNull(NtkStrictEpisodeDiscoveryCoordinator.normalizedPath("/webtoon/only-work"))
        assertNull(NtkStrictEpisodeDiscoveryCoordinator.normalizedPath("/api/webtoon/work/episode"))
    }

    @Test
    fun sourceLeaseAndPreparedKeyPreserveMixedCaseSlugIdentity() {
        val path = "/webtoon/u-bt-I_killed-863ce912/u-mqaz97dp-sc5w"

        assertEquals(path, NtkSourceSpoolRegistry.normalizedPath(path))
        assertEquals("ntk-strict:$path", NtkInlineReaderController.strictPreparedKey(path))
        assertEquals(
            path,
            NtkSourceSpoolRegistry.normalizedPath("HTTPS://SBXH9.COM$path?from=test"),
        )
    }

    @Test
    fun adjacentAuthorityIsLimitedToAnotherEpisodeOfTheSameWork() {
        val owner = "/manhwa/u-mokdrojr-um67/u-mqh17v8s-wmyq"

        assertTrue(
            NtkStrictEpisodeDiscoveryCoordinator.ntkAdjacentOwnerAllowsTarget(
                owner,
                "/manhwa/u-mokdrojr-um67/u-mqqehf8u-34w2",
            ),
        )
        assertFalse(
            NtkStrictEpisodeDiscoveryCoordinator.ntkAdjacentOwnerAllowsTarget(owner, owner),
        )
        assertFalse(
            NtkStrictEpisodeDiscoveryCoordinator.ntkAdjacentOwnerAllowsTarget(
                owner,
                "/manhwa/another-work/u-mqqehf8u-34w2",
            ),
        )
        assertFalse(
            NtkStrictEpisodeDiscoveryCoordinator.ntkAdjacentOwnerAllowsTarget(
                owner,
                "/webtoon/u-mokdrojr-um67/u-mqqehf8u-34w2",
            ),
        )
    }

    @Test
    fun slugManhwaStartsItsMandatoryAckAtTheCommittedClick() {
        assertTrue(
            NtkStrictEpisodeDiscoveryCoordinator.requiresClickOwnedIsolatedAck(
                "/manhwa/u-mokdrojr-um67/u-mqh185th-1ww5",
            ),
        )
        assertFalse(
            NtkStrictEpisodeDiscoveryCoordinator.requiresClickOwnedIsolatedAck(
                "/manhwa/12345/67890",
            ),
        )
        assertFalse(
            NtkStrictEpisodeDiscoveryCoordinator.requiresClickOwnedIsolatedAck(
                "/webtoon/u-work/u-episode",
            ),
        )
    }

}
