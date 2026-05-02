package ml.melun.mangaview;

import android.content.Context;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.multidex.MultiDexApplication;

import ml.melun.mangaview.mangaview.CustomHttpClient;
import ml.melun.mangaview.report.CrashReporter;



//@AcraCore(reportContent = { APP_VERSION_NAME, ANDROID_VERSION, PHONE_MODEL, STACK_TRACE, REPORT_ID})


public class MainApplication extends MultiDexApplication {
    public static CustomHttpClient httpClient;
    public static Preference p;
    public static Context appContext;
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
        httpClient = new CustomHttpClient(this);
        super.onCreate();
    }
}
