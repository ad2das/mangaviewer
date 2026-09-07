package ml.melun.mangaview.viewer

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import ml.melun.mangaview.ViewerApplication
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.source.PreparationIntent
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NtkAckTimingInstrumentedTest {
    @Test
    fun coldExactEpisodeManifestUsesTheProductionAckPath() = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val application = instrumentation.targetContext.applicationContext as ViewerApplication
        val source = application.graph.sources.require(SourceId("ntk"))
        val episode = EpisodeId(
            SeriesId(SourceId("ntk"), arguments.getString("ntkSeriesKey") ?: SERIES_KEY),
            arguments.getString("ntkEpisodeKey") ?: EPISODE_KEY,
        )
        val started = SystemClock.elapsedRealtime()

        source.prepare(episode, PreparationIntent.INITIAL_VIEW)
        val manifest = source.manifest(episode)

        val elapsed = SystemClock.elapsedRealtime() - started
        Log.i(
            TAG,
            "manifest-ready elapsedMs=$elapsed pages=${manifest.pages.size} " +
                "next=${manifest.nextEpisodeId?.remoteKey.orEmpty()}",
        )
        assertTrue("NTK manifest was empty", manifest.pages.isNotEmpty())
    }

    private companion object {
        const val TAG = "NtkAckProbe"
        const val SERIES_KEY = "/manhwa/35525"
        const val EPISODE_KEY = "/manhwa/35525/u-mt8lz567-og83"
    }
}
