package ml.melun.mangaview.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.Closeable
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.data.network.OkHttpTransportFactory
import ml.melun.mangaview.source.CatalogOrder
import ml.melun.mangaview.source.CatalogQuery
import ml.melun.mangaview.source.ContentSource
import ml.melun.mangaview.source.SeriesKind
import ml.melun.mangaview.source.wfwf.DEFAULT_WFWF_ORIGIN
import ml.melun.mangaview.source.wfwf.WfwfConfig
import ml.melun.mangaview.source.wfwf.WfwfContentSource
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WfwfOriginLiveProbeTest {
    @Test
    fun catalogAndEpisodeListResolveThroughTheLiveOrigin() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val factory = OkHttpTransportFactory(Dispatchers.IO)
        val transport = factory.protect(factory.create())
        val report = JSONObject()
        try {
            val source = WfwfContentSource(
                WfwfConfig(DEFAULT_WFWF_ORIGIN, "Mozilla/5.0 (Linux; Android 15) probe"),
                transport,
                scope,
                originProbeObserver = { Log.i("WfwfOrigin", it) },
            )
            val page = withTimeout(90_000) {
                source.catalog(CatalogQuery(SeriesKind.COMIC, CatalogOrder.LATEST))
            }
            report.put("catalogItems", page.items.size)
            report.put("catalogNext", page.nextCursor ?: JSONObject.NULL)
            val seriesId = page.items.firstOrNull()?.id
            if (seriesId != null) {
                val episodes = withTimeout(90_000) { source.episodes(seriesId) }
                report.put("episodeCount", episodes.items.size)
                report.put("firstEpisode", episodes.items.firstOrNull()?.id?.remoteKey ?: JSONObject.NULL)
                val episodeId = episodes.items.firstOrNull()?.id
                if (episodeId != null) {
                    val manifest = withTimeout(90_000) { source.manifest(episodeId) }
                    report.put("pageCount", manifest.pages.size)
                    report.put("firstPageBytes", drain(source, manifest.pages.first().id))
                    report.put("secondPageBytes", drain(source, manifest.pages[1].id))
                }
            }
        } catch (failure: Throwable) {
            report.put("failure", failure.javaClass.simpleName + ": " + failure.message)
        } finally {
            (transport as? Closeable)?.close()
        }
        File(context.getExternalFilesDir(null), "wfwf-origin-live-probe.json")
            .writeText(report.toString(2))
        Log.i("WfwfProbe", report.toString())
        Unit
    }

    private suspend fun drain(source: ContentSource, pageId: PageId): Int {
        val opened = withTimeout(120_000) { source.openPage(pageId) }
        var total = 0
        try {
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = opened.stream.readAtMost(buffer, 0, buffer.size)
                if (read <= 0) break
                total += read
            }
        } finally {
            opened.close()
        }
        return total
    }
}
