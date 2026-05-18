package ml.melun.mangaview;

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import ml.melun.mangaview.activity.EpisodeActivity;
import ml.melun.mangaview.mangaview.MTitle;
import ml.melun.mangaview.mangaview.Title;

@RunWith(AndroidJUnit4.class)
public class EpisodeActivityNetworkTest {
    @Test
    public void ntkComicTitleOpensEpisodeList() throws Exception {
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

        try(ActivityScenario<EpisodeActivity> scenario = ActivityScenario.launch(intent)) {
            waitForEpisodeList(scenario);
            AtomicInteger itemCount = new AtomicInteger();
            scenario.onActivity(activity -> {
                RecyclerView list = activity.findViewById(R.id.EpisodeList);
                RecyclerView.Adapter<?> adapter = list == null ? null : list.getAdapter();
                itemCount.set(adapter == null ? 0 : adapter.getItemCount());
            });
            assertTrue("Expected NTK title to render header plus episodes", itemCount.get() > 1);
        }
    }

    private static void waitForEpisodeList(ActivityScenario<EpisodeActivity> scenario) throws Exception {
        long deadline = System.currentTimeMillis() + 30000L;
        while(System.currentTimeMillis() < deadline) {
            AtomicBoolean ready = new AtomicBoolean(false);
            scenario.onActivity(activity -> {
                RecyclerView list = activity.findViewById(R.id.EpisodeList);
                RecyclerView.Adapter<?> adapter = list == null ? null : list.getAdapter();
                ready.set(!activity.isFinishing() && adapter != null && adapter.getItemCount() > 1);
            });
            if(ready.get())
                return;
            Thread.sleep(500L);
        }
    }
}
