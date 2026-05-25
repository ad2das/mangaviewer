package ml.melun.mangaview.adapter;

import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;

final class StripDecodedPreloadTarget extends CustomTarget<Bitmap> {
    interface Delegate {
        void removeTarget(String requestKey);
        boolean isGenerationCurrent(long generation);
        void untrack(String requestKey);
        boolean canUseResource(Bitmap resource);
        Bitmap copyForDisplay(Bitmap resource);
        void cacheBitmap(String key, Bitmap bitmap);
        void rememberHeight(String heightKey, Bitmap bitmap);
    }

    private final String requestKey;
    private final String cacheKey;
    private final String heightKey;
    private final long generation;
    private final Delegate delegate;

    StripDecodedPreloadTarget(String requestKey, String cacheKey, String heightKey,
                              long generation, Delegate delegate) {
        this.requestKey = requestKey;
        this.cacheKey = cacheKey;
        this.heightKey = heightKey;
        this.generation = generation;
        this.delegate = delegate;
    }

    @Override
    public void onResourceReady(@NonNull Bitmap resource, @Nullable Transition<? super Bitmap> transition) {
        delegate.removeTarget(requestKey);
        if(!delegate.isGenerationCurrent(generation)) {
            delegate.untrack(requestKey);
            return;
        }
        if(!delegate.canUseResource(resource))
            return;
        Bitmap displayBitmap = delegate.copyForDisplay(resource);
        if(displayBitmap == null)
            return;
        delegate.cacheBitmap(cacheKey, displayBitmap);
        delegate.rememberHeight(heightKey, displayBitmap);
    }

    @Override
    public void onLoadCleared(@Nullable Drawable placeholder) {
        delegate.removeTarget(requestKey);
    }

    @Override
    public void onLoadFailed(@Nullable Drawable errorDrawable) {
        delegate.removeTarget(requestKey);
        delegate.untrack(requestKey);
    }
}
