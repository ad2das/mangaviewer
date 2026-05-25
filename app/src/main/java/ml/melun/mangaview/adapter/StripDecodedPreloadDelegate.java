package ml.melun.mangaview.adapter;

import android.graphics.Bitmap;

final class StripDecodedPreloadDelegate implements StripDecodedPreloadTarget.Delegate {
    interface Callbacks {
        boolean isContextDestroyed();
        Bitmap copyForDisplay(Bitmap resource);
        void rememberHeight(String heightKey, Bitmap bitmap);
    }

    private final StripPreloadState preloadState;
    private final StripPreloadScheduleState scheduleState;
    private final StripDecodedBitmapCache bitmapCache;
    private final Callbacks callbacks;

    StripDecodedPreloadDelegate(StripPreloadState preloadState,
                                StripPreloadScheduleState scheduleState,
                                StripDecodedBitmapCache bitmapCache,
                                Callbacks callbacks) {
        this.preloadState = preloadState;
        this.scheduleState = scheduleState;
        this.bitmapCache = bitmapCache;
        this.callbacks = callbacks;
    }

    @Override
    public void removeTarget(String requestKey) {
        preloadState.removeDecodedTarget(requestKey);
    }

    @Override
    public boolean isGenerationCurrent(long generation) {
        return generation == scheduleState.generation();
    }

    @Override
    public void untrack(String requestKey) {
        preloadState.untrack(requestKey);
    }

    @Override
    public boolean canUseResource(Bitmap resource) {
        return resource != null && !resource.isRecycled() && !callbacks.isContextDestroyed();
    }

    @Override
    public Bitmap copyForDisplay(Bitmap resource) {
        return callbacks.copyForDisplay(resource);
    }

    @Override
    public void cacheBitmap(String key, Bitmap bitmap) {
        bitmapCache.put(key, bitmap);
    }

    @Override
    public void rememberHeight(String heightKey, Bitmap bitmap) {
        callbacks.rememberHeight(heightKey, bitmap);
    }
}
