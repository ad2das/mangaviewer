package ml.melun.mangaview.adapter;

import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;

final class StripBitmapBindTarget extends CustomTarget<Bitmap> {
    interface Delegate {
        boolean isActive(StripBitmapBindTarget target);
        void clearRetrySuccess();
        boolean isBitmapUsable(Bitmap bitmap);
        void handleLoadFailed();
        void bindPreview(Bitmap bitmap);
        void clearPreviewOnly();
        void bindFull(Bitmap bitmap);
        void hideRefresh();
        void logFirstBindIfNeeded(long bindStartMs);
        void markDisplayedAndPreload();
        void applyKnownHeight();
        void clearFrame();
        void scheduleRetry();
    }

    private final boolean previewOnly;
    private final long bindStartMs;
    private final Delegate delegate;
    private boolean resourceDelivered = false;

    StripBitmapBindTarget(boolean previewOnly, long bindStartMs, Delegate delegate) {
        this.previewOnly = previewOnly;
        this.bindStartMs = bindStartMs;
        this.delegate = delegate;
    }

    @Override
    public void onResourceReady(@NonNull Bitmap resource, @Nullable Transition<? super Bitmap> transition) {
        if(!delegate.isActive(this))
            return;
        delegate.clearRetrySuccess();
        if(!delegate.isBitmapUsable(resource)) {
            delegate.handleLoadFailed();
            return;
        }
        resourceDelivered = true;
        if(previewOnly)
            delegate.bindPreview(resource);
        else {
            delegate.clearPreviewOnly();
            delegate.bindFull(resource);
        }
        delegate.hideRefresh();
        delegate.logFirstBindIfNeeded(bindStartMs);
        delegate.markDisplayedAndPreload();
    }

    @Override
    public void onLoadCleared(@Nullable Drawable placeholder) {
        if(!delegate.isActive(this))
            return;
        delegate.applyKnownHeight();
        delegate.clearFrame();
        delegate.hideRefresh();
    }

    @Override
    public void onLoadFailed(@Nullable Drawable errorDrawable) {
        if(!delegate.isActive(this))
            return;
        if(previewOnly)
            delegate.clearPreviewOnly();
        if(resourceDelivered) {
            delegate.hideRefresh();
            delegate.scheduleRetry();
            return;
        }
        delegate.handleLoadFailed();
    }
}
