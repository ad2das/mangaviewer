package ml.melun.mangaview.activity;

import android.content.Intent;

final class MainActivityStartupPolicy {
    static final long DEFERRED_TASKS_DELAY_MS = 12_000L;
    static final long UPDATE_CHECK_DELAY_MS = 5 * 60_000L;
    static final long VISIBLE_WARMUP_SUPPRESS_MS = 15_000L;
    static final long CONTINUE_WARMUP_SUPPRESS_MS = 0L;
    static final long PERFORMANCE_MONITOR_DELAY_MS = 16_000L;
    static final long NTK_CAPTCHA_CHECK_DELAY_MS = 0L;
    static final long WFWF_DOMAIN_REFRESH_DELAY_MS = 8_000L;
    static final long NTK_CAPTCHA_CHECK_MIN_INTERVAL_MS = 10_000L;

    private MainActivityStartupPolicy() {
    }

    static boolean shouldFinishDuplicateLauncher(boolean isTaskRoot, String action, boolean hasLauncherCategory) {
        return !isTaskRoot
                && Intent.ACTION_MAIN.equals(action)
                && hasLauncherCategory;
    }
}

