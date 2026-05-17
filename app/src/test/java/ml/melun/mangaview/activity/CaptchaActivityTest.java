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
}
