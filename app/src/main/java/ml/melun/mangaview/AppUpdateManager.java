package ml.melun.mangaview;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.util.Base64;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import ml.melun.mangaview.report.CrashReporter;
import ml.melun.mangaview.runtime.AppDispatchers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class AppUpdateManager {
    private static final String VERSION_API_URL = "https://api.github.com/repos/ad2das/mangaviewer/contents/version.json?ref=main";
    private static final String RAW_VERSION_URL = "https://raw.githubusercontent.com/ad2das/mangaviewer/main/version.json";
    private static final String PREF = "appUpdate";
    private static final String KEY_VERSION = "latestVersion";
    private static final String KEY_LINK = "latestLink";
    private static final String APK_MIME = "application/vnd.android.package-archive";
    private static final String UPDATE_APK_PREFIX = "mangaViewer-update-";
    private static final String UPDATE_APK_SUFFIX = ".apk";
    private static final int APK_PARALLEL_PARTS = 4;
    private static final long APK_PARALLEL_MIN_BYTES = 8L * 1024L * 1024L;
    private static final OkHttpClient VERSION_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(7, TimeUnit.SECONDS)
            .build();
    private static final OkHttpClient APK_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .build();
    private static boolean checkStartedThisSession;
    private static boolean dialogShownThisSession;
    private static boolean downloading;
    private static File pendingInstallApk;

    private AppUpdateManager() {
    }

    public static void checkForUpdate(Activity activity) {
        if(activity == null || downloading)
            return;
        Context appContext = activity.getApplicationContext();
        AppDispatchers.runIo(() -> deleteStaleUpdateApks(appContext, pendingInstallApk));
        UpdateInfo cachedInfo = readCachedUpdateInfo(appContext);
        if(isUpdateAvailable(cachedInfo) && !dialogShownThisSession) {
            dialogShownThisSession = true;
            showUpdateDialog(activity, cachedInfo);
        }
        if(checkStartedThisSession)
            return;
        checkStartedThisSession = true;
        AppDispatchers.runIo(() -> {
            UpdateInfo info = fetchUpdateInfo(appContext);
            if(info == null)
                return;
            cacheUpdateInfo(appContext, info);
            if(!isUpdateAvailable(info) || dialogShownThisSession)
                return;
            dialogShownThisSession = true;
            AppDispatchers.runOnMain(() -> showUpdateDialog(activity, info));
        });
    }

    public static void resumePendingInstall(Activity activity) {
        if(activity == null || pendingInstallApk == null || !pendingInstallApk.exists())
            return;
        if(canRequestPackageInstalls(activity))
            installApk(activity, pendingInstallApk);
    }

    private static UpdateInfo fetchUpdateInfo(Context context) {
        UpdateInfo apiInfo = fetchUpdateInfoFromUrl(VERSION_API_URL, true);
        if(apiInfo != null)
            return apiInfo;
        return fetchUpdateInfoFromUrl(RAW_VERSION_URL + "?t=" + System.currentTimeMillis(), false);
    }

    private static UpdateInfo fetchUpdateInfoFromUrl(String url, boolean allowGithubContentsEnvelope) {
        try {
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "MangaView")
                    .header("Accept", allowGithubContentsEnvelope
                            ? "application/vnd.github.raw, application/vnd.github+json, application/json, text/plain, */*"
                            : "application/json, text/plain, */*")
                    .header("Cache-Control", "no-cache")
                    .header("Pragma", "no-cache")
                    .build();
            try(Response response = VERSION_CLIENT.newCall(request).execute()) {
                if(!response.isSuccessful())
                    return null;
                ResponseBody body = response.body();
                if(body == null)
                    return null;
                JSONObject json = parseVersionJson(body.string(), allowGithubContentsEnvelope);
                int version = json.optInt("version", -1);
                String link = json.optString("link", "");
                if(version <= 0 || link.length() == 0)
                    return null;
                return new UpdateInfo(version, link);
            }
        } catch (Exception e) {
            CrashReporter.record(e);
            return null;
        }
    }

    private static JSONObject parseVersionJson(String body, boolean allowGithubContentsEnvelope) throws Exception {
        JSONObject json = new JSONObject(body);
        if(json.has("version") || !allowGithubContentsEnvelope)
            return json;
        String encoded = json.optString("content", "");
        if(encoded.length() == 0)
            return json;
        byte[] decoded = Base64.decode(encoded.replace("\n", ""), Base64.DEFAULT);
        return new JSONObject(new String(decoded, StandardCharsets.UTF_8));
    }

    private static boolean isUpdateAvailable(UpdateInfo info) {
        return info != null && info.version > currentVersionCode(MainApplication.appContext)
                && info.link != null && info.link.length() > 0;
    }

    private static UpdateInfo readCachedUpdateInfo(Context context) {
        if(context == null)
            return null;
        SharedPreferences pref = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        int version = pref.getInt(KEY_VERSION, -1);
        String link = pref.getString(KEY_LINK, "");
        if(version <= 0 || link == null || link.length() == 0)
            return null;
        return new UpdateInfo(version, link);
    }

    private static void cacheUpdateInfo(Context context, UpdateInfo info) {
        if(context == null || info == null)
            return;
        UpdateInfo current = readCachedUpdateInfo(context);
        if(current != null && current.version > info.version)
            return;
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_VERSION, info.version)
                .putString(KEY_LINK, info.link)
                .apply();
    }

    private static void showUpdateDialog(Activity activity, UpdateInfo info) {
        if(!Utils.canUseContextForUi(activity) || info == null)
            return;
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle("새 버전 업데이트")
                .setMessage("새 APK가 있습니다.\n현재: " + currentVersionCode(activity) + "\n최신: " + info.version + "\n\n다운로드 후 설치 화면을 바로 열까요?")
                .setPositiveButton("업데이트", (dialog, which) -> downloadAndInstall(activity, info))
                .setNegativeButton("나중에", null);
        Utils.safeShowDialog(builder);
    }

    private static void downloadAndInstall(Activity activity, UpdateInfo info) {
        if(activity == null || info == null || downloading)
            return;
        downloading = true;
        ProgressDialog progressDialog = new ProgressDialog(activity);
        progressDialog.setTitle("업데이트 다운로드");
        progressDialog.setMessage("APK를 받는 중입니다...");
        progressDialog.setIndeterminate(false);
        progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progressDialog.setCancelable(false);
        try {
            progressDialog.show();
        } catch (RuntimeException e) {
            CrashReporter.record(e);
        }

        Context appContext = activity.getApplicationContext();
        AppDispatchers.runIo(() -> {
            File apk = downloadApk(appContext, info, progress -> AppDispatchers.runOnMain(() -> {
                try {
                    if(progressDialog.isShowing())
                        progressDialog.setProgress(progress);
                } catch (RuntimeException e) {
                    CrashReporter.record(e);
                }
            }));
            AppDispatchers.runOnMain(() -> {
                downloading = false;
                try {
                    if(progressDialog.isShowing())
                        progressDialog.dismiss();
                } catch (RuntimeException e) {
                    CrashReporter.record(e);
                }
                if(apk == null) {
                    Utils.safeToast(activity, "업데이트 다운로드에 실패했습니다.", Toast.LENGTH_LONG);
                    return;
                }
                pendingInstallApk = apk;
                installApk(activity, apk);
            });
        });
    }

    private static File downloadApk(Context context, UpdateInfo info, ProgressCallback callback) {
        try {
            File dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if(dir == null)
                dir = context.getCacheDir();
            if(!dir.exists() && !dir.mkdirs())
                return null;
            deleteStaleUpdateApks(context, null);
            File apk = new File(dir, UPDATE_APK_PREFIX + info.version + UPDATE_APK_SUFFIX);
            DownloadPlan plan = fetchDownloadPlan(info.link);
            String downloadUrl = plan != null && plan.url != null && plan.url.length() > 0
                    ? plan.url
                    : info.link;
            if(plan != null && plan.supportsRange && plan.length >= APK_PARALLEL_MIN_BYTES) {
                File parallelApk = downloadApkParallel(downloadUrl, apk, plan.length, callback);
                if(parallelApk != null)
                    return parallelApk;
            }
            return downloadApkSingle(downloadUrl, apk, plan == null ? -1 : plan.length, callback);
        } catch (Exception e) {
            CrashReporter.record(e);
            return null;
        }
    }

    private static DownloadPlan fetchDownloadPlan(String url) {
        try {
            Request request = new Request.Builder()
                    .url(url)
                    .head()
                    .header("User-Agent", "MangaView")
                    .header("Accept", APK_MIME + ", application/octet-stream, */*")
                    .build();
            try(Response response = APK_CLIENT.newCall(request).execute()) {
                if(!response.isSuccessful())
                    return null;
                long length = parseContentLength(response);
                String ranges = response.header("Accept-Ranges", "");
                boolean supportsRange = ranges != null && ranges.toLowerCase(Locale.US).contains("bytes");
                return new DownloadPlan(response.request().url().toString(), length, supportsRange);
            }
        } catch (Exception e) {
            CrashReporter.record(e);
            return null;
        }
    }

    private static File downloadApkParallel(String url, File apk, long total, ProgressCallback callback) {
        ExecutorService executor = Executors.newFixedThreadPool(APK_PARALLEL_PARTS);
        List<Future<Boolean>> futures = new ArrayList<>();
        File temp = new File(apk.getParentFile(), apk.getName() + ".part");
        AtomicLong downloaded = new AtomicLong(0);
        try {
            if(temp.exists() && !temp.delete())
                return null;
            try(RandomAccessFile file = new RandomAccessFile(temp, "rw")) {
                file.setLength(total);
            }
            long partSize = (total + APK_PARALLEL_PARTS - 1) / APK_PARALLEL_PARTS;
            for(int i = 0; i < APK_PARALLEL_PARTS; i++) {
                long start = i * partSize;
                long end = Math.min(total - 1, start + partSize - 1);
                if(start > end)
                    continue;
                futures.add(executor.submit(segmentDownloadTask(url, temp, start, end, total, downloaded, callback)));
            }
            for(Future<Boolean> future : futures) {
                if(!future.get())
                    return null;
            }
            if(apk.exists() && !apk.delete())
                return null;
            if(!temp.renameTo(apk))
                return null;
            callback.onProgress(100);
            return apk.exists() && apk.length() == total ? apk : null;
        } catch (Exception e) {
            CrashReporter.record(e);
            return null;
        } finally {
            executor.shutdownNow();
            if(temp.exists() && (!apk.exists() || apk.length() != total)) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
        }
    }

    private static Callable<Boolean> segmentDownloadTask(String url, File temp, long start, long end, long total,
                                                         AtomicLong downloaded, ProgressCallback callback) {
        return () -> {
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "MangaView")
                    .header("Accept", APK_MIME + ", application/octet-stream, */*")
                    .header("Range", "bytes=" + start + "-" + end)
                    .build();
            try(Response response = APK_CLIENT.newCall(request).execute()) {
                if(response.code() != 206 || response.body() == null)
                    return false;
                try(InputStream input = response.body().byteStream();
                    RandomAccessFile output = new RandomAccessFile(temp, "rw")) {
                    output.seek(start);
                    byte[] buffer = new byte[256 * 1024];
                    int read;
                    int lastProgress = -1;
                    while((read = input.read(buffer)) != -1) {
                        output.write(buffer, 0, read);
                        long done = downloaded.addAndGet(read);
                        int progress = (int) Math.min(100, (done * 100) / total);
                        if(progress != lastProgress) {
                            lastProgress = progress;
                            callback.onProgress(progress);
                        }
                    }
                }
                return true;
            }
        };
    }

    private static File downloadApkSingle(String url, File apk, long expectedLength, ProgressCallback callback) {
        try {
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "MangaView")
                    .header("Accept", APK_MIME + ", application/octet-stream, */*")
                    .build();
            try(Response response = APK_CLIENT.newCall(request).execute()) {
                if(!response.isSuccessful() || response.body() == null)
                    return null;
                long total = expectedLength > 0 ? expectedLength : response.body().contentLength();
                try(InputStream input = response.body().byteStream();
                    FileOutputStream output = new FileOutputStream(apk)) {
                    byte[] buffer = new byte[256 * 1024];
                    long readTotal = 0;
                    int read;
                    int lastProgress = -1;
                    while((read = input.read(buffer)) != -1) {
                        output.write(buffer, 0, read);
                        readTotal += read;
                        if(total > 0) {
                            int progress = (int) Math.min(100, (readTotal * 100) / total);
                            if(progress != lastProgress) {
                                lastProgress = progress;
                                callback.onProgress(progress);
                            }
                        }
                    }
                    output.flush();
                }
                return apk.exists() && apk.length() > 0 ? apk : null;
            }
        } catch (Exception e) {
            CrashReporter.record(e);
            return null;
        }
    }

    private static long parseContentLength(Response response) {
        String value = response.header("Content-Length");
        if(value == null || value.length() == 0)
            return -1;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static void deleteStaleUpdateApks(Context context, File keep) {
        if(context == null)
            return;
        deleteStaleUpdateApksInDir(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), keep);
        deleteStaleUpdateApksInDir(context.getCacheDir(), keep);
    }

    private static void deleteStaleUpdateApksInDir(File dir, File keep) {
        if(dir == null || !dir.exists() || !dir.isDirectory())
            return;
        File[] files = dir.listFiles((parent, name) ->
                name != null && name.startsWith(UPDATE_APK_PREFIX) && name.endsWith(UPDATE_APK_SUFFIX));
        if(files == null)
            return;
        String keepPath = null;
        try {
            keepPath = keep == null ? null : keep.getCanonicalPath();
        } catch (Exception e) {
            CrashReporter.record(e);
        }
        for(File file : files) {
            try {
                if(keepPath != null && keepPath.equals(file.getCanonicalPath()))
                    continue;
                if(file.exists() && !file.delete())
                    CrashReporter.record(new IllegalStateException("Failed to delete update APK: " + file.getAbsolutePath()));
            } catch (Exception e) {
                CrashReporter.record(e);
            }
        }
    }

    private static void installApk(Activity activity, File apk) {
        if(!Utils.canUseContextForUi(activity) || apk == null || !apk.exists())
            return;
        if(!canRequestPackageInstalls(activity)) {
            Utils.safeToast(activity, "설치 허용 화면에서 권한을 켠 뒤 앱으로 돌아오면 설치를 계속합니다.", Toast.LENGTH_LONG);
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName()));
                Utils.safeStartActivity(activity, intent);
            }
            return;
        }
        try {
            Uri apkUri = FileProvider.getUriForFile(activity,
                    activity.getPackageName() + ".fileprovider",
                    apk);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(apkUri, APK_MIME);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if(Utils.safeStartActivity(activity, intent))
                pendingInstallApk = null;
        } catch (RuntimeException e) {
            CrashReporter.record(e);
            Utils.safeToast(activity, "설치 화면을 열지 못했습니다.", Toast.LENGTH_LONG);
        }
    }

    private static boolean canRequestPackageInstalls(Context context) {
        if(Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
            return true;
        return context.getPackageManager().canRequestPackageInstalls();
    }

    private static long currentVersionCode(Context context) {
        if(context == null)
            return -1;
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                return info.getLongVersionCode();
            return info.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        }
    }

    private interface ProgressCallback {
        void onProgress(int progress);
    }

    private static final class DownloadPlan {
        final String url;
        final long length;
        final boolean supportsRange;

        DownloadPlan(String url, long length, boolean supportsRange) {
            this.url = url;
            this.length = length;
            this.supportsRange = supportsRange;
        }
    }

    private static final class UpdateInfo {
        final int version;
        final String link;

        UpdateInfo(int version, String link) {
            this.version = version;
            this.link = link;
        }
    }
}
