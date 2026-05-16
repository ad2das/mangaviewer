package ml.melun.mangaview.runtime;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import ml.melun.mangaview.report.CrashReporter;

public final class AppDispatchers {
    private static final ThreadPoolExecutor IO = boundedPool("manga-io", 2, 10, 256);
    private static final ThreadPoolExecutor NETWORK_FANOUT = boundedPool("manga-net", 4, 12, 512);
    private static final ThreadPoolExecutor USER_ACTION = boundedPool("manga-action", 1, 4, 128);
    private static final ThreadPoolExecutor NAVIGATION = boundedPool("manga-nav", 1, 2, 32);
    private static final ThreadPoolExecutor UI_DIFF = boundedPool("manga-diff", 1, 2, 96);
    private static final ThreadPoolExecutor IMAGE_WARMUP = boundedPool("manga-image",
            AppDispatcherPolicy.IMAGE_WARMUP_CORE_THREADS,
            AppDispatcherPolicy.IMAGE_WARMUP_MAX_THREADS,
            AppDispatcherPolicy.IMAGE_WARMUP_QUEUE_SIZE);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private AppDispatchers() {
    }

    public static Executor io() {
        return IO;
    }

    public static Executor userAction() {
        return USER_ACTION;
    }

    public static Executor uiDiff() {
        return UI_DIFF;
    }

    public static Executor imageWarmup() {
        return IMAGE_WARMUP;
    }

    public static Handler main() {
        return MAIN;
    }

    public static void runIo(Runnable runnable) {
        IO.execute(safe(runnable));
    }

    public static void runIoDelayed(Runnable runnable, long delayMs) {
        MAIN.postDelayed(() -> runIo(runnable), Math.max(0L, delayMs));
    }

    public static void runUserAction(Runnable runnable) {
        USER_ACTION.execute(safe(runnable));
    }

    public static TaskHandle submitIo(Runnable runnable) {
        return new TaskHandle(IO.submit(safe(runnable)));
    }

    public static TaskHandle submitUserAction(Runnable runnable) {
        return new TaskHandle(USER_ACTION.submit(safe(runnable)));
    }

    public static TaskHandle submitNavigation(Runnable runnable) {
        return new TaskHandle(NAVIGATION.submit(safe(runnable)));
    }

    public static TaskHandle submitUiDiff(Runnable runnable) {
        return new TaskHandle(UI_DIFF.submit(safe(runnable)));
    }

    public static TaskHandle submitImageWarmup(Runnable runnable) {
        return new TaskHandle(IMAGE_WARMUP.submit(safe(runnable)));
    }

    public static <T> CompletionService<T> ioCompletionService() {
        return new ExecutorCompletionService<>(NETWORK_FANOUT);
    }

    public static void runOnMain(Runnable runnable) {
        if(Looper.myLooper() == Looper.getMainLooper())
            runnable.run();
        else
            MAIN.post(runnable);
    }

    public static <T> Callable<T> safeCallable(Callable<T> callable) {
        return () -> {
            try {
                return callable.call();
            } catch (Throwable throwable) {
                CrashReporter.record(throwable);
                if(throwable instanceof Exception)
                    throw (Exception) throwable;
                throw new RuntimeException(throwable);
            }
        };
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

    private static ThreadPoolExecutor boundedPool(String name, int core, int max, int queueSize) {
        BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>(queueSize);
        ThreadPoolExecutor executor = new java.util.concurrent.ThreadPoolExecutor(
                core,
                max,
                30L,
                TimeUnit.SECONDS,
                queue,
                namedThreadFactory(name),
                (runnable, rejectedExecutor) -> {
                    if(rejectedExecutor == null || rejectedExecutor.isShutdown())
                        return;
                    if(name.startsWith("manga-image"))
                        return;
                    Thread overflow = namedThreadFactory(name + "-overflow").newThread(() -> safe(runnable).run());
                    overflow.start();
                });
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private static ThreadFactory namedThreadFactory(String name) {
        final int[] count = {0};
        return runnable -> {
            Thread thread = new java.lang.Thread(runnable, name + '-' + (++count[0]));
            thread.setDaemon(true);
            return thread;
        };
    }

    public static final class TaskHandle {
        private final Future future;

        private TaskHandle(Future future) {
            this.future = future;
        }

        public boolean cancel() {
            return future == null || future.cancel(true);
        }

        public boolean isDone() {
            return future == null || future.isDone();
        }
    }
}
