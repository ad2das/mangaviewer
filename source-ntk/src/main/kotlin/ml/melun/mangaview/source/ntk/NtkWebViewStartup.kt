package ml.melun.mangaview.source.ntk

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewOutcomeReceiver
import androidx.webkit.WebViewStartUpConfig
import androidx.webkit.WebViewStartUpResult
import androidx.webkit.WebViewStartupException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Application-owned WebView startup shared only inside the isolated NTK process. */
class NtkWebViewStartup {
    private val lock = Any()
    private val waiters = mutableListOf<Waiter>()
    private var started = false
    private var failure: Throwable? = null
    private var completed = false

    fun start(context: Context) {
        val accepted = synchronized(lock) {
            if (started) false else true.also { started = true }
        }
        if (!accepted) return
        val startedAt = SystemClock.elapsedRealtime()
        val executor = Executors.newSingleThreadExecutor { work ->
            Thread(work, "ntk-webview-startup").apply { isDaemon = true }
        }
        val config = WebViewStartUpConfig.Builder(executor).build()
        runCatching {
            WebViewCompat.startUpWebView(
                context.applicationContext,
                config,
                startupReceiver(startedAt, executor),
            )
        }.onFailure { complete(it, startedAt, executor, null) }
    }

    fun whenReady(ready: () -> Unit, failed: (Throwable) -> Unit) {
        val outcome = synchronized(lock) {
            if (!completed) {
                waiters += Waiter(ready, failed)
                return
            }
            failure
        }
        if (outcome == null) ready() else failed(outcome)
    }

    private fun startupReceiver(
        startedAt: Long,
        executor: ExecutorService,
    ) = object : WebViewOutcomeReceiver<WebViewStartUpResult, WebViewStartupException> {
        override fun onResult(result: WebViewStartUpResult) {
            complete(null, startedAt, executor, result)
        }

        override fun onError(error: WebViewStartupException) {
            complete(error, startedAt, executor, null)
        }
    }

    private fun complete(
        error: Throwable?,
        startedAt: Long,
        executor: ExecutorService,
        result: WebViewStartUpResult?,
    ) {
        val callbacks = synchronized(lock) {
            if (completed) return
            completed = true
            failure = error
            waiters.toList().also { waiters.clear() }
        }
        executor.shutdown()
        val elapsed = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
        Log.d(
            STARTUP_TAG,
            "complete success=${error == null} elapsedMs=$elapsed " +
                "uiMs=${result?.totalTimeInUiThreadMillis ?: -1}",
        )
        callbacks.forEach { waiter ->
            if (error == null) waiter.ready() else waiter.failed(error)
        }
    }

    private data class Waiter(
        val ready: () -> Unit,
        val failed: (Throwable) -> Unit,
    )
}

/** Supplies process-local startup state without a mutable global singleton. */
interface NtkWebViewStartupOwner {
    val ntkWebViewStartup: NtkWebViewStartup
}

private const val STARTUP_TAG = "NtkWebViewStartup"
