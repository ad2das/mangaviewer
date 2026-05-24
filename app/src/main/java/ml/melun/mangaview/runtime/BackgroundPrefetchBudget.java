package ml.melun.mangaview.runtime;

import java.util.LinkedHashSet;
import java.util.Set;

import android.os.SystemClock;

public final class BackgroundPrefetchBudget {
    private static final int EPISODE_SNAPSHOT_LIMIT = 2;
    private static final long EPISODE_SNAPSHOT_FAILURE_SUPPRESS_MS = 30000L;
    private static final long USER_NAVIGATION_SUPPRESS_MS = 4500L;
    private static final Set<String> ACTIVE_EPISODE_SNAPSHOTS = new LinkedHashSet<>();
    private static volatile long suppressNonCriticalUntilMs = 0L;

    private BackgroundPrefetchBudget() {
    }

    public static boolean tryAcquireEpisodeSnapshot(String key) {
        if(key == null || key.length() == 0)
            return false;
        if(isNonCriticalPrefetchSuppressed())
            return false;
        synchronized (ACTIVE_EPISODE_SNAPSHOTS) {
            if(ACTIVE_EPISODE_SNAPSHOTS.contains(key))
                return false;
            if(ACTIVE_EPISODE_SNAPSHOTS.size() >= EPISODE_SNAPSHOT_LIMIT)
                return false;
            ACTIVE_EPISODE_SNAPSHOTS.add(key);
            return true;
        }
    }

    public static void releaseEpisodeSnapshot(String key) {
        if(key == null || key.length() == 0)
            return;
        synchronized (ACTIVE_EPISODE_SNAPSHOTS) {
            ACTIVE_EPISODE_SNAPSHOTS.remove(key);
        }
    }

    public static void suppressNonCriticalPrefetch(long durationMs) {
        suppressNonCriticalUntilMs = Math.max(suppressNonCriticalUntilMs,
                nowMs() + Math.max(0L, durationMs));
    }

    public static boolean isNonCriticalPrefetchSuppressed() {
        return nonCriticalPrefetchDelayMs() > 0L;
    }

    public static void recordEpisodeSnapshotFailure() {
        suppressNonCriticalPrefetch(EPISODE_SNAPSHOT_FAILURE_SUPPRESS_MS);
    }

    public static void suppressForUserNavigation() {
        suppressNonCriticalPrefetch(USER_NAVIGATION_SUPPRESS_MS);
    }

    public static long nonCriticalPrefetchDelayMs() {
        return Math.max(0L, suppressNonCriticalUntilMs - nowMs());
    }

    private static long nowMs() {
        try {
            return SystemClock.uptimeMillis();
        } catch (RuntimeException e) {
            return System.currentTimeMillis();
        }
    }

    static void clearEpisodeSnapshotsForTest() {
        synchronized (ACTIVE_EPISODE_SNAPSHOTS) {
            ACTIVE_EPISODE_SNAPSHOTS.clear();
        }
        suppressNonCriticalUntilMs = 0L;
    }

    static int activeEpisodeSnapshotsForTest() {
        synchronized (ACTIVE_EPISODE_SNAPSHOTS) {
            return ACTIVE_EPISODE_SNAPSHOTS.size();
        }
    }

    static long userNavigationSuppressMsForTest() {
        return USER_NAVIGATION_SUPPRESS_MS;
    }
}
