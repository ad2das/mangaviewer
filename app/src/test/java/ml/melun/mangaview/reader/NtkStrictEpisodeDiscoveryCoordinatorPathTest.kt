package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NtkStrictEpisodeDiscoveryCoordinatorPathTest {
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
}
