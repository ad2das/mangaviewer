package ml.melun.mangaview.reader

import ml.melun.mangaview.runtime.AppDispatchers

/** Single coordinator lane. It must only run short state transitions, never blocking work. */
object ReaderPipelineExecutors {
    fun executeCoordinator(block: () -> Unit): AppDispatchers.TaskHandle =
        AppDispatchers.submitNtkViewerCritical(block)
}
