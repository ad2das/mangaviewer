package ml.melun.mangaview.activity;

final class EpisodeWarmupPolicy {
    static final long VISIBLE_IDLE_DELAY_MS = 80L;
    static final long REFRESH_AFTER_CACHE_PROBE_MS = 160L;
    static final long INITIAL_VIEWER_TARGET_DELAY_MS = 0L;
    static final long INITIAL_VISIBLE_DELAY_MS = 0L;
    static final long NTK_INITIAL_VISIBLE_DELAY_MS = 0L;
    static final long WFWF_INITIAL_VISIBLE_DELAY_MS = 0L;
    static final int VISIBLE_AHEAD = 3;

    private EpisodeWarmupPolicy() {
    }

    static int visibleLimit(boolean dataSave, boolean aggressiveAllowed, boolean ntkSite) {
        if(dataSave)
            return 1;
        if(ntkSite)
            return aggressiveAllowed ? 5 : 4;
        return aggressiveAllowed ? 5 : 4;
    }

    static long initialViewerTargetDelay(boolean ntkSite) {
        return ntkSite ? NTK_INITIAL_VISIBLE_DELAY_MS : INITIAL_VIEWER_TARGET_DELAY_MS;
    }

    static long initialVisibleDelay(boolean ntkSite) {
        return initialVisibleDelay(ntkSite, false);
    }

    static long initialVisibleDelay(boolean ntkSite, boolean wfwfSite) {
        if(wfwfSite)
            return WFWF_INITIAL_VISIBLE_DELAY_MS;
        return ntkSite ? NTK_INITIAL_VISIBLE_DELAY_MS : INITIAL_VISIBLE_DELAY_MS;
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

