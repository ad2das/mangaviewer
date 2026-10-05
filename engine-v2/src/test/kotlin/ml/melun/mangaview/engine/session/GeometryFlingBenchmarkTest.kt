package ml.melun.mangaview.engine.session

import java.lang.management.ManagementFactory
import java.util.Random
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.PageSpec
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.engine.api.EngineViewport
import ml.melun.mangaview.engine.api.InputOutcome
import ml.melun.mangaview.engine.api.InputSample
import ml.melun.mangaview.engine.api.SessionEvent
import ml.melun.mangaview.engine.api.SourceAnchor
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reported JVM benchmark (never asserted): replays a cold fast fling over an 80-page document the
 * way the viewer drives the geometry per input segment. It is the before/after evidence for the
 * exact-geometry work, not a gate.
 */
class GeometryFlingBenchmarkTest {
    @Test
    fun flingGeometryMoveSteps() {
        val geometry = geometryFixture()
        val velocity = FlingVelocity()
        repeat(WARMUP_STEPS) { geometryStep(geometry, velocity) }
        val allocatedBefore = threadAllocatedBytes()
        val startedAt = System.nanoTime()
        repeat(MEASURED_STEPS) { geometryStep(geometry, velocity) }
        report("geometry.move", startedAt, allocatedBefore)
    }

    @Test
    fun flingSessionInputSteps() {
        val manifest = flingManifest()
        val episode = manifest.id
        var now = 0L
        val session = EngineSession(1L, episode, EngineViewport(1080, 1920)) { now }
        session.dispatch(SessionEvent.ManifestResolved(1L, manifest, true))
        session.dispatch(
            SessionEvent.PositionResolved(1L, SourceAnchor(manifest.pages.first().id, 0L, 0L), null),
        )
        val velocity = FlingVelocity()
        var sequence = 0L
        fun step() {
            now += 1_000_000L
            sequence += 1L
            val update = session.dispatch(
                SessionEvent.Input(
                    InputSample(
                        sequence = sequence,
                        gestureId = 1L,
                        eventTimeNanos = now,
                        deltaScreenUnits = velocity.nextDelta(),
                    ),
                ),
            )
            if (update.receipts.any {
                    it.outcome == InputOutcome.CLAMPED || it.outcome == InputOutcome.DEFERRED
                }
            ) {
                velocity.reverse()
            }
            velocity.drift()
        }
        repeat(WARMUP_STEPS) { step() }
        val allocatedBefore = threadAllocatedBytes()
        val startedAt = System.nanoTime()
        repeat(MEASURED_STEPS) { step() }
        report("session.input", startedAt, allocatedBefore)
    }

    private fun geometryStep(geometry: DocumentGeometry, velocity: FlingVelocity) {
        val result = geometry.move(BigRational.of(velocity.nextDelta()))
        if (result.boundary != null || result.blocker != null || result.remaining.signum() != 0) {
            velocity.reverse()
            geometry.move(BigRational.of(velocity.nextDelta()))
        }
        velocity.drift()
    }

    private fun geometryFixture(): DocumentGeometry {
        val manifest = flingManifest()
        val geometry = DocumentGeometry(manifest.id, EngineViewport(1080, 1920))
        geometry.addManifest(manifest, known = true)
        geometry.anchor = AnchorState(manifest.pages.first().id, BigRational.ZERO, 0L)
        return geometry
    }

    private fun flingManifest(): EpisodeManifest {
        val series = SeriesId(SourceId("bench"), "perf-series")
        val episode = EpisodeId(series, "perf-episode")
        val heights = intArrayOf(3000, 4200, 5400, 6600, 7800, 9000, 10200, 12000)
        val pages = List(80) { ordinal ->
            PageSpec(
                PageId.at(episode, ordinal), ordinal,
                PageDimensions(720, heights[ordinal % heights.size]),
            )
        }
        return EpisodeManifest(episode, "fling-benchmark", pages)
    }

    private fun report(label: String, startedAt: Long, allocatedBefore: Long) {
        val elapsed = System.nanoTime() - startedAt
        val allocated = threadAllocatedBytes() - allocatedBefore
        val nsPerStep = elapsed.toDouble() / MEASURED_STEPS
        val bytesPerStep = allocated.toDouble() / MEASURED_STEPS
        println(
            "GeometryFlingBenchmark[$label] steps=$MEASURED_STEPS " +
                "ns/step=%.1f bytes/step=%.1f".format(nsPerStep, bytesPerStep),
        )
        assertTrue("elapsed time must be observable", elapsed > 0L)
    }

    private fun threadAllocatedBytes(): Long = try {
        val bean = ManagementFactory.getThreadMXBean()
        if (bean is com.sun.management.ThreadMXBean && bean.isThreadAllocatedMemorySupported) {
            bean.isThreadAllocatedMemoryEnabled = true
            bean.getThreadAllocatedBytes(Thread.currentThread().id)
        } else {
            -1L
        }
    } catch (_: Throwable) {
        -1L
    }

    private class FlingVelocity(seed: Long = 20261005L) {
        private val rng = Random(seed)
        private var value = 320_000L

        fun nextDelta(): Long = value
        fun reverse() { value = -value }
        fun drift() {
            value += rng.nextInt(80_000) - 40_000
            value = value.coerceIn(-LIMIT, LIMIT)
        }

        private companion object {
            const val LIMIT = 520_000L
        }
    }

    private companion object {
        const val WARMUP_STEPS = 2_000
        const val MEASURED_STEPS = 10_000
    }
}
