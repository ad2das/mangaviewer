package ml.melun.mangaview.benchmark;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

import ml.melun.mangaview.runtime.ViewerTelemetry;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Benchmark-build-only, one-shot-per-source exact p0-p4 signal to Macrobenchmark. */
public final class BenchmarkAdjacentCommitSignal {
    public static final String PREFS_NAME = "ntk_benchmark_adjacent_commit_signal";
    public static final String ACTION_PREFIX =
            "ml.melun.mangaview.macrobenchmark.P0_COMMIT.";
    public static final String MACRO_PACKAGE = "ml.melun.mangaview.macrobenchmark";
    public static final String EXTRA_NONCE = "nonce";
    public static final String EXTRA_CASE_ID = "caseId";
    public static final String EXTRA_EPISODE_PATH = "episodePath";
    public static final String EXTRA_SOURCE_INDEX = "sourceIndex";
    public static final String EXTRA_PRESENTED_AT_NANOS = "presentedAtNanos";
    public static final String EXTRA_SENDER_AT_NANOS = "senderAtNanos";
    public static final String EXTRA_VIEWER_GENERATION = "viewerGeneration";
    public static final String EXTRA_PHASE = "phase";
    public static final String EXTRA_SEMANTIC_PUBLISHED_AT_NANOS = "semanticPublishedAtNanos";
    public static final String EXTRA_FORWARD_BOUNDARY_REACHED_AT_NANOS =
            "forwardBoundaryReachedAtNanos";
    public static final String EXTRA_MOTION_ENDED_AT_NANOS = "motionEndedAtNanos";
    public static final String EXTRA_RUNWAY_READY_AT_NANOS = "runwayReadyAtNanos";
    public static final String EXTRA_ADJACENT_WORK_STARTED_AT_NANOS =
            "adjacentWorkStartedAtNanos";
    public static final String EXTRA_RUNWAY_PAGE_COUNT = "runwayPageCount";
    public static final String EXTRA_TOTAL_PAGE_COUNT = "totalPageCount";
    public static final String PHASE_PHYSICAL_COMMIT = "PHYSICAL_COMMIT";
    public static final String PHASE_SEMANTIC_COMMIT = "SEMANTIC_COMMIT";
    public static final String PHASE_PHYSICAL_MOTION_IDLE = "PHYSICAL_MOTION_IDLE";
    public static final String PHASE_RUNWAY_READY = "RUNWAY_READY";
    public static final String PREF_ACTION = "action";
    public static final String PREF_NONCE = "nonce";
    public static final String PREF_CASE_ID = "caseId";
    public static final String PREF_EXPECTED_EPISODE_PATH = "expectedEpisodePath";

    private static final String TAG = "BenchmarkP0Signal";
    private static final int REQUIRED_PHYSICAL_PAGES = 5;
    private static final int PREPARED_RUNWAY_PAGES = 5;
    private static final AtomicInteger SENT_MASK = new AtomicInteger(0);
    private static final AtomicInteger SEMANTIC_SENT_MASK = new AtomicInteger(0);
    private static final AtomicBoolean RUNWAY_READY_SENT = new AtomicBoolean(false);
    private static final AtomicLong LAST_MOTION_IDLE_AT_NANOS = new AtomicLong(0L);
    private static final long[] PRESENTED_AT_NANOS = new long[REQUIRED_PHYSICAL_PAGES];
    private static volatile Context applicationContext;
    private static volatile Config config;
    private static volatile RunwayReadyState runwayReadyState;

    private BenchmarkAdjacentCommitSignal() {
    }

