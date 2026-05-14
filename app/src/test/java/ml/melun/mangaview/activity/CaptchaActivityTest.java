package ml.melun.mangaview.activity;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
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
    public void stableNtkNormalPageCanFinishWithoutNewClearanceCookie() {
        assertFalse(CaptchaActivity.shouldFinishNormalNtkPageForTest(1, 2000L));
        assertFalse(CaptchaActivity.shouldFinishNormalNtkPageForTest(2, 1200L));
        assertTrue(CaptchaActivity.shouldFinishNormalNtkPageForTest(2, 1201L));
    }
}
