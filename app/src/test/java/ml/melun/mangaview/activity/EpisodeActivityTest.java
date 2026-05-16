package ml.melun.mangaview.activity;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import ml.melun.mangaview.mangaview.Manga;

import static ml.melun.mangaview.mangaview.MTitle.base_webtoon;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
}
