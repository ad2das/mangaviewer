package ml.melun.mangaview;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.multidex.MultiDexApplication;
import androidx.work.Configuration;
import androidx.work.WorkManager;

import ml.melun.mangaview.ClassificationDbUpdater;
import ml.melun.mangaview.ClassificationDbStore;
import ml.melun.mangaview.benchmark.BenchmarkAdjacentCommitSignal;
import ml.melun.mangaview.activity.NtkQuicFetcher;
import ml.melun.mangaview.mangaview.CustomHttpClient;
import ml.melun.mangaview.mangaview.NtkWebViewFallbackManager;
import ml.melun.mangaview.report.CrashReporter;
import ml.melun.mangaview.reader.ReaderImageCache;
import ml.melun.mangaview.reader.HostExactHardwareTilePool;
import ml.melun.mangaview.reader.NtkNativeSurfaceFrameRatePolicy;
import ml.melun.mangaview.repository.room.MangaRoomStore;
import ml.melun.mangaview.runtime.AppDispatchers;
import ml.melun.mangaview.runtime.MainThreadStallMonitor;
import ml.melun.mangaview.runtime.PerfTrace;
import ml.melun.mangaview.runtime.ViewerTelemetry;
import ml.melun.mangaview.runtime.ViewerColdStateSnapshot;
import ml.melun.mangaview.ntkack.NtkAckProcessRuntime;
import ml.melun.mangaview.ntkack.ProcessRole;



//@AcraCore(reportContent = { APP_VERSION_NAME, ANDROID_VERSION, PHONE_MODEL, STACK_TRACE, REPORT_ID})


