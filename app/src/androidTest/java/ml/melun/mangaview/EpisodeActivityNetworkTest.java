package ml.melun.mangaview;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.View;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import org.junit.Test;
import org.junit.Before;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import ml.melun.mangaview.activity.CaptchaActivity;
import ml.melun.mangaview.activity.EpisodeActivity;
import ml.melun.mangaview.activity.ReaderV2Activity;
import ml.melun.mangaview.mangaview.CustomHttpClient;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.MTitle;
import ml.melun.mangaview.mangaview.Search;
import ml.melun.mangaview.mangaview.Title;
import ml.melun.mangaview.reader.ReaderSurfaceView;

@RunWith(AndroidJUnit4.class)
public class EpisodeActivityNetworkTest {
    private static final String PACKAGE_NAME = "ml.melun.mangaview";

    @Before
    public void requireLiveNetworkOptIn() {
        LiveNetworkAssume.assumeEnabled();
    }

    @Test
    public void ntkComicTitleOpensEpisodeList() throws Exception {
        launchNtkComicTitle();

        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        UiObject2 episodeList = device.wait(Until.findObject(By.res(PACKAGE_NAME, "EpisodeList")), 60000L);
        if(episodeList == null) {
            assertCaptchaShown(device, "NTK title");
            return;
        }
        assertNotNull("Expected NTK episode list to render", episodeList);
        UiObject2 episodeRow = device.wait(Until.findObject(By.res(PACKAGE_NAME, "episode")), 60000L);
        assertNotNull("Expected NTK title to render at least one episode", episodeRow);
    }

    @Test
    public void ntkComicEpisodeOpensViewer() throws Exception {
        launchNtkComicTitle();

        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        UiObject2 episodeRow = device.wait(Until.findObject(By.res(PACKAGE_NAME, "episode")), 60000L);
        if(episodeRow == null) {
            assertCaptchaShown(device, "NTK episode list");
            return;
        }
        assertNotNull("Expected NTK title to render at least one episode", episodeRow);

        episodeRow.click();

        assertReaderOpenedOrCaptchaShown(device, "NTK");
    }

    @Test
    public void ntkComicToolbarPreviousButtonSwitchesEpisode() throws Exception {
        launchNtkComicTitle();

        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        UiObject2 episodeRow = device.wait(Until.findObject(By.res(PACKAGE_NAME, "episode")), 60000L);
        if(episodeRow == null) {
            assertCaptchaShown(device, "NTK previous episode list");
            return;
        }
        assertNotNull("Expected NTK title to render at least one episode", episodeRow);

        episodeRow.click();
        assertReaderOpenedOrCaptchaShown(device, "NTK");
        if(device.findObject(By.res(PACKAGE_NAME, "captchaContainer")) != null) return;
        showReaderToolbar(device);

        UiObject2 toolbarTitle = device.wait(Until.findObject(By.res(PACKAGE_NAME, "toolbar_title")), 10000L);
        assertNotNull("Expected viewer toolbar title to render", toolbarTitle);
        String originalTitle = toolbarTitle.getText();

        UiObject2 previousButton = device.wait(Until.findObject(By.res(PACKAGE_NAME, "toolbar_previous")), 10000L);
        assertNotNull("Expected previous episode button to render", previousButton);
        previousButton.click();

        UiObject2 changedTitle = waitForToolbarTitleChange(device, originalTitle, 3000L);
        assertTrue("Expected previous episode button to switch the viewer episode",
                originalTitle == null || !originalTitle.equals(changedTitle.getText()));

        String previousTitle = changedTitle.getText();
        UiObject2 nextButton = device.wait(Until.findObject(By.res(PACKAGE_NAME, "toolbar_next")), 10000L);
        assertNotNull("Expected next episode button to render", nextButton);
        nextButton.click();

        UiObject2 restoredTitle = waitForToolbarTitleChange(device, previousTitle, 3000L);
        assertTrue("Expected next episode button to switch the viewer episode",
                previousTitle == null || !previousTitle.equals(restoredTitle.getText()));
        assertReaderOpened(device, "NTK previous episode");
    }

    @Test
    public void ntkComicViewerSurvivesFastScrollStress() throws Exception {
        launchNtkComicTitle();
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        UiObject2 episodeRow = waitForEpisodeRowThroughAutoCaptcha(device, "NTK scroll stress episode list");
        clickFreshEpisodeRow(device, episodeRow);
        assertReaderOpenedThroughAutoCaptcha(device, "NTK scroll stress");
        stressScrollViewer(device, "ntk");
    }

    @Test
    public void wfwfComicTitleOpensEpisodeList() throws Exception {
        launchWfwfComicTitle();

        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        UiObject2 episodeList = device.wait(Until.findObject(By.res(PACKAGE_NAME, "EpisodeList")), 60000L);
        assertNotNull("Expected WFWF episode list to render", episodeList);
        UiObject2 episodeRow = device.wait(Until.findObject(By.res(PACKAGE_NAME, "episode")), 60000L);
        assertNotNull("Expected WFWF title to render at least one episode", episodeRow);
    }

    @Test
    public void wfwfComicEpisodeOpensViewer() throws Exception {
        launchWfwfComicTitle();

        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        UiObject2 episodeRow = device.wait(Until.findObject(By.res(PACKAGE_NAME, "episode")), 60000L);
        assertNotNull("Expected WFWF title to render at least one episode", episodeRow);

        executeShell("logcat -c");
        episodeRow.click();

        assertReaderOpened(device, "WFWF");
        assertNoInitialVisibleCoverageGap("WFWF");
    }

    @Test
    public void wfwfQuickReadOpensStartEpisode() throws Exception {
        ActivityScenario<EpisodeActivity> scenario = launchWfwfSummertimeTitle();
        try {
            UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
            UiObject2 quickReadButton = device.wait(Until.findObject(By.res(PACKAGE_NAME, "HeaderFirst")), 60000L);
            assertNotNull("Expected WFWF quick read button to render", quickReadButton);

            AtomicReference<String> expectedStartEpisode = new AtomicReference<>("");
            scenario.onActivity(activity -> {
                List<Manga> episodes = readEpisodes(activity);
                if(episodes != null && episodes.size() > 0) {
                    Manga start = episodes.get(episodes.size() - 1);
                    expectedStartEpisode.set(start == null ? "" : start.getName());
                }
            });

            quickReadButton.click();

            assertReaderOpened(device, "WFWF quick read");
            showReaderToolbar(device);
            UiObject2 toolbarTitle = device.wait(Until.findObject(By.res(PACKAGE_NAME, "toolbar_title")), 10000L);
            assertNotNull("Expected WFWF quick read toolbar title", toolbarTitle);
            assertEquals("Quick read without a bookmark should open the readable start episode",
                    expectedStartEpisode.get(), toolbarTitle.getText());
        } finally {
            scenario.close();
        }
    }

