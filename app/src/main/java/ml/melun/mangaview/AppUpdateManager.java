package ml.melun.mangaview;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

import ml.melun.mangaview.report.CrashReporter;
import ml.melun.mangaview.runtime.AppDispatchers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class AppUpdateManager {
    private static final String VERSION_URL = "https://raw.githubusercontent.com/ad2das/mangaviewer/main/version.json";
    private static final String APK_MIME = "application/vnd.android.package-archive";
    private static boolean checkedThisSession;
    private static boolean downloading;
    private static File pendingInstallApk;

    private AppUpdateManager() {
    }

    public static void checkForUpdate(Activity activity) {
        if(activity == null || checkedThisSession || downloading)
            return;
        checkedThisSession = true;
        Context appContext = activity.getApplicationContext();
        AppDispatchers.runIo(() -> {
            UpdateInfo info = fetchUpdateInfo(appContext);
            if(info == null || info.version <= BuildConfig.VERSION_CODE || info.link == null || info.link.length() == 0)
                return;
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
        try {
            OkHttpClient client = MainApplication.getHttpClient().client;
            Request request = new Request.Builder()
                    .url(VERSION_URL)
                    .header("Cache-Control", "no-cache")
                    .build();
            try(Response response = client.newCall(request).execute()) {
                if(!response.isSuccessful())
                    return null;
                ResponseBody body = response.body();
                if(body == null)
                    return null;
                JSONObject json = new JSONObject(body.string());
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

    private static void showUpdateDialog(Activity activity, UpdateInfo info) {
        if(!Utils.canUseContextForUi(activity) || info == null)
            return;
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle("새 버전 업데이트")
                .setMessage("새 APK가 있습니다.\n현재: " + BuildConfig.VERSION_CODE + "\n최신: " + info.version + "\n\n다운로드 후 설치 화면을 바로 열까요?")
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
            OkHttpClient client = MainApplication.getHttpClient().client;
            Request request = new Request.Builder().url(info.link).build();
            try(Response response = client.newCall(request).execute()) {
                if(!response.isSuccessful() || response.body() == null)
                    return null;
                File dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if(dir == null)
                    dir = context.getCacheDir();
                if(!dir.exists() && !dir.mkdirs())
                    return null;
                File apk = new File(dir, "mangaViewer-update-" + info.version + ".apk");
                long total = response.body().contentLength();
                try(InputStream input = response.body().byteStream();
                    FileOutputStream output = new FileOutputStream(apk)) {
                    byte[] buffer = new byte[64 * 1024];
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
                    BuildConfig.APPLICATION_ID + ".fileprovider",
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

    private interface ProgressCallback {
        void onProgress(int progress);
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
