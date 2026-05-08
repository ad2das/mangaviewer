package ml.melun.mangaview;

import android.app.Application;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Configuration;


import ml.melun.mangaview.mangaview.CustomHttpClient;
import ml.melun.mangaview.mangaview.MainPageWebtoon;
import ml.melun.mangaview.report.CrashReporter;
import ml.melun.mangaview.repository.room.MangaRoomStore;
import ml.melun.mangaview.runtime.AppDispatchers;



//@AcraCore(reportContent = { APP_VERSION_NAME, ANDROID_VERSION, PHONE_MODEL, STACK_TRACE, REPORT_ID})


public class MainApplication extends Application implements Configuration.Provider {
    public static CustomHttpClient httpClient;
    public static Preference p;
    public static Context appContext;
    public static FirebaseAccountManager firebaseAccountManager;
    public static FirebaseSyncManager firebaseSyncManager;
    @Override
    public void onCreate() {
        appContext = this;
        super.onCreate();
    }

    @NonNull
    @Override
    public Configuration getWorkManagerConfiguration() {
        return new Configuration.Builder().build();
    }

    public static synchronized Preference getPreference() {
        if(p == null)
            p = new Preference(appContext);
        return p;
    }

    public static synchronized void initCoreServices() {
        CrashReporter.install(appContext);
        getPreference();
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
        getPreference();
        if(firebaseSyncManager == null)
            firebaseSyncManager = new FirebaseSyncManager(appContext, p);
        return firebaseSyncManager;
    }

    public static synchronized void initDeferredServices() {
        initCoreServices();
        MangaRoomStore.prime(appContext);
        AppDispatchers.runIo(MainPageWebtoon::preloadClassificationDbs);
        getFirebaseAccountManager();
        getFirebaseSyncManager();
    }
}
