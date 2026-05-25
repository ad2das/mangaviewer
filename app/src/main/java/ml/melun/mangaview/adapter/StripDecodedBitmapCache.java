package ml.melun.mangaview.adapter;

import android.graphics.Bitmap;
import android.util.LruCache;

import androidx.annotation.NonNull;

final class StripDecodedBitmapCache {
    private final LruCache<String, CachedBitmap> cache;

    StripDecodedBitmapCache(boolean dataSave) {
        cache = new LruCache<String, CachedBitmap>(decodedCacheSizeKb(dataSave)) {
            @Override
            protected int sizeOf(@NonNull String key, @NonNull CachedBitmap value) {
                return value.sizeKb;
            }
        };
    }

    CachedBitmap get(String key) {
        return cache.get(key);
    }

    void remove(String key) {
        cache.remove(key);
    }

    void evictAll() {
        cache.evictAll();
    }

    void put(String key, Bitmap bitmap) {
        if(key == null || key.length() == 0 || bitmap == null || bitmap.isRecycled())
            return;
        int sizeKb = bitmapSizeKb(bitmap);
        if(!shouldCacheDecodedBitmap(sizeKb, cache.maxSize()))
            return;
        cache.put(key, new CachedBitmap(bitmap, sizeKb));
    }

    static boolean shouldCacheDecodedBitmap(int bitmapSizeKb, int cacheSizeKb) {
        return StripImagePolicy.shouldCacheDecodedBitmap(bitmapSizeKb, cacheSizeKb);
    }

    private static int decodedCacheSizeKb(boolean dataSave) {
        int maxMemoryKb = (int)(Runtime.getRuntime().maxMemory() / 1024);
        int targetKb = maxMemoryKb / (dataSave ? 40 : 24);
        int minKb = dataSave ? 2 * 1024 : 3 * 1024;
        int maxKb = dataSave ? 4 * 1024 : 8 * 1024;
        return Math.max(minKb, Math.min(targetKb, maxKb));
    }

    private static int bitmapSizeKb(Bitmap bitmap) {
        if(bitmap == null)
            return 1;
        return Math.max(1, bitmap.getByteCount() / 1024);
    }

    static final class CachedBitmap {
        final Bitmap bitmap;
        final int sizeKb;

        CachedBitmap(Bitmap bitmap, int sizeKb) {
            this.bitmap = bitmap;
            this.sizeKb = Math.max(1, sizeKb);
        }

        boolean isUsable() {
            return bitmap != null && !bitmap.isRecycled();
        }
    }
}
