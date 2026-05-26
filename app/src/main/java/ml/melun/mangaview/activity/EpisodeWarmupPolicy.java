package ml.melun.mangaview.activity;

final class EpisodeWarmupPolicy {
    static final long VIEWER_TARGET_IDLE_DELAY_MS = 80L;
    static final long REFRESH_AFTER_CACHE_PROBE_MS = 160L;
    static final long INITIAL_VIEWER_TARGET_DELAY_MS = 0L;

    private EpisodeWarmupPolicy() {
    }

    static long initialViewerTargetDelay(boolean ntkSite) {
        return INITIAL_VIEWER_TARGET_DELAY_MS;
    }

    static boolean shouldDirectWarmupNtkViewerPage(boolean ntkPreference, boolean ntkClient, String episodePath) {
        return (ntkPreference || ntkClient)
                && episodePath != null
                && episodePath.trim().length() > 0;
    }

    static boolean shouldPreloadNtkFirstFrameAfterDirectWarmup(boolean directWarmupSucceeded) {
        return directWarmupSucceeded;
    }
}

