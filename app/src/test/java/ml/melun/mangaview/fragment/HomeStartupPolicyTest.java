package ml.melun.mangaview.fragment;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HomeStartupPolicyTest {
    @Test
    public void reselectingFailedHomeModeIsAnExplicitRetry() {
        assertTrue(MainMain.shouldRetrySelectedHomeOnReselect(MainMain.HOME_FETCH_FAILED));
        assertFalse(MainMain.shouldRetrySelectedHomeOnReselect(MainMain.HOME_FETCH_LOADING));
        assertFalse(MainMain.shouldRetrySelectedHomeOnReselect(MainMain.HOME_FETCH_COMPLETE));
    }

    @Test
    public void inactiveInitialRowsWaitUntilSelectedHomePaints() {
        assertEquals(1200L, HomeStartupPolicy.inactiveInitialRowsDelayMsForTest(false));
        assertEquals(1800L, HomeStartupPolicy.inactiveInitialRowsDelayMsForTest(true));
    }

    @Test
    public void selectedFetchStartsAfterInitialHomePaint() {
        assertEquals(220L, HomeStartupPolicy.activeFetchDelayMsForTest(false));
        assertEquals(320L, HomeStartupPolicy.activeFetchDelayMsForTest(true));
    }

    @Test
    public void ntkCaptchaStartsImmediatelyWhenNeeded() {
        assertEquals(0L, HomeStartupPolicy.autoCaptchaDelayMsForTest(false));
        assertEquals(5_000L, HomeStartupPolicy.autoCaptchaDelayMsForTest(true));
    }
}
