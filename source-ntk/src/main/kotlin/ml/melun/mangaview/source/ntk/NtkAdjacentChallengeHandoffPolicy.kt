package ml.melun.mangaview.source.ntk

internal object NtkAdjacentChallengeHandoffPolicy {
    fun shouldInherit(
        completedDelivery: Boolean,
        challengeStarted: Boolean,
        challengeResolved: Boolean,
        challengePath: String?,
        requestedPath: String,
    ): Boolean = completedDelivery &&
        challengeStarted &&
        !challengeResolved &&
        challengePath == requestedPath
}
