package ml.melun.mangaview.source.ntk

/** Turns the client's exact at-least-once retry into one fresh browser navigation. */
internal object NtkDeliveryRedrivePolicy {
    fun shouldRedrive(
        exactRedelivery: Boolean,
        completed: Boolean,
        previousRedrives: Int,
        state: NtkAckPreparationState,
        authorizationProgressed: Boolean,
    ): Boolean = exactRedelivery && !completed && previousRedrives == 0 &&
        !authorizationProgressed && state.ordinal < NtkAckPreparationState.CHALLENGE_READY.ordinal
}
