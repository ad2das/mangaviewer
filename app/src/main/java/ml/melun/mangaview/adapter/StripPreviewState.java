package ml.melun.mangaview.adapter;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import ml.melun.mangaview.model.PageItem;

final class StripPreviewState {
    private final Map<String, Long> previewEpisodesUntil = new HashMap<>();
    private final Set<String> previewOnlyImageLoads = new LinkedHashSet<>();
    private final Set<String> fullQualityPromotionKeys = new LinkedHashSet<>();
    private boolean fullRebindScheduled = false;

    void markEpisodePreviewOnly(PageItem page, long nowMs, long durationMs) {
        if(page == null || page.manga == null || durationMs <= 0L)
            return;
        String key = PageItem.episodeKey(page.manga);
        if(key.length() > 0)
            previewEpisodesUntil.put(key, nowMs + durationMs);
    }

    boolean consumeFullQualityPromotion(String pageKey) {
        return fullQualityPromotionKeys.remove(pageKey);
    }

    boolean isPreviewOnly(String pageKey) {
        return pageKey != null && previewOnlyImageLoads.contains(pageKey);
    }

    void markPreviewOnly(String pageKey) {
        if(pageKey != null && pageKey.length() > 0)
            previewOnlyImageLoads.add(pageKey);
    }

    void clearPreviewOnly(String pageKey) {
        previewOnlyImageLoads.remove(pageKey);
    }

    boolean hasPreviewOnlyImages() {
        return !previewOnlyImageLoads.isEmpty();
    }

    Set<String> snapshotPreviewOnlyKeys() {
        return new LinkedHashSet<>(previewOnlyImageLoads);
    }

    void promoteToFullQuality(String pageKey) {
        previewOnlyImageLoads.remove(pageKey);
        if(pageKey != null && pageKey.length() > 0)
            fullQualityPromotionKeys.add(pageKey);
    }

    boolean isFullRebindScheduled() {
        return fullRebindScheduled;
    }

    void setFullRebindScheduled(boolean scheduled) {
        fullRebindScheduled = scheduled;
    }

    boolean isEpisodePreviewActive(PageItem item, long nowMs) {
        return previewEpisodeRemainingMs(item, nowMs) > 0L;
    }

    long previewEpisodeRemainingMs(PageItem item, long nowMs) {
        if(item == null || item.manga == null)
            return 0L;
        String episodeKey = PageItem.episodeKey(item.manga);
        Long untilMs = previewEpisodesUntil.get(episodeKey);
        if(untilMs == null)
            return 0L;
        long remainingMs = untilMs - nowMs;
        if(remainingMs <= 0L) {
            previewEpisodesUntil.remove(episodeKey);
            return 0L;
        }
        return remainingMs;
    }

    void retain(Set<String> activePageKeys, Set<String> activeEpisodeKeys) {
        previewOnlyImageLoads.retainAll(activePageKeys);
        fullQualityPromotionKeys.retainAll(activePageKeys);
        previewEpisodesUntil.keySet().retainAll(activeEpisodeKeys);
    }

    void clear() {
        fullRebindScheduled = false;
        previewOnlyImageLoads.clear();
        fullQualityPromotionKeys.clear();
        previewEpisodesUntil.clear();
    }
}
