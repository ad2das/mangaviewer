package ml.melun.mangaview.repository;

public final class CachePolicy {
    public static final long HOME_TTL_MS = 24 * 60 * 60 * 1000L;
    public static final long SEARCH_TTL_MS = 10 * 60 * 1000L;
    public static final long EPISODE_TTL_MS = 12 * 60 * 60 * 1000L;
    public static final long VIEWER_IMAGE_URL_TTL_MS = 30 * 60 * 1000L;

    private CachePolicy() {
    }

    public static boolean isFresh(long savedAt, long ttlMs) {
        return isFresh(savedAt, ttlMs, System.currentTimeMillis());
    }

    static boolean isFreshForTest(long savedAt, long ttlMs, long now) {
        return isFresh(savedAt, ttlMs, now);
    }

    private static boolean isFresh(long savedAt, long ttlMs, long now) {
        return savedAt > 0 && savedAt <= now && now - savedAt <= ttlMs;
    }
}
