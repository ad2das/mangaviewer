package ml.melun.mangaview.runtime;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import ml.melun.mangaview.report.CrashReporter;

public final class AppDispatchers {
    private static final ExecutorService IO = Executors.newCachedThreadPool();
    private static final ExecutorService USER_ACTION = Executors.newCachedThreadPool();
    private static final ExecutorService IMAGE_WARMUP = Executors.newCachedThreadPool();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private AppDispatchers() {
    }

    public static ExecutorService io() {
        return IO;
    }

    public static ExecutorService userAction() {
        return USER_ACTION;
    }

    public static ExecutorService imageWarmup() {
        return IMAGE_WARMUP;
    }

    public static Handler main() {
        return MAIN;
    }

    public static void runIo(Runnable runnable) {
        IO.execute(safe(runnable));
    }

    public static void runUserAction(Runnable runnable) {
        USER_ACTION.execute(safe(runnable));
    }

    public static Future<?> submitImageWarmup(Runnable runnable) {
        return IMAGE_WARMUP.submit(safe(runnable));
    }

    public static Future<?> submitUserAction(Runnable runnable) {
        return USER_ACTION.submit(safe(runnable));
    }

    public static void runOnMain(Runnable runnable) {
        if(Looper.myLooper() == Looper.getMainLooper())
            runnable.run();
        else
            MAIN.post(runnable);
    }

    private static Runnable safe(Runnable runnable) {
        return () -> {
            try {
                runnable.run();
            } catch (Throwable throwable) {
                CrashReporter.record(throwable);
            }
        };
    }
}
