package ml.melun.mangaview.viewer.runtime

import java.io.Closeable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Ownership transfers at invocation; cancellation never frees pixels being read by the owner. */
internal suspend fun <T : Closeable> uploadOnOwnerQueue(
    pixels: T,
    post: (Runnable) -> Boolean,
    upload: (T) -> Long,
    release: (Long) -> Unit,
): Long {
    val finished = CompletableDeferred<Unit>()
    var ownedKey = 0L
    try {
        ownedKey = enqueueUpload(pixels, post, upload, release, finished)
        withContext(NonCancellable) { finished.await() }
        currentCoroutineContext().ensureActive()
        return ownedKey.also { ownedKey = 0L }
    } finally {
        try {
            withContext(NonCancellable) { finished.await() }
        } finally {
            if (ownedKey > 0L) release(ownedKey)
        }
    }
}

private suspend fun <T : Closeable> enqueueUpload(
    pixels: T,
    post: (Runnable) -> Boolean,
    upload: (T) -> Long,
    release: (Long) -> Unit,
    finished: CompletableDeferred<Unit>,
): Long = suspendCancellableCoroutine { continuation ->
    val task = Runnable {
        try {
            if (!continuation.isActive) return@Runnable
            val key = upload(pixels)
            continuation.resume(key) { _, abandoned, _ ->
                if (abandoned > 0L) release(abandoned)
            }
        } catch (failure: Throwable) {
            continuation.resumeWith(Result.failure(failure))
        } finally {
            try { pixels.close() } finally { finished.complete(Unit) }
        }
    }
    val accepted = try {
        post(task)
    } catch (failure: Throwable) {
        try { pixels.close() } finally { finished.complete(Unit) }
        continuation.resumeWith(Result.failure(failure))
        return@suspendCancellableCoroutine
    }
    if (!accepted) {
        try { pixels.close() } finally { finished.complete(Unit) }
        continuation.resumeWith(Result.failure(IllegalStateException("Renderer queue is closed")))
    }
}
