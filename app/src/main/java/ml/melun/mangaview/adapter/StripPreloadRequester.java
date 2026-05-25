package ml.melun.mangaview.adapter;

import android.content.Context;
import android.graphics.Bitmap;

import com.bumptech.glide.Priority;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.CustomTarget;

import ml.melun.mangaview.model.PageItem;

final class StripPreloadRequester {
    interface Callbacks {
        boolean canStart();
        boolean isDataSave();
        Object imageModel(PageItem page);
        RequestOptions imageOptions(PageItem page);
        RequestOptions previewOptions(PageItem page);
        String preloadKey(PageItem page);
        String decodedCacheKey(PageItem page);
        String decodedPreviewCacheKey(PageItem page);
    }

    private final Context context;
    private final StripPreloadState preloadState;
    private final StripDecodedBitmapCache decodedBitmapCache;
    private final StripDecodedPreloadDelegate decodedPreloadDelegate;
    private final Callbacks callbacks;
    private final int decodedPreloadActiveLimit;

    StripPreloadRequester(Context context, StripPreloadState preloadState,
                          StripDecodedBitmapCache decodedBitmapCache,
                          StripDecodedPreloadDelegate decodedPreloadDelegate,
                          Callbacks callbacks, int decodedPreloadActiveLimit) {
        this.context = context;
        this.preloadState = preloadState;
        this.decodedBitmapCache = decodedBitmapCache;
        this.decodedPreloadDelegate = decodedPreloadDelegate;
        this.callbacks = callbacks;
        this.decodedPreloadActiveLimit = decodedPreloadActiveLimit;
    }

    void preloadPageSourceOnly(PageItem page, Priority priority) {
        if(!callbacks.canStart())
            return;
        String pageKey = callbacks.preloadKey(page);
        if(pageKey.length() == 0)
            return;
        String key = StripPageKeyPolicy.sourcePreloadRequestKey(pageKey);
        if(!preloadState.track(key))
            return;
        try {
            StripPreloadRequest.sourceOnly(context, callbacks.imageModel(page), priority,
                    () -> preloadState.untrack(key));
        } catch (IllegalArgumentException e) {
            preloadState.untrack(key);
        }
    }

    void preloadPageIntoDecodedCache(PageItem page, Priority priority, long generation, boolean preview) {
        if(!callbacks.canStart())
            return;
        int activeLimit = callbacks.isDataSave() ? 1 : decodedPreloadActiveLimit;
        if(activeLimit <= 0 || preloadState.decodedTargetCount() >= activeLimit) {
            preloadPageSourceOnly(page, priority == Priority.IMMEDIATE ? Priority.IMMEDIATE : Priority.HIGH);
            return;
        }
        String key = preview ? callbacks.decodedPreviewCacheKey(page) : callbacks.decodedCacheKey(page);
        String heightKey = callbacks.decodedCacheKey(page);
        if(key == null || key.length() == 0)
            return;
        StripDecodedBitmapCache.CachedBitmap cached = decodedBitmapCache.get(key);
        if(cached != null && cached.isUsable())
            return;
        if(cached != null)
            decodedBitmapCache.remove(key);
        String requestKey = StripPageKeyPolicy.decodedPreloadRequestKey(key);
        if(!preloadState.track(requestKey))
            return;
        CustomTarget<Bitmap> target = new StripDecodedPreloadTarget(requestKey, key, heightKey,
                generation, decodedPreloadDelegate);
        preloadState.putDecodedTarget(requestKey, target);
        if(!callbacks.canStart()) {
            preloadState.removeDecodedTarget(requestKey);
            preloadState.untrack(requestKey);
            return;
        }
        try {
            StripPreloadRequest.decoded(context, callbacks.imageModel(page), priority,
                    preview ? callbacks.previewOptions(page) : callbacks.imageOptions(page), target);
        } catch (IllegalArgumentException e) {
            preloadState.removeDecodedTarget(requestKey);
            preloadState.untrack(requestKey);
        }
    }
}
