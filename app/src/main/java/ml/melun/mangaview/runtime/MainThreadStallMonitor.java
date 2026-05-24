package ml.melun.mangaview.runtime;

import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.Printer;

import java.util.Map;
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
                if(durationMs >= DISPATCH_WARN_MS) {
                    Log.w(TAG, "main_dispatch_ms=" + durationMs + " msg=" + message);
                    logThreads("main_dispatch_ms=" + durationMs);
                }
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
            if(durationMs >= SECTION_WARN_MS) {
                Log.w(TAG, "main_section_ms=" + durationMs + " name=" + name);
                if(durationMs >= DISPATCH_WARN_MS) logThreads("main_section_ms=" + durationMs + " name=" + name);
            }
        }
    }

    public static void warn(String name, long durationMs) {
        if(!enabled || durationMs < DISPATCH_WARN_MS)
            return;
        Log.w(TAG, name + "_ms=" + durationMs);
        logThreads(name + "_ms=" + durationMs);
    }

    public static void warn(String name, float durationMs) {
        warn(name, Math.round(durationMs));
    }

    private static void logThreads(String reason) {
        StringBuilder builder = new StringBuilder(4096);
        builder.append("thread_snapshot reason=").append(reason);
        for(Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
            Thread thread = entry.getKey();
            String name = thread.getName();
            if(thread == Looper.getMainLooper().getThread()
                    || name.startsWith("Reader")
                    || name.startsWith("RenderThread")
                    || name.startsWith("hwui")
                    || name.startsWith("Finalizer")) {
                builder.append("\n  thread=").append(name)
                        .append(" state=").append(thread.getState());
                StackTraceElement[] stack = entry.getValue();
                int limit = Math.min(stack.length, 8);
                for(int i = 0; i < limit; i++) {
                    builder.append("\n    at ").append(stack[i]);
                }
            }
        }
        Log.w(TAG, builder.toString());
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
