package ml.melun.mangaview.ntkack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NtkAckCookieBoundaryTest {
    private val f = NtkAckTestFixtures

    @Test
    fun seedAndGrantAllowlistsAreExact() {
        val seed = NtkAckCookie("cf_clearance", "value", domain = "newtoki.example", path = "/")
        assertEquals(1, NtkAckCookieBoundary.validateSeeds(f.ORIGIN, f.PATH, listOf(seed)).size)
        assertThrows(IllegalArgumentException::class.java) {
            NtkAckCookieBoundary.validateSeeds(
                f.ORIGIN,
                f.PATH,
                listOf(seed.copy(name = "ad_ack")),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            NtkAckCookieBoundary.validateSeeds(
                f.ORIGIN,
                f.PATH,
                listOf(seed.copy(name = "outside")),
            )
        }
        assertEquals(1, NtkAckCookieBoundary.validateGrants(f.ORIGIN, f.PATH, listOf(f.grant())).size)
    }

    @Test
    fun grantsRequireActualResponseScopeAndSetCookieDigest() {
        assertThrows(IllegalArgumentException::class.java) {
            NtkAckCookieBoundary.validateGrants(
                f.ORIGIN,
                f.PATH,
                listOf(f.grant().copy(setCookieDigestSha256 = "")),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            NtkAckCookieBoundary.validateGrants(
                f.ORIGIN,
                f.PATH,
                listOf(f.grant().copy(responseUrl = "https://evil.example/api/ad/ack")),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            NtkAckCookieBoundary.validateGrants(
                f.ORIGIN,
                f.PATH,
                listOf(f.grant().copy(domain = "evil.example")),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            NtkAckCookieBoundary.validateGrants(
                f.ORIGIN,
                f.PATH,
                listOf(f.grant().copy(path = "/webtoon/")),
            )
        }
    }
}