    /** Loads the tiny seed before viewer timing begins; publish performs no disk I/O. */
    public static void initialize(Context context) {
        if(context == null) return;
        Context app = context.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String action = clean(prefs.getString(PREF_ACTION, ""));
        String nonce = clean(prefs.getString(PREF_NONCE, ""));
        String caseId = clean(prefs.getString(PREF_CASE_ID, ""));
        String expectedPath = clean(prefs.getString(PREF_EXPECTED_EPISODE_PATH, ""));
        applicationContext = app;
        SENT_MASK.set(0);
        SEMANTIC_SENT_MASK.set(0);
        RUNWAY_READY_SENT.set(false);
        LAST_MOTION_IDLE_AT_NANOS.set(0L);
        runwayReadyState = null;
        synchronized(PRESENTED_AT_NANOS) {
            java.util.Arrays.fill(PRESENTED_AT_NANOS, 0L);
        }
        config = action.equals(ACTION_PREFIX + nonce)
                && nonce.matches("[0-9a-f]{32}")
                && !caseId.isEmpty()
                && isEpisodePath(expectedPath)
                ? new Config(action, nonce, caseId, expectedPath)
                : null;
    }

    /** Lets the render path avoid even constructing a candidate outside an armed benchmark. */
    public static boolean isEnabled() {
        return config != null;
    }

    /** Called only after an identity-exact native frame has already been presented. */
    public static void publish(
            String episodePath,
            int sourceIndex,
            long presentedAtNanos,
            long viewerGeneration) {
        Config current = config;
        Context context = applicationContext;
        String normalizedPath = clean(episodePath);
        if(current == null || context == null || sourceIndex < 0
                || sourceIndex >= REQUIRED_PHYSICAL_PAGES
                || presentedAtNanos <= 0L || viewerGeneration <= 0L
                || !current.expectedEpisodePath.equals(normalizedPath)) {
            return;
        }
        int sourceBit = 1 << sourceIndex;
        int observedMask;
        do {
            observedMask = SENT_MASK.get();
            if((observedMask & sourceBit) != 0) return;
        } while(!SENT_MASK.compareAndSet(observedMask, observedMask | sourceBit));
        synchronized(PRESENTED_AT_NANOS) {
            PRESENTED_AT_NANOS[sourceIndex] = presentedAtNanos;
        }
        long senderAtNanos = SystemClock.elapsedRealtimeNanos();
        Intent signal = new Intent(current.action)
                .setPackage(MACRO_PACKAGE)
                .putExtra(EXTRA_PHASE, PHASE_PHYSICAL_COMMIT)
                .putExtra(EXTRA_NONCE, current.nonce)
                .putExtra(EXTRA_CASE_ID, current.caseId)
                .putExtra(EXTRA_EPISODE_PATH, normalizedPath)
                .putExtra(EXTRA_SOURCE_INDEX, sourceIndex)
                .putExtra(EXTRA_PRESENTED_AT_NANOS, presentedAtNanos)
                .putExtra(EXTRA_SENDER_AT_NANOS, senderAtNanos)
                .putExtra(EXTRA_VIEWER_GENERATION, viewerGeneration);
        RunwayReadyState ready = runwayReadyState;
        if(ready != null
                && ready.viewerGeneration == viewerGeneration
                && ready.episodePath.equals(normalizedPath)) {
            signal.putExtra(EXTRA_ADJACENT_WORK_STARTED_AT_NANOS, ready.workStartedAtNanos)
                    .putExtra(EXTRA_RUNWAY_READY_AT_NANOS, ready.readyAtNanos)
                    .putExtra(EXTRA_RUNWAY_PAGE_COUNT, ready.pageCount)
                    .putExtra(EXTRA_TOTAL_PAGE_COUNT, ready.totalPageCount);
        }
        try {
            context.sendBroadcast(signal);
        } catch(RuntimeException failure) {
            // A later strict Activity callback may still deliver the same exact proof.
            int sentMask;
            do {
                sentMask = SENT_MASK.get();
            } while(!SENT_MASK.compareAndSet(sentMask, sentMask & ~sourceBit));
            synchronized(PRESENTED_AT_NANOS) {
                if(PRESENTED_AT_NANOS[sourceIndex] == presentedAtNanos)
                    PRESENTED_AT_NANOS[sourceIndex] = 0L;
            }
            Log.e(TAG, "Unable to publish benchmark adjacent commit signal", failure);
        }
    }

