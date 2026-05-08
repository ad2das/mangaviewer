package ml.melun.mangaview.download;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;

import ml.melun.mangaview.Downloader;
import ml.melun.mangaview.report.CrashReporter;

public class DownloadQueueWorker extends Worker {
    public static final String KEY_QUEUE_ID = "queue_id";
    public static final String QUEUE_DIR = "download_queue";

    public DownloadQueueWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        String queueId = getInputData().getString(KEY_QUEUE_ID);
        if(queueId == null || queueId.length() == 0)
            return Result.failure();
        File file = new File(getApplicationContext().getFilesDir(), QUEUE_DIR + "/" + queueId + ".json");
        if(!file.exists())
            return Result.failure();
        try {
            String payload = readPayload(file);
            int split = payload.indexOf('\n');
            if(split <= 0)
                return Result.failure();
            Intent intent = new Intent(getApplicationContext(), Downloader.class);
            intent.setAction(Downloader.ACTION_QUEUE);
            intent.putExtra("title", payload.substring(0, split));
            intent.putExtra("selected", payload.substring(split + 1));
            if(Build.VERSION.SDK_INT >= 26)
                getApplicationContext().startForegroundService(intent);
            else
                getApplicationContext().startService(intent);
            file.delete();
            return Result.success();
        } catch (Exception e) {
            CrashReporter.record(e);
            return Result.retry();
        }
    }

    private String readPayload(File file) throws java.io.IOException {
        try(FileInputStream input = new FileInputStream(file);
            ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while((read = input.read(buffer)) > 0)
                output.write(buffer, 0, read);
            return output.toString();
        }
    }
}
