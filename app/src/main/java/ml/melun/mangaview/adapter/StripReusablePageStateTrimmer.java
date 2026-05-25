package ml.melun.mangaview.adapter;

import android.graphics.Bitmap;

import com.bumptech.glide.request.target.CustomTarget;

import java.util.List;
import java.util.Map;
import java.util.Set;

import ml.melun.mangaview.mangaview.Decoder;

final class StripReusablePageStateTrimmer {
    interface Callbacks {
        boolean isContextDestroyed();
        void clearDecodedTarget(CustomTarget<Bitmap> target);
    }

    private final StripPreloadState preloadState;
    private final StripDisplayState displayState;
    private final StripImageRetryState imageRetryState;
    private final StripPreviewState previewState;
    private final StripPageHeightTracker pageHeightTracker;
    private final StripDecodedBitmapCache decodedBitmapCache;
    private final Map<String, Decoder> decoders;
    private final Callbacks callbacks;

    StripReusablePageStateTrimmer(StripPreloadState preloadState,
                                  StripDisplayState displayState,
                                  StripImageRetryState imageRetryState,
                                  StripPreviewState previewState,
                                  StripPageHeightTracker pageHeightTracker,
                                  StripDecodedBitmapCache decodedBitmapCache,
                                  Map<String, Decoder> decoders,
                                  Callbacks callbacks) {
        this.preloadState = preloadState;
        this.displayState = displayState;
        this.imageRetryState = imageRetryState;
        this.previewState = previewState;
        this.pageHeightTracker = pageHeightTracker;
        this.decodedBitmapCache = decodedBitmapCache;
        this.decoders = decoders;
        this.callbacks = callbacks;
    }

    void clearAll() {
        preloadState.clearTrackedRequests();
        imageRetryState.clear();
        previewState.clear();
        decodedBitmapCache.evictAll();
        pageHeightTracker.clear();
        decoders.clear();
        clearDecodedPreloadTargets();
    }

    void trimToLoadedItems(List<Object> items, boolean autoCut, boolean reverse, int width) {
        if(items == null || items.size() == 0) {
            clearAll();
            return;
        }
        Set<String> activePageKeys = StripPageKeyPolicy.activePageKeys(items, autoCut, reverse, width);
        if(activePageKeys.isEmpty()) {
            clearAll();
            return;
        }
        Set<String> activePreloadKeys = StripPageKeyPolicy.activePreloadKeys(items, autoCut, reverse, width,
                StripImagePolicy.previewWidth(width));
        preloadState.retainTrackedRequests(activePreloadKeys);
        displayState.retainDisplayed(activePageKeys);
        imageRetryState.retain(activePageKeys);
        previewState.retain(activePageKeys, StripPageKeyPolicy.activeEpisodeKeys(items));
        pageHeightTracker.retain(activePageKeys);
        decoders.clear();
        trimDecodedPreloadTargets(activePreloadKeys);
    }

    void cancelDecodedPreload(String pageKey) {
        if(pageKey == null || pageKey.length() == 0)
            return;
        String requestKey = StripPageKeyPolicy.decodedPreloadRequestKey(pageKey);
        CustomTarget<Bitmap> target = preloadState.removeDecodedTarget(requestKey);
        if(target == null)
            return;
        preloadState.untrack(requestKey);
        if(callbacks.isContextDestroyed())
            return;
        callbacks.clearDecodedTarget(target);
    }

    private void trimDecodedPreloadTargets(Set<String> activePreloadKeys) {
        List<CustomTarget<Bitmap>> staleTargets = preloadState.removeDecodedTargetsNotIn(activePreloadKeys);
        clearTargets(staleTargets);
    }

    private void clearDecodedPreloadTargets() {
        List<CustomTarget<Bitmap>> targets = preloadState.drainDecodedTargets();
        clearTargets(targets);
    }

    private void clearTargets(List<CustomTarget<Bitmap>> targets) {
        if(targets.isEmpty() || callbacks.isContextDestroyed())
            return;
        for(CustomTarget<Bitmap> target : targets)
            callbacks.clearDecodedTarget(target);
    }

    static boolean shouldRetainTrackedPreloadForLoadedPage(String trackedKey, Set<String> activePageKeys) {
        return StripPageKeyPolicy.activePreloadKeys(activePageKeys).contains(trackedKey);
    }
}
