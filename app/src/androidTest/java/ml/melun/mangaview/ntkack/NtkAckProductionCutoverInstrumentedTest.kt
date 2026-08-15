package ml.melun.mangaview.ntkack

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ml.melun.mangaview.MainApplication
import ml.melun.mangaview.LiveNetworkAssume
import ml.melun.mangaview.mangaview.MTitle
import ml.melun.mangaview.mangaview.Manga
import ml.melun.mangaview.reader.NtkAuthoritativeManifest
import ml.melun.mangaview.reader.NtkEpisodeDocumentPlanParser
import ml.melun.mangaview.reader.NtkExactManifestProofKind
import ml.melun.mangaview.reader.NtkSourceSpoolRegistry
import ml.melun.mangaview.reader.NtkStrictEpisodeDiscoveryCoordinator
import ml.melun.mangaview.reader.NtkViewerImageApiAuthorityParser
import ml.melun.mangaview.runtime.ViewerTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** One production coordinator flight through the currently selected exact authority route. */
@RunWith(AndroidJUnit4::class)
class NtkAckProductionCutoverInstrumentedTest {
    @Test
    fun fixed31InstallsExactAuthorityThroughTheCurrentProductionRoute() {
        LiveNetworkAssume.assumeEnabled()
        val context = ApplicationProvider.getApplicationContext<Context>()
        checkNotNull(MainApplication.p).setNtkSitePresetForDiagnostics(ORIGIN)
        val client = checkNotNull(MainApplication.getHttpClient())
        client.clearNtkTransientLoads()
        client.clearPageCache()
        listOf("ad_ack", "ad_ack_c", "ad_guard_l", "ntk_ve").forEach {
            client.setCookie(it, null)
        }
        client.resetStrictExactLogicalRequestCountsForTest()
        NtkEpisodeDocumentPlanParser.resetInvocationCountForTest()
        NtkViewerImageApiAuthorityParser.resetInvocationCountForTest()

        val installed = AtomicReference<NtkAuthoritativeManifest>()
        val latch = CountDownLatch(1)
        // Production starts strict discovery only for a currently owned reader generation.  Keep
        // this live-network test on that same lifecycle path instead of bypassing the ownership
        // fence with a coordinator-only synthetic call.
        val viewerGeneration = ViewerTelemetry.viewerOpen(
            "33727",
            FIXED31_PATH,
            "instrumented_test",
        )
        val subscription = NtkSourceSpoolRegistry.addAuthoritativeManifestListener { path, manifest ->
            if (path == FIXED31_PATH) {
                installed.compareAndSet(null, manifest)
                latch.countDown()
            }
        }
        try {
            val manga = Manga(1692251, "fixed31", "", MTitle.base_comic).apply {
                ntkEpisodePath = FIXED31_PATH
                ntkImageWorkId = "33727"
                ntkImageEpisodeId = "1692251"
            }
            assertTrue(
                "production strict flight did not start",
                NtkStrictEpisodeDiscoveryCoordinator.start(client, manga),
            )
            assertTrue("production strict flight timed out", latch.await(45, TimeUnit.SECONDS))
            val manifest = installed.get()
            assertNotNull(manifest)
            assertEquals(31, manifest.seal.pageCount)
            assertEquals(1L, client.strictDocumentLogicalRequestCount())
            assertEquals(1L, NtkEpisodeDocumentPlanParser.invocationCount())
            when (manifest.proof.kind) {
                NtkExactManifestProofKind.TOKEN_BOUND_GENERATED -> {
                    // A fresh document plus an EOF/digest-verified numeric body is already the
                    // stronger low-latency authority.  Production deliberately cancels the
                    // isolated ACK route instead of issuing a redundant signed API request.
                    assertEquals(0L, client.strictImageApiLogicalRequestCount())
                    assertEquals(0L, NtkViewerImageApiAuthorityParser.invocationCount())
                }
                NtkExactManifestProofKind.VIEWER_IMAGE_API -> {
                    // If the generated candidate is physically unavailable, the compatibility
                    // route must still be exactly one isolated signed exchange.
                    assertEquals(1L, client.strictImageApiLogicalRequestCount())
                    assertEquals(1L, NtkViewerImageApiAuthorityParser.invocationCount())
                    val hello = NtkAckBrowserClient.get(context).verifiedHello()
                    assertNotNull(hello)
                    assertTrue(hello!!.servicePid > 0 && hello.servicePid != android.os.Process.myPid())
                }
                else -> throw AssertionError("Unexpected exact proof kind: ${manifest.proof.kind}")
            }
        } finally {
            subscription.close()
            ViewerTelemetry.viewerClosedAfterDrain("instrumented_test_complete", viewerGeneration)
        }
    }

    companion object {
        private const val ORIGIN = "https://sbxh9.com"
        private const val FIXED31_PATH = "/manhwa/33727/1692251"
    }
}
