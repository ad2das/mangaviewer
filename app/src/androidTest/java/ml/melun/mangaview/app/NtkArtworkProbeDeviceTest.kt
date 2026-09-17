package ml.melun.mangaview.app

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import ml.melun.mangaview.ViewerApplication
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.source.CatalogOrder
import ml.melun.mangaview.source.CatalogQuery
import ml.melun.mangaview.source.SeriesKind
import org.junit.Test
import org.junit.runner.RunWith

/** Diagnostic: reports parsed NTK cover keys and whether the artwork fetch succeeds. */
@RunWith(AndroidJUnit4::class)
class NtkArtworkProbeDeviceTest {
    @Test fun reportsCoverKeysAndArtworkFetch() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val graph = (context.applicationContext as ViewerApplication).graph
        val source = graph.sources.require(SourceId("ntk"))
        val page = runCatching {
            source.catalog(CatalogQuery(SeriesKind.WEBTOON, CatalogOrder.POPULAR))
        }.getOrElse { failure ->
            Log.e("NtkArtworkProbe", "catalog failed", failure)
            return@runBlocking
        }
        Log.i("NtkArtworkProbe", "series=${page.items.size}")
        page.items.take(5).forEach { series ->
            Log.i("NtkArtworkProbe", "key=${series.id.remoteKey} thumb=${series.thumbnailKey}")
        }
        val first = page.items.firstOrNull() ?: return@runBlocking
        val started = SystemClock.elapsedRealtime()
        val opened = runCatching { source.openArtwork(first) }.getOrElse { failure ->
            Log.e("NtkArtworkProbe", "openArtwork threw elapsedMs=${SystemClock.elapsedRealtime() - started}", failure)
            return@runBlocking
        }
        val elapsed = SystemClock.elapsedRealtime() - started
        if (opened == null) {
            Log.w("NtkArtworkProbe", "openArtwork=null elapsedMs=$elapsed")
            return@runBlocking
        }
        val bytes = opened.use { page ->
            val buffer = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val read = page.stream.readAtMost(buffer, 0, buffer.size)
                if (read < 0) break
                total += read
            }
            total
        }
        Log.i("NtkArtworkProbe", "openArtwork bytes=$bytes elapsedMs=$elapsed")
    }
}
