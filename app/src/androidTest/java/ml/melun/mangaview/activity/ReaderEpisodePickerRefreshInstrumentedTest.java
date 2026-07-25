package ml.melun.mangaview.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;

import ml.melun.mangaview.MainApplication;
import ml.melun.mangaview.Utils;
import ml.melun.mangaview.mangaview.MTitle;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;

public class ReaderEpisodePickerRefreshInstrumentedTest {
    @Test
    public void partialNeighborEpisodeListRefreshesToServerList() {
        Context context = ApplicationProvider.getApplicationContext();
        MainApplication.p.setNtkSitePreset("https://sbxh4.com");
        MainApplication.p.setBaseMode(MTitle.base_webtoon);
        MainApplication.getHttpClient().clearPageCache();

        Title title = new Title(
                "Neighbor episode picker regression",
                "",
                "",
                new ArrayList<>(),
                "",
                840540,
                MTitle.base_webtoon);
        title.setSourceSite("ntk");
        title.setPath("/webtoon/840540");

        Manga current = new Manga(55, "55화", "", MTitle.base_webtoon);
        current.setNtkEpisodePath("/webtoon/840540/nv-840540-55");
        current.setImgs(Collections.singletonList(
                "https://i.toonflix.app/webtoon/840540/nv-840540-55/p001.jpg"));
        current.setTitle(title);
        current.setTitleId(title.getId());
        title.setEps(Collections.singletonList(current));
        title.setReadingProgress(current.getId(), 0, 55);

        Intent intent = new Intent(context, ReaderV2Activity.class);
        intent.putExtra("online", true);
        intent.putExtra("title", Utils.toViewerTitleJson(title, true));
        intent.putExtra("manga", Utils.toViewerMangaJson(current, title));
        intent.putExtra(ViewerIntentContract.EXTRA_EXACT_EPISODE, true);
        intent.putExtra(ViewerIntentContract.EXTRA_START_AT_FIRST_PAGE, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        Activity activity = InstrumentationRegistry.getInstrumentation().startActivitySync(intent);
        assertTrue(activity instanceof ReaderV2Activity);
        ReaderV2Activity reader = (ReaderV2Activity) activity;
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(reader::testOpenEpisodePicker);
            int refreshedCount = waitForEpisodeCount(reader, 55, 15000L);
            assertTrue("Expected all 55 server episodes in the reader picker, got " + refreshedCount,
                    refreshedCount >= 55);
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(reader::finish);
        }
    }

    @Test
    public void skyHighInvasionPickerIncludesEpisodeOne() {
        Context context = ApplicationProvider.getApplicationContext();
        MainApplication.p.setNtkSitePreset("https://sbxh4.com");
        MainApplication.p.setBaseMode(MTitle.base_comic);
        MainApplication.getHttpClient().clearPageCache();

        Title title = new Title(
                "천공 침범",
                "",
                "",
                new ArrayList<>(),
                "",
                3540,
                MTitle.base_comic);
        title.setSourceSite("ntk");
        title.setPath("/manhwa/3540");

        Manga current = new Manga(258, "258화", "", MTitle.base_comic);
        current.setNtkEpisodePath("/manhwa/3540/135918");
        current.setImgs(Collections.singletonList(
                "https://i.toonflix.app/blacktoon/episodes/3540/135918/p001.jpg"));
        current.setTitle(title);
        current.setTitleId(title.getId());
        title.setEps(Collections.singletonList(current));
        title.setReadingProgress(current.getId(), 0, 255);

        Intent intent = new Intent(context, ReaderV2Activity.class);
        intent.putExtra("online", true);
        intent.putExtra("title", Utils.toViewerTitleJson(title, true));
        intent.putExtra("manga", Utils.toViewerMangaJson(current, title));
        intent.putExtra(ViewerIntentContract.EXTRA_EXACT_EPISODE, true);
        intent.putExtra(ViewerIntentContract.EXTRA_START_AT_FIRST_PAGE, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        Activity activity = InstrumentationRegistry.getInstrumentation().startActivitySync(intent);
        assertTrue(activity instanceof ReaderV2Activity);
        ReaderV2Activity reader = (ReaderV2Activity) activity;
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(reader::testOpenEpisodePicker);
            int refreshedCount = waitForEpisodeCount(reader, 255, 25000L);
            assertEquals("Expected every server episode in the reader picker",
                    255, refreshedCount);
            assertEquals("The reader picker must include the actual 1화 entry",
                    "/manhwa/3540/44827",
                    waitForEpisodePath(reader, "1", 5000L));
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(reader::finish);
        }
    }

    private static int waitForEpisodeCount(
            ReaderV2Activity reader,
            int expected,
            long timeoutMs
    ) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        int latest = 0;
        while(SystemClock.elapsedRealtime() < deadline) {
            final int[] count = {0};
            InstrumentationRegistry.getInstrumentation().runOnMainSync(
                    () -> count[0] = reader.testEpisodeSnapshotCount());
            latest = count[0];
            if(latest >= expected)
                return latest;
            SystemClock.sleep(100L);
        }
        return latest;
    }

    private static String waitForEpisodePath(
            ReaderV2Activity reader,
            String visibleNumber,
            long timeoutMs
    ) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while(SystemClock.elapsedRealtime() < deadline) {
            final String[] path = {null};
            InstrumentationRegistry.getInstrumentation().runOnMainSync(
                    () -> path[0] = reader.testEpisodePathForVisibleNumber(visibleNumber));
            if(path[0] != null)
                return path[0];
            SystemClock.sleep(100L);
        }
        return null;
    }
}