    @Test
    public void wfwfComicViewerSurvivesFastScrollStress() throws Exception {
        ActivityScenario<EpisodeActivity> scenario = launchWfwfSummertimeTitle();
        try {
            UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
            UiObject2 episodeRow = device.wait(Until.findObject(By.res(PACKAGE_NAME, "episode")), 60000L);
            assertNotNull("Expected WFWF title to render at least one episode", episodeRow);
            episodeRow.click();
            assertReaderOpened(device, "WFWF scroll stress");
            stressScrollViewer(device, "wfwf");
        } finally {
            scenario.close();
        }
    }

    @Test
    public void wfwfJagaanEpisode40HasNoWideBlackImageGaps() throws Exception {
        Manga episode40 = openWfwfJagaanEpisode("40");
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        assertReaderOpened(device, "Jagaan 40");

        int width = device.getDisplayWidth();
        int height = device.getDisplayHeight();
        int x = width / 2;
        int top = Math.max(96, height / 7);
        int bottom = Math.min(height - 96, height * 6 / 7);
        BlackGapReport worst = BlackGapReport.none();
        for(int i = 0; i < 8; i++) {
            Thread.sleep(900L);
            File screenshot = new File(ApplicationProvider.getApplicationContext().getCacheDir(), "jagaan40_reader_" + i + ".png");
            assertTrue("Expected reader screenshot", device.takeScreenshot(screenshot));
            Bitmap bitmap = BitmapFactory.decodeFile(screenshot.getAbsolutePath());
            if(bitmap != null) {
                BlackGapReport report = findWideBlackGap(bitmap, 180, 96);
                Log.d("ViewerPerf", "jagaan40_gap_sample index=" + i + " " + report + " episode=" + episode40.getName());
                if(report.length > worst.length)
                    worst = report;
                bitmap.recycle();
            }
            device.swipe(x, bottom, x, top, 16);
        }
        assertTrue("Expected no wide black image gaps in Jagaan 40; worst=" + worst,
                worst.length < 32);
    }

    @Test
    public void wfwfJagaanEpisode42ShowsFailureStateForBrokenSourceImages() throws Exception {
        openWfwfJagaanEpisode("42");
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        assertReaderOpened(device, "Jagaan 42");

        Thread.sleep(1500L);
        File screenshot = new File(ApplicationProvider.getApplicationContext().getCacheDir(), "jagaan42_reader.png");
        assertTrue("Expected reader screenshot", device.takeScreenshot(screenshot));
        Bitmap bitmap = BitmapFactory.decodeFile(screenshot.getAbsolutePath());
        assertNotNull("Expected readable Jagaan 42 screenshot", bitmap);
        try {
            assertTrue("Expected Jagaan 42 to leave the blank/loading state", countNonBlankPixels(bitmap, 180, 96) > 1000);
        } finally {
            bitmap.recycle();
        }
    }

    @Test
    public void ntkJagaanEpisode42RendersReaderImage() throws Exception {
        openNtkJagaanEpisode("42");
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        assertReaderOpenedThroughAutoCaptcha(device, "NTK Jagaan 42");

        Thread.sleep(1500L);
        File screenshot = new File(ApplicationProvider.getApplicationContext().getCacheDir(), "ntk_jagaan42_reader.png");
        assertTrue("Expected NTK Jagaan 42 reader screenshot", device.takeScreenshot(screenshot));
        Bitmap bitmap = BitmapFactory.decodeFile(screenshot.getAbsolutePath());
        assertNotNull("Expected readable NTK Jagaan 42 screenshot", bitmap);
        try {
            assertTrue("Expected NTK Jagaan 42 to leave the blank/loading state",
                    countNonBlankPixels(bitmap, 180, 96) > 1000);
        } finally {
            bitmap.recycle();
        }
    }

    @Test
    public void ntkJagaanEpisode52RendersReaderImage() throws Exception {
        assertNtkJagaanEpisodeRendersReaderImage("52");
    }

    @Test
    public void ntkJagaanEpisode72RendersReaderImage() throws Exception {
        assertNtkJagaanEpisodeRendersReaderImage("72");
    }

    private void assertNtkJagaanEpisodeRendersReaderImage(String episodeNumber) throws Exception {
        openNtkJagaanEpisode(episodeNumber);
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        assertReaderOpenedThroughAutoCaptcha(device, "NTK Jagaan " + episodeNumber);

        Thread.sleep(1500L);
        File screenshot = new File(ApplicationProvider.getApplicationContext().getCacheDir(),
                "ntk_jagaan" + episodeNumber + "_reader.png");
        assertTrue("Expected NTK Jagaan " + episodeNumber + " reader screenshot", device.takeScreenshot(screenshot));
        Bitmap bitmap = BitmapFactory.decodeFile(screenshot.getAbsolutePath());
        assertNotNull("Expected readable NTK Jagaan " + episodeNumber + " screenshot", bitmap);
        try {
            assertTrue("Expected NTK Jagaan " + episodeNumber + " to leave the blank/loading state",
                    countNonBlankPixels(bitmap, 180, 96) > 1000);
        } finally {
            bitmap.recycle();
        }
    }

    private Manga openWfwfJagaanEpisode(String episodeNumber) throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        MainApplication.p.setSitePreset(CustomHttpClient.DEFAULT_COMIC_URL, CustomHttpClient.WEBTOON_URL);
        MainApplication.p.setBaseMode(MTitle.base_comic);

        Title title = findWfwfJagaanTitle();
        int status = title.fetchEps(MainApplication.getHttpClient());
        assertEquals("Expected Jagaan episodes to load", Title.LOAD_OK, status);
        List<Manga> episodes = Title.orderedEpisodeSnapshot(title.getEps());
        assertTrue("Expected Jagaan episode list", episodes != null && episodes.size() > 0);
        Manga targetEpisode = null;
        for(Manga episode : episodes) {
            String number = Manga.visibleEpisodeNumberKey(episode == null ? null : episode.getName());
            if(episodeNumber.equals(number)) {
                targetEpisode = episode;
                break;
            }
        }
        assertNotNull("Expected Jagaan episode " + episodeNumber + " in " + firstEpisodeNames(episodes, Math.min(8, episodes.size())), targetEpisode);
        targetEpisode.setTitle(title);
        targetEpisode.setTitleId(title.getId());
        MainApplication.p.resetViewerBookmark();
        MainApplication.p.setBookmark(title, -1);

