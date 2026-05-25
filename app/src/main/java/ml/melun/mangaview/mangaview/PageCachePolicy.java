package ml.melun.mangaview.mangaview;

final class PageCachePolicy {
    private PageCachePolicy() {
    }

    static boolean isFresh(long cachedAt, long now, long ttlMillis) {
        return cachedAt <= now && now - cachedAt < ttlMillis;
    }

    static boolean isUsableForColdStart(long cachedAt, long now, long coldStartTtlMillis) {
        return cachedAt <= now && now - cachedAt <= coldStartTtlMillis;
    }

    static boolean shouldServeColdStartImmediately(boolean allowColdStartStale,
                                                   CustomHttpClient.FetchMode fetchMode,
                                                   boolean hasCachedPage,
                                                   boolean fresh) {
        return allowColdStartStale && hasCachedPage && !fresh && fetchMode != CustomHttpClient.FetchMode.CACHE_ONLY;
    }

    static boolean shouldWaitForActiveLoad(boolean hasStaleCache) {
        return !hasStaleCache;
    }
}
