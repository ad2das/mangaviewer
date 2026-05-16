package ml.melun.mangaview.fragment;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HomeInactivePrefetchPolicyTest {
    @Test
    public void inactivePrefetchWaitsForSelectedHomeContent() {
        assertFalse(HomeInactivePrefetchPolicy.shouldScheduleForTest(true, MainMain.HOME_FETCH_IDLE, false));
        assertFalse(HomeInactivePrefetchPolicy.shouldScheduleForTest(true, MainMain.HOME_FETCH_LOADING, false));
        assertTrue(HomeInactivePrefetchPolicy.shouldScheduleForTest(true, MainMain.HOME_FETCH_PARTIAL, false));
        assertTrue(HomeInactivePrefetchPolicy.shouldScheduleForTest(true, MainMain.HOME_FETCH_COMPLETE, false));
    }

    @Test
    public void inactivePrefetchOnlyRunsForNtkWhenNotWaiting() {
        assertFalse(HomeInactivePrefetchPolicy.shouldScheduleForTest(false, MainMain.HOME_FETCH_COMPLETE, false));
        assertFalse(HomeInactivePrefetchPolicy.shouldScheduleForTest(true, MainMain.HOME_FETCH_COMPLETE, true));
    }

    @Test
    public void incompleteSelectedHomeGetsLongerDelay() {
        assertEquals(1600L, HomeInactivePrefetchPolicy.delayMsForTest(MainMain.HOME_FETCH_PARTIAL));
        assertEquals(900L, HomeInactivePrefetchPolicy.delayMsForTest(MainMain.HOME_FETCH_COMPLETE));
    }
}
