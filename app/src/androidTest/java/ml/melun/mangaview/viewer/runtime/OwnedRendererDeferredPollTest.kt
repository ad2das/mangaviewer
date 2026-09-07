package ml.melun.mangaview.viewer.runtime

import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import ml.melun.mangaview.viewer.FixedPx
import ml.melun.mangaview.viewer.session.SceneSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Functional renderer lifecycle regression only; no live content or corpus credit. */
@RunWith(AndroidJUnit4::class)
class OwnedRendererDeferredPollTest {
    @Test
    fun continuousProductionPollsBeforeProducerStopsAndTerminatesEachTokenOnce() {
        ActivityScenario.launch(OwnedRendererProbeActivity::class.java).use { scenario ->
            var probe: OwnedRendererProbeActivity? = null
            var closeRequested = false
            scenario.onActivity { probe = it }
            val owned = requireNotNull(probe)
            try {
                assertTrue("Probe surface did not become available", owned.awaitSurface())
                val offersBeforeObservation = owned.frameOfferCountForVerification()
                scenario.onActivity { it.start(functionalScene()) }

                assertTrue(
                    "No presentation callback arrived during continuous production",
                    owned.awaitPresentationCountAtLeast(1, PRESENTATION_DEADLINE_MILLIS),
                )
                val firstCount = owned.presentationSnapshot().size
                assertTrue("Probe producer stopped before observation", owned.frameProductionActiveForVerification())
                assertTrue(
                    "Presentation polling did not progress while producer was active",
                    owned.awaitPresentationCountAtLeast(
                        firstCount + 1,
                        PRESENTATION_DEADLINE_MILLIS,
                    ),
                )
                val duringProduction = owned.presentationSnapshot()
                assertTrue("Producer ended before the in-flight observation", owned.frameProductionActiveForVerification())
                assertTrue(
                    "Producer did not offer frames during the in-flight observation",
                    owned.frameOfferCountForVerification() > offersBeforeObservation,
                )

                val cleanupKinds = setOf(
                    PresentationTimestampKind.CANCELLED,
                    PresentationTimestampKind.DROPPED,
                    PresentationTimestampKind.CONTEXT_LOST,
                )
                assertTrue(
                    "A close/context terminal was reported before producer stop: " +
                        duringProduction.map { it.timestampKind },
                    duringProduction.none { it.timestampKind in cleanupKinds },
                )
                Log.i(TAG, "beforeStop=${duringProduction.groupingBy(::disposition).eachCount()}")

                lateinit var closed: CountDownLatch
                scenario.onActivity {
                    closeRequested = true
                    closed = it.finishDiagnosticCapture()
                }
                assertTrue(
                    "Renderer close did not drain its terminal callbacks",
                    closed.await(CLOSE_DEADLINE_MILLIS, TimeUnit.MILLISECONDS),
                )

                val afterClose = owned.presentationSnapshot()
                assertTrue("Close produced no terminal presentation records", afterClose.isNotEmpty())
                val byToken = afterClose.groupingBy { it.token }.eachCount()
                assertTrue(
                    "An issued/native-submit-attempt token received duplicate terminal dispositions: $byToken",
                    byToken.values.all { it == 1 },
                )
                val tokens = byToken.keys.sorted()
                val nextIssuedToken = readNextIssuedTokenAfterClose(owned)
                assertTrue(
                    "Renderer issued no native-submit-attempt token before close: nextToken=$nextIssuedToken",
                    nextIssuedToken > 1L,
                )
                val issuedTokens = (1L until nextIssuedToken).toList()
                assertEquals(
                    "An issued/native-submit-attempt token has no terminal disposition",
                    issuedTokens,
                    tokens,
                )
                assertFalse("Frame producer remained active after close", owned.frameProductionActiveForVerification())
                assertTrue(
                    "A presentation callback arrived after native renderer destruction",
                    owned.awaitPresentationCountUnchanged(
                        afterClose.size,
                        NO_LATE_CALLBACK_DEADLINE_MILLIS,
                    ),
                )
                Log.i(TAG, "afterClose=${afterClose.groupingBy(::disposition).eachCount()}")
            } finally {
                if (!closeRequested) {
                    closeRequested = true
                    val cleanup = runCatching {
                        lateinit var closed: CountDownLatch
                        scenario.onActivity { closed = it.finishDiagnosticCapture() }
                        closed.await(CLOSE_DEADLINE_MILLIS, TimeUnit.MILLISECONDS)
                    }.getOrDefault(false)
                    check(cleanup) { "Deferred-poll regression cleanup did not complete" }
                }
            }
        }
    }

