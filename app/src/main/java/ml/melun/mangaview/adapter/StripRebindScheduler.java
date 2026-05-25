package ml.melun.mangaview.adapter;

import android.os.Handler;
import android.os.SystemClock;

import java.util.List;
import java.util.Set;

import ml.melun.mangaview.model.PageItem;

final class StripRebindScheduler {
    interface Callbacks {
        boolean isReleased();
        boolean isScrollBusy();
        List<Object> items();
        String pageKey(PageItem page);
        void notifyHeightChanged(int position);
        void notifyFullRebind(int position);
    }

    private final Handler mainHandler;
    private final StripPageHeightTracker pageHeightTracker;
    private final StripPreviewState previewState;
    private final StripPreloadScheduleState scheduleState;
    private final Callbacks callbacks;
    private final long heightCorrectionDelayMs;
    private final int previewFullRebindBatch;
    private final int previewFullRebindRadius;
    private final long previewFullRebindDelayMs;
    private final long previewFullStableIdleMs;

    StripRebindScheduler(Handler mainHandler, StripPageHeightTracker pageHeightTracker,
                         StripPreviewState previewState, StripPreloadScheduleState scheduleState,
                         Callbacks callbacks, long heightCorrectionDelayMs, int previewFullRebindBatch,
                         int previewFullRebindRadius, long previewFullRebindDelayMs,
                         long previewFullStableIdleMs) {
        this.mainHandler = mainHandler;
        this.pageHeightTracker = pageHeightTracker;
        this.previewState = previewState;
        this.scheduleState = scheduleState;
        this.callbacks = callbacks;
        this.heightCorrectionDelayMs = heightCorrectionDelayMs;
        this.previewFullRebindBatch = previewFullRebindBatch;
        this.previewFullRebindRadius = previewFullRebindRadius;
        this.previewFullRebindDelayMs = previewFullRebindDelayMs;
        this.previewFullStableIdleMs = previewFullStableIdleMs;
    }

    void schedulePendingHeightCorrections() {
        if(callbacks.isReleased() || !pageHeightTracker.hasPendingCorrections()
                || scheduleState.isHeightCorrectionScheduled())
            return;
        scheduleState.setHeightCorrectionScheduled(true);
        mainHandler.postDelayed(() -> {
            scheduleState.setHeightCorrectionScheduled(false);
            flushPendingHeightCorrections();
        }, heightCorrectionDelayMs);
    }

    void schedulePreviewFullRebind(long extraDelayMs) {
        if(callbacks.isReleased() || callbacks.isScrollBusy() || !previewState.hasPreviewOnlyImages()
                || previewState.isFullRebindScheduled())
            return;
        previewState.setFullRebindScheduled(true);
        long stableIdleDelayMs = stableIdleRemainingMs();
        mainHandler.postDelayed(() -> {
            previewState.setFullRebindScheduled(false);
            flushPreviewFullRebinds();
        }, Math.max(Math.max(previewFullRebindDelayMs, stableIdleDelayMs), Math.max(0L, extraDelayMs)));
    }

    long previewFullRebindDelayMs(PageItem item) {
        long remaining = previewEpisodeRemainingMs(item);
        return Math.max(Math.max(previewFullRebindDelayMs, stableIdleRemainingMs()), remaining);
    }

    boolean isEpisodePreviewActive(PageItem item) {
        return previewState.isEpisodePreviewActive(item, SystemClock.uptimeMillis());
    }

    long previewEpisodeRemainingMs(PageItem item) {
        return previewState.previewEpisodeRemainingMs(item, SystemClock.uptimeMillis());
    }

    private void flushPendingHeightCorrections() {
        List<Object> items = callbacks.items();
        if(callbacks.isReleased() || callbacks.isScrollBusy() || !pageHeightTracker.hasPendingCorrections()
                || items == null)
            return;
        Set<String> keys = pageHeightTracker.drainPendingCorrections();
        int anchor = scheduleState.anchorForItemCount(items.size());
        int start = Math.max(0, anchor - 6);
        int end = Math.min(items.size() - 1, anchor + 6);
        int notified = notifyHeightCorrectionsInRange(items, keys, start, end, 8);
        if(notified == 0)
            notifyHeightCorrectionsInRange(items, keys, 0, items.size() - 1, 4);
    }

    private int notifyHeightCorrectionsInRange(List<Object> items, Set<String> keys, int start, int end, int limit) {
        int notified = 0;
        for(int i = start; i <= end && notified < limit; i++) {
            Object item = items.get(i);
            if(item instanceof PageItem && keys.contains(callbacks.pageKey((PageItem) item))) {
                callbacks.notifyHeightChanged(i);
                notified++;
            }
        }
        return notified;
    }

    private void flushPreviewFullRebinds() {
        List<Object> items = callbacks.items();
        if(callbacks.isReleased() || callbacks.isScrollBusy() || !previewState.hasPreviewOnlyImages()
                || items == null)
            return;
        long stableIdleDelayMs = stableIdleRemainingMs();
        if(stableIdleDelayMs > 0L) {
            schedulePreviewFullRebind(stableIdleDelayMs);
            return;
        }
        Set<String> keys = previewState.snapshotPreviewOnlyKeys();
        int anchor = scheduleState.anchorForItemCount(items.size());
        int start = Math.max(0, anchor - previewFullRebindRadius);
        int end = Math.min(items.size() - 1, anchor + previewFullRebindRadius);
        int notified = notifyPreviewFullRebindsInRange(items, keys, start, end, previewFullRebindBatch);
        if(notified > 0 && hasPreviewOnlyImageNearAnchor(items, anchor))
            schedulePreviewFullRebind(previewFullRebindDelayMs);
    }

    private int notifyPreviewFullRebindsInRange(List<Object> items, Set<String> keys, int start, int end, int limit) {
        int notified = 0;
        for(int i = start; i <= end && notified < limit; i++) {
            Object item = items.get(i);
            if(!(item instanceof PageItem))
                continue;
            PageItem page = (PageItem) item;
            if(isEpisodePreviewActive(page))
                continue;
            String key = callbacks.pageKey(page);
            if(!keys.contains(key))
                continue;
            previewState.promoteToFullQuality(key);
            callbacks.notifyFullRebind(i);
            notified++;
        }
        return notified;
    }

    private boolean hasPreviewOnlyImageNearAnchor(List<Object> items, int anchor) {
        if(items == null || !previewState.hasPreviewOnlyImages())
            return false;
        int normalizedAnchor = Math.max(0, Math.min(anchor, items.size() - 1));
        int start = Math.max(0, normalizedAnchor - previewFullRebindRadius);
        int end = Math.min(items.size() - 1, normalizedAnchor + previewFullRebindRadius);
        for(int i = start; i <= end; i++) {
            Object item = items.get(i);
            if(item instanceof PageItem && previewState.isPreviewOnly(callbacks.pageKey((PageItem) item)))
                return true;
        }
        return false;
    }

    private long stableIdleRemainingMs() {
        return scheduleState.stableIdleRemainingMs(SystemClock.uptimeMillis(), previewFullStableIdleMs);
    }
}
