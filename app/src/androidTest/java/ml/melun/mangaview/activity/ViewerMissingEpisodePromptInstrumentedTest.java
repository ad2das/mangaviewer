package ml.melun.mangaview.activity;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import ml.melun.mangaview.MainApplication;
import ml.melun.mangaview.R;
import ml.melun.mangaview.Utils;
import ml.melun.mangaview.mangaview.MTitle;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;
import ml.melun.mangaview.repository.MangaRepository;

import static ml.melun.mangaview.mangaview.Title.LOAD_OK;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class ViewerMissingEpisodePromptInstrumentedTest {
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

    private Title fetchWfwfSummertimeRendering() {
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

        int result = MangaRepository.fetchEpisodesForeground(title);
        assertEquals("Expected WFWF Summertime Rendering episodes to load", LOAD_OK, result);
        return title;
    }

    private Intent viewerIntent(Manga episode, Title title) {
        Context context = ApplicationProvider.getApplicationContext();
        Intent intent = new Intent(context, ViewerActivity.class);
        intent.putExtra("online", true);
        intent.putExtra("title", Utils.toViewerTitleJson(title, true));
        intent.putExtra("manga", Utils.toViewerMangaJson(episode, title));
        intent.putExtra(ViewerActivity.EXTRA_EXACT_EPISODE, true);
        intent.putExtra(ViewerActivity.EXTRA_START_AT_FIRST_PAGE, true);
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

    private static boolean waitForToolbarTitle(Activity activity, String expectedText, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while(SystemClock.elapsedRealtime() < deadline) {
            final String[] text = new String[1];
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                TextView title = activity.findViewById(R.id.toolbar_title);
                text[0] = title == null || title.getText() == null ? "" : title.getText().toString();
            });
            if(text[0] != null && text[0].contains(expectedText))
                return true;
            SystemClock.sleep(500);
        }
        return false;
    }
}
