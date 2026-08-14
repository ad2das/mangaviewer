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
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ml.melun.mangaview.report.CrashReporter;
import ml.melun.mangaview.runtime.AppDispatchers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class AppUpdateManager {
    private static final String TAG = "AppUpdate";
    private static final String UPDATE_CHANNEL = "main";
    private static final String VERSION_API_URL = "https://api.github.com/repos/ad2das/mangaviewer/contents/version.json?ref=" + UPDATE_CHANNEL;
    private static final String RAW_VERSION_URL = "https://raw.githubusercontent.com/ad2das/mangaviewer/" + UPDATE_CHANNEL + "/version.json";
    private static final String CDN_VERSION_URL = "https://cdn.jsdelivr.net/gh/ad2das/mangaviewer@" + UPDATE_CHANNEL + "/version.json";
    private static final String LATEST_RELEASE_API_URL = "https://api.github.com/repos/ad2das/mangaviewer/releases/tags/main-latest";
    private static final String RELEASE_VERSION_URL = "https://github.com/ad2das/mangaviewer/releases/download/main-latest/version.json";
    private static final String UPDATE_USER_AGENT = "MangaView Update";
    private static final String PREF = "appUpdate";
    private static final String KEY_VERSION = UPDATE_CHANNEL + "_latestVersion";
    private static final String KEY_LINK = UPDATE_CHANNEL + "_latestLink";
    private static final String KEY_SKIPPED_VERSION = UPDATE_CHANNEL + "_skippedVersion";
    private static final String KEY_PLAN_LINK = UPDATE_CHANNEL + "_downloadPlanLink";
    private static final String KEY_PLAN_URL = UPDATE_CHANNEL + "_downloadPlanUrl";
    private static final String KEY_PLAN_LENGTH = UPDATE_CHANNEL + "_downloadPlanLength";
    private static final String KEY_PLAN_RANGE = UPDATE_CHANNEL + "_downloadPlanRange";
    private static final String KEY_PLAN_FETCHED_AT = UPDATE_CHANNEL + "_downloadPlanFetchedAt";
    private static final String APK_MIME = "application/vnd.android.package-archive";
    private static final String UPDATE_APK_PREFIX = "mangaViewer-update-";
    private static final String UPDATE_APK_SUFFIX = ".apk";
    private static final int APK_PARALLEL_PARTS = 8;
    private static final long APK_PARALLEL_MIN_BYTES = 8L * 1024L * 1024L;
    private static final long DOWNLOAD_PLAN_TTL_MS = 10 * 60_000L;
    private static final Pattern APK_VERSION_PATTERN = Pattern.compile("mangaViewer_(\\d+).+\\.apk");
    private static final OkHttpClient VERSION_CLIENT = new OkHttpClient.Builder()
            .dns(AppUpdateManager::lookupUpdateDns)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build();
    private static final OkHttpClient APK_CLIENT = new OkHttpClient.Builder()
            .dns(AppUpdateManager::lookupUpdateDns)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .build();
    private static final ExecutorService UPDATE_DOWNLOAD_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "manga-update-download");
        thread.setDaemon(true);
        return thread;
    });
    private static boolean checkStartedThisSession;
    private static boolean dialogShownThisSession;
    private static boolean downloading;
    private static File pendingInstallApk;

    private AppUpdateManager() {
    }

    private static List<InetAddress> lookupUpdateDns(String hostname) throws UnknownHostException {
        try {
            return okhttp3.Dns.SYSTEM.lookup(hostname);
        } catch (UnknownHostException e) {
            List<InetAddress> fallback = fallbackUpdateAddresses(hostname);
            if(!fallback.isEmpty()) {
                log("dnsFallback host=" + hostname + " count=" + fallback.size());
                return fallback;
            }
            throw e;
        }
    }

    private static List<InetAddress> fallbackUpdateAddresses(String hostname) throws UnknownHostException {
        if("api.github.com".equalsIgnoreCase(hostname))
            return Arrays.asList(address(hostname, 20, 200, 245, 245));
        if("github.com".equalsIgnoreCase(hostname))
            return Arrays.asList(address(hostname, 20, 200, 245, 247));
        if("raw.githubusercontent.com".equalsIgnoreCase(hostname)
                || "release-assets.githubusercontent.com".equalsIgnoreCase(hostname)
                || "objects.githubusercontent.com".equalsIgnoreCase(hostname))
            return Arrays.asList(
                    address(hostname, 185, 199, 108, 133),
                    address(hostname, 185, 199, 109, 133),
                    address(hostname, 185, 199, 110, 133),
                    address(hostname, 185, 199, 111, 133));
        if("cdn.jsdelivr.net".equalsIgnoreCase(hostname))
            return Arrays.asList(
                    address(hostname, 151, 101, 1, 229),
                    address(hostname, 151, 101, 65, 229),
                    address(hostname, 151, 101, 129, 229),
                    address(hostname, 151, 101, 193, 229));
        return new ArrayList<>();
    }

    private static InetAddress address(String hostname, int a, int b, int c, int d) throws UnknownHostException {
        return InetAddress.getByAddress(hostname, new byte[] {(byte)a, (byte)b, (byte)c, (byte)d});
    }

    public static void checkForUpdate(Activity activity) {
        if(activity == null || downloading)
            return;
        Context appContext = activity.getApplicationContext();
        AppDispatchers.runIo(() -> deleteStaleUpdateApks(appContext, pendingInstallApk));
        UpdateInfo cachedInfo = readCachedUpdateInfo(appContext);
        if(isUpdateAvailable(appContext, cachedInfo, true)) {
            warmDownloadPlan(appContext, cachedInfo);
            maybeShowAutomaticUpdateDialog(activity, cachedInfo);
        }
        if(checkStartedThisSession)
            return;
        checkStartedThisSession = true;
        AppDispatchers.runIo(() -> {
            UpdateInfo info = fetchUpdateInfo(appContext);
            if(info == null)
                return;
            cacheUpdateInfo(appContext, info);
            if(isUpdateAvailable(appContext, info, true))
                warmDownloadPlan(appContext, info);
            if(!isUpdateAvailable(appContext, info, true))
                return;
            AppDispatchers.runOnMain(() -> maybeShowAutomaticUpdateDialog(activity, info));
        });
    }

    private static void maybeShowAutomaticUpdateDialog(Activity activity, UpdateInfo info) {
        if(!automaticUpdatePromptsEnabled() || dialogShownThisSession)
            return;
        dialogShownThisSession = true;
        showUpdateDialog(activity, info);
    }

    private static boolean automaticUpdatePromptsEnabled() {
        return false;
    }

    static boolean automaticUpdatePromptsEnabledForTest() {
        return automaticUpdatePromptsEnabled();
    }

    public static void checkForUpdateNow(Activity activity) {
        if(activity == null)
            return;
        if(downloading) {
            Utils.safeToast(activity, "업데이트를 이미 다운로드 중입니다.", Toast.LENGTH_SHORT);
            return;
        }
        Context appContext = activity.getApplicationContext();
        Utils.safeToast(activity, "업데이트를 확인하는 중입니다.", Toast.LENGTH_SHORT);
        AppDispatchers.runIo(() -> {
            UpdateInfo info = fetchUpdateInfo(appContext);
            if(info != null)
                cacheUpdateInfo(appContext, info);
            if(info == null) {
                UpdateInfo cachedInfo = readCachedUpdateInfo(appContext);
                if(cachedInfo != null) {
                    log("manualCheck source=cache version=" + cachedInfo.version);
                    info = cachedInfo;
                }
            }
            if(info != null && isUpdateAvailable(appContext, info, false)) {
                UpdateInfo updateInfo = info;
                warmDownloadPlan(appContext, info);
                AppDispatchers.runOnMain(() -> showUpdateDialog(activity, updateInfo));
                return;
            }
            UpdateInfo resultInfo = info;
            AppDispatchers.runOnMain(() -> {
                if(resultInfo == null)
                    showManualUpdateResult(activity, "업데이트 확인 실패", "최신 버전 정보를 가져오지 못했습니다.\n네트워크 상태를 확인한 뒤 다시 시도해 주세요.");
                else
                    showManualUpdateResult(activity, "최신 버전입니다", "현재 설치된 버전: " + currentVersionCode(activity) + "\n최신 버전: " + resultInfo.version);
            });
        });
    }

    public static void resumePendingInstall(Activity activity) {
        if(activity == null || pendingInstallApk == null || !pendingInstallApk.exists())
            return;
        if(canRequestPackageInstalls(activity))
            installApk(activity, pendingInstallApk);
    }

    private static UpdateInfo fetchUpdateInfo(Context context) {
        long now = System.currentTimeMillis();
        UpdateInfo releaseInfo = fetchUpdateInfoFromLatestRelease();
        if(releaseInfo != null)
            return releaseInfo;
        UpdateInfo releaseAssetInfo = fetchUpdateInfoFromUrl("github-main-latest-version-asset", RELEASE_VERSION_URL + "?t=" + now, false);
        if(releaseAssetInfo != null)
            return releaseAssetInfo;
        UpdateInfo apiInfo = fetchUpdateInfoFromUrl("github-api", VERSION_API_URL, true);
        if(apiInfo != null)
            return apiInfo;
        UpdateInfo rawInfo = fetchUpdateInfoFromUrl("github-raw", RAW_VERSION_URL + "?t=" + now, false);
        if(rawInfo != null)
            return rawInfo;
        UpdateInfo cdnInfo = fetchUpdateInfoFromUrl("jsdelivr", CDN_VERSION_URL + "?t=" + now, false);
        if(cdnInfo != null)
            return cdnInfo;
        return null;
    }

    private static UpdateInfo fetchUpdateInfoFromUrl(String source, String url, boolean allowGithubContentsEnvelope) {
        long started = System.currentTimeMillis();
        try {
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", UPDATE_USER_AGENT)
                    .header("Accept", allowGithubContentsEnvelope
                            ? "application/vnd.github.raw, application/vnd.github+json, application/json, text/plain, */*"
                            : "application/json, text/plain, */*")
                    .header("Cache-Control", "no-cache")
                    .header("Pragma", "no-cache")
                    .build();
            try(Response response = VERSION_CLIENT.newCall(request).execute()) {
                if(!response.isSuccessful()) {
                    log("versionFetchFailed source=" + source
                            + " code=" + response.code()
                            + " ms=" + (System.currentTimeMillis() - started));
                    return null;
                }
                ResponseBody body = response.body();
                if(body == null)
                    return null;
                JSONObject json = parseVersionJson(body.string(), allowGithubContentsEnvelope);
                int version = json.optInt("version", -1);
                String link = json.optString("link", "");
                if(version <= 0 || link.length() == 0)
                    return null;
                log("versionFetchOk source=" + source
                        + " version=" + version
                        + " ms=" + (System.currentTimeMillis() - started));
                return new UpdateInfo(version, link);
            }
        } catch (Exception e) {
            if(shouldReportVersionFetchFailure(e))
                CrashReporter.record(e);
            log("versionFetchException source=" + source
                    + " " + e.getClass().getSimpleName() + ": " + e.getMessage()
                    + " ms=" + (System.currentTimeMillis() - started));
            return null;
        }
    }

    private static UpdateInfo fetchUpdateInfoFromLatestRelease() {
        long started = System.currentTimeMillis();
        try {
            Request request = new Request.Builder()
                    .url(LATEST_RELEASE_API_URL)
                    .header("User-Agent", UPDATE_USER_AGENT)
                    .header("Accept", "application/vnd.github+json, application/json, */*")
                    .header("Cache-Control", "no-cache")
                    .header("Pragma", "no-cache")
                    .build();
            try(Response response = VERSION_CLIENT.newCall(request).execute()) {
                if(!response.isSuccessful() || response.body() == null) {
                    log("versionFetchFailed source=github-main-latest-release code=" + response.code()
                            + " ms=" + (System.currentTimeMillis() - started));
                    return null;
                }
                JSONObject json = new JSONObject(response.body().string());
                UpdateInfo info = parseLatestReleaseInfo(json);
                if(info != null)
                    log("versionFetchOk source=github-main-latest-release version=" + info.version
                            + " ms=" + (System.currentTimeMillis() - started));
                return info;
            }
        } catch (Exception e) {
            if(shouldReportVersionFetchFailure(e))
                CrashReporter.record(e);
            log("versionFetchException source=github-main-latest-release "
                    + e.getClass().getSimpleName() + ": " + e.getMessage()
                    + " ms=" + (System.currentTimeMillis() - started));
            return null;
        }
    }

    static boolean shouldReportVersionFetchFailure(Throwable failure) {
        return failure != null && !(failure instanceof java.io.IOException);
    }

    private static UpdateInfo parseLatestReleaseInfo(JSONObject releaseJson) {
        if(releaseJson == null)
            return null;
        org.json.JSONArray assets = releaseJson.optJSONArray("assets");
        if(assets == null)
            return null;
        UpdateInfo best = null;
        for(int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if(asset == null)
                continue;
            String name = asset.optString("name", "");
            String link = asset.optString("browser_download_url", "");
            Matcher matcher = APK_VERSION_PATTERN.matcher(name);
            if(!matcher.matches() || link.length() == 0)
                continue;
            try {
                int version = Integer.parseInt(matcher.group(1));
                if(best == null || version > best.version)
                    best = new UpdateInfo(version, link);
            } catch (NumberFormatException e) {
                CrashReporter.record(e);
            }
        }
        return best;
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

    private static boolean isUpdateAvailable(Context context, UpdateInfo info, boolean respectSkippedVersion) {
        return info != null && info.version > currentVersionCode(context == null ? MainApplication.appContext : context)
                && info.link != null && info.link.length() > 0
                && (!respectSkippedVersion || info.version != skippedVersion(context));
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

    private static int skippedVersion(Context context) {
        if(context == null)
            return -1;
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getInt(KEY_SKIPPED_VERSION, -1);
    }

    private static void skipVersion(Context context, UpdateInfo info) {
        if(context == null || info == null)
            return;
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_SKIPPED_VERSION, info.version)
                .apply();
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
                .setNegativeButton("나중에", null)
                .setNeutralButton("이 버전 건너뛰기", (dialog, which) -> {
                    skipVersion(activity.getApplicationContext(), info);
                    Utils.safeToast(activity, "이 버전은 건너뜁니다.", Toast.LENGTH_SHORT);
                });
        Utils.safeShowDialog(builder);
    }

    private static void showManualUpdateResult(Activity activity, String title, String message) {
        if(!Utils.canUseContextForUi(activity)) {
            Utils.safeToast(activity, message == null ? title : message, Toast.LENGTH_LONG);
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null);
        Utils.safeShowDialog(builder);
    }

    private static void downloadAndInstall(Activity activity, UpdateInfo info) {
        if(activity == null || info == null || downloading)
            return;
        long clickedAt = System.currentTimeMillis();
        log("downloadClick version=" + info.version);
        downloading = true;
        ProgressDialog progressDialog = new ProgressDialog(activity);
        progressDialog.setTitle("업데이트 다운로드");
        progressDialog.setMessage("APK를 받는 중입니다...");
        progressDialog.setIndeterminate(false);
        progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progressDialog.setMax(100);
        progressDialog.setProgress(1);
        AtomicBoolean progressUiCancelled = new AtomicBoolean(false);
        // Network and package-manager stalls must never trap the user behind a modal window.
        // Dismissing the UI does not corrupt the staged download; the bounded worker may finish
        // in the background and the pending install is offered again on the next resume.
        progressDialog.setCancelable(true);
        progressDialog.setOnCancelListener(dialog -> {
            progressUiCancelled.set(true);
            Utils.safeToast(activity,
                    "업데이트 다운로드는 백그라운드에서 계속되며, 다음 실행 때 설치할 수 있습니다.",
                    Toast.LENGTH_SHORT);
        });
        try {
            progressDialog.show();
            progressDialog.setProgress(1);
        } catch (RuntimeException e) {
            CrashReporter.record(e);
        }

        Context appContext = activity.getApplicationContext();
        final boolean[] firstVisibleProgressLogged = {false};
        UPDATE_DOWNLOAD_EXECUTOR.execute(() -> {
            try {
                log("downloadWorkerStart delayMs=" + (System.currentTimeMillis() - clickedAt));
                UpdateInfo downloadInfo = latestInfoForDownload(appContext, info);
                File apk = downloadApk(appContext, downloadInfo, progress -> AppDispatchers.runOnMain(() -> {
                    try {
                        if(progressDialog.isShowing()) {
                            progressDialog.setProgress(progress);
                            if(!firstVisibleProgressLogged[0] && progress > 0) {
                                firstVisibleProgressLogged[0] = true;
                                log("progressVisible first=" + progress);
                            }
                        }
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
                    if(!progressUiCancelled.get() && Utils.canUseContextForUi(activity)) {
                        installApk(activity, apk);
                    } else {
                        Utils.safeToast(appContext,
                                "업데이트 다운로드가 완료되었습니다. 앱으로 돌아오면 설치를 계속합니다.",
                                Toast.LENGTH_LONG);
                    }
                });
            } catch (Throwable throwable) {
                CrashReporter.record(throwable);
                AppDispatchers.runOnMain(() -> {
                    downloading = false;
                    try {
                        if(progressDialog.isShowing())
                            progressDialog.dismiss();
                    } catch (RuntimeException e) {
                        CrashReporter.record(e);
                    }
                    Utils.safeToast(activity, "업데이트 다운로드에 실패했습니다.", Toast.LENGTH_LONG);
                });
            }
        });
    }

    private static UpdateInfo latestInfoForDownload(Context context, UpdateInfo requested) {
        long started = System.currentTimeMillis();
        UpdateInfo cached = readCachedUpdateInfo(context);
        if(cached != null && isUpdateAvailable(context, cached, false)
                && (requested == null
                || cached.version >= requested.version
                || !cached.link.equals(requested.link))) {
            log("refreshBeforeDownload source=cache ms=" + (System.currentTimeMillis() - started)
                    + " requested=" + (requested == null ? -1 : requested.version)
                    + " latest=" + cached.version);
            return cached;
        }
        if(requested != null && isUpdateAvailable(context, requested, false)) {
            log("refreshBeforeDownload source=requested ms=" + (System.currentTimeMillis() - started)
                    + " requested=" + requested.version
                    + " latest=" + (cached == null ? -1 : cached.version));
            return requested;
        }
        UpdateInfo latest = fetchUpdateInfo(context);
        if(latest != null && isUpdateAvailable(context, latest, false)) {
            cacheUpdateInfo(context, latest);
            log("refreshBeforeDownload source=network ms=" + (System.currentTimeMillis() - started)
                    + " requested=" + (requested == null ? -1 : requested.version)
                    + " latest=" + latest.version);
            return latest;
        }
        log("refreshBeforeDownload source=none ms=" + (System.currentTimeMillis() - started)
                + " requested=" + (requested == null ? -1 : requested.version)
                + " latest=" + (latest == null ? -1 : latest.version));
        return requested;
    }

    private static File downloadApk(Context context, UpdateInfo info, ProgressCallback callback) {
        try {
            if(info == null || info.link == null || info.link.length() == 0)
                return null;
            long started = System.currentTimeMillis();
            log("downloadStart version=" + info.version + " url=" + info.link);
            callback.onProgress(1);
            File dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if(dir == null)
                dir = context.getCacheDir();
            if(!dir.exists() && !dir.mkdirs())
                return null;
            deleteStaleUpdateApks(context, null);
            File apk = new File(dir, UPDATE_APK_PREFIX + info.version + UPDATE_APK_SUFFIX);
            DownloadPlan plan = readCachedDownloadPlan(context, info.link);
            if(plan != null)
                log("planCacheHit bytes=" + plan.length + " range=" + plan.supportsRange);
            else
                log("planCacheMiss startDirect");
            String downloadUrl = plan != null && plan.url != null && plan.url.length() > 0
                    ? plan.url
                    : info.link;
            if(plan != null && plan.supportsRange && plan.length >= APK_PARALLEL_MIN_BYTES) {
                File parallelApk = downloadApkParallel(downloadUrl, apk, plan.length, callback);
                if(parallelApk != null) {
                    log("downloadDone mode=parallel ms=" + (System.currentTimeMillis() - started)
                            + " bytes=" + parallelApk.length());
                    return parallelApk;
                }
                log("parallelFailed retrySingle version=" + info.version);
            }
            File singleApk = downloadApkSingle(info.link, apk, plan == null ? -1 : plan.length, callback);
            if(singleApk != null)
                log("downloadDone mode=single ms=" + (System.currentTimeMillis() - started)
                        + " bytes=" + singleApk.length());
            else
                log("downloadFailed ms=" + (System.currentTimeMillis() - started));
            return singleApk;
        } catch (Exception e) {
            CrashReporter.record(e);
            log("downloadException " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }

    private static void warmDownloadPlan(Context context, UpdateInfo info) {
        if(context == null || info == null || info.link == null || info.link.length() == 0)
            return;
        if(readCachedDownloadPlan(context, info.link) != null)
            return;
        AppDispatchers.runIo(() -> fetchAndCacheDownloadPlan(context, info.link));
    }

    private static DownloadPlan fetchAndCacheDownloadPlan(Context context, String link) {
        DownloadPlan plan = fetchDownloadPlan(link);
        if(plan != null)
            cacheDownloadPlan(context, link, plan);
        return plan;
    }

    private static DownloadPlan readCachedDownloadPlan(Context context, String link) {
        if(context == null || link == null || link.length() == 0)
            return null;
        try {
            SharedPreferences pref = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            if(!link.equals(pref.getString(KEY_PLAN_LINK, "")))
                return null;
            long fetchedAt = pref.getLong(KEY_PLAN_FETCHED_AT, 0L);
            if(System.currentTimeMillis() - fetchedAt > DOWNLOAD_PLAN_TTL_MS)
                return null;
            String url = pref.getString(KEY_PLAN_URL, "");
            long length = pref.getLong(KEY_PLAN_LENGTH, -1L);
            boolean supportsRange = pref.getBoolean(KEY_PLAN_RANGE, false);
            if(url == null || url.length() == 0 || length <= 0)
                return null;
            return new DownloadPlan(url, length, supportsRange);
        } catch (RuntimeException e) {
            CrashReporter.record(e);
            return null;
        }
    }

    private static void cacheDownloadPlan(Context context, String link, DownloadPlan plan) {
        if(context == null || link == null || plan == null || plan.url == null || plan.url.length() == 0)
            return;
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PLAN_LINK, link)
                .putString(KEY_PLAN_URL, plan.url)
                .putLong(KEY_PLAN_LENGTH, plan.length)
                .putBoolean(KEY_PLAN_RANGE, plan.supportsRange)
                .putLong(KEY_PLAN_FETCHED_AT, System.currentTimeMillis())
                .apply();
    }

    private static DownloadPlan fetchDownloadPlan(String url) {
        try {
            long started = System.currentTimeMillis();
            Request request = new Request.Builder()
                    .url(url)
                    .head()
                    .header("User-Agent", "MangaView")
                    .header("Accept", APK_MIME + ", application/octet-stream, */*")
                    .build();
            try(Response response = APK_CLIENT.newCall(request).execute()) {
                if(!response.isSuccessful()) {
                    log("planFailed code=" + response.code() + " ms=" + (System.currentTimeMillis() - started));
                    return null;
                }
                long length = parseContentLength(response);
                String ranges = response.header("Accept-Ranges", "");
                boolean supportsRange = ranges != null && ranges.toLowerCase(Locale.US).contains("bytes");
                log("plan code=" + response.code()
                        + " ms=" + (System.currentTimeMillis() - started)
                        + " bytes=" + length
                        + " range=" + supportsRange
                        + " finalHost=" + response.request().url().host());
                return new DownloadPlan(response.request().url().toString(), length, supportsRange);
            }
        } catch (Exception e) {
            CrashReporter.record(e);
            log("planException " + e.getClass().getSimpleName() + ": " + e.getMessage());
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
                if(response.code() != 206 || response.body() == null) {
                    log("segmentFailed start=" + start + " end=" + end + " code=" + response.code());
                    return false;
                }
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
                if(!response.isSuccessful() || response.body() == null) {
                    log("singleFailed code=" + response.code());
                    return null;
                }
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
                return apk.exists() && isCompleteDownload(apk.length(), total) ? apk : null;
            }
        } catch (Exception e) {
            CrashReporter.record(e);
            return null;
        }
    }

    static boolean isCompleteDownloadForTest(long actualLength, long expectedLength) {
        return isCompleteDownload(actualLength, expectedLength);
    }

    private static boolean isCompleteDownload(long actualLength, long expectedLength) {
        if(expectedLength > 0)
            return actualLength == expectedLength;
        return actualLength > 0;
    }

    private static void log(String message) {
        Log.d(TAG, message);
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
        File[] files = dir.listFiles((parent, name) -> isStaleUpdateArtifact(name));
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

    static boolean isStaleUpdateArtifactForTest(String name) {
        return isStaleUpdateArtifact(name);
    }

    private static boolean isStaleUpdateArtifact(String name) {
        return name != null
                && name.startsWith(UPDATE_APK_PREFIX)
                && (name.endsWith(UPDATE_APK_SUFFIX) || name.endsWith(UPDATE_APK_SUFFIX + ".part"));
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
