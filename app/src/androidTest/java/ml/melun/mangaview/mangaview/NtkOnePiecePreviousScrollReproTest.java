package ml.melun.mangaview.mangaview;

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

import ml.melun.mangaview.LiveNetworkAssume;
import ml.melun.mangaview.MainApplication;
import ml.melun.mangaview.Utils;
import ml.melun.mangaview.activity.EpisodeActivity;

@RunWith(AndroidJUnit4.class)
public class NtkOnePiecePreviousScrollReproTest {
    private static final String PACKAGE_NAME = "ml.melun.mangaview";

    @Test
    public void scrollOnlyPreviousEpisodeFiveTimes() throws Exception {
        LiveNetworkAssume.assumeEnabled();
        launchOnePieceEpisodes();

        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        UiObject2 episodeRow = device.wait(Until.findObject(By.res(PACKAGE_NAME, "episode")), 90000L);
        assertNotNull("Expected NTK One Piece latest episode row", episodeRow);
        episodeRow.click();

        device.waitForIdle(90000L);
        Thread.sleep(5000L);

        int width = device.getDisplayWidth();
        int height = device.getDisplayHeight();
        int x = width / 2;
        int fromY = Math.max(120, height / 4);
        int toY = Math.min(height - 160, height * 3 / 4);

        for (int round = 0; round < 5; round++) {
            for (int swipe = 0; swipe < 18; swipe++) {
                device.swipe(x, fromY, x, toY, 36);
                Thread.sleep(420L);
            }
            Thread.sleep(3000L);
        }
    }

    private void launchOnePieceEpisodes() {
        Context context = ApplicationProvider.getApplicationContext();
        MainApplication.p.setNtkSitePreset("https://sbxh1.com");
        MainApplication.p.setBaseMode(MTitle.base_comic);

        Title title = new Title(
                "원피스(ONE PIECE)",
                "https://11toon8.com/data/toon_category/2.webp",
                "",
                Collections.singletonList("애니화"),
                "",
                2,
                MTitle.base_comic);
        title.setSourceSite("ntk");

        Intent intent = new Intent(context, EpisodeActivity.class);
        intent.putExtra("title", Utils.toViewerTitleJson(title, true));
        intent.putExtra("online", true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
}
