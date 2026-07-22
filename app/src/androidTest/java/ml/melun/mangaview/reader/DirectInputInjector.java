package ml.melun.mangaview.reader;

import android.app.UiAutomation;
import android.os.Build;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import android.view.Display;

import androidx.test.platform.app.InstrumentationRegistry;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Injects a touchscreen stream directly through InputManager.
 *
 * <p>The public UiAutomation injection path synchronizes WindowManager input transactions before
 * every DOWN. That synchronization runs after the event timestamp is assigned and can therefore
 * hide a real first-input delay behind test infrastructure. This helper resolves the hidden direct
 * backend and adopts the shell INJECT_EVENTS identity before a timestamp is taken. Unsupported or
 * inaccessible direct injection is a strict failure; there is deliberately no UiAutomation
 * fallback.</p>
 */
public final class DirectInputInjector {
    private static final String INJECT_EVENTS_PERMISSION = "android.permission.INJECT_EVENTS";
    private static final int INJECT_MODE_ASYNC = 0;
    private static final int INJECT_MODE_WAIT_FOR_RESULT = 1;
    private static final int INJECT_MODE_WAIT_FOR_FINISH = 2;
    private static final int INVALID_UID = -1;
    private static final ReentrantLock SEQUENCE_LOCK = new ReentrantLock(true);

    private static volatile Backend backend;
    private static volatile Throwable backendResolutionFailure;
    private static volatile boolean backendResolutionAttempted;

    private DirectInputInjector() {
    }

    /** Resolves the direct backend without acquiring the sequence lock or holding shell identity. */
    public static void prepareBackend() {
        requireBackend();
    }

    public static TouchSequence beginTouchSequence() {
        Backend resolved = requireBackend();
        SEQUENCE_LOCK.lock();
        UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        boolean adopted = false;
        try {
            automation.adoptShellPermissionIdentity(INJECT_EVENTS_PERMISSION);
            adopted = true;
            return new TouchSequence(resolved, automation);
        } catch(Throwable failure) {
            if(adopted) {
                try {
                    automation.dropShellPermissionIdentity();
                } catch(Throwable ignored) {
                }
            }
            SEQUENCE_LOCK.unlock();
            throw strictFailure("Unable to adopt shell INJECT_EVENTS identity", failure);
        }
    }

    /**
     * Injects a physical navigation tap through the public synchronized UiAutomation path.
     * Unlike reader input, navigation is outside performance measurement and must wait for any
     * in-flight WindowManager animation/input-window transaction before the DOWN is delivered.
     */
    public static InjectionResult[] injectNavigationTapWaitForFinish(
            float x, float y, long holdMs) {
        SEQUENCE_LOCK.lock();
        try {
            UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
            long downTimeMs = SystemClock.uptimeMillis();
            InjectionResult down = injectWithUiAutomation(
                    automation, downTimeMs, downTimeMs, MotionEvent.ACTION_DOWN, x, y);
            SystemClock.sleep(Math.max(1L, holdMs));
            long upTimeMs = Math.max(downTimeMs, SystemClock.uptimeMillis());
            InjectionResult up = injectWithUiAutomation(
                    automation, downTimeMs, upTimeMs, MotionEvent.ACTION_UP, x, y);
            return new InjectionResult[]{down, up};
        } finally {
            SEQUENCE_LOCK.unlock();
        }
    }

    private static InjectionResult injectWithUiAutomation(
            UiAutomation automation, long downTimeMs, long eventTimeMs,
            int action, float x, float y) {
        MotionEvent event = obtainTouchEvent(
                downTimeMs, eventTimeMs, action, x, y);
        long callStartedAtMs = SystemClock.uptimeMillis();
        boolean injected;
        try {
            injected = automation.injectInputEvent(event, true);
        } finally {
            event.recycle();
        }
        long callDurationMs = SystemClock.uptimeMillis() - callStartedAtMs;
        if(!injected) {
            throw strictFailure("Synchronized navigation injection rejected action="
                    + (action & MotionEvent.ACTION_MASK), null);
        }
        return new InjectionResult(
                action & MotionEvent.ACTION_MASK, eventTimeMs, callDurationMs, true);
    }

    public static final class InjectionResult {
        private final int action;
        private final long eventTimeMs;
        private final long callDurationMs;
        private final boolean injected;

        private InjectionResult(int action, long eventTimeMs, long callDurationMs,
                                boolean injected) {
            this.action = action;
            this.eventTimeMs = eventTimeMs;
            this.callDurationMs = callDurationMs;
            this.injected = injected;
        }

        public int getAction() {
            return action;
        }

