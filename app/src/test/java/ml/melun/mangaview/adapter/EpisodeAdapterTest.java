package ml.melun.mangaview.adapter;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import ml.melun.mangaview.mangaview.MTitle;
import ml.melun.mangaview.mangaview.Manga;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class EpisodeAdapterTest {
    @Test
    public void validEpisodePositionRejectsHeaderAndOutOfRangeRows() {
        assertFalse(EpisodeAdapter.isValidEpisodePositionForTest(Arrays.asList("a", "b"), 0));
        assertFalse(EpisodeAdapter.isValidEpisodePositionForTest(Arrays.asList("a", "b"), 3));
    }

    @Test
    public void validEpisodePositionRejectsMissingData() {
        assertFalse(EpisodeAdapter.isValidEpisodePositionForTest(null, 1));
        assertFalse(EpisodeAdapter.isValidEpisodePositionForTest(Collections.emptyList(), 1));
    }

    @Test
    public void validEpisodePositionAcceptsExistingEpisodeRow() {
        assertTrue(EpisodeAdapter.isValidEpisodePositionForTest(Arrays.asList("a", "b"), 2));
    }

    @Test
    public void deferredThumbnailClearSkipsRowAndPlaceholderStates() {
        assertFalse(EpisodeAdapter.shouldClearThumbnailBeforeDeferredBindForTest("loaded-key", false));
        assertFalse(EpisodeAdapter.shouldClearThumbnailBeforeDeferredBindForTest("pending:loaded-key", true));
        assertFalse(EpisodeAdapter.shouldClearThumbnailBeforeDeferredBindForTest("placeholder", true));
        assertFalse(EpisodeAdapter.shouldClearThumbnailBeforeDeferredBindForTest("empty", true));
        assertFalse(EpisodeAdapter.shouldClearThumbnailBeforeDeferredBindForTest(null, true));
    }

    @Test
    public void deferredThumbnailClearKeepsHeaderReplacementClean() {
        assertTrue(EpisodeAdapter.shouldClearThumbnailBeforeDeferredBindForTest("loaded-key", true));
    }

    @Test
    public void ntkDisplayReleaseUsesVisibleLatestEpisodeNameWhenApiNumberIsInternal() {
        List<Manga> episodes = Collections.singletonList(new Manga(1293, "1183화", "26.05.22", MTitle.base_comic));

        assertEquals("1183화", EpisodeAdapter.displayReleaseForNtkForTest("ntk", "1293화", episodes));
    }
}
