package ml.melun.mangaview.app

import org.junit.Assert.assertEquals
import org.junit.Test

class NewxtoonUserAgentClaimsTest {
    private val engineUserAgent =
        "Mozilla/5.0 (Linux; Android 14; sdk_gphone64_x86_64 Build/UP1A.231005.007; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/124.0.6367.82 Mobile Safari/537.36"

    @Test
    fun constructingTheIdentityDoesNotResolveTheEngineUserAgent() {
        var calls = 0
        NewxtoonUserAgentClaims(
            resolveEngineUserAgent = { calls++; engineUserAgent },
            spoofsDeviceIdentity = true,
            greaseBrand = "Not A;Brand",
        )
        assertEquals("construction must not touch the WebView user agent", 0, calls)
    }

    @Test
    fun theEmulatorSpoofRewritesTheUserAgentLikeChromeForAndroid() {
        val claims = NewxtoonUserAgentClaims({ engineUserAgent }, true, "Not A;Brand")
        assertEquals(engineUserAgent, claims.engineUserAgent)
        assertEquals("124.0.6367.82", claims.engineChromeVersion)
        assertEquals(
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
            claims.sourceUserAgent,
        )
        assertEquals(
            "\"Not A;Brand\";v=\"99\", \"Google Chrome\";v=\"124\", \"Chromium\";v=\"124\"",
            claims.clientHints,
        )
    }

    @Test
    fun aRealDeviceKeepsTheEngineUserAgentByteForByte() {
        val claims = NewxtoonUserAgentClaims({ engineUserAgent }, false, "Not/A)Brand")
        assertEquals(engineUserAgent, claims.sourceUserAgent)
        assertEquals("124.0.6367.82", claims.engineChromeVersion)
        assertEquals(
            "\"Not/A)Brand\";v=\"99\", \"Google Chrome\";v=\"124\", \"Chromium\";v=\"124\"",
            claims.clientHints,
        )
    }

    @Test
    fun aMissingChromeVersionFallsBackToTheShippedOne() {
        val claims = NewxtoonUserAgentClaims({ "WebView/4.0" }, false, "Not A;Brand")
        assertEquals("124.0.0.0", claims.engineChromeVersion)
    }
}
