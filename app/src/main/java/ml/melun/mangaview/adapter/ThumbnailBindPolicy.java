package ml.melun.mangaview.adapter;

final class ThumbnailBindPolicy {
    static final String TAG_EMPTY = "empty";
    static final String TAG_PLACEHOLDER = "placeholder";
    private static final String PENDING_PREFIX = "pending:";

    private ThumbnailBindPolicy() {
    }

    static boolean shouldSkipDeferredBind(Object currentTag, String key) {
        if(key == null)
            key = "";
        String tag = String.valueOf(currentTag);
        return key.equals(tag) || pendingKey(key).equals(tag);
    }

    static boolean shouldClearBeforeDeferredBind(Object currentTag, boolean clearImmediately) {
        if(!clearImmediately || currentTag == null)
            return false;
        String tag = String.valueOf(currentTag);
        return tag.length() > 0
                && !tag.startsWith(PENDING_PREFIX)
                && !TAG_PLACEHOLDER.equals(tag)
                && !TAG_EMPTY.equals(tag);
    }

    static String pendingKey(String key) {
        return PENDING_PREFIX + (key == null ? "" : key);
    }
}
