package ml.melun.mangaview.runtime;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;

import androidx.metrics.performance.FrameData;
import androidx.metrics.performance.JankStats;
import androidx.metrics.performance.PerformanceMetricsState;
import androidx.metrics.performance.StateInfo;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import ml.melun.mangaview.MainApplication;
import ml.melun.mangaview.mangaview.MTitle;

public final class PerformanceMonitor {
    private static final long REPORT_INTERVAL_MS = 5000L;
    private static PerformanceMonitor current;

    private final JankStats jankStats;
    private final View decorView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final PerformanceMetricsState metricsState;
    private final AtomicReference<FrameContext> pendingFrameContext = new AtomicReference<>();
    private final AtomicBoolean stateUpdateScheduled = new AtomicBoolean(false);
    private long totalFrames = 0;
    private long jankyFrames = 0;
    private long worstFrameMs = 0;
    private long totalFrameDurationUiNanos = 0;
    private long consecutiveJankyFrames = 0;
    private long maxConsecutiveJankyFrames = 0;
    private long lastReportAt = SystemClock.elapsedRealtime();
    private String screen = "startup";
    private String mode = "webtoon";
    private String phase = "idle";
    private String workId = "none";
    private String episodeId = "none";
    private boolean viewerActive;

    private PerformanceMonitor(Activity activity) {
        decorView = activity.getWindow().getDecorView();
        jankStats = JankStats.createAndTrack(activity.getWindow(), this::onFrame);
        metricsState = PerformanceMetricsState.getHolderForHierarchy(decorView).getState();
        jankStats.setTrackingEnabled(false);
    }

    public static void attach(Activity activity) {
        if(activity == null)
            return;
        try {
            activity.getWindow().getDecorView();
            if(current != null)
                current.destroyInternal("reattach");
            current = new PerformanceMonitor(activity);
            updateSiteMode();
        } catch (Throwable throwable) {
            Log.w("PerfTrace", "jank_monitor_attach_failed", throwable);
        }
    }

    public static void pause() {
        if(current != null) {
            current.report("pause");
            current.jankStats.setTrackingEnabled(false);
        }
    }

    public static void resume() {
        if(current != null) {
            updateSiteMode();
            current.jankStats.setTrackingEnabled(current.viewerActive);
        }
    }

    public static void detach() {
        if(current == null)
            return;
        current.destroyInternal("detach");
        current = null;
    }

    public static void viewerStarted(String workId, String episodeId, String mode) {
        PerformanceMonitor monitor = current;
        if(monitor == null)
            return;
        monitor.runOnMain(() -> monitor.viewerStartedInternal(workId, episodeId, mode));
    }

    public static void viewerStopped(String reason) {
        PerformanceMonitor monitor = current;
        if(monitor == null)
            return;
        monitor.runOnMain(() -> monitor.viewerStoppedInternal(reason));
    }

    public static void frameState(int imageIndex, String direction, float velocityPxPerSecond,
                                  int viewportStart, int viewportEnd, int inFlightRequests,
                                  int activeDecodes, long bitmapBytes) {
        PerformanceMonitor monitor = current;
        if(monitor == null || !monitor.viewerActive)
            return;
        monitor.pendingFrameContext.set(new FrameContext(
                imageIndex,
                safe(direction, "idle"),
                velocityPxPerSecond,
                viewportStart,
                viewportEnd,
                Math.max(0, inFlightRequests),
                Math.max(0, activeDecodes),
                Math.max(0L, bitmapBytes)));
        monitor.scheduleStateUpdate();
    }

    public static void screen(String screen) {
        if(current == null)
            return;
        current.report("screen_change");
        current.screen = safe(screen, "unknown");
        current.phase = "idle";
        if(current.viewerActive) {
            current.putState("screen", current.screen);
            current.putState("phase", current.phase);
        }
        updateSiteMode();
    }

    public static void phase(String phase) {
        if(current == null)
            return;
        String nextPhase = safe(phase, "idle");
        if(!nextPhase.equals(current.phase))
            current.report("phase_change");
        current.phase = nextPhase;
        if(current.viewerActive)
            current.putState("phase", nextPhase);
        current.reportIfDue();
    }

    public static void updateSiteMode() {
        if(current == null || MainApplication.p == null)
            return;
        current.mode = MainApplication.p.getBaseMode() == MTitle.base_webtoon ? "webtoon" : "manhwa";
    }

    public static void reportNow(String reason) {
        if(current != null) {
            if(reason != null && reason.contains("scroll_idle") && current.totalFrames < 120)
                return;
            current.report(reason);
        }
    }

    private void onFrame(FrameData frameData) {
        if(!viewerActive)
            return;
        totalFrames++;
        if(frameData.isJank()) {
            jankyFrames++;
            consecutiveJankyFrames++;
            maxConsecutiveJankyFrames = Math.max(
                    maxConsecutiveJankyFrames, consecutiveJankyFrames);
        } else {
            consecutiveJankyFrames = 0L;
        }
        long durationNanos = Math.max(0L, frameData.getFrameDurationUiNanos());
        totalFrameDurationUiNanos += durationNanos;
        long durationMs = durationNanos / 1000000L;
        if(durationMs > worstFrameMs)
            worstFrameMs = durationMs;
        if(frameData.isJank())
            reportJankyFrame(frameData, durationMs);
        reportIfDue();
    }

    private void reportJankyFrame(FrameData frameData, long durationMs) {
        if(!PerfTrace.shouldLog())
            return;
        StringBuilder states = new StringBuilder();
        List<StateInfo> frameStates = frameData.getStates();
        for(int i = 0; i < frameStates.size(); i++) {
            if(i > 0)
                states.append(';');
            StateInfo state = frameStates.get(i);
            states.append(state.getKey()).append(':').append(state.getValue());
        }
        PerfTrace.mark("jank_frame",
                "durationMs=" + durationMs + ",states=" + states);
    }