    @Test
    fun unchangedPresentationWaitRejectsCallbackBeforeDeadline() {
        ActivityScenario.launch(OwnedRendererProbeActivity::class.java).use { scenario ->
            var probe: OwnedRendererProbeActivity? = null
            var closeRequested = false
            scenario.onActivity { probe = it }
            val owned = requireNotNull(probe)
            try {
                assertTrue("Probe surface did not become available", owned.awaitSurface())
                scenario.onActivity { it.start(functionalScene()) }
                assertTrue(
                    "No presentation callback arrived during continuous production",
                    owned.awaitPresentationCountAtLeast(1, PRESENTATION_DEADLINE_MILLIS),
                )
                val expected = owned.presentationSnapshot().size
                val startedAt = SystemClock.uptimeMillis()
                assertFalse(
                    "Unchanged wait accepted a deliberate producer callback",
                    owned.awaitPresentationCountUnchanged(expected, UNCHANGED_WAIT_DEADLINE_MILLIS),
                )
                val elapsed = SystemClock.uptimeMillis() - startedAt
                assertTrue(
                    "Callback-count change was not observed before the full deadline: ${elapsed}ms",
                    elapsed < UNCHANGED_WAIT_DEADLINE_MILLIS - 100L,
                )
                assertTrue(
                    "The deliberate callback-count change was not recorded",
                    owned.presentationSnapshot().size > expected,
                )
            } finally {
                if (!closeRequested) {
                    closeRequested = true
                    lateinit var closed: CountDownLatch
                    scenario.onActivity {
                        closeRequested = true
                        closed = it.finishDiagnosticCapture()
                    }
                    check(closed.await(CLOSE_DEADLINE_MILLIS, TimeUnit.MILLISECONDS)) {
                        "Deferred-poll wait regression cleanup did not complete"
                    }
                }
            }
        }
    }

    private fun readNextIssuedTokenAfterClose(activity: OwnedRendererProbeActivity): Long {
        val rendererField = activity.javaClass.getDeclaredField("renderer").apply {
            isAccessible = true
        }
        val renderer = rendererField.get(activity)
            ?: error("OwnedRendererProbeActivity.renderer was null after close")
        val nextTokenField = renderer.javaClass.getDeclaredField("nextToken").apply {
            isAccessible = true
        }
        val value = nextTokenField.get(renderer)
        return (value as? Long)
            ?: error("OwnedSurfaceRenderer.nextToken reflection returned ${value?.javaClass}")
    }

    private fun disposition(presentation: OwnedPresentation): String = when (presentation.timestampKind) {
        PresentationTimestampKind.COMPOSITION_LATCH -> "COMPOSITION_LATCH_NON_PHYSICAL_PROXY"
        PresentationTimestampKind.DISPLAY_PRESENT -> "DISPLAY_PRESENT_OBSERVATION_ONLY"
        PresentationTimestampKind.CANCELLED,
        PresentationTimestampKind.DROPPED,
        PresentationTimestampKind.CONTEXT_LOST -> "TERMINAL_CLEANUP"
        else -> "OTHER_NATIVE_DISPOSITION"
    }

    private fun functionalScene() = SceneSnapshot(
        generation = 1L,
        lifecycleEpoch = 1L,
        sceneRevision = 1L,
        geometryRevision = 1L,
        viewportRevision = 0L,
        windowId = 0L,
        localOrigin = FixedPx.ZERO,
        scrollOffset = FixedPx.ZERO,
        contentHeight = FixedPx.fromPixels(8_000),
        quads = emptyList(),
    )

    private companion object {
        const val TAG = "OwnedRendererDeferredPollTest"
        const val PRESENTATION_DEADLINE_MILLIS = 10_000L
        const val CLOSE_DEADLINE_MILLIS = 10_000L
        const val NO_LATE_CALLBACK_DEADLINE_MILLIS = 1_000L
        const val UNCHANGED_WAIT_DEADLINE_MILLIS = 10_000L
    }
}
