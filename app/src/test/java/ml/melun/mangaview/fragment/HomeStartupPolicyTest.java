package ml.melun.mangaview.fragment;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class HomeStartupPolicyTest {
    @Test
    public void inactiveInitialRowsWaitUntilSelectedHomePaints() {
        assertEquals(1200L, HomeStartupPolicy.inactiveInitialRowsDelayMsForTest(false));
        assertEquals(1800L, HomeStartupPolicy.inactiveInitialRowsDelayMsForTest(true));
    }
}
