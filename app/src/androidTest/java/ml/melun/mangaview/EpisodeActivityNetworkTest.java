package ml.melun.mangaview;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import org.junit.Test;
import org.junit.Before;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import ml.melun.mangaview.activity.EpisodeActivity;
import ml.melun.mangaview.mangaview.CustomHttpClient;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.MTitle;
import ml.melun.mangaview.mangaview.Search;
import ml.melun.mangaview.mangaview.Title;

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
        assertNotNull("Expected NTK episode list to render", episodeList);
        UiObject2 episodeRow = device.wait(Until.findObject(By.res(PACKAGE_NAME, "episode")), 60000L);
        assertNotNull("Expected NTK title to render at least one episode", episodeRow);
    }

    @Test
    public void ntkComicEpisodeOpensViewer() throws Exception {
        launchNtkComicTitle();

        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        UiObject2 episodeRow = device.wait(Until.findObject(By.res(PACKAGE_NAME, "episode")), 60000L);
        assertNotNull("Expected NTK title to render at least one episode", episodeRow);

        episodeRow.click();

        assertReaderOpened(device, "NTK");
    }

    @Test
    public void ntkComicToolbarPreviousButtonSwitchesEpisode() throws Exception {
        launchNtkComicTitle();

        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        UiObject2 episodeRow = device.wait(Until.findObject(By.res(PACKAGE_NAME, "episode")), 60000L);
        assertNotNull("Expected NTK title to render at least one episode", episodeRow);

        episodeRow.click();
        assertReaderOpened(device, "NTK");
        showReaderToolbar(device);

        UiObject2 toolbarTitle = device.wait(Until.findObject(By.res(PACKAGE_NAME, "toolbar_title")), 10000L);
        assertNotNull("Expected viewer toolbar title to render", toolbarTitle);
        String originalTitle = toolbarTitle.getText();

        UiObject2 previousButton = device.wait(Until.findObject(By.res(PACKAGE_NAME, "toolbar_previous")), 10000L);
        assertNotNull("Expected previous episode button to render", previousButton);
        previousButton.click();

        UiObject2 changedTitle = device.wait(Until.findObject(By.res(PACKAGE_NAME, "toolbar_title")), 20000L);
        assertNotNull("Expected viewer toolbar title after previous episode tap", changedTitle);
        long deadline = System.currentTimeMillis() + 20000L;
        while(originalTitle != null && originalTitle.equals(changedTitle.getText()) && System.currentTimeMillis() < deadline) {
            Thread.sleep(250L);
            changedTitle = device.findObject(By.res(PACKAGE_NAME, "toolbar_title"));
            if(changedTitle == null) {
                showReaderToolbar(device);
                changedTitle = device.wait(Until.findObject(By.res(PACKAGE_NAME, "toolbar_title")), 3000L);
            }
        }
        assertNotNull("Expected viewer toolbar title to remain visible", changedTitle);
        assertTrue("Expected previous episode button to switch the viewer episode",
                originalTitle == null || !originalTitle.equals(changedTitle.getText()));
        assertReaderOpened(device, "NTK previous episode");
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

        episodeRow.click();

        assertReaderOpened(device, "WFWF");
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
        UiObject2 episodeRow = device.wait(Until.findObject(By.res(PACKAGE_NAME, "episode")), 90000L);
        assertNotNull("Expected NTK webtoon title to render at least one episode", episodeRow);

        episodeRow.click();

        assertReaderOpened(device, "NTK webtoon");
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
        MainApplication.p.setSitePreset("https://wfwf452.com/cm", "https://wfwf452.com");
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
                "나 혼자만 레벨업",
                "외모지상주의",
                "화산귀환",
                "마왕의 딸은 너무 착해"
        };
        for(String query : queries) {
            Search search = new Search(query, 0, MTitle.base_webtoon);
            int status = search.fetch(MainApplication.getHttpClient());
            if(status != 0 || search.getResult() == null)
                continue;
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
        }
        throw new AssertionError("Expected live " + (ntk ? "NTK" : "WFWF") + " webtoon search to return a launchable title");
    }

    private ActivityScenario<EpisodeActivity> launchWfwfSummertimeTitle() {
        Context context = ApplicationProvider.getApplicationContext();
        MainApplication.p.setSitePreset("https://wfwf453.com/cm", "https://wfwf453.com");
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

        Intent intent = new Intent(context, EpisodeActivity.class);
        intent.putExtra("title", Utils.toViewerTitleJson(title, true));
        intent.putExtra("online", true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        return ActivityScenario.launch(intent);
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
