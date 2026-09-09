package ml.melun.mangaview.data.network

import org.junit.Assert.assertEquals
import org.junit.Test

class HttpEngineReadTimingTest {
    @Test fun separatesConsumerAdmissionDispatchAndNetworkWaitWithoutDoubleCounting() {
        var now = 100L
        val records = mutableListOf<Map<String, Any>>()
        val timing = HttpEngineReadTiming("https://example.test/image", records::add) { now }
        timing.headers()
        now = 110
        val pull = timing.demand()
        now = 130; pull.admitted()
        now = 160; pull.issued()
        now = 210; pull.completed(12)
        now = 215
        val eof = timing.demand()
        now = 217; eof.admitted()
        now = 220; eof.issued()
        now = 225; eof.completed(-1)
        timing.close(true)
        timing.close(true)
        val result = records.single()
        assertEquals(15L, result["consumerWaitNanos"])
        assertEquals(22L, result["admissionWaitNanos"])
        assertEquals(33L, result["dispatchWaitNanos"])
        assertEquals(55L, result["readWaitIncludingCallbackQueueNanos"])
        assertEquals(12L, result["bytes"])
        assertEquals(1L, result["chunks"])
    }

    @Test fun callbackQueueWaitIsReportedSeparatelyAndCommandStillRunsOnce() {
        var now = 10L
        var calls = 0
        var result: Map<String, Any>? = null
        val timing = HttpEngineReadTiming("https://example.test/image", { result = it }) { now }
        val runnable = timing.queued(Runnable { calls++ })
        now = 90
        runnable.run()
        timing.close(false)
        assertEquals(1, calls)
        assertEquals(80L, result!!["allCallbackQueueWaitNanos"])
        assertEquals(80L, result!!["maxCallbackQueueWaitNanos"])
    }
}
