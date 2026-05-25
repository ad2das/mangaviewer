package ml.melun.mangaview.adapter;

import android.graphics.Bitmap;

import com.bumptech.glide.load.engine.DiskCacheStrategy;

import java.util.Set;

import ml.melun.mangaview.model.PageItem;

final class StripAdapterTestAccess {
    private StripAdapterTestAccess() {
    }

    static boolean shouldUsePreviewOnlyBind(boolean scrollBusy, boolean forceFullQuality) {
        return StripAdapterTestPolicy.shouldUsePreviewOnlyBind(scrollBusy, forceFullQuality);
    }

    static boolean shouldCacheDisplayedBitmap(String cacheKey, boolean holderActive, boolean bitmapUsable) {
        return StripAdapterTestPolicy.shouldCacheDisplayedBitmap(cacheKey, holderActive, bitmapUsable);
    }

    static boolean shouldRetryImageLoad(boolean released, String pageKey, int attempts) {
        return StripAdapterTestPolicy.shouldRetryImageLoad(released, pageKey, attempts);
    }

    static long imageRetryDelayMs(int nextAttempt) {
        return StripAdapterTestPolicy.imageRetryDelayMs(nextAttempt);
    }

    static boolean isDisplayBitmapUsable(Bitmap bitmap) {
        return StripAdapterTestPolicy.isDisplayBitmapUsable(bitmap);
    }

    static int estimatedPageHeight(boolean autoCut, int side, int width, long pageHeightTotal, int pageHeightSampleCount) {
        return StripAdapterTestPolicy.estimatedPageHeight(autoCut, side, width, pageHeightTotal, pageHeightSampleCount);
    }

    static boolean shouldLogFirstVisible(boolean alreadyLogged) {
        return StripAdapterTestPolicy.shouldLogFirstVisible(alreadyLogged);
    }

    static int previewWidth(int viewerWidth) {
        return StripAdapterTestPolicy.previewWidth(viewerWidth);
    }

    static DiskCacheStrategy viewerDiskCacheStrategy(boolean scrollBusy) {
        return StripAdapterTestPolicy.viewerDiskCacheStrategy(scrollBusy);
    }

    static Object imageModel(PageItem item) {
        return StripAdapterTestPolicy.imageModel(item);
    }

    static boolean isAttachableImagePage(PageItem item) {
        return StripAdapterTestPolicy.isAttachableImagePage(item);
    }

    static int preloadAheadCount() {
        return StripImagePolicy.PRELOAD_AHEAD_COUNT;
    }

    static int initialPreloadAheadCount() {
        return StripImagePolicy.INITIAL_PRELOAD_AHEAD_COUNT;
    }

    static int decodedPreloadActiveLimit() {
        return StripImagePolicy.DECODED_PRELOAD_ACTIVE_LIMIT;
    }

    static long scrollIdlePreloadDelayMs() {
        return StripImagePolicy.SCROLL_IDLE_PRELOAD_DELAY_MS;
    }

    static long scrollIdleHeightCorrectionDelayMs() {
        return StripImagePolicy.SCROLL_IDLE_HEIGHT_CORRECTION_DELAY_MS;
    }

    static boolean shouldRunBusyPreload(int previousPosition, int nextPosition, long elapsedMs) {
        return StripAdapterTestPolicy.shouldRunBusyPreload(previousPosition, nextPosition, elapsedMs);
    }

    static boolean startsPreloadFromBind() {
        return !StripImagePolicy.RENDER_ONLY_PRELOADS;
    }

    static boolean startsPreloadFromScrollAnchor() {
        return !StripImagePolicy.RENDER_ONLY_PRELOADS;
    }

    static boolean shouldRetainTrackedPreloadForLoadedPage(String trackedKey, Set<String> activePageKeys) {
        return StripAdapterTestPolicy.shouldRetainTrackedPreloadForLoadedPage(trackedKey, activePageKeys);
    }

    static boolean shouldCacheDecodedBitmap(int bitmapSizeKb, int cacheSizeKb) {
        return StripAdapterTestPolicy.shouldCacheDecodedBitmap(bitmapSizeKb, cacheSizeKb);
    }
}
