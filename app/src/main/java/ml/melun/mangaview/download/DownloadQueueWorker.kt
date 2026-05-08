package ml.melun.mangaview.download

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.Worker
import androidx.work.WorkerParameters
import ml.melun.mangaview.Downloader
import java.io.File

class DownloadQueueWorker(
    appContext: Context,
    params: WorkerParameters,
) : Worker(appContext, params) {
    override fun doWork(): Result {
        val queueId = inputData.getString(KEY_QUEUE_ID) ?: return Result.failure()
        val file = File(applicationContext.filesDir, "$QUEUE_DIR/$queueId.json")
        if (!file.exists()) return Result.failure()
        return try {
            val payload = file.readText()
            val split = payload.indexOf('\n')
            if (split <= 0) return Result.failure()
            val title = payload.substring(0, split)
            val selected = payload.substring(split + 1)
            val intent = Intent(applicationContext, Downloader::class.java).apply {
                action = Downloader.ACTION_QUEUE
                putExtra("title", title)
                putExtra("selected", selected)
            }
            if (Build.VERSION.SDK_INT >= 26) {
                applicationContext.startForegroundService(intent)
            } else {
                applicationContext.startService(intent)
            }
            file.delete()
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val KEY_QUEUE_ID = "queue_id"
        const val QUEUE_DIR = "download_queue"
    }
}
