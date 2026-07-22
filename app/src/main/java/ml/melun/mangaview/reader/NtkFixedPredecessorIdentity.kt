package ml.melun.mangaview.reader

/** Exact admission-token predecessor binding, independent of telemetry collection windows. */
internal object NtkFixedPredecessorIdentity {
    fun invalid(
        workGeneration: Long,
        admissionSequence: Long,
        priorWorkGeneration: Long,
        priorAdmissionSequence: Long,
        priorRetirementSequence: Long
    ): Boolean {
        if (workGeneration <= 0L || admissionSequence <= 0L) return true
        if (workGeneration == 1L) {
            return priorWorkGeneration != 0L ||
                priorAdmissionSequence != 0L || priorRetirementSequence != 0L
        }
        return priorWorkGeneration <= 0L ||
            priorWorkGeneration >= workGeneration ||
            priorAdmissionSequence <= 0L ||
            priorAdmissionSequence >= admissionSequence ||
            priorRetirementSequence <= 0L
    }
}
