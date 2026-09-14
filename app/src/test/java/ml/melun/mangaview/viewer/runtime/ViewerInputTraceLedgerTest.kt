package ml.melun.mangaview.viewer.runtime

import ml.melun.mangaview.engine.api.FrameIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerInputTraceLedgerTest {
    @Test fun traceLedgerMirrorsProductionCoalescingAndDrainOrderExactly() {
        val production = PointerDeltaLedger()
        val trace = ViewerInputTraceLedger()
        production.begin(1_000f); trace.begin(1_000f, true)
        listOf(980f, 940f, 930f, 1_050f, 1_200f, 1_150f, 1_100f).forEachIndexed { index, y ->
            assertEquals(production.append(y), trace.append(y, 100L + index, 5), 0.0)
        }
        assertEquals(production.drain(), trace.drain().map { it.delta })
        production.rebase(300f); trace.rebase(300f)
        production.append(280f); trace.append(280f, 200L, 6)
        assertEquals(production.drain(), trace.drain().map { it.delta })
    }

    @Test fun segmentSpansTrackEveryMergedSameSignSample() {
        val trace = ViewerInputTraceLedger()
        trace.begin(1_000f, true)
        trace.append(900f, 100L, 2)
        trace.append(800f, 110L, 2)
        trace.append(1_200f, 120L, 2)

        val drained = trace.drain()
        assertEquals(2, drained.size)
        val merged = drained[0]
        assertEquals(1L, merged.id)
        assertEquals(2, merged.pointerId)
        assertEquals(1L, merged.firstSampleOrdinal)
        assertEquals(2L, merged.lastSampleOrdinal)
        assertEquals(2, merged.mergedSampleCount)
        assertEquals(100L, merged.firstEventTimeNanos)
        assertEquals(110L, merged.lastEventTimeNanos)
        assertEquals(200.0, merged.delta, 0.0)
        assertFalse(merged.synthetic)
        val reversal = drained[1]
        assertEquals(2L, reversal.id)
        assertEquals(3L, reversal.firstSampleOrdinal)
        assertEquals(3L, reversal.lastSampleOrdinal)
        assertEquals(-400.0, reversal.delta, 0.0)
    }

    @Test fun zeroDeltaSamplesKeepOrdinalsWithoutCreatingSegments() {
        val trace = ViewerInputTraceLedger()
        trace.begin(500f, true)
        assertEquals(0.0, trace.append(500f, 10L, 0), 0.0)
        assertTrue(trace.drain().isEmpty())
        trace.append(400f, 20L, 0)

        val drained = trace.drain()
        assertEquals(1, drained.size)
        assertEquals(2L, drained.single().firstSampleOrdinal)
        assertEquals(2L, drained.single().lastSampleOrdinal)
        assertEquals(100.0, drained.single().delta, 0.0)
    }

    @Test fun sampleOrdinalsContinueAcrossDrainsAndGestures() {
        val trace = ViewerInputTraceLedger()
        trace.begin(0f, true)
        trace.append(-80f, 1L, 0)
        trace.drain()
        trace.begin(50f, true)
        trace.append(40f, 2L, 1)

        val segment = trace.drain().single()
        assertEquals(2L, segment.firstSampleOrdinal)
        assertEquals(1, segment.pointerId)
    }

    @Test fun untrackedGestureStoresNoSamplesAndNeverBackfillsMidToggle() {
        val trace = ViewerInputTraceLedger()
        trace.begin(1_000f, false)
        repeat(64) { index ->
            assertEquals(0.0, trace.append(900f - index, 100L + index, 0), 0.0)
        }
        trace.rebase(50f)
        assertFalse(trace.isTracking)
        assertTrue(trace.drain().isEmpty())
        assertEquals(0L, trace.sampleCount)
        // A later explicit begin decides tracking; the untracked gesture stays unmapped.
        trace.begin(200f, true)
        trace.append(150f, 500L, 1)
        assertEquals(50.0, trace.drain().single().delta, 0.0)
        assertEquals(1L, trace.sampleCount)
    }

    @Test fun observationOnlyMirrorNeverThrowsWhenProductionResetsWithPendingInput() {
        val production = PointerDeltaLedger()
        production.begin(10f)
        production.append(5f)
        assertThrows(IllegalStateException::class.java) { production.begin(1f) }

        val trace = ViewerInputTraceLedger()
        trace.begin(10f, true)
        trace.append(5f, 1L, 0)
        trace.begin(1f, true)
        assertTrue(trace.drain().isEmpty())
        assertEquals(1L, trace.sampleCount)
    }

    @Test fun syntheticFlingStepsShareTheSegmentNamespaceButStayUnmapped() {
        val trace = ViewerInputTraceLedger()
        trace.begin(10f, true)
        trace.append(5f, 1L, 3)
        val real = trace.drain().single()
        val synthetic = trace.synthetic(-12.5)

        assertTrue(synthetic.synthetic)
        assertFalse(real.synthetic)
        assertTrue(synthetic.id > real.id)
        assertEquals(0, synthetic.pointerId)
        assertEquals(0L, synthetic.firstSampleOrdinal)
        assertEquals(0L, synthetic.lastSampleOrdinal)
        assertEquals(-12.5, synthetic.delta, 0.0)
        assertTrue(trace.drain().isEmpty())
        assertEquals(1L, trace.sampleCount)
    }

    @Test fun traceGestureEpochGroupsEverySampleFromOneDown() {
        val epoch = ViewerInputTraceGesture()
        assertEquals(1L, epoch.id)
        epoch.beginTouch()
        val touch = epoch.id
        val first = viewerSampleTraceName(touch, 0, 1, 10L, 10L, 5.0)
        val second = viewerSampleTraceName(touch, 0, 2, 20L, 10L, 5.0)
        epoch.beginTouch()
        val nextTouch = viewerSampleTraceName(epoch.id, 0, 3, 30L, 30L, 5.0)

        assertTrue(first.startsWith("viewer_sample:2:"))
        assertTrue(second.startsWith("viewer_sample:2:"))
        assertTrue(nextTouch.startsWith("viewer_sample:3:"))
        assertTrue(touch > 0L)
    }

    @Test fun traceNamesCarryTheDocumentedSchemaShape() {
        val identity = FrameIdentity(1, 2, 3, 4, 5, 6)
        assertEquals("engine_input:1:2:3:4:5:s6", engineInputTraceName(1, 2, 3, 4, 5, 6))
        assertEquals("engine_present:1:4:2:3:7", enginePresentTraceName(identity, 7))
        assertEquals("engine_present_fence:5:6:1:7:8", enginePresentFenceTraceName(identity, 1, 7, 8))
        assertEquals("viewer_sample:2:3:4:5:6:3ff8000000000000", viewerSampleTraceName(2, 3, 4, 5, 6, 1.5))
        val segment = ViewerInputTraceLedger.Segment(1, 2, 3, 4, 5, 6, 7, -1.5)
        assertEquals("viewer_segment:1:2:real:3-4:5:bff8000000000000", viewerSegmentTraceName(segment))
        assertEquals("viewer_segment:1:2:fling:3-4:5:bff8000000000000",
            viewerSegmentTraceName(segment.copy(synthetic = true)))
        assertEquals("viewer_segment:lost", viewerSegmentTraceName(null))
    }

    @Test fun everyTraceNameFitsTheAtraceSectionLimitAtWorstCase() {
        val segment = ViewerInputTraceLedger.Segment(Long.MAX_VALUE, Int.MIN_VALUE, Long.MAX_VALUE,
            Long.MAX_VALUE, Int.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, -Double.MAX_VALUE)
        val identity = FrameIdentity(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE,
            Long.MAX_VALUE, Long.MAX_VALUE)
        val names = listOf(
            viewerSampleTraceName(Long.MAX_VALUE, Int.MIN_VALUE, Long.MAX_VALUE, Long.MAX_VALUE,
                Long.MAX_VALUE, Double.MAX_VALUE),
            viewerSegmentTraceName(segment),
            viewerSegmentTraceName(segment.copy(synthetic = true)),
            viewerSegmentTraceName(null),
            engineInputTraceName(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE,
                Long.MAX_VALUE, Long.MAX_VALUE),
            enginePresentTraceName(identity, Long.MAX_VALUE),
            enginePresentFenceTraceName(identity, Int.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE),
        )
        names.forEach { assertTrue("trace section name exceeds 127 chars (${it.length}): $it", it.length <= 127) }
    }
}
