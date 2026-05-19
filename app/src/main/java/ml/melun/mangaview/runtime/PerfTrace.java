package ml.melun.mangaview.runtime;

import android.os.SystemClock;
import android.util.Log;

import ml.melun.mangaview.MainApplication;

public final class PerfTrace {
    private static final String TAG = "PerfTrace";

    private PerfTrace() {
    }

    public static long start(String name) {
        return SystemClock.elapsedRealtime();
    }

    public static void end(String name, long startedAtMs) {
        mark(name, SystemClock.elapsedRealtime() - startedAtMs);
    }

    public static void mark(String name, long valueMs) {
        if(!shouldLog())
            return;
        Log.d(TAG, name + "=" + valueMs);
    }

    public static void mark(String name, String metadata) {
        if(!shouldLog())
            return;
        Log.d(TAG, name + "=" + metadata);
    }

    public static boolean shouldLog() {
        return shouldLog(isDebuggableApp(), Log.isLoggable(TAG, Log.DEBUG));
    }

    static boolean shouldLogForTest(boolean debugTagLoggable) {
        return shouldLog(false, debugTagLoggable);
    }

    static boolean shouldLogForTest(boolean debugBuild, boolean debugTagLoggable) {
        return shouldLog(debugBuild, debugTagLoggable);
    }

    private static boolean shouldLog(boolean debugBuild, boolean debugTagLoggable) {
        return debugTagLoggable;
    }

    private static boolean isDebuggableApp() {
        try {
            return MainApplication.appContext != null
                    && (MainApplication.appContext.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
