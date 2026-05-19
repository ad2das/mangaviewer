package ml.melun.mangaview.runtime;

import android.app.Activity;
import android.os.SystemClock;
import android.util.Log;

import androidx.metrics.performance.FrameData;
import androidx.metrics.performance.JankStats;

import java.util.Locale;

import ml.melun.mangaview.MainApplication;
import ml.melun.mangaview.mangaview.MTitle;

public final class PerformanceMonitor {
    private static final long REPORT_INTERVAL_MS = 5000L;
    private static PerformanceMonitor current;

    private final JankStats jankStats;
    private long totalFrames = 0;
    private long jankyFrames = 0;
    private long worstFrameMs = 0;
    private long lastReportAt = SystemClock.elapsedRealtime();
    private String screen = "startup";
    private String mode = "webtoon";
    private String phase = "idle";

    private PerformanceMonitor(Activity activity) {
        jankStats = JankStats.createAndTrack(activity.getWindow(), this::onFrame);
        jankStats.setTrackingEnabled(true);
    }

    public static void attach(Activity activity) {
        if(activity == null || !PerfTrace.shouldLog())
            return;
        try {
            activity.getWindow().getDecorView();
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
            current.jankStats.setTrackingEnabled(true);
        }
    }

    public static void screen(String screen) {
        if(current == null)
            return;
        current.report("screen_change");
        current.screen = safe(screen, "unknown");
        current.phase = "idle";
        updateSiteMode();
    }

    public static void phase(String phase) {
        if(current == null)
            return;
        current.phase = safe(phase, "idle");
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
        totalFrames++;
        if(frameData.isJank())
            jankyFrames++;
        long durationMs = frameData.getFrameDurationUiNanos() / 1000000L;
        if(durationMs > worstFrameMs)
            worstFrameMs = durationMs;
        reportIfDue();
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
        String site = MainApplication.p != null && MainApplication.p.isNtkSite() ? "ntk" : "wfwf";
        PerfTrace.mark("jank_summary",
                "reason=" + safe(reason, "manual")
                        + ",screen=" + screen
                        + ",site=" + site
                        + ",mode=" + mode
                        + ",phase=" + phase
                        + ",totalFrames=" + totalFrames
                        + ",jankyFrames=" + jankyFrames
                        + ",jankPercent=" + String.format(Locale.US, "%.2f", jankPercent)
                        + ",worstFrameMs=" + worstFrameMs);
        totalFrames = 0;
        jankyFrames = 0;
        worstFrameMs = 0;
        lastReportAt = SystemClock.elapsedRealtime();
    }

    private static String safe(String value, String fallback) {
        return value == null || value.length() == 0 ? fallback : value;
    }
}