        public long getEventTimeMs() {
            return eventTimeMs;
        }

        public long getCallDurationMs() {
            return callDurationMs;
        }

        public boolean isInjected() {
            return injected;
        }

        @Override
        public String toString() {
            return "InjectionResult(action=" + action
                    + ",eventTimeMs=" + eventTimeMs
                    + ",callDurationMs=" + callDurationMs
                    + ",injected=" + injected + ")";
        }
    }

    public static final class TouchSequence implements AutoCloseable {
        private final Backend directBackend;
        private final UiAutomation automation;
        private long downTimeMs;
        private long lastEventTimeMs;
        private float lastX;
        private float lastY;
        private boolean gestureActive;
        private boolean closed;

        private TouchSequence(Backend directBackend, UiAutomation automation) {
            this.directBackend = directBackend;
            this.automation = automation;
        }

        /** Injects one physical sample without waiting for application dispatch or rendering. */
        public InjectionResult injectAsync(int action, float x, float y) {
            return injectAsync(action, x, y, SystemClock.uptimeMillis());
        }

        /**
         * Injects one physical sample using the producer's immutable absolute schedule.
         * A session intentionally supports multiple DOWN..UP gestures so shell identity,
         * reflection setup, and the sequence lock never perturb inter-gesture cadence.
         */
        public InjectionResult injectAsync(int action, float x, float y,
                                           long plannedEventTimeMs) {
            return inject(action, x, y, plannedEventTimeMs, INJECT_MODE_ASYNC);
        }

        /** Injects navigation input after InputDispatcher confirms a real target window. */
        public InjectionResult injectWaitForResult(int action, float x, float y) {
            return inject(action, x, y, SystemClock.uptimeMillis(), INJECT_MODE_WAIT_FOR_RESULT);
        }

        /** Injects navigation input and waits until its target finishes dispatching it. */
        public InjectionResult injectWaitForFinish(int action, float x, float y) {
            return inject(action, x, y, SystemClock.uptimeMillis(), INJECT_MODE_WAIT_FOR_FINISH);
        }

        private InjectionResult inject(int action, float x, float y,
                                       long plannedEventTimeMs, int injectionMode) {
            if(closed)
                throw strictFailure("Touch sequence is already closed", null);
            int masked = action & MotionEvent.ACTION_MASK;
            if(masked == MotionEvent.ACTION_DOWN) {
                if(gestureActive)
                    throw strictFailure("Duplicate DOWN in strict touch sequence", null);
            } else if(!gestureActive) {
                throw strictFailure("Touch event without active DOWN action=" + masked, null);
            }
            if(plannedEventTimeMs <= 0L)
                throw strictFailure("Invalid planned touch event time=" + plannedEventTimeMs, null);
            if(gestureActive && plannedEventTimeMs < lastEventTimeMs) {
                throw strictFailure("Non-monotonic planned touch event time previous="
                        + lastEventTimeMs + " current=" + plannedEventTimeMs, null);
            }

            long eventDownTimeMs = masked == MotionEvent.ACTION_DOWN
                    ? plannedEventTimeMs : downTimeMs;
            MotionEvent event = obtainTouchEvent(
                    eventDownTimeMs, plannedEventTimeMs, action, x, y);
            long callStartedAtMs = SystemClock.uptimeMillis();
            boolean injected;
            try {
                injected = directBackend.inject(event, injectionMode);
            } finally {
                event.recycle();
            }
            long callDurationMs = SystemClock.uptimeMillis() - callStartedAtMs;
            if(!injected) {
                throw strictFailure("Direct InputManager injection rejected action=" + masked
                        + " mode=" + injectionMode, null);
            }
            downTimeMs = eventDownTimeMs;
            lastEventTimeMs = plannedEventTimeMs;
            lastX = x;
            lastY = y;
            gestureActive = masked != MotionEvent.ACTION_UP
                    && masked != MotionEvent.ACTION_CANCEL;
            if(!gestureActive) {
                downTimeMs = 0L;
                lastEventTimeMs = 0L;
            }
            return new InjectionResult(masked, plannedEventTimeMs, callDurationMs, true);
        }

        /** Best-effort fail-fast cleanup; cancellation is asynchronous as well. */
        public void abortActiveGestureAsync() {
            if(closed || !gestureActive)
                return;
            try {
                injectAsync(MotionEvent.ACTION_CANCEL, lastX, lastY,
                        Math.max(lastEventTimeMs, SystemClock.uptimeMillis()));
            } catch(Throwable ignored) {
                // The original producer failure remains authoritative.
            }
        }

