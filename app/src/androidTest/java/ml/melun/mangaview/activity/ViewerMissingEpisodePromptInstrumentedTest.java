package ml.melun.mangaview.activity;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.BySelector;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import ml.melun.mangaview.MainApplication;
import ml.melun.mangaview.R;
import ml.melun.mangaview.Utils;
import ml.melun.mangaview.LiveNetworkAssume;
import ml.melun.mangaview.mangaview.CustomHttpClient;
import ml.melun.mangaview.mangaview.MTitle;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;
import ml.melun.mangaview.repository.MangaRepository;

import static ml.melun.mangaview.mangaview.Title.LOAD_OK;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class ViewerMissingEpisodePromptInstrumentedTest {
    private static final String PACKAGE_NAME = "ml.melun.mangaview";

    @Test
    public void wfwfDemonDaughterNextFrom1Opens2BeforeHyphenPartEpisode() {
        Title title = new Title(
                "마왕의 딸은 너무 착해!!",
                "",
                "",
                Collections.singletonList("판타지"),
                "",
                10001,
                MTitle.base_comic);
        title.setSourceSite("wfwf");
        Manga episodeElevenTwo = new Manga(20, "마왕의 딸은 너무 착해!! 11-2화", "", MTitle.base_comic);
        episodeElevenTwo.setTitle(title);
        episodeElevenTwo.setTitleId(title.getId());
        Manga episodeTwo = new Manga(2, "마왕의 딸은 너무 착해!! 2화", "", MTitle.base_comic);
        episodeTwo.setTitle(title);
        episodeTwo.setTitleId(title.getId());
        episodeTwo.setImgs(Collections.singletonList("https://example.com/demon-daughter-2.jpg"));
        Manga episodeOne = new Manga(1, "마왕의 딸은 너무 착해!! 1화", "", MTitle.base_comic);
        episodeOne.setTitle(title);
        episodeOne.setTitleId(title.getId());
        episodeOne.setImgs(Collections.singletonList("https://example.com/demon-daughter-1.jpg"));
        Manga special = new Manga(19, "마왕의 딸은 너무 착해!! 번외편", "", MTitle.base_comic);
        special.setTitle(title);
        special.setTitleId(title.getId());
        ArrayList<Manga> episodes = new ArrayList<>();
        episodes.add(episodeElevenTwo);
        episodes.add(episodeOne);
        episodes.add(special);
        episodes.add(episodeTwo);
        title.setEps(episodes);
        episodeOne.setEps(episodes);

        assertEquals("마왕의 딸은 너무 착해!! 2화", episodeOne.nextEp().getName());

        Activity activity = InstrumentationRegistry.getInstrumentation().startActivitySync(viewerIntent(episodeOne, title));
        try {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertTrue("Expected viewer to start on WFWF 1화",
                    waitForToolbarTitle(activity, "1화", 10000));

            UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
            assertTrue("Expected viewer backing list to include WFWF 2화",
                    setViewerEpisodeImages(activity, 2, Collections.singletonList("https://example.com/demon-daughter-2.jpg")));
            View next = waitForEnabledView(activity, R.id.toolbar_next, 10000);
            assertNotNull("Expected WFWF 1화 next button to be enabled", next);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(next::performClick);

            assertTrue("Expected WFWF next from 1화 to open 2화",
                    waitForToolbarTitle(activity, "2화", 10000));
            assertFalse("WFWF next from 1화 must not land on 11-2화 while 2화 exists",
                    toolbarTitle(activity).contains("11-2화"));
            assertTrue("Expected no missing episode dialog while 2화 exists",
                    device.wait(Until.findObject(By.text("회차 누락")), 1000) == null);
        } finally {
            activity.finish();
        }
    }

    @Test
    public void wfwfScrollUpFromEpisode2PrependsEpisode1() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        Title title = new Title(
                "WFWF Scroll Boundary",
                "",
                "",
                Collections.singletonList("test"),
                "",
                100001,
                MTitle.base_comic);
        title.setSourceSite("wfwf");

        Manga episodeTwo = new Manga(2, "WFWF Scroll Boundary 2화", "", MTitle.base_comic);
        episodeTwo.setTitle(title);
        episodeTwo.setTitleId(title.getId());
        episodeTwo.setImgs(Collections.singletonList(testImagePath(context, "wfwf-boundary-2.png", Color.rgb(30, 90, 170))));
        Manga episodeOne = new Manga(1, "WFWF Scroll Boundary 1화", "", MTitle.base_comic);
        episodeOne.setTitle(title);
        episodeOne.setTitleId(title.getId());
        episodeOne.setImgs(Collections.singletonList(testImagePath(context, "wfwf-boundary-1.png", Color.rgb(170, 70, 30))));

        ArrayList<Manga> episodes = new ArrayList<>();
        episodes.add(episodeTwo);
        episodes.add(episodeOne);
        title.setEps(episodes);
        episodeTwo.setEps(episodes);
        episodeOne.setEps(episodes);

        Activity activity = InstrumentationRegistry.getInstrumentation().startActivitySync(viewerIntent(episodeTwo, title));
        try {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertTrue("Expected viewer to start on WFWF 2화",
                    waitForToolbarTitle(activity, "2화", 10000));
            assertTrue("Expected viewer backing list to include WFWF 1화",
                    setViewerEpisodeImages(activity, 1, Collections.singletonList(
                            testImagePath(context, "wfwf-boundary-1.png", Color.rgb(170, 70, 30)))));

            UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
            int width = device.getDisplayWidth();
            int height = device.getDisplayHeight();
            int x = width / 2;
            int fromY = Math.max(120, height / 4);
            int toY = Math.min(height - 160, height * 3 / 4);
            for(int swipe = 0; swipe < 4 && !toolbarTitle(activity).contains("1화"); swipe++) {
                device.swipe(x, fromY, x, toY, 36);
                SystemClock.sleep(700);
            }

            assertTrue("Expected upward scroll at top to move into previous WFWF episode 1화",
                    waitForToolbarTitle(activity, "1화", 10000));
        } finally {
            activity.finish();
        }
    }

    @Test
    public void wfwfScrollDownFromEpisode1AppendsEpisode2() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        Title title = new Title(
                "WFWF Scroll Boundary",
                "",
                "",
                Collections.singletonList("test"),
                "",
                100002,
                MTitle.base_comic);
        title.setSourceSite("wfwf");

        Manga episodeTwo = new Manga(2, "WFWF Scroll Boundary 2화", "", MTitle.base_comic);
        episodeTwo.setTitle(title);
        episodeTwo.setTitleId(title.getId());
        episodeTwo.setImgs(Collections.singletonList(testImagePath(context, "wfwf-boundary-tall-2.png", Color.rgb(30, 90, 170))));
        Manga episodeOne = new Manga(1, "WFWF Scroll Boundary 1화", "", MTitle.base_comic);
        episodeOne.setTitle(title);
        episodeOne.setTitleId(title.getId());
        episodeOne.setImgs(Collections.singletonList(testImagePath(context, "wfwf-boundary-tall-1.png", Color.rgb(170, 70, 30))));

        ArrayList<Manga> episodes = new ArrayList<>();
        episodes.add(episodeTwo);
        episodes.add(episodeOne);
        title.setEps(episodes);
        episodeOne.setEps(episodes);
        episodeTwo.setEps(episodes);

        Activity activity = InstrumentationRegistry.getInstrumentation().startActivitySync(viewerIntent(episodeOne, title));
        try {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertTrue("Expected viewer to start on WFWF 1화",
                    waitForToolbarTitle(activity, "1화", 10000));
            assertTrue("Expected viewer backing list to include WFWF 2화",
                    setViewerEpisodeImages(activity, 2, Collections.singletonList(
                            testImagePath(context, "wfwf-boundary-tall-2.png", Color.rgb(30, 90, 170)))));

            UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
            int width = device.getDisplayWidth();
            int height = device.getDisplayHeight();
            int x = width / 2;
            int fromY = Math.min(height - 160, height * 3 / 4);
            int toY = Math.max(120, height / 4);
            for(int swipe = 0; swipe < 8 && !toolbarTitle(activity).contains("2화"); swipe++) {
                device.swipe(x, fromY, x, toY, 36);
                SystemClock.sleep(450);
            }

            assertTrue("Expected downward scroll at bottom to move into next WFWF episode 2화",
                    waitForToolbarTitle(activity, "2화", 10000));
        } finally {
            activity.finish();
        }
    }

    @Test
    public void wfwfSummertimeRenderingPromptsFrom74To80() {
        Title title = fetchWfwfSummertimeRendering();
        ArrayList<Manga> episodes = Utils.snapshotEpisodes(title);
        Manga episode74 = findEpisode(episodes, 74);
        Manga nextAfter74 = nextEpisodeInViewerOrder(episodes, episode74);

        assertNotNull("Expected WFWF Summertime Rendering 74화", episode74);
        assertNotNull("Expected an episode after 74화", nextAfter74);
        assertTrue("Expected WFWF Summertime Rendering 74화 to jump to 80화 in the actual list",
                matchesEpisodeNumber(nextAfter74.getName(), 80));

        episode74.setImgs(Collections.singletonList("https://example.com/summertime-74.jpg"));
        Activity activity = InstrumentationRegistry.getInstrumentation().startActivitySync(viewerIntent(episode74, title));
        try {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
            View next = waitForEnabledView(activity, R.id.toolbar_next, 10000);
            assertNotNull("Expected viewer next episode button", next);

            InstrumentationRegistry.getInstrumentation().runOnMainSync(next::performClick);

            assertNotNull("Expected missing episode dialog title",
                    device.wait(Until.findObject(By.text("회차 누락")), 5000));
            assertNotNull("Expected missing episode dialog to offer NTK",
                    device.wait(Until.findObject(By.textContains("NTK에서 마저 볼까요")), 5000));

            UiObject2 ntkButton = device.wait(Until.findObject(By.text("NTK에서 보기")), 5000);
            assertNotNull("Expected NTK continue button", ntkButton);
            ntkButton.click();

            assertTrue("Expected viewer to continue on NTK missing 75화",
                    waitForToolbarTitle(activity, "75화", 60000));
        } finally {
            activity.finish();
        }
    }

    @Test
    public void wfwfSummertimePathless74PromptsBefore80Not71() {
        Title title = fetchWfwfSummertimeRendering();
        ArrayList<Manga> episodes = Utils.snapshotEpisodes(title);
        Manga actual74 = findEpisode(episodes, 74);
        Manga actual71 = findEpisode(episodes, 71);

        assertNotNull("Expected WFWF Summertime Rendering 74화", actual74);
        assertNotNull("Expected WFWF Summertime Rendering 71화", actual71);
        assertTrue("Expected WFWF 74화 to use a shifted source id",
                actual74.getId() != 74);

        actual74.setImgs(Collections.singletonList("https://example.com/wfwf-summertime-74.jpg"));
        Manga legacyVisible74 = new Manga(74, "서머타임 렌더링 74화", "", MTitle.base_comic);
        legacyVisible74.setTitle(title);
        legacyVisible74.setTitleId(title.getId());
        legacyVisible74.setEps(episodes);
        legacyVisible74.setImgs(Collections.singletonList("https://example.com/legacy-wfwf-summertime-74.jpg"));

        assertEquals("Pathless WFWF 74화 should resolve to the actual source id before opening",
                actual74.getUrl(), legacyVisible74.getUrl());

        Activity activity = InstrumentationRegistry.getInstrumentation().startActivitySync(viewerIntent(legacyVisible74, title));
        try {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertTrue("Expected viewer to start on WFWF 74화",
                    waitForToolbarTitle(activity, "74화", 10000));

            UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
            View next = waitForEnabledView(activity, R.id.toolbar_next, 10000);
            assertNotNull("Expected WFWF 74화 next button to be enabled", next);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(next::performClick);

            assertNotNull("Expected missing episode dialog instead of jumping to 71화",
                    device.wait(Until.findObject(By.text("회차 누락")), 5000));
            assertNotNull("Expected missing episode dialog to offer NTK after 74화",
                    device.wait(Until.findObject(By.textContains("NTK에서 마저 볼까요")), 5000));
            assertFalse("WFWF next from pathless 74화 must not land on 71화",
                    toolbarTitle(activity).contains("71화"));
        } finally {
            activity.finish();
        }
    }

    @Test
    public void wfwfContinueToNtkBackReturnsToNtkEpisodeList() {
        Title title = fetchWfwfSummertimeRendering();
        ArrayList<Manga> episodes = Utils.snapshotEpisodes(title);
        Manga episode74 = findEpisode(episodes, 74);

        assertNotNull("Expected WFWF Summertime Rendering 74화", episode74);
        episode74.setImgs(Collections.singletonList("https://example.com/wfwf-summertime-74.jpg"));

        ActivityScenario<EpisodeActivity> scenario = ActivityScenario.launch(episodeIntent(title));
        try {
            scenario.onActivity(activity ->
                    activity.startActivityForResult(viewerIntent(activity, episode74, title, false), 0));

            UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
            assertTrue("Expected viewer to start on WFWF 74화",
                    waitForUiToolbarTitle(device, "74화", 10000));

            UiObject2 next = waitForEnabledObject(device, By.res(PACKAGE_NAME, "toolbar_next"), 10000);
            assertNotNull("Expected WFWF 74화 next button to be enabled", next);
            next.click();

            assertNotNull("Expected missing episode dialog title",
                    device.wait(Until.findObject(By.text("회차 누락")), 5000));
            UiObject2 ntkButton = device.wait(Until.findObject(By.text("NTK에서 보기")), 5000);
            assertNotNull("Expected NTK continue button", ntkButton);
            ntkButton.click();

            assertTrue("Expected viewer to continue on NTK missing 75화",
                    waitForUiToolbarTitle(device, "75화", 60000));
            device.pressBack();

            assertNotNull("Expected back from switched viewer to show an episode list",
                    device.wait(Until.findObject(By.res(PACKAGE_NAME, "EpisodeList")), 60000));
            assertTrue("Expected returned episode list to use NTK, not the original WFWF title",
                    waitForCurrentEpisodeActivitySource("ntk", 10000));
        } finally {
            scenario.close();
            finishResumedActivities();
        }
    }

    @Test
    public void ntkSummertimePickerKeepsVisibleEpisodeMappedToNtkPath() {
        Title title = fetchNtkSummertimeRendering();
        ArrayList<Manga> episodes = Utils.snapshotEpisodes(title);
        Manga episode75 = findEpisode(episodes, 75);
        Manga episode91 = findEpisode(episodes, 91);

        assertNotNull("Expected NTK Summertime Rendering 75화", episode75);
        assertNotNull("Expected NTK Summertime Rendering 91화", episode91);
        assertTrue("Expected NTK 91화 to have a concrete source episode path",
                episode91.getNtkEpisodePath().contains("/manhwa/7843/"));
        assertFalse("NTK 91화 must not fall back to visible episode-number URL",
                episode91.getNtkEpisodePath().endsWith("/91"));

        episode75.setImgs(Collections.singletonList("https://example.com/ntk-summertime-75.jpg"));
        episode91.setImgs(Collections.singletonList("https://example.com/ntk-summertime-91.jpg"));
        Activity activity = InstrumentationRegistry.getInstrumentation().startActivitySync(viewerIntent(episode75, title));
        try {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertTrue("Expected viewer picker backing list to keep actual NTK paths",
                    waitForViewerEpisodePath(activity, "91화", episode91.getNtkEpisodePath(), 10000));
            Manga pickerEpisode91 = viewerEpisode(activity, 91);
            assertNotNull("Expected viewer picker backing list to include 91화", pickerEpisode91);
            pickerEpisode91.setImgs(Collections.singletonList("https://example.com/ntk-summertime-91.jpg"));

            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> ((ReaderV2Activity) activity).testOpenEpisode(pickerEpisode91));
            assertTrue("Expected picker-selected NTK 91화 to open as 91화",
                    waitForToolbarTitle(activity, "91화", 60000));
            assertFalse("NTK 91화 selection must not land on 87화",
                    toolbarTitle(activity).contains("87화"));
        } finally {
            activity.finish();
        }
    }

    @Test
    public void ntkSummertimeNextFromPathless90Opens91Not87() {
        Title title = fetchNtkSummertimeRendering();
        ArrayList<Manga> episodes = Utils.snapshotEpisodes(title);
        Manga actual90 = findEpisode(episodes, 90);
        Manga actual91 = findEpisode(episodes, 91);
        Manga actual87 = findEpisode(episodes, 87);

        assertNotNull("Expected NTK Summertime Rendering 90화", actual90);
        assertNotNull("Expected NTK Summertime Rendering 91화", actual91);
        assertNotNull("Expected NTK Summertime Rendering 87화", actual87);
        assertFalse("NTK 90화 and 87화 should be different source paths",
                actual90.getNtkEpisodePath().equals(actual87.getNtkEpisodePath()));

        actual90.setImgs(Collections.singletonList("https://example.com/ntk-summertime-90.jpg"));
        actual91.setImgs(Collections.singletonList("https://example.com/ntk-summertime-91.jpg"));

        Manga legacyVisible90 = new Manga(90, "서머타임 렌더링 90화", "", MTitle.base_comic);
        legacyVisible90.setTitle(title);
        legacyVisible90.setTitleId(title.getId());
        legacyVisible90.setEps(episodes);
        legacyVisible90.setImgs(Collections.singletonList("https://example.com/legacy-ntk-summertime-90.jpg"));

        assertEquals("Pathless legacy 90화 should resolve to actual NTK 90화 path before opening",
                actual90.getNtkEpisodePath(), legacyVisible90.getNtkEpisodePath());

        Activity activity = InstrumentationRegistry.getInstrumentation().startActivitySync(viewerIntent(legacyVisible90, title));
        try {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertTrue("Expected viewer to start on NTK 90화",
                    waitForToolbarTitle(activity, "90화", 10000));

            View next = waitForEnabledView(activity, R.id.toolbar_next, 10000);
            assertNotNull("Expected NTK 90화 next button to be enabled", next);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(next::performClick);

            assertTrue("Expected NTK next from 90화 to open 91화",
                    waitForToolbarTitle(activity, "91화", 60000));
            assertFalse("NTK next from 90화 must not land on 87화",
                    toolbarTitle(activity).contains("87화"));
        } finally {
            activity.finish();
        }
    }

    private Title fetchWfwfSummertimeRendering() {
        LiveNetworkAssume.assumeEnabled();
        MainApplication.p.setSitePreset(CustomHttpClient.DEFAULT_COMIC_URL, CustomHttpClient.WEBTOON_URL);
        MainApplication.p.setBaseMode(MTitle.base_comic);

        Title title = new Title(
                "서머타임 렌더링",
                "",
                "",
                Collections.singletonList("미스터리"),
                "",
                10017,
                MTitle.base_comic);
        title.setSourceSite("wfwf");

        int result = MangaRepository.fetchEpisodesForeground(title);
        assertEquals("Expected WFWF Summertime Rendering episodes to load", LOAD_OK, result);
        return title;
    }

    private Title fetchNtkSummertimeRendering() {
        LiveNetworkAssume.assumeEnabled();
        MainApplication.p.setNtkSitePreset(CustomHttpClient.NTK_WEBTOON_URL);
        MainApplication.p.setBaseMode(MTitle.base_comic);

        Title title = new Title(
                "서머타임 렌더링",
                "",
                "",
                Collections.singletonList("미스터리"),
                "",
                7843,
                MTitle.base_comic);
        title.setSourceSite("ntk");
        title.setPath("/manhwa/7843");

        int result = MangaRepository.fetchEpisodesForeground(title);
        assertEquals("Expected NTK Summertime Rendering episodes to load", LOAD_OK, result);
        return title;
    }

    private Intent viewerIntent(Manga episode, Title title) {
        Context context = ApplicationProvider.getApplicationContext();
        return viewerIntent(context, episode, title, true);
    }

    private static String testImagePath(Context context, String name, int color) throws Exception {
        File file = new File(context.getCacheDir(), name);
        if(file.isFile() && file.length() > 0)
            return file.getAbsolutePath();
        int imageHeight = name.contains("tall") ? 2200 : 1440;
        Bitmap bitmap = Bitmap.createBitmap(720, imageHeight, Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(color);
        try(FileOutputStream output = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        } finally {
            bitmap.recycle();
        }
        return file.getAbsolutePath();
    }

    private Intent viewerIntent(Context context, Manga episode, Title title, boolean newTask) {
        Intent intent = new Intent(context, ReaderV2Activity.class);
        intent.putExtra("online", true);
        intent.putExtra("title", Utils.toViewerTitleJson(title, true));
        intent.putExtra("manga", Utils.toViewerMangaJson(episode, title));
        intent.putExtra(ViewerIntentContract.EXTRA_EXACT_EPISODE, true);
        intent.putExtra(ViewerIntentContract.EXTRA_START_AT_FIRST_PAGE, true);
        if(newTask)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    private Intent episodeIntent(Title title) {
        Context context = ApplicationProvider.getApplicationContext();
        Intent intent = new Intent(context, EpisodeActivity.class);
        intent.putExtra("online", true);
        intent.putExtra("title", Utils.toViewerTitleJson(title, true));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    private static Manga findEpisode(List<Manga> episodes, int number) {
        if(episodes == null)
            return null;
        for(Manga episode : episodes)
            if(episode != null && matchesEpisodeNumber(episode.getName(), number))
                return episode;
        return null;
    }

    private static Manga nextEpisodeInViewerOrder(List<Manga> episodes, Manga current) {
        if(episodes == null || current == null)
            return null;
        int index = episodes.indexOf(current);
        return index > 0 ? episodes.get(index - 1) : null;
    }

    private static boolean matchesEpisodeNumber(String name, int number) {
        if(name == null)
            return false;
        return Pattern.compile("(^|\\D)0*" + number + "\\s*화").matcher(name).find();
    }

    private static View waitForEnabledView(Activity activity, int id, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while(SystemClock.elapsedRealtime() < deadline) {
            final View[] result = new View[1];
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                View view = activity.findViewById(id);
                if(view != null && view.isEnabled())
                    result[0] = view;
            });
            if(result[0] != null)
                return result[0];
            SystemClock.sleep(250);
        }
        return null;
    }

    private static boolean waitForViewerEpisodePath(Activity activity, String episodeName, String expectedPath, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while(SystemClock.elapsedRealtime() < deadline) {
            Manga episode = viewerEpisode(activity, episodeName);
            if(episode != null && expectedPath.equals(episode.getNtkEpisodePath()))
                return true;
            SystemClock.sleep(250);
        }
        return false;
    }

    private static Manga viewerEpisode(Activity activity, int episodeNumber) {
        final Manga[] result = new Manga[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            if(!(activity instanceof ReaderV2Activity))
                return;
            result[0] = ((ReaderV2Activity) activity).testEpisode(episodeNumber);
        });
        return result[0];
    }

    private static boolean setViewerEpisodeImages(Activity activity, int episodeNumber, List<String> images) {
        final boolean[] found = {false};
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            if(!(activity instanceof ReaderV2Activity))
                return;
            found[0] = ((ReaderV2Activity) activity).testSetEpisodeImages(episodeNumber, images);
        });
        return found[0];
    }

    private static boolean setEpisodeImages(List<Manga> episodes, int episodeNumber, List<String> images) {
        if(episodes == null)
            return false;
        boolean found = false;
        for(Manga episode : episodes) {
            if(episode != null && matchesEpisodeNumber(episode.getName(), episodeNumber)) {
                episode.setImgs(images);
                found = true;
            }
        }
        return found;
    }

    private static Manga viewerEpisode(Activity activity, String episodeName) {
        final Manga[] result = new Manga[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            if(!(activity instanceof ReaderV2Activity))
                return;
            result[0] = ((ReaderV2Activity) activity).testEpisode(episodeName);
        });
        return result[0];
    }

    private static boolean waitForToolbarTitle(Activity activity, String expectedText, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while(SystemClock.elapsedRealtime() < deadline) {
            String[] text = {toolbarTitle(activity)};
            if(text[0] != null && text[0].contains(expectedText))
                return true;
            SystemClock.sleep(500);
        }
        return false;
    }

    private static String toolbarTitle(Activity activity) {
        final String[] text = new String[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            TextView title = activity.findViewById(R.id.toolbar_title);
            text[0] = title == null || title.getText() == null ? "" : title.getText().toString();
        });
        return text[0] == null ? "" : text[0];
    }

    private static UiObject2 waitForEnabledObject(UiDevice device, BySelector selector, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while(SystemClock.elapsedRealtime() < deadline) {
            UiObject2 object = device.findObject(selector);
            if(object != null && object.isEnabled())
                return object;
            SystemClock.sleep(250);
        }
        return null;
    }

    private static boolean waitForUiToolbarTitle(UiDevice device, String expectedText, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        boolean tappedReader = false;
        while(SystemClock.elapsedRealtime() < deadline) {
            UiObject2 title = device.findObject(By.res(PACKAGE_NAME, "toolbar_title"));
            String text = title == null ? "" : title.getText();
            if(text != null && text.contains(expectedText))
                return true;
            if(!tappedReader) {
                UiObject2 strip = device.findObject(By.res(PACKAGE_NAME, "strip"));
                if(strip != null) {
                    android.graphics.Rect bounds = strip.getVisibleBounds();
                    device.click(bounds.centerX(), bounds.centerY());
                    tappedReader = true;
                }
            }
            SystemClock.sleep(500);
        }
        return false;
    }

    private static boolean waitForCurrentEpisodeActivitySource(String expectedSource, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while(SystemClock.elapsedRealtime() < deadline) {
            final String[] source = new String[1];
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                Collection<Activity> activities = ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(Stage.RESUMED);
                for(Activity activity : activities) {
                    if(activity instanceof EpisodeActivity) {
                        Title title = ((EpisodeActivity) activity).title;
                        source[0] = title == null ? null : title.getSourceSite();
                        return;
                    }
                }
            });
            if(expectedSource.equals(source[0]))
                return true;
            SystemClock.sleep(250);
        }
        return false;
    }

    private static void finishResumedActivities() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Collection<Activity> activities = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED);
            for(Activity activity : activities)
                activity.finish();
        });
    }
}
