package ml.melun.mangaview.ntkack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NtkAckEvidenceRecorderTest {
    private val f = NtkAckTestFixtures

    @Test
    fun exactServerTranscriptReachesProved() {
        val recorder = recorder()
        challenge(recorder)
        recorder.recordMetric(f.D3, f.D4, f.D6, 204)
        recorder.recordMetric(f.D3, f.D5, f.D7, 200)
        recorder.recordCanary(f.PATH, f.D3, f.D6, f.D7, 200)
        recorder.recordGuardProof(f.D3, "guard-v1", f.D8, f.D9, "tp-value")
        recorder.recordAck(f.PATH, f.D3, "key-1", f.DA, f.DB, 200, "ok", f.DC)

        assertEquals(NtkAckEvidenceRecorder.State.PROVED, recorder.state)
        assertEquals(2, recorder.evidenceOrThrow().observed2xxCount)
    }

    @Test
    fun callbacksOrDomCountsCannotCreateProof() {
        val recorder = recorder()
        assertThrows(IllegalStateException::class.java) { recorder.evidenceOrThrow() }
    }

    @Test
    fun missingOrNon2xxMetricFailsClosed() {
        val missing = recorder()
        challenge(missing)
        missing.recordMetric(f.D3, f.D4, f.D6, 200)
        assertThrows(IllegalStateException::class.java) { missing.evidenceOrThrow() }

        val rejected = recorder()
        challenge(rejected)
        assertThrows(IllegalArgumentException::class.java) {
            rejected.recordMetric(f.D3, f.D4, f.D6, 403)
        }
        assertEquals(NtkAckEvidenceRecorder.State.FAILED, rejected.state)
    }

    @Test
    fun wrongChallengeCanaryGuardAckOrKeyFailsClosed() {
        assertRejectedAfterChallenge { it.recordMetric(f.D2, f.D4, f.D6, 200) }
        assertRejectedAfterMetrics { it.recordCanary("/manhwa/1/2", f.D3, f.D6, f.D7, 200) }
        assertRejectedAfterCanary {
            it.recordGuardProof(f.D3, "guard-other", f.D8, f.D9, "tp-value")
        }
        assertRejectedAfterGuard { it.recordAck(f.PATH, f.D3, "key-1", f.DA, f.DB, 200, "false", f.DC) }
        assertRejectedAfterGuard { it.recordAck(f.PATH, f.D3, "key-1", f.DA, f.DB, 204, "ok", f.DC) }
        assertRejectedAfterGuard { it.recordAck(f.PATH, f.D3, "key-other", f.DA, f.DB, 200, "ok", f.DC) }
    }

    private fun recorder() = NtkAckEvidenceRecorder(f.PATH, "key-1", canaryRequired = true)

    private fun challenge(recorder: NtkAckEvidenceRecorder) = recorder.recordChallenge(
        f.PATH, "key-1", f.D1, f.D2, 200, f.D3, "guard-v1", setOf(f.D4, f.D5),
    )

    private fun metrics(recorder: NtkAckEvidenceRecorder) {
        recorder.recordMetric(f.D3, f.D4, f.D6, 200)
        recorder.recordMetric(f.D3, f.D5, f.D7, 200)
    }

    private fun canary(recorder: NtkAckEvidenceRecorder) {
        metrics(recorder)
        recorder.recordCanary(f.PATH, f.D3, f.D6, f.D7, 200)
    }

    private fun guard(recorder: NtkAckEvidenceRecorder) {
        canary(recorder)
        recorder.recordGuardProof(f.D3, "guard-v1", f.D8, f.D9, "tp-value")
    }

    private fun assertRejectedAfterChallenge(action: (NtkAckEvidenceRecorder) -> Unit) {
        val recorder = recorder(); challenge(recorder)
        assertThrows(RuntimeException::class.java) { action(recorder) }
        assertEquals(NtkAckEvidenceRecorder.State.FAILED, recorder.state)
    }

    private fun assertRejectedAfterMetrics(action: (NtkAckEvidenceRecorder) -> Unit) {
        val recorder = recorder(); challenge(recorder); metrics(recorder)
        assertThrows(RuntimeException::class.java) { action(recorder) }
        assertEquals(NtkAckEvidenceRecorder.State.FAILED, recorder.state)
    }

    private fun assertRejectedAfterCanary(action: (NtkAckEvidenceRecorder) -> Unit) {
        val recorder = recorder(); challenge(recorder); canary(recorder)
        assertThrows(RuntimeException::class.java) { action(recorder) }
        assertEquals(NtkAckEvidenceRecorder.State.FAILED, recorder.state)
    }

    private fun assertRejectedAfterGuard(action: (NtkAckEvidenceRecorder) -> Unit) {
        val recorder = recorder(); challenge(recorder); guard(recorder)
        assertThrows(RuntimeException::class.java) { action(recorder) }
        assertEquals(NtkAckEvidenceRecorder.State.FAILED, recorder.state)
    }
}
