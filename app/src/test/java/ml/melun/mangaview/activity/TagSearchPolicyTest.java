package ml.melun.mangaview.activity;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TagSearchPolicyTest {
    @Test
    public void searchFailureCaptchaPolicyUsesResultAndChallengeState() {
        assertFalse(TagSearchPolicy.shouldOpenCaptchaAfterSearchFailure(0, null, true));
        assertFalse(TagSearchPolicy.shouldOpenCaptchaAfterSearchFailure(1, null, false));
        assertTrue(TagSearchPolicy.shouldOpenCaptchaAfterSearchFailure(1, null, true));
    }

    @Test
    public void episodeSnapshotPrefetchStaysOnCurrentSourceFamily() {
        assertTrue(TagSearchPolicy.shouldPrefetchEpisodeSnapshot(null, true));
        assertTrue(TagSearchPolicy.shouldPrefetchEpisodeSnapshot("", false));
        assertTrue(TagSearchPolicy.shouldPrefetchEpisodeSnapshot("ntk", true));
        assertFalse(TagSearchPolicy.shouldPrefetchEpisodeSnapshot("ntk", false));
        assertFalse(TagSearchPolicy.shouldPrefetchEpisodeSnapshot("wfwf", true));
        assertTrue(TagSearchPolicy.shouldPrefetchEpisodeSnapshot("wfwf", false));
        assertFalse(TagSearchPolicy.shouldPrefetchEpisodeSnapshot("wolf123", true));
        assertTrue(TagSearchPolicy.shouldPrefetchEpisodeSnapshot("custom", true));
    }
}
