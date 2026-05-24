package ml.melun.mangaview;

import android.content.Context;
import android.content.pm.ApplicationInfo;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.multidex.MultiDexApplication;
import androidx.work.Configuration;
import androidx.work.WorkManager;

import ml.melun.mangaview.ClassificationDbUpdater;
import ml.melun.mangaview.mangaview.CustomHttpClient;
import ml.melun.mangaview.mangaview.MainPageWebtoon;
import ml.melun.mangaview.report.CrashReporter;
import ml.melun.mangaview.repository.room.MangaRoomStore;
import ml.melun.mangaview.runtime.AppDispatchers;
import ml.melun.mangaview.runtime.MainThreadStallMonitor;
import ml.melun.mangaview.runtime.PerfTrace;



//@AcraCore(reportContent = { APP_VERSION_NAME, ANDROID_VERSION, PHONE_MODEL, STACK_TRACE, REPORT_ID})


public class MainApplication extends MultiDexApplication {
    public static volatile CustomHttpClient httpClient;
    public static Preference p;
    public static Context appContext;
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
        MainThreadStallMonitor.install((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0);
        long crashReporterStartedAt = PerfTrace.start("app_crash_reporter_install_ms");
        CrashReporter.install(this);
        PerfTrace.end("app_crash_reporter_install_ms", crashReporterStartedAt);
        long vectorStartedAt = PerfTrace.start("app_vector_delegate_ms");
        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true);
        PerfTrace.end("app_vector_delegate_ms", vectorStartedAt);
        long preferenceStartedAt = PerfTrace.start("app_preference_init_ms");
        p = new Preference(this);
        PerfTrace.end("app_preference_init_ms", preferenceStartedAt);
        long superStartedAt = PerfTrace.start("app_super_on_create_ms");
        super.onCreate();
        PerfTrace.end("app_super_on_create_ms", superStartedAt);
        PerfTrace.end("app_on_create_ms", appStartedAt);
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

    public static void initDeferredServices() {
        if(deferredServicesStarted)
            return;
        synchronized (deferredServicesLock) {
            if(!deferredServicesStarted) {
                deferredServicesStarted = true;
                AppDispatchers.runIoDelayed(() -> MangaRoomStore.prime(appContext), 1800);
                AppDispatchers.runIoDelayed(MainPageWebtoon::preloadClassificationDbs, 3800);
                AppDispatchers.runIoDelayed(() -> ClassificationDbUpdater.updateInBackground(appContext), 7000);
            }
        }
    }
}
