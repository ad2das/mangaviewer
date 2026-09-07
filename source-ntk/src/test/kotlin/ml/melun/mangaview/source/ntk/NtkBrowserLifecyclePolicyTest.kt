package ml.melun.mangaview.source.ntk

import org.junit.Assert.assertEquals
import org.junit.Test

class NtkBrowserLifecyclePolicyTest {
    @Test
    fun completedManifestReusesResidentBrowserForTheNextEpisode() {
        assertEquals(
            NtkBrowserSupersession.REUSE_RESIDENT_BROWSER,
            browserSupersession("{\"ok\":true}"),
        )
    }

    @Test
    fun unfinishedManifestRetiresBrowserToIsolateLateAuthorization() {
        assertEquals(
            NtkBrowserSupersession.RETIRE_UNFINISHED_BROWSER,
            browserSupersession(null),
        )
    }

    @Test
    fun clientQuiescesConsumedManifestInsteadOfDestroyingItsRenderer() {
        assertEquals(NtkBrowserProtocol.MSG_QUIESCE, browserSupersessionControl(true))
        assertEquals(NtkBrowserProtocol.MSG_CANCEL, browserSupersessionControl(false))
    }
}
