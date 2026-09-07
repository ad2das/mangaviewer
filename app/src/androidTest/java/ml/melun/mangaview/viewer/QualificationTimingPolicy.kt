package ml.melun.mangaview.viewer

/** An attribution is evidence from outside the gate, never inferred from a slow observation. */
internal enum class TimingCause { EXTERNAL, DEVICE, APP, UNKNOWN }
internal data class TimingException(
    val id: String,
    val gate: String,
    val cause: TimingCause,
    val evidenceSha256: String,
    val maximumValue: Double,
    val deviceFingerprint: String,
)
internal data class TimingAttribution(
    val exceptionId: String,
    val evidenceSha256: String,
    val sampleKey: String,
    val cause: TimingCause,
    val independentlyVerified: Boolean,
)
internal data class TimingObservation(
    val gate: String,
    val value: Double,
    val limit: Double,
    val inclusive: Boolean,
    val sampleKey: String,
    val attribution: TimingAttribution? = null,
)
internal data class TimingDecision(val passed: Boolean, val exceptionId: String? = null, val reason: String)

internal class QualificationTimingPolicy(
    private val deviceFingerprint: String,
    private val exceptions: List<TimingException> = emptyList(),
) {
    init {
        require(exceptions.map { it.id }.distinct().size == exceptions.size)
        exceptions.forEach {
            require(it.cause == TimingCause.EXTERNAL || it.cause == TimingCause.DEVICE)
            require(it.evidenceSha256.matches(Regex("[a-fA-F0-9]{64}")))
            require(it.maximumValue.isFinite() && it.maximumValue >= 0.0)
            require(it.deviceFingerprint == deviceFingerprint)
        }
    }

    fun evaluate(observation: TimingObservation): TimingDecision {
        if (!observation.value.isFinite() || observation.value < 0.0) {
            return TimingDecision(false, reason = "${observation.gate}: measurement unavailable")
        }
        val within = if (observation.inclusive) observation.value <= observation.limit
            else observation.value < observation.limit
        if (within) return TimingDecision(true, reason = "Within goal")
        val attribution = observation.attribution
        val registered = exceptions.singleOrNull { it.id == attribution?.exceptionId }
        val proven = attribution != null && registered != null && attribution.independentlyVerified &&
            attribution.sampleKey == observation.sampleKey && registered.gate == observation.gate &&
            attribution.cause == registered.cause && attribution.evidenceSha256 == registered.evidenceSha256 &&
            observation.value <= registered.maximumValue
        return if (proven) TimingDecision(true, registered!!.id, "Proven ${registered.cause} limitation; observed=${observation.value}")
        else TimingDecision(false, reason = "${observation.gate}=${observation.value} exceeded goal ${observation.limit}; no matching preregistered independent attribution")
    }

    /** Collection may continue for a registered candidate, but only the offline proof can grant pass. */
    fun canAwaitIndependentAttribution(observation: TimingObservation): Boolean =
        observation.value.isFinite() && observation.value >= 0.0 && exceptions.any {
            it.gate == observation.gate && observation.value <= it.maximumValue
        }

    companion object {
        fun fromJson(text: String, fingerprint: String): QualificationTimingPolicy {
            val json = org.json.JSONObject(text)
            require(json.optString("deviceFingerprint", fingerprint) == fingerprint)
            val records = json.getJSONArray("exceptions")
            val exceptions = (0 until records.length()).map { index -> records.getJSONObject(index).let {
                TimingException(it.getString("id"), it.getString("gate"), TimingCause.valueOf(it.getString("cause")),
                    it.getString("evidenceSha256"), it.getDouble("maximumValue"), it.getString("deviceFingerprint"))
            } }
            return QualificationTimingPolicy(fingerprint, exceptions)
        }
    }
}
