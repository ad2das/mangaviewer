package ml.melun.mangaview.adapter;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.SystemClock;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.request.RequestOptions;

import ml.melun.mangaview.glide.ViewerWarmupManager;
import ml.melun.mangaview.model.PageItem;

final class StripImageBinder {
    interface Callbacks {
        PageItem itemAt(int position);
        Object imageModel(PageItem item);
        String pageBindKey(PageItem item);
        String decodedCacheKey(PageItem item);
        String decodedPreviewCacheKey(PageItem item);
        RequestOptions imageOptions(PageItem item);
        RequestOptions previewOptions(PageItem item);
        boolean isScrollBusy();
        boolean isReleased();
        boolean autoPromotePreviewFullQuality();
        boolean autoCut();
        boolean reverse();
        int width();
        boolean isEpisodePreviewActive(PageItem item);
        boolean isHolderStillBound(StripImageViewHolder holder, PageItem item, String pageKey);
        boolean isActiveHolder(StripImageViewHolder holder, PageItem item,
                               StripBitmapBindTarget target, String pageKey, int bindGeneration);
        boolean isBitmapUsable(Bitmap bitmap);
        void clearImageTarget(StripImageViewHolder holder);
        void applyKnownHeight(StripImageViewHolder holder, PageItem item, String pageKey);
        void clearFrameBitmap(StripImageViewHolder holder);
        void bindBitmap(StripImageViewHolder holder, PageItem item, String pageKey, Bitmap bitmap);
        void bindPreviewBitmap(StripImageViewHolder holder, PageItem item, String pageKey, Bitmap bitmap);
        void markDisplayedAndPreload(StripImageViewHolder holder, PageItem item, String pageKey);
        void logNextPageCacheHitOnce(PageItem item);
        void schedulePreviewFullRebind(long extraDelayMs);
        long previewFullRebindDelayMs(PageItem item);
        void notifyItemChanged(int position);
    }

    private final Handler mainHandler;
    private final StripDecodedBitmapCache decodedBitmapCache;
    private final StripPreviewState previewState;
    private final StripImageRetryState imageRetryState;
    private final Callbacks callbacks;

    StripImageBinder(Handler mainHandler, StripDecodedBitmapCache decodedBitmapCache,
                     StripPreviewState previewState, StripImageRetryState imageRetryState,
                     Callbacks callbacks) {
        this.mainHandler = mainHandler;
        this.decodedBitmapCache = decodedBitmapCache;
        this.previewState = previewState;
        this.imageRetryState = imageRetryState;
        this.callbacks = callbacks;
    }

    void glideBind(StripImageViewHolder holder, int position) {
        PageItem item = callbacks.itemAt(position);
        Object url = callbacks.imageModel(item);
        String pageKey = callbacks.pageBindKey(item);
        boolean samePageRebind = pageKey.equals(holder.boundPageKey);
        if(!samePageRebind)
            callbacks.clearImageTarget(holder);
        int bindGeneration = ++holder.bindGeneration;
        holder.boundPageKey = pageKey;
        holder.bindStartedAtMs = SystemClock.elapsedRealtime();
        callbacks.applyKnownHeight(holder, item, pageKey);
        boolean forceFullQuality = previewState.consumeFullQualityPromotion(pageKey);
        boolean preferPreview = StripImagePolicy.shouldUsePreviewOnlyBind(callbacks.isScrollBusy(), forceFullQuality)
                || (callbacks.isEpisodePreviewActive(item) && !forceFullQuality);
        if(preferPreview) {
            if(bindCachedPreviewIfAvailable(holder, item, pageKey, bindGeneration)) {
                callbacks.markDisplayedAndPreload(holder, item, pageKey);
                return;
            }
            startGlideBind(holder, item, url, pageKey, bindGeneration, samePageRebind, true);
            return;
        }
        if(bindCachedFullIfAvailable(holder, item, pageKey, bindGeneration))
            return;
        if(bindCachedPreviewIfAvailable(holder, item, pageKey, bindGeneration)) {
            callbacks.markDisplayedAndPreload(holder, item, pageKey);
            return;
        }
        startGlideBind(holder, item, url, pageKey, bindGeneration, samePageRebind, false);
    }