    private void reportIfDue() {
        long now = SystemClock.elapsedRealtime();
        if(now - lastReportAt >= REPORT_INTERVAL_MS)
            report("interval");
    }

    private void report(String reason) {
        if(totalFrames <= 0)
            return;
        double jankPercent = (jankyFrames * 100.0d) / Math.max(1L, totalFrames);
        ViewerTelemetry.frameSummary(
                totalFrames,
                jankyFrames,
                worstFrameMs,
                totalFrameDurationUiNanos,
                maxConsecutiveJankyFrames,
                reason);
        String site = MainApplication.p != null && MainApplication.p.isNtkSite() ? "ntk" : "wfwf";
        PerfTrace.mark("jank_summary",
                "reason=" + safe(reason, "manual")
                        + ",screen=" + screen
                        + ",site=" + site
                        + ",mode=" + mode
                        + ",workId=" + workId
                        + ",episodeId=" + episodeId
                        + ",phase=" + phase
                        + ",totalFrames=" + totalFrames
                        + ",jankyFrames=" + jankyFrames
                        + ",jankPercent=" + String.format(Locale.US, "%.2f", jankPercent)
                        + ",worstFrameMs=" + worstFrameMs);
        // Keep session-cumulative counters. Intermediate reports are snapshots and the final
        // viewer-stop report must cover every opening/scroll/direction-change frame rather than
        // only the last reporting interval.
        lastReportAt = SystemClock.elapsedRealtime();
    }

    private void viewerStartedInternal(String nextWorkId, String nextEpisodeId, String nextMode) {
        report("viewer_restart");
        viewerActive = true;
        screen = "viewer";
        phase = "opening";
        workId = safe(nextWorkId, "unknown");
        episodeId = safe(nextEpisodeId, "unknown");
        mode = safe(nextMode, mode);
        putState("screen", screen);
        putState("work", workId);
        putState("episode", episodeId);
        putState("mode", mode);
        putState("phase", phase);
        totalFrames = 0L;
        jankyFrames = 0L;
        worstFrameMs = 0L;
        totalFrameDurationUiNanos = 0L;
        consecutiveJankyFrames = 0L;
        maxConsecutiveJankyFrames = 0L;
        lastReportAt = SystemClock.elapsedRealtime();
        jankStats.setTrackingEnabled(true);
    }

    private void viewerStoppedInternal(String reason) {
        if(!viewerActive)
            return;
        report(safe(reason, "viewer_stop"));
        viewerActive = false;
        jankStats.setTrackingEnabled(false);
        pendingFrameContext.set(null);
        removeViewerStates();
        screen = "episode";
        phase = "idle";
        workId = "none";
        episodeId = "none";
    }

    private void scheduleStateUpdate() {
        if(!stateUpdateScheduled.compareAndSet(false, true))
            return;
        decorView.postOnAnimation(() -> {
            stateUpdateScheduled.set(false);
            FrameContext context = pendingFrameContext.getAndSet(null);
            if(context == null || !viewerActive)
                return;
            putState("imageIndex", Integer.toString(context.imageIndex));
            putState("direction", context.direction);
            putState("velocity", velocityBucket(context.velocityPxPerSecond));
            putState("viewport", context.viewportStart + "-" + context.viewportEnd);
            putState("requests", Integer.toString(context.inFlightRequests));
            putState("decodes", Integer.toString(context.activeDecodes));
            putState("bitmapMiB", Long.toString(context.bitmapBytes / (1024L * 1024L)));
            PerfTrace.counter("ViewerActiveRequests", context.inFlightRequests);
            PerfTrace.counter("ViewerActiveDecodes", context.activeDecodes);
            PerfTrace.counter("ViewerBitmapBytes", context.bitmapBytes);
        });
    }

    private void destroyInternal(String reason) {
        viewerStoppedInternal(reason);
        jankStats.setTrackingEnabled(false);
        pendingFrameContext.set(null);
    }

    private void removeViewerStates() {
        String[] keys = new String[]{
                "screen", "work", "episode", "mode", "phase", "imageIndex", "direction",
                "velocity", "viewport", "requests", "decodes", "bitmapMiB"
        };
        for(String key : keys)
            metricsState.removeState(key);
    }

    private void putState(String key, String value) {
        metricsState.putState(key, value);
    }

    private void runOnMain(Runnable runnable) {
        if(Looper.myLooper() == Looper.getMainLooper())
            runnable.run();
        else
            mainHandler.post(runnable);
    }

    private static String velocityBucket(float velocity) {
        float absolute = Math.abs(velocity);
        if(absolute < 50f)
            return "idle";
        if(absolute < 500f)
            return "slow";
        if(absolute < 2500f)
            return "medium";
        return "fast";
    }

    private static String safe(String value, String fallback) {
        return value == null || value.length() == 0 ? fallback : value;
    }

    private static final class FrameContext {
        final int imageIndex;
        final String direction;
        final float velocityPxPerSecond;
        final int viewportStart;
        final int viewportEnd;
        final int inFlightRequests;
        final int activeDecodes;
        final long bitmapBytes;

        FrameContext(int imageIndex, String direction, float velocityPxPerSecond,
                     int viewportStart, int viewportEnd, int inFlightRequests,
                     int activeDecodes, long bitmapBytes) {
            this.imageIndex = imageIndex;
            this.direction = direction;
            this.velocityPxPerSecond = velocityPxPerSecond;
            this.viewportStart = viewportStart;
            this.viewportEnd = viewportEnd;
            this.inFlightRequests = inFlightRequests;
            this.activeDecodes = activeDecodes;
            this.bitmapBytes = bitmapBytes;
        }
    }
}