        Utils.openViewerPrepared(context, targetEpisode, 0, false, true, false, title, true, true);
        return targetEpisode;
    }

    private Manga openNtkJagaanEpisode(String episodeNumber) throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        MainApplication.p.setNtkSitePreset(CustomHttpClient.NTK_COMIC_URL);
        MainApplication.p.setBaseMode(MTitle.base_comic);

        Title title = findNtkJagaanTitle();
        int status = title.fetchEps(MainApplication.getHttpClient());
        if(status == Title.LOAD_CAPTCHA) {
            openNtkCaptchaAndWaitForAutoVerification(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()), "NTK Jagaan episode list");
            status = title.fetchEps(MainApplication.getHttpClient());
        }
        assertEquals("Expected NTK Jagaan episodes to load", Title.LOAD_OK, status);
        List<Manga> episodes = Title.orderedEpisodeSnapshot(title.getEps());
        assertTrue("Expected NTK Jagaan episode list", episodes != null && episodes.size() > 0);
        Manga targetEpisode = null;
        for(Manga episode : episodes) {
            String number = Manga.visibleEpisodeNumberKey(episode == null ? null : episode.getName());
            if(episodeNumber.equals(number)) {
                targetEpisode = episode;
                break;
            }
        }
        assertNotNull("Expected NTK Jagaan episode " + episodeNumber + " in "
                + firstEpisodeNames(episodes, Math.min(8, episodes.size())), targetEpisode);
        targetEpisode.setTitle(title);
        targetEpisode.setTitleId(title.getId());
        MainApplication.p.resetViewerBookmark();
        MainApplication.p.setBookmark(title, -1);

        Utils.openViewerPrepared(context, targetEpisode, 0, false, true, false, title, true, true);
        return targetEpisode;
    }

    @Test
    public void wfwfActualViewerSavesAndRestoresScrolledPosition() throws Exception {
        ActivityScenario<EpisodeActivity> scenario = launchWfwfSummertimeTitle();
        try {
            UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
            UiObject2 episodeRow = device.wait(Until.findObject(By.res(PACKAGE_NAME, "episode")), 60000L);
            assertNotNull("Expected WFWF title to render at least one episode", episodeRow);

            AtomicReference<Manga> selectedRef = new AtomicReference<>();
            AtomicReference<Title> titleRef = new AtomicReference<>();
            scenario.onActivity(activity -> {
                List<Manga> episodes = readEpisodes(activity);
                Title title = readTitle(activity);
                assertTrue("Expected live WFWF episodes", episodes != null && episodes.size() > 0);
                Manga selected = episodes.get(0);
                selected.setTitle(title);
                selected.setTitleId(title.getId());
                selectedRef.set(selected);
                titleRef.set(title);
                MainApplication.p.resetViewerBookmark();
                MainApplication.p.setBookmark(title, -1);
                activity.openViewer(selected, 0, true);
            });

            assertReaderOpened(device, "actual WFWF resume save");
            ReaderV2Activity firstReader = waitForReaderActivity(10000L);
            assertNotNull("Expected actual WFWF reader activity", firstReader);

            ReaderSurfaceView.ProgressPosition moved = scrollActualReaderUntilProgressChanges(device, firstReader);
            assertTrue("Expected actual WFWF reader scroll to leave the initial position, page="
                            + moved.getPage() + " offset=" + moved.getOffset(),
                    moved.getPage() > 0 || moved.getOffset() != 0);

            Thread.sleep(1500L);
            Manga selected = selectedRef.get();
            Title title = titleRef.get();
            int savedPage = MainApplication.p.getViewerBookmark(selected);
            int savedOffset = MainApplication.p.getViewerBookmarkOffset(selected);
            assertTrue("Expected actual WFWF scroll position to be saved, page="
                            + savedPage + " offset=" + savedOffset,
                    savedPage > 0 || savedOffset != 0);
            assertEquals("Expected actual WFWF title bookmark to point at the read episode",
                    selected.getId(), MainApplication.p.getBookmark(title));

            device.pressBack();
            device.wait(Until.gone(By.res(PACKAGE_NAME, "strip")), 10000L);

            scenario.onActivity(activity -> activity.openViewer(selected, 0, false));
            assertReaderOpened(device, "actual WFWF resume restore");
            ReaderV2Activity resumedReader = waitForReaderActivity(10000L);
            assertNotNull("Expected actual WFWF resumed reader activity", resumedReader);
            ReaderSurfaceView.ProgressPosition restored = waitForReaderProgress(resumedReader, 10000L);
            assertTrue("Expected actual WFWF continue-read to restore saved position, savedPage="
                            + savedPage + " savedOffset=" + savedOffset
                            + " restoredPage=" + restored.getPage()
                            + " restoredOffset=" + restored.getOffset(),
                    restored.getPage() > 0 || Math.abs(restored.getOffset()) > 100);
        } finally {
            scenario.close();
            finishReaderActivities();
        }
    }

    @Test
    public void wfwfWebtoonSearchResultOpensEpisodeListAndViewer() throws Exception {
        launchSearchWebtoonTitle(false);

        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        UiObject2 episodeRow = device.wait(Until.findObject(By.res(PACKAGE_NAME, "episode")), 90000L);
        assertNotNull("Expected WFWF webtoon title to render at least one episode", episodeRow);

        episodeRow.click();

        assertReaderOpened(device, "WFWF webtoon");
    }

    @Test
    public void ntkWebtoonSearchResultOpensEpisodeListAndViewer() throws Exception {
        launchSearchWebtoonTitle(true);

        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        UiObject2 episodeRow = waitForEpisodeRowThroughAutoCaptcha(device, "NTK webtoon search episode list");
        clickFreshEpisodeRow(device, episodeRow);
        assertReaderOpenedThroughAutoCaptcha(device, "NTK webtoon");
    }

    @Test
    public void wfwfSummertimeEpisodeListMatchesViewerPickerOrder() throws Exception {
        ActivityScenario<EpisodeActivity> scenario = launchWfwfSummertimeTitle();
        try {
            UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
            UiObject2 episodeList = device.wait(Until.findObject(By.res(PACKAGE_NAME, "EpisodeList")), 60000L);
            assertNotNull("Expected Summertime Rendering episode list to render", episodeList);
            UiObject2 episodeRow = device.wait(Until.findObject(By.res(PACKAGE_NAME, "episode")), 60000L);
            assertNotNull("Expected Summertime Rendering to render at least one episode", episodeRow);

            AtomicReference<List<String>> screenOrderRef = new AtomicReference<>(Collections.emptyList());
            AtomicReference<List<String>> pickerOrderRef = new AtomicReference<>(Collections.emptyList());
            scenario.onActivity(activity -> {
                List<Manga> screenEpisodes = readEpisodes(activity);
                Title loadedTitle = readTitle(activity);
                screenOrderRef.set(firstEpisodeNames(screenEpisodes, 5));
                pickerOrderRef.set(firstEpisodeNames(Utils.snapshotEpisodes(loadedTitle), 5));
            });

            List<String> screenOrder = screenOrderRef.get();
            List<String> pickerOrder = pickerOrderRef.get();
            int count = Math.min(screenOrder.size(), pickerOrder.size());
            assertTrue("Expected several Summertime Rendering episodes to compare", count >= 3);
            assertEquals("Episode screen order should match viewer picker order",
                    screenOrder.subList(0, count), pickerOrder.subList(0, count));

            List<UiObject2> visibleRows = visibleEpisodeRows(device);
            assertTrue("Expected visible Summertime Rendering episode rows", visibleRows.size() >= 1);
            String selectedEpisodeName = visibleRows.get(0).getText();
            visibleRows.get(0).click();
            assertReaderOpened(device, "Summertime Rendering");
            showReaderToolbar(device);
            UiObject2 toolbarTitle = device.wait(Until.findObject(By.res(PACKAGE_NAME, "toolbar_title")), 10000L);
            assertNotNull("Expected viewer toolbar title to render", toolbarTitle);
            assertEquals("Viewer should open the same episode selected from the episode screen",
                    selectedEpisodeName, toolbarTitle.getText());

            UiObject2 episodeButton = device.wait(Until.findObject(By.res(PACKAGE_NAME, "toolbar_spinner")), 10000L);
            assertNotNull("Expected viewer episode picker button to render", episodeButton);
            episodeButton.click();
            UiObject2 pickerTitle = device.wait(Until.findObject(By.text("회차 선택")), 10000L);
            assertNotNull("Expected viewer episode picker to open", pickerTitle);
            UiObject2 pickerEpisode = device.wait(Until.findObject(By.text(selectedEpisodeName)), 5000L);
            assertNotNull("Expected viewer picker to show the selected episode", pickerEpisode);
        } finally {
            scenario.close();
        }
    }

    private void launchNtkComicTitle() {
        Context context = ApplicationProvider.getApplicationContext();
        MainApplication.p.setNtkSitePreset("https://sbxh1.com");
        MainApplication.p.setBaseMode(MTitle.base_comic);

        Title title = new Title(
                "천공 침범",
                "https://11toon8.com/data/toon_category/3540.webp",
                "",
                Collections.singletonList("스릴러"),
                "",
                3540,
                MTitle.base_comic);
        title.setSourceSite("ntk");

        Intent intent = new Intent(context, EpisodeActivity.class);
        intent.putExtra("title", Utils.toViewerTitleJson(title, true));
        intent.putExtra("online", true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        context.startActivity(intent);
    }

    private void launchWfwfComicTitle() {
        Context context = ApplicationProvider.getApplicationContext();
        MainApplication.p.setSitePreset(CustomHttpClient.DEFAULT_COMIC_URL, CustomHttpClient.WEBTOON_URL);
        MainApplication.p.setBaseMode(MTitle.base_comic);

        Title title = new Title(
                "wfwf comic smoke",
                "",
                "",
                Collections.singletonList("action"),
                "",
                18714,
                MTitle.base_comic);
        title.setSourceSite("wfwf");

        Intent intent = new Intent(context, EpisodeActivity.class);
        intent.putExtra("title", Utils.toViewerTitleJson(title, true));
        intent.putExtra("online", true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        context.startActivity(intent);
    }

    private void launchSearchWebtoonTitle(boolean ntk) throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        if(ntk)
            MainApplication.p.setNtkSitePreset(CustomHttpClient.NTK_WEBTOON_URL);
        else
            MainApplication.p.setSitePreset(CustomHttpClient.DEFAULT_COMIC_URL, CustomHttpClient.WEBTOON_URL);
        MainApplication.p.setBaseMode(MTitle.base_webtoon);

        Title title = findLiveWebtoonTitle(ntk);

        Intent intent = new Intent(context, EpisodeActivity.class);
        intent.putExtra("title", Utils.toViewerTitleJson(title, true));
        intent.putExtra("online", true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        context.startActivity(intent);
    }

    private Title findLiveWebtoonTitle(boolean ntk) throws Exception {
        String[] queries = {
                "서툰 연하남의 이상한 계약",
                "서툰 연하남",
                "나 혼자만 레벨업",
                "외모지상주의",
                "화산귀환",
                "마왕의 딸은 너무 착해"
        };
        boolean retriedAfterCaptcha = false;
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        for(String query : queries) {
            Title title = searchLiveWebtoonTitle(query, ntk);
            if(title != null)
                return title;
            if(ntk && !retriedAfterCaptcha) {
                retriedAfterCaptcha = true;
                openNtkCaptchaAndWaitForAutoVerification(device, "NTK webtoon search");
                title = searchLiveWebtoonTitle(query, true);
                if(title != null)
                    return title;
            }
        }
        throw new AssertionError("Expected live " + (ntk ? "NTK" : "WFWF") + " webtoon search to return a launchable title");
    }

    private Title searchLiveWebtoonTitle(String query, boolean ntk) throws Exception {
        Search search = new Search(query, 0, MTitle.base_webtoon);
        int status = search.fetch(MainApplication.getHttpClient());
        if(status != 0 || search.getResult() == null)
            return null;
        for(Title title : search.getResult()) {
            if(title == null || title.getId() <= 0)
                continue;
            if(title.getBaseMode() != MTitle.base_webtoon)
                continue;
            if(ntk && !"ntk".equals(title.getSourceSite()))
                continue;
            if(!ntk && !"wfwf".equals(title.getSourceSite()))
                continue;
            return title;
        }
        return null;
    }

    private static void openNtkCaptchaAndWaitForAutoVerification(UiDevice device, String label) throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        Intent intent = new Intent(context, CaptchaActivity.class);
        intent.putExtra("url", MainApplication.getHttpClient().getUrl());
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);

        long deadline = System.currentTimeMillis() + 180000L;
        boolean sawCaptcha = false;
        while(System.currentTimeMillis() < deadline) {
            if(MainApplication.getHttpClient().hasNtkAccessProof()) {
                finishCaptchaActivities();
                Thread.sleep(500L);
                return;
            }
            if(isCaptchaShown(device))
                sawCaptcha = true;
            Thread.sleep(500L);
        }
        fail("Expected " + label + " auto captcha verification to finish"
                + (sawCaptcha ? " after showing captcha" : ", but captcha screen was not observed"));
    }

    private ActivityScenario<EpisodeActivity> launchWfwfSummertimeTitle() {
        Context context = ApplicationProvider.getApplicationContext();
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
        MainApplication.p.setBookmark(title, -1);

        Intent intent = new Intent(context, EpisodeActivity.class);
        intent.putExtra("title", Utils.toViewerTitleJson(title, true));
        intent.putExtra("online", true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        return ActivityScenario.launch(intent);
    }

    private Title findWfwfJagaanTitle() throws Exception {
        String[] queries = {
                "쟈건",
                "자건",
                "Jagaan",
                "Jagan"
        };
        for(String query : queries) {
            Search search = new Search(query, 0, MTitle.base_comic);
            int status = search.fetch(MainApplication.getHttpClient());
            Log.d("ViewerPerf", "jagaan_search query=" + query + " status=" + status
                    + " count=" + (search.getResult() == null ? 0 : search.getResult().size()));
            if(status != 0 || search.getResult() == null)
                continue;
            for(Title title : search.getResult()) {
                if(title == null || title.getId() <= 0)
                    continue;
                Log.d("ViewerPerf", "jagaan_search_result query=" + query
                        + " id=" + title.getId()
                        + " baseMode=" + title.getBaseMode()
                        + " source=" + title.getSourceSite()
                        + " name=" + title.getName());
                if(title.getBaseMode() != MTitle.base_comic)
                    continue;
                if(!"wfwf".equals(title.getSourceSite()))
                    continue;
                String name = title.getName() == null ? "" : title.getName().toLowerCase(java.util.Locale.ROOT);
                if(name.contains("쟈건") || name.contains("자건") || name.contains("jag"))
                    return title;
            }
        }
        throw new AssertionError("Expected WFWF Jagaan title search result");
    }

    private Title findNtkJagaanTitle() throws Exception {
        String[] queries = {
                "쟈건",
                "자건",
                "Jagaan",
                "Jagan"
        };
        boolean retriedAfterCaptcha = false;
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        for(String query : queries) {
            Title title = searchLiveComicTitle(query, true);
            if(title != null)
                return title;
            if(!retriedAfterCaptcha) {
                retriedAfterCaptcha = true;
                openNtkCaptchaAndWaitForAutoVerification(device, "NTK Jagaan search");
                title = searchLiveComicTitle(query, true);
                if(title != null)
                    return title;
            }
        }
        throw new AssertionError("Expected NTK Jagaan title search result");
    }

    private Title searchLiveComicTitle(String query, boolean ntk) throws Exception {
        Search search = new Search(query, 0, MTitle.base_comic);
        int status = search.fetch(MainApplication.getHttpClient());
        Log.d("ViewerPerf", "jagaan_ntk_search query=" + query + " status=" + status
                + " count=" + (search.getResult() == null ? 0 : search.getResult().size()));
        if(status != 0 || search.getResult() == null)
            return null;
        for(Title title : search.getResult()) {
            if(title == null || title.getId() <= 0)
                continue;
            Log.d("ViewerPerf", "jagaan_ntk_search_result query=" + query
                    + " id=" + title.getId()
                    + " baseMode=" + title.getBaseMode()
                    + " source=" + title.getSourceSite()
                    + " name=" + title.getName());
            if(title.getBaseMode() != MTitle.base_comic)
                continue;
            if(ntk && !"ntk".equals(title.getSourceSite()))
                continue;
            if(!ntk && !"wfwf".equals(title.getSourceSite()))
                continue;
            String name = title.getName() == null ? "" : title.getName().toLowerCase(java.util.Locale.ROOT);
            if(name.contains("쟈건") || name.contains("자건") || name.contains("jag"))
                return title;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<Manga> readEpisodes(EpisodeActivity activity) {
        try {
            Field field = EpisodeActivity.class.getDeclaredField("episodes");
            field.setAccessible(true);
            Object value = field.get(activity);
            return value instanceof List ? (List<Manga>) value : Collections.emptyList();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static Title readTitle(EpisodeActivity activity) {
        try {
            Field field = EpisodeActivity.class.getDeclaredField("title");
            field.setAccessible(true);
            Object value = field.get(activity);
            return value instanceof Title ? (Title) value : null;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static List<String> firstEpisodeNames(List<Manga> episodes, int limit) {
        if(episodes == null || episodes.size() == 0)
            return Collections.emptyList();
        ArrayList<String> names = new ArrayList<>();
        for(Manga episode : episodes) {
            if(episode == null || episode.getName() == null || episode.getName().length() == 0)
                continue;
            names.add(episode.getName());
            if(names.size() >= limit)
                break;
        }
        return names;
    }

    private static List<UiObject2> visibleEpisodeRows(UiDevice device) {
        ArrayList<UiObject2> rows = new ArrayList<>(device.findObjects(By.res(PACKAGE_NAME, "episode")));
        for(int i = rows.size() - 1; i >= 0; i--) {
            UiObject2 row = rows.get(i);
            String text = row == null ? null : row.getText();
            if(text == null || text.length() == 0)
                rows.remove(i);
        }
        Collections.sort(rows, (left, right) -> {
            Rect leftBounds = left.getVisibleBounds();
            Rect rightBounds = right.getVisibleBounds();
            int topCompare = Integer.compare(leftBounds.top, rightBounds.top);
            if(topCompare != 0)
                return topCompare;
            return Integer.compare(leftBounds.left, rightBounds.left);
        });
        return rows;
    }

    private static void assertReaderOpened(UiDevice device, String label) {
        UiObject2 strip = device.wait(Until.findObject(By.res(PACKAGE_NAME, "strip")), 60000L);
        assertNotNull("Expected tapping a " + label + " episode to open the reader", strip);
        UiObject2 firstDrawable = device.wait(Until.findObject(By.desc("reader-drawable-ready")), 60000L);
        assertNotNull("Expected tapping a " + label + " episode to render the first reader image", firstDrawable);
    }

    private static void clickFreshEpisodeRow(UiDevice device, UiObject2 row) throws Exception {
        for(int attempt = 0; attempt < 3; attempt++) {
            UiObject2 target = attempt == 0 ? row : device.wait(Until.findObject(By.res(PACKAGE_NAME, "episode")), 5000L);
            assertNotNull("Expected NTK episode row to remain clickable", target);
            try {
                target.click();
                return;
            } catch(androidx.test.uiautomator.StaleObjectException e) {
                if(attempt == 2)
                    throw e;
                Thread.sleep(250L);
            }
        }
    }

    private static UiObject2 waitForEpisodeRowThroughAutoCaptcha(UiDevice device, String label) throws Exception {
        long deadline = System.currentTimeMillis() + 180000L;
        boolean sawCaptcha = false;
        while(System.currentTimeMillis() < deadline) {
            UiObject2 row = device.findObject(By.res(PACKAGE_NAME, "episode"));
            if(row != null)
                return row;
            if(isCaptchaShown(device))
                sawCaptcha = true;
            Thread.sleep(500L);
        }
        UiObject2 row = device.findObject(By.res(PACKAGE_NAME, "episode"));
        assertNotNull("Expected " + label + " to resolve after NTK auto captcha"
                + (sawCaptcha ? " flow" : ""), row);
        return row;
    }

    private static void assertReaderOpenedThroughAutoCaptcha(UiDevice device, String label) throws Exception {
        long deadline = System.currentTimeMillis() + 180000L;
        boolean sawCaptcha = false;
        while(System.currentTimeMillis() < deadline) {
            UiObject2 strip = device.findObject(By.res(PACKAGE_NAME, "strip"));
            if(strip != null) {
                UiObject2 firstDrawable = device.wait(Until.findObject(By.desc("reader-drawable-ready")), 60000L);
                assertNotNull("Expected tapping a " + label + " episode to render the first reader image", firstDrawable);
                return;
            }
            if(isCaptchaShown(device))
                sawCaptcha = true;
            Thread.sleep(500L);
        }
        fail("Expected tapping a " + label + " episode to open the reader after NTK auto captcha"
                + (sawCaptcha ? " flow" : ""));
    }

    private static void assertReaderOpenedOrCaptchaShown(UiDevice device, String label) {
        long deadline = System.currentTimeMillis() + 60000L;
        while(System.currentTimeMillis() < deadline) {
            UiObject2 strip = device.findObject(By.res(PACKAGE_NAME, "strip"));
            if(strip != null) {
                UiObject2 firstDrawable = device.wait(Until.findObject(By.desc("reader-drawable-ready")), 60000L);
                assertNotNull("Expected tapping a " + label + " episode to render the first reader image", firstDrawable);
                return;
            }
            UiObject2 captcha = device.findObject(By.res(PACKAGE_NAME, "captchaContainer"));
            if(captcha != null) {
                assertCaptchaShown(device, label);
                return;
            }
            try {
                Thread.sleep(250L);
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        UiObject2 strip = device.findObject(By.res(PACKAGE_NAME, "strip"));
        if(strip != null) {
            UiObject2 firstDrawable = device.wait(Until.findObject(By.desc("reader-drawable-ready")), 60000L);
            assertNotNull("Expected tapping a " + label + " episode to render the first reader image", firstDrawable);
            return;
        }
        assertCaptchaShown(device, label);
    }

    private static boolean isCaptchaShown(UiDevice device) {
        if(device.findObject(By.res(PACKAGE_NAME, "captchaContainer")) != null)
            return true;
        final boolean[] found = new boolean[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Collection<Activity> activities = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED);
            for(Activity activity : activities) {
                if(activity instanceof CaptchaActivity) {
                    found[0] = true;
                    return;
                }
            }
        });
        return found[0];
    }

    private static void finishCaptchaActivities() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Collection<Activity> activities = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED);
            for(Activity activity : activities) {
                if(activity instanceof CaptchaActivity)
                    activity.finish();
            }
        });
    }

    private static void finishReaderActivities() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Collection<Activity> activities = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED);
            for(Activity activity : activities) {
                if(activity instanceof ReaderV2Activity)
                    activity.finish();
            }
        });
    }

    private static ReaderV2Activity waitForReaderActivity(long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while(System.currentTimeMillis() < deadline) {
            AtomicReference<ReaderV2Activity> result = new AtomicReference<>();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                Collection<Activity> activities = ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(Stage.RESUMED);
                for(Activity activity : activities) {
                    if(activity instanceof ReaderV2Activity) {
                        result.set((ReaderV2Activity) activity);
                        return;
                    }
                }
            });
            if(result.get() != null)
                return result.get();
            Thread.sleep(100L);
        }
        return null;
    }

    private static ReaderSurfaceView.ProgressPosition scrollActualReaderUntilProgressChanges(
            UiDevice device, ReaderV2Activity reader) throws Exception {
        int width = device.getDisplayWidth();
        int height = device.getDisplayHeight();
        int x = width / 2;
        int fromY = Math.min(height - 160, height * 3 / 4);
        int toY = Math.max(120, height / 4);
        ReaderSurfaceView.ProgressPosition latest = waitForReaderProgress(reader, 10000L);
        for(int i = 0; i < 8; i++) {
            device.swipe(x, fromY, x, toY, 36);
            Thread.sleep(650L);
            latest = waitForReaderProgress(reader, 3000L);
            if(latest.getPage() > 0 || latest.getOffset() != 0)
                return latest;
        }
        return latest;
    }

    private static ReaderSurfaceView.ProgressPosition waitForReaderProgress(
            ReaderV2Activity reader, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        AtomicReference<ReaderSurfaceView.ProgressPosition> result = new AtomicReference<>();
        while(System.currentTimeMillis() < deadline) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                    result.set(reader.testCurrentProgressPosition()));
            if(result.get() != null)
                return result.get();
            Thread.sleep(100L);
        }
        return result.get();
    }

    private static void assertCaptchaShown(UiDevice device, String label) {
        UiObject2 captcha = device.wait(Until.findObject(By.res(PACKAGE_NAME, "captchaContainer")), 5000L);
        assertNotNull("Expected " + label + " to open the in-app captcha screen when NTK requires verification", captcha);
        assertEquals("Captcha must stay inside the app package", PACKAGE_NAME, device.getCurrentPackageName());
        boolean webViewVisible = waitForCaptchaView(R.id.captchaWebView, 5000L);
        boolean loadErrorVisible = waitForCaptchaView(R.id.infoText, 1000L);
        assertTrue("Expected captcha WebView or in-app NTK load error panel", webViewVisible || loadErrorVisible);
        assertTrue("Expected captcha reload action", waitForCaptchaView(R.id.captchaReload, 5000L));
        assertTrue("Expected captcha cookie check action", waitForCaptchaView(R.id.captchaCheckCookie, 5000L));
        assertTrue("Expected captcha close action", waitForCaptchaView(R.id.captchaClose, 5000L));
    }

    private static boolean waitForCaptchaView(int viewId, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while(System.currentTimeMillis() < deadline) {
            final boolean[] found = new boolean[1];
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                Collection<Activity> activities = ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(Stage.RESUMED);
                for(Activity activity : activities) {
                    if(activity instanceof CaptchaActivity) {
                        View view = activity.findViewById(viewId);
                        found[0] = view != null && view.getVisibility() == View.VISIBLE;
                        return;
                    }
                }
            });
            if(found[0])
                return true;
            try {
                Thread.sleep(100L);
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static UiObject2 waitForToolbarTitleChange(UiDevice device, String originalTitle, long timeoutMs) throws Exception {
        UiObject2 title = device.wait(Until.findObject(By.res(PACKAGE_NAME, "toolbar_title")), 5000L);
        assertNotNull("Expected viewer toolbar title after episode tap", title);
        long deadline = System.currentTimeMillis() + timeoutMs;
        while(originalTitle != null && originalTitle.equals(title.getText()) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50L);
            title = device.findObject(By.res(PACKAGE_NAME, "toolbar_title"));
            if(title == null) {
                showReaderToolbar(device);
                title = device.wait(Until.findObject(By.res(PACKAGE_NAME, "toolbar_title")), 500L);
            }
        }
        assertNotNull("Expected viewer toolbar title to remain visible", title);
        assertTrue("Expected toolbar title to change quickly after episode tap",
                originalTitle == null || !originalTitle.equals(title.getText()));
        return title;
    }

    private static void stressScrollViewer(UiDevice device, String source) throws Exception {
        int width = device.getDisplayWidth();
        int height = device.getDisplayHeight();
        int x = width / 2;
        int top = Math.max(96, height / 7);
        int middle = height / 2;
        int bottom = Math.min(height - 96, height * 6 / 7);

        executeShell("logcat -c");
        Log.d("ViewerPerf", "viewer_scroll_stress_start source=" + source);
        Thread.sleep(2500L);

        executeShell("input motionevent DOWN " + x + " " + middle);
        for(int i = 0; i < 24; i++) {
            int y = (i % 2 == 0) ? top : bottom;
            executeShell("input motionevent MOVE " + x + " " + y);
            Thread.sleep(18L);
        }
        executeShell("input motionevent UP " + x + " " + middle);

        for(int i = 0; i < 28; i++) {
            device.swipe(x, bottom, x, top, 4);
            Thread.sleep(35L);
        }
        for(int i = 0; i < 12; i++) {
            device.swipe(x, top, x, bottom, 4);
            Thread.sleep(35L);
        }
        device.waitForIdle(5000L);
        Thread.sleep(1500L);
        assertReaderOpened(device, source + " scroll stress");
        SurfaceJankMetrics metrics = readSurfaceJankMetrics(source);
        assertTrue("Expected enough reader surface frame samples during " + source + " scroll stress: " + metrics.rawLine,
                metrics.samples >= 8);
        assertEquals("Expected zero reader render frame drops during " + source + " scroll stress: " + metrics.rawLine,
                0, metrics.droppedFrames);
        assertEquals("Expected zero reader render frame debt during " + source + " scroll stress: " + metrics.rawLine,
                0, metrics.droppedFrameDebt);
        Log.d("ViewerPerf", "viewer_scroll_stress_end source=" + source);
    }

    private static void assertNoInitialVisibleCoverageGap(String source) throws Exception {
        long deadline = System.currentTimeMillis() + 5000L;
        String latestOutput = "";
        while(System.currentTimeMillis() < deadline) {
            latestOutput = executeShellOutput("logcat -d -s ReaderSurfaceStats:I *:S");
            String loadingLine = firstLineContaining(latestOutput, "reader_visible_loading=");
            String coverageLine = firstLineContaining(latestOutput, "reader_visible_coverage");
            if(loadingLine != null && coverageLine != null) {
                assertEquals("Expected no visible loading on initial " + source + " reader frame: " + loadingLine,
                        0, parseMetricInt(loadingLine, "reader_visible_loading", -1));
                assertEquals("Expected no placeholder pixels on initial " + source + " reader frame: " + coverageLine,
                        0, parseMetricInt(coverageLine, "placeholderPx", -1));
                assertEquals("Expected no missing pixels on initial " + source + " reader frame: " + coverageLine,
                        0, parseMetricInt(coverageLine, "missingPx", -1));
                assertTrue("Expected drawable pixels on initial " + source + " reader frame: " + coverageLine,
                        parseMetricInt(coverageLine, "drawablePx", 0) > 0);
                return;
            }
            Thread.sleep(100L);
        }
        assertTrue("Expected initial reader coverage metrics for " + source + ": " + latestOutput, false);
    }

    private static String firstLineContaining(String text, String needle) {
        for(String line : text.split("\\R")) {
            if(line.contains(needle))
                return line;
        }
        return null;
    }

    private static int parseMetricInt(String line, String key, int defaultValue) {
        String prefix = key + "=";
        int start = line.indexOf(prefix);
        if(start < 0)
            return defaultValue;
        start += prefix.length();
        int end = start;
        while(end < line.length() && line.charAt(end) >= '0' && line.charAt(end) <= '9')
            end++;
        if(end == start)
            return defaultValue;
        return Integer.parseInt(line.substring(start, end));
    }

    private static BlackGapReport findWideBlackGap(Bitmap bitmap) {
        return findWideBlackGap(bitmap, 0, 0);
    }

    private static BlackGapReport findWideBlackGap(Bitmap bitmap, int ignoredBottomPx) {
        return findWideBlackGap(bitmap, 0, ignoredBottomPx);
    }

    private static BlackGapReport findWideBlackGap(Bitmap bitmap, int ignoredTopPx, int ignoredBottomPx) {
        int width = bitmap.getWidth();
        int top = Math.max(0, ignoredTopPx);
        int height = Math.max(0, bitmap.getHeight() - Math.max(0, ignoredBottomPx));
        int start = -1;
        BlackGapReport worst = BlackGapReport.none();
        for(int y = top; y < height; y++) {
            boolean black = isWideBlackRow(bitmap, y, width);
            if(black) {
                if(start < 0)
                    start = y;
            } else if(start >= 0) {
                if(y - start > worst.length)
                    worst = new BlackGapReport(start, y - 1, y - start);
                start = -1;
            }
        }
        if(start >= 0 && height - start > worst.length)
            worst = new BlackGapReport(start, height - 1, height - start);
        return worst;
    }

    private static boolean isWideBlackRow(Bitmap bitmap, int y, int width) {
        int samples = 48;
        int black = 0;
        for(int i = 0; i < samples; i++) {
            int x = Math.round((width - 1) * (i / (float)(samples - 1)));
            int pixel = bitmap.getPixel(x, y);
            if(Color.red(pixel) < 24 && Color.green(pixel) < 24 && Color.blue(pixel) < 24)
                black++;
        }
        return black >= samples - 1;
    }

    private static int countNonBlankPixels(Bitmap bitmap, int ignoredTopPx, int ignoredBottomPx) {
        int width = bitmap.getWidth();
        int top = Math.max(0, ignoredTopPx);
        int bottom = Math.max(top, bitmap.getHeight() - Math.max(0, ignoredBottomPx));
        int step = 4;
        int count = 0;
        for(int y = top; y < bottom; y += step) {
            for(int x = 0; x < width; x += step) {
                int pixel = bitmap.getPixel(x, y);
                int red = Color.red(pixel);
                int green = Color.green(pixel);
                int blue = Color.blue(pixel);
                boolean nearWhite = red > 246 && green > 246 && blue > 246;
                boolean nearBlack = red < 16 && green < 16 && blue < 16;
                if(!nearWhite && !nearBlack)
                    count++;
            }
        }
        return count * step * step;
    }

    private static final class BlackGapReport {
        final int start;
        final int end;
        final int length;

        BlackGapReport(int start, int end, int length) {
            this.start = start;
            this.end = end;
            this.length = length;
        }

        static BlackGapReport none() {
            return new BlackGapReport(-1, -1, 0);
        }

        @Override
        public String toString() {
            return "start=" + start + " end=" + end + " length=" + length;
        }
    }

    private static SurfaceJankMetrics readSurfaceJankMetrics(String source) throws Exception {
        String output = executeShellOutput("logcat -d -s ReaderSurfaceStats:I *:S");
        SurfaceJankMetrics aggregate = new SurfaceJankMetrics("aggregate", Collections.<String, String>emptyMap());
        int sessions = 0;
        for(String line : output.split("\\R")) {
            if(!line.contains("surface_jank_v3"))
                continue;
            aggregate = aggregate.plus(SurfaceJankMetrics.parse(line));
            sessions++;
        }
        assertTrue("Expected ReaderSurfaceStats surface_jank_v3 metrics during " + source + " scroll stress", sessions > 0);
        Log.d("ViewerPerf", "viewer_scroll_jank_assert source=" + source
                + " sessions=" + sessions
                + " samples=" + aggregate.samples
                + " missedIntervals=" + aggregate.missedIntervals
                + " missedFrames=" + aggregate.missedFrames
                + " droppedFrames=" + aggregate.droppedFrames
                + " droppedFrameDebt=" + aggregate.droppedFrameDebt
                + " strictOverBudget=" + aggregate.strictOverBudget
                + " totalP95Max=" + aggregate.totalP95
                + " raw=" + aggregate.rawLine);
        return aggregate;
    }

    private static void executeShell(String command) throws Exception {
        executeShellOutput(command);
    }

    private static String executeShellOutput(String command) throws Exception {
        ParcelFileDescriptor descriptor =
                InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command);
        try(FileInputStream input = new FileInputStream(descriptor.getFileDescriptor());
            ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int read;
            while((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static final class SurfaceJankMetrics {
        final String rawLine;
        final int samples;
        final int strictOverBudget;
        final int missedIntervals;
        final int missedFrames;
        final int droppedFrames;
        final int droppedFrameDebt;
        final float totalP95;

        private SurfaceJankMetrics(String rawLine, Map<String, String> values) {
            this.rawLine = rawLine;
            samples = parseInt(values, "samples", 0);
            strictOverBudget = parseInt(values, "strictOverBudget", 0);
            missedIntervals = parseInt(values, "missedIntervals", 0);
            missedFrames = parseInt(values, "missedFrames", 0);
            droppedFrames = parseInt(values, "droppedFrames", 0);
            droppedFrameDebt = parseInt(values, "droppedFrameDebt", 0);
            totalP95 = parseFloat(values, "totalP95", 0f);
        }

        private SurfaceJankMetrics(
                String rawLine,
                int samples,
                int strictOverBudget,
                int missedIntervals,
                int missedFrames,
                int droppedFrames,
                int droppedFrameDebt,
                float totalP95) {
            this.rawLine = rawLine;
            this.samples = samples;
            this.strictOverBudget = strictOverBudget;
            this.missedIntervals = missedIntervals;
            this.missedFrames = missedFrames;
            this.droppedFrames = droppedFrames;
            this.droppedFrameDebt = droppedFrameDebt;
            this.totalP95 = totalP95;
        }

        static SurfaceJankMetrics parse(String line) {
            Map<String, String> values = new HashMap<>();
            for(String token : line.split("\\s+")) {
                int separator = token.indexOf('=');
                if(separator <= 0 || separator == token.length() - 1)
                    continue;
                values.put(token.substring(0, separator), token.substring(separator + 1));
            }
            return new SurfaceJankMetrics(line, values);
        }

        SurfaceJankMetrics plus(SurfaceJankMetrics other) {
            return new SurfaceJankMetrics(
                    rawLine + "\n" + other.rawLine,
                    samples + other.samples,
                    strictOverBudget + other.strictOverBudget,
                    missedIntervals + other.missedIntervals,
                    missedFrames + other.missedFrames,
                    droppedFrames + other.droppedFrames,
                    droppedFrameDebt + other.droppedFrameDebt,
                    Math.max(totalP95, other.totalP95));
        }

        private static int parseInt(Map<String, String> values, String key, int defaultValue) {
            String value = values.get(key);
            if(value == null)
                return defaultValue;
            return Integer.parseInt(value);
        }

        private static float parseFloat(Map<String, String> values, String key, float defaultValue) {
            String value = values.get(key);
            if(value == null)
                return defaultValue;
            return Float.parseFloat(value);
        }
    }

    private static void showReaderToolbar(UiDevice device) {
        UiObject2 toolbar = device.findObject(By.res(PACKAGE_NAME, "viewerToolbar"));
        if(toolbar != null)
            return;
        UiObject2 strip = device.findObject(By.res(PACKAGE_NAME, "strip"));
        assertNotNull("Expected reader surface to render before showing toolbar", strip);
        Rect bounds = strip.getVisibleBounds();
        for(int i = 0; i < 5 && toolbar == null; i++) {
            device.click(bounds.centerX(), bounds.centerY());
            toolbar = device.wait(Until.findObject(By.res(PACKAGE_NAME, "viewerToolbar")), 2000L);
            if(toolbar == null && device.findObject(By.res(PACKAGE_NAME, "toolbar_title")) != null)
                toolbar = device.findObject(By.res(PACKAGE_NAME, "viewerToolbar"));
        }
        assertNotNull("Expected reader toolbar to show after tapping the reader surface", toolbar);
    }
}
