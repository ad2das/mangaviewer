package ml.melun.mangaview.glide;

final class ViewerWarmupCachePolicy {
    private ViewerWarmupCachePolicy() {
    }

    static boolean shouldEvictDecodedCacheWhenClearingWork() {
        return false;
    }
}