public class MainApplication extends MultiDexApplication implements Configuration.Provider {
    public static volatile CustomHttpClient httpClient;
    public static Preference p;
    public static Context appContext;
    public static volatile Activity currentActivity;
    public static volatile FirebaseAccountManager firebaseAccountManager;
    public static volatile FirebaseSyncManager firebaseSyncManager;
    private static volatile boolean deferredServicesStarted = false;
    private static final Object httpClientLock = new Object();
    private static final Object firebaseAccountLock = new Object();
    private static final Object firebaseSyncLock = new Object();
    private static final Object deferredServicesLock = new Object();
    private static final Object workManagerLock = new Object();
    private static final Object ntkForegroundViewerLock = new Object();
    private static volatile boolean workManagerInitialized = false;
    private static final long NTK_FOREGROUND_VIEWER_ACTIVE_MS = 30000L;
    private static final String PREF_LAST_NTK_VIEWER_PATH = "lastNtkViewerPath";
    private static final String PREF_LAST_NTK_VIEWER_WORK_ID = "lastNtkViewerWorkId";
    private static final String PREF_LAST_NTK_VIEWER_EPISODE_ID = "lastNtkViewerEpisodeId";
    private static volatile String ntkForegroundViewerPath = "";
    private static volatile long ntkForegroundViewerStartedAtMs = 0L;
    private static volatile long ntkForegroundViewerInputAtMs = 0L;
    @Override
    protected void attachBaseContext(Context base) {
        String processName = ProcessRole.resolveProcessName(base);
        if(ProcessRole.isNtkAckProcess(processName)
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WebView.setDataDirectorySuffix("ntk_ack_v1");
        }
        super.attachBaseContext(base);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if(ProcessRole.isNtkAckProcess(this)) {
            appContext = this;
            NtkAckProcessRuntime.initialize(this);
            return;
        }
        ViewerColdStateSnapshot.captureAtProcessStart(this).record();
        long appStartedAt = PerfTrace.start("app_on_create_ms");
        appContext = this;
        BenchmarkAdjacentCommitSignal.initialize(this);
        MainThreadStallMonitor.install(Log.isLoggable("MainStall", Log.DEBUG));
        long crashReporterStartedAt = PerfTrace.start("app_crash_reporter_install_ms");
        CrashReporter.install(this);
        registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityResumed(Activity activity) {
                currentActivity = activity;
            }

            @Override
            public void onActivityPaused(Activity activity) {
                if(currentActivity == activity)
                    currentActivity = null;
            }

            @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}
            @Override public void onActivityStarted(Activity activity) {}
            @Override public void onActivityStopped(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
            @Override public void onActivityDestroyed(Activity activity) {
                if(currentActivity == activity)
                    currentActivity = null;
            }
        });
        PerfTrace.end("app_crash_reporter_install_ms", crashReporterStartedAt);
        long vectorStartedAt = PerfTrace.start("app_vector_delegate_ms");
        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true);
        PerfTrace.end("app_vector_delegate_ms", vectorStartedAt);
        long preferenceStartedAt = PerfTrace.start("app_preference_init_ms");
        p = new Preference(this);
        refreshWebViewDebuggingPolicy();
        PerfTrace.end("app_preference_init_ms", preferenceStartedAt);
        boolean hostGpuEmulatorRuntime = NtkNativeSurfaceFrameRatePolicy.INSTANCE
                .isEmulatorRuntime(Build.FINGERPRINT, Build.MODEL, Build.HARDWARE, Build.PRODUCT);
        if(HostExactHardwareTilePool.INSTANCE.supported(hostGpuEmulatorRuntime)
                && !NtkNativeSurfaceFrameRatePolicy.INSTANCE.isHwuiOverrideEnabled()) {
            AppDispatchers.runIo(HostExactHardwareTilePool::primeProcessTokenReserve);
        }
        AppDispatchers.runIo(() -> ClassificationDbStore.cleanupLegacyFiles(appContext));
        PerfTrace.end("app_on_create_ms", appStartedAt);
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        boolean aggressive = level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
                || level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE;
        if(aggressive) {
            ReaderImageCache.trimVolatileMemory(this, true);
        } else if(level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            ReaderImageCache.trimVolatileMemory(this, false);
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        ReaderImageCache.trimVolatileMemory(this, true);
    }

    public static void noteNtkForegroundViewerPath(String path) {
        if(path == null || path.length() == 0)
            return;
        if(!path.startsWith("/webtoon/") && !path.startsWith("/manhwa/"))
            return;
        long now = android.os.SystemClock.elapsedRealtime();
        boolean runPathSideEffects;
        synchronized(ntkForegroundViewerLock) {
            runPathSideEffects = shouldRunNtkForegroundPathSideEffects(
                    ntkForegroundViewerPath,
                    ntkForegroundViewerStartedAtMs,
                    path,
                    now);
            ntkForegroundViewerPath = path;
            ntkForegroundViewerStartedAtMs = now;
            ntkForegroundViewerInputAtMs = 0L;
            if(!runPathSideEffects) {
                Log.d("MainApplication", "ntk_foreground_viewer_path_refresh path=" + path);
                return;
            }
        }
        if(runPathSideEffects) {
            final String activePath = path;
            AppDispatchers.submitNtkViewerCritical(() -> {
                rememberLastNtkViewerPath(activePath, "", "", false);
                try {
                    ReaderImageCache.INSTANCE.cancelOtherNtkEpisodeVolatile(
                            activePath, "foreground_viewer_path");
                } catch(Throwable t) {
                    Log.d("MainApplication", "ntk_foreground_viewer_cancel_other_error path="
                            + activePath + "," + t);
                }
                try {
                    NtkWebViewFallbackManager.get(appContext)
                            .cancelViewerImageFetchesExceptPath(
                                    activePath, "foreground_viewer_path");
                } catch(Throwable t) {
                    Log.d("MainApplication",
                            "ntk_foreground_viewer_cancel_other_webview_error path="
                                    + activePath + "," + t);
                }
            });
        }
        Log.d("MainApplication", "ntk_foreground_viewer_path path=" + path);
    }

    static boolean shouldRunNtkForegroundPathSideEffects(String currentPath,
                                                          long currentStartedAtMs,
                                                          String nextPath,
                                                          long nowMs) {
        return currentPath == null
                || !nextPath.equals(currentPath)
                || nowMs - currentStartedAtMs >= NTK_FOREGROUND_VIEWER_ACTIVE_MS;
    }

    public static void clearNtkForegroundViewerPath(String path) {
        synchronized(ntkForegroundViewerLock) {
            if(!shouldClearNtkForegroundViewerPath(ntkForegroundViewerPath, path))
                return;
            ntkForegroundViewerPath = "";
            ntkForegroundViewerStartedAtMs = 0L;
            ntkForegroundViewerInputAtMs = 0L;
        }
        Log.d("MainApplication", "ntk_foreground_viewer_path_clear path=" + path);
    }

    static boolean shouldClearNtkForegroundViewerPath(String currentPath, String closingPath) {
        String current = currentPath == null ? "" : currentPath.trim();
        String closing = closingPath == null ? "" : closingPath.trim();
        return current.length() > 0 && current.equals(closing);
    }

    public static void noteNtkForegroundViewerInput(String path) {
        if(path == null || path.length() == 0)
            return;
        String foreground = ntkForegroundViewerPath;
        if(foreground == null || !path.equals(foreground))
            return;
        ntkForegroundViewerInputAtMs = android.os.SystemClock.elapsedRealtime();
    }

    public static void rememberLastNtkViewerPath(String path, String workId, String episodeId) {
        rememberLastNtkViewerPath(path, workId, episodeId, true);
    }

    public static void rememberLastNtkViewerPath(String path, String workId, String episodeId,
                                                 boolean replaceIdentity) {
        if(appContext == null || path == null || path.length() == 0)
            return;
        if(!path.startsWith("/webtoon/") && !path.startsWith("/manhwa/"))
            return;
        try {
            android.content.SharedPreferences.Editor editor =
                    appContext.getSharedPreferences("mangaView", Context.MODE_PRIVATE)
                            .edit()
                            .putString(PREF_LAST_NTK_VIEWER_PATH, path);
            if(replaceIdentity || (workId != null && workId.length() > 0))
                editor.putString(PREF_LAST_NTK_VIEWER_WORK_ID, workId == null ? "" : workId);
            if(replaceIdentity || (episodeId != null && episodeId.length() > 0))
                editor.putString(PREF_LAST_NTK_VIEWER_EPISODE_ID, episodeId == null ? "" : episodeId);
            editor.apply();
        } catch(Exception e) {
            Log.d("MainApplication", "ntk_last_viewer_path_store_error " + e);
        }
    }

    public static boolean isNtkForegroundViewerPathActive() {
        String foreground = ntkForegroundViewerPath;
        return isNtkForegroundViewerLeaseActive(
                foreground,
                ntkForegroundViewerStartedAtMs,
                android.os.SystemClock.elapsedRealtime(),
                ViewerTelemetry.isActiveEpisode(foreground));
    }

    public static String activeNtkForegroundViewerPath() {
        return isNtkForegroundViewerPathActive() ? ntkForegroundViewerPath : "";
    }

    public static boolean isNtkForegroundViewerPath(String path) {
        if(path == null || path.length() == 0)
            return false;
        String foreground = ntkForegroundViewerPath;
        return foreground != null
                && path.equals(foreground)
                && isNtkForegroundViewerLeaseActive(
                        foreground,
                        ntkForegroundViewerStartedAtMs,
                        android.os.SystemClock.elapsedRealtime(),
                        ViewerTelemetry.isActiveEpisode(path));
    }

    static boolean isNtkForegroundViewerLeaseActive(String foregroundPath,
                                                       long startedAtMs,
                                                       long nowMs,
                                                       boolean exactViewerEpisodeActive) {
        if(foregroundPath == null || foregroundPath.length() == 0)
            return false;
        if(exactViewerEpisodeActive)
            return true;
        return nowMs - startedAtMs < NTK_FOREGROUND_VIEWER_ACTIVE_MS;
    }

    public static long ntkForegroundViewerInputQuietRemainingMs(String path, long quietMs) {
        if(path == null || path.length() == 0 || quietMs <= 0L)
            return 0L;
        String foreground = ntkForegroundViewerPath;
        if(foreground == null || !path.equals(foreground))
            return 0L;
        long inputAt = ntkForegroundViewerInputAtMs;
        if(inputAt <= 0L)
            return 0L;
        long elapsed = android.os.SystemClock.elapsedRealtime() - inputAt;
        return elapsed >= quietMs ? 0L : quietMs - Math.max(0L, elapsed);
    }

    public static CustomHttpClient getHttpClient() {
        CustomHttpClient local = httpClient;
        if(local == null) {
            synchronized (httpClientLock) {
                local = httpClient;
                if(local == null) {
                    local = new CustomHttpClient(appContext);
                    httpClient = local;
                }
            }
        }
        return local;
    }

    public static FirebaseAccountManager getFirebaseAccountManager() {
        FirebaseAccountManager local = firebaseAccountManager;
        if(local == null) {
            synchronized (firebaseAccountLock) {
                local = firebaseAccountManager;
                if(local == null) {
                    local = new FirebaseAccountManager(appContext);
                    firebaseAccountManager = local;
                }
            }
        }
        return local;
    }

    public static FirebaseSyncManager getFirebaseSyncManager() {
        FirebaseSyncManager local = firebaseSyncManager;
        if(local == null) {
            synchronized (firebaseSyncLock) {
                local = firebaseSyncManager;
                if(local == null) {
                    local = new FirebaseSyncManager(appContext, p);
                    firebaseSyncManager = local;
                }
            }
        }
        return local;
    }

    public static WorkManager getWorkManager(Context context) {
        Context app = appContext != null ? appContext : context.getApplicationContext();
        if(!workManagerInitialized) {
            synchronized (workManagerLock) {
                if(!workManagerInitialized) {
                    try {
                        WorkManager.initialize(app, new Configuration.Builder().build());
                    } catch (IllegalStateException ignored) {
                    }
                    workManagerInitialized = true;
                }
            }
        }
        return WorkManager.getInstance(app);
    }

    public static void refreshWebViewDebuggingPolicy() {
        refreshWebViewDebuggingPolicy(p);
    }

    public static void refreshWebViewDebuggingPolicy(Preference preference) {
        Context context = appContext;
        if(context == null)
            return;
        boolean debuggable = (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        boolean enabled = shouldEnableWebViewDebuggingForTest(debuggable,
                preference != null && preference.isNtkSite());
        if(!enabled)
            return;
        if(Looper.myLooper() == Looper.getMainLooper()) {
            WebView.setWebContentsDebuggingEnabled(enabled);
        } else {
            new Handler(Looper.getMainLooper()).post(
                    () -> WebView.setWebContentsDebuggingEnabled(enabled));
        }
    }

    static boolean shouldEnableWebViewDebuggingForTest(boolean debuggable, boolean ntkSite) {
        return false;
    }

    @NonNull
    @Override
    public Configuration getWorkManagerConfiguration() {
        return new Configuration.Builder().build();
    }

    public static void initDeferredServices() {
        if(deferredServicesStarted)
            return;
        synchronized (deferredServicesLock) {
            if(!deferredServicesStarted) {
                deferredServicesStarted = true;
                AppDispatchers.runIoDelayed(() -> MangaRoomStore.prime(appContext), 1800);
                AppDispatchers.runIoDelayed(() -> ClassificationDbUpdater.start(appContext), 7000);
            }
        }
    }
}