    /**
     * Called on the main thread only after the exact actual state and both stable accessibility
     * nodes have synchronously published. A semantic signal can never invent or replace physical
     * evidence: its presented timestamp must equal this process's already-sent physical phase.
     */
    public static void publishSemanticCommit(
            String episodePath,
            int sourceIndex,
            long presentedAtNanos,
            long viewerGeneration) {
        Config current = config;
        Context context = applicationContext;
        String normalizedPath = clean(episodePath);
        if(current == null || context == null || sourceIndex < 0
                || sourceIndex >= REQUIRED_PHYSICAL_PAGES
                || presentedAtNanos <= 0L || viewerGeneration <= 0L
                || !current.expectedEpisodePath.equals(normalizedPath)) {
            return;
        }
        synchronized(PRESENTED_AT_NANOS) {
            if(PRESENTED_AT_NANOS[sourceIndex] != presentedAtNanos) return;
        }
        long forwardBoundaryReachedAtNanos =
                ViewerTelemetry.currentForwardBoundaryReachedAtNanos();
        if(sourceIndex == 0 && (forwardBoundaryReachedAtNanos <= 0L
                || forwardBoundaryReachedAtNanos > presentedAtNanos)) return;
        int sourceBit = 1 << sourceIndex;
        int observedMask;
        do {
            observedMask = SEMANTIC_SENT_MASK.get();
            if((observedMask & sourceBit) != 0) return;
        } while(!SEMANTIC_SENT_MASK.compareAndSet(observedMask, observedMask | sourceBit));
        long semanticPublishedAtNanos = SystemClock.elapsedRealtimeNanos();
        long senderAtNanos = SystemClock.elapsedRealtimeNanos();
        Intent signal = new Intent(current.action)
                .setPackage(MACRO_PACKAGE)
                .putExtra(EXTRA_PHASE, PHASE_SEMANTIC_COMMIT)
                .putExtra(EXTRA_NONCE, current.nonce)
                .putExtra(EXTRA_CASE_ID, current.caseId)
                .putExtra(EXTRA_EPISODE_PATH, normalizedPath)
                .putExtra(EXTRA_SOURCE_INDEX, sourceIndex)
                .putExtra(EXTRA_PRESENTED_AT_NANOS, presentedAtNanos)
                .putExtra(EXTRA_SEMANTIC_PUBLISHED_AT_NANOS, semanticPublishedAtNanos)
                .putExtra(
                        EXTRA_FORWARD_BOUNDARY_REACHED_AT_NANOS,
                        forwardBoundaryReachedAtNanos)
                .putExtra(EXTRA_SENDER_AT_NANOS, senderAtNanos)
                .putExtra(EXTRA_VIEWER_GENERATION, viewerGeneration);
        try {
            context.sendBroadcast(signal);
        } catch(RuntimeException failure) {
            int sentMask;
            do {
                sentMask = SEMANTIC_SENT_MASK.get();
            } while(!SEMANTIC_SENT_MASK.compareAndSet(sentMask, sentMask & ~sourceBit));
            Log.e(TAG, "Unable to publish benchmark adjacent semantic commit signal", failure);
        }
    }