    private boolean bindCachedFullIfAvailable(StripImageViewHolder holder, PageItem item,
                                              String pageKey, int bindGeneration) {
        String cacheKey = callbacks.decodedCacheKey(item);
        StripDecodedBitmapCache.CachedBitmap cached = decodedBitmapCache.get(cacheKey);
        if(cached != null && cached.isUsable() && callbacks.isHolderStillBound(holder, item, pageKey)) {
            callbacks.logNextPageCacheHitOnce(item);
            imageRetryState.clearSuccess(pageKey);
            previewState.clearPreviewOnly(pageKey);
            if(!callbacks.isBitmapUsable(cached.bitmap)) {
                decodedBitmapCache.remove(cacheKey);
                handleImageLoadFailed(holder, item, pageKey, bindGeneration);
                return true;
            }
            callbacks.bindBitmap(holder, item, pageKey, cached.bitmap);
            holder.refresh.setVisibility(View.GONE);
            callbacks.markDisplayedAndPreload(holder, item, pageKey);
            return true;
        }
        if(cached != null)
            decodedBitmapCache.remove(cacheKey);
        Bitmap warmupCached = ViewerWarmupManager.getDecodedBitmap(item, callbacks.autoCut(),
                callbacks.reverse(), callbacks.width(), false);
        if(warmupCached != null && !warmupCached.isRecycled()
                && callbacks.isHolderStillBound(holder, item, pageKey)) {
            callbacks.logNextPageCacheHitOnce(item);
            imageRetryState.clearSuccess(pageKey);
            previewState.clearPreviewOnly(pageKey);
            if(!callbacks.isBitmapUsable(warmupCached)) {
                handleImageLoadFailed(holder, item, pageKey, bindGeneration);
                return true;
            }
            callbacks.bindBitmap(holder, item, pageKey, warmupCached);
            holder.refresh.setVisibility(View.GONE);
            cacheDisplayedBitmap(cacheKey, warmupCached);
            callbacks.markDisplayedAndPreload(holder, item, pageKey);
            return true;
        }
        return false;
    }

    private boolean bindCachedPreviewIfAvailable(StripImageViewHolder holder, PageItem item,
                                                 String pageKey, int bindGeneration) {
        String previewCacheKey = callbacks.decodedPreviewCacheKey(item);
        StripDecodedBitmapCache.CachedBitmap previewCached = decodedBitmapCache.get(previewCacheKey);
        if(previewCached != null && previewCached.isUsable()
                && callbacks.isHolderStillBound(holder, item, pageKey)) {
            imageRetryState.clearSuccess(pageKey);
            if(!callbacks.isBitmapUsable(previewCached.bitmap)) {
                decodedBitmapCache.remove(previewCacheKey);
                handleImageLoadFailed(holder, item, pageKey, bindGeneration);
                return true;
            }
            callbacks.bindPreviewBitmap(holder, item, pageKey, previewCached.bitmap);
            holder.refresh.setVisibility(View.GONE);
            return true;
        }
        if(previewCached != null)
            decodedBitmapCache.remove(previewCacheKey);
        Bitmap warmupPreview = ViewerWarmupManager.getDecodedBitmap(item, callbacks.autoCut(),
                callbacks.reverse(), callbacks.width(), true);
        if(warmupPreview != null && !warmupPreview.isRecycled()
                && callbacks.isHolderStillBound(holder, item, pageKey)) {
            imageRetryState.clearSuccess(pageKey);
            if(!callbacks.isBitmapUsable(warmupPreview)) {
                handleImageLoadFailed(holder, item, pageKey, bindGeneration);
                return true;
            }
            decodedBitmapCache.put(previewCacheKey, warmupPreview);
            callbacks.bindPreviewBitmap(holder, item, pageKey, warmupPreview);
            holder.refresh.setVisibility(View.GONE);
            return true;
        }
        return false;
    }

    private void startGlideBind(StripImageViewHolder holder, PageItem item, Object url, String pageKey,
                                int bindGeneration, boolean samePageRebind, boolean previewOnly) {
        if(!samePageRebind)
            callbacks.clearFrameBitmap(holder);
        holder.refresh.setVisibility(View.GONE);
        startBitmapBind(holder, item, url, pageKey, bindGeneration, previewOnly);
    }

