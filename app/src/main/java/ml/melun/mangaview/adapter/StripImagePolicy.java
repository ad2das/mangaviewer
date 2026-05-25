package ml.melun.mangaview.adapter;

import androidx.recyclerview.widget.RecyclerView;

final class StripImagePolicy {
    static final int PRELOAD_AHEAD_COUNT = 6;
    static final int DATA_SAVE_PRELOAD_AHEAD_COUNT = 2;
    static final int INITIAL_PRELOAD_AHEAD_COUNT = 4;
    static final int DECODED_PRELOAD_ACTIVE_LIMIT = 0;
    static final long SCROLL_IDLE_PRELOAD_DELAY_MS = 650L;
    static final long SCROLL_IDLE_HEIGHT_CORRECTION_DELAY_MS = 1000L;
    static final boolean RENDER_ONLY_PRELOADS = false;

    private static final int SCROLL_BUSY_PRELOAD_MIN_DISTANCE = 4;
    private static final long SCROLL_BUSY_PRELOAD_MIN_INTERVAL_MS = 120L;
    private static final int PREVIEW_MIN_WIDTH = 360;
    private static final int PREVIEW_WIDTH_NUMERATOR = 1;
    private static final int PREVIEW_WIDTH_DENOMINATOR = 3;

    private StripImagePolicy() {
    }

    static boolean shouldUsePreviewOnlyBind(boolean scrollBusy, boolean forceFullQuality) {
        return scrollBusy && !forceFullQuality;
    }

    static int previewWidth(int viewerWidth) {
        int width = Math.max(1, viewerWidth);
        int scaled = (width * PREVIEW_WIDTH_NUMERATOR) / PREVIEW_WIDTH_DENOMINATOR;
        return Math.min(width, Math.max(PREVIEW_MIN_WIDTH, scaled));
    }

    static boolean shouldRunBusyPreload(int previousPosition, int nextPosition, long elapsedMs) {
        if(nextPosition == RecyclerView.NO_POSITION)
            return false;
        if(previousPosition == RecyclerView.NO_POSITION)
            return true;
        if(Math.abs(nextPosition - previousPosition) >= SCROLL_BUSY_PRELOAD_MIN_DISTANCE)
            return true;
        return elapsedMs >= SCROLL_BUSY_PRELOAD_MIN_INTERVAL_MS;
    }

    static boolean shouldCacheDecodedBitmap(int bitmapSizeKb, int cacheSizeKb) {
        int normalizedCacheSizeKb = Math.max(1, cacheSizeKb);
        int maxEntryKb = Math.max(1024, normalizedCacheSizeKb / 3);
        return bitmapSizeKb > 0 && bitmapSizeKb <= maxEntryKb;
    }
}
