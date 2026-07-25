package ml.melun.mangaview.mangaview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.os.SystemClock;
import android.util.Log;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import ml.melun.mangaview.MainApplication;

public class NtkSearchEpisodeListInstrumentedTest {
    private static final String TAG = "ViewerPerf";

    @Test
    public void queryReturnsFetchableEpisodeList() throws Exception {
        String query = InstrumentationRegistry.getArguments().getString("ntkQuery", "둘째에게");
        String expectedName = InstrumentationRegistry.getArguments().getString("ntkExpectedName", query);
        String requestedBaseMode = InstrumentationRegistry.getArguments()
                .getString("ntkBaseMode", "webtoon");
        int baseMode = "comic".equalsIgnoreCase(requestedBaseMode)
                || "manhwa".equalsIgnoreCase(requestedBaseMode)
                ? MTitle.base_comic
                : MTitle.base_webtoon;
        boolean allowEmptyEpisodes = Boolean.parseBoolean(
                InstrumentationRegistry.getArguments().getString("ntkAllowEmptyEpisodes", "false"));
        boolean dumpRsc = Boolean.parseBoolean(
                InstrumentationRegistry.getArguments().getString("ntkDumpRsc", "false"));
        boolean requireEpisodeOne = Boolean.parseBoolean(
                InstrumentationRegistry.getArguments().getString("ntkRequireEpisodeOne", "false"));
        String siteRoot = InstrumentationRegistry.getArguments()
                .getString("ntkSiteRoot", CustomHttpClient.NTK_WEBTOON_URL);
        MainApplication.p.setNtkSitePreset(siteRoot);
        MainApplication.p.setBaseMode(baseMode);
        MainApplication.getHttpClient().clearPageCache();
        Search.clearNtkResultCaches();

        long searchStarted = SystemClock.elapsedRealtime();
        Search search = new Search(query, 0, baseMode);
        int searchStatus = search.fetch(MainApplication.getHttpClient());
        long searchMs = SystemClock.elapsedRealtime() - searchStarted;
        int resultCount = search.getResult() == null ? 0 : search.getResult().size();
        Log.d(TAG, "ntk_query_episode_search status=" + searchStatus
                + ",ms=" + searchMs
                + ",query=" + query
                + ",baseMode=" + baseMode
                + ",results=" + resultCount);
        assertEquals(0, searchStatus);
        assertNotNull(search.getResult());
        assertTrue("Expected NTK search results for " + query, resultCount > 0);

        Title target = null;
        for(Title title : search.getResult()) {
            if(title == null || !"ntk".equals(title.getSourceSite()))
                continue;
            String name = title.getName() == null ? "" : title.getName();
            Log.d(TAG, "ntk_query_episode_candidate id=" + title.getId()
                    + ",name=" + name
                    + ",path=" + title.getPath()
                    + ",baseMode=" + title.getBaseMode());
            if(target == null)
                target = title;
            if(name.contains(expectedName)) {
                target = title;
                break;
            }
        }
        assertNotNull("Expected NTK webtoon title for " + expectedName, target);
        if(dumpRsc)
            dumpRscPayload(target);

        long epsStarted = SystemClock.elapsedRealtime();
        int epsStatus = target.fetchEps(MainApplication.getHttpClient());
        long epsMs = SystemClock.elapsedRealtime() - epsStarted;
        int epsCount = target.getEps() == null ? 0 : target.getEps().size();
        boolean containsEpisodeOne = false;
        String firstEpisode = "";
        String lastEpisode = "";
        if(target.getEps() != null && !target.getEps().isEmpty()) {
            Manga first = target.getEps().get(0);
            Manga last = target.getEps().get(target.getEps().size() - 1);
            firstEpisode = first == null ? "" : first.getName() + "|" + first.getNtkEpisodePath();
            lastEpisode = last == null ? "" : last.getName() + "|" + last.getNtkEpisodePath();
            for(Manga episode : target.getEps()) {
                if(episode != null && "1".equals(Manga.visibleEpisodeNumberKey(episode.getName()))) {
                    containsEpisodeOne = true;
                    break;
                }
            }
        }
        Log.d(TAG, "ntk_query_episode_fetch status=" + epsStatus
                + ",ms=" + epsMs
                + ",titleId=" + target.getId()
                + ",name=" + target.getName()
                + ",path=" + target.getPath()
                + ",eps=" + epsCount
                + ",containsEpisodeOne=" + containsEpisodeOne
                + ",first=" + firstEpisode
                + ",last=" + lastEpisode
                + ",confirmedEmpty=" + target.isNtkEpisodeListConfirmedEmpty());
        assertEquals(Title.LOAD_OK, epsStatus);
        assertNotNull(target.getEps());
        if(!allowEmptyEpisodes && !target.isNtkEpisodeListConfirmedEmpty())
            assertTrue("Expected fetched episodes for " + target.getName(), epsCount > 0);
        if(epsCount == 0)
            assertTrue("Expected empty NTK episode list to be confirmed for " + target.getName(),
                    target.isNtkEpisodeListConfirmedEmpty());
        if(requireEpisodeOne)
            assertTrue("Expected 1화 in fetched episodes for " + target.getName(),
                    containsEpisodeOne);
    }

    private static void dumpRscPayload(Title target) {
        try {
            CustomHttpClient.PageResponse rsc = MainApplication.getHttpClient().mgetNtkRscPage(target.getPath(), 0);
            String body = rsc == null || rsc.body == null ? "" : rsc.body;
            File dump = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),
                    "ntk-title-rsc-dump.txt");
            try(FileOutputStream output = new FileOutputStream(dump, false)) {
                output.write(body.getBytes(StandardCharsets.UTF_8));
            }
            Log.d(TAG, "ntk_query_episode_rsc_dump path=" + target.getPath()
                    + ",code=" + (rsc == null ? 0 : rsc.code)
                    + ",bodyLen=" + body.length()
                    + ",file=" + dump.getAbsolutePath());
        } catch(Exception e) {
            Log.d(TAG, "ntk_query_episode_rsc_dump_failed path=" + target.getPath() + "," + e);
        }
    }
}