    private void startBitmapBind(StripImageViewHolder holder, PageItem item, Object url, String pageKey,
                                 int bindGeneration, boolean previewOnly) {
        if(previewOnly)
            previewState.markPreviewOnly(pageKey);
        else
            previewState.clearPreviewOnly(pageKey);
        long bindStart = SystemClock.elapsedRealtime();
        StripBitmapBindTarget imageTarget = new StripBitmapBindTarget(previewOnly, bindStart,
                new StripBitmapBindDelegate(holder, item, pageKey, bindGeneration, previewState,
                        imageRetryState, bitmapBindCallbacks()));
        holder.imageTarget = imageTarget;
        try {
            StripImageBindRequest.into(holder.frame, url, callbacks.imageOptions(item),
                    callbacks.previewOptions(item), previewOnly, imageTarget);
            if(previewOnly && !callbacks.isScrollBusy() && callbacks.autoPromotePreviewFullQuality())
                callbacks.schedulePreviewFullRebind(callbacks.previewFullRebindDelayMs(item));
        } catch (IllegalArgumentException e) {
            if(previewOnly)
                previewState.clearPreviewOnly(pageKey);
            handleImageLoadFailed(holder, item, pageKey, bindGeneration);
        }
    }

    private StripBitmapBindDelegate.Callbacks bitmapBindCallbacks() {
        return new StripBitmapBindDelegate.Callbacks() {
            @Override public boolean isActiveHolder(StripImageViewHolder holder, PageItem item,
                                                    StripBitmapBindTarget target, String pageKey, int bindGeneration) {
                return callbacks.isActiveHolder(holder, item, target, pageKey, bindGeneration);
            }
            @Override public boolean isBitmapUsable(Bitmap bitmap) { return callbacks.isBitmapUsable(bitmap); }
            @Override public void handleImageLoadFailed(StripImageViewHolder holder, PageItem item,
                                                        String pageKey, int bindGeneration) {
                StripImageBinder.this.handleImageLoadFailed(holder, item, pageKey, bindGeneration);
            }
            @Override public void bindPreviewBitmap(StripImageViewHolder holder, PageItem item,
                                                    String pageKey, Bitmap bitmap) {
                callbacks.bindPreviewBitmap(holder, item, pageKey, bitmap);
            }
            @Override public void bindBitmap(StripImageViewHolder holder, PageItem item,
                                             String pageKey, Bitmap bitmap) {
                callbacks.bindBitmap(holder, item, pageKey, bitmap);
            }
            @Override public void markDisplayedAndPreload(StripImageViewHolder holder,
                                                          PageItem item, String pageKey) {
                callbacks.markDisplayedAndPreload(holder, item, pageKey);
            }
            @Override public void applyKnownHeight(StripImageViewHolder holder,
                                                   PageItem item, String pageKey) {
                callbacks.applyKnownHeight(holder, item, pageKey);
            }
            @Override public void clearFrameBitmap(StripImageViewHolder holder) {
                callbacks.clearFrameBitmap(holder);
            }
            @Override public void scheduleImageRetry(StripImageViewHolder holder,
                                                     PageItem item, String pageKey, int bindGeneration) {
                StripImageBinder.this.scheduleImageRetry(holder, item, pageKey, bindGeneration);
            }
        };
    }

    private void handleImageLoadFailed(StripImageViewHolder holder, PageItem item,
                                       String pageKey, int bindGeneration) {
        callbacks.applyKnownHeight(holder, item, pageKey);
        callbacks.clearFrameBitmap(holder);
        if(scheduleImageRetry(holder, item, pageKey, bindGeneration)) {
            holder.refresh.setVisibility(View.GONE);
            return;
        }
        holder.refresh.setVisibility(View.VISIBLE);
    }

    private boolean scheduleImageRetry(StripImageViewHolder holder, PageItem item,
                                       String pageKey, int bindGeneration) {
        int nextAttempt = imageRetryState.nextAttempt(pageKey, callbacks.isReleased());
        if(nextAttempt < 0)
            return false;
        ViewerWarmupManager.logMetric("viewer_image_retry", nextAttempt);
        long delayMs = StripImageRetryState.retryDelayMs(nextAttempt);
        mainHandler.postDelayed(() -> {
            if(callbacks.isReleased() || !callbacks.isHolderStillBound(holder, item, pageKey)
                    || holder.bindGeneration != bindGeneration)
                return;
            int position = holder.getAdapterPosition();
            if(position != RecyclerView.NO_POSITION)
                callbacks.notifyItemChanged(position);
        }, delayMs);
        return true;
    }

    private void cacheDisplayedBitmap(String cacheKey, Bitmap bitmap) {
        if(!StripBitmapDisplayPolicy.shouldCacheDisplayedBitmap(cacheKey, callbacks.isBitmapUsable(bitmap)))
            return;
        decodedBitmapCache.put(cacheKey, bitmap);
    }
}
