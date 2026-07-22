package ml.melun.mangaview.activity;

import org.junit.Test;

import android.content.Intent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

public class MainActivityStartupPolicyTest {
    @Test
    public void startupBackgroundWorkRunsAfterInitialHomeWindow() {
        assertTrue(MainActivity.startupDeferredTasksDelayMsForTest() >= 2000L);
        assertTrue(MainActivity.startupUpdateCheckDelayMsForTest() >= 300000L);
        assertEquals(3_000L, MainActivity.startupNtkCaptchaCheckDelayMsForTest());
        assertTrue(MainActivity.ntkCaptchaCheckMinIntervalMsForTest() >= 5000L);
        assertEquals(0L, MainActivity.startupContinueWarmupSuppressMsForTest());
    }

    @Test
    public void duplicateLauncherMainDoesNotStackOverExistingTask() {
        assertTrue(MainActivity.shouldFinishDuplicateLauncherForTest(false, Intent.ACTION_MAIN, true));
        assertFalse(MainActivity.shouldFinishDuplicateLauncherForTest(true, Intent.ACTION_MAIN, true));
        assertFalse(MainActivity.shouldFinishDuplicateLauncherForTest(false, Intent.ACTION_VIEW, true));
        assertFalse(MainActivity.shouldFinishDuplicateLauncherForTest(false, Intent.ACTION_MAIN, false));
    }

    @Test
    public void tabPolicyMapsNavigationItemsToStableFragments() {
        assertEquals(0, MainTabPolicy.fragmentIndex(ml.melun.mangaview.R.id.nav_main));
        assertEquals(1, MainTabPolicy.fragmentIndex(ml.melun.mangaview.R.id.nav_search));
        assertEquals(2, MainTabPolicy.fragmentIndex(ml.melun.mangaview.R.id.nav_recent));
        assertEquals(2, MainTabPolicy.fragmentIndex(ml.melun.mangaview.R.id.nav_favorite));
        assertEquals(2, MainTabPolicy.fragmentIndex(ml.melun.mangaview.R.id.nav_download));
        assertEquals(ml.melun.mangaview.R.id.nav_search, MainTabPolicy.tabId(1));
        assertEquals("내 보관함", MainTabPolicy.tabTitle(2));
    }
}
