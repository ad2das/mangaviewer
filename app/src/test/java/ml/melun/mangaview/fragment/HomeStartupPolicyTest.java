package ml.melun.mangaview.fragment;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class HomeStartupPolicyTest {
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
    public void ntkCaptchaWaitsUntilHomeIsUsable() {
        assertEquals(0L, HomeStartupPolicy.autoCaptchaDelayMsForTest(false));
        assertEquals(20_000L, HomeStartupPolicy.autoCaptchaDelayMsForTest(true));
    }
}
