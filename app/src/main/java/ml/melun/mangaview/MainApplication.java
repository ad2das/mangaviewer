package ml.melun.mangaview;

import android.content.Context;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.multidex.MultiDexApplication;

import ml.melun.mangaview.mangaview.CustomHttpClient;
import ml.melun.mangaview.mangaview.MainPageWebtoon;
import ml.melun.mangaview.glide.ViewerWarmupManager;
import ml.melun.mangaview.report.CrashReporter;
import ml.melun.mangaview.repository.room.MangaRoomStore;
import ml.melun.mangaview.runtime.AppDispatchers;



//@AcraCore(reportContent = { APP_VERSION_NAME, ANDROID_VERSION, PHONE_MODEL, STACK_TRACE, REPORT_ID})


public class MainApplication extends MultiDexApplication {
    public static CustomHttpClient httpClient;
    public static Preference p;
    public static Context appContext;
    public static FirebaseAccountManager firebaseAccountManager;
    public static FirebaseSyncManager firebaseSyncManager;
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
    }

    @Override
    public void onCreate() {
        appContext = this;
        CrashReporter.install(this);
        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true);
        p = new Preference(this);
        MangaRoomStore.prime(this);
        AppDispatchers.runIo(MainPageWebtoon::preloadClassificationDbs);
        super.onCreate();
        ViewerWarmupManager.warmupSavedContinues(this, 6);
    }

    public static synchronized CustomHttpClient getHttpClient() {
        if(httpClient == null)
            httpClient = new CustomHttpClient(appContext);
        return httpClient;
    }

    public static synchronized FirebaseAccountManager getFirebaseAccountManager() {
        if(firebaseAccountManager == null)
            firebaseAccountManager = new FirebaseAccountManager(appContext);
        return firebaseAccountManager;
    }

    public static synchronized FirebaseSyncManager getFirebaseSyncManager() {
        if(firebaseSyncManager == null)
            firebaseSyncManager = new FirebaseSyncManager(appContext, p);
        return firebaseSyncManager;
    }

    public static synchronized void initDeferredServices() {
        getFirebaseAccountManager();
        getFirebaseSyncManager();
    }
}
