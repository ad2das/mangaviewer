package ml.melun.mangaview.activity;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import ml.melun.mangaview.MainApplication;
import ml.melun.mangaview.R;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class AppScreenResizeSmokeInstrumentedTest {
    @After
    public void restorePortrait() {
        Activity activity = currentActivity;
        if(activity != null && !activity.isFinishing())
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        currentActivity = null;
    }

    private Activity currentActivity;

    @Test
    public void everyOfflineSecondaryScreenSurvivesShortWindowAndRecreation() {
        List<Intent> screens = new ArrayList<>();
        screens.add(intent(SettingsActivity.class));
        screens.add(intent(DownloadActivity.class));
        screens.add(intent(FolderSelectActivity.class)
                .putExtra("mode", FolderSelectActivity.MODE_FOLDER_SELECT)
                .putExtra("title", "폴더 선택"));
        screens.add(intent(LicenseActivity.class));
        screens.add(intent(AdvSearchActivity.class));
        screens.add(intent(TagSearchActivity.class)
                .putExtra("mode", 7)
                .putExtra("query", "")
                .putExtra("title", "보관함"));
        screens.add(intent(LayoutEditActivity.class));
        screens.add(intent(DebugActivity.class));

        for(Intent intent : screens)
            exercise(intent);
    }

    @Test
    public void firstRunAndCrashRecoveryScreensSurviveResize() {
        long previousAgreement = MainApplication.p.getSharedPref().getLong("eula2", -1L);
        try {
            exercise(intent(FirstTimeActivity.class));
            exerciseDialog(intent(CrashReportActivity.class));
        } finally {
            MainApplication.p.getSharedPref().edit().putLong("eula2", previousAgreement).commit();
        }
    }

    @Test
    public void mainChromeNeverCoversItsContentAfterResize() {
        try(ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                currentActivity = activity;
                assertUsable(activity);
                assertMainChromeDoesNotOverlap(activity);
                activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                assertUsable(activity);
                assertMainChromeDoesNotOverlap(activity);
            });
            scenario.recreate();
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                currentActivity = activity;
                assertUsable(activity);
                assertMainChromeDoesNotOverlap(activity);
            });
        } finally {
            currentActivity = null;
        }
    }

    @Test
    public void leavingSearchTabDismissesImeInsteadOfCoveringLibrary() {
        try(ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                currentActivity = activity;
                activity.navigateToTab(1);
                activity.getSupportFragmentManager().executePendingTransactions();
                View search = activity.findViewById(R.id.searchBox);
                assertNotNull(search);
                assertTrue(search.requestFocus());
                InputMethodManager input = (InputMethodManager)
                        activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
                assertNotNull(input);
                input.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT);
            });
            assertTrue("search IME never became visible", waitForIme(scenario, true));
            scenario.onActivity(activity -> activity.navigateToTab(2));
            assertTrue("search IME still covers the library after tab change",
                    waitForIme(scenario, false));
            scenario.onActivity(activity -> {
                View search = activity.fragments[1] == null ||
                        activity.fragments[1].getView() == null ? null :
                        activity.fragments[1].getView().findViewById(R.id.searchBox);
                assertNotNull(search);
                assertFalse("hidden search field retained input focus", search.hasFocus());
            });
        } finally {
            currentActivity = null;
        }
    }

    private boolean waitForIme(ActivityScenario<MainActivity> scenario, boolean visible) {
        long deadline = SystemClock.uptimeMillis() + 5_000L;
        while(SystemClock.uptimeMillis() < deadline) {
            AtomicBoolean matches = new AtomicBoolean(false);
            scenario.onActivity(activity -> {
                WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
                matches.set(insets != null && insets.isVisible(WindowInsets.Type.ime()) == visible);
            });
            if(matches.get())
                return true;
            SystemClock.sleep(50L);
        }
        return false;
    }

    private void exercise(Intent intent) {
        try(ActivityScenario<? extends Activity> scenario = ActivityScenario.launch(intent)) {
            scenario.onActivity(activity -> {
                currentActivity = activity;
                assertUsable(activity);
                activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(this::assertUsable);
            scenario.recreate();
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                currentActivity = activity;
                assertUsable(activity);
            });
        } finally {
            currentActivity = null;
        }
    }

    private void exerciseDialog(Intent intent) {
        try(ActivityScenario<? extends Activity> scenario = ActivityScenario.launch(intent)) {
            scenario.onActivity(activity -> {
                currentActivity = activity;
                assertWindowUsable(activity);
                activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(this::assertWindowUsable);
            scenario.recreate();
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                currentActivity = activity;
                assertWindowUsable(activity);
            });
        } finally {
            currentActivity = null;
        }
    }

    private Intent intent(Class<? extends Activity> activityClass) {
        return new Intent(
                InstrumentationRegistry.getInstrumentation().getTargetContext(),
                activityClass);
    }

    private void assertUsable(Activity activity) {
        assertWindowUsable(activity);
        View content = activity.findViewById(android.R.id.content);
        assertTrue(content instanceof ViewGroup);
        assertTrue(activity.getClass().getSimpleName() + " has no content",
                ((ViewGroup)content).getChildCount() > 0);
        assertTrue(activity.getClass().getSimpleName() + " has no visible usable content",
                hasVisibleUsableContent(content));
        assertScreenSpecificControls(activity);
    }

    private void assertWindowUsable(Activity activity) {
        assertFalse(activity.getClass().getSimpleName() + " unexpectedly finished", activity.isFinishing());
        View decor = activity.getWindow().getDecorView();
        assertNotNull(decor);
        assertTrue(activity.getClass().getSimpleName() + " width", decor.getWidth() > 0);
        assertTrue(activity.getClass().getSimpleName() + " height", decor.getHeight() > 0);
        assertTrue(activity.getClass().getSimpleName() + " hidden", decor.isShown());
        assertTrue(activity.getClass().getSimpleName() + " became untouchable",
                (activity.getWindow().getAttributes().flags &
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0);
    }

    private void assertScreenSpecificControls(Activity activity) {
        if(activity instanceof DownloadActivity) {
            assertVisible(activity, R.id.dl_eplist);
            assertVisible(activity, R.id.dl_btn);
            assertVisible(activity, R.id.dl_all_btn);
        } else if(activity instanceof FolderSelectActivity) {
            assertVisible(activity, R.id.dirList);
            assertVisible(activity, R.id.dirSelectBtn);
            assertVisible(activity, R.id.storageSelectBtn);
        } else if(activity instanceof LayoutEditActivity) {
            assertVisible(activity, R.id.layoutLeftButton);
            assertVisible(activity, R.id.layoutRightButton);
            assertVisible(activity, R.id.layout_save);
        } else if(activity instanceof TagSearchActivity) {
            assertVisible(activity, R.id.tagSearchToolbar);
            assertVisible(activity, R.id.tagSearchResult);
        } else if(activity instanceof FirstTimeActivity) {
            assertPresentAndEnabled(activity, R.id.first_def_url);
            assertPresentAndEnabled(activity, R.id.eulaAgreeBtn);
            assertPresentAndEnabled(activity, R.id.eulaNoUrlBtn);
        }
    }

    private void assertPresentAndEnabled(Activity activity, int id) {
        View view = activity.findViewById(id);
        assertNotNull(activity.getClass().getSimpleName() + " missing " + id, view);
        assertTrue(activity.getClass().getSimpleName() + " disabled " + id, view.isEnabled());
    }

    private void assertVisible(Activity activity, int id) {
        View view = activity.findViewById(id);
        assertNotNull(activity.getClass().getSimpleName() + " missing " + id, view);
        Rect visible = new Rect();
        assertTrue(activity.getClass().getSimpleName() + " clipped " + id,
                view.getVisibility() == View.VISIBLE && view.getGlobalVisibleRect(visible) &&
                        visible.width() > 0 && visible.height() > 0);
    }

    private boolean hasVisibleUsableContent(View view) {
        if(view.getVisibility() != View.VISIBLE || view.getWidth() <= 0 || view.getHeight() <= 0)
            return false;
        Rect visible = new Rect();
        if((view.isClickable() || view.isFocusable() || view.canScrollVertically(1) ||
                view.canScrollVertically(-1) || view.canScrollHorizontally(1) ||
                view.canScrollHorizontally(-1)) && view.getGlobalVisibleRect(visible) &&
                visible.width() > 0 && visible.height() > 0)
            return true;
        if(view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for(int index = 0; index < group.getChildCount(); index++) {
                if(hasVisibleUsableContent(group.getChildAt(index)))
                    return true;
            }
        }
        return false;
    }

    private void assertMainChromeDoesNotOverlap(MainActivity activity) {
        View content = activity.findViewById(R.id.contentHolder);
        View navigation = activity.findViewById(R.id.bottom_nav);
        assertNotNull(content);
        assertNotNull(navigation);
        Rect contentBounds = new Rect();
        Rect navigationBounds = new Rect();
        assertTrue(content.getGlobalVisibleRect(contentBounds));
        assertTrue(navigation.getGlobalVisibleRect(navigationBounds));
        assertTrue("bottom navigation covers catalogue content: " + contentBounds + " / " + navigationBounds,
                contentBounds.bottom <= navigationBounds.top);
        View modeToggle = activity.findViewById(R.id.mainModeToggle);
        if(modeToggle != null)
            assertTrue("reader mode toggle disappeared in a compact window",
                    modeToggle.getVisibility() == View.VISIBLE);
    }
}
