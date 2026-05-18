package ml.melun.mangaview;

import static org.junit.Assert.assertNotNull;

import android.content.Context;
import android.content.Intent;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;

import ml.melun.mangaview.activity.EpisodeActivity;
import ml.melun.mangaview.mangaview.MTitle;
import ml.melun.mangaview.mangaview.Title;

@RunWith(AndroidJUnit4.class)
public class EpisodeActivityNetworkTest {
    private static final String PACKAGE_NAME = "ml.melun.mangaview";

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

        UiObject2 viewerToolbar = device.wait(Until.findObject(By.res(PACKAGE_NAME, "viewerToolbar")), 60000L);
        assertNotNull("Expected tapping an NTK episode to open the viewer", viewerToolbar);
        UiObject2 strip = device.wait(Until.findObject(By.res(PACKAGE_NAME, "strip")), 60000L);
        assertNotNull("Expected NTK viewer content strip to render", strip);
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
}
