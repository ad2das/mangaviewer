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
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.multidex.MultiDexApplication;
import androidx.work.Configuration;
import androidx.work.WorkManager;

import ml.melun.mangaview.ClassificationDbUpdater;
import ml.melun.mangaview.ClassificationDbStore;
import ml.melun.mangaview.activity.NtkQuicFetcher;
import ml.melun.mangaview.activity.NtkBrowserSessionBroker;
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
    private static volatile String ntkBrowserWarmPath = "";
    private static volatile long ntkBrowserWarmStartedAtMs = 0L;
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
                maybeWarmNtkBrowserSession(activity);
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
            String baseUrl = client.getUrl(ntkPath);
            client.syncCookiesFromWebView(baseUrl, true);
            client.syncCookiesFromWebView(baseUrl + ntkPath, true);
            Activity activity = currentActivity;
            if(!client.hasCloudflareClearanceForUrl(baseUrl)) {
                android.util.Log.d("MainApplication", "ntk_prewarm_ack_no_clearance_start_browser path=" + ntkPath);
                if(activity != null)
                new Handler(Looper.getMainLooper()).post(
                        () -> maybeWarmNtkBrowserSession(activity, manga, baseUrl, ntkPath, client, true));
            }
            android.util.Log.d("MainApplication", "ntk_prewarm_ack_start path=" + ntkPath);
            boolean ackOk = client.preWarmStrictNtkAckForPath(ntkPath, 3500L);
            boolean keyOk = client.warmNtkViewerRequestKey(baseUrl, ntkPath);
            String imagePrimeKey = Utils.startNtkGeneratedInitialRunwayPrewarm(appContext, manga);
            if(activity != null)
                maybeWarmNtkBrowserSession(activity, manga, baseUrl, ntkPath, client, true);
            android.util.Log.d("MainApplication", "ntk_prewarm_ack_done path=" + ntkPath
                    + ",ack=" + ackOk
                    + ",key=" + keyOk
                    + ",imagePrime=" + (imagePrimeKey != null));
            ml.melun.mangaview.runtime.ContinueReadinessCoordinator.primeColdStart(appContext);
            startNtkTrustRefresher(ntkPath);
        } catch(Exception e) {
            android.util.Log.d("MainApplication", "ntk_prewarm_ack_error " + e);
        }
    }

    private void maybeWarmNtkBrowserSession(Activity activity) {
        if(activity == null || p == null || !p.isNtkSite())
            return;
        AppDispatchers.runIo(() -> {
            try {
                java.util.List<ml.melun.mangaview.mangaview.MTitle> recent = p.getRecent();
                if(recent == null || recent.isEmpty())
                    return;
                ml.melun.mangaview.mangaview.MTitle item = recent.get(0);
                if(item == null)
                    return;
                ml.melun.mangaview.mangaview.Title title = item instanceof ml.melun.mangaview.mangaview.Title
                        ? (ml.melun.mangaview.mangaview.Title) item
                        : new ml.melun.mangaview.mangaview.Title(item);
                ml.melun.mangaview.mangaview.Manga manga =
                        ml.melun.mangaview.activity.ViewerResumeResolver.resumeManga(title);
                if(manga == null || !manga.isOnline())
                    return;
                manga.setTitle(title);
                manga.setTitleId(title.getId());
                manga.ensureNtkEpisodePathFromIdentity();
                String path = manga.getNtkEpisodePath();
                if(path == null || !(path.startsWith("/webtoon/") || path.startsWith("/manhwa/")))
                    return;
                CustomHttpClient client = getHttpClient();
                if(client == null || !client.isNtk())
                    return;
                String baseUrl = client.getUrl(path);
                client.syncCookiesFromWebView(baseUrl, true);
                client.syncCookiesFromWebView(baseUrl + path, true);
                if(!client.hasCloudflareClearanceForUrl(baseUrl))
                    Log.d("MainApplication", "ntk_browser_session_warm_no_clearance path=" + path);
                new Handler(Looper.getMainLooper()).post(
                        () -> maybeWarmNtkBrowserSession(activity, manga, baseUrl, path, client, true));
            } catch(Exception e) {
                Log.d("MainApplication", "ntk_browser_session_warm_resolve_error " + e);
            }
        });
    }

    private static void maybeWarmNtkBrowserSession(Activity activity,
                                                   ml.melun.mangaview.mangaview.Manga manga,
                                                   String baseUrl,
                                                   String path,
                                                   CustomHttpClient client,
                                                   boolean force) {
        if(activity == null || activity.isFinishing() || path == null || path.length() == 0 || client == null)
            return;
        long now = android.os.SystemClock.elapsedRealtime();
        if(path.equals(ntkBrowserWarmPath) && now - ntkBrowserWarmStartedAtMs < 60000L)
            return;
        if(!force && ntkBrowserWarmPath != null && ntkBrowserWarmPath.length() > 0
                && !path.equals(ntkBrowserWarmPath)
                && now - ntkBrowserWarmStartedAtMs < 12000L) {
            Log.d("MainApplication", "ntk_browser_session_warm_visible_skip_active current="
                    + ntkBrowserWarmPath + ",candidate=" + path);
            return;
        }
        FrameLayout parent = activity.findViewById(android.R.id.content);
        if(parent == null)
            return;
        ntkBrowserWarmPath = path;
        ntkBrowserWarmStartedAtMs = now;
        try {
            NtkBrowserSessionBroker.INSTANCE.attach(
                    activity,
                    parent,
                    baseUrl,
                    path,
                    client.agent,
                    java.util.Collections.emptyMap(),
                    false,
                    new NtkBrowserSessionBroker.Listener() {
                        @Override
                        public void onImages(@NonNull NtkBrowserSessionBroker.ImageSnapshot snapshot) {
                            Log.d("MainApplication", "ntk_browser_session_warm_images path="
                                    + snapshot.getPath() + ",count=" + snapshot.getImages().size());
                        }

                        @Override
                        public void onState(@NonNull String statePath, boolean cloudflare,
                                            @NonNull String title, @NonNull String bodySample) {
                            if(cloudflare)
                                Log.d("MainApplication", "ntk_browser_session_warm_cf path=" + statePath);
                        }

                        @Override
                        public void onFirstDrawable(@NonNull String drawablePath) {
                            Log.d("MainApplication", "ntk_browser_session_warm_first_drawable path=" + drawablePath);
                        }

                        @Override
                        public void onViewportReady(@NonNull String readyPath) {
                            Log.d("MainApplication", "ntk_browser_session_warm_viewport_ready path=" + readyPath);
                        }

                        @Override
                        public void onScroll(@NonNull NtkBrowserSessionBroker.ScrollSnapshot snapshot) {
                        }

                        @Override
                        public void onCoverage(@NonNull NtkBrowserSessionBroker.VisibleCoverageSnapshot snapshot) {
                        }

                        @Override
                        public void onNeedsUserVerification(@NonNull String verificationPath) {
                            Log.d("MainApplication", "ntk_browser_session_warm_needs_verification path=" + verificationPath);
                        }

                        @Override
                        public void onError(@NonNull String errorPath, @NonNull String message) {
                            Log.d("MainApplication", "ntk_browser_session_warm_error path="
                                    + errorPath + "," + message);
                        }
                    },
                    manga.getNtkImageCount());
            Log.d("MainApplication", "ntk_browser_session_warm_start path=" + path);
        } catch(Exception e) {
            Log.d("MainApplication", "ntk_browser_session_warm_attach_error path=" + path + "," + e);
        }
    }

    public static void warmNtkBrowserSessionForEpisode(Activity activity,
                                                       ml.melun.mangaview.mangaview.Manga manga,
                                                       ml.melun.mangaview.mangaview.Title title) {
        if(activity == null || manga == null)
            return;
        try {
            manga.setTitle(title);
            if(title != null)
                manga.setTitleId(title.getId());
            manga.ensureNtkEpisodePathFromIdentity();
            String path = manga.getNtkEpisodePath();
            if(path == null || !(path.startsWith("/webtoon/") || path.startsWith("/manhwa/")))
                return;
            CustomHttpClient client = getHttpClient();
            if(client == null || !client.isNtk())
                return;
            String baseUrl = client.getUrl(path);
            maybeWarmNtkBrowserSession(activity, manga, baseUrl, path, client, true);
        } catch(Exception e) {
            Log.d("MainApplication", "ntk_browser_session_warm_episode_error " + e);
        }
    }

    public static void warmVisibleNtkBrowserSessionForEpisode(Activity activity,
                                                              ml.melun.mangaview.mangaview.Manga manga,
                                                              ml.melun.mangaview.mangaview.Title title) {
        if(activity == null || manga == null)
            return;
        try {
            manga.setTitle(title);
            if(title != null)
                manga.setTitleId(title.getId());
            manga.ensureNtkEpisodePathFromIdentity();
            String path = manga.getNtkEpisodePath();
            if(path == null || !(path.startsWith("/webtoon/") || path.startsWith("/manhwa/")))
                return;
            CustomHttpClient client = getHttpClient();
            if(client == null || !client.isNtk())
                return;
            String baseUrl = client.getUrl(path);
            maybeWarmNtkBrowserSession(activity, manga, baseUrl, path, client, false);
        } catch(Exception e) {
            Log.d("MainApplication", "ntk_browser_session_warm_visible_error " + e);
        }
    }

    private void startNtkTrustRefresher(String path) {
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
                String baseUrl = client.getUrl(path);
                boolean ackOk = client.preWarmStrictNtkAckForPath(path, 2500L);
                boolean keyOk = client.warmNtkViewerRequestKey(baseUrl, path);
                android.util.Log.d("MainApplication", "ntk_trust_refresh_done path=" + path
                        + ",ack=" + ackOk
                        + ",key=" + keyOk);
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
