package ml.melun.mangaview.activity;

import org.junit.Test;

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
}
