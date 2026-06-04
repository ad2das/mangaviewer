package ml.melun.mangaview.activity;

import org.junit.Test;
import ml.melun.mangaview.mangaview.CustomHttpClient;

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
        assertFalse(CaptchaActivity.shouldFinishNormalNtkPageForTest(1, 2000L));
        assertFalse(CaptchaActivity.shouldFinishNormalNtkPageForTest(2, 1200L));
        assertTrue(CaptchaActivity.shouldFinishNormalNtkPageForTest(2, 1201L));
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
    public void ntkCaptchaUserAgentUsesDesktopChromeShape() {
        String ua = CaptchaActivity.ntkCaptchaUserAgentForTest(
                "Mozilla/5.0 (Linux; Android 15; sdk_gphone64_x86_64 Build/AE3A; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/124.0.6367.219 Mobile Safari/537.36");

        assertTrue(ua.contains("Windows NT"));
        assertTrue(ua.contains("Chrome/148.0.0.0"));
        assertFalse(ua.contains("Mobile Safari"));
        assertFalse(ua.contains("; wv"));
    }

    @Test
    public void ntkCaptchaDisablesWebViewDebuggingEvenInDebugBuilds() {
        assertFalse(CaptchaActivity.shouldEnableWebContentsDebuggingForTest(true, true));
        assertFalse(CaptchaActivity.shouldEnableWebContentsDebuggingForTest(false, true));
        assertTrue(CaptchaActivity.shouldEnableWebContentsDebuggingForTest(true, false));
        assertFalse(CaptchaActivity.shouldEnableWebContentsDebuggingForTest(false, false));
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
    public void turnstileTouchRepeatsAreBoundedAndSpaced() {
        assertFalse(CaptchaActivity.shouldRetryTurnstileTouchForTest(1000L, 0L, 0));
        assertFalse(CaptchaActivity.shouldRetryTurnstileTouchForTest(7000L, 0L, 1));
        assertTrue(CaptchaActivity.shouldRetryTurnstileTouchForTest(8000L, 0L, 1));
        assertTrue(CaptchaActivity.shouldRetryTurnstileTouchForTest(16000L, 8000L, 2));
        assertFalse(CaptchaActivity.shouldRetryTurnstileTouchForTest(24000L, 16000L, 3));
    }

    @Test
    public void turnstileCheckBacksOffUntilNextAllowedTouch() {
        assertEquals(600L, CaptchaActivity.nextTurnstileCheckDelayForTest(true, "", 0, 1000L, 0L));
        assertEquals(1000L, CaptchaActivity.nextTurnstileCheckDelayForTest(false, "", 0, 1000L, 0L));
        assertEquals(6000L, CaptchaActivity.nextTurnstileCheckDelayForTest(false, "widget", 1, 2000L, 0L));
        assertEquals(1000L, CaptchaActivity.nextTurnstileCheckDelayForTest(false, "widget", 2, 9000L, 0L));
        assertEquals(2000L, CaptchaActivity.nextTurnstileCheckDelayForTest(false, "widget", 3, 9000L, 0L));
    }

    @Test
    public void ntkQuicInterceptOnlyRunsInsideQuicHtmlFallback() {
        String root = CustomHttpClient.NTK_WEBTOON_URL;
        String imgRoot = root.replace("https://", "https://img.");
        assertTrue(CaptchaActivity.shouldInterceptNtkQuicRequestForTest(
                true, true, "GET", root + "/cdn-cgi/challenge-platform/h/b/orchestrate/chl_page/v1", true));
        assertTrue(CaptchaActivity.shouldInterceptNtkQuicRequestForTest(
                true, true, "GET", imgRoot + "/resource.js", true));
        assertFalse(CaptchaActivity.shouldInterceptNtkQuicRequestForTest(
                true, false, "GET", root + "/cdn-cgi/challenge-platform/h/b/orchestrate/chl_page/v1", true));
        assertFalse(CaptchaActivity.shouldInterceptNtkQuicRequestForTest(
                true, true, "POST", root + "/cdn-cgi/challenge-platform/h/b/flow/ov1", true));
        assertFalse(CaptchaActivity.shouldInterceptNtkQuicRequestForTest(
                true, true, "GET", "https://challenges.cloudflare.com/turnstile/v0/api.js", true));
        assertFalse(CaptchaActivity.shouldInterceptNtkQuicRequestForTest(
                true, true, "GET", root + "/", false));
        assertFalse(CaptchaActivity.shouldInterceptNtkQuicRequestForTest(
                false, true, "GET", root + "/", true));
    }
}
