package ml.melun.mangaview.source.ntk

import android.content.Context
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewOutcomeReceiver
import androidx.webkit.WebViewStartUpConfig
import androidx.webkit.WebViewStartUpResult
import androidx.webkit.WebViewStartupException
import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal class NtkBrowserStartup(
    private val context: Context,
    private val ready: () -> Unit,
    private val failed: (Throwable) -> Unit,
) : Closeable {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ntk-webview-startup")
    }

    fun begin() {
        val config = WebViewStartUpConfig.Builder(executor).build()
        runCatching {
            WebViewCompat.startUpWebView(
                context.applicationContext,
                config,
                object : WebViewOutcomeReceiver<WebViewStartUpResult, WebViewStartupException> {
                    override fun onResult(result: WebViewStartUpResult) = ready()
                    override fun onError(error: WebViewStartupException) = failed(error)
                },
            )
        }.onFailure(failed)
    }

    override fun close() {
        executor.shutdownNow()
    }
}
