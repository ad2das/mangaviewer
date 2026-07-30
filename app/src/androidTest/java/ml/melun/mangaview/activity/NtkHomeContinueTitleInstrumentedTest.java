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
    private static final String AD_THUMB =
            "https://aws-cdn1.site/board_uploads/2026/06/24/065250_fdaad08ea68b.png";
    private static final String EXPECTED_THUMB =
            "https://i1.imgcloud18.com/10001/2266a3ee.jpg";

    @Test
    public void homeContinueRepairsStoredAdThumbnail() {
        Context context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences("mangaView", Context.MODE_PRIVATE).edit().clear().commit();
        MainApplication.p.init(context);
        MainApplication.p.setNtkSitePreset(CustomHttpClient.NTK_WEBTOON_URL);
        MainApplication.p.setBaseMode(MTitle.base_comic);

        MTitle recent = new MTitle(
                "마왕의 딸은 너무 착해!!",
                10001,
                AD_THUMB,
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
            assertTrue("Expected repaired thumbnail to be persisted",
                    waitForStoredMetadata(recent, "마왕의 딸은 너무 착해!!", EXPECTED_THUMB, 10000L));
            device.swipe(540, 1850, 540, 1100, 24);
            assertTrue("Expected repaired continue title on the home screen",
                    device.wait(Until.hasObject(By.text("마왕의 딸은 너무 착해!!")), 10000L));
            assertFalse(device.hasObject(By.text("뉴토끼 - 웹툰 미리보기")));

            MTitle stored = MainApplication.p.findRecentTitle(recent);
            assertNotNull(stored);
            assertEquals("마왕의 딸은 너무 착해!!", stored.getName());
            assertEquals(EXPECTED_THUMB, stored.getThumb());
        }
    }

    private boolean waitForStoredMetadata(
            MTitle recent, String expectedTitle, String expectedThumb, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while(SystemClock.elapsedRealtime() < deadline) {
            MTitle stored = MainApplication.p.findRecentTitle(recent);
            if(stored != null
                    && expectedTitle.equals(stored.getName())
                    && expectedThumb.equals(stored.getThumb()))
                return true;
            SystemClock.sleep(100L);
        }
        return false;
    }
}
