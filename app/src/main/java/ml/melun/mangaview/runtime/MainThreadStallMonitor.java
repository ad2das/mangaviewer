package ml.melun.mangaview.runtime;

import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.Printer;

import java.util.concurrent.atomic.AtomicBoolean;

public final class MainThreadStallMonitor {
    private static final String TAG = "MainStall";
    private static volatile boolean enabled;
    private static final long DISPATCH_WARN_MS = 24L;
    private static final long SECTION_WARN_MS = 12L;
    private static final int MAX_MESSAGE_LEN = 180;
    private static final AtomicBoolean installed = new AtomicBoolean(false);

    private MainThreadStallMonitor() {
    }

    public static void install(boolean enable) {
        enabled = enable;
        if(!enabled)
            return;
        if(!installed.compareAndSet(false, true))
            return;
        Looper.getMainLooper().setMessageLogging(new Printer() {
            private long startedAtMs;
            private String message;

            @Override
            public void println(String x) {
                if(x == null)
                    return;
                if(x.startsWith(">>>>> Dispatching")) {
                    startedAtMs = SystemClock.uptimeMillis();
                    message = shorten(x);
                    return;
                }
                if(!x.startsWith("<<<<< Finished") || startedAtMs <= 0)
                    return;
                long durationMs = SystemClock.uptimeMillis() - startedAtMs;
                if(durationMs >= DISPATCH_WARN_MS)
                    Log.w(TAG, "main_dispatch_ms=" + durationMs + " msg=" + message);
                startedAtMs = 0L;
                message = null;
            }
        });
    }

    public static void trace(String name, Runnable runnable) {
        if(!enabled) {
            runnable.run();
            return;
        }
        long startedAtMs = SystemClock.uptimeMillis();
        try {
            runnable.run();
        } finally {
            long durationMs = SystemClock.uptimeMillis() - startedAtMs;
            if(durationMs >= SECTION_WARN_MS)
                Log.w(TAG, "main_section_ms=" + durationMs + " name=" + name);
        }
    }

    public interface Supplier<T> {
        T get();
    }

    public static <T> T traceResult(String name, Supplier<T> supplier) {
        if(!enabled)
            return supplier.get();
        long startedAtMs = SystemClock.uptimeMillis();
        try {
            return supplier.get();
        } finally {
            long durationMs = SystemClock.uptimeMillis() - startedAtMs;
            if(durationMs >= SECTION_WARN_MS)
                Log.w(TAG, "main_section_ms=" + durationMs + " name=" + name);
        }
    }

    private static String shorten(String value) {
        if(value.length() <= MAX_MESSAGE_LEN)
            return value;
        return value.substring(0, MAX_MESSAGE_LEN) + "...";
    }
}
