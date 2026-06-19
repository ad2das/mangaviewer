package ml.melun.mangaview.activity;

import org.junit.Test;
import ml.melun.mangaview.mangaview.CustomHttpClient;

import java.util.Collections;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CaptchaActivityTest {
    @Test
    public void ntkCaptchaLoadErrorsDoNotShowGenericConnectionPopup() {
        assertTrue(CaptchaActivity.shouldSuppressNtkLoadErrorPopupForTest(true, "https://example.com", "https://example.com"));
        assertTrue(CaptchaActivity.shouldSuppressNtkLoadErrorPopupForTest(false, "https://sbxh1.com", "https://example.com"));
        assertTrue(CaptchaActivity.shouldSuppressNtkLoadErrorPopupForTest(false, "https://example.com", "https://ntk01.com/manhwa"));
        assertFalse(CaptchaActivity.shouldSuppressNtkLoadErrorPopupForTest(false, "https://wfwf123.com", "https://wfwf123.com"));
    }

    @Test
    public void stableNtkNormalPageCanFinishCaptchaAfterCookieSync() {
        assertFalse(CaptchaActivity.shouldFinishNormalNtkPageForTest(0, 2000L));
        assertFalse(CaptchaActivity.shouldFinishNormalNtkPageForTest(1, 800L));
        assertTrue(CaptchaActivity.shouldFinishNormalNtkPageForTest(1, 801L));
    }

    @Test
    public void pastedCookieParserExtractsClearanceOnlyByName() {
        assertEquals("abc123", CaptchaActivity.extractCookieValueForTest(
                "__cf_bm=skip; cf_clearance=abc123; Path=/", "cf_clearance"));
        assertEquals("value", CaptchaActivity.extractCookieValueForTest(
                "foo=bar\ncf_clearance=value", "cf_clearance"));
        assertNull(CaptchaActivity.extractCookieValueForTest("clearance=value", "cf_clearance"));
    }

    @Test
    public void clearanceCookiePolicyRejectsDeletedOrShortValues() {
        assertFalse(CaptchaCookiePolicy.isValidClearanceValue("deleted"));
        assertFalse(CaptchaCookiePolicy.isValidClearanceValue("null"));
        assertFalse(CaptchaCookiePolicy.isValidClearanceValue("short"));
        assertTrue(CaptchaCookiePolicy.isValidClearanceValue("abcdefghijklmnopqrstuvwxyz"));
    }

    @Test
    public void quicSetCookieSummaryDoesNotExposeCookieValues() {
        String summary = CaptchaActivity.quicSetCookieNamesForTest(Arrays.asList(
                "__cf_bm=hidden-bm-value; Path=/",
                "cf_clearance=hidden-clearance-value; Path=/"));

        assertTrue(summary.contains("__cf_bm"));
        assertTrue(summary.contains("cf_clearance"));
        assertTrue(summary.contains("hasClearance=true"));
        assertFalse(summary.contains("hidden-bm-value"));
        assertFalse(summary.contains("hidden-clearance-value"));
    }

    @Test
    public void captchaNavigationAllowsOnlyNtkAndCloudflareTargets() {
        assertFalse(CaptchaActivity.shouldBlockCaptchaNavigationForTest("https://sbxh2.com/manhwa/3540"));
        assertFalse(CaptchaActivity.shouldBlockCaptchaNavigationForTest("https://challenges.cloudflare.com/cdn-cgi/challenge-platform/h/b/turnstile"));
        assertFalse(CaptchaActivity.shouldBlockCaptchaNavigationForTest("https://cloudflare.com/turnstile/v0/api.js"));
        assertTrue(CaptchaActivity.shouldBlockCaptchaNavigationForTest("https://buy.m.11st.co.kr/products?turnstile=1"));
        assertTrue(CaptchaActivity.shouldBlockCaptchaNavigationForTest("https://toss.im/"));
    }

    @Test
    public void ntkCaptchaProxyLoadErrorRetriesDirectOnce() {
        assertTrue(CaptchaActivity.shouldRetryCaptchaLoadWithoutProxyForTest(
                true, true, false, "https://sbxh3.com/manhwa/3540"));
        assertFalse(CaptchaActivity.shouldRetryCaptchaLoadWithoutProxyForTest(
                true, true, true, "https://sbxh3.com/manhwa/3540"));
        assertFalse(CaptchaActivity.shouldRetryCaptchaLoadWithoutProxyForTest(
                true, false, false, "https://sbxh3.com/manhwa/3540"));
        assertFalse(CaptchaActivity.shouldRetryCaptchaLoadWithoutProxyForTest(
                false, true, false, "https://sbxh3.com/manhwa/3540"));
    }

    @Test
    public void captchaUserAgentUsesCurrentWebViewVersionWithoutWvMarker() {
        String ua = CaptchaActivity.captchaUserAgentForTest(
                "Mozilla/5.0 (Linux; Android 15; sdk_gphone64_x86_64 Build/AE3A; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/124.0.6367.219 Mobile Safari/537.36");

        assertTrue(ua.contains("Chrome/124.0.6367.219"));
        assertFalse(ua.contains("; wv"));
        assertFalse(ua.contains("Version/4.0"));
    }

    @Test
    public void ntkCaptchaUserAgentUsesWebViewChromeShapeWithoutWvMarker() {
        String ua = CaptchaActivity.ntkCaptchaUserAgentForTest(
                "Mozilla/5.0 (Linux; Android 15; sdk_gphone64_x86_64 Build/AE3A; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/124.0.6367.219 Mobile Safari/537.36");

        assertTrue(ua.contains("Android 15"));
        assertTrue(ua.contains("Chrome/124.0.6367.219"));
        assertTrue(ua.contains("Mobile Safari"));
        assertFalse(ua.contains("Windows NT"));
        assertFalse(ua.contains("; wv"));
        assertFalse(ua.contains("Version/4.0"));
    }

    @Test
    public void ntkCaptchaDisablesWebViewDebuggingEvenInDebugBuilds() {
        assertFalse(CaptchaActivity.shouldEnableWebContentsDebuggingForTest(true, true));
        assertFalse(CaptchaActivity.shouldEnableWebContentsDebuggingForTest(false, true));
        assertTrue(CaptchaActivity.shouldEnableWebContentsDebuggingForTest(true, false));
        assertFalse(CaptchaActivity.shouldEnableWebContentsDebuggingForTest(false, false));
        assertTrue(CaptchaActivity.shouldEnableWebContentsDebuggingForTest(true, true, true));
        assertFalse(CaptchaActivity.shouldEnableWebContentsDebuggingForTest(false, true, true));
    }

    @Test
    public void ntkCaptchaLoadRetriesWithProxyAfterDirectHttpsFailure() {
        assertTrue(CaptchaActivity.shouldRetryCaptchaLoadWithProxyForTest(
                true, false, false, "https://sbxh3.com/"));
        assertFalse(CaptchaActivity.shouldRetryCaptchaLoadWithProxyForTest(
                true, true, false, "https://sbxh3.com/"));
        assertFalse(CaptchaActivity.shouldRetryCaptchaLoadWithProxyForTest(
                true, false, true, "https://sbxh3.com/"));
        assertFalse(CaptchaActivity.shouldRetryCaptchaLoadWithProxyForTest(
                false, false, false, "https://sbxh3.com/"));
    }

    @Test
    public void ntkCaptchaLoadRetriesWithQuicAfterDirectHttpsFailure() {
        assertTrue(CaptchaActivity.shouldRetryCaptchaLoadWithQuicForTest(
                true, false, false, "https://sbxh3.com/", true));
        assertFalse(CaptchaActivity.shouldRetryCaptchaLoadWithQuicForTest(
                true, true, false, "https://sbxh3.com/", true));
        assertFalse(CaptchaActivity.shouldRetryCaptchaLoadWithQuicForTest(
                true, false, true, "https://sbxh3.com/", true));
        assertFalse(CaptchaActivity.shouldRetryCaptchaLoadWithQuicForTest(
                true, false, false, "https://sbxh3.com/", false));
        assertFalse(CaptchaActivity.shouldRetryCaptchaLoadWithQuicForTest(
                false, false, false, "https://sbxh3.com/", true));
    }

    @Test
    public void ntkRootBootstrapSuppressesStaleRootErrorsAfterClearance() {
        assertTrue(CaptchaActivity.shouldSuppressStaleNtkRootErrorAfterClearanceForTest(
                true,
                "https://sbxh7.com/",
                "https://sbxh7.com/webtoon/17332/1515337",
                true,
                false));
        assertTrue(CaptchaActivity.shouldSuppressStaleNtkRootErrorAfterClearanceForTest(
                true,
                "https://sbxh7.com",
                "https://sbxh7.com/webtoon/17332/1515337",
                false,
                true));
        assertFalse(CaptchaActivity.shouldSuppressStaleNtkRootErrorAfterClearanceForTest(
                true,
                "https://sbxh7.com/webtoon/17332/1515337",
                "https://sbxh7.com/webtoon/17332/1515337",
                true,
                true));
        assertFalse(CaptchaActivity.shouldSuppressStaleNtkRootErrorAfterClearanceForTest(
                true,
                "https://sbxh7.com/",
                "https://sbxh7.com/board/free",
                true,
                true));
        assertFalse(CaptchaActivity.shouldSuppressStaleNtkRootErrorAfterClearanceForTest(
                false,
                "https://sbxh7.com/",
                "https://sbxh7.com/webtoon/17332/1515337",
                true,
                true));
    }

    @Test
    public void ntkAccessVerificationPrefersOriginalEpisodeOverBootstrapPage() {
        assertEquals("/webtoon/3774/176692", CaptchaActivity.ntkVerificationPathForAccessForTest(
                "https://sbxh7.com/webtoon/3774/176692",
                "https://sbxh7.com/webtoon/3774/176692",
                "https://sbxh7.com/manhwa"));
        assertEquals("/manhwa/25694/1767091", CaptchaActivity.ntkVerificationPathForAccessForTest(
                "https://sbxh7.com/manhwa/25694/1767091",
                "https://sbxh7.com/manhwa",
                "https://sbxh7.com/manhwa"));
    }

    @Test
    public void ntkAdAckBeforeFinishOnlyTreatsEpisodePathsAsEpisodes() {
        assertFalse(CaptchaActivity.isNtkEpisodePathForAdAckForTest("/webtoon/16968"));
        assertFalse(CaptchaActivity.isNtkEpisodePathForAdAckForTest("/manhwa/36525"));
        assertFalse(CaptchaActivity.isNtkEpisodePathForAdAckForTest("/manhwa"));
        assertTrue(CaptchaActivity.isNtkEpisodePathForAdAckForTest("/webtoon/16968/1463195"));
        assertTrue(CaptchaActivity.isNtkEpisodePathForAdAckForTest("/manhwa/36525/1807424?dpl=test"));
    }

    @Test
    public void turnstileTouchRepeatsAreBoundedAndSpaced() {
        assertFalse(CaptchaActivity.shouldRetryTurnstileTouchForTest(1000L, 0L, 0));
        assertFalse(CaptchaActivity.shouldRetryTurnstileTouchForTest(2400L, 0L, 1));
        assertTrue(CaptchaActivity.shouldRetryTurnstileTouchForTest(2500L, 0L, 1));
        assertTrue(CaptchaActivity.shouldRetryTurnstileTouchForTest(5000L, 2500L, 5));
        assertTrue(CaptchaActivity.shouldRetryTurnstileTouchForTest(7500L, 5000L, 6));
        assertTrue(CaptchaActivity.shouldRetryTurnstileTouchForTest(5000L, 2500L, 17));
        assertFalse(CaptchaActivity.shouldRetryTurnstileTouchForTest(7500L, 5000L, 18));
        assertFalse(CaptchaActivity.shouldRetryTurnstileTouchForTest(45_000L, 42_500L, 18));
    }

    @Test
    public void turnstileCheckBacksOffUntilNextAllowedTouch() {
        assertEquals(600L, CaptchaActivity.nextTurnstileCheckDelayForTest(true, "", 0, 1000L, 0L));
        assertEquals(1000L, CaptchaActivity.nextTurnstileCheckDelayForTest(false, "", 0, 1000L, 0L));
        assertEquals(1000L, CaptchaActivity.nextTurnstileCheckDelayForTest(false, "widget", 1, 2000L, 0L));
        assertEquals(1500L, CaptchaActivity.nextTurnstileCheckDelayForTest(false, "widget", 2, 1000L, 0L));
        assertEquals(1000L, CaptchaActivity.nextTurnstileCheckDelayForTest(false, "widget", 6, 9000L, 0L));
        assertEquals(2000L, CaptchaActivity.nextTurnstileCheckDelayForTest(false, "widget", 18, 9000L, 0L));
    }

    @Test
    public void turnstileIframeSelectionIgnoresFeedbackReports() {
        assertTrue(CaptchaActivity.shouldUseTurnstileIframeForTest(
                "https://challenges.cloudflare.com/cdn-cgi/challenge-platform/h/g/turnstile/f/ov2/normal",
                162.5, 68.8));
        assertTrue(CaptchaActivity.shouldUseTurnstileIframeForTest(
                "", 162.5, 68.8));
        assertFalse(CaptchaActivity.shouldUseTurnstileIframeForTest(
                "https://challenges.cloudflare.com/cdn-cgi/challenge-platform/h/g/feedback-reports/rhwaj/en-us/light/overrunning",
                393.0, 600.0));
        assertFalse(CaptchaActivity.shouldUseTurnstileIframeForTest(
                "https://sbxh4.com/cdn-cgi/challenge-platform/h/g/flow/ov1/token",
                393.0, 600.0));
        assertFalse(CaptchaActivity.shouldUseTurnstileIframeForTest(
                "https://example.com/not-turnstile", 162.5, 68.8));
    }

    @Test
    public void turnstileFallbackContainerRejectsLargeFeedbackPanels() {
        assertTrue(CaptchaActivity.shouldUseTurnstileFallbackContainerForTest(360.0, 68.0));
        assertFalse(CaptchaActivity.shouldUseTurnstileFallbackContainerForTest(393.0, 600.0));
        assertFalse(CaptchaActivity.shouldUseTurnstileFallbackContainerForTest(42.0, 68.0));
    }

    @Test
    public void turnstileAutoScriptChecksExistingOpenShadowRoots() {
        assertTrue(CaptchaActivity.TURNSTILE_AUTO_JS.contains("el.__sr||el.shadowRoot"));
        assertTrue(CaptchaActivity.TURNSTILE_AUTO_JS.contains("new MouseEvent('click'"));
        assertTrue(CaptchaActivity.SHADOW_HOOK_JS.contains("new MouseEvent('click'"));
    }

    @Test
    public void ntkQuicInterceptServesSameRootCloudflareChallengeResourcesWhenFallbackActive() {
        String root = CustomHttpClient.NTK_WEBTOON_URL;
        String imgRoot = root.replace("https://", "https://img.");
        assertTrue(CaptchaActivity.shouldInterceptNtkQuicRequestForTest(
                true, true, "GET", root + "/cdn-cgi/challenge-platform/h/b/orchestrate/chl_page/v1", true));
        assertTrue(CaptchaActivity.shouldInterceptNtkQuicRequestForTest(
                true, true, "GET", imgRoot + "/resource.js", true));
        assertTrue(CaptchaActivity.shouldInterceptNtkQuicRequestForTest(
                true, false, "GET", root + "/api/ad/guard-js?v=b1781038137728-wasm-1781038137731", true));
        assertTrue(CaptchaActivity.shouldInterceptNtkQuicRequestForTest(
                true, false, "GET", root + "/api/ad/guard-wasm?v=b1781038137728-wasm-1781038137731", true));
        assertFalse(CaptchaActivity.shouldInterceptNtkQuicRequestForTest(
                true, false, "GET", root + "/cdn-cgi/challenge-platform/h/b/orchestrate/chl_page/v1", true));
        assertFalse(CaptchaActivity.shouldInterceptNtkQuicRequestForTest(
                true, true, "POST", root + "/cdn-cgi/challenge-platform/h/b/flow/ov1", true));
        assertFalse(CaptchaActivity.shouldInterceptNtkQuicRequestForTest(
                true, false, "POST", root + "/api/ad/guard-js?v=b1781038137728-wasm-1781038137731", true));
        assertFalse(CaptchaActivity.shouldInterceptNtkQuicRequestForTest(
                true, true, "GET", "https://challenges.cloudflare.com/turnstile/v0/api.js", true));
        assertFalse(CaptchaActivity.shouldInterceptNtkQuicRequestForTest(
                true, true, "GET", root + "/", false));
        assertFalse(CaptchaActivity.shouldInterceptNtkQuicRequestForTest(
                false, true, "GET", root + "/", true));
    }

    @Test
    public void ntkCaptchaBridgeRoutesSameRootChallengeAndAdPosts() {
        String script = CaptchaActivity.ntkQuicBridgeJavascriptForTest();

        assertTrue(script.contains("window.__ntkBridgeFetch=function(input,init)"));
        assertTrue(script.contains("window.fetch=wrappedFetch"));
        assertTrue(script.contains("XMLHttpRequest"));
        assertTrue(script.contains("xp.open=wrappedOpen"));
        assertTrue(script.contains("return nativeFetch.apply(this,arguments)"));
        assertTrue(script.contains("sameRootChallengePost"));
        assertTrue(script.contains("sameRootAdApiPost"));
        assertTrue(script.contains("window.__ntkBridgeFetch(absolute.href"));
        assertTrue(script.contains("window.NtkQuicBridge.request(absolute.href"));
        assertTrue(script.contains("Sec-Fetch-Dest"));
        assertTrue(script.contains("Sec-Fetch-Mode"));
        assertTrue(script.contains("Sec-Fetch-Site"));
        assertTrue(script.contains("sec-ch-ua"));
        assertTrue(script.contains("navigator.userAgent"));
        assertTrue(script.contains("a.pathname.indexOf('/cdn-cgi/challenge-platform/')===0"));
        assertTrue(script.contains("a.pathname==='/api/ad/challenge'"));
        assertTrue(script.contains("a.pathname==='/api/ad/ack'"));
        assertTrue(script.contains("String(m||'GET').toUpperCase()==='POST'"));
    }

    @Test
    public void ntkDocumentVerificationRejectsNginxForbiddenPage() {
        assertFalse(CaptchaActivity.isUsableNtkDocumentVerificationBodyForTest(
                "<html><head><title>403 Forbidden</title></head><body><center><h1>403 Forbidden</h1></center><hr><center>nginx/1.24.0</center></body></html>"));
        assertTrue(CaptchaActivity.isUsableNtkDocumentVerificationBodyForTest(
                "<!doctype html><html><body><a href=\"/manhwa/2\">One Piece</a><script src=\"/_next/static/app.js\"></script></body></html>"));
    }

    @Test
    public void ntkBridgeRetriesOnlyBlockedControlPostsWithHttp2() {
        NtkQuicFetcher.Result forbidden = new NtkQuicFetcher.Result(
                403, new byte[0], Collections.emptyMap(), null);
        NtkQuicFetcher.Result ok = new NtkQuicFetcher.Result(
                200, new byte[0], Collections.emptyMap(), null);

        assertTrue(CaptchaActivity.shouldRetryNtkBridgePostWithHttp2ForTest(
                CustomHttpClient.NTK_WEBTOON_URL + "/api/ad/challenge", "POST", forbidden));
        assertTrue(CaptchaActivity.shouldRetryNtkBridgePostWithHttp2ForTest(
                CustomHttpClient.NTK_WEBTOON_URL + "/api/ev/sync", "POST", forbidden));
        assertFalse(CaptchaActivity.shouldRetryNtkBridgePostWithHttp2ForTest(
                CustomHttpClient.NTK_WEBTOON_URL + "/api/ad/challenge", "GET", forbidden));
        assertFalse(CaptchaActivity.shouldRetryNtkBridgePostWithHttp2ForTest(
                CustomHttpClient.NTK_WEBTOON_URL + "/api/manhwa-list", "POST", forbidden));
        assertFalse(CaptchaActivity.shouldRetryNtkBridgePostWithHttp2ForTest(
                CustomHttpClient.NTK_WEBTOON_URL + "/api/ad/challenge", "POST", ok));
    }
}
