package ml.melun.mangaview.activity;

import org.junit.Test;

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
    public void stableNtkNormalPageRequiresAppAccessVerificationBeforeFinish() {
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
    public void captchaNavigationAllowsOnlyNtkAndCloudflareTargets() {
        assertFalse(CaptchaActivity.shouldBlockCaptchaNavigationForTest("https://sbxh2.com/manhwa/3540"));
        assertFalse(CaptchaActivity.shouldBlockCaptchaNavigationForTest("https://challenges.cloudflare.com/cdn-cgi/challenge-platform/h/b/turnstile"));
        assertFalse(CaptchaActivity.shouldBlockCaptchaNavigationForTest("https://cloudflare.com/turnstile/v0/api.js"));
        assertTrue(CaptchaActivity.shouldBlockCaptchaNavigationForTest("https://buy.m.11st.co.kr/products?turnstile=1"));
        assertTrue(CaptchaActivity.shouldBlockCaptchaNavigationForTest("https://toss.im/"));
    }
}
