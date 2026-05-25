package ml.melun.mangaview.activity;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class EpisodeWarmupPolicyTest {
    @Test
    public void visibleLimitPreservesDataSaverAndAggressiveBudgets() {
        assertEquals(1, EpisodeWarmupPolicy.visibleLimit(true, false, false));
        assertEquals(4, EpisodeWarmupPolicy.visibleLimit(false, false, false));
        assertEquals(5, EpisodeWarmupPolicy.visibleLimit(false, true, false));
        assertEquals(4, EpisodeWarmupPolicy.visibleLimit(false, false, true));
        assertEquals(5, EpisodeWarmupPolicy.visibleLimit(false, true, true));
    }

    @Test
    public void ntkDirectWarmupRequiresNtkContextAndConcretePath() {
        assertTrue(EpisodeWarmupPolicy.shouldDirectWarmupNtkViewerPage(true, false, "/manhwa/1/2"));
        assertTrue(EpisodeWarmupPolicy.shouldDirectWarmupNtkViewerPage(false, true, "/webtoon/1/2"));
        assertFalse(EpisodeWarmupPolicy.shouldDirectWarmupNtkViewerPage(false, false, "/manhwa/1/2"));
        assertFalse(EpisodeWarmupPolicy.shouldDirectWarmupNtkViewerPage(true, false, ""));
        assertFalse(EpisodeWarmupPolicy.shouldDirectWarmupNtkViewerPage(true, false, null));
    }

    @Test
    public void firstFramePreloadFollowsDirectWarmupResult() {
        assertTrue(EpisodeWarmupPolicy.shouldPreloadNtkFirstFrameAfterDirectWarmup(true));
        assertFalse(EpisodeWarmupPolicy.shouldPreloadNtkFirstFrameAfterDirectWarmup(false));
    }
}
