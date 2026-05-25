package ml.melun.mangaview.mangaview;

final class HttpDocumentPolicy {
    private HttpDocumentPolicy() {
    }

    static boolean shouldUseNtkWebViewFallback(boolean ntkUrl,
                                               boolean missingResponse,
                                               String path,
                                               CustomHttpClient.FetchMode fetchMode) {
        if(fetchMode != CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW || !ntkUrl || !missingResponse || path == null)
            return false;
        return isNtkWebViewFetchPath(path);
    }

    static boolean shouldUseSharedWebViewFallback(boolean ntkUrl,
                                                  boolean missingResponse,
                                                  String path,
                                                  CustomHttpClient.FetchMode fetchMode,
                                                  boolean allowWolfWebViewFallback) {
        if(fetchMode != CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW || !missingResponse || path == null)
            return false;
        if(ntkUrl)
            return shouldUseNtkWebViewFallback(true, true, path, fetchMode);
        return shouldUseWolfWebViewFallback(false, true, path, fetchMode, allowWolfWebViewFallback);
    }

    static boolean shouldUseWolfWebViewFallback(boolean ntkUrl,
                                                boolean missingResponse,
                                                String path,
                                                CustomHttpClient.FetchMode fetchMode,
                                                boolean allowWolfWebViewFallback) {
        return fetchMode == CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW
                && allowWolfWebViewFallback
                && !ntkUrl
                && missingResponse
                && isWolfEpisodeDocumentPath(path);
    }

    static boolean shouldUseFastNtkPageDirect(boolean ntkUrl, String path, CustomHttpClient.FetchMode fetchMode) {
        if(!ntkUrl || path == null || fetchMode == CustomHttpClient.FetchMode.CACHE_ONLY)
            return false;
        return path.startsWith("/webtoon/") || path.startsWith("/manhwa/") || path.startsWith("/api/");
    }

    static boolean isNtkEpisodeDocumentPath(String path) {
        return path != null && path.matches("^/(?:webtoon|manhwa)/[^/?#]+/[^/?#]+.*");
    }

    static boolean isNtkTitleDocumentPath(String path) {
        return path != null && path.matches("^/(?:webtoon|manhwa)/[^/?#]+/?$");
    }

    static boolean isNtkNavigableDocumentPath(String path) {
        return isNtkTitleDocumentPath(path) || isNtkEpisodeDocumentPath(path);
    }

    static boolean isNtkWebViewFetchPath(String path) {
        return isNtkNavigableDocumentPath(path)
                || isNtkApiPath(path)
                || isNtkSearchPath(path);
    }

    static boolean isNtkApiPath(String path) {
        return path != null && path.startsWith("/api/");
    }

    static boolean isNtkSearchPath(String path) {
        return path != null && (path.equals("/search") || path.startsWith("/search?"));
    }

    static boolean isWolfEpisodeDocumentPath(String path) {
        return path != null && (path.startsWith("/cl?toon=")
                || path.startsWith("/list?toon=")
                || path.startsWith("/cv?toon=")
                || path.startsWith("/view?toon="));
    }
}
