package ml.melun.mangaview.runtime;

import android.os.SystemClock;
import android.util.Log;

import androidx.tracing.Trace;

import ml.melun.mangaview.MainApplication;

public final class PerfTrace {
    private static final String TAG = "PerfTrace";
    private static final int MAX_SECTION_NAME_LENGTH = 127;

    private PerfTrace() {
    }

    public static long start(String name) {
        return SystemClock.elapsedRealtime();
    }

    public static void end(String name, long startedAtMs) {
        // start/end are elapsed-time markers and are intentionally allowed to cross callbacks
        // and threads. Use begin/end or beginAsync/endAsync for real trace slices.
        mark(name, SystemClock.elapsedRealtime() - startedAtMs);
    }

    public static void begin(String name) {
        Trace.beginSection(traceName(name));
    }

    public static void end() {
        Trace.endSection();
    }

    public static void beginAsync(String name, int cookie) {
        Trace.beginAsyncSection(traceName(name), cookie);
    }

    public static void endAsync(String name, int cookie) {
        Trace.endAsyncSection(traceName(name), cookie);
    }

    public static void counter(String name, long value) {
        long clamped = Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value));
        Trace.setCounter(traceName(name), (int) clamped);
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

    private static String traceName(String value) {
        String safe = value == null || value.length() == 0 ? "unnamed" : value;
        return safe.length() <= MAX_SECTION_NAME_LENGTH
                ? safe
                : safe.substring(0, MAX_SECTION_NAME_LENGTH);
    }
}
