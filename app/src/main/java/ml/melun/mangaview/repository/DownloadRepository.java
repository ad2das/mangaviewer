package ml.melun.mangaview.repository;

import android.content.Context;
import android.content.Intent;

import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.google.gson.Gson;

import org.json.JSONArray;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import ml.melun.mangaview.Downloader;
import ml.melun.mangaview.mangaview.DownloadTitle;
import ml.melun.mangaview.mangaview.Title;

public final class DownloadRepository {
    private DownloadRepository() {
    }

    public static String enqueue(Context context, Title title, JSONArray selected) throws Exception {
        String queueId = UUID.randomUUID().toString();
        File queueDir = new File(context.getFilesDir(), Downloader.QUEUE_DIR);
        if(!queueDir.exists() && !queueDir.mkdirs())
            throw new IllegalStateException("Failed to create download queue");
        File queueFile = new File(queueDir, queueId + ".json");
        String payload = new Gson().toJson(new DownloadTitle(title)) + "\n" + selected.toString();
        writeQueuePayload(queueFile, payload);
        Data input = new Data.Builder()
                .putString(Downloader.KEY_QUEUE_ID, queueId)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(Downloader.class)
                .setInputData(input)
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork(Downloader.WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request);
        return queueId;
    }

    public static void cancelAll(Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(Downloader.WORK_NAME);
        context.sendBroadcast(new Intent().setAction(Downloader.BROADCAST_STOP));
    }

    static String queueTempFileNameForTest(String fileName) {
        return queueTempFileName(fileName);
    }

    static void writeQueuePayloadForTest(File queueFile, String payload) throws Exception {
        writeQueuePayload(queueFile, payload);
    }

    private static void writeQueuePayload(File queueFile, String payload) throws Exception {
        File temp = new File(queueFile.getParentFile(), queueTempFileName(queueFile.getName()));
        File backup = new File(queueFile.getAbsolutePath() + ".bak");
        try {
            if(temp.exists() && !temp.delete())
                throw new IllegalStateException("Failed to delete stale download queue temp file");
            try (FileOutputStream stream = new FileOutputStream(temp, false)) {
                stream.write(payload.getBytes(StandardCharsets.UTF_8));
                stream.flush();
            }
            if(backup.exists())
                backup.delete();
            boolean hadExisting = queueFile.exists();
            if(hadExisting && !queueFile.renameTo(backup))
                throw new IllegalStateException("Failed to backup download queue file");
            if(!temp.renameTo(queueFile)) {
                if(hadExisting)
                    backup.renameTo(queueFile);
                throw new IllegalStateException("Failed to publish download queue file");
            }
            if(backup.exists())
                backup.delete();
        } finally {
            if(temp.exists())
                temp.delete();
        }
    }

    private static String queueTempFileName(String fileName) {
        return fileName + ".tmp";
    }
}
