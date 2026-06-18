package ml.melun.mangaview.activity;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class EpisodeWarmupPolicyTest {
    @Test
    public void ntkDirectWarmupIsDisabledOnEpisodeList() {
        assertFalse(EpisodeWarmupPolicy.shouldDirectWarmupNtkViewerPage(true, false, "/manhwa/1/2"));
        assertFalse(EpisodeWarmupPolicy.shouldDirectWarmupNtkViewerPage(false, true, "/webtoon/1/2"));
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
