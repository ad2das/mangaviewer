package ml.melun.mangaview.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;

import ml.melun.mangaview.LiveNetworkAssume;
import ml.melun.mangaview.MainApplication;
import ml.melun.mangaview.R;
import ml.melun.mangaview.Utils;
import ml.melun.mangaview.mangaview.CustomHttpClient;
import ml.melun.mangaview.mangaview.MTitle;
import ml.melun.mangaview.mangaview.Search;
import ml.melun.mangaview.mangaview.Title;

public class NtkEmptyEpisodeActivityInstrumentedTest {
    private static final String TAG = "ViewerPerf";

    @Test
    public void confirmedEmptyEpisodeListRendersWithoutErrorFallback() throws Exception {
        LiveNetworkAssume.assumeEnabled();
        Context context = ApplicationProvider.getApplicationContext();
        forceNtkWebtoonMode();
        MainApplication.getHttpClient().clearPageCache();
        Search.clearNtkResultCaches();

        Title title = new Title("둘째에게", "", "고태호", null, "", 845711, MTitle.base_webtoon);
        title.setSourceSite("ntk");
        title.setPath("/webtoon/845711");

        Intent intent = new Intent(context, EpisodeActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra("title", Utils.toViewerTitleJson(title, false));
        intent.putExtra("online", true);

        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        EpisodeActivity activity = (EpisodeActivity) instrumentation.startActivitySync(intent);
        try {
            long started = SystemClock.elapsedRealtime();
            boolean rendered = waitForHeaderOnlyEpisodeList(instrumentation, activity, 15000L);
            long elapsed = SystemClock.elapsedRealtime() - started;
            Log.d(TAG, "ntk_empty_episode_activity rendered=" + rendered
                    + ",ms=" + elapsed
                    + ",confirmedEmpty=" + (activity.title != null && activity.title.isNtkEpisodeListConfirmedEmpty())
                    + ",adapterItems=" + adapterItemCount(activity));
            assertTrue("Expected confirmed empty NTK episode list to render", rendered);
            assertNotNull(activity.title);
            assertTrue(activity.title.isNtkEpisodeListConfirmedEmpty());
            assertNotNull(activity.episodeAdapter);
            assertEquals(2, activity.episodeAdapter.getItemCount());
            RecyclerView list = activity.findViewById(R.id.EpisodeList);
            assertNotNull(list);
            assertNotNull(list.getAdapter());
            assertEquals(2, list.getAdapter().getItemCount());
        } finally {
            activity.finish();
        }
    }

    @Test
    public void searchedDuljjaeEpisodeListRendersWithoutErrorFallback() throws Exception {
        LiveNetworkAssume.assumeEnabled();
        Context context = ApplicationProvider.getApplicationContext();
        forceNtkWebtoonMode();
        MainApplication.getHttpClient().clearPageCache();
        Search.clearNtkResultCaches();

        Search search = new Search("\uB458\uC9F8\uC5D0\uAC8C", 0, MTitle.base_webtoon);
        int status = search.fetch(MainApplication.getHttpClient());
        Log.d(TAG, "ntk_duljjae_search status=" + status
                + ",count=" + (search.getResult() == null ? 0 : search.getResult().size()));
        assertEquals(0, status);
        assertNotNull(search.getResult());
        assertTrue("Expected NTK search result for \uB458\uC9F8\uC5D0\uAC8C", search.getResult().size() > 0);

        Title title = null;
        for(Title result : search.getResult()) {
            Log.d(TAG, "ntk_duljjae_search_result id=" + result.getId()
                    + ",name=" + result.getName()
                    + ",path=" + result.getPath()
                    + ",url=" + result.getUrl()
                    + ",source=" + result.getSourceSite());
            if(result.getName() != null && result.getName().contains("\uB458\uC9F8\uC5D0\uAC8C")) {
                title = result;
                break;
            }
        }
        assertNotNull("Expected matching NTK title for \uB458\uC9F8\uC5D0\uAC8C", title);

        Intent intent = new Intent(context, EpisodeActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra("title", Utils.toViewerTitleJson(title, false));
        intent.putExtra("online", true);

        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        EpisodeActivity activity = (EpisodeActivity) instrumentation.startActivitySync(intent);
        try {
            long started = SystemClock.elapsedRealtime();
            boolean rendered = waitForRenderedEpisodeList(instrumentation, activity, 20000L);
            long elapsed = SystemClock.elapsedRealtime() - started;
            Log.d(TAG, "ntk_duljjae_search_episode rendered=" + rendered
                    + ",ms=" + elapsed
                    + ",confirmedEmpty=" + (activity.title != null && activity.title.isNtkEpisodeListConfirmedEmpty())
                    + ",adapterItems=" + adapterItemCount(activity));
            assertTrue("Expected searched NTK episode list to render without error fallback", rendered);
            assertTrue("Expected confirmed empty NTK episode message to be visible",
                    waitForVisibleText(instrumentation, activity, "\uD68C\uCC28\uAC00 \uC544\uC9C1 \uC5C6\uC2B5\uB2C8\uB2E4", 5000L));
        } finally {
            activity.finish();
        }
    }

    private static boolean waitForHeaderOnlyEpisodeList(Instrumentation instrumentation,
                                                        EpisodeActivity activity,
                                                        long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while(SystemClock.elapsedRealtime() < deadline) {
            final boolean[] rendered = {false};
            instrumentation.runOnMainSync(() -> rendered[0] = activity != null
                    && activity.title != null
                    && activity.title.isNtkEpisodeListConfirmedEmpty()
                    && activity.episodeAdapter != null
                    && activity.episodeAdapter.getItemCount() == 2
                    && activity.findViewById(R.id.EpisodeList) instanceof RecyclerView
                    && ((RecyclerView) activity.findViewById(R.id.EpisodeList)).getAdapter() != null
                    && ((RecyclerView) activity.findViewById(R.id.EpisodeList)).getAdapter().getItemCount() == 2);
            if(rendered[0])
                return true;
            SystemClock.sleep(100L);
        }
        return false;
    }

    private static boolean waitForRenderedEpisodeList(Instrumentation instrumentation,
                                                      EpisodeActivity activity,
                                                      long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while(SystemClock.elapsedRealtime() < deadline) {
            final boolean[] rendered = {false};
            instrumentation.runOnMainSync(() -> rendered[0] = activity != null
                    && activity.episodeAdapter != null
                    && activity.episodeAdapter.getItemCount() >= 2
                    && activity.findViewById(R.id.EpisodeList) instanceof RecyclerView
                    && ((RecyclerView) activity.findViewById(R.id.EpisodeList)).getAdapter() != null
                    && ((RecyclerView) activity.findViewById(R.id.EpisodeList)).getAdapter().getItemCount() >= 2);
            if(rendered[0])
                return true;
            SystemClock.sleep(100L);
        }
        return false;
    }

    private static int adapterItemCount(EpisodeActivity activity) {
        final int[] count = {-1};
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            count[0] = activity != null && activity.episodeAdapter != null
                    ? activity.episodeAdapter.getItemCount()
                    : -1;
        });
        return count[0];
    }

    private static boolean waitForVisibleText(Instrumentation instrumentation,
                                              EpisodeActivity activity,
                                              String expected,
                                              long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while(SystemClock.elapsedRealtime() < deadline) {
            final boolean[] found = {false};
            instrumentation.runOnMainSync(() -> {
                View root = activity == null ? null : activity.getWindow().getDecorView();
                found[0] = containsVisibleText(root, expected);
            });
            if(found[0])
                return true;
            SystemClock.sleep(100L);
        }
        return false;
    }

    private static boolean containsVisibleText(View view, String expected) {
        if(view == null || view.getVisibility() != View.VISIBLE)
            return false;
        if(view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if(text != null && text.toString().contains(expected))
                return true;
        }
        if(view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for(int i = 0; i < group.getChildCount(); i++) {
                if(containsVisibleText(group.getChildAt(i), expected))
                    return true;
            }
        }
        return false;
    }

    private static void forceNtkWebtoonMode() {
        String siteRoot = InstrumentationRegistry.getArguments()
                .getString("ntkSiteRoot", CustomHttpClient.NTK_WEBTOON_URL);
        MainApplication.p.setNtkSitePreset(siteRoot);
        MainApplication.p.setBaseMode(MTitle.base_webtoon);
    }
}
