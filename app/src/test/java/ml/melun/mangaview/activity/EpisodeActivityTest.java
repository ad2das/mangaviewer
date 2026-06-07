package ml.melun.mangaview.activity;

import org.junit.Test;

import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.List;

import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;

import static ml.melun.mangaview.mangaview.MTitle.base_comic;
import static ml.melun.mangaview.mangaview.MTitle.base_webtoon;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class EpisodeActivityTest {
    @Test
    public void ntkDirectWarmupRequiresNtkModeAndEpisodePath() {
        assertTrue(EpisodeActivity.shouldDirectWarmupNtkViewerPageForTest(true, false, "/manhwa/1/episode"));
        assertTrue(EpisodeActivity.shouldDirectWarmupNtkViewerPageForTest(false, true, "/webtoon/1/episode"));
        assertFalse(EpisodeActivity.shouldDirectWarmupNtkViewerPageForTest(false, false, "/manhwa/1/episode"));
        assertFalse(EpisodeActivity.shouldDirectWarmupNtkViewerPageForTest(true, false, ""));
        assertFalse(EpisodeActivity.shouldDirectWarmupNtkViewerPageForTest(true, false, null));
    }

    @Test
    public void ntkFirstFramePreloadRunsOnlyAfterDirectPageWarmup() {
        assertTrue(EpisodeActivity.shouldPreloadNtkFirstFrameAfterDirectWarmupForTest(true));
        assertFalse(EpisodeActivity.shouldPreloadNtkFirstFrameAfterDirectWarmupForTest(false));
    }

    @Test
    public void quickReadWithoutBookmarkStartsFromFirstEpisode() {
        List<Manga> episodes = new ArrayList<>();
        episodes.add(new Manga(122, "122", "", base_webtoon));
        episodes.add(new Manga(121, "121", "", base_webtoon));
        episodes.add(new Manga(1, "1", "", base_webtoon));

        assertEquals(2, EpisodeActivity.firstReadableEpisodeIndexForTest(episodes));
    }

    @Test
    public void selectedEpisodeProgressUsesAdapterPositionBeforeViewerLaunch() {
        List<Manga> episodes = new ArrayList<>();
        episodes.add(new Manga(101, "Episode 10", "", base_webtoon));
        episodes.add(new Manga(102, "Episode 9", "", base_webtoon));

        Manga selected = new Manga(999, "Different id", "", base_webtoon);

        assertEquals(2, EpisodeActivity.selectedEpisodeIndexForProgressForTest(2, selected, episodes));
    }

    @Test
    public void selectedEpisodeProgressFallsBackToPathWhenPositionMissing() {
        List<Manga> episodes = new ArrayList<>();
        Manga first = new Manga(101, "Episode 10", "", base_webtoon);
        first.setNtkEpisodePath("/webtoon/1/10");
        Manga second = new Manga(102, "Episode 9", "", base_webtoon);
        second.setNtkEpisodePath("/webtoon/1/9");
        episodes.add(first);
        episodes.add(second);

        Manga selected = new Manga(999, "Different id", "", base_webtoon);
        selected.setNtkEpisodePath("/webtoon/1/9");

        assertEquals(2, EpisodeActivity.selectedEpisodeIndexForProgressForTest(-1, selected, episodes));
    }

    @Test
    public void diskEpisodeCacheLoadsOnlyAfterMemoryMiss() {
        assertFalse(EpisodeActivity.shouldLoadDiskEpisodeCacheAsyncForTest(true));
        assertTrue(EpisodeActivity.shouldLoadDiskEpisodeCacheAsyncForTest(false));
        assertTrue(EpisodeActivity.episodeRefreshAfterCacheProbeMsForTest() <= 200L);
    }

    @Test
    public void mediumMemoryCacheSnapshotsParseOnMainThreadForInstantEpisodeList() {
        assertTrue(EpisodeActivity.shouldParseMemoryCacheOnMainForTest(16 * 1024));
        assertTrue(EpisodeActivity.shouldParseMemoryCacheOnMainForTest(64 * 1024));
        assertFalse(EpisodeActivity.shouldParseMemoryCacheOnMainForTest(64 * 1024 + 1));
        assertFalse(EpisodeActivity.shouldParseMemoryCacheOnMainForTest(0));
    }

    @Test
    public void episodeCacheSnapshotDoesNotSerializeRecursiveEpisodes() {
        Title title = new Title("Sky", "", "", null, "", 42, base_webtoon);
        List<Manga> episodes = new ArrayList<>();
        Manga first = new Manga(1, "1", "today", base_webtoon);
        first.setTitle(title);
        first.setTitleId(title.getId());
        first.setEps(episodes);
        first.setNtkEpisodePath("/webtoon/42/1");
        episodes.add(first);
        title.setEps(episodes);

        ArrayList<Manga> snapshot = EpisodeActivity.episodeCacheSnapshotForTest(episodes);
        String json = new Gson().toJson(snapshot);

        assertTrue(json.contains("/webtoon/42/1"));
        assertNull(snapshot.get(0).getTitle());
        assertTrue(snapshot.get(0).getEps() == null || snapshot.get(0).getEps().isEmpty());
    }

    @Test
    public void episodeScreenNormalizesVisibleEpisodeOrderBeforeRendering() {
        List<Manga> episodes = new ArrayList<>();
        episodes.add(new Manga(25, "서머타임 렌더링 23화", "", base_comic));
        episodes.add(new Manga(24, "서머타임 렌더링 24화", "", base_comic));
        episodes.add(new Manga(23, "서머타임 렌더링 22화", "", base_comic));

        ArrayList<Manga> normalized = EpisodeActivity.normalizeEpisodeSnapshotForTest(episodes);

        assertEquals("서머타임 렌더링 24화", normalized.get(0).getName());
        assertEquals("서머타임 렌더링 23화", normalized.get(1).getName());
        assertEquals("서머타임 렌더링 22화", normalized.get(2).getName());
    }

    @Test
    public void episodeCacheSnapshotUsesVisibleEpisodeOrder() {
        List<Manga> episodes = new ArrayList<>();
        episodes.add(new Manga(25, "서머타임 렌더링 23화", "", base_comic));
        episodes.add(new Manga(24, "서머타임 렌더링 24화", "", base_comic));
        episodes.add(new Manga(23, "서머타임 렌더링 22화", "", base_comic));

        ArrayList<Manga> snapshot = EpisodeActivity.episodeCacheSnapshotForTest(episodes);

        assertEquals("서머타임 렌더링 24화", snapshot.get(0).getName());
        assertEquals("서머타임 렌더링 23화", snapshot.get(1).getName());
        assertEquals("서머타임 렌더링 22화", snapshot.get(2).getName());
    }

    @Test
    public void malformedIntentTitleDoesNotCrashEpisodeScreen() {
        assertNull(EpisodeActivity.parseIntentTitleForTest("{name:broken"));

        Title title = EpisodeActivity.parseIntentTitleForTest("{\"name\":\"Sky\",\"id\":10994,\"baseMode\":1}");
        assertEquals("Sky", title.getName());
    }

    @Test
    public void viewerSourceSwitchResultRequiresValidReplacementTitle() {
        Title title = new Title("서머타임 렌더링", "", "", null, "", 7843, base_comic);
        title.setSourceSite("ntk");
        String titleJson = new Gson().toJson(title);

        assertTrue(EpisodeActivity.shouldSwitchEpisodeListForViewerResultForTest(true, titleJson));
        assertFalse(EpisodeActivity.shouldSwitchEpisodeListForViewerResultForTest(false, titleJson));
        assertFalse(EpisodeActivity.shouldSwitchEpisodeListForViewerResultForTest(true, "{name:broken"));
        assertFalse(EpisodeActivity.shouldSwitchEpisodeListForViewerResultForTest(true, ""));
    }

    @Test
    public void sameEpisodeIdentityListAllowsSkippingDuplicateRefreshRender() {
        List<Manga> cached = new ArrayList<>();
        Manga cachedFirst = new Manga(1, "1", "", base_webtoon);
        cachedFirst.setNtkEpisodePath("/manhwa/10/1");
        cached.add(cachedFirst);

        List<Manga> fresh = new ArrayList<>();
        Manga freshFirst = new Manga(1, "updated", "", base_webtoon);
        freshFirst.setNtkEpisodePath("/manhwa/10/1");
        fresh.add(freshFirst);

        assertTrue(EpisodeActivity.sameEpisodeIdentityList(cached, fresh));

        freshFirst.setNtkEpisodePath("/manhwa/10/2");
        assertFalse(EpisodeActivity.sameEpisodeIdentityList(cached, fresh));
    }

    @Test
    public void compatibleEpisodeCacheMatchesTitlePrefix() {
        List<Manga> episodes = new ArrayList<>();
        episodes.add(new Manga(256, "Sky Invasion 258", "", base_webtoon));
        episodes.add(new Manga(255, "Sky Invasion 257", "", base_webtoon));

        assertTrue(EpisodeActivity.cachedEpisodeTitleMatchScoreForTest("Sky Invasion", episodes) > 0);
        assertEquals(0, EpisodeActivity.cachedEpisodeTitleMatchScoreForTest("Other Title", episodes));
    }

    @Test
    public void compatibleEpisodeCacheDoesNotCrossKnownSources() {
        assertTrue(EpisodeActivity.isCompatibleCacheSourceForTest("ntk", "ntk"));
        assertFalse(EpisodeActivity.isCompatibleCacheSourceForTest("ntk", "wfwf"));
        assertTrue(EpisodeActivity.isCompatibleCacheSourceForTest("", "ntk"));
    }
}