        @Override
        public void close() {
            if(closed)
                return;
            try {
                abortActiveGestureAsync();
            } finally {
                closed = true;
                try {
                    automation.dropShellPermissionIdentity();
                } finally {
                    SEQUENCE_LOCK.unlock();
                }
            }
        }
    }

    private static MotionEvent obtainTouchEvent(long downTimeMs, long eventTimeMs, int action,
                                                float x, float y) {
        MotionEvent.PointerProperties properties = new MotionEvent.PointerProperties();
        properties.id = 0;
        properties.toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords coordinates = new MotionEvent.PointerCoords();
        coordinates.x = x;
        coordinates.y = y;
        coordinates.pressure = (action & MotionEvent.ACTION_MASK) == MotionEvent.ACTION_UP
                || (action & MotionEvent.ACTION_MASK) == MotionEvent.ACTION_CANCEL ? 0f : 1f;
        coordinates.size = 1f;
        return MotionEvent.obtain(
                downTimeMs,
                eventTimeMs,
                action,
                1,
                new MotionEvent.PointerProperties[]{properties},
                new MotionEvent.PointerCoords[]{coordinates},
                0,
                0,
                1f,
                1f,
                0,
                0,
                InputDevice.SOURCE_TOUCHSCREEN,
                Display.DEFAULT_DISPLAY,
                0,
                MotionEvent.CLASSIFICATION_NONE);
    }

    private interface Backend {
        boolean inject(InputEvent event, int mode);
    }

    private static Backend requireBackend() {
        Backend cached = backend;
        if(cached != null)
            return cached;
        synchronized(DirectInputInjector.class) {
            cached = backend;
            if(cached != null)
                return cached;
            if(!backendResolutionAttempted) {
                backendResolutionAttempted = true;
                try {
                    backend = resolveInputManagerBackend();
                    cached = backend;
                } catch(Throwable failure) {
                    backendResolutionFailure = failure;
                }
            }
        }
        if(cached == null) {
            throw strictFailure("Direct InputManager injection is unavailable",
                    backendResolutionFailure);
        }
        return cached;
    }

    private static Backend resolveInputManagerBackend() throws Exception {
        // API 35 keeps the process singleton on InputManager. InputManagerGlobal is the
        // implementation held by that wrapper but no longer exposes getInstance().
        Class<?> managerClass = Class.forName("android.hardware.input.InputManager");
        Method getInstance = managerClass.getDeclaredMethod("getInstance");
        getInstance.setAccessible(true);
        Object manager = getInstance.invoke(null);
        if(manager == null)
            throw new IllegalStateException("InputManager.getInstance returned null");

        Method twoArg = null;
        Method threeArg = null;
        for(Method candidate : managerClass.getDeclaredMethods()) {
            if(!"injectInputEvent".equals(candidate.getName()))
                continue;
            Class<?>[] parameters = candidate.getParameterTypes();
            if(parameters.length < 2 || !InputEvent.class.isAssignableFrom(parameters[0])
                    || parameters[1] != int.class)
                continue;
            if(parameters.length == 2)
                twoArg = candidate;
            else if(parameters.length == 3 && parameters[2] == int.class)
                threeArg = candidate;
        }
        Method inject = twoArg != null ? twoArg : threeArg;
        if(inject == null)
            throw new NoSuchMethodException("InputManager.injectInputEvent(InputEvent,int[,int])");
        if(Modifier.isStatic(inject.getModifiers()))
            throw new IllegalStateException("Unexpected static InputManager.injectInputEvent");
        inject.setAccessible(true);
        final Method resolvedInject = inject;
        final Object resolvedManager = manager;
        final boolean hasTargetUid = inject.getParameterTypes().length == 3;
        return (event, mode) -> {
            try {
                Object result = hasTargetUid
                        ? resolvedInject.invoke(resolvedManager, event, mode, INVALID_UID)
                        : resolvedInject.invoke(resolvedManager, event, mode);
                if(!(result instanceof Boolean)) {
                    throw strictFailure("InputManager returned non-boolean result=" + result, null);
                }
                return (Boolean)result;
            } catch(InvocationTargetException failure) {
                Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                throw strictFailure("InputManager injection invocation failed", cause);
            } catch(IllegalAccessException failure) {
                throw strictFailure("InputManager injection became inaccessible", failure);
            }
        };
    }

    private static AssertionError strictFailure(String message, Throwable cause) {
        String detail = message + " sdk=" + Build.VERSION.SDK_INT
                + "; delayed UiAutomation fallback is forbidden";
        return cause == null ? new AssertionError(detail) : new AssertionError(detail, cause);
    }
}
