package ml.melun.mangaview.source.ntk

/** Monotonic lifecycle of one provider acknowledgement flight. */
internal enum class NtkAckPreparationState {
    COLD,
    ORIGIN_READY,
    CHALLENGE_READY,
    ACK_READY,
    PARKED,
}

/** Signals emitted only after the provider has accepted the current episode authorization. */
internal fun isNtkAuthorizationProof(phase: String, status: Int): Boolean =
    phase == "ack-ready" ||
        phase == "early-ack-ready" ||
        status in 200..299 && (
            phase.startsWith("ack-meta:ok=true,acked=true") ||
                phase.startsWith("challenge-meta:ok=true,ackValid=true")
            )
