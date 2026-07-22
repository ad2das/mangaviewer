package ml.melun.mangaview.mangaview;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HttpDocumentPolicyTest {
    @Test
    public void ntkDocumentPathsAreClassifiedSeparatelyFromApiAndSearch() {
        assertTrue(HttpDocumentPolicy.isNtkTitleDocumentPath("/manhwa/1"));
        assertTrue(HttpDocumentPolicy.isNtkEpisodeDocumentPath("/manhwa/1/2"));
        assertTrue(HttpDocumentPolicy.isNtkCategoryDocumentPath("/manhwa?page=2"));
        assertTrue(HttpDocumentPolicy.isNtkCategoryDocumentPath("/ing?tag=action"));
        assertTrue(HttpDocumentPolicy.isNtkWebViewFetchPath("/manhwa?page=2"));
        assertTrue(HttpDocumentPolicy.isNtkWebViewFetchPath("/ing?tag=action"));
        assertTrue(HttpDocumentPolicy.isNtkWebViewFetchPath("/api/manhwa-list"));
        assertTrue(HttpDocumentPolicy.isNtkWebViewFetchPath("/search?q=onepiece"));
        assertFalse(HttpDocumentPolicy.isNtkEpisodeDocumentPath("/api/manhwa-list"));
    }

    @Test
    public void everyStrictEpisodeShapeRejectsSharedWebViewAndFastDocumentRoutes() {
        String[] strictEpisodePaths = {
                "/manhwa/33727/1692251",
                "/webtoon/850236/nv-850236-11",
                "/webtoon/68630031/kp-68630031-69262979",
                "/webtoon/work/episode?from=reader#top"
        };

        for(String path : strictEpisodePaths) {
            assertTrue(path, HttpDocumentPolicy.isNtkEpisodeDocumentPath(path));
            assertFalse(path, HttpDocumentPolicy.isNtkWebViewFetchPath(path));
            assertFalse(path, HttpDocumentPolicy.shouldUseNtkWebViewFallback(
                    true, true, path, CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW));
            assertFalse(path, HttpDocumentPolicy.shouldUseSharedWebViewFallback(
                    true, true, path, CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW, true));
            assertFalse(path, HttpDocumentPolicy.shouldUseFastNtkPageDirect(
                    true, path, CustomHttpClient.FetchMode.DIRECT_ONLY));
        }
    }

    @Test
    public void sharedWebViewFallbackRequiresAllowedModeAndDocumentPath() {
        assertTrue(HttpDocumentPolicy.shouldUseSharedWebViewFallback(false, true, "/cv?toon=1&num=2",
                CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW, true));
        assertFalse(HttpDocumentPolicy.shouldUseSharedWebViewFallback(false, true, "/cm?type1=genre",
                CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW, true));
        assertFalse(HttpDocumentPolicy.shouldUseSharedWebViewFallback(false, true, "/cv?toon=1&num=2",
                CustomHttpClient.FetchMode.DIRECT_ONLY, true));
        assertFalse(HttpDocumentPolicy.shouldUseSharedWebViewFallback(true, true, "/manhwa/1/2",
                CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW, true));
        assertFalse(HttpDocumentPolicy.shouldUseSharedWebViewFallback(true, true, "/search?q=onepiece",
                CustomHttpClient.FetchMode.SEARCH_NO_WEBVIEW, true));
        assertFalse(HttpDocumentPolicy.shouldUseSharedWebViewFallback(true, true, "/api/manhwa-list",
                CustomHttpClient.FetchMode.SEARCH_NO_WEBVIEW, true));
        assertFalse(HttpDocumentPolicy.shouldUseSharedWebViewFallback(true, true, "/manhwa/1/2",
                CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW, true));
        assertTrue(HttpDocumentPolicy.shouldUseSharedWebViewFallback(true, true, "/manhwa?page=2",
                CustomHttpClient.FetchMode.ALLOW_SHARED_WEBVIEW, true));
    }

    @Test
    public void fastNtkDirectSkipsCacheOnlyAndStaticAssets() {
        assertFalse(HttpDocumentPolicy.shouldUseFastNtkPageDirect(true, "/manhwa/1/2",
                CustomHttpClient.FetchMode.DIRECT_ONLY));
        assertTrue(HttpDocumentPolicy.shouldUseFastNtkPageDirect(true, "/api/manhwa-list",
                CustomHttpClient.FetchMode.DIRECT_ONLY));
        assertTrue(HttpDocumentPolicy.shouldUseFastNtkPageDirect(true, "/search?q=onepiece",
                CustomHttpClient.FetchMode.DIRECT_ONLY));
        assertTrue(HttpDocumentPolicy.shouldUseFastNtkPageDirect(true, "/manhwa?page=2",
                CustomHttpClient.FetchMode.DIRECT_ONLY));
        assertFalse(HttpDocumentPolicy.shouldUseFastNtkPageDirect(true, "/_next/static/app.js",
                CustomHttpClient.FetchMode.DIRECT_ONLY));
        assertFalse(HttpDocumentPolicy.shouldUseFastNtkPageDirect(true, "/manhwa/1/2",
                CustomHttpClient.FetchMode.CACHE_ONLY));
    }

    @Test
    public void ntkSearchNoWebViewOnlyCoversSearchAndApiPaths() {
        assertTrue(HttpDocumentPolicy.isNtkSearchNoWebViewPath("/search?q=onepiece"));
        assertTrue(HttpDocumentPolicy.isNtkSearchNoWebViewPath("/api/manhwa-list?keyword=onepiece"));
        assertFalse(HttpDocumentPolicy.isNtkSearchNoWebViewPath("/manhwa/1"));
        assertFalse(HttpDocumentPolicy.isNtkSearchNoWebViewPath("/manhwa/1/2"));
        assertFalse(HttpDocumentPolicy.isNtkSearchNoWebViewPath("/manhwa?page=2"));
    }
}
