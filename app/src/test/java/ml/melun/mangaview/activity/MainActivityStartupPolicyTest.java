package ml.melun.mangaview.activity;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class MainActivityStartupPolicyTest {
    @Test
    public void startupBackgroundWorkRunsAfterInitialHomeWindow() {
        assertTrue(MainActivity.startupDeferredTasksDelayMsForTest() >= 2000L);
        assertTrue(MainActivity.startupUpdateCheckDelayMsForTest() >= 6000L);
    }
}
