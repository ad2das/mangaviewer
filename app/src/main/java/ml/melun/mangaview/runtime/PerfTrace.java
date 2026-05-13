package ml.melun.mangaview.runtime;

import android.os.SystemClock;
import android.util.Log;

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
        return shouldLog(Log.isLoggable(TAG, Log.DEBUG));
    }

    static boolean shouldLogForTest(boolean debugTagLoggable) {
        return shouldLog(debugTagLoggable);
    }

    private static boolean shouldLog(boolean debugTagLoggable) {
        return debugTagLoggable;
    }
}
