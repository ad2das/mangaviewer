package ml.melun.mangaview.source.ntk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NtkBrowserResourcePolicyTest {
    @Test
    fun blocksPresentationOnlyResources() {
        assertEquals(
            "text/css",
            NtkBrowserResourcePolicy.blockedMimeType(
                "apihost.store",
                "/_next/static/css/viewer.css",
                "text/css",
            ),
        )
        assertEquals(
            "application/javascript",
            NtkBrowserResourcePolicy.blockedMimeType(
                "apihost.store",
                "/init/theme.js",
                "application/javascript",
            ),
        )
        assertNull(NtkBrowserResourcePolicy.blockedMimeType(
            "apihost.store",
            "/_next/static/chunks/app/viewer.js",
            "application/javascript",
        ))
    }

    @Test
    fun preservesOnlyAckApiAndGuardModuleTraffic() {
        assertNull(NtkBrowserResourcePolicy.blockedMimeType(
            "toki.test",
            "/api/ad/challenge",
            "application/json",
        ))
        assertNull(NtkBrowserResourcePolicy.blockedMimeType(
            "toki.test",
            "/wasm/ad-guard/ad_guard.js",
            "application/javascript",
        ))
        assertNull(NtkBrowserResourcePolicy.blockedMimeType(
            "toki.test",
            "/wasm/ad-guard/ad_guard_bg.wasm",
            "application/wasm",
        ))
        assertNull(NtkBrowserResourcePolicy.blockedMimeType(
            "apihost.store",
            "/init/block.js",
            "application/javascript",
        ))
    }
}
