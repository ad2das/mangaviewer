package ml.melun.mangaview.viewer

import android.app.Instrumentation
import android.os.SystemClock
import androidx.test.uiautomator.Condition
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import ml.melun.mangaview.ViewerApplication
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.ui.library.LibraryEpisodeWarmer

/** Reproduces the real detail screen's eager, fixed-duration warm path without readiness polling. */
internal inline fun <T> withProductionDetailWarmup(
    instrumentation: Instrumentation,
    episode: LiveEpisode,
    block: () -> T,
): T {
    val application = instrumentation.targetContext.applicationContext as ViewerApplication
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val warmer = LibraryEpisodeWarmer(
        scope = scope,
        dispatcher = Dispatchers.IO,
        sources = application.graph.sources,
        repository = application.graph.repository,
        library = application.graph.userLibrary,
    )
    return try {
        warmer.warm(episode.episodeId())
        waitFixedDetailDwell(instrumentation)
        block()
    } finally {
        warmer.cancel()
        scope.cancel()
    }
}

private fun waitFixedDetailDwell(instrumentation: Instrumentation) {
    val started = SystemClock.elapsedRealtime()
    val completed = UiDevice.getInstance(instrumentation).wait(
        Condition<UiDevice, Boolean> {
            SystemClock.elapsedRealtime() - started >= DETAIL_DWELL_MILLIS
        },
        DETAIL_DWELL_MILLIS + 1_000L,
    )
    check(completed == true) { "Detail-screen warmup interval did not elapse" }
}

private fun LiveEpisode.episodeId(): EpisodeId = EpisodeId(
    SeriesId(SourceId(sourceId), seriesKey),
    episodeKey,
)

private const val DETAIL_DWELL_MILLIS = 2_500L
