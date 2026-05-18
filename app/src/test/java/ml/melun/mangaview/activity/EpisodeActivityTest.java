package ml.melun.mangaview.activity;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;

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
    public void visibleEpisodeWarmupLimitWarmsTapTargetsWhenDataSaverIsOff() {
        assertEquals(1, EpisodeActivity.visibleEpisodeWarmupLimitForTest(true, false));
        assertEquals(1, EpisodeActivity.visibleEpisodeWarmupLimitForTest(false, false));
        assertEquals(1, EpisodeActivity.visibleEpisodeWarmupLimitForTest(false, true));
        assertEquals(1, EpisodeActivity.visibleEpisodeWarmupLimitForTest(false, true, true));
    }

    @Test
    public void visibleEpisodeWarmupStartsSoonAfterContent() {
        assertTrue(EpisodeActivity.initialVisibleEpisodeWarmupDelayMsForTest() <= 300L);
        assertTrue(EpisodeActivity.visibleEpisodeWarmupIdleDelayMsForTest() <= 250L);
    }

    @Test
    public void ntkVisibleEpisodeWarmupWaitsForFirstFrameWarmup() {
        assertEquals(260L, EpisodeActivity.initialVisibleEpisodeWarmupDelayMsForTest(false));
        assertTrue(EpisodeActivity.initialVisibleEpisodeWarmupDelayMsForTest(true) >= 650L);
    }

    @Test
    public void diskEpisodeCacheLoadsOnlyAfterMemoryMiss() {
        assertFalse(EpisodeActivity.shouldLoadDiskEpisodeCacheAsyncForTest(true));
        assertTrue(EpisodeActivity.shouldLoadDiskEpisodeCacheAsyncForTest(false));
    }

    @Test
    public void malformedIntentTitleDoesNotCrashEpisodeScreen() {
        assertNull(EpisodeActivity.parseIntentTitleForTest("{name:broken"));

        Title title = EpisodeActivity.parseIntentTitleForTest("{\"name\":\"Sky\",\"id\":10994,\"baseMode\":1}");
        assertEquals("Sky", title.getName());
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
}
