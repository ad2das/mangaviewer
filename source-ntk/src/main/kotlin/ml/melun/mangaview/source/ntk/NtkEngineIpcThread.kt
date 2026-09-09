package ml.melun.mangaview.source.ntk

import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/** Callback ownership for one already-admitted browser exchange, joined before its lease returns. */
internal suspend fun <T> withNtkEngineIpc(block: suspend (Handler) -> T): T = withContext(Dispatchers.IO) {
    val thread = HandlerThread("ntk-engine-ipc").apply { start() }
    try {
        val handler = Handler(thread.looper)
        withContext(handler.asCoroutineDispatcher()) { block(handler) }
    } finally {
        thread.quitSafely()
        withContext(NonCancellable) { thread.join() }
    }
}
