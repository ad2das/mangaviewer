package ml.melun.mangaview.activity

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.viewer.runtime.ViewerLaunchSpec

/** Direct-entry host. Normal library navigation attaches the same reader to its existing window. */
class ViewerActivity : ComponentActivity() {
    internal lateinit var screen: EngineViewerScreen
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openWithoutTransitionAnimation()
        try {
            val spec = savedInstanceState?.getBundle("reader.session")?.let(ViewerScreenState::read)
                ?: ViewerLaunchSpec.from(intent)
            screen = EngineViewerScreen(this, spec, ::finish, ::launchEpisode)
            val root = screen.create()
            setContentView(root)
            root.requestApplyInsets()
            screen.open()
        } catch (failure: Exception) {
            android.util.Log.e("ViewerActivity", "viewer launch failed", failure)
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    @Suppress("DEPRECATION")
    private fun openWithoutTransitionAnimation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        } else overridePendingTransition(0, 0)
    }

    private fun launchEpisode(episodeId: EpisodeId) {
        startActivity(Intent(this, ViewerActivity::class.java).apply {
            putExtra(ViewerLaunchSpec.EXTRA_SOURCE_ID, episodeId.seriesId.sourceId.value)
            putExtra(ViewerLaunchSpec.EXTRA_SERIES_KEY, episodeId.seriesId.remoteKey)
            putExtra(ViewerLaunchSpec.EXTRA_EPISODE_KEY, episodeId.remoteKey)
        })
        finish()
    }

    override fun onStart() { super.onStart(); if (::screen.isInitialized) screen.enterForeground() }
    override fun onStop() { if (::screen.isInitialized) screen.enterBackground(); super.onStop() }
    override fun onDestroy() { if (::screen.isInitialized) screen.close(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        if (::screen.isInitialized) outState.putBundle("reader.session", ViewerScreenState.write(screen.restorationSpec()))
        super.onSaveInstanceState(outState)
    }

    internal fun reserveWholeTraversalInputEvidence() = screen.reserveWholeTraversalInputEvidence()
    internal fun presentedRegionsSince(sequence: Long) = screen.presentedRegionsSince(sequence)
    internal fun presentationNanosSnapshot() = screen.presentationNanosSnapshot()
    internal fun presentationCadenceNanosSnapshot() = screen.presentationCadenceNanosSnapshot()
    internal fun presentationEvidenceSnapshot() = screen.presentationEvidenceSnapshot()
    internal fun presentationEvidenceSince(sequence: Long) = screen.presentationEvidenceSince(sequence)
    internal fun renderSamplesSnapshot() = screen.renderSamplesSnapshot()
    internal fun motionFrameNanosSnapshot() = screen.motionFrameNanosSnapshot()
    internal fun motionFramesSince(sequence: Long) = screen.motionFramesSince(sequence)
    internal fun presentationRefreshPeriodNanos() = screen.presentationRefreshPeriodNanos()
    internal fun gestureWindowsSnapshot() = screen.gestureWindowsSnapshot()
    internal fun userInputRevisionSnapshot() = screen.userInputRevisionSnapshot()
    internal fun engineInputObservationsSince(ordinal: Long) = screen.engineInputObservationsSince(ordinal)
    internal fun engineInputCloseProof() = screen.engineInputCloseProof()
    internal fun engineFramesSince(ordinal: Long) = screen.engineFramesSince(ordinal)
    internal fun engineFrameCloseProof() = screen.engineFrameCloseProof()
    internal fun viewerTelemetrySnapshot() = screen.viewerTelemetrySnapshot()
    internal fun viewerStartupTimingSnapshot() = screen.viewerStartupTimingSnapshot()
    internal fun viewerCachedResumeSnapshot() = screen.viewerCachedResumeSnapshot()
    internal fun viewerEngineSnapshot() = screen.viewerEngineSnapshot()
    internal suspend fun viewerEngineDiagnosticSnapshot() = screen.viewerEngineDiagnosticSnapshot()
    internal fun viewerEngineFrameSnapshot() = screen.viewerEngineFrameSnapshot()
    internal suspend fun awaitEngineClosed() = screen.awaitEngineClosed()
    internal fun engineDecodeWorkersTerminated() = screen.engineDecodeWorkersTerminated()
    internal fun episodePickerFailureSnapshot() = screen.episodePickerFailureSnapshot()
    internal suspend fun captureNextEngineFrame(top: Int, bottom: Int) = screen.captureNextEngineFrame(top, bottom)
    internal suspend fun captureNextEngineViewportFrame() = screen.captureNextEngineViewportFrame()
    internal fun viewerFailureSnapshot() = screen.viewerFailureSnapshot()
    internal fun isViewerInputSurfaceReady() = screen.isViewerInputSurfaceReady()
}
