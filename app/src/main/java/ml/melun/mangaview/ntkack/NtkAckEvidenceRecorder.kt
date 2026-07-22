package ml.melun.mangaview.ntkack

import java.util.TreeSet

/** Native authority for the exact challenge -> metrics -> canary -> guard -> ACK transcript. */
class NtkAckEvidenceRecorder(
    private val episodePath: String,
    private val requestKeyId: String,
    private val canaryRequired: Boolean,
) {
    enum class State {
        NEW,
        CHALLENGE_200,
        REQUIRED_METRICS_2XX,
        CANARY_200,
        GUARD_PROOF_READY,
        ACK_200_OK,
        PROVED,
        FAILED,
    }

    data class Evidence(
        val challengeRequestDigestSha256: String,
        val challengeResponseDigestSha256: String,
        val challengeTokenDigestSha256: String,
        val observationSetDigestSha256: String,
        val requiredObservationCount: Int,
        val observed2xxCount: Int,
        val canaryRequestDigestSha256: String,
        val canaryResponseDigestSha256: String,
        val canaryStatus: Int,
        val guardVersion: String,
        val guardJsDigestSha256: String,
        val guardWasmDigestSha256: String,
        val guardTpDigestSha256: String,
        val ackRequestBodyDigestSha256: String,
        val ackResponseBodyDigestSha256: String,
        val ackStatus: Int,
        val ackOutcome: String,
        val cookieGrantDigestSha256: String,
    )

    var state: State = State.NEW
        private set

    private var challengeRequestDigest = ""
    private var challengeResponseDigest = ""
    private var challengeTokenDigest = ""
    private var expectedGuardVersion = ""
    private var expectedObservations = emptySet<String>()
    private val observedObservations = LinkedHashMap<String, String>()
    private var canaryRequestDigest = ""
    private var canaryResponseDigest = ""
    private var canaryStatus = 0
    private var guardVersion = ""
    private var guardJsDigest = ""
    private var guardWasmDigest = ""
    private var guardTpDigest = ""
    private var ackRequestDigest = ""
    private var ackResponseDigest = ""
    private var ackStatus = 0
    private var ackOutcome = ""
    private var cookieGrantDigest = ""

    @Synchronized
    fun recordChallenge(
        path: String,
        observedRequestKeyId: String,
        requestDigestSha256: String,
        responseDigestSha256: String,
        status: Int,
        tokenDigestSha256: String,
        guardVersion: String,
        requiredObservationDigests: Set<String>,
    ) {
        requireState(State.NEW)
        if (path != episodePath || observedRequestKeyId != requestKeyId || status != 200 ||
            !validDigest(requestDigestSha256) || !validDigest(responseDigestSha256) ||
            !validDigest(tokenDigestSha256) || guardVersion.isBlank() ||
            requiredObservationDigests.isEmpty() ||
            requiredObservationDigests.any { !validDigest(it) }
        ) return fail("invalid challenge evidence")
        challengeRequestDigest = requestDigestSha256
        challengeResponseDigest = responseDigestSha256
        challengeTokenDigest = tokenDigestSha256
        expectedGuardVersion = guardVersion
        expectedObservations = TreeSet(requiredObservationDigests)
        state = State.CHALLENGE_200
    }

    @Synchronized
    fun recordMetric(
        tokenDigestSha256: String,
        observationDigestSha256: String,
        responseDigestSha256: String,
        status: Int,
    ) {
        if (state != State.CHALLENGE_200) return fail("metric arrived out of order")
        if (tokenDigestSha256 != challengeTokenDigest ||
            observationDigestSha256 !in expectedObservations || !validDigest(responseDigestSha256) ||
            status !in 200..299
        ) return fail("metric evidence mismatch")
        if (observedObservations.putIfAbsent(
                observationDigestSha256,
                NtkAckProofCodec.sha256Utf8("$observationDigestSha256|$status|$responseDigestSha256"),
            ) != null
        ) return fail("duplicate metric evidence")
        if (observedObservations.size == expectedObservations.size) {
            state = State.REQUIRED_METRICS_2XX
            if (!canaryRequired) state = State.CANARY_200
        }
    }

    @Synchronized
    fun recordCanary(
        path: String,
        tokenDigestSha256: String,
        requestDigestSha256: String,
        responseDigestSha256: String,
        status: Int,
    ) {
        if (!canaryRequired || state != State.REQUIRED_METRICS_2XX) {
            return fail("canary arrived out of order")
        }
        if (path != episodePath || tokenDigestSha256 != challengeTokenDigest || status != 200 ||
            !validDigest(requestDigestSha256) || !validDigest(responseDigestSha256)
        ) return fail("canary evidence mismatch")
        canaryRequestDigest = requestDigestSha256
        canaryResponseDigest = responseDigestSha256
        canaryStatus = status
        state = State.CANARY_200
    }

    @Synchronized
    fun recordGuardProof(
        tokenDigestSha256: String,
        version: String,
        jsDigestSha256: String,
        wasmDigestSha256: String,
        tp: String,
    ) {
        if (state != State.CANARY_200) return fail("guard proof arrived out of order")
        if (tokenDigestSha256 != challengeTokenDigest || version != expectedGuardVersion ||
            tp.isBlank() ||
            !validDigest(jsDigestSha256) || !validDigest(wasmDigestSha256)
        ) return fail("guard proof mismatch")
        guardVersion = version
        guardJsDigest = jsDigestSha256
        guardWasmDigest = wasmDigestSha256
        guardTpDigest = NtkAckProofCodec.sha256Utf8(tp)
        state = State.GUARD_PROOF_READY
    }

    @Synchronized
    fun recordAck(
        path: String,
        tokenDigestSha256: String,
        observedRequestKeyId: String,
        requestBodyDigestSha256: String,
        responseBodyDigestSha256: String,
        status: Int,
        outcome: String,
        cookieGrantDigestSha256: String,
    ) {
        if (state != State.GUARD_PROOF_READY) return fail("ACK arrived out of order")
        if (path != episodePath || tokenDigestSha256 != challengeTokenDigest ||
            observedRequestKeyId != requestKeyId || status != 200 || outcome !in setOf("ok", "acked") ||
            !validDigest(requestBodyDigestSha256) || !validDigest(responseBodyDigestSha256) ||
            !validDigest(cookieGrantDigestSha256)
        ) return fail("ACK evidence mismatch")
        ackRequestDigest = requestBodyDigestSha256
        ackResponseDigest = responseBodyDigestSha256
        ackStatus = status
        ackOutcome = outcome
        cookieGrantDigest = cookieGrantDigestSha256
        state = State.ACK_200_OK
        state = State.PROVED
    }

    @Synchronized
    fun evidenceOrThrow(): Evidence {
        check(state == State.PROVED) { "ACK evidence is not PROVED: $state" }
        val observationSetDigest = NtkAckProofCodec.observationSetDigest(
            observedObservations.values.toSet(),
        )
        return Evidence(
            challengeRequestDigest,
            challengeResponseDigest,
            challengeTokenDigest,
            observationSetDigest,
            expectedObservations.size,
            observedObservations.size,
            canaryRequestDigest,
            canaryResponseDigest,
            canaryStatus,
            guardVersion,
            guardJsDigest,
            guardWasmDigest,
            guardTpDigest,
            ackRequestDigest,
            ackResponseDigest,
            ackStatus,
            ackOutcome,
            cookieGrantDigest,
        )
    }

    @Synchronized
    fun cancel(postMayHaveEscaped: Boolean) {
        if (state == State.PROVED || state == State.FAILED) return
        state = State.FAILED
        if (postMayHaveEscaped) {
            throw IllegalStateException("INDETERMINATE_ACK")
        }
    }

    private fun requireState(required: State) {
        check(state == required) { "Expected $required but was $state" }
    }

    private fun fail(message: String): Nothing {
        state = State.FAILED
        throw IllegalArgumentException(message)
    }

    private fun validDigest(value: String): Boolean = value.matches(Regex("[0-9a-f]{64}"))
}
