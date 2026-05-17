package ml.melun.mangaview;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import ml.melun.mangaview.mangaview.MainPageWebtoon;
import ml.melun.mangaview.report.CrashReporter;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class ClassificationDbUpdater {
    private static final long CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L;
    private static final long FAILURE_RETRY_MS = 60 * 60 * 1000L;
    private static final String PREF = "classificationDbUpdater";
    private static final String WEBTOON_URL = "https://raw.githubusercontent.com/ad2das/mangaviewer/main/webtoon-classification.json";
    private static final String COMIC_URL = "https://raw.githubusercontent.com/ad2das/mangaviewer/main/comic-classification.json";
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build();

    private ClassificationDbUpdater() {
    }

    public static void updateInBackground(Context context) {
        if(context == null)
            return;
        Context appContext = context.getApplicationContext();
        updateOne(appContext, false, WEBTOON_URL, "webtoon-classification.json");
        updateOne(appContext, true, COMIC_URL, "comic-classification.json");
    }

    private static void updateOne(Context context, boolean comic, String url, String fileName) {
        SharedPreferences pref = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String key = comic ? "comic" : "webtoon";
        try {
            long now = System.currentTimeMillis();
            long lastChecked = pref.getLong(key + ".checkedAt", 0);
            if(now - lastChecked < CHECK_INTERVAL_MS)
                return;

            Request.Builder builder = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "MangaView")
                    .header("Cache-Control", "no-cache");
            String etag = pref.getString(key + ".etag", "");
            String lastModified = pref.getString(key + ".lastModified", "");
            if(etag.length() > 0)
                builder.header("If-None-Match", etag);
            if(lastModified.length() > 0)
                builder.header("If-Modified-Since", lastModified);

            try(Response response = CLIENT.newCall(builder.build()).execute()) {
                SharedPreferences.Editor editor = pref.edit().putLong(key + ".checkedAt", now);
                if(response.code() == 304) {
                    editor.apply();
                    return;
                }
                if(!response.isSuccessful()) {
                    editor.apply();
                    return;
                }
                ResponseBody body = response.body();
                if(body == null) {
                    editor.apply();
                    return;
                }
                String json = body.string();
                if(!isValidDb(json)) {
                    editor.apply();
                    return;
                }
                if(writeCachedDb(context, fileName, json)) {
                    String newEtag = response.header("ETag", "");
                    String newLastModified = response.header("Last-Modified", "");
                    if(newEtag != null && newEtag.length() > 0)
                        editor.putString(key + ".etag", newEtag);
                    if(newLastModified != null && newLastModified.length() > 0)
                        editor.putString(key + ".lastModified", newLastModified);
                    MainPageWebtoon.invalidateClassificationDbs();
                }
                editor.apply();
            }
        } catch (IOException e) {
            long retryCheckedAt = System.currentTimeMillis() - CHECK_INTERVAL_MS + FAILURE_RETRY_MS;
            pref.edit().putLong(key + ".checkedAt", retryCheckedAt).apply();
        } catch (Exception e) {
            CrashReporter.record(e);
        }
    }

    private static boolean isValidDb(String json) {
        try {
            if(json == null || json.length() == 0)
                return false;
            JSONObject titles = new JSONObject(json).optJSONObject("titles");
            return titles != null && titles.length() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean writeCachedDb(Context context, String fileName, String json) {
        File dir = MainPageWebtoon.classificationDbCacheDir(context);
        if(dir == null)
            return false;
        if(!dir.exists() && !dir.mkdirs())
            return false;
        File target = new File(dir, fileName);
        File tmp = new File(dir, fileName + ".tmp");
        try(FileOutputStream output = new FileOutputStream(tmp)) {
            output.write(json.getBytes(StandardCharsets.UTF_8));
            output.flush();
        } catch (Exception e) {
            CrashReporter.record(e);
            return false;
        }
        if(target.exists())
            return replaceWithBackup(target, tmp);
        return tmp.renameTo(target);
    }

    private static boolean replaceWithBackup(File target, File tmp) {
        File backup = new File(target.getParentFile(), target.getName() + ".bak");
        if(backup.exists() && !backup.delete())
            return false;
        if(!target.renameTo(backup))
            return false;
        if(tmp.renameTo(target)) {
            backup.delete();
            return true;
        }
        backup.renameTo(target);
        return false;
    }
}
