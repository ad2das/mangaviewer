package ml.melun.mangaview.adapter;

import android.graphics.Bitmap;

import com.bumptech.glide.load.engine.DiskCacheStrategy;

import java.util.Set;

import ml.melun.mangaview.model.PageItem;

final class StripAdapterTestPolicy {
    private StripAdapterTestPolicy() {
    }

    static boolean shouldUsePreviewOnlyBind(boolean scrollBusy, boolean forceFullQuality) {
        return StripImagePolicy.shouldUsePreviewOnlyBind(scrollBusy, forceFullQuality);
    }

    static boolean shouldCacheDisplayedBitmap(String cacheKey, boolean holderActive, boolean bitmapUsable) {
        return holderActive && StripBitmapDisplayPolicy.shouldCacheDisplayedBitmap(cacheKey, bitmapUsable);
    }

    static boolean shouldRetryImageLoad(boolean released, String pageKey, int attempts) {
        return StripImageRetryState.shouldRetry(released, pageKey, attempts, StripAdapter.IMAGE_LOAD_RETRY_LIMIT);
    }

    static long imageRetryDelayMs(int nextAttempt) {
        return StripImageRetryState.retryDelayMs(nextAttempt);
    }

    static boolean isDisplayBitmapUsable(Bitmap bitmap) {
        return StripImageRenderController.isDisplayBitmapUsable(bitmap);
    }

    static int estimatedPageHeight(boolean autoCut, int side, int width,
                                   long pageHeightTotal, int pageHeightSampleCount) {
        return StripImageRenderController.estimatedPageHeight(autoCut, side, width,
                pageHeightTotal, pageHeightSampleCount);
    }

    static boolean shouldLogFirstVisible(boolean alreadyLogged) {
        return StripDisplayState.shouldLogFirstVisible(alreadyLogged);
    }

    static int previewWidth(int viewerWidth) {
        return StripImageRenderController.previewWidth(viewerWidth);
    }

    static DiskCacheStrategy viewerDiskCacheStrategy(boolean scrollBusy) {
        return StripImageRenderController.viewerDiskCacheStrategy(scrollBusy);
    }

    static Object imageModel(PageItem item) {
        return StripImageRequestPolicy.imageModel(item);
    }

    static boolean isAttachableImagePage(PageItem item) {
        return StripAttachmentController.isAttachableImagePage(item);
    }

    static boolean shouldRunBusyPreload(int previousPosition, int nextPosition, long elapsedMs) {
        return StripScrollPreloadController.shouldRunBusyPreload(previousPosition, nextPosition, elapsedMs);
    }

    static boolean shouldRetainTrackedPreloadForLoadedPage(String trackedKey, Set<String> activePageKeys) {
        return StripReusablePageStateTrimmer.shouldRetainTrackedPreloadForLoadedPage(trackedKey, activePageKeys);
    }

    static boolean shouldCacheDecodedBitmap(int bitmapSizeKb, int cacheSizeKb) {
        return StripDecodedBitmapCache.shouldCacheDecodedBitmap(bitmapSizeKb, cacheSizeKb);
    }
}
