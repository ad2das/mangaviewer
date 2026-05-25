package ml.melun.mangaview.adapter;

import android.graphics.Bitmap;

final class StripBitmapDisplayPolicy {
    private StripBitmapDisplayPolicy() {
    }

    static boolean shouldCacheDisplayedBitmap(String cacheKey, boolean bitmapUsable) {
        return cacheKey != null && cacheKey.length() > 0 && bitmapUsable;
    }

    static boolean isDisplayBitmapUsable(Bitmap bitmap) {
        return bitmap != null && !bitmap.isRecycled() && bitmap.getWidth() > 0 && bitmap.getHeight() > 0;
    }

    static Bitmap copyBitmapForDisplay(Bitmap bitmap) {
        return isDisplayBitmapUsable(bitmap) ? bitmap : null;
    }
}
