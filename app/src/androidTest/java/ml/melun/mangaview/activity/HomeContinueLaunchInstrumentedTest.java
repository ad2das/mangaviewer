package ml.melun.mangaview.activity;

import android.app.Activity;
import android.content.Context;
import android.os.SystemClock;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collection;

import ml.melun.mangaview.Utils;
import ml.melun.mangaview.MainApplication;
import ml.melun.mangaview.mangaview.MTitle;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;
import ml.melun.mangaview.reader.ReaderSurfaceView;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

@RunWith(AndroidJUnit4.class)
public class HomeContinueLaunchInstrumentedTest {
    @Test
    public void exactNtkHomeContinueLaunchesReaderWithoutPreparedImageKey() {
        Context context = ApplicationProvider.getApplicationContext();
        Title title = new Title(
                "Home continue regression",
                "",
                "",
                null,
                "",
                25694,
                MTitle.base_comic);
        title.setSourceSite("ntk");
        title.setBookmark(1767091);
        title.setResumeNtkImageIdentity("25694", "1767091", 112);
        Manga resume = ViewerResumeResolver.resumeManga(title);
        assertNotNull(resume);
        MainApplication.p.setViewerBookmark(resume, 2, -420, 0);

        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> Utils.openContinueViewer(context, resume, -1));

        ReaderV2Activity reader = waitForReader(10000L);
        try {
            assertNotNull("Home continue must enter ReaderV2 instead of showing a preparation toast",
                    reader);
            assertEquals("/manhwa/25694/1767091", reader.testCurrentNtkEpisodePath());
            assertFalse("Continue must resume the saved page instead of forcing page zero",
                    reader.getIntent().getBooleanExtra(
                            ViewerIntentContract.EXTRA_START_AT_FIRST_PAGE, false));
            assertFalse("Cold home continue must not depend on a prepared image key",
                    reader.getIntent().hasExtra(
                            ml.melun.mangaview.reader.ReaderLaunchPreparer.EXTRA_PREPARED_KEY));
            assertEquals("Strict NTK session must use the saved per-episode page",
                    2, waitForSessionStartPage(reader, 20000L));
            ReaderSurfaceView.ProgressPosition restored =
                    waitForProgressPosition(reader, 2, -420, 20000L);
            assertNotNull("Reader surface must restore the saved page and offset", restored);
            UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
            assertNull("Home continue must not show the old permanent preparation toast",
                    device.wait(Until.findObject(By.textContains("회차 준비 중")), 750L));
        } finally {
            if(reader != null) {
                InstrumentationRegistry.getInstrumentation().runOnMainSync(reader::finish);
            }
        }
    }

    private static ReaderSurfaceView.ProgressPosition waitForProgressPosition(
            ReaderV2Activity reader,
            int expectedPage,
            int expectedOffset,
            long timeoutMs
    ) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while(SystemClock.elapsedRealtime() < deadline) {
            final ReaderSurfaceView.ProgressPosition[] result = {null};
            InstrumentationRegistry.getInstrumentation().runOnMainSync(
                    () -> result[0] = reader.testCurrentProgressPosition());
            ReaderSurfaceView.ProgressPosition position = result[0];
            if(position != null
                    && position.getPage() == expectedPage
                    && Math.abs(position.getOffset() - expectedOffset) <= 2)
                return position;
            SystemClock.sleep(100L);
        }
        return null;
    }

    private static int waitForSessionStartPage(ReaderV2Activity reader, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while(SystemClock.elapsedRealtime() < deadline) {
            final int[] result = {-1};
            InstrumentationRegistry.getInstrumentation().runOnMainSync(
                    () -> result[0] = reader.testSessionInitialStartPage());
            if(result[0] >= 0)
                return result[0];
            SystemClock.sleep(100L);
        }
        return -1;
    }

    private static ReaderV2Activity waitForReader(long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while(SystemClock.elapsedRealtime() < deadline) {
            final ReaderV2Activity[] result = new ReaderV2Activity[1];
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                Collection<Activity> activities = ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(Stage.RESUMED);
                for(Activity activity : activities) {
                    if(activity instanceof ReaderV2Activity) {
                        result[0] = (ReaderV2Activity) activity;
                        return;
                    }
                }
            });
            if(result[0] != null)
                return result[0];
            SystemClock.sleep(100L);
        }
        return null;
    }
}
