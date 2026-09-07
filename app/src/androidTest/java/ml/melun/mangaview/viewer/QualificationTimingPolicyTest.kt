package ml.melun.mangaview.viewer

import org.junit.Assert.*
import org.junit.Test

class QualificationTimingPolicyTest {
    private val hash = "a".repeat(64)
    private val exception = TimingException("device-frame", "surface-gap-ms", TimingCause.DEVICE, hash, 120.0, "device")
    private val observation = TimingObservation("surface-gap-ms", 110.0, 100.0, false, "episode")

    @Test fun unknownAndUnregisteredDelaysAlwaysFail() {
        assertFalse(QualificationTimingPolicy("device").evaluate(observation).passed)
        assertFalse(QualificationTimingPolicy("device", listOf(exception)).evaluate(observation).passed)
    }

    @Test fun exceptionsRequireExactEvidenceCauseSampleAndBound() {
        val policy = QualificationTimingPolicy("device", listOf(exception))
        val attribution = TimingAttribution(exception.id, hash, "episode", TimingCause.DEVICE, true)
        assertTrue(policy.evaluate(observation.copy(attribution = attribution)).passed)
        assertFalse(policy.evaluate(observation.copy(attribution = attribution.copy(cause = TimingCause.APP))).passed)
        assertFalse(policy.evaluate(observation.copy(attribution = attribution.copy(sampleKey = "other"))).passed)
        assertFalse(policy.evaluate(observation.copy(value = 121.0, attribution = attribution)).passed)
        assertFalse(policy.evaluate(observation.copy(attribution = attribution.copy(independentlyVerified = false))).passed)
    }
}