    /**
     * Publishes the exact app-owned instant when the prepared p0-p4 runway reaches residency.
     * This phase is independent from accessibility frame coalescing and never proves that a page
     * was physically shown; the independent physical p0-p4 phases retain that responsibility.
     */
    public static void publishRunwayReady(
            String episodePath,
            int pageCount,
            int totalPageCount,
            long adjacentWorkStartedAtNanos,
            long readyAtNanos,
            long viewerGeneration) {
        Config current = config;
        Context context = applicationContext;
        String normalizedPath = clean(episodePath);
        if(current == null || context == null
                || pageCount != PREPARED_RUNWAY_PAGES || totalPageCount < pageCount
                || adjacentWorkStartedAtNanos <= 0L
                || adjacentWorkStartedAtNanos > readyAtNanos
                || readyAtNanos <= 0L || viewerGeneration <= 0L
                || !current.expectedEpisodePath.equals(normalizedPath)
                ) {
            return;
        }
        if(!RUNWAY_READY_SENT.compareAndSet(false, true)) {
            return;
        }
        runwayReadyState = new RunwayReadyState(
                normalizedPath,
                pageCount,
                totalPageCount,
                adjacentWorkStartedAtNanos,
                readyAtNanos,
                viewerGeneration);
        long senderAtNanos = SystemClock.elapsedRealtimeNanos();
        Intent signal = new Intent(current.action)
                .setPackage(MACRO_PACKAGE)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                .putExtra(EXTRA_PHASE, PHASE_RUNWAY_READY)
                .putExtra(EXTRA_NONCE, current.nonce)
                .putExtra(EXTRA_CASE_ID, current.caseId)
                .putExtra(EXTRA_EPISODE_PATH, normalizedPath)
                .putExtra(EXTRA_ADJACENT_WORK_STARTED_AT_NANOS, adjacentWorkStartedAtNanos)
                .putExtra(EXTRA_RUNWAY_READY_AT_NANOS, readyAtNanos)
                .putExtra(EXTRA_RUNWAY_PAGE_COUNT, pageCount)
                .putExtra(EXTRA_TOTAL_PAGE_COUNT, totalPageCount)
                .putExtra(EXTRA_SENDER_AT_NANOS, senderAtNanos)
                .putExtra(EXTRA_VIEWER_GENERATION, viewerGeneration);
        try {
            context.sendBroadcast(signal);
        } catch(RuntimeException failure) {
            RUNWAY_READY_SENT.compareAndSet(true, false);
            Log.e(TAG, "Unable to publish benchmark adjacent runway-ready signal", failure);
        }
    }

    /** Publishes the app-owned end of a real pointer/drag/fling trace, never a harness guess. */
    public static void publishPhysicalMotionIdle(
            long motionEndedAtNanos,
            long viewerGeneration) {
        Config current = config;
        Context context = applicationContext;
        if(current == null || context == null || motionEndedAtNanos <= 0L
                || viewerGeneration <= 0L) {
            return;
        }
        long previous;
        do {
            previous = LAST_MOTION_IDLE_AT_NANOS.get();
            if(previous >= motionEndedAtNanos) return;
        } while(!LAST_MOTION_IDLE_AT_NANOS.compareAndSet(previous, motionEndedAtNanos));
        Intent signal = new Intent(current.action)
                .setPackage(MACRO_PACKAGE)
                .putExtra(EXTRA_PHASE, PHASE_PHYSICAL_MOTION_IDLE)
                .putExtra(EXTRA_NONCE, current.nonce)
                .putExtra(EXTRA_CASE_ID, current.caseId)
                .putExtra(EXTRA_MOTION_ENDED_AT_NANOS, motionEndedAtNanos)
                .putExtra(EXTRA_SENDER_AT_NANOS, SystemClock.elapsedRealtimeNanos())
                .putExtra(EXTRA_VIEWER_GENERATION, viewerGeneration);
        try {
            context.sendBroadcast(signal);
        } catch(RuntimeException failure) {
            LAST_MOTION_IDLE_AT_NANOS.compareAndSet(motionEndedAtNanos, previous);
            Log.e(TAG, "Unable to publish benchmark physical-motion idle signal", failure);
        }
    }

    private static boolean isEpisodePath(String path) {
        return path.startsWith("/webtoon/") || path.startsWith("/manhwa/");
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class Config {
        final String action;
        final String nonce;
        final String caseId;
        final String expectedEpisodePath;

        Config(String action, String nonce, String caseId, String expectedEpisodePath) {
            this.action = action;
            this.nonce = nonce;
            this.caseId = caseId;
            this.expectedEpisodePath = expectedEpisodePath;
        }
    }

    private static final class RunwayReadyState {
        final String episodePath;
        final int pageCount;
        final int totalPageCount;
        final long workStartedAtNanos;
        final long readyAtNanos;
        final long viewerGeneration;

        RunwayReadyState(
                String episodePath,
                int pageCount,
                int totalPageCount,
                long workStartedAtNanos,
                long readyAtNanos,
                long viewerGeneration) {
            this.episodePath = episodePath;
            this.pageCount = pageCount;
            this.totalPageCount = totalPageCount;
            this.workStartedAtNanos = workStartedAtNanos;
            this.readyAtNanos = readyAtNanos;
            this.viewerGeneration = viewerGeneration;
        }
    }
}
