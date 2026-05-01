package ml.melun.mangaview.task;

import android.os.Handler;
import android.os.Looper;

import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public abstract class LifecycleTask<Params, Progress, Result> implements DefaultLifecycleObserver {
    public enum Status {
        PENDING,
        RUNNING,
        FINISHED
    }

    private static final int POOL_SIZE = 5;
    private static final int KEEP_ALIVE_SECONDS = 30;
    private static final AtomicInteger THREAD_ID = new AtomicInteger(1);

    public static final ExecutorService THREAD_POOL_EXECUTOR = new ThreadPoolExecutor(
            POOL_SIZE,
            POOL_SIZE,
            KEEP_ALIVE_SECONDS,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            runnable -> new Thread(runnable, "LifecycleTask-" + THREAD_ID.getAndIncrement())
    );
    public static final ExecutorService USER_ACTION_EXECUTOR = new ThreadPoolExecutor(
            3,
            3,
            KEEP_ALIVE_SECONDS,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            runnable -> new Thread(runnable, "LifecycleUserTask-" + THREAD_ID.getAndIncrement())
    );
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final Object lifecycleCandidate;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean lifecycleDestroyed = new AtomicBoolean(false);
    private final AtomicBoolean completed = new AtomicBoolean(false);
    private final AtomicBoolean backgroundStarted = new AtomicBoolean(false);
    private Lifecycle lifecycle;
    private volatile Status status = Status.PENDING;
    private Future<?> future;

    protected LifecycleTask() {
        this(null);
    }

    protected LifecycleTask(Object lifecycleCandidate) {
        this.lifecycleCandidate = lifecycleCandidate;
    }

    public final Status getStatus() {
        return status;
    }

    public final boolean isCancelled() {
        return cancelled.get();
    }

    public boolean cancel(boolean mayInterruptIfRunning) {
        cancelled.set(true);
        boolean result = future == null || future.cancel(mayInterruptIfRunning);
        if(status == Status.RUNNING && !backgroundStarted.get())
            MAIN.post(() -> finish(null));
        return result;
    }

    public final LifecycleTask<Params, Progress, Result> execute(Params... params) {
        return executeOnExecutor(THREAD_POOL_EXECUTOR, params);
    }

    public final LifecycleTask<Params, Progress, Result> executeOnExecutor(Executor executor, Params... params) {
        if(status != Status.PENDING)
            throw new IllegalStateException("Task can be executed only once");
        status = Status.RUNNING;
        bindLifecycle();
        runOnMain(() -> {
            if(isActive())
                onPreExecute();
        });
        future = submit(executor, () -> {
            backgroundStarted.set(true);
            Result result = null;
            try {
                if(isActive())
                    result = doInBackground(params == null || params.length == 0 ? null : params);
            } catch (Throwable e) {
                if(!isCancelled())
                    e.printStackTrace();
            }
            Result finalResult = result;
            MAIN.post(() -> finish(finalResult));
        });
        return this;
    }

    protected final void publishProgress(Progress... values) {
        MAIN.post(() -> {
            if(!isCancelled() && isActive())
                onProgressUpdate(values);
        });
    }

    protected void onPreExecute() {
    }

    protected abstract Result doInBackground(Params... params);

    protected void onProgressUpdate(Progress... values) {
    }

    protected void onPostExecute(Result result) {
    }

    protected void onCancelled(Result result) {
        onCancelled();
    }

    protected void onCancelled() {
    }

    @Override
    public void onDestroy(LifecycleOwner owner) {
        lifecycleDestroyed.set(true);
        cancel(true);
    }

    private Future<?> submit(Executor executor, Runnable runnable) {
        if(executor instanceof ExecutorService)
            return ((ExecutorService)executor).submit(runnable);
        executor.execute(runnable);
        return null;
    }

    private void finish(Result result) {
        if(!completed.compareAndSet(false, true))
            return;
        status = Status.FINISHED;
        boolean destroyed = lifecycleDestroyed.get()
                || lifecycle != null && lifecycle.getCurrentState() == Lifecycle.State.DESTROYED;
        unbindLifecycle();
        if(destroyed)
            return;
        if(isCancelled()) {
            onCancelled(result);
            return;
        }
        if(isActive())
            onPostExecute(result);
    }

    private boolean isActive() {
        return !lifecycleDestroyed.get()
                && (lifecycle == null || lifecycle.getCurrentState() != Lifecycle.State.DESTROYED);
    }

    private void bindLifecycle() {
        LifecycleOwner owner = lifecycleOwner();
        if(owner == null)
            return;
        lifecycle = owner.getLifecycle();
        lifecycle.addObserver(this);
        if(lifecycle.getCurrentState() == Lifecycle.State.DESTROYED)
            cancel(true);
    }

    private void unbindLifecycle() {
        if(lifecycle == null)
            return;
        lifecycle.removeObserver(this);
        lifecycle = null;
    }

    private LifecycleOwner lifecycleOwner() {
        if(lifecycleCandidate != null)
            return findLifecycleOwner(lifecycleCandidate, 2);
        return findLifecycleOwner(this, 3);
    }

    private LifecycleOwner findLifecycleOwner(Object value, int depth) {
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        return findLifecycleOwner(value, depth, seen);
    }

    private LifecycleOwner findLifecycleOwner(Object value, int depth, Set<Object> seen) {
        if(value == null || depth < 0 || seen.contains(value))
            return null;
        if(value instanceof LifecycleOwner)
            return (LifecycleOwner)value;
        seen.add(value);

        Class<?> current = value.getClass();
        if(!isAppClass(current))
            return null;
        while(current != null && current != Object.class) {
            Field[] fields = current.getDeclaredFields();
            for(Field field : fields) {
                if(Modifier.isStatic(field.getModifiers()))
                    continue;
                try {
                    field.setAccessible(true);
                    LifecycleOwner owner = findLifecycleOwner(field.get(value), depth - 1, seen);
                    if(owner != null)
                        return owner;
                } catch (Exception ignored) {
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private boolean isAppClass(Class<?> current) {
        return current != null && current.getName().startsWith("ml.melun.mangaview");
    }

    private void runOnMain(Runnable runnable) {
        if(Looper.myLooper() == Looper.getMainLooper())
            runnable.run();
        else
            MAIN.post(runnable);
    }
}
