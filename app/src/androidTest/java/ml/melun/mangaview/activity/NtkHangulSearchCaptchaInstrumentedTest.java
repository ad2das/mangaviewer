package ml.melun.mangaview.activity;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import ml.melun.mangaview.LiveNetworkAssume;
import ml.melun.mangaview.MainApplication;
import ml.melun.mangaview.R;
import ml.melun.mangaview.mangaview.MTitle;

@RunWith(AndroidJUnit4.class)
public class NtkHangulSearchCaptchaInstrumentedTest {
    private static final String PACKAGE_NAME = "ml.melun.mangaview";
    private static final String JAGAAN_QUERY = "\uC7C8\uAC74";

    @Before
    public void requireLiveNetworkOptIn() {
        LiveNetworkAssume.assumeEnabled();
    }

    @Test
    public void hangulSearchOpensCaptchaInsteadOfNoResultsWhenNtkRequiresChallenge() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences("mangaView", Context.MODE_PRIVATE).edit().clear().commit();
        MainApplication.p.init(context);
        MainApplication.p.setNtkSitePreset("https://sbxh3.com");
        MainApplication.p.setBaseMode(MTitle.base_comic);

        try(ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
            device.wait(Until.hasObject(By.res(PACKAGE_NAME, "bottomNavigationView")), 30000L);
            UiObject2 searchTab = device.wait(Until.findObject(By.res(PACKAGE_NAME, "nav_search")), 10000L);
            assertTrue("Expected search tab to be visible", searchTab != null);
            searchTab.click();
            assertTrue("Expected search box to be visible",
                    device.wait(Until.hasObject(By.res(PACKAGE_NAME, "searchBox")), 10000L));

            scenario.onActivity(activity -> activity.search(JAGAAN_QUERY));

            boolean captchaOrResult = false;
            long deadline = System.currentTimeMillis() + 90000L;
            while(System.currentTimeMillis() < deadline) {
                if(device.findObject(By.res(PACKAGE_NAME, "captchaContainer")) != null) {
                    captchaOrResult = true;
                    break;
                }
                UiObject2 result = device.findObject(By.res(PACKAGE_NAME, "searchResult"));
                if(result != null && result.getChildCount() > 0) {
                    captchaOrResult = true;
                    break;
                }
                UiObject2 noResult = visibleNoResult(device);
                if(noResult != null)
                    break;
                Thread.sleep(500L);
            }

            assertNull("NTK Hangul search should not silently fall back to no results while captcha is required",
                    visibleNoResult(device));
            assertTrue("Expected NTK Hangul search to show captcha or real search results", captchaOrResult);
        }
    }

    private UiObject2 visibleNoResult(UiDevice device) {
        UiObject2 noResultText = device.findObject(By.res(PACKAGE_NAME, "noResultText"));
        if(noResultText == null)
            return null;
        String text = noResultText.getText();
        return text != null && text.contains(JAGAAN_QUERY) ? noResultText : null;
    }
}
