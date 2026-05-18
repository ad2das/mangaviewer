package ml.melun.mangaview.activity;

import org.junit.Test;

import android.content.Intent;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

public class MainActivityStartupPolicyTest {
    @Test
    public void startupBackgroundWorkRunsAfterInitialHomeWindow() {
        assertTrue(MainActivity.startupDeferredTasksDelayMsForTest() >= 2000L);
        assertTrue(MainActivity.startupUpdateCheckDelayMsForTest() >= 300000L);
        assertTrue(MainActivity.startupNtkCaptchaCheckDelayMsForTest() >= 15000L);
        assertTrue(MainActivity.ntkCaptchaCheckMinIntervalMsForTest() >= 5000L);
    }

    @Test
    public void duplicateLauncherMainDoesNotStackOverExistingTask() {
        assertTrue(MainActivity.shouldFinishDuplicateLauncherForTest(false, Intent.ACTION_MAIN, true));
        assertFalse(MainActivity.shouldFinishDuplicateLauncherForTest(true, Intent.ACTION_MAIN, true));
        assertFalse(MainActivity.shouldFinishDuplicateLauncherForTest(false, Intent.ACTION_VIEW, true));
        assertFalse(MainActivity.shouldFinishDuplicateLauncherForTest(false, Intent.ACTION_MAIN, false));
    }
}
