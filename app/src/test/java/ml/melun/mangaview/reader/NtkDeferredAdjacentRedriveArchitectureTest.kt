package ml.melun.mangaview.reader

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkDeferredAdjacentRedriveArchitectureTest {
    private val source = File(
        "src/main/java/ml/melun/mangaview/reader/ReaderSession.kt",
    ).readText()

    @Test
    fun deferredOwnerReoffersBusyAndFinishesExplicitCancellation() {
        val redrive = functionBody("private fun redriveDeferredAdjacentAppend(")
        assertTrue(redrive.contains("AppendStartResult.BUSY -> deferredAdjacentPrepareMailbox.reoffer(request)"))
        assertTrue(redrive.contains("NTK_ADJACENT_COMPLETION_WAIT_RECHECK_MS"))
        assertTrue(redrive.contains("AppendStartResult.CANCELLED -> if (!silentMissing)"))
        assertTrue(redrive.contains("postBoundaryAppendFinished("))
    }

    @Test
    fun everyTimerRedriveUsesTheResultOwningHelper() {
        val flush = functionBody("private fun flushDeferredAdjacentPrepare(wakeupToken: Long)")
        val currentInstall = functionBody("private fun scheduleAdjacentCurrentInstallRetry(")
        assertTrue(flush.contains("deferredAdjacentPrepareMailbox.take(wakeupToken)"))
        assertTrue(flush.contains("redriveDeferredAdjacentAppend(pending)"))
        assertTrue(currentInstall.contains("scheduleDeferredAdjacentPrepare("))
        assertTrue(!currentInstall.contains("main.postDelayed"))
    }

    @Test
    fun completionAccelerationReplacesTheOwnedWakeupInsteadOfPostingAnotherFlush() {
        val completion = functionBody("private fun maybeWarmCompletedForwardEpisode(")
        assertTrue(completion.contains("deferredAdjacentPrepareMailbox.accelerate()"))
        assertTrue(completion.contains("hasCanonicalDrawableCompletion(ref)"))
        assertTrue(!completion.contains("main.post { flushDeferredAdjacentPrepare"))
    }

    @Test
    fun adjacentCompletionUsesCanonicalHistoryBeforeEvictablePixelState() {
        val complete = functionBody("private fun isEpisodeFullyDrawableForAdjacent(")
        assertTrue(complete.contains("hasCanonicalDrawableCompletion(page)"))
        assertTrue(complete.contains("hasListenerDrawableDelivery(index, page)"))
        val clear = functionBody("private fun clearDrawableReadyState(")
        assertTrue(!clear.contains("episodeDrawableCompletionLedger"))
        val streamGate = functionBody(
            "private fun isCurrentEpisodeCompleteForImmediateAdjacentStream(",
        )
        assertTrue(streamGate.contains("hasCanonicalDrawableCompletion(page)"))
        val testGate = functionBody("fun hasFullyReadyEpisodeForTest(")
        assertTrue(testGate.contains("hasCanonicalDrawableCompletion(page)"))
    }

    @Test
    fun residentExactP0ParksOnPhysicalIdleInsteadOfFrameRatePolling() {
        val schedule = functionBody("private fun scheduleInitialAdjacentRunwayAppendRetry(")
        val idle = functionBody(
            "private fun scheduleParkedInitialAdjacentRunwayWakeAfterPhysicalIdle(",
        )
        val wake = functionBody("private fun wakeParkedInitialAdjacentRunwayRetries(")

        assertTrue(schedule.contains("parkedInitialAdjacentRunwayRetries[exactP0WakePath] = retry"))
        assertTrue(schedule.contains("NTK_INITIAL_ADJACENT_EVENT_WAIT_WATCHDOG_MS"))
        assertTrue(schedule.contains("liveness insurance for a lost Android lifecycle edge"))
        assertTrue(idle.contains("parkedInitialAdjacentIdleWakePosted.compareAndSet(false, true)"))
        assertTrue(idle.contains("!viewportBusy.get()"))
        assertTrue(idle.contains("wakeParkedInitialAdjacentRunwayRetries()"))
        assertTrue(wake.contains("main.removeCallbacks(retry)"))
        assertTrue(wake.contains("retry.run()"))
    }

    private fun functionBody(signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "Missing function: $signature" }
        val open = source.indexOf('{', start)
        check(open >= 0)
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        error("Unclosed function: $signature")
    }
}
