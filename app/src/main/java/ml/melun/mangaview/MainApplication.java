package ml.melun.mangaview;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
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
import ml.melun.mangaview.activity.NtkQuicFetcher;
import ml.melun.mangaview.mangaview.CustomHttpClient;
import ml.melun.mangaview.report.CrashReporter;
import ml.melun.mangaview.repository.room.MangaRoomStore;
import ml.melun.mangaview.runtime.AppDispatchers;
import ml.melun.mangaview.runtime.MainThreadStallMonitor;
import ml.melun.mangaview.runtime.PerfTrace;



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
    private static volatile boolean workManagerInitialized = false;
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
    }

    @Override
    public void onCreate() {
        long appStartedAt = PerfTrace.start("app_on_create_ms");
        appContext = this;
        WebView.setWebContentsDebuggingEnabled(false);
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
        AppDispatchers.runIo(() -> ClassificationDbStore.cleanupLegacyFiles(appContext));
        long superStartedAt = PerfTrace.start("app_super_on_create_ms");
        super.onCreate();
        PerfTrace.end("app_super_on_create_ms", superStartedAt);
        PerfTrace.end("app_on_create_ms", appStartedAt);
        AppDispatchers.runIo(() -> preWarmNtkAck());
    }

    private static volatile java.util.concurrent.ScheduledExecutorService ntkTrustRefresher;

    private void preWarmNtkAck() {
        try {
            if(p == null || !p.isNtkSite())
                return;
            java.util.List<ml.melun.mangaview.mangaview.MTitle> recent = p.getRecent();
            if(recent == null || recent.isEmpty())
                return;
            ml.melun.mangaview.mangaview.MTitle item = recent.get(0);
            if(item == null)
                return;
            ml.melun.mangaview.mangaview.Title title = item instanceof ml.melun.mangaview.mangaview.Title
                    ? (ml.melun.mangaview.mangaview.Title) item
                    : new ml.melun.mangaview.mangaview.Title(item);
            ml.melun.mangaview.mangaview.Manga manga = ml.melun.mangaview.activity.ViewerResumeResolver.resumeManga(title);
            if(manga == null || !manga.isOnline())
                return;
            manga.setTitle(title);
            manga.setTitleId(title.getId());
            manga.ensureNtkEpisodePathFromIdentity();
            String ntkPath = manga.getNtkEpisodePath();
            if(ntkPath == null || ntkPath.length() == 0)
                return;
            CustomHttpClient client = getHttpClient();
            if(client == null || !client.isNtk())
                return;
            android.util.Log.d("MainApplication", "ntk_prewarm_ack_start path=" + ntkPath);
            client.preStartNtkAckForPath(ntkPath);
            ml.melun.mangaview.runtime.ContinueReadinessCoordinator.primeColdStart(appContext);
            preWarmAllEpisodeAcks(title, manga);
            startNtkTrustRefresher(ntkPath, title, manga);
        } catch(Exception e) {
            android.util.Log.d("MainApplication", "ntk_prewarm_ack_error " + e);
        }
    }

    private java.util.List<String> collectAllEpisodePaths(ml.melun.mangaview.mangaview.Title title,
                                                           ml.melun.mangaview.mangaview.Manga resumeManga) {
        java.util.List<String> paths = new java.util.ArrayList<>();
        try {
            java.util.List<ml.melun.mangaview.mangaview.Manga> episodes = ml.melun.mangaview.Utils.snapshotEpisodes(title);
            if(episodes == null || episodes.isEmpty())
                return paths;
            for(ml.melun.mangaview.mangaview.Manga ep : episodes) {
                if(ep == null)
                    continue;
                ep.setTitle(title);
                ep.setTitleId(title.getId());
                ep.ensureNtkEpisodePathFromIdentity();
                String epPath = ep.getNtkEpisodePath();
                if(epPath != null && epPath.length() > 0)
                    paths.add(epPath);
            }
        } catch(Exception e) {
            android.util.Log.d("MainApplication", "ntk_collect_episode_paths_error " + e);
        }
        return paths;
    }

    private void preWarmAllEpisodeAcks(ml.melun.mangaview.mangaview.Title title,
                                       ml.melun.mangaview.mangaview.Manga resumeManga) {
        java.util.List<String> allPaths = collectAllEpisodePaths(title, resumeManga);
        if(allPaths.isEmpty())
            return;
        android.util.Log.d("MainApplication", "ntk_prewarm_all_episodes count=" + allPaths.size());
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(5, r -> {
            Thread t = new Thread(r, "ntk-ack-prewarm-all");
            t.setDaemon(true);
            return t;
        });
        CustomHttpClient client = getHttpClient();
        if(client == null)
            return;
        for(String path : allPaths) {
            pool.submit(() -> {
                try {
                    Thread.sleep(50);
                    client.preStartNtkAckForPath(path);
                } catch(InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch(Exception e) {
                    android.util.Log.d("MainApplication", "ntk_prewarm_episode_error path=" + path + "," + e);
                }
            });
        }
        pool.shutdown();
    }

    private void startNtkTrustRefresher(String path, ml.melun.mangaview.mangaview.Title title,
                                        ml.melun.mangaview.mangaview.Manga resumeManga) {
        if(ntkTrustRefresher != null)
            return;
        ntkTrustRefresher = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ntk-trust-refresher");
            t.setDaemon(true);
            return t;
        });
        ntkTrustRefresher.scheduleAtFixedRate(() -> {
            try {
                CustomHttpClient client = getHttpClient();
                if(client == null || !client.isNtk())
                    return;
                android.util.Log.d("MainApplication", "ntk_trust_refresh path=" + path);
                client.preStartNtkAckForPath(path);
                preWarmAllEpisodeAcks(title, resumeManga);
            } catch(Exception e) {
                android.util.Log.d("MainApplication", "ntk_trust_refresh_error " + e);
            }
        }, 4, 4, java.util.concurrent.TimeUnit.MINUTES);
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
        if(Looper.myLooper() == Looper.getMainLooper()) {
            WebView.setWebContentsDebuggingEnabled(enabled);
        } else {
            new Handler(Looper.getMainLooper()).post(
                    () -> WebView.setWebContentsDebuggingEnabled(enabled));
        }
    }

    static boolean shouldEnableWebViewDebuggingForTest(boolean debuggable, boolean ntkSite) {
        return debuggable && !ntkSite;
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
