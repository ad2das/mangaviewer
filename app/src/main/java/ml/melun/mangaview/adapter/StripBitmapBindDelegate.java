package ml.melun.mangaview.adapter;

import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.View;

import ml.melun.mangaview.glide.ViewerWarmupManager;
import ml.melun.mangaview.model.PageItem;

final class StripBitmapBindDelegate implements StripBitmapBindTarget.Delegate {
    interface Callbacks {
        boolean isActiveHolder(StripImageViewHolder holder, PageItem item,
                               StripBitmapBindTarget target, String pageKey, int bindGeneration);
        boolean isBitmapUsable(Bitmap bitmap);
        void handleImageLoadFailed(StripImageViewHolder holder, PageItem item,
                                   String pageKey, int bindGeneration);
        void bindPreviewBitmap(StripImageViewHolder holder, PageItem item, String pageKey, Bitmap bitmap);
        void bindBitmap(StripImageViewHolder holder, PageItem item, String pageKey, Bitmap bitmap);
        void markDisplayedAndPreload(StripImageViewHolder holder, PageItem item, String pageKey);
        void applyKnownHeight(StripImageViewHolder holder, PageItem item, String pageKey);
        void clearFrameBitmap(StripImageViewHolder holder);
        void scheduleImageRetry(StripImageViewHolder holder, PageItem item,
                                String pageKey, int bindGeneration);
    }

    private final StripImageViewHolder holder;
    private final PageItem item;
    private final String pageKey;
    private final int bindGeneration;
    private final StripPreviewState previewState;
    private final StripImageRetryState retryState;
    private final Callbacks callbacks;

    StripBitmapBindDelegate(StripImageViewHolder holder, PageItem item, String pageKey,
                            int bindGeneration, StripPreviewState previewState,
                            StripImageRetryState retryState, Callbacks callbacks) {
        this.holder = holder;
        this.item = item;
        this.pageKey = pageKey;
        this.bindGeneration = bindGeneration;
        this.previewState = previewState;
        this.retryState = retryState;
        this.callbacks = callbacks;
    }

    @Override
    public boolean isActive(StripBitmapBindTarget target) {
        return callbacks.isActiveHolder(holder, item, target, pageKey, bindGeneration);
    }

    @Override
    public void clearRetrySuccess() {
        retryState.clearSuccess(pageKey);
    }

    @Override
    public boolean isBitmapUsable(Bitmap bitmap) {
        return callbacks.isBitmapUsable(bitmap);
    }

    @Override
    public void handleLoadFailed() {
        callbacks.handleImageLoadFailed(holder, item, pageKey, bindGeneration);
    }

    @Override
    public void bindPreview(Bitmap bitmap) {
        callbacks.bindPreviewBitmap(holder, item, pageKey, bitmap);
    }

    @Override
    public void clearPreviewOnly() {
        previewState.clearPreviewOnly(pageKey);
    }

    @Override
    public void bindFull(Bitmap bitmap) {
        callbacks.bindBitmap(holder, item, pageKey, bitmap);
    }

    @Override
    public void hideRefresh() {
        holder.refresh.setVisibility(View.GONE);
    }

    @Override
    public void logFirstBindIfNeeded(long bindStartMs) {
        if(item.index == 0)
            ViewerWarmupManager.logMetric("viewer_first_bind_ms", SystemClock.elapsedRealtime() - bindStartMs);
    }

    @Override
    public void markDisplayedAndPreload() {
        callbacks.markDisplayedAndPreload(holder, item, pageKey);
    }

    @Override
    public void applyKnownHeight() {
        callbacks.applyKnownHeight(holder, item, pageKey);
    }

    @Override
    public void clearFrame() {
        callbacks.clearFrameBitmap(holder);
    }

    @Override
    public void scheduleRetry() {
        callbacks.scheduleImageRetry(holder, item, pageKey, bindGeneration);
    }
}
