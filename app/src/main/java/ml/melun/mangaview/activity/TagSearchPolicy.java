package ml.melun.mangaview.activity;

import java.util.Locale;

import ml.melun.mangaview.repository.MangaRepository;

final class TagSearchPolicy {
    static final int THUMBNAIL_PRELOAD_AHEAD = 6;
    static final int THUMBNAIL_PRELOAD_DELAY_MS = 80;
    static final int EPISODE_SNAPSHOT_PREFETCH_AHEAD = 8;
    static final int EPISODE_SNAPSHOT_PREFETCH_DELAY_MS = 0;
    static final int EPISODE_SNAPSHOT_PREFETCH_ACTIVE_LIMIT = 4;
    static final int EPISODE_SNAPSHOT_BACKGROUND_LIMIT = 36;
    static final int LOAD_MORE_THRESHOLD = 18;

    private TagSearchPolicy() {
    }

    static boolean shouldOpenCaptchaAfterSearchFailure(int result, Exception failure, boolean cloudflareChallengeSinceLoad) {
        if(result == 0)
            return false;
        if(failure != null)
            return MangaRepository.shouldReportSearchFailure(failure);
        return cloudflareChallengeSinceLoad;
    }

    static boolean shouldPrefetchEpisodeSnapshot(String sourceSite, boolean ntkPreference) {
        if(sourceSite == null || sourceSite.trim().length() == 0)
            return true;
        String source = sourceSite.trim().toLowerCase(Locale.ROOT);
        if("ntk".equals(source))
            return ntkPreference;
        if("wfwf".equals(source) || source.startsWith("wolf"))
            return !ntkPreference;
        return true;
    }
}

