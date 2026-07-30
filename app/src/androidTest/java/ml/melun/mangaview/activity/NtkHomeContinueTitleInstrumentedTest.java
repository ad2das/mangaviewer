package ml.melun.mangaview.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.SystemClock;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;

import org.junit.Test;

import java.util.Collections;

import ml.melun.mangaview.MainApplication;
import ml.melun.mangaview.mangaview.CustomHttpClient;
import ml.melun.mangaview.mangaview.MTitle;

public class NtkHomeContinueTitleInstrumentedTest {
    private static final String PACKAGE_NAME = "ml.melun.mangaview";

    @Test
    public void homeContinueRepairsPreviouslyStoredSiteHeading() {
        Context context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences("mangaView", Context.MODE_PRIVATE).edit().clear().commit();
        MainApplication.p.init(context);
        MainApplication.p.setNtkSitePreset(CustomHttpClient.NTK_WEBTOON_URL);
        MainApplication.p.setBaseMode(MTitle.base_comic);

        MTitle recent = new MTitle(
                "뉴토끼 - 웹툰 미리보기",
                10001,
                "https://i1.imgcloud18.com/10001/2266a3ee.jpg",
                "",
                Collections.emptyList(),
                "32화",
                MTitle.base_comic);
        recent.setSourceSite("ntk");
        recent.setReadingProgress(32, 32, 32);
        MainApplication.p.setRecents(Collections.singletonList(recent));

        try(ActivityScenario<MainActivity> ignored = ActivityScenario.launch(MainActivity.class)) {
            UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
            assertTrue(device.wait(Until.hasObject(By.res(PACKAGE_NAME, "bottom_nav")), 10000L));
            assertTrue("Expected repaired title to be persisted",
                    waitForStoredTitle(recent, "마왕의 딸은 너무 착해!!", 10000L));
            device.swipe(540, 1850, 540, 1100, 24);
            assertTrue("Expected repaired continue title on the home screen",
                    device.wait(Until.hasObject(By.text("마왕의 딸은 너무 착해!!")), 10000L));
            assertFalse(device.hasObject(By.text("뉴토끼 - 웹툰 미리보기")));

            MTitle stored = MainApplication.p.findRecentTitle(recent);
            assertNotNull(stored);
            assertEquals("마왕의 딸은 너무 착해!!", stored.getName());
        }
    }

    private boolean waitForStoredTitle(MTitle recent, String expected, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while(SystemClock.elapsedRealtime() < deadline) {
            MTitle stored = MainApplication.p.findRecentTitle(recent);
            if(stored != null && expected.equals(stored.getName()))
                return true;
            SystemClock.sleep(100L);
        }
        return false;
    }
}
