package ml.melun.mangaview.source.ntk

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

/** Transfers one page resource, joining the old attempt before an ACK-driven replacement. */
internal suspend fun <T : Any> raceNtkAuthorizationAndPage(
    awaitAuthorization: suspend () -> Boolean,
    attempt: suspend () -> Result<T>,
    release: (T) -> Unit,
): T {
    var selected: T? = null
    try {
        return coroutineScope {
            val pages = Channel<Result<T>>(onUndeliveredElement = { it.getOrNull()?.let(release) })
            val authorization = async { awaitAuthorization() }
            val direct = launch { pages.send(attempt()) }
            try {
                val first = select<Result<T>?> {
                    pages.onReceive { it }
                    authorization.onAwait { null }
                }
                val result = when {
                    first?.isSuccess == true -> first.getOrThrow()
                    first != null -> {
                        val failure = requireNotNull(first.exceptionOrNull())
                        if (!authorization.await()) throw failure
                        attempt().getOrElse { retryFailure ->
                            retryFailure.addSuppressed(failure)
                            throw retryFailure
                        }
                    }
                    !authorization.await() -> pages.receive().getOrThrow()
                    else -> {
                        direct.cancelAndJoin()
                        pages.cancel()
                        attempt().getOrThrow()
                    }
                }
                selected = result
                result
            } finally {
                pages.cancel()
                direct.cancel()
                authorization.cancel()
            }
        }
    } catch (failure: Throwable) {
        selected?.let(release)
        throw failure
    }
}
