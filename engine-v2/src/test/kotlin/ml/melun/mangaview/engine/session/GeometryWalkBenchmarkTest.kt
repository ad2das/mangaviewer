package ml.melun.mangaview.engine.session

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Reported JVM benchmark (never asserted): before/after evidence for the geometry walk fix. It
 * restores one deep anchor inside a 5 x 400-page retained window and times [MEASURED_STEPS]
 * representative reverse moves for the production bounded walk and the frozen uncapped reference,
 * each step re-warmed so both paths start from the identical cursor. Run with GEOMETRY_BENCH=1 and
 * paste the output into the report; the counts expose why the ratio is what it is.
 */
class GeometryWalkBenchmarkTest {
    @Test
    fun backwardWalkSteps() {
        assumeTrue(
            "set GEOMETRY_BENCH=1 to run the geometry walk benchmark",
            System.getenv("GEOMETRY_BENCH") == "1",
        )
        val geometry = deepWindowGeometry()
        val anchor = requireNotNull(geometry.anchor)
        val delta = BigRational.of(-7_000_000L)
        val reference = ReferenceGeometryMove(geometry)

        repeat(WARMUP_STEPS) {
            geometry.anchor = anchor
            geometry.move(delta)
        }
        val walkedBefore = geometry.backwardWalkPages
        val buildsBefore = geometry.pageIndices.builds
        val lookupsBefore = geometry.pageIndices.lookups
        var startedAt = System.nanoTime()
        repeat(MEASURED_STEPS) {
            geometry.anchor = anchor
            geometry.move(delta)
        }
        report(
            "new", System.nanoTime() - startedAt,
            geometry.backwardWalkPages - walkedBefore,
            geometry.pageIndices.builds - buildsBefore,
            geometry.pageIndices.lookups - lookupsBefore,
        )

        repeat(WARMUP_STEPS) {
            geometry.anchor = anchor
            reference.move(delta)
        }
        val referenceWalkedBefore = reference.walkPages
        val referenceScansBefore = reference.indexScanSteps
        startedAt = System.nanoTime()
        repeat(MEASURED_STEPS) {
            geometry.anchor = anchor
            reference.move(delta)
        }
        report(
            "reference", System.nanoTime() - startedAt,
            reference.walkPages - referenceWalkedBefore,
            0L,
            reference.indexScanSteps - referenceScansBefore,
        )
    }

    private fun report(
        label: String,
        elapsedNanos: Long,
        walkedPages: Long,
        builds: Long,
        indexWork: Long,
    ) {
        val steps = MEASURED_STEPS.toDouble()
        val line = "[geometry-walk-bench] %s steps=%d ns/step=%.0f walkedPages/step=%.2f " +
            "indexBuilds/step=%.3f indexWork/step=%.2f"
        println(
            line.format(
                label, MEASURED_STEPS, elapsedNanos / steps, walkedPages / steps, builds / steps,
                indexWork / steps,
            ),
        )
        assertTrue("elapsed time must be observable", elapsedNanos > 0L)
    }

    private companion object {
        const val WARMUP_STEPS = 200
        const val MEASURED_STEPS = 2_000
    }
}
